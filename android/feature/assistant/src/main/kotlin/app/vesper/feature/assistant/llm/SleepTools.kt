package app.vesper.feature.assistant.llm

import app.vesper.core.model.BedtimeRequest
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.llm.Schema.bool
import app.vesper.feature.assistant.llm.Schema.int
import app.vesper.feature.assistant.llm.Schema.props
import app.vesper.feature.assistant.llm.Schema.str
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sleep sounds and the bedtime routine (services.sleep, `:feature:sleep`):
 *  - `bedtime` ("good night", "bedtime"): next alarm, Do Not Disturb until it, Night mode, the
 *    default sleep sound with its timer, optional dim. The answer says when the alarm is.
 *  - `sleep_sound`: play / stop a sound, or change the sleep timer.
 * Same rules as [VesperTools]: runs on the main thread, slots read at call time.
 */
internal class SleepTools(private val zone: () -> ZoneId) {
    /** null = not one of these tools. */
    fun run(s: VesperServices, name: String, input: JSONObject): ToolOutcome? = when (name) {
        "bedtime" -> bedtime(s, input)
        "sleep_sound" -> sleepSound(s, input)
        else -> null
    }

    private fun bedtime(s: VesperServices, input: JSONObject): ToolOutcome {
        if (!s.sleep.available) return ToolOutcome.error("NOT_AVAILABLE", "The bedtime routine isn't on this display.")
        val sound = if (input.has("sound")) SleepSound.of(input.getString("sound")) else null
        val req = BedtimeRequest(
            sound = sound,
            timerMinutes = if (input.has("timer_minutes")) input.getInt("timer_minutes") else null,
            playSound = if (input.has("play_sound")) input.getBoolean("play_sound") else null,
            dim = if (input.has("dim")) input.getBoolean("dim") else null,
        )
        val r = s.sleep.bedtime(req)
        val out = JSONObject()
            .put("next_alarm", r.alarmAtMillis?.let { iso(it) } ?: JSONObject.NULL)
            .put("do_not_disturb", r.dndOn)
            .put("night_mode", r.nightOn)
            .put("sleep_sound", r.sound?.title ?: JSONObject.NULL)
        r.alarmLabel?.let { out.put("alarm_label", it) }
        r.dndUntilMillis?.let { out.put("do_not_disturb_until", iso(it)) }
        if (r.sound != null) out.put("timer_minutes", r.timerMinutes)
        r.dimmedTo?.let { out.put("brightness_percent", (it * 100).toInt()) }
        if (r.failed.isNotEmpty()) out.put("failed", JSONArray(r.failed))
        out.put("say", if (r.alarmAtMillis != null) "Tell them when the alarm rings." else "No alarm is set; say so and offer to set one.")
        val alarm = r.alarmAtMillis?.let { "Alarm " + clock(it) } ?: "No alarm"
        val soundText = r.sound?.let { " · ${it.title}" + if (r.timerMinutes > 0) " ${r.timerMinutes} min" else "" } ?: ""
        return ToolOutcome(out, card = ResultCard.Action("Good night", alarm + soundText, Icon.ALARM))
    }

    private fun sleepSound(s: VesperServices, input: JSONObject): ToolOutcome {
        val sl = s.sleep
        if (!sl.available) return ToolOutcome.error("NOT_AVAILABLE", "Sleep sounds aren't on this display.")
        val timer = if (input.has("timer_minutes")) input.getInt("timer_minutes") else null
        return when (input.getString("action")) {
            "play" -> {
                val sound = if (input.has("sound")) SleepSound.of(input.getString("sound"))
                    ?: return ToolOutcome.error("UNKNOWN_SOUND", "Sounds: ${SleepSound.values().joinToString { it.title }}") else null
                if (!sl.play(sound, timer)) return ToolOutcome.error("FAILED", "Couldn't start the sound (something else may be using the speaker).")
                val st = sl.state.value
                ToolOutcome(JSONObject().put("playing", st.sound?.title).put("timer_minutes", st.timerMinutes),
                    card = ResultCard.Action(st.sound?.title ?: "Sleep sound", if (st.timerMinutes > 0) "${st.timerMinutes} min, then fades out" else "Until you stop it", Icon.PLAY))
            }
            "stop" -> {
                val was = sl.state.value.sound
                sl.stop()
                ToolOutcome(JSONObject().put("stopped", was?.title ?: JSONObject.NULL),
                    card = ResultCard.Action(was?.title ?: "Sleep sound", "Stopped", Icon.PAUSE))
            }
            "set_timer" -> {
                val m = timer ?: return ToolOutcome.error("NEEDS_TIMER", "Give timer_minutes (0 = off).")
                if (sl.state.value.sound == null) return ToolOutcome.error("NOTHING_PLAYING", "No sleep sound is playing.")
                sl.setTimer(m)
                ToolOutcome(JSONObject().put("timer_minutes", m),
                    card = ResultCard.Action(sl.state.value.sound?.title ?: "Sleep sound", if (m > 0) "$m min timer" else "Timer off", Icon.TIMER))
            }
            else -> ToolOutcome.error("BAD_ACTION", "action is play, stop or set_timer.")
        }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDateTime().withNano(0).toString()

    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone()).let { VesperTools.clock(it.hour, it.minute) }

    companion object {
        private val SOUNDS = SleepSound.values().map { it.id }
        private val TIMERS = SleepSounds.TIMER_CHOICES.sorted()

        val SPECS = listOf(
            ToolSpec("bedtime", "The bedtime routine, for \"good night\" or \"bedtime\": reads the next alarm, turns on Do Not Disturb until it, switches Night mode on and starts the user's sleep sound with its timer (per their settings). Answer with when the alarm rings, e.g. \"Good night. Your alarm is at 7.\" Only pass options the user asked for.",
                props("sound" to str("A different sleep sound than the default.", SOUNDS),
                    "timer_minutes" to int("Sleep timer in minutes (15, 30, 60 or 90; 0 = no timer).", 0, 90),
                    "play_sound" to bool("false = no sleep sound tonight."),
                    "dim" to bool("Dim the screen too."))),
            ToolSpec("sleep_sound", "Play or stop a sleep sound generated on this display (rain, ocean, white, pink or brown noise, fan, crickets), or change its sleep timer. The sound fades out over 30 s when the timer ends.",
                props("action" to str("What to do.", listOf("play", "stop", "set_timer")),
                    "sound" to str("For play; default = the user's default sound.", SOUNDS),
                    "timer_minutes" to int("Sleep timer: one of $TIMERS (0 = until stopped). Default = the user's setting.", 0, 90)),
                listOf("action")),
        )
    }
}
