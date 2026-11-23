package app.vesper.feature.camera.profiles

import app.vesper.core.model.Presence
import app.vesper.core.model.Profile
import app.vesper.core.model.ProfileManager
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.SwitchReason
import app.vesper.feature.camera.face.FaceTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real [ProfileManager] (`services.profiles`), NOTES §6.6:
 *  - profiles, the active one and "Auto by face" persist in [settings] (`camera.profiles*`).
 *  - `enrolled` reflects whether encrypted templates exist in [templates].
 *  - [reject] ("Not me") goes back to shared and blocks that person from face matching for
 *    [rejectCooldownMs].
 *  - With Auto by face on, a face-picked or manual profile goes back to shared after
 *    [timeoutMs] of EMPTY presence (UNKNOWN, e.g. a dark room at night, doesn't count).
 */
class FaceProfileManager(
    private val settings: SettingsStore,
    private val templates: TemplateStore?,
    private val scope: CoroutineScope,
    presence: Flow<Presence>?,
    demo: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    var rejectCooldownMs: Long = 5 * 60_000,
    var timeoutMs: Long = settings.getInt(KEY_TIMEOUT_MIN, TIMEOUT_DEFAULT_MIN) * 60_000L,
) : ProfileManager {

    private val _profiles = MutableStateFlow(load(demo))
    override val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _active = MutableStateFlow(
        _profiles.value.firstOrNull { it.id == settings.getString(KEY_ACTIVE, Profile.SHARED_ID) } ?: Profile.SHARED)
    override val active: StateFlow<Profile> = _active.asStateFlow()
    private val _auto = MutableStateFlow(settings.getBoolean(KEY_AUTO, true))
    override val autoByFace: StateFlow<Boolean> = _auto.asStateFlow()

    /** Why the active profile last changed (the greeting shows only for FACE). */
    var lastReason: SwitchReason = SwitchReason.MANUAL; private set
    private val blockedUntil = HashMap<String, Long>()

    init {
        // The web settings page writes the store directly: apply its changes live.
        scope.launch {
            settings.changes.collect { k ->
                when (k) {
                    KEY_AUTO -> _auto.value = settings.getBoolean(KEY_AUTO, true)
                    KEY_TIMEOUT_MIN -> timeoutMs = settings.getInt(KEY_TIMEOUT_MIN, TIMEOUT_DEFAULT_MIN).coerceIn(1, 240) * 60_000L
                }
            }
        }
        if (presence != null) scope.launch {
            presence.distinctUntilChanged().collectLatest { p ->
                if (p == Presence.EMPTY) {
                    delay(timeoutMs)
                    if (_auto.value && _active.value.id != Profile.SHARED_ID) switchTo(Profile.SHARED_ID, SwitchReason.TIMEOUT)
                }
            }
        }
    }

    override fun switchTo(profileId: String, reason: SwitchReason) {
        val p = _profiles.value.firstOrNull { it.id == profileId } ?: return
        if (reason == SwitchReason.FACE && (!_auto.value || isBlocked(profileId))) return
        lastReason = reason
        _active.value = p
        settings.putString(KEY_ACTIVE, p.id)
    }

    override fun setAutoByFace(enabled: Boolean) {
        _auto.value = enabled
        settings.putBoolean(KEY_AUTO, enabled)
    }

    override fun reject(profileId: String) {
        blockedUntil[profileId] = clock() + rejectCooldownMs
        if (_active.value.id != Profile.SHARED_ID) switchTo(Profile.SHARED_ID, SwitchReason.MANUAL)
    }

    fun isBlocked(profileId: String): Boolean {
        val until = blockedUntil[profileId] ?: return false
        if (clock() >= until) { blockedUntil.remove(profileId); return false }
        return true
    }

    /** Ids currently blocked by "Not me". */
    fun blocked(): Set<String> = _profiles.value.map { it.id }.filter { isBlocked(it) }.toSet()

    override fun upsert(profile: Profile) {
        val fixed = profile.copy(enrolled = templates?.has(profile.id) ?: profile.enrolled)
        val l = _profiles.value
        val i = l.indexOfFirst { it.id == fixed.id }
        _profiles.value = if (i >= 0) l.toMutableList().also { it[i] = fixed } else l + fixed
        if (_active.value.id == fixed.id) _active.value = fixed
        persist()
    }

    override fun delete(profileId: String) {
        if (profileId == Profile.SHARED_ID) return
        templates?.delete(profileId)
        _profiles.value = _profiles.value.filterNot { it.id == profileId }
        if (_active.value.id == profileId) switchTo(Profile.SHARED_ID, SwitchReason.MANUAL)
        persist()
    }

    // --- face templates (owned here so `enrolled` stays true to what's on disk) ---

    fun saveTemplates(t: FaceTemplates) {
        val store = templates ?: return
        store.save(t)
        _profiles.value.firstOrNull { it.id == t.profileId }?.let { upsert(it) }
    }

    fun forgetFace(profileId: String) {
        templates?.delete(profileId)
        _profiles.value.firstOrNull { it.id == profileId }?.let { upsert(it) }
    }

    /** Everyone with templates for [embedderId] (others need re-enrolment). */
    fun enrolledTemplates(embedderId: String): List<FaceTemplates> {
        val store = templates ?: return emptyList()
        return _profiles.value.filter { it.id != Profile.SHARED_ID && it.enrolled }
            .mapNotNull { store.load(it.id) }
            .filter { it.embedderId == embedderId }
    }

    private fun load(demo: Boolean): List<Profile> {
        val raw = settings.getString(KEY_PROFILES, "")
        val list = if (raw.isNotBlank()) parse(raw) else if (demo) DEMO else emptyList()
        val fixed = list.filterNot { it.id == Profile.SHARED_ID }
            .map { it.copy(enrolled = templates?.has(it.id) ?: false) }
        return listOf(Profile.SHARED) + fixed
    }

    private fun persist() {
        settings.putString(KEY_PROFILES, encode(_profiles.value.filterNot { it.id == Profile.SHARED_ID }))
    }

    companion object {
        const val KEY_PROFILES = "camera.profiles"
        const val KEY_ACTIVE = "camera.profiles.active"
        const val KEY_AUTO = "camera.profiles.auto"
        const val KEY_TIMEOUT_MIN = "camera.profiles.timeoutMin"
        const val TIMEOUT_DEFAULT_MIN = 10

        /**
         * Sample people. Maya (orange, the Solar face) differs from the shared StandBy (the Liquid
         * Glass face), so switching to her visibly changes the clock; the Profiles demo adds her.
         */
        val DEMO_MAYA = Profile("maya", "Maya", 0xFFFF9F0A.toInt(), faceId = "solar", calendarEntity = "calendar.maya")
        val DEMO = listOf(
            DEMO_MAYA,
            Profile("sam", "Sam", 0xFF30D158.toInt(), faceId = "digital", calendarEntity = "calendar.sam"),
        )

        fun encode(list: List<Profile>): String = JSONArray().also { a ->
            for (p in list) a.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("color", p.color); put("face", p.faceId)
                p.calendarEntity?.let { put("calendar", it) }
            })
        }.toString()

        fun parse(raw: String): List<Profile> = try {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.getInt("color"), o.optString("face", "digital"),
                    if (o.has("calendar")) o.getString("calendar") else null)
            }
        } catch (e: Exception) { emptyList() }
    }
}
