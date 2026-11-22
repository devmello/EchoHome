package app.vesper.feature.clock

import app.vesper.core.model.NightMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SuperellipseTest {
    @Test fun pointsLieOnTheCurve() {
        for (deg in 0 until 360 step 7) {
            val p = Superellipse.ring(deg.toDouble(), 446.0, 222.0)
            assertEquals("deg $deg", 1.0, Superellipse.level(p.x - 480.0, p.y - 240.0, 446.0, 222.0), 1e-4)
        }
    }

    @Test fun cardinalPoints() {
        val top = Superellipse.ring(0.0, 446.0, 222.0)
        assertEquals(480f, top.x, 1e-3f); assertEquals(18f, top.y, 1e-3f)
        assertEquals(0f, top.nx, 1e-6f); assertEquals(-1f, top.ny, 1e-6f); assertEquals(0f, top.rotDeg, 1e-4f)
        val right = Superellipse.ring(90.0, 446.0, 222.0)
        assertEquals(926f, right.x, 1e-3f); assertEquals(240f, right.y, 1e-3f); assertEquals(90f, right.rotDeg, 1e-3f)
        val bottom = Superellipse.ring(180.0, 380.0, 172.0)
        assertEquals(412f, bottom.y, 1e-3f)
    }

    @Test fun normalsAreUnitAndOutward() {
        for (deg in 0 until 360 step 11) {
            val p = Superellipse.ring(deg.toDouble(), 446.0, 222.0)
            assertEquals(1.0, hypot(p.nx.toDouble(), p.ny.toDouble()), 1e-5)
            // outward: dot(normal, radius) > 0
            assertTrue((p.x - 480) * p.nx + (p.y - 240) * p.ny > 0)
        }
    }

    @Test fun cornerIsSquarerThanAnEllipse() {
        // At 45°, n=5 pushes the point further out than an ellipse (n=2) would.
        val p = Superellipse.ring(45.0, 446.0, 222.0)
        val e = Superellipse.ring(45.0, 446.0, 222.0, n = 2.0)
        assertTrue(hypot(p.x - 480.0, p.y - 240.0) > hypot(e.x - 480.0, e.y - 240.0) + 20)
    }
}

class SunMathTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test fun declinationAtSolsticesAndEquinox() {
        assertEquals(23.44, Math.toDegrees(SunMath.declination(utc(2026, 6, 21, 12))), 0.6)
        assertEquals(-23.44, Math.toDegrees(SunMath.declination(utc(2026, 12, 21, 12))), 0.6)
        assertEquals(0.0, Math.toDegrees(SunMath.declination(utc(2026, 3, 20, 12))), 2.0)
    }

    @Test fun subsolarLongitude() {
        assertEquals(0.0, SunMath.subsolarLongitude(utc(2026, 11, 22, 12)), 1e-9)
        assertEquals(-90.0, SunMath.subsolarLongitude(utc(2026, 11, 22, 18)), 1e-9)
        assertEquals(37.5, SunMath.subsolarLongitude(utc(2026, 11, 22, 9, 30)), 1e-9)
    }

    @Test fun daylightTwilightBand() {
        assertEquals(0.0, SunMath.daylight(-0.2), 0.0)
        assertEquals(0.5, SunMath.daylight(0.0), 1e-12)
        assertEquals(1.0, SunMath.daylight(0.3), 0.0)
        // smoothstep is monotonic
        var last = -1.0
        for (i in -20..20) { val v = SunMath.daylight(i / 100.0); assertTrue(v >= last); last = v }
    }

    @Test fun noonIsLitMidnightIsDark() {
        val t = utc(2026, 11, 22, 20) // 12:00 in Santa Cruz (PST)
        val decl = SunMath.declination(t); val lon = SunMath.subsolarLongitude(t)
        assertEquals(1.0, SunMath.daylight(SunMath.cosZenith(36.97, -122.03, decl, lon)), 1e-9)
        assertEquals(0.0, SunMath.daylight(SunMath.cosZenith(36.97, 57.97, decl, lon)), 1e-9)
    }

    @Test fun santaCruzSunriseMatchesTheDesign() {
        // Weather.dc.html shows sunrise 6:58 AM (Nov 22, Santa Cruz)
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val (rise, set) = SunMath.sunriseSunset(utc(2026, 11, 22, 20), 36.97, -122.03, zone)!!
        val r = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(rise), zone.toZoneId())
        val s = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(set), zone.toZoneId())
        assertEquals(LocalDate.of(2026, 11, 22), r.toLocalDate())
        assertEquals(6 * 60 + 58.0, r.hour * 60.0 + r.minute, 4.0)
        assertEquals(16 * 60 + 52.0, s.hour * 60.0 + s.minute, 5.0)
    }

    @Test fun polarNight() {
        assertNull(SunMath.sunriseSunset(utc(2026, 12, 21, 12), 80.0, 15.0, TimeZone.getTimeZone("UTC")))
    }
}

class WorldMapTest {
    @Test fun maskHasLandWhereExpected() {
        assertEquals(36, WorldMap.rows.size)
        assertTrue(WorldMap.rows.all { it.length == 24 })
        val land = WorldMap.land
        assertTrue(land.size in 700..1500)
        // the bottom row is empty (Antarctica is cut off)
        assertTrue(land.none { it.row == 35 })
        // dot geometry: first column centre at 5.5 px (toFixed rounding), first row at 69.5 px
        val d = land.first { it.col == 0 }
        assertEquals(5.5f, d.cx, 0f)
        assertEquals(WorldMap.TOP_PX.toFloat() + d.row * 10 + 5.5f, d.cy, 0f)
    }

    @Test fun projection() {
        val (x, y) = WorldMap.project(76.0, 157.5)
        assertEquals(0.0, x, 1e-9); assertEquals(64.0, y, 1e-9)
        val (x2, _) = WorldMap.project(0.0, -22.5) // 180° east of the left edge
        assertEquals(480.0, x2, 1e-9)
        val (sx, sy) = WorldMap.project(36.97, -122.03)
        assertTrue(sx in 200.0..230.0 && sy in 160.0..180.0)
    }
}

class AutoNightTest {
    @Test fun entersOnlyAfter30sOfDark() {
        val n = AutoNight()
        assertFalse(n.onLux(1f, 0))
        assertEquals(30_000L, n.nextCheckAt())
        assertFalse(n.onLux(0.5f, 29_999))
        assertFalse(n.onTick(29_999))
        assertTrue(n.onTick(30_000))
        assertNull(n.nextCheckAt())
    }

    @Test fun brightReadingResetsTheTimer() {
        val n = AutoNight()
        n.onLux(1f, 0)
        n.onLux(5f, 20_000) // not dark any more (≥ 3)
        assertNull(n.nextCheckAt())
        n.onLux(1f, 25_000)
        assertFalse(n.onTick(50_000))
        assertTrue(n.onTick(55_000))
    }

    @Test fun hysteresis() {
        val n = AutoNight()
        n.onLux(0f, 0); n.onTick(30_000)
        assertTrue(n.active)
        assertTrue(n.onLux(8f, 40_000))   // between 3 and 10: stays on
        assertTrue(n.onLux(10f, 41_000))  // not above 10
        assertFalse(n.onLux(10.5f, 42_000)) // leaves immediately
        assertFalse(n.onLux(8f, 43_000))  // between: stays off, no timer
        assertNull(n.nextCheckAt())
    }

    @Test fun modeOverrides() {
        assertTrue(AutoNight.resolve(NightMode.ON, false))
        assertFalse(AutoNight.resolve(NightMode.OFF, true))
        assertTrue(AutoNight.resolve(NightMode.AUTO, true))
        assertFalse(AutoNight.resolve(NightMode.AUTO, false))
    }
}

class ColorsTest {
    @Test fun mix() {
        assertEquals(0xFFFFFFFF.toInt(), Colors.mix(0xFFFF9F0A.toInt(), true, 1f))
        assertEquals(0xFF000000.toInt(), Colors.mix(0xFFFF9F0A.toInt(), false, 1f))
        // Solar bg: mix(#FF9F0A, black, .72) = rgb(71, 45, 3)
        assertEquals(Colors.argb(1f, 71, 45, 3), Colors.mix(0xFFFF9F0A.toInt(), false, 0.72f))
    }

    @Test fun hslRoundTrip() {
        for (c in listOf(0xFFFF375F, 0xFF30D158, 0xFF0A84FF, 0xFFBE180E, 0xFFFFFFFF, 0xFF7A0D07)) {
            val hsl = Colors.toHsl(c.toInt())
            val back = Colors.fromHsl(hsl[0], hsl[1], hsl[2])
            for (sh in listOf(16, 8, 0)) assertTrue(abs(((back shr sh) and 255) - ((c.toInt() shr sh) and 255)) <= 1)
        }
        val w = Colors.toHsl(0xFFFFFFFF.toInt())
        assertEquals(0f, w[1], 0f); assertEquals(100f, w[2], 0f)
    }

    @Test fun passWithWhiteTintOnlyFrosts() {
        // white tint → tAmt 0: just moves toward white by wAmt
        val c = Colors.pass(0xFF2F74FF.toInt(), 0xFFFFFFFF.toInt(), 0f, 0.5f, 1f)
        assertEquals(Colors.argb(1f, 151, 186, 255), c)
    }

    @Test fun formatting() {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 22, 21, 5) }
        assertEquals("9:05", TimeText.time(cal, false))
        assertEquals("21:05", TimeText.time(cal, true))
        assertEquals("Sunday, November 22", TimeText.dateLine(cal))
        assertEquals("9:05 PM", TimeText.clock(cal.timeInMillis, false, zone = TimeZone.getTimeZone("UTC")))
        assertEquals("9:05", TimeText.clock(cal.timeInMillis, false, amPm = false, zone = TimeZone.getTimeZone("UTC")))
        assertNotNull(WorldFace.parseCities("Tokyo|Asia/Tokyo|35.68|139.69").single())
        assertEquals(3, WorldFace.parseCities("").size)
    }
}

class SettingsFormatTest {
    @Test fun citiesRoundTripAsTextList() {
        val text = WorldFace.DEFAULT_CITIES.joinToString(",") { WorldFace.encodeCity(it) }
        val back = WorldFace.parseCities(text)
        assertEquals(WorldFace.DEFAULT_CITIES.map { it.name }, back.map { it.name })
        assertEquals("Asia/Kolkata", back[2].zone.id)
        // the older ";" form still parses, and at most three are kept
        assertEquals(3, WorldFace.parseCities("A|UTC|0|0;B|UTC|1|1;C|UTC|2|2;D|UTC|3|3").size)
    }

    @Test fun hexColours() {
        assertEquals(0xFFFF375F.toInt(), parseHexColor("#FF375F"))
        assertEquals(0xFFFF375F.toInt(), parseHexColor("ff375f"))
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("nope00"))
        assertEquals("#FF9F0A", hexColor(0xFFFF9F0A.toInt()))
    }
}

class NightScheduleTest {
    @Test fun overnightWindow() {
        val s = NightSchedule(22 * 60, 7 * 60)
        assertTrue(s.isNight(23 * 60))
        assertTrue(s.isNight(0))
        assertTrue(s.isNight(6 * 60 + 59))
        assertFalse(s.isNight(7 * 60))
        assertFalse(s.isNight(12 * 60))
        assertTrue(s.isNight(22 * 60))
        assertEquals(60, s.minutesToNextChange(21 * 60))      // → 22:00
        assertEquals(7 * 60, s.minutesToNextChange(0))       // → 07:00
        assertEquals(15 * 60, s.minutesToNextChange(7 * 60)) // at 07:00 the next change is 22:00
    }

    @Test fun sameDayWindowAndNever() {
        val s = NightSchedule(13 * 60, 14 * 60)
        assertTrue(s.isNight(13 * 60 + 30))
        assertFalse(s.isNight(14 * 60))
        assertFalse(NightSchedule(600, 600).isNight(600))
    }

    @Test fun parsesTimes() {
        assertEquals(22 * 60, NightSchedule.parse("22:00"))
        assertEquals(7 * 60 + 5, NightSchedule.parse("07:05"))
        assertNull(NightSchedule.parse("24:00"))
        assertNull(NightSchedule.parse("7"))
    }
}

class RainWindowTest {
    @Test fun centresOnTheRain() {
        // the fake's tomorrow: 10 % overnight, 30 % at 6–8, 60–80 % at 9–14, 10 % after
        val day = (0..23).map { h -> when (h) { in 9..14 -> 60 + (h % 3) * 10; in 6..8 -> 30; else -> 10 } }
        assertEquals(day.subList(7, 17), rainWindow(day, 10).toList())
    }

    @Test fun shortListsAreKeptAndEdgesClamp() {
        assertEquals(listOf(5, 6), rainWindow(listOf(5, 6), 10).toList())
        val late = List(20) { if (it >= 18) 90 else 0 }
        assertEquals(late.subList(10, 20), rainWindow(late, 10).toList())
        assertEquals(List(10) { 0 }, rainWindow(List(24) { 0 }, 10).toList())
    }
}
