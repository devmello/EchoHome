package app.vesper.feature.home.glance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** (LocalDate.ofInstant is API 34; the Echo is API 30.) */
fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "8:20 AM", as in the designs. */
fun clock12(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val h = t.hour % 12
    return "${if (h == 0) 12 else h}:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
}
