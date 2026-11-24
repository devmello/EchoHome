package app.vesper.feature.clock

import app.vesper.core.model.Alarm
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.Nowcast
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class FarLayoutTest {
    private val zone = TimeZone.getTimeZone("UTC")
    /** Sunday, November 22, 2026, 22:30 UTC. */
    private val now = 1795386600000L
    private val hour = 3_600_000L
    private fun alarmIn(ms: Long) = NextAlarm(Alarm("a", 6, 15), now + ms)
    private fun weather(c: WeatherCondition = WeatherCondition.CLOUDY, nowcast: Nowcast? = null, night: Boolean = false) =
        Weather(54, 61, 47, c, night, nowcast = nowcast)

    @Test fun styles() {
        assertEquals(FarLayout.Style.OWN, FarLayout.styleOf("analog"))
        assertEquals(FarLayout.Style.OWN, FarLayout.styleOf("float"))
        assertEquals(FarLayout.Style.OWN, FarLayout.styleOf("glass"))
        for (id in listOf("digital", "world", "solar", "weather", "calendar")) assertEquals(id, FarLayout.Style.BIG_CLOCK, FarLayout.styleOf(id))
        assertEquals(FarLayout.Style.NONE, FarLayout.styleOf("photos"))
        assertEquals(400, FarLayout.weightOf("solar"))
        assertEquals(600, FarLayout.weightOf("digital"))
    }

    @Test fun alarmWithinTwelveHoursWins() {
        val f = FarFact.pick(now, alarmIn(7 * hour + 45 * 60_000L), weather(), is24 = false, zone = zone)
        assertEquals(FarFact.Kind.ALARM, f.kind)
        assertEquals("6:15 AM", f.text)
        assertEquals("06:15", FarFact.pick(now, alarmIn(7 * hour + 45 * 60_000L), null, is24 = true, zone = zone).text)
    }

    @Test fun farAlarmGivesWayToWeather() {
        val f = FarFact.pick(now, alarmIn(13 * hour), weather(), is24 = false, zone = zone)
        assertEquals(FarFact.Kind.WEATHER, f.kind)
        assertEquals("54°  Cloudy", f.text)
    }

    @Test fun pastAlarmIsIgnored() {
        assertEquals(FarFact.Kind.DATE, FarFact.pick(now, alarmIn(-60_000L), null, is24 = false, zone = zone).kind)
    }

    @Test fun rainSoonBeatsWeather() {
        // 15-min steps from now: dry, dry, rain → rain in 30 min
        val nc = Nowcast(now, listOf(0f, 0f, 0.4f, 0.8f))
        val f = FarFact.pick(now, null, weather(nowcast = nc), is24 = false, zone = zone)
        assertEquals(FarFact.Kind.RAIN, f.kind)
        assertEquals("Rain in 30 min", f.text)
        assertEquals("Rain now", FarFact.pick(now, null, weather(nowcast = Nowcast(now, listOf(0.5f))), false, zone).text)
    }

    @Test fun rainLaterOrAlreadyRainingIsJustTheWeather() {
        val later = Nowcast(now, listOf(0f, 0f, 0f, 0f, 0f, 0.3f)) // 75 min
        assertEquals(FarFact.Kind.WEATHER, FarFact.pick(now, null, weather(nowcast = later), false, zone).kind)
        val raining = FarFact.pick(now, null, weather(WeatherCondition.RAIN, Nowcast(now, listOf(1f))), false, zone)
        assertEquals(FarFact.Kind.WEATHER, raining.kind)
        assertEquals("54°  Rain", raining.text)
    }

    @Test fun clearNightSaysClear() {
        assertEquals("54°  Clear", FarFact.pick(now, null, weather(WeatherCondition.CLEAR, night = true), false, zone).text)
        assertEquals("54°  Sunny", FarFact.pick(now, null, weather(WeatherCondition.CLEAR), false, zone).text)
    }

    @Test fun noDataIsTheDate() {
        val f = FarFact.pick(now, null, null, is24 = false, zone = zone)
        assertEquals(FarFact.Kind.DATE, f.kind)
        assertEquals("Sunday 22", f.text)
    }

    @Test fun fadeMultipliesAlpha() {
        assertEquals(0x80FFFFFF.toInt(), fade(0xFFFFFFFF.toInt(), 128f / 255f))
        assertEquals(0x00BE180E, fade(0xFFBE180E.toInt(), 0f))
        assertEquals(0x66FF0000, fade(0x66FF0000, 1f))
    }
}
