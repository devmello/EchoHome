package app.vesper.feature.home.lamp

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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Lamp screen state + actions, with the Home tiles' optimistic UI (HomeController):
 *  - an action patches the entity at once with the state HA should report ([LightCalls.expected]),
 *    then calls the service;
 *  - an [HaException] rolls the patch back and shows [LampUi.error] for a few seconds;
 *  - otherwise the patch stays until HA reports a newer state for that entity (or [overrideTtlMs]).
 *
 * Sliders and the white track preview locally in the view and call here once, on release.
 */
class LampController(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val errorMs: Long = 3500,
    private val overrideTtlMs: Long = 5000,
) {
    private class Patch(val entity: HaEntity, val base: HaEntity?, val until: Long)

    private val registry = MutableStateFlow<Registry?>(null)
    private val patches = MutableStateFlow<Map<String, Patch>>(emptyMap())
    private val selected = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val _ui = MutableStateFlow<LampUi?>(null)
    val ui: StateFlow<LampUi?> = _ui.asStateFlow()
    val entityId: String? get() = selected.value

    private val jobs = ArrayList<Job>()
    private var errorJob: Job? = null

    /** Shows [id]; null keeps the current light, or picks the Home screen's room lamp. */
    fun show(id: String?) {
        if (id != null) selected.value = id
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            ha.connection.filter { it == HaConnection.CONNECTED }.collect {
                try { registry.value = Registry.load(ha) } catch (_: HaException) { /* keep the last one */ }
            }
        }
        jobs += scope.launch {
            combine(ha.entities, registry, patches, selected, error) { ents, reg, p, sel, err ->
                val effective = applyPatches(ents, p)
                val r = reg ?: Registry.EMPTY
                val id = sel ?: if (reg != null) LampPresenter.defaultLight(effective, r, settings.getString(HomeSettings.SELECTED_ROOM, "").ifEmpty { null }) else null
                if (sel == null && id != null) selected.value = id
                if (reg == null && sel == null) null else LampPresenter.present(id, effective, r, err)
            }.collect { _ui.value = it }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

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

    private fun light(): LightDetail? = selected.value?.let(::current)?.takeIf { it.domain == "light" }?.let(LightDetail::from)

    /** Patches [call]'s entity to its expected state, then sends it. */
    private fun run(call: ServiceCall) {
        val now = current(call.entityId) ?: return
        val base = patches.value[call.entityId]?.base ?: ha.entities.value[call.entityId]
        val until = System.currentTimeMillis() + overrideTtlMs
        patches.value = patches.value + (call.entityId to Patch(LightCalls.expected(now, call), base, until))
        scope.launch {
            delay(overrideTtlMs + 50)
            val p = patches.value[call.entityId]
            if (p != null && p.until <= System.currentTimeMillis()) patches.value = patches.value - call.entityId
        }
        scope.launch {
            try { ha.callService(call.domain, call.service, call.entityId, call.data) }
            catch (e: HaException) {
                patches.value = patches.value - call.entityId
                showError(if (e.code == "not_connected" || e.code == "disconnected") "Couldn't reach Home Assistant" else "Couldn't change ${now.friendlyName}")
            }
        }
    }

    private fun showError(msg: String) {
        error.value = msg
        errorJob?.cancel()
        errorJob = scope.launch { delay(errorMs); error.value = null }
    }

    // ---- actions ----

    /** Released slider: 0 % turns the light off. */
    fun setBrightness(pct: Int) { light()?.let { run(LightCalls.brightness(it, pct)) } }

    fun setPower(on: Boolean) { light()?.let { run(LightCalls.power(it, on)) } }

    fun togglePower() { light()?.let { run(LightCalls.power(it, !it.on)) } }

    fun setKelvin(kelvin: Int) { light()?.let { l -> LightCalls.kelvin(l, kelvin)?.let(::run) } }

    fun setColor(rgb: Int) { light()?.let { l -> LightCalls.color(l, rgb)?.let(::run) } }

    fun applyPreset(index: Int) {
        val p = LampPreset.ALL.getOrNull(index) ?: return
        light()?.let { run(LightCalls.preset(it, p)) }
    }

    /** A switch in the room list. */
    fun toggleOther(entityId: String) { current(entityId)?.let { run(LightCalls.toggle(it)) } }
}
