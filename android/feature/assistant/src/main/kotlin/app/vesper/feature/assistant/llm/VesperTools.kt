package app.vesper.feature.assistant.llm

import android.os.SystemClock
import app.vesper.core.model.Alarm
import app.vesper.core.model.Profile
import app.vesper.core.model.VesperServices
import app.vesper.core.model.VesperTimer
import app.vesper.feature.assistant.llm.Schema.bool
import app.vesper.feature.assistant.llm.Schema.int
import app.vesper.feature.assistant.llm.Schema.props
import app.vesper.feature.assistant.llm.Schema.str
import app.vesper.feature.assistant.llm.Schema.strArray
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude's tools on the app's own services (NOTES §6): timers, alarms, weather, calendar, media,
 * volume, and Home Assistant only when it's configured. Slots are read at call time (never cached).
 * Service calls run on [main] (the stores are main-thread objects).
 */
class VesperTools(
    private val services: () -> VesperServices,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
) : ToolHost {

    private val echo = EchoTools(zone, elapsed, wall)
    private val lists = ListTools(zone, wall)

    override fun specs(): List<ToolSpec> = BASE + EchoTools.SPECS + ListTools.SPECS + if (services().config.secrets.haConfigured) HOME else emptyList()

    override suspend fun run(name: String, input: JSONObject): ToolOutcome = withContext(main) {
        val s = services()
        when (name) {
            "set_timer" -> setTimer(s, input)
            "list_timers" -> ToolOutcome(JSONObject().put("timers", timersJson(s.timers.timers.value)))
            "cancel_timer" -> cancelTimer(s, input)
            "set_alarm" -> setAlarm(s, input)
            "list_alarms" -> ToolOutcome(JSONObject().put("alarms", JSONArray(s.alarms.alarms.value.map(::alarmJson)))
                .put("next", s.alarms.next.value?.let { n -> JSONObject().put("alarm_id", n.alarm.id).put("at", iso(n.triggerAtMillis)).also { o -> n.reason?.let { o.put("reason", it) } } }))
            "update_alarm" -> updateAlarm(s, input)
            "delete_alarm" -> deleteAlarm(s, input)
            "get_weather" -> weather(s)
            "get_calendar" -> calendar(s, input)
            "now_playing" -> nowPlaying(s)
            "media_control" -> mediaControl(s, input)
            "set_volume" -> setVolume(s, input)
            "set_brightness" -> setBrightness(s, input)
            "get_display" -> s.system.state.value.let { st ->
                ToolOutcome(JSONObject().put("brightness_percent", (st.brightness * 100).toInt()).put("volume_percent", (st.volume * 100).toInt())
                    .put("do_not_disturb", st.doNotDisturb).put("night_mode", st.nightMode.name.lowercase()).put("night_active", st.nightActive)
                    .put("camera_enabled", !st.cameraDisabled).put("camera_shutter_closed", st.shutterClosed).put("mic_muted", st.micMuted))
            }
            "home_list" -> if (s.config.secrets.haConfigured) homeList(s, input) else notAvailable()
            "home_control" -> if (s.config.secrets.haConfigured) homeControl(s, input) else notAvailable()
            else -> lists.run(s, name, input) ?: echo.run(s, name, input) ?: ToolOutcome.error("UNKNOWN_TOOL", "No tool named $name")
        }
    }

    private fun notAvailable() = ToolOutcome.error("NOT_CONFIGURED", "Home controls aren't set up on this display.")

    // ---- timers ----

    private fun setTimer(s: VesperServices, input: JSONObject): ToolOutcome {
        val secs = input.getLong("duration_seconds")
        val label = input.optString("label", "").trim()
        val t = s.timers.start(secs * 1000, label)
            ?: return ToolOutcome.error("TOO_MANY_TIMERS", "Only 3 timers can run at once.")
        return ToolOutcome(JSONObject().put("timer", timerJson(t)),
            card = ResultCard.Action(label.ifEmpty { "Timer" }, "${duration(secs)} · started", Icon.TIMER))
    }

    private fun cancelTimer(s: VesperServices, input: JSONObject): ToolOutcome {
        val list = s.timers.timers.value
        val target = EchoTools.resolveTimer(list, input) ?: return EchoTools.timerNotFound(list)
        if (target.state == VesperTimer.State.DONE) s.timers.dismiss(target.id) else s.timers.cancel(target.id)
        return ToolOutcome(JSONObject().put("cancelled", target.id),
            card = ResultCard.Action(target.label.ifEmpty { "Timer" }, "Cancelled", Icon.TIMER))
    }

    private fun timersJson(list: List<VesperTimer>) = JSONArray(list.map(::timerJson))

    private fun timerJson(t: VesperTimer) = JSONObject()
        .put("timer_id", t.id).put("label", t.label)
        .put("state", t.state.name.lowercase())
        .put("duration_seconds", t.durationMs / 1000)
        .put("remaining_seconds", t.remainingMs(elapsed()) / 1000)

    // ---- alarms ----

    private fun setAlarm(s: VesperServices, input: JSONObject): ToolOutcome {
        val a = Alarm(
            id = "a-" + UUID.randomUUID().toString().take(8),
            hour = input.getInt("hour"), minute = input.getInt("minute"),
            days = days(input.optJSONArray("days")),
            label = input.optString("label", "").ifBlank { "Alarm" },
            leaveBy = input.optBoolean("leave_by", false),
            readyMinutes = if (input.has("ready_minutes")) input.getInt("ready_minutes") else 45,
            skipHolidays = input.optBoolean("skip_holidays", false),
            profileId = s.profiles.active.value.id.takeIf { it != Profile.SHARED_ID && input.optBoolean("mine", false) },
        )
        s.alarms.upsert(a)
        return ToolOutcome(JSONObject().put("alarm", alarmJson(a)),
            card = ResultCard.Action(a.label, "${clock(a.hour, a.minute)}${repeatText(a.days)}${if (a.leaveBy) " · leave-by" else ""}", Icon.ALARM))
    }

    private fun updateAlarm(s: VesperServices, input: JSONObject): ToolOutcome {
        val old = s.alarms.alarms.value.firstOrNull { it.id == input.getString("alarm_id") }
            ?: return ToolOutcome.error("NOT_FOUND", "No alarm with that id; call list_alarms.")
        val a = old.copy(
            hour = if (input.has("hour")) input.getInt("hour") else old.hour,
            minute = if (input.has("minute")) input.getInt("minute") else old.minute,
            enabled = if (input.has("enabled")) input.getBoolean("enabled") else old.enabled,
            days = if (input.has("days")) days(input.optJSONArray("days")) else old.days,
            label = if (input.has("label")) input.getString("label").ifBlank { old.label } else old.label,
            leaveBy = if (input.has("leave_by")) input.getBoolean("leave_by") else old.leaveBy,
            readyMinutes = if (input.has("ready_minutes")) input.getInt("ready_minutes") else old.readyMinutes,
            skipHolidays = if (input.has("skip_holidays")) input.getBoolean("skip_holidays") else old.skipHolidays,
        )
        s.alarms.upsert(a)
        val state = if (a.enabled) "${clock(a.hour, a.minute)}${repeatText(a.days)}" else "Off"
        return ToolOutcome(JSONObject().put("alarm", alarmJson(a)), card = ResultCard.Action(a.label, state, Icon.ALARM))
    }

    private fun deleteAlarm(s: VesperServices, input: JSONObject): ToolOutcome {
        val a = s.alarms.alarms.value.firstOrNull { it.id == input.getString("alarm_id") }
            ?: return ToolOutcome.error("NOT_FOUND", "No alarm with that id; call list_alarms.")
        if (!input.getBoolean("confirmed")) return ToolOutcome.error("NEEDS_CONFIRMATION",
            "Not deleted. Ask the user to confirm deleting \"${a.label}\" (${clock(a.hour, a.minute)}), then call again with confirmed=true.")
        s.alarms.delete(a.id)
        return ToolOutcome(JSONObject().put("deleted", a.id), card = ResultCard.Action(a.label, "Deleted", Icon.ALARM))
    }

    private fun alarmJson(a: Alarm) = JSONObject()
        .put("alarm_id", a.id).put("label", a.label)
        .put("time", "%02d:%02d".format(a.hour, a.minute))
        .put("enabled", a.enabled)
        .put("days", JSONArray(a.days.sorted().map { it.name.take(3) }))
        .also { o ->
            if (a.leaveBy) o.put("leave_by", true).put("ready_minutes", a.readyMinutes)
            if (a.skipHolidays) o.put("skip_holidays", true)
        }

    private fun days(a: JSONArray?): Set<DayOfWeek> =
        (0 until (a?.length() ?: 0)).mapNotNull { i -> DayOfWeek.values().firstOrNull { it.name.startsWith(a!!.getString(i)) } }.toSet()

    private fun repeatText(d: Set<DayOfWeek>) = when {
        d.isEmpty() -> ""
        d.size == 7 -> " · daily"
        d == WEEKDAYS -> " · weekdays"
        else -> " · " + d.sorted().joinToString(" ") { it.name.take(2).lowercase().replaceFirstChar(Char::uppercase) }
    }

    // ---- glances ----

    private fun weather(s: VesperServices): ToolOutcome {
        val w = s.weather.weather.value ?: return ToolOutcome.error("UNAVAILABLE", "Weather isn't available right now.")
        val now = wall()
        val hourly = JSONArray(w.hourly.filter { it.timeMillis >= now - 3_600_000 }.take(24).map {
            JSONObject().put("time", iso(it.timeMillis)).put("temp", it.temperature)
                .put("condition", it.condition.name.lowercase()).put("precip_chance", it.precipChance)
        })
        val out = JSONObject()
            .put("location", w.location.ifBlank { s.location.value.name }).put("unit", w.unit)
            .put("now", JSONObject().put("temp", w.temperature).put("condition", w.condition.name.lowercase()).put("night", w.isNight))
            .put("today", JSONObject().put("high", w.high).put("low", w.low))
            .put("tomorrow_summary", w.tomorrowSummary ?: JSONObject.NULL)
            .put("hourly", hourly)
        w.sunriseMillis?.let { out.put("sunrise", iso(it)) }
        w.sunsetMillis?.let { out.put("sunset", iso(it)) }
        val rainy = w.condition.name.let { it == "RAIN" || it == "POURING" || it == "LIGHTNING" }
        val card = ResultCard.Weather(
            label = w.location.ifBlank { "Now" },
            headline = "${w.temperature}° ${conditionText(w.condition.name)}",
            detail = "H:${w.high}° L:${w.low}°" + (w.tomorrowSummary?.let { " · $it" } ?: ""),
            rain = rainy || (w.tomorrowSummary?.contains("rain", ignoreCase = true) == true),
        )
        return ToolOutcome(out, card = card)
    }

    private fun calendar(s: VesperServices, input: JSONObject): ToolOutcome {
        val z = zone()
        val today = Instant.ofEpochMilli(wall()).atZone(z).toLocalDate()
        val day = if (input.optString("day") == "tomorrow") today.plusDays(1) else today
        val from = day.atStartOfDay(z).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val profiles = s.profiles.profiles.value
        val active = s.profiles.active.value
        val who = input.optString("person", "").trim()
        // Default: the active person's calendars plus shared ones; everyone when nobody in particular is active.
        val pid: String? = when {
            who.isEmpty() -> active.id.takeIf { it != Profile.SHARED_ID }
            who.equals("everyone", true) || who.equals("all", true) -> null
            else -> (profiles.firstOrNull { it.id.equals(who, true) || it.name.equals(who, true) }
                ?: return ToolOutcome.error("NOT_FOUND", "People: ${profiles.filter { it.id != Profile.SHARED_ID }.joinToString { it.name }}")).id
        }
        val events = s.calendar.events.value.filter {
            it.endMillis > from && it.startMillis < to && (pid == null || it.profileId == null || it.profileId == pid)
        }
        fun owner(id: String?) = if (id == null) "shared" else profiles.firstOrNull { it.id == id }?.name ?: id
        val arr = JSONArray(events.map { e ->
            JSONObject().put("title", e.title).put("start", iso(e.startMillis)).put("end", iso(e.endMillis))
                .put("all_day", e.allDay).put("location", e.location ?: JSONObject.NULL).put("details", e.subtitle)
                .put("calendar", owner(e.profileId))
        })
        return ToolOutcome(JSONObject().put("day", day.toString())
            .put("showing", if (pid == null) "everyone" else owner(pid) + " and shared").put("events", arr))
    }

    private fun nowPlaying(s: VesperServices): ToolOutcome {
        val m = s.media.current.value ?: return ToolOutcome(JSONObject().put("playing", false).put("loaded", false))
        val st = m.state
        return ToolOutcome(JSONObject().put("loaded", true).put("playing", st.playing)
            .put("title", st.title).put("artist", st.artist).put("album", st.album)
            .put("source", st.sourceLabel.ifEmpty { st.source.name.lowercase() })
            .put("can_skip", m.controls.canSkip).put("can_pause", m.controls.canPause),
            card = ResultCard.Media(st.title, listOf(st.artist, st.sourceLabel).filter { it.isNotBlank() }.joinToString(" · ")))
    }

    private fun mediaControl(s: VesperServices, input: JSONObject): ToolOutcome {
        val m = s.media.current.value ?: return ToolOutcome.error("NOTHING_LOADED", "Nothing is playing on this display. It can't search for music; start something with AirPlay or Spotify first.")
        val c = m.controls
        val action = input.getString("action")
        val src = m.state.sourceLabel.ifBlank { "This source" }
        if ((action == "next" || action == "previous") && !c.canSkip)
            return ToolOutcome.error("CANT_SKIP", "$src doesn't let the display skip tracks; skip on the phone.")
        if (action == "pause" && !c.canPause)
            return ToolOutcome.error("CANT_PAUSE", "$src can't be paused from the display.")
        when (action) {
            "play" -> c.play()
            "pause" -> c.pause()
            "next" -> c.next()
            "previous" -> c.previous()
        }
        val state = when (action) { "play" -> "Playing"; "pause" -> "Music paused"; "next" -> "Next track"; else -> "Previous track" }
        val title = m.state.title.ifBlank { "This display" }
        return ToolOutcome(JSONObject().put("ok", true).put("action", action).put("title", m.state.title),
            card = ResultCard.Action(title, state, if (action == "pause") Icon.PAUSE else Icon.PLAY))
    }

    private fun setVolume(s: VesperServices, input: JSONObject): ToolOutcome {
        val pct = input.getInt("percent")
        val ok = s.systemControls.setVolume(pct / 100f, showHud = true)
        return if (ok) ToolOutcome(JSONObject().put("volume_percent", pct), card = ResultCard.Action("Volume", "$pct%", Icon.PLAY))
        else ToolOutcome.error("FAILED", "Couldn't change the volume.")
    }

    private fun setBrightness(s: VesperServices, input: JSONObject): ToolOutcome {
        val pct = input.getInt("percent")
        val ok = s.systemControls.setBrightness(pct / 100f, showHud = true)
        return if (ok) ToolOutcome(JSONObject().put("brightness_percent", pct), card = ResultCard.Action("Brightness", "$pct%", Icon.BRIGHTNESS))
        else ToolOutcome.error("FAILED", "Couldn't change the brightness.")
    }

    // ---- Home Assistant (optional) ----

    private fun homeList(s: VesperServices, input: JSONObject): ToolOutcome {
        val want = input.optString("domain", "")
        val list = s.homeAssistant.entities.value.values
            .filter { it.domain in HOME_DOMAINS && (want.isEmpty() || it.domain == want) }
            .sortedBy { it.entityId }
            .take(80)
        return ToolOutcome(JSONObject().put("entities", JSONArray(list.map {
            JSONObject().put("entity_id", it.entityId).put("name", it.friendlyName).put("state", it.state)
        })))
    }

    private suspend fun homeControl(s: VesperServices, input: JSONObject): ToolOutcome {
        val id = input.getString("entity_id")
        val domain = id.substringBefore('.')
        if (domain !in HOME_DOMAINS) return ToolOutcome.error("NOT_ALLOWED", "Only lights, switches, fans, covers, scenes, scripts and media players can be controlled.")
        val ent = s.homeAssistant.entities.value[id] ?: return ToolOutcome.error("NOT_FOUND", "No entity $id; call home_list.")
        val action = input.getString("action")
        val data = JSONObject()
        if (input.has("brightness_percent") && domain == "light") data.put("brightness_pct", input.getInt("brightness_percent"))
        val service = when {
            domain == "scene" || domain == "script" -> "turn_on"
            domain == "cover" -> if (action == "turn_on") "open_cover" else if (action == "turn_off") "close_cover" else "toggle"
            else -> action
        }
        return try {
            s.homeAssistant.callService(domain, service, id, data.takeIf { it.length() > 0 })
            val state = when (action) {
                "turn_on" -> if (data.has("brightness_pct")) "${data.getInt("brightness_pct")}%" else "On"
                "turn_off" -> "Off"
                else -> "Toggled"
            }
            val icon = when (domain) { "light" -> Icon.LAMP; "media_player" -> Icon.TV; else -> Icon.POWER }
            ToolOutcome(JSONObject().put("ok", true), card = ResultCard.Action(ent.friendlyName, state, icon))
        } catch (e: Exception) {
            ToolOutcome.error("FAILED", "Home Assistant didn't do it (${e.javaClass.simpleName}).")
        }
    }

    // ---- formatting ----

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDateTime().withNano(0).toString()

    companion object {
        private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val HOME_DOMAINS = setOf("light", "switch", "fan", "cover", "scene", "script", "media_player")
        private val DAY_NAMES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        fun clock(h: Int, m: Int) = "${if (h % 12 == 0) 12 else h % 12}:%02d %s".format(m, if (h < 12) "AM" else "PM")

        fun duration(secs: Long): String = when {
            secs < 60 -> "$secs s"
            secs % 3600 == 0L -> "${secs / 3600} h"
            secs >= 3600 -> "${secs / 3600} h ${(secs % 3600) / 60} min"
            secs % 60 == 0L -> "${secs / 60} min"
            else -> "${secs / 60} min ${secs % 60} s"
        }

        fun conditionText(c: String) = c.lowercase().replace('_', ' ').let { if (it == "clear") "and clear" else it }

        val BASE = listOf(
            ToolSpec("set_timer", "Start a countdown timer on this display (at most 3 run at once).",
                props("duration_seconds" to int("Length in seconds.", 1, 86_400),
                    "label" to str("Optional short name, e.g. \"Pasta\".")), listOf("duration_seconds")),
            ToolSpec("list_timers", "List the running, paused and finished timers."),
            ToolSpec("cancel_timer", "Cancel a timer. Give timer_id or label; with neither, the only timer is cancelled.",
                props("timer_id" to str("From list_timers."), "label" to str("The timer's name."))),
            ToolSpec("set_alarm", "Create an alarm on this display. Times are local, 24-hour.",
                props("hour" to int("0-23", 0, 23), "minute" to int("0-59", 0, 59),
                    "label" to str("Optional name."),
                    "days" to strArray("Repeat days; empty or omitted for a one-time alarm.", DAY_NAMES),
                    "leave_by" to bool("Ring earlier when the day's first calendar event with a place needs it (event start minus travel minus ready_minutes). Never later than the set time."),
                    "ready_minutes" to int("Getting-ready time for leave_by; default 45.", 0, 240),
                    "skip_holidays" to bool("Don't ring on public holidays or all-day holiday / day-off events."),
                    "mine" to bool("Make it the active person's own alarm instead of a shared one.")),
                listOf("hour", "minute")),
            ToolSpec("list_alarms", "List the alarms (with their ids) and when the next one rings."),
            ToolSpec("update_alarm", "Change an alarm's time, repeat days, or turn it on or off.",
                props("alarm_id" to str("From list_alarms."), "hour" to int("0-23", 0, 23), "minute" to int("0-59", 0, 59),
                    "enabled" to bool("On or off."), "days" to strArray("Repeat days.", DAY_NAMES),
                    "label" to str("New name."), "leave_by" to bool("Leave-by on or off."),
                    "ready_minutes" to int("Getting-ready time for leave_by.", 0, 240),
                    "skip_holidays" to bool("Skip public holidays.")),
                listOf("alarm_id")),
            ToolSpec("delete_alarm", "Delete an alarm for good. Unless the user plainly asked to delete this exact alarm, first ask them to confirm in your answer, then call with confirmed=true after they say yes. With confirmed=false nothing is deleted.",
                props("alarm_id" to str("From list_alarms."), "confirmed" to bool("True only if the user plainly asked for this deletion or just confirmed it.")),
                listOf("alarm_id", "confirmed")),
            ToolSpec("get_weather", "Current weather, today's high and low, a summary for tomorrow and the hourly forecast."),
            ToolSpec("get_calendar", "Calendar events for today or tomorrow. By default the active person's plus shared ones (everyone's if no one is active); person picks someone by name, or \"everyone\".",
                props("day" to str("Which day.", listOf("today", "tomorrow")), "person" to str("A household member's name, or \"everyone\".")), listOf("day")),
            ToolSpec("now_playing", "What's playing on this display (AirPlay, Spotify or another app): title, artist, source, whether it can skip or pause."),
            ToolSpec("media_control", "Control what's already playing on this display. It can't search for or start new music, and some sources (e.g. AirPlay) can't skip or pause: now_playing says.",
                props("action" to str("What to do.", listOf("play", "pause", "next", "previous"))), listOf("action")),
            ToolSpec("set_volume", "Set this display's speaker volume.",
                props("percent" to int("0-100", 0, 100)), listOf("percent")),
            ToolSpec("get_display", "This display's brightness and volume in percent, plus Do Not Disturb, Night mode, camera and microphone state."),
            ToolSpec("set_brightness", "Set this display's screen brightness. For \"brighter\" or \"dimmer\", read get_display and move about 20 points.",
                props("percent" to int("1-100", 1, 100)), listOf("percent")),
        )

        val HOME = listOf(
            ToolSpec("home_list", "List smart-home devices (Home Assistant) with their state.",
                props("domain" to str("Optional filter.", HOME_DOMAINS.sorted()))),
            ToolSpec("home_control", "Turn a smart-home device on or off (lights, switches, fans, covers, scenes, scripts, media players). Call home_list first to get entity ids.",
                props("entity_id" to str("e.g. light.bedside_lamp"),
                    "action" to str("What to do.", listOf("turn_on", "turn_off", "toggle")),
                    "brightness_percent" to int("Lights only, with turn_on.", 1, 100)),
                listOf("entity_id", "action")),
        )
    }
}
