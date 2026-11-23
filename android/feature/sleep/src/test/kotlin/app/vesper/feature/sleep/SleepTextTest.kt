package app.vesper.feature.sleep

import app.vesper.core.model.BedtimeReport
import app.vesper.core.model.SettingType
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTextTest {
    private val zone = ZoneId.of("UTC")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test fun `timer status per state`() {
        val now = at("2026-11-23T23:00:00")
        assertEquals("Plays 30 min, then fades out", SleepText.timerStatus(SleepState(), 30, 0, now, zone, false, null))
        assertEquals("Plays until you stop it", SleepText.timerStatus(SleepState(), 0, 0, now, zone, false, null))
        val playing = SleepState(SleepSound.RAIN, true, 30, endsAtElapsed = 28 * 60_000L)
        assertEquals("Stops at 11:28 PM · 28 min left", SleepText.timerStatus(playing, 30, 0, now, zone, false, null))
        assertEquals("Stops at 23:28 · 28 min left", SleepText.timerStatus(playing, 30, 0, now, zone, true, null))
        assertEquals("Fading out…", SleepText.timerStatus(playing.copy(fading = true), 30, 0, now, zone, false, null))
        assertEquals("Paused · 12 min left", SleepText.timerStatus(SleepState(SleepSound.RAIN, false, 30), 30, 0, now, zone, false, 11 * 60_000L + 10))
        assertEquals("1 h 30 min left", SleepText.minutesLeft(90 * 60_000L))
        assertEquals("1 h left", SleepText.minutesLeft(60 * 60_000L))
    }

    @Test fun `routine preview and answer`() {
        assertEquals("Good night: Do Not Disturb until 7:00 AM, Night mode and Rain for 30 min",
            SleepText.routinePreview(true, true, false, SleepSound.RAIN, 30, "7:00 AM"))
        assertEquals("Good night: Night mode", SleepText.routinePreview(false, true, false, null, 30, null))
        assertEquals("Good night shows your next alarm", SleepText.routinePreview(false, false, false, null, 0, null))
        val r = BedtimeReport(at("2026-11-24T07:00:00"), "Wake up", true, null, true, SleepSound.RAIN, 30, null)
        assertEquals("Good night. Your alarm is at 7:00 AM.", SleepText.goodNight(r, zone, false))
        assertEquals("Good night. No alarm is set.", SleepText.goodNight(r.copy(alarmAtMillis = null), zone, false))
    }

    @Test fun `settings defaults and parsing`() {
        val s = MemSettings()
        assertEquals(SleepSound.RAIN, SleepSettings.sound(s))
        assertEquals(30, SleepSettings.timer(s))
        assertEquals(7 to 0, SleepSettings.wakeFallback(s))
        s.putString(SleepSettings.TIMER, "45") // not a choice
        assertEquals(30, SleepSettings.timer(s))
        s.putString(SleepSettings.TIMER, "0")
        assertEquals(0, SleepSettings.timer(s))
        s.putString(SleepSettings.SOUND, "nonsense")
        assertEquals(SleepSound.RAIN, SleepSettings.sound(s))
        s.putString(SleepSettings.WAKE_FALLBACK, "25:00")
        assertEquals(7 to 0, SleepSettings.wakeFallback(s))
        // every key is unique and every CHOICE default is one of its options
        val keys = SleepSettings.section.settings.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (spec in SleepSettings.section.settings.filter { it.type == SettingType.CHOICE })
            assertTrue(spec.key, spec.options.any { it.first == spec.default })
        assertTrue(keys.none { it.startsWith("sleep.state.") }) // internal state isn't a user setting
    }

    @Test fun `sound names`() {
        assertEquals(SleepSound.BROWN, SleepSound.of("brown"))
        assertEquals(SleepSound.BROWN, SleepSound.of("Brown noise"))
        assertEquals(SleepSound.WHITE, SleepSound.of(" WHITE "))
        assertEquals(SleepSound.CRICKETS, SleepSound.of("crickets"))
        assertNull(SleepSound.of("thunder"))
    }
}
