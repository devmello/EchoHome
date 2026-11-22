package app.vesper.feature.home.home

import app.vesper.core.model.HaConnection
import app.vesper.core.model.HaEntity
import app.vesper.core.model.HaException
import app.vesper.core.model.HomeAssistant
import app.vesper.core.model.SettingsStore
import app.vesper.feature.home.HomeSettings
import app.vesper.feature.home.ha.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Home screen state + actions, with optimistic UI:
 *  - each action patches the entity locally at once ([optimistic]), then calls the service;
 *  - an [HaException] rolls the patch back and shows [HomeUi.error] for a few seconds;
 *  - otherwise the patch stays until HA reports a newer state for that entity (or 5 s pass).
 *
 * [start] runs while the Home screen is attached; [stop] cancels everything.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class HomeController(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** How long a failed action's message stays. */
    private val errorMs: Long = 3500,
    /** Thermostat +/- taps are sent once they pause this long. */
    private val thermostatDebounceMs: Long = 700,
    private val overrideTtlMs: Long = 5000,
    /** Slot defaults when settings have none (the demo home's scene order). */
    private val defaults: (areaId: String, slot: Slot) -> List<String>? = { _, _ -> null },
) {
    private class Patch(val entity: HaEntity, val base: HaEntity?, val until: Long)

    private val registry = MutableStateFlow<Registry?>(null)
    private val patches = MutableStateFlow<Map<String, Patch>>(emptyMap())
    private val selected = MutableStateFlow(settings.getString(HomeSettings.SELECTED_ROOM, ""))
    private val error = MutableStateFlow<String?>(null)
    /** Bumped when a "home.room…" setting changes, to re-run the mapping. */
    private val settingsVersion = MutableStateFlow(0)
    private val _ui = MutableStateFlow<HomeUi?>(null)
    val ui: StateFlow<HomeUi?> = _ui.asStateFlow()

    private var jobs = ArrayList<Job>()
    private val pendingThermostat = HashMap<String, Job>()
    private var errorJob: Job? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            // (Re)load the registries on every connect and when HA says they changed.
            ha.connection.filter { it == HaConnection.CONNECTED }.collect { loadRegistry() }
        }
        jobs += scope.launch {
            merge(
                ha.subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "area_registry_updated")),
                ha.subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "entity_registry_updated")),
                ha.subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "device_registry_updated")),
            ).debounce(2000).collect { loadRegistry() }
        }
        jobs += scope.launch {
            val selection = combine(selected, error, settingsVersion) { sel, err, _ -> sel to err }
            combine(ha.entities, registry, patches, selection) { ents, reg, p, (sel, err) ->
                val effective = applyPatches(ents, p)
                val rooms = RoomMapper.rooms(effective, reg ?: Registry.EMPTY, ::override, HomeSettings.list(settings, HomeSettings.ROOMS).orEmpty())
                HomePresenter.present(rooms, sel, effective, err)
            }.collect { _ui.value = it }
        }
        jobs += scope.launch {
            // Settings changes (slot overrides, room order) re-run the mapping.
            settings.changes.filter { it.startsWith("home.room") }.collect { settingsVersion.value++ }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        pendingThermostat.values.forEach { it.cancel() }
        pendingThermostat.clear()
    }

    private suspend fun loadRegistry() {
        try { registry.value = Registry.load(ha) } catch (_: HaException) { /* keep the last one; retried on reconnect */ }
    }

    private fun override(areaId: String, slot: Slot): List<String>? =
        HomeSettings.list(settings, HomeSettings.slotKey(areaId, slot)) ?: defaults(areaId, slot)

    /** Drops patches HA has overtaken (a newer state object arrived) or that expired. */
    private fun applyPatches(ents: Map<String, HaEntity>, p: Map<String, Patch>): Map<String, HaEntity> {
        if (p.isEmpty()) return ents
        val now = System.currentTimeMillis()
        val live = p.filter { (id, patch) -> ents[id] === patch.base && patch.until > now }
        if (live.size != p.size) scope.launch { patches.value = patches.value.filterKeys { it in live } }
        if (live.isEmpty()) return ents
        val out = HashMap(ents)
        live.forEach { (id, patch) -> out[id] = patch.entity }
        return out
    }

    private fun current(id: String): HaEntity? = patches.value[id]?.entity ?: ha.entities.value[id]

    private fun optimistic(id: String, state: String? = null, attrs: (JSONObject) -> Unit = {}) {
        val now = current(id) ?: return
        val a = JSONObject(now.attributes.toString()).also(attrs)
        val base = patches.value[id]?.base ?: ha.entities.value[id]
        val until = System.currentTimeMillis() + overrideTtlMs
        patches.value = patches.value + (id to Patch(now.copy(state = state ?: now.state, attributes = a), base, until))
        scope.launch {
            // HA accepted the call but never reported a change: fall back to its state.
            delay(overrideTtlMs + 50)
            val p = patches.value[id]
            if (p != null && p.until <= System.currentTimeMillis()) patches.value = patches.value - id
        }
    }

    private fun rollback(id: String, what: String, e: HaException) {
        patches.value = patches.value - id
        showError(if (e.code == "not_connected" || e.code == "disconnected") "Couldn't reach Home Assistant" else "Couldn't change $what")
    }

    private fun showError(msg: String) {
        error.value = msg
        errorJob?.cancel()
        errorJob = scope.launch { delay(errorMs); error.value = null }
    }

    private fun call(id: String, what: String, domain: String, service: String, data: JSONObject? = null) {
        scope.launch {
            try { ha.callService(domain, service, id, data) }
            catch (e: HaException) { rollback(id, what, e) }
        }
    }

    // ---- actions ----

    fun selectRoom(id: String) {
        selected.value = id
        settings.putString(HomeSettings.SELECTED_ROOM, id)
    }

    /** +/- on the thermostat: moves the target by one step now, sends after a pause. */
    fun nudgeTarget(entityId: String, steps: Int) {
        val e = current(entityId) ?: return
        val t = ThermostatTile.from(e)
        val target = ((t.target ?: t.current ?: return) + steps * t.step).coerceIn(t.min, t.max)
        optimistic(entityId) { it.put(t.targetKey, target) }
        pendingThermostat.remove(entityId)?.cancel()
        pendingThermostat[entityId] = scope.launch {
            delay(thermostatDebounceMs)
            pendingThermostat.remove(entityId)
            val data = JSONObject().put(t.targetKey, target)
            if (t.targetKey != "temperature") {
                // heat_cool: set_temperature needs both ends of the range.
                val other = if (t.targetKey == "target_temp_low") "target_temp_high" else "target_temp_low"
                e.attributes.optDouble(other).takeUnless { it.isNaN() }?.let { data.put(other, it) }
            }
            try { ha.callService("climate", "set_temperature", entityId, data) }
            catch (ex: HaException) { rollback(entityId, t.name, ex) }
        }
    }

    /** Tapping the mode chip: next of the entity's hvac_modes. */
    fun cycleMode(entityId: String) {
        val t = current(entityId)?.let(ThermostatTile::from) ?: return
        if (t.modes.size < 2) return
        val next = t.modes[(t.modes.indexOf(t.mode) + 1).mod(t.modes.size)]
        optimistic(entityId, state = next)
        call(entityId, t.name, "climate", "set_hvac_mode", JSONObject().put("hvac_mode", next))
    }

    /** Live while dragging: local only. */
    fun previewBrightness(entityId: String, pct: Int) {
        optimistic(entityId, state = if (pct > 0) "on" else "off") { it.put("brightness", (pct.coerceIn(0, 100) * 255 / 100.0).roundToInt()) }
    }

    /** Released: sends it. 0 % turns the light off. */
    fun setBrightness(entityId: String, pct: Int) {
        previewBrightness(entityId, pct)
        val name = current(entityId)?.friendlyName ?: "the light"
        if (pct <= 0) call(entityId, name, "light", "turn_off")
        else call(entityId, name, "light", "turn_on", JSONObject().put("brightness_pct", pct.coerceIn(1, 100)))
    }

    fun toggle(entityId: String) {
        val e = current(entityId) ?: return
        val on = e.state == "on"
        optimistic(entityId, state = if (on) "off" else "on")
        call(entityId, e.friendlyName, e.domain, if (on) "turn_off" else "turn_on")
    }

    fun activateScene(entityId: String) {
        val e = current(entityId) ?: return
        optimistic(entityId, state = java.time.OffsetDateTime.now().toString())
        if (e.domain == "script") call(entityId, e.friendlyName, "script", "turn_on")
        else call(entityId, e.friendlyName, "scene", "turn_on")
    }

    /** Lock ↔ unlock, garage open ↔ close. */
    fun toggleSecure(entityId: String) {
        val e = current(entityId) ?: return
        val t = SecureTile.from(e)
        if (t.isLock) {
            val unlock = !t.on
            optimistic(entityId, state = if (unlock) "unlocking" else "locking")
            call(entityId, e.friendlyName, "lock", if (unlock) "unlock" else "lock")
        } else {
            val open = !t.on
            optimistic(entityId, state = if (open) "opening" else "closing")
            call(entityId, e.friendlyName, "cover", if (open) "open_cover" else "close_cover")
        }
    }
}
