package com.echobedside.feature.photos

import com.echobedside.feature.photos.data.Captions
import com.echobedside.feature.photos.data.DecodeMath
import com.echobedside.feature.photos.data.PhotoAsset
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsTest {
    private val today = LocalDate.of(2026, 11, 22)
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 17, 30)
    private fun cap(a: PhotoAsset, album: String = "Family", offline: Boolean = false) =
        Captions.caption(a, album, today, "Immich", offline, Locale.US)

    @Test fun designCaption() {
        val c = cap(PhotoAsset("a", at(2025, 10, 12), "Big Sur"))
        assertEquals("Big Sur", c.title)
        assertEquals("October 2025 · Immich · Family album", c.subtitle)
    }

    @Test fun anniversaries() {
        assertEquals("3 years ago today", Captions.whenText(at(2023, 11, 22), today, Locale.US))
        assertEquals("1 year ago today", Captions.whenText(at(2025, 11, 22), today, Locale.US))
        assertEquals("2 years ago this week", Captions.whenText(at(2024, 11, 19), today, Locale.US))
        assertEquals("2 years ago this week", Captions.whenText(at(2024, 11, 25), today, Locale.US))
        assertEquals("November 2024", Captions.whenText(at(2024, 11, 26), today, Locale.US))
        // the same day this year isn't a memory
        assertEquals("November 2026", Captions.whenText(at(2026, 11, 22), today, Locale.US))
        // Feb 29 photos come back on Feb 28
        assertEquals("2 years ago today", Captions.whenText(LocalDateTime.of(2024, 2, 29, 9, 0), LocalDate.of(2026, 2, 28), Locale.US))
        assertNull(Captions.yearsAgoNear(LocalDate.of(2026, 1, 1), today))
    }

    @Test fun withoutPlaceOrDate() {
        val noPlace = cap(PhotoAsset("a", at(2023, 11, 22)))
        assertEquals("3 years ago today", noPlace.title)
        assertEquals("Immich · Family album", noPlace.subtitle)
        val nothing = cap(PhotoAsset("a", null))
        assertEquals("Family", nothing.title)
        assertEquals("Immich", nothing.subtitle)
        val noDate = cap(PhotoAsset("a", null, "Kyoto"))
        assertEquals("Immich · Family album", noDate.subtitle)
    }

    @Test fun offlineAndAlbumNames() {
        val c = cap(PhotoAsset("a", at(2025, 7, 4), "Night market, Taipei"), album = "Summer photos", offline = true)
        assertEquals("July 2025 · Offline · Summer photos", c.subtitle)
        assertEquals("Trips album", Captions.albumLabel("Trips"))
        assertEquals("Wedding Album", Captions.albumLabel("Wedding Album"))
    }
}

class DecodeMathTest {
    @Test fun sampleSizeLeavesEnoughPixels() {
        // Immich preview default 1440×1080 (4:3) → cover 960×480 needs width 960: no subsampling
        assertEquals(1, DecodeMath.inSampleSize(1440, 1080, 960, 480))
        // 4000×3000 → width 960 needed: 4000/4 = 1000 ≥ 960
        assertEquals(4, DecodeMath.inSampleSize(4000, 3000, 960, 480))
        // panorama 6000×1500 → height 480 needed: 1500/2 = 750, /4 = 375 too small
        assertEquals(2, DecodeMath.inSampleSize(6000, 1500, 960, 480))
        // portrait 1080×1440 is fitted at full height 480: 1440/2 = 720 ≥ 480, /4 = 360 too small
        assertEquals(2, DecodeMath.inSampleSize(1080, 1440, 960, 480))
        assertEquals(1, DecodeMath.inSampleSize(640, 480, 960, 480))
        assertEquals(1, DecodeMath.inSampleSize(0, 0, 960, 480))
    }

    @Test fun coverCrop() {
        // 4:3 → 2:1: full width, 720 of 1080 rows, 40 % of the excess from the top
        assertEquals(DecodeMath.Rect(0, 144, 1440, 864), DecodeMath.coverSource(1440, 1080, 960, 480))
        // wider than 2:1: centred horizontally
        assertEquals(DecodeMath.Rect(500, 0, 2500, 1000), DecodeMath.coverSource(3000, 1000, 960, 480))
        // exactly 2:1: untouched
        assertEquals(DecodeMath.Rect(0, 0, 960, 480), DecodeMath.coverSource(960, 480, 960, 480))
    }

    @Test fun portraitsAreFitted() {
        assertTrue(DecodeMath.fits(1080, 1440))
        assertTrue(DecodeMath.fits(1000, 1000))
        assertFalse(DecodeMath.fits(1440, 1080))
        assertEquals(DecodeMath.Rect(300, 0, 660, 480), DecodeMath.fitDest(1080, 1440, 960, 480))
    }
}
