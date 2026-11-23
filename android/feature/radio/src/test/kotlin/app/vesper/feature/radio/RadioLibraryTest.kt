package app.vesper.feature.radio

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioLibraryTest {
    private val dir = Files.createTempDirectory("radio").toFile()
    private val file = File(dir, "radio.json")

    @After fun tearDown() { dir.deleteRecursively() }

    private fun st(id: String, name: String = "Station $id") = Station(id, name, "https://s.example/$id", tags = listOf("jazz"), countryCode = "US", codec = "MP3", bitrate = 128)

    @Test fun favouritesPersistAcrossInstances() {
        val lib = RadioLibrary(file)
        assertTrue(lib.toggleFavourite(st("a")))
        assertTrue(lib.toggleFavourite(st("b")))
        assertTrue(lib.isFavourite("a"))
        val again = RadioLibrary(file)
        assertEquals(listOf("b", "a"), again.favourites.value.map { it.uuid }) // newest first
        assertEquals(st("a"), again.favourites.value[1])
        assertFalse(again.toggleFavourite(st("a")))
        assertEquals(listOf("b"), RadioLibrary(file).favourites.value.map { it.uuid })
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test fun recentIsMostRecentFirstCappedAndPersisted() {
        val lib = RadioLibrary(file)
        for (i in 0 until RadioLibrary.MAX_RECENT + 3) lib.played(st("s$i"))
        lib.played(st("s5"))
        val r = RadioLibrary(file).recent.value
        assertEquals(RadioLibrary.MAX_RECENT, r.size)
        assertEquals("s5", r[0].uuid)
        assertEquals("s${RadioLibrary.MAX_RECENT + 2}", r[1].uuid)
        assertEquals(1, r.count { it.uuid == "s5" })
    }

    @Test fun demoStationsAreNotRemembered() {
        val lib = RadioLibrary(file)
        lib.played(DemoStations.ALL[0])
        assertTrue(lib.recent.value.isEmpty())
    }

    @Test fun playingRefreshesAFavouritesSavedCopy() {
        val lib = RadioLibrary(file)
        lib.setFavourite(st("a"), true)
        lib.played(st("a").copy(url = "https://new.example/a"))
        assertEquals("https://new.example/a", RadioLibrary(file).favourites.value[0].url)
        lib.refresh(listOf(st("a").copy(url = "https://newer.example/a")))
        assertEquals("https://newer.example/a", RadioLibrary(file).favourites.value[0].url)
    }

    @Test fun corruptFileStartsEmpty() {
        file.writeText("{not json")
        val lib = RadioLibrary(file)
        assertTrue(lib.favourites.value.isEmpty())
        lib.setFavourite(st("a"), true)
        assertEquals(1, RadioLibrary(file).favourites.value.size)
    }

    @Test fun matchesSpokenNames() {
        val lib = RadioLibrary(file)
        lib.setFavourite(st("k", "KEXP 90.3 FM"), true)
        lib.setFavourite(st("b", "BBC Radio 3"), true)
        lib.played(st("f", "FIP Jazz"))
        assertEquals("k", lib.match("kexp")?.uuid)
        assertEquals("b", lib.match("BBC radio 3")?.uuid)
        assertEquals("b", lib.match("radio 3 bbc")?.uuid)
        assertEquals("f", lib.match("fip jazz")?.uuid)
        assertNull(lib.match("classic fm"))
        assertNull(lib.match(""))
    }
}
