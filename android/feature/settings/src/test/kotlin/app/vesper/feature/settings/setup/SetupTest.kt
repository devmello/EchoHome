package app.vesper.feature.settings.setup

import app.vesper.feature.settings.FakeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTest {
    @Test fun freshInstallShowsSetupAndRemembersIt() {
        val s = FakeSettingsStore()
        assertTrue(FirstRun.resolve(s, hadSettings = false))
        assertEquals(false, s.map[FirstRun.KEY_DONE])
        // Next start: other features have written keys by now, but the stored answer wins.
        s.putString("airplay.hwaddr", "76a341af9034")
        assertTrue(FirstRun.resolve(s, hadSettings = true))
    }

    @Test fun existingInstallIsNeverInterrupted() {
        val s = FakeSettingsStore()
        s.putString("device.name", "Echo Show")
        assertFalse(FirstRun.resolve(s, hadSettings = true))
        assertEquals(true, s.map[FirstRun.KEY_DONE])
        assertFalse(FirstRun.resolve(s, hadSettings = true))
    }

    @Test fun doneAndRunAgain() {
        val s = FakeSettingsStore()
        assertTrue(FirstRun.resolve(s, hadSettings = false))
        FirstRun.markDone(s)
        assertFalse(FirstRun.resolve(s, hadSettings = false))
        FirstRun.reset(s)
        assertTrue(FirstRun.resolve(s, hadSettings = true))
    }

    @Test fun storedReadsAbsentBooleanAndText() {
        val s = FakeSettingsStore()
        assertNull(FirstRun.stored(s))
        s.putBoolean(FirstRun.KEY_DONE, true)
        assertEquals(true, FirstRun.stored(s))
        s.putBoolean(FirstRun.KEY_DONE, false)
        assertEquals(false, FirstRun.stored(s))
        // The settings page may store a value as text.
        s.map[FirstRun.KEY_DONE] = "true"
        assertEquals(true, FirstRun.stored(s))
        s.map[FirstRun.KEY_DONE] = "nonsense"
        assertNull(FirstRun.stored(s))
    }

    @Test fun onlyTheGateKeyDoesNotCount() {
        assertFalse(FirstRun.countsAsSettings(FirstRun.KEY_DONE))
        assertTrue(FirstRun.countsAsSettings("clock.face"))
    }
}

class SetupFlowTest {
    @Test fun walksForwardAndBack() {
        val f = SetupFlow()
        assertEquals(SetupStep.WELCOME, f.step)
        assertFalse(f.canBack)
        assertFalse(f.back())
        repeat(SetupStep.entries.size - 1) { assertTrue(f.next()) }
        assertEquals(SetupStep.DONE, f.step)
        assertTrue(f.isLast)
        assertFalse(f.next())
        assertTrue(f.back())
        assertEquals(SetupStep.CLOCK, f.step)
    }

    @Test fun onlyWelcomeAndDoneAreRequired() {
        val required = SetupStep.entries.filter { !it.optional }
        assertEquals(listOf(SetupStep.WELCOME, SetupStep.DONE), required)
    }

    @Test fun labels() {
        val f = SetupFlow()
        assertEquals("Get started", f.rightLabel(engaged = false))
        f.next()
        assertEquals(SetupStep.WIFI, f.step)
        assertEquals("Skip", f.rightLabel(engaged = false))
        assertEquals("Next", f.rightLabel(engaged = true))
        f.go(SetupStep.DONE)
        assertEquals("Done", f.rightLabel(engaged = false))
    }

    @Test fun skipsAreRecordedAndUndone() {
        val f = SetupFlow()
        f.next()                    // Welcome: required, never "skipped"
        assertTrue(f.skipped.isEmpty())
        f.next(engaged = false)     // Wi-Fi skipped
        assertEquals(setOf(SetupStep.WIFI), f.skipped)
        f.back()
        f.next(engaged = true)      // came back and connected
        assertTrue(f.skipped.isEmpty())
        f.restart()
        assertEquals(SetupStep.WELCOME, f.step)
    }

    @Test fun jumpToAStep() {
        val f = SetupFlow()
        assertTrue(f.go(SetupStep.PEOPLE))
        assertEquals(SetupStep.PEOPLE, f.step)
        assertFalse(f.go(SetupStep.PEOPLE))
    }

    @Test fun parsesStepArgs() {
        assertEquals(SetupStep.WELCOME, SetupStep.parse("1"))
        assertEquals(SetupStep.WIFI, SetupStep.parse("2"))
        assertEquals(SetupStep.DONE, SetupStep.parse("8"))
        assertEquals(SetupStep.PHONE, SetupStep.parse(" Phone "))
        assertNull(SetupStep.parse("0"))
        assertNull(SetupStep.parse("9"))
        assertNull(SetupStep.parse("lamp"))
        assertNull(SetupStep.parse(""))
        assertNull(SetupStep.parse(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stepsMustStartWithWelcomeAndEndWithDone() {
        SetupFlow(listOf(SetupStep.WIFI, SetupStep.DONE))
    }
}

class SetupChoicesTest {
    @Test fun cleansNames() {
        assertEquals("Kitchen", SetupChoices.cleanName("  Kitchen "))
        assertEquals("Guest Room", SetupChoices.cleanName("Guest \t  Room"))
        assertEquals("AB", SetupChoices.cleanName("A\u0007B"))
        assertNull(SetupChoices.cleanName("   "))
        assertEquals(SetupChoices.NAME_MAX, SetupChoices.cleanName("x".repeat(80))!!.length)
        assertEquals("Echo Show", SetupChoices.NAMES.first())
    }

    @Test fun temperatureUnitFollowsTheWeatherRule() {
        assertTrue(SetupChoices.fahrenheit(null, "US"))
        assertFalse(SetupChoices.fahrenheit("", "DE"))
        assertFalse(SetupChoices.fahrenheit("c", "US"))
        assertTrue(SetupChoices.fahrenheit("F", "GB"))
    }

    @Test fun nightModes() {
        assertEquals(SetupChoices.Night.AUTO, SetupChoices.Night.of(null))
        assertEquals(SetupChoices.Night.AUTO, SetupChoices.Night.of("on"))
        assertEquals(SetupChoices.Night.SCHEDULE, SetupChoices.Night.of("schedule"))
        assertEquals(SetupChoices.Night.OFF, SetupChoices.Night.of(" OFF "))
        assertEquals(listOf("auto", "schedule", "off"), SetupChoices.Night.entries.map { it.stored })
    }

    @Test fun scheduleText() {
        assertEquals("10 PM to 7 AM", SetupChoices.scheduleText("22:00", "07:00", is24 = false))
        assertEquals("9:30 PM to 12 AM", SetupChoices.scheduleText("21:30", "00:00", is24 = false))
        assertEquals("22:00 to 07:00", SetupChoices.scheduleText("22:00", "7:00", is24 = true))
    }

    @Test fun faceIndex() {
        val ids = listOf("digital", "analog", "world")
        assertEquals(1, SetupChoices.faceIndex(ids, "analog"))
        assertEquals(0, SetupChoices.faceIndex(ids, "gone"))
        assertEquals(0, SetupChoices.faceIndex(ids, null))
    }

    @Test fun usesTheFeaturesOwnKeys() {
        assertEquals("weather.unit", SetupChoices.KEY_UNIT)
        assertEquals("clock.face", SetupChoices.KEY_FACE)
        assertEquals("clock.night.mode", SetupChoices.KEY_NIGHT_MODE)
        assertEquals("assistant.wakeWord", SetupChoices.KEY_WAKE_WORD)
    }
}

class SetupResumeTest {
    private fun SetupResume.feed(needed: Boolean, vararg ids: String) = ids.map { onScreen(it, needed) }

    @Test fun firstRunComesBackAtStandByAfterAnotherScreen() {
        val r = SetupResume()
        // Setup open, then `--es screen lamp`, then Back from the lamp: the setup opens again at StandBy.
        assertEquals(listOf(false, false, false, true), r.feed(true, "standby", "onboarding", "lamp", "standby"))
        // Shown again: nothing owed any more.
        assertEquals(listOf(false, false), r.feed(true, "onboarding", "onboarding"))
    }

    @Test fun transientStandByStaysOwedUntilShown() {
        val r = SetupResume()
        // The shell passes StandBy while it swaps the top screen: the caller re-checks after a
        // post (it's the lamp by then), and the next real StandBy asks again.
        assertEquals(listOf(false, true, false, true), r.feed(true, "onboarding", "standby", "lamp", "standby"))
        assertEquals(listOf(false, false, false), r.feed(true, "onboarding", "profiles-enrol", "onboarding"))
    }

    @Test fun faceEnrolmentIsPartOfTheSetup() {
        val r = SetupResume()
        assertEquals(listOf(false, false, false, false), r.feed(true, "onboarding", "profiles-enrol", "onboarding", "profiles-enrol"))
        // Enrolment left straight to StandBy: back to the setup.
        assertTrue(r.onScreen("standby", true))
    }

    @Test fun reviewOrFinishedSetupStaysClosed() {
        val r = SetupResume()
        assertEquals(listOf(false, false, false), r.feed(false, "onboarding", "lamp", "standby"))
        // First-run setup interrupted, then finished or skipped (needed false): nothing owed, even
        // after "Run setup again" makes it needed again.
        r.feed(true, "onboarding", "lamp")
        assertFalse(r.onScreen("standby", false))
        assertFalse(r.onScreen("standby", true))
    }

    @Test fun otherScreensNeverOpenIt() {
        assertEquals(listOf(false, false, false, false), SetupResume().feed(true, "standby", "lamp", "standby", "nowplaying"))
    }
}
