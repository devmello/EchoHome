package app.vesper.feature.alarm

import android.content.Context
import android.text.format.DateFormat
import app.vesper.core.model.Alarm
import app.vesper.core.model.WeatherCondition
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text formats from the designs: "7:00" (no AM/PM, like StandBy), "Sunday, November 22", "8:15 AM". */
internal object AlarmFormat {
    fun is24(ctx: Context) = DateFormat.is24HourFormat(ctx)

    fun hm(ctx: Context, hour: Int, minute: Int): String =
        if (is24(ctx)) "%d:%02d".format(hour, minute) else "%d:%02d".format(if (hour % 12 == 0) 12 else hour % 12, minute)

    fun alarmTime(ctx: Context, a: Alarm) = hm(ctx, a.hour, a.minute)

    fun clock(ctx: Context, millis: Long): String {
        val t = local(millis)
        return hm(ctx, t.hour, t.minute)
    }

    /** "8:15 AM" (calendar events keep the AM/PM). */
    fun eventTime(ctx: Context, millis: Long): String =
        local(millis).format(DateTimeFormatter.ofPattern(if (is24(ctx)) "H:mm" else "h:mm a", Locale.getDefault()))

    fun dateLine(millis: Long): String = local(millis).format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))

    /** m:ss, or h:mm:ss from an hour (Timers.dc.html mmss: rounds up). */
    fun countdown(ms: Long): String {
        val s = ((ms.coerceAtLeast(0) + 999) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    /** "10 min" (Math.round(total / 60)), "45 sec" under a minute. */
    fun total(ms: Long): String = if (ms < 60_000) "${(ms + 999) / 1000} sec" else "${Math.round(ms / 60_000.0)} min"

    fun condition(c: WeatherCondition, night: Boolean): String = when (c) {
        WeatherCondition.CLEAR -> if (night) "Clear" else "Sunny"
        WeatherCondition.PARTLY_CLOUDY -> "Partly cloudy"
        WeatherCondition.CLOUDY -> "Cloudy"
        WeatherCondition.FOG -> "Fog"
        WeatherCondition.RAIN -> "Rain"
        WeatherCondition.POURING -> "Heavy rain"
        WeatherCondition.LIGHTNING -> "Thunderstorms"
        WeatherCondition.SNOW -> "Snow"
        WeatherCondition.SLEET -> "Sleet"
        WeatherCondition.HAIL -> "Hail"
        WeatherCondition.WIND -> "Windy"
        WeatherCondition.EXCEPTIONAL -> "—"
    }

    private fun local(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
}
