package app.vesper.feature.settings.api

import app.vesper.core.model.AlarmStore
import app.vesper.core.model.Banner
import app.vesper.core.model.CrashLog
import app.vesper.core.model.MediaHub
import app.vesper.core.model.NightMode
import app.vesper.core.model.NotificationFeed
import app.vesper.core.model.Profile
import app.vesper.core.model.ProfileManager
import app.vesper.core.model.SecretStore
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingsSchema
import app.vesper.core.model.SystemControls
import app.vesper.core.model.SystemStateStore
import app.vesper.core.model.TimerStore
import app.vesper.core.model.VesperTimer
import app.vesper.feature.settings.http.HttpRequest
import app.vesper.feature.settings.http.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The services the phone remote, the people's calendars and diagnostics talk to (read lazily: features bind them after install). */
class RemoteDeps(
    val media: () -> MediaHub,
    val system: () -> SystemStateStore,
    val controls: () -> SystemControls,
    val timers: () -> TimerStore,
    val alarms: () -> AlarmStore,
    val notifications: () -> NotificationFeed,
    val profiles: () -> ProfileManager,
    val secrets: () -> SecretStore,
    val crashLog: () -> CrashLog,
    /** SystemClock.elapsedRealtime(): the clock of timers and media positions. */
    val elapsed: () -> Long,
    /** System.currentTimeMillis(). */
    val wall: () -> Long = System::currentTimeMillis,
    /** The settings schema: a person's preferences take the options of the settings they override. */
    val schema: () -> SettingsSchema? = { null },
)

/**
 * Phone remote, per-person calendars and diagnostics. Every route sits behind the pairing check
 * in [SettingsApi] (this class never sees an unpaired request).
 *
 *  - `GET /api/remote` → now playing, volume / brightness / DND / night, timers, next alarm
 *  - `POST /api/remote/media` `{action: play|pause|next|previous}`
 *  - `POST /api/remote/system` `{volume?, brightness?, dnd?, night?: auto|on|off}` (all or nothing)
 *  - `POST /api/remote/timers` `{seconds, label?}`; `POST /api/remote/timers/<id>` `{action: pause|resume|cancel|dismiss|add, seconds?}`
 *  - `POST /api/remote/note` `{text}` → a banner on the Echo
 *  - `GET /api/people` → profiles with their calendar state (`set` / `unset`, never the URLs)
 *  - `PUT /api/people/<id>/calendar` `{urls: "…" | null}` → write-only, stored under [Secrets.calendarKeyFor]
 *  - `PUT /api/people/<id>/prefs` `{face?, wallpaper?, nightMode?, alarmSound?}` → their own choices ("" = Everyone's)
 *  - `GET /api/diagnostics`, `POST /api/diagnostics/clear`
 *
 * Store calls (writes) run on the main thread via [onMain]; reading a StateFlow's value is
 * thread-safe, so state reads don't hop.
 */
class RemoteApi(
    private val d: RemoteDeps,
    private val onMain: (() -> Any?) -> Any?,
) {
    private val noteTimes = ArrayDeque<Long>()
    private var noteSeq = 0

    fun handles(path: String) =
        path == "/api/remote" || path.startsWith("/api/remote/") || path == "/api/people" ||
            path.startsWith("/api/people/") || path == "/api/diagnostics" || path.startsWith("/api/diagnostics/")

    fun handle(req: HttpRequest): HttpResponse = try {
        val p = req.path
        val m = req.method
        when {
            p == "/api/remote" && m == "GET" -> ok(state())
            p == "/api/remote/media" && m == "POST" -> media(body(req))
            p == "/api/remote/system" && m == "POST" -> system(body(req))
            p == "/api/remote/timers" && m == "POST" -> startTimer(body(req))
            p.startsWith("/api/remote/timers/") && m == "POST" -> timerAction(p.removePrefix("/api/remote/timers/"), body(req))
            p == "/api/remote/note" && m == "POST" -> note(body(req))
            p == "/api/people" && m == "GET" -> ok(peopleJson())
            p.startsWith("/api/people/") && p.endsWith("/calendar") && (m == "PUT" || m == "POST") ->
                calendar(p.removePrefix("/api/people/").removeSuffix("/calendar"), body(req))
            p.startsWith("/api/people/") && p.endsWith("/prefs") && (m == "PUT" || m == "POST") ->
                prefs(p.removePrefix("/api/people/").removeSuffix("/prefs"), body(req))
            p == "/api/diagnostics" && m == "GET" -> ok(diagnosticsJson())
            p == "/api/diagnostics/clear" && m == "POST" -> { onMain { d.crashLog().clear() }; ok(diagnosticsJson()) }
            else -> HttpResponse.error(404, "No such API")
        }
    } catch (e: ValidationException) {
        HttpResponse.error(422, e.message ?: "Invalid")
    }

    // ---- live state ----

    fun state(): JSONObject {
        val now = d.elapsed()
        val sys = d.system().state.value
        val o = JSONObject()
        val cur = d.media().current.value
        o.put("media", cur?.let { s ->
            val st = s.state
            JSONObject().put("title", st.title).put("artist", st.artist).put("playing", st.playing)
                .put("source", st.sourceLabel.ifEmpty { st.source.name.lowercase() })
                .put("positionMs", st.positionNow(now)).put("durationMs", st.durationMs)
                .put("canSkip", s.controls.canSkip).put("canPause", s.controls.canPause)
        } ?: JSONObject.NULL)
        o.put("system", JSONObject().put("volume", sys.volume.toDouble()).put("brightness", sys.brightness.toDouble())
            .put("dnd", sys.doNotDisturb).put("night", sys.nightMode.name.lowercase()).put("nightActive", sys.nightActive))
        o.put("timers", JSONArray().apply { for (t in d.timers().timers.value) put(timerJson(t, now)) })
        o.put("maxTimers", TimerStore.MAX_TIMERS)
        val next = d.alarms().next.value
        o.put("nextAlarm", next?.let {
            JSONObject().put("at", it.triggerAtMillis).put("label", it.alarm.label)
                .put("time", "%02d:%02d".format(it.alarm.hour, it.alarm.minute)).putOpt("reason", it.reason)
        } ?: JSONObject.NULL)
        o.put("now", d.wall())
        return o
    }

    private fun timerJson(t: VesperTimer, now: Long) = JSONObject().put("id", t.id).put("label", t.label)
        .put("durationMs", t.durationMs).put("state", t.state.name.lowercase()).put("remainingMs", t.remainingMs(now))

    // ---- controls ----

    private fun media(b: JSONObject): HttpResponse {
        val action = b.optString("action")
        if (action !in setOf("play", "pause", "next", "previous")) throw ValidationException("Action: play, pause, next or previous")
        val c = d.media().current.value?.controls ?: return HttpResponse.error(409, "Nothing is playing")
        onMain {
            when (action) { "play" -> c.play(); "pause" -> c.pause(); "next" -> c.next(); else -> c.previous() }
        }
        return ok(JSONObject().put("ok", true))
    }

    private fun system(b: JSONObject): HttpResponse {
        if (b.length() == 0) throw ValidationException("Nothing to change")
        var volume: Float? = null; var brightness: Float? = null; var dnd: Boolean? = null; var night: NightMode? = null
        for (k in b.keys().asSequence().toList()) {
            val v = b.opt(k)
            when (k) {
                "volume" -> volume = unit(v, "Volume")
                "brightness" -> brightness = unit(v, "Brightness")
                "dnd" -> dnd = v as? Boolean ?: throw ValidationException("Do Not Disturb: true or false")
                "night" -> night = NightMode.values().firstOrNull { it.name.equals(v as? String, ignoreCase = true) }
                    ?: throw ValidationException("Night mode: auto, on or off")
                else -> throw ValidationException("Unknown field: ${k.take(40)}")
            }
        }
        val failed = onMain {
            val c = d.controls()
            buildList {
                volume?.let { if (!c.setVolume(it, true)) add("volume") }
                brightness?.let { if (!c.setBrightness(it, false)) add("brightness") }
                dnd?.let { if (!c.setDoNotDisturb(it)) add("Do Not Disturb") }
                night?.let { if (!c.setNightMode(it)) add("night mode") }
            }
        } as List<*>
        if (failed.isNotEmpty()) return HttpResponse.error(409, "The Echo can't change ${failed.joinToString()} right now")
        return ok(JSONObject().put("ok", true).put("system", state().getJSONObject("system")))
    }

    private fun startTimer(b: JSONObject): HttpResponse {
        val secs = whole(b.opt("seconds"), 1..MAX_TIMER_SECONDS, "Timer length")
        val label = text(b.opt("label") ?: "", MAX_LABEL, "Label", allowEmpty = true)
        val t = onMain { d.timers().start(secs * 1000L, label) } as VesperTimer?
            ?: return HttpResponse.error(409, "${TimerStore.MAX_TIMERS} timers are already running")
        return ok(JSONObject().put("ok", true).put("timer", timerJson(t, d.elapsed())))
    }

    private fun timerAction(id: String, b: JSONObject): HttpResponse {
        val action = b.optString("action")
        if (action !in setOf("pause", "resume", "cancel", "dismiss", "add")) throw ValidationException("Action: pause, resume, cancel, dismiss or add")
        val extra = if (action == "add") whole(b.opt("seconds"), 1..MAX_TIMER_SECONDS, "Extra time") else 0
        if (d.timers().timers.value.none { it.id == id }) return HttpResponse.error(404, "No such timer")
        onMain {
            val s = d.timers()
            when (action) {
                "pause" -> s.pause(id); "resume" -> s.resume(id); "cancel" -> s.cancel(id)
                "dismiss" -> s.dismiss(id); else -> s.addTime(id, extra * 1000L)
            }
        }
        return ok(JSONObject().put("ok", true))
    }

    private fun note(b: JSONObject): HttpResponse {
        val text = text(b.opt("text"), MAX_NOTE, "Note")
        val now = d.wall()
        synchronized(noteTimes) {
            while (noteTimes.isNotEmpty() && now - noteTimes.first() > 60_000) noteTimes.removeFirst()
            if (noteTimes.size >= MAX_NOTES_PER_MIN) return HttpResponse.error(429, "Too many notes, wait a minute")
            noteTimes.addLast(now)
            noteSeq++
        }
        val banner = Banner(
            id = "note-$now-$noteSeq", appName = "Note", appColor = NOTE_COLOR, glyph = "message",
            title = "Note", body = text, postedAtMillis = now,
        )
        onMain { d.notifications().post(banner) }
        return ok(JSONObject().put("ok", true))
    }

    // ---- per-person calendars ----

    private fun people(): List<Profile> = d.profiles().profiles.value.filter { it.id != Profile.SHARED_ID }

    /** Each person with their calendar state and own preferences ("" = follow Everyone's, i.e. the shared setting). */
    fun peopleJson(): JSONObject = JSONObject().put("people", JSONArray().apply {
        for (p in people()) put(JSONObject().put("id", p.id).put("name", p.name)
            .put("color", "#%06X".format(p.color and 0xFFFFFF))
            .put("calendar", if (d.secrets().isSet(Secrets.calendarKeyFor(p.id))) "set" else "unset")
            .put("face", p.faceId).put("wallpaper", p.wallpaper.orEmpty())
            .put("nightMode", p.nightMode.orEmpty()).put("alarmSound", p.alarmSound.orEmpty()))
    })

    /**
     * `PUT /api/people/<id>/prefs` `{face?, wallpaper?, nightMode?, alarmSound?}`: a person's own
     * clock face, Liquid Glass wallpaper, Night mode and alarm sound. Values are the options of the
     * setting each one overrides ([PREF_KEYS]); "" or null = follow Everyone's (not for face).
     */
    private fun prefs(id: String, b: JSONObject): HttpResponse {
        val p = people().firstOrNull { it.id == id } ?: return HttpResponse.error(404, "No such person")
        if (b.keys().asSequence().any { it !in PREF_KEYS }) throw ValidationException("Unknown field")
        fun value(field: String, allowEmpty: Boolean): String? {
            val raw = if (b.isNull(field)) null else b.opt(field) as? String ?: throw ValidationException("$field: expected text")
            if (raw.isNullOrEmpty()) { if (allowEmpty) return null else throw ValidationException("$field: required") }
            val options = d.schema()?.spec(PREF_KEYS.getValue(field))?.options?.map { it.first }
            if (options != null && options.isNotEmpty() && raw !in options) throw ValidationException("$field: not one of the choices")
            return raw
        }
        var next = p
        if (b.has("face")) next = next.copy(faceId = value("face", allowEmpty = false)!!)
        if (b.has("wallpaper")) next = next.copy(wallpaper = value("wallpaper", true))
        if (b.has("nightMode")) next = next.copy(nightMode = value("nightMode", true))
        if (b.has("alarmSound")) next = next.copy(alarmSound = value("alarmSound", true))
        if (next != p) onMain { d.profiles().upsert(next) }
        return ok(JSONObject().put("ok", true).put("id", p.id).put("face", next.faceId).put("wallpaper", next.wallpaper.orEmpty())
            .put("nightMode", next.nightMode.orEmpty()).put("alarmSound", next.alarmSound.orEmpty()))
    }

    private fun calendar(id: String, b: JSONObject): HttpResponse {
        val p = people().firstOrNull { it.id == id } ?: return HttpResponse.error(404, "No such person")
        if (b.keys().asSequence().any { it != "urls" }) throw ValidationException("Unknown field")
        val key = Secrets.calendarKeyFor(p.id)
        val raw = if (!b.has("urls") || b.isNull("urls")) null else b.opt("urls") as? String ?: throw ValidationException("Calendar links: expected text")
        if (raw == null || raw.isBlank()) {
            onMain { d.secrets().set(key, null) }
        } else {
            val urls = Calendars.parse(raw)
            onMain { d.secrets().set(key, urls.joinToString("\n")) }
        }
        return ok(JSONObject().put("ok", true).put("id", p.id)
            .put("calendar", if (d.secrets().isSet(key)) "set" else "unset"))
    }

    // ---- diagnostics ----

    fun diagnosticsJson(): JSONObject = JSONObject().put("entries", JSONArray().apply {
        for (e in d.crashLog().entries.value.asReversed()) put(JSONObject().put("at", e.atMillis).put("kind", e.kind.name.lowercase())
            .put("thread", e.thread.take(80)).put("summary", e.summary.take(300)).put("stack", e.stack.take(MAX_STACK)))
    })

    // ---- helpers ----

    private fun body(req: HttpRequest): JSONObject = try { JSONObject(req.bodyText()) } catch (e: JSONException) {
        throw ValidationException("Expected a JSON object")
    }

    private fun unit(v: Any?, what: String): Float {
        val n = (v as? Number)?.toDouble() ?: throw ValidationException("$what: expected a number from 0 to 1")
        if (n.isNaN() || n < 0.0 || n > 1.0) throw ValidationException("$what: 0 to 1")
        return n.toFloat()
    }

    private fun whole(v: Any?, range: IntRange, what: String): Int {
        val n = (v as? Number)?.toDouble() ?: throw ValidationException("$what: expected a number")
        if (n != Math.rint(n) || n < range.first || n > range.last) throw ValidationException("$what: ${range.first}–${range.last}")
        return n.toInt()
    }

    private fun text(v: Any?, max: Int, what: String, allowEmpty: Boolean = false): String {
        val s = (v as? String)?.trim() ?: throw ValidationException("$what: expected text")
        if (s.isEmpty() && !allowEmpty) throw ValidationException("$what: can't be empty")
        if (s.length > max || s.any { it < ' ' && it != '\n' }) throw ValidationException("$what: at most $max characters")
        return s
    }

    private fun ok(o: JSONObject) = HttpResponse.json(200, o.toString())

    companion object {
        /** A person's preference field → the setting it overrides (its options are the allowed values). */
        val PREF_KEYS = mapOf(
            "face" to "clock.face",
            "wallpaper" to "clock.glass.wallpaper",
            "nightMode" to "clock.night.mode",
            "alarmSound" to "alarm.sound",
        )
        const val MAX_LABEL = 40
        const val MAX_NOTE = 280
        const val MAX_STACK = 8000
        const val MAX_TIMER_SECONDS = 24 * 3600
        const val MAX_NOTES_PER_MIN = 20
        /** iOS Messages green. */
        const val NOTE_COLOR = 0xFF30D158.toInt()
    }
}

/** Validation of the iCal links typed on the page. Values are never logged or echoed back. */
object Calendars {
    const val MAX_URLS = 10
    const val MAX_URL = 2048

    /** [raw] is one link per line (commas and spaces also separate). Returns the cleaned links; throws on anything that isn't an http(s) / webcal link. */
    fun parse(raw: String): List<String> {
        if (raw.length > MAX_URLS * (MAX_URL + 1)) throw ValidationException("Calendar links: too long")
        val urls = Secrets.splitUrls(raw)
        if (urls.isEmpty()) throw ValidationException("Calendar links: paste at least one link")
        if (urls.size > MAX_URLS) throw ValidationException("Calendar links: at most $MAX_URLS")
        for (u in urls) {
            val scheme = u.substringBefore("://", "").lowercase()
            if (scheme !in setOf("https", "http", "webcal", "webcals") || u.length > MAX_URL || u.any { it < ' ' || it == '"' || it == '<' || it == '>' } ||
                u.substringAfter("://").substringBefore('/').isBlank()) {
                throw ValidationException("Calendar links: each one must be an https:// or webcal:// address")
            }
        }
        return urls.distinct()
    }
}
