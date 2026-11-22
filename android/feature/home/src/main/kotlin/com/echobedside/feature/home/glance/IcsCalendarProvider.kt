package com.echobedside.feature.home.glance

import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.CalendarProvider
import com.echobedside.core.model.SettingsStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [CalendarProvider] on iCal subscription URLs (`calendar.icsUrls` in secrets.properties).
 *
 *  - Downloads each feed every 15 min with ETag / Last-Modified (a 304 costs almost nothing),
 *    streaming it to `cacheDir/ics/` so a large feed never sits in memory as one string.
 *  - Starts from the cached files, so events show at boot before the network is up.
 *  - Expands today + tomorrow (device zone) and re-expands at midnight.
 *  - Colour per calendar: `calendar.color.<n>` ("#RRGGBB", n = 0-based URL index), else the design palette.
 *  - URLs are secrets (they grant read access): never logged; files are named by a hash.
 */
class IcsCalendarProvider(
    private val urls: List<String>,
    private val cacheDir: File,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = Net.http,
    private val periodMs: Long = 15 * 60_000L,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) : CalendarProvider {
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    override val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    private val lock = Mutex()
    private var started = false

    /** Last download problem per feed, for the status / a future settings screen. */
    val errors = MutableStateFlow<Map<Int, String>>(emptyMap())

    fun start() {
        if (started) return
        started = true
        scope.launch {
            rebuild()
            while (true) {
                downloadAll()
                rebuild()
                val now = clock()
                val midnight = localDate(now, zone()).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
                if (midnight - now < periodMs) { delay(midnight - now + 1000); rebuild(); delay((periodMs - (midnight - now)).coerceAtLeast(0)) }
                else delay(periodMs)
            }
        }
    }

    override fun refresh() { scope.launch { downloadAll(); rebuild() } }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun file(i: Int) = File(cacheDir, "ics/cal$i-${key(urls[i])}.ics")
    private fun meta(i: Int) = File(cacheDir, "ics/cal$i-${key(urls[i])}.meta")

    /** webcal:// is just https. */
    internal fun httpUrl(u: String) = u.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")

    private suspend fun downloadAll() = withContext(Dispatchers.IO) {
        File(cacheDir, "ics").mkdirs()
        val errs = HashMap<Int, String>()
        for (i in urls.indices) {
            try { download(i) } catch (e: IOException) { errs[i] = e.message ?: "download failed" } catch (e: IllegalArgumentException) { errs[i] = "bad URL" }
        }
        errors.value = errs
    }

    private fun download(i: Int) {
        val f = file(i); val m = meta(i)
        val (etag, modified) = if (m.exists() && f.exists()) m.readLines().let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() } else "" to ""
        val req = Request.Builder().url(httpUrl(urls[i])).apply {
            if (etag.isNotEmpty()) header("If-None-Match", etag)
            if (modified.isNotEmpty()) header("If-Modified-Since", modified)
        }.build()
        client.newCall(req).execute().use { res ->
            if (res.code == 304) return
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val tmp = File(f.parentFile, f.name + ".part")
            res.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            m.writeText(res.header("ETag").orEmpty() + "\n" + res.header("Last-Modified").orEmpty())
        }
    }

    private suspend fun rebuild() = lock.withLock {
        val list = withContext(Dispatchers.Default) {
            val now = clock()
            val z = zone()
            val from = localDate(now, z).atStartOfDay(z).toInstant()
            val to = localDate(now, z).plusDays(2).atStartOfDay(z).toInstant()
            urls.indices.flatMap { i ->
                val f = file(i)
                if (!f.exists()) return@flatMap emptyList()
                val cal = try { f.bufferedReader().use { Ics.parse(it, from, to, z) } } catch (_: Exception) { return@flatMap emptyList() }
                toEvents(cal, i, from, to, z)
            }.sortedWith(compareBy({ localDate(it.startMillis, z) }, { !it.allDay }, { it.startMillis }, { it.title }))
        }
        _events.value = list
    }

    private fun toEvents(cal: Ics.Calendar, index: Int, from: Instant, to: Instant, z: ZoneId): List<CalendarEvent> {
        val color = parseColor(settings.getString("calendar.color.$index", "")) ?: PALETTE[index % PALETTE.size]
        return Ics.occurrences(cal, from, to, z).map { o -> event(o, index, color, cal.name) }
    }

    companion object {
        /** Calendar.dc.html event colours, assigned to calendars in order. */
        val PALETTE = intArrayOf(0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFFBF5AF2.toInt(),
            0xFFFF375F.toInt(), 0xFF64D2FF.toInt(), 0xFFFFD60A.toInt())

        fun parseColor(s: String?): Int? = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

        /** Subtitle: the first part of the location ("Downtown"), else the description's first line, else the calendar's name. */
        fun event(o: Ics.Occurrence, index: Int, color: Int, calName: String?): CalendarEvent {
            val e = o.event
            val subtitle = e.location?.substringBefore(',')?.substringBefore('\n')?.trim()?.ifEmpty { null }
                ?: e.description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                ?: calName.orEmpty()
            return CalendarEvent(
                id = "$index|${e.uid}|${o.start.toEpochMilli()}",
                title = e.summary.ifEmpty { "Busy" },
                subtitle = subtitle,
                startMillis = o.start.toEpochMilli(),
                endMillis = o.end.toEpochMilli(),
                allDay = o.allDay,
                color = color,
                location = e.location,
            )
        }
    }
}
