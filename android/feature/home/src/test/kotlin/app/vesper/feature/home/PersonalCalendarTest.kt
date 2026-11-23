package app.vesper.feature.home

import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.Profile
import app.vesper.core.model.Secrets
import app.vesper.feature.home.glance.IcsCalendarProvider
import app.vesper.feature.home.glance.Net
import app.vesper.feature.home.glance.PersonalCalendars
import app.vesper.feature.home.glance.PersonalFeeds
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-person calendars: URLs from the SecretStore per profile, events tagged with `profileId`. */
class PersonalCalendarTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 22)
    private val now = today.atTime(8, 0).atZone(la).toInstant().toEpochMilli()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private fun ics(summary: String, hour: Int) = listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:$summary",
        "DTSTART;TZID=America/Los_Angeles:20261122T${"%02d".format(hour)}0000", "DTEND;TZID=America/Los_Angeles:20261122T${"%02d".format(hour + 1)}0000",
        "SUMMARY:$summary", "LOCATION:Downtown", "END:VEVENT", "END:VCALENDAR").joinToString("\r\n")

    private fun serve(vararg feeds: Pair<String, String>) {
        val map = feeds.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = map[request.requestUrl!!.encodedPath]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
    }

    @Test fun readsEachProfilesUrlsFromTheSecretStore() {
        val store = InMemorySecretStore()
        store.set(Secrets.calendarKeyFor("maya"), "https://a.example/maya.ics, https://b.example/maya2.ics\nwebcal://c.example/m3.ics")
        store.set(Secrets.calendarKeyFor("sam"), "https://a.example/sam.ics")
        store.set(Secrets.calendarKeyFor(Profile.SHARED_ID), "https://a.example/ignored.ics") // shared URLs live in calendar.icsUrls
        val ps = listOf(Profile.SHARED, Profile("maya", "Maya", 0xFFFF375F.toInt()), Profile("sam", "Sam", 0xFF30D158.toInt()), Profile("kid", "Kid", 0))
        val feeds = PersonalCalendars.read(ps, store)
        assertEquals(listOf("maya", "sam"), feeds.map { it.profileId }) // no URLs: left out
        assertEquals(listOf("https://a.example/maya.ics", "https://b.example/maya2.ics", "webcal://c.example/m3.ics"), feeds[0].urls)
        assertEquals(0xFFFF375F.toInt(), feeds[0].color)
    }

    @Test fun flowEmitsAgainWhenProfilesOrTheirSecretsChange() = runBlocking {
        val store = InMemorySecretStore()
        val profiles = MutableStateFlow(listOf(Profile.SHARED, Profile("maya", "Maya", 1)))
        val emitted = java.util.concurrent.CopyOnWriteArrayList<List<PersonalFeeds>>()
        val job = scope.launch { PersonalCalendars.flow({ profiles }, { store }).collect { emitted += it } }
        withTimeout(3000) { while (emitted.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(emptyList<PersonalFeeds>(), emitted.last())
        store.set(Secrets.calendarKeyFor("maya"), "https://a.example/maya.ics")
        withTimeout(3000) { while (emitted.last().isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals("maya", emitted.last().single().profileId)
        profiles.value = profiles.value + Profile("sam", "Sam", 2)
        store.set(Secrets.calendarKeyFor("sam"), "https://a.example/sam.ics")
        withTimeout(3000) { while (emitted.last().size < 2) kotlinx.coroutines.delay(20) }
        profiles.value = profiles.value.filter { it.id != "maya" } // a profile deleted
        withTimeout(3000) { while (emitted.last().size != 1) kotlinx.coroutines.delay(20) }
        assertEquals("sam", emitted.last().single().profileId)
        job.cancel()
    }

    @Test fun eventsAreTaggedWithTheirProfileAndSharedOnesAreNot() = runBlocking {
        serve("/shared.ics" to ics("Team lunch", 12), "/maya.ics" to ics("Maya yoga", 9), "/sam.ics" to ics("Sam gym", 7))
        val dir = Files.createTempDirectory("ics").toFile()
        val personal = MutableStateFlow(listOf(
            PersonalFeeds("maya", 0xFFFF375F.toInt(), listOf(server.url("/maya.ics").toString())),
            PersonalFeeds("sam", 0xFFFFFFFF.toInt(), listOf(server.url("/sam.ics").toString()))))
        val p = IcsCalendarProvider(flowOf(listOf(server.url("/shared.ics").toString())), dir, MemSettings(), scope,
            client = Net.http, zone = { la }, clock = { now }, personal = personal)
        p.start()
        val ev = withTimeout(5000) { p.events.first { it.size == 3 } }.associateBy { it.title }
        assertNull(ev["Team lunch"]!!.profileId) // shared
        assertEquals("maya", ev["Maya yoga"]!!.profileId)
        assertEquals("sam", ev["Sam gym"]!!.profileId)
        assertEquals(0xFFFF375F.toInt(), ev["Maya yoga"]!!.color) // a person's calendar takes the profile colour
        assertEquals(IcsCalendarProvider.PALETTE[2], ev["Sam gym"]!!.color) // white (shared) profile colour: palette, numbered after the shared one
        assertEquals(listOf("Sam gym", "Maya yoga", "Team lunch"), p.events.value.map { it.title }) // sorted by start

        // A new URL for Maya (secret changed): reloaded at once. Removing a person drops their events.
        serve("/shared.ics" to ics("Team lunch", 12), "/maya.ics" to ics("Maya yoga", 9), "/maya2.ics" to ics("Maya book club", 18))
        personal.value = listOf(PersonalFeeds("maya", 0xFFFF375F.toInt(), listOf(server.url("/maya.ics").toString(), server.url("/maya2.ics").toString())))
        val after = withTimeout(5000) { p.events.first { e -> e.any { it.title == "Maya book club" } } }
        assertEquals(setOf("Team lunch", "Maya yoga", "Maya book club"), after.map { it.title }.toSet())
        assertEquals("maya", after.first { it.title == "Maya book club" }.profileId)
        dir.deleteRecursively()
        Unit
    }

    @Test fun personalFeedsAloneReplaceTheFallback() = runBlocking {
        serve("/maya.ics" to ics("Maya yoga", 9))
        val dir = Files.createTempDirectory("ics").toFile()
        val sample = app.vesper.core.model.CalendarEvent("s", "Sample", "", now, now + 1, color = 0)
        val fake = object : app.vesper.core.model.CalendarProvider { override val events = MutableStateFlow(listOf(sample)) }
        val personal = MutableStateFlow(emptyList<PersonalFeeds>())
        val p = IcsCalendarProvider(flowOf(emptyList()), dir, MemSettings(), scope, fallback = fake, client = Net.http, zone = { la }, clock = { now }, personal = personal)
        p.start()
        assertEquals("Sample", withTimeout(3000) { p.events.first { it.isNotEmpty() } }.single().title)
        personal.value = listOf(PersonalFeeds("maya", null, listOf(server.url("/maya.ics").toString())))
        assertEquals("maya", withTimeout(5000) { p.events.first { e -> e.any { it.title == "Maya yoga" } } }.single().profileId)
        dir.deleteRecursively()
        Unit
    }
}
