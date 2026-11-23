package app.vesper.feature.assistant.llm

import android.os.Bundle
import app.vesper.core.model.Banner
import app.vesper.core.model.NightMode
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.VesperServices
import app.vesper.core.model.VesperTimer
import app.vesper.feature.assistant.llm.Schema.bool
import app.vesper.feature.assistant.llm.Schema.int
import app.vesper.feature.assistant.llm.Schema.props
import app.vesper.feature.assistant.llm.Schema.str
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The tools beyond timers / alarms / basic media (which live in [VesperTools]): next alarm, timer
 * pause / resume, Echo controls (Do Not Disturb, Night mode, camera), screens and profiles, air
 * quality, rain nowcast, travel time, the iPhone's battery, and notes on the screen.
 * Same rules as [VesperTools]: runs on the main thread, slots read at call time.
 */
internal class EchoTools(
    private val zone: () -> ZoneId,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
) {
    /** null = not one of these tools. */
    suspend fun run(s: VesperServices, name: String, input: JSONObject): ToolOutcome? = when (name) {
        "next_alarm" -> nextAlarm(s)
        "set_alarm_enabled" -> alarmEnabled(s, input)
        "pause_timer" -> pauseResume(s, input, pause = true)
        "resume_timer" -> pauseResume(s, input, pause = false)
        "set_do_not_disturb" -> dnd(s, input)
        "set_night_mode" -> night(s, input)
        "set_camera" -> camera(s, input)
        "open_screen" -> openScreen(s, input)
        "switch_profile" -> switchProfile(s, input)
        "get_air_quality" -> air(s)
        "get_rain_nowcast" -> rain(s)
        "get_travel_time" -> travel(s, input)
        "get_phone_battery" -> phone(s)
        "show_note" -> note(s, input)
        else -> null
    }

    // ---- alarms ----

    private fun nextAlarm(s: VesperServices): ToolOutcome {
        val n = s.alarms.next.value ?: return ToolOutcome(JSONObject().put("next", JSONObject.NULL).put("message", "No alarm is set to ring."))
        val a = n.alarm
        val at = Instant.ofEpochMilli(n.triggerAtMillis).atZone(zone())
        val out = JSONObject().put("alarm_id", a.id).put("label", a.label)
            .put("rings_at", at.toLocalDateTime().withNano(0).toString())
            .put("in_minutes", ((n.triggerAtMillis - wall()) / 60_000L).coerceAtLeast(0))
            .put("set_time", "%02d:%02d".format(a.hour, a.minute))
        n.reason?.let { out.put("reason", it) }
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(wall()).atZone(zone()).toLocalDate()
        val day = if (sameDay) "" else " · " + at.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
        return ToolOutcome(out, card = ResultCard.Action(a.label, VesperTools.clock(at.hour, at.minute) + day + if (n.reason != null) " · earlier" else "", Icon.ALARM))
    }

    private fun alarmEnabled(s: VesperServices, input: JSONObject): ToolOutcome {
        val a = s.alarms.alarms.value.firstOrNull { it.id == input.getString("alarm_id") }
            ?: return ToolOutcome.error("NOT_FOUND", "No alarm with that id; call list_alarms.")
        val on = input.getBoolean("enabled")
        s.alarms.setEnabled(a.id, on)
        return ToolOutcome(JSONObject().put("alarm_id", a.id).put("enabled", on),
            card = ResultCard.Action(a.label, if (on) VesperTools.clock(a.hour, a.minute) else "Off", Icon.ALARM))
    }

    // ---- timers ----

    private fun pauseResume(s: VesperServices, input: JSONObject, pause: Boolean): ToolOutcome {
        val list = s.timers.timers.value
        val t = resolveTimer(list, input) ?: return timerNotFound(list)
        val ok = if (pause) t.state == VesperTimer.State.RUNNING else t.state == VesperTimer.State.PAUSED
        if (!ok) return ToolOutcome.error(if (pause) "NOT_RUNNING" else "NOT_PAUSED", "That timer is ${t.state.name.lowercase()}.")
        if (pause) s.timers.pause(t.id) else s.timers.resume(t.id)
        val left = VesperTools.duration(t.remainingMs(elapsed()) / 1000)
        return ToolOutcome(JSONObject().put("timer_id", t.id).put("state", if (pause) "paused" else "running"),
            card = ResultCard.Action(t.label.ifEmpty { "Timer" }, if (pause) "Paused · $left left" else "Running · $left left", Icon.TIMER))
    }

    // ---- Echo controls ----

    private fun dnd(s: VesperServices, input: JSONObject): ToolOutcome {
        val on = input.getBoolean("on")
        return if (s.systemControls.setDoNotDisturb(on))
            ToolOutcome(JSONObject().put("do_not_disturb", on), card = ResultCard.Action("Do Not Disturb", if (on) "On" else "Off", Icon.POWER))
        else ToolOutcome.error("FAILED", "Couldn't change Do Not Disturb.")
    }

    private fun night(s: VesperServices, input: JSONObject): ToolOutcome {
        val mode = NightMode.valueOf(input.getString("mode").uppercase())
        return if (s.systemControls.setNightMode(mode))
            ToolOutcome(JSONObject().put("night_mode", mode.name.lowercase()),
                card = ResultCard.Action("Night mode", when (mode) { NightMode.AUTO -> "Automatic"; NightMode.ON -> "On"; NightMode.OFF -> "Off" }, Icon.BRIGHTNESS))
        else ToolOutcome.error("FAILED", "Couldn't change Night mode.")
    }

    private fun camera(s: VesperServices, input: JSONObject): ToolOutcome {
        val on = input.getBoolean("enabled")
        return if (s.systemControls.setCameraDisabled(!on))
            ToolOutcome(JSONObject().put("camera_enabled", on), card = ResultCard.Action("Camera", if (on) "On" else "Off", Icon.POWER))
        else ToolOutcome.error("FAILED", "Couldn't change the camera.")
    }

    // ---- screens and profiles ----

    private fun openScreen(s: VesperServices, input: JSONObject): ToolOutcome {
        val key = input.getString("screen")
        val target = SCREENS[key] ?: return ToolOutcome.error("UNKNOWN_SCREEN", "Screens: ${SCREENS.keys.joinToString()}")
        val args = target.second?.let { face -> Bundle().apply { putString("face", face) } }
        s.navigator.show(target.first, args)
        val icon = when (key) { "now_playing" -> Icon.PLAY; "timers" -> Icon.TIMER; "calendar" -> Icon.CALENDAR; "lists" -> Icon.LIST; else -> Icon.POWER }
        return ToolOutcome(JSONObject().put("opened", key), card = ResultCard.Action(SCREEN_TITLES[key] ?: key.replaceFirstChar(Char::uppercase), "Opened", icon))
    }

    private fun switchProfile(s: VesperServices, input: JSONObject): ToolOutcome {
        val want = input.getString("profile").trim()
        val list = s.profiles.profiles.value
        val p = list.firstOrNull { it.id.equals(want, true) || it.name.equals(want, true) }
            ?: if (want.equals("everyone", true) || want.equals("shared", true)) Profile.SHARED else null
        p ?: return ToolOutcome.error("NOT_FOUND", "Profiles: ${list.joinToString { it.name }}")
        s.profiles.switchTo(p.id, SwitchReason.MANUAL)
        return ToolOutcome(JSONObject().put("active_profile", p.name), card = ResultCard.Action(p.name, "Profile", Icon.POWER))
    }

    // ---- info ----

    private fun air(s: VesperServices): ToolOutcome {
        val w = s.weather.weather.value ?: return ToolOutcome.error("UNAVAILABLE", "Weather isn't available right now.")
        val a = w.air ?: return ToolOutcome.error("UNAVAILABLE", "Air quality isn't available right now.")
        val o = JSONObject()
        a.usAqi?.let { o.put("us_aqi", it); o.put("aqi_category", a.aqiCategory) }
        a.uvIndex?.let { o.put("uv_now", round1(it)) }
        a.uvIndexMax?.let { o.put("uv_max_today", round1(it)) }
        o.put("pollen_grains_m3", JSONObject().also { p -> a.pollen.forEach { (k, v) -> p.put(k, round1(v)) } })
        if (a.pollen.isEmpty()) o.put("pollen_note", "No pollen data for this location.")
        val sub = listOfNotNull(a.usAqi?.let { "AQI $it" }, a.uvIndexMax?.let { "UV ${round1(it)}" }).joinToString(" · ")
        return ToolOutcome(o, card = ResultCard.Action("Air quality", listOfNotNull(a.aqiCategory, sub.ifEmpty { null }).joinToString(" · "), Icon.BRIGHTNESS))
    }

    private fun rain(s: VesperServices): ToolOutcome {
        val w = s.weather.weather.value ?: return ToolOutcome.error("UNAVAILABLE", "Weather isn't available right now.")
        val n = w.nowcast ?: return ToolOutcome.error("UNAVAILABLE", "The short-term rain forecast isn't available right now.")
        val now = wall()
        val end = n.startMillis + n.precipMm.size * n.stepMinutes * 60_000L
        if (end <= now) return ToolOutcome.error("STALE", "The short-term rain forecast is out of date.")
        val until = n.minutesUntilRain(now)
        val window = ((end - now) / 60_000L).toInt()
        val o = JSONObject().put("window_minutes", window)
            .put("raining_now", until == 0)
            .put("minutes_until_rain", until ?: JSONObject.NULL)
            .put("peak_mm_per_${n.stepMinutes}min", round1(n.precipMm.maxOrNull() ?: 0f))
        val text = when (until) { null -> "Dry for $window min"; 0 -> "Raining now"; else -> "Rain in $until min" }
        return ToolOutcome(o, card = ResultCard.Weather("Rain check", text, w.location, rain = until != null))
    }

    private suspend fun travel(s: VesperServices, input: JSONObject): ToolOutcome {
        val dest = input.getString("destination").trim()
        if (dest.isEmpty()) return ToolOutcome.error("INVALID_INPUT", "Give a place.")
        val depart = wall() + (if (input.has("depart_in_minutes")) input.getInt("depart_in_minutes") else 0) * 60_000L
        val mins = withTimeoutOrNull(10_000) { s.travel.minutesTo(dest, depart) }
            ?: return ToolOutcome.error("UNKNOWN", "Couldn't work out the travel time to that place.")
        return ToolOutcome(JSONObject().put("destination", dest).put("minutes", mins),
            card = ResultCard.Action(dest.take(40), "${VesperTools.duration(mins * 60L)} from home", Icon.CALENDAR))
    }

    private fun phone(s: VesperServices): ToolOutcome {
        val p = s.phone.value ?: return ToolOutcome.error("NOT_PAIRED", "No iPhone is paired with this display.")
        val o = JSONObject().put("phone", p.deviceName).put("connected", p.connected)
        if (!p.connected) return ToolOutcome(o.put("message", "The phone isn't connected right now."))
        val b = p.batteryPercent ?: return ToolOutcome(o.put("message", "The phone doesn't share its battery level."))
        o.put("battery_percent", b)
        p.charging?.let { o.put("charging", it) }
        return ToolOutcome(o, card = ResultCard.Action(p.deviceName, "$b%" + if (p.charging == true) " · charging" else "", Icon.POWER))
    }

    private fun note(s: VesperServices, input: JSONObject): ToolOutcome {
        val text = input.getString("text").trim()
        if (text.isEmpty()) return ToolOutcome.error("INVALID_INPUT", "The note is empty.")
        val title = input.optString("title", "").trim().ifEmpty { "Note" }
        s.notifications.post(Banner(id = "assistant-note:" + UUID.randomUUID().toString().take(8), appName = "Assistant",
            appColor = 0xFF0A84FF.toInt(), glyph = "message", title = title, body = text))
        val o = JSONObject().put("shown", true)
        if (s.system.state.value.doNotDisturb) o.put("warning", "Do Not Disturb is on, so the banner may not pop up.")
        return ToolOutcome(o, card = ResultCard.Action(title.take(30), "Shown on screen", Icon.CALENDAR))
    }

    private fun round1(v: Float) = Math.round(v * 10f) / 10.0

    companion object {
        /** voice name -> (screen id, StandBy face to open it on). */
        val SCREENS: Map<String, Pair<String, String?>> = linkedMapOf(
            "clock" to ("standby" to null),
            "weather" to ("standby" to "weather"),
            "calendar" to ("standby" to "calendar"),
            "now_playing" to ("nowplaying" to null),
            "timers" to ("timers" to null),
            "lists" to ("lists" to null),
            "commute" to ("commute" to null),
            "stocks" to ("stocks" to null),
            "home" to ("home" to null),
            "photos" to ("photos" to null),
            "profiles" to ("profiles" to null),
            "phone" to ("phone-pairing" to null),
            "settings" to ("settings-pair" to null),
        )
        private val SCREEN_TITLES = mapOf("now_playing" to "Now playing", "phone" to "iPhone", "lists" to "Reminders & lists")

        fun resolveTimer(list: List<VesperTimer>, input: JSONObject): VesperTimer? {
            val id = input.optString("timer_id", "")
            val label = input.optString("label", "")
            return when {
                id.isNotEmpty() -> list.firstOrNull { it.id == id }
                label.isNotEmpty() -> list.firstOrNull { it.label.equals(label, ignoreCase = true) }
                list.size == 1 -> list[0]
                else -> null
            }
        }

        fun timerNotFound(list: List<VesperTimer>) = ToolOutcome.error("NOT_FOUND",
            if (list.isEmpty()) "No timers are running." else "Say which timer: ${list.joinToString { it.label.ifEmpty { it.id } }}")

        private fun timerRef() = props("timer_id" to str("From list_timers."), "label" to str("The timer's name."))

        val SPECS = listOf(
            ToolSpec("next_alarm", "When the next alarm rings, with the reason if it rings earlier than set (leave-by) or skips a holiday."),
            ToolSpec("set_alarm_enabled", "Turn an existing alarm on or off without changing it.",
                props("alarm_id" to str("From list_alarms."), "enabled" to bool("On or off.")), listOf("alarm_id", "enabled")),
            ToolSpec("pause_timer", "Pause a running timer. Give timer_id or label; with neither, the only timer.", timerRef()),
            ToolSpec("resume_timer", "Resume a paused timer. Give timer_id or label; with neither, the only timer.", timerRef()),
            ToolSpec("set_do_not_disturb", "Turn Do Not Disturb on or off (hides banners except doorbell, alarms and calls).",
                props("on" to bool("True = on.")), listOf("on")),
            ToolSpec("set_night_mode", "Night mode: red, dim display. auto follows the light sensor and schedule.",
                props("mode" to str("Which mode.", listOf("auto", "on", "off"))), listOf("mode")),
            ToolSpec("set_camera", "Turn the camera on or off (it recognises who is there). Not the microphone.",
                props("enabled" to bool("True = camera on.")), listOf("enabled")),
            ToolSpec("open_screen", "Show a screen on the display. weather and calendar are StandBy faces; settings shows the address of the settings page.",
                props("screen" to str("Which screen.", SCREENS.keys.toList())), listOf("screen")),
            ToolSpec("switch_profile", "Make a household member the active profile (their face, alarms, calendar). \"everyone\" is the shared profile.",
                props("profile" to str("The person's name.")), listOf("profile")),
            ToolSpec("get_air_quality", "Air quality (US AQI), UV index and pollen where available."),
            ToolSpec("get_rain_nowcast", "Will it rain in the next hour or two: minutes until rain starts, or dry. Use it for \"do I need an umbrella\"."),
            ToolSpec("get_travel_time", "Travel time from home to a place (address or name).",
                props("destination" to str("Address or place name."),
                    "depart_in_minutes" to int("Leave this many minutes from now (default 0).", 0, 1440)), listOf("destination")),
            ToolSpec("get_phone_battery", "The paired iPhone's battery level and whether it's charging."),
            ToolSpec("show_note", "Show a short note on the display as a banner right now (something to read). For a reminder at a time, use add_reminder.",
                props("text" to str("The note, up to 200 characters.", max = 200), "title" to str("Optional heading.", max = 40)), listOf("text")),
        )
    }
}
