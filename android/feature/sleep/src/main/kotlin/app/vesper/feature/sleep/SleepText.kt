package app.vesper.feature.sleep

import app.vesper.core.model.BedtimeReport
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** The Sleep screen's words (pure, unit-tested). */
internal object SleepText {
    private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun clock(wallMs: Long, zone: ZoneId, h24: Boolean): String =
        Instant.ofEpochMilli(wallMs).atZone(zone).format(if (h24) H24 else H12)

    fun minutesLeft(ms: Long): String {
        val m = ceil(ms / 60_000.0).toLong().coerceAtLeast(1)
        return if (m >= 60 && m % 60 == 0L) "${m / 60} h left" else if (m > 60) "${m / 60} h ${m % 60} min left" else "$m min left"
    }

    /** The timer platter's status line. [nowElapsed]/[nowWall] convert the elapsed end to a clock time. */
    fun timerStatus(st: SleepState, pickedTimer: Int, nowElapsed: Long, nowWall: Long, zone: ZoneId, h24: Boolean, pausedLeftMs: Long?): String = when {
        st.sound == null -> if (pickedTimer > 0) "Plays $pickedTimer min, then fades out" else "Plays until you stop it"
        !st.playing -> if (pausedLeftMs != null) "Paused · ${minutesLeft(pausedLeftMs)}" else "Paused"
        st.fading -> "Fading out…"
        st.endsAtElapsed != null -> {
            val left = st.endsAtElapsed!! - nowElapsed
            "Stops at ${clock(nowWall + left, zone, h24)} · ${minutesLeft(left)}"
        }
        else -> "Plays until you stop it"
    }

    /** What "Good night" will do, from the settings (the footer line). */
    fun routinePreview(dnd: Boolean, night: Boolean, dim: Boolean, sound: SleepSound?, timer: Int, untilText: String?): String {
        val parts = ArrayList<String>()
        if (dnd) parts += if (untilText != null) "Do Not Disturb until $untilText" else "Do Not Disturb"
        if (night) parts += "Night mode"
        if (dim) parts += "a dim screen"
        if (sound != null) parts += if (timer > 0) "${sound.title} for $timer min" else sound.title
        if (parts.isEmpty()) return "Good night shows your next alarm"
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Good night: $list"
    }

    /** One line after the routine ran (the screen's subtitle, the demo answer's shape). */
    fun goodNight(r: BedtimeReport, zone: ZoneId, h24: Boolean): String {
        val alarm = r.alarmAtMillis?.let { "Your alarm is at ${clock(it, zone, h24)}." } ?: "No alarm is set."
        return "Good night. $alarm"
    }
}
