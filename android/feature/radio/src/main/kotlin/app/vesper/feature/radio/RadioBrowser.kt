package app.vesper.feature.radio

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

/**
 * The Radio Browser directory (api.radio-browser.info: free, keyless, community run), used the way
 * its docs ask:
 *  - Servers: a DNS lookup of `all.api.radio-browser.info` with a reverse lookup of each address
 *    to its name (HTTPS needs the name), shuffled; on a failed request the next one is tried.
 *    Only if DNS gives no names, the `/json/servers` list of a known entry point ([ServerDiscovery]).
 *    Never one hard-coded server.
 *  - Every request sends a descriptive User-Agent ([USER_AGENT]).
 *  - A play (a person tapping a station or asking for it) is reported once with `/json/url/{uuid}`,
 *    which the directory uses for popularity (counted once per IP and station per day). Off with
 *    the `radio.reportPlays` setting.
 *  - Results are cached for [CACHE_MS], so browsing the genre chips doesn't repeat requests.
 */
class RadioBrowser(
    private val http: OkHttpClient = defaultClient(),
    private val discover: suspend () -> List<String> = { ServerDiscovery.discover(http) },
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var servers: List<String> = emptyList()
    private var serversAt = 0L
    /** Index into [servers] of the one that answered last. */
    private var current = 0
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Station>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Station>>>?) = size > 24
    }

    data class Query(
        val name: String? = null,
        val tag: String? = null,
        /** ISO 3166-1 alpha-2. */
        val countryCode: String? = null,
        val limit: Int = 30,
        /** The tag must match a whole tag ("jazz" not "christmas jazz"). */
        val tagExact: Boolean = true,
    )

    /** Stations for [q], most popular first, broken ones hidden, unplayable codecs dropped. */
    suspend fun search(q: Query): List<Station> {
        val key = "s|${q.name}|${q.tag}|${q.tagExact}|${q.countryCode}|${q.limit}"
        cached(key)?.let { return it }
        val body = get("/json/stations/search") { u ->
            q.name?.takeIf { it.isNotBlank() }?.let { u.addQueryParameter("name", it.trim()) }
            q.tag?.takeIf { it.isNotBlank() }?.let {
                u.addQueryParameter("tag", it.trim().lowercase())
                if (q.tagExact) u.addQueryParameter("tagExact", "true")
            }
            q.countryCode?.takeIf { it.isNotBlank() }?.let { u.addQueryParameter("countrycode", it.trim().uppercase()) }
            u.addQueryParameter("order", "clickcount").addQueryParameter("reverse", "true")
                .addQueryParameter("hidebroken", "true").addQueryParameter("limit", q.limit.coerceIn(1, 100).toString())
        }
        val list = parseStations(body).filter(Station::playable).distinctBy { it.uuid }
        lock.withLock { cache[key] = now() to list }
        return list
    }

    /** Fresh copies of saved stations (stream URLs change). */
    suspend fun byUuid(uuids: List<String>): List<Station> {
        if (uuids.isEmpty()) return emptyList()
        return parseStations(get("/json/stations/byuuid") { it.addQueryParameter("uuids", uuids.take(50).joinToString(",")) })
    }

    /** Tells the directory a person played [uuid]. Best effort; errors are ignored. */
    suspend fun reportPlay(uuid: String) {
        try { get("/json/url/$uuid") {} } catch (e: IOException) { Log.i(TAG, "click not counted: ${e.message}") }
    }

    private suspend fun cached(key: String): List<Station>? = lock.withLock {
        cache[key]?.takeIf { now() - it.first < CACHE_MS }?.second
    }

    /** GET [path] on the current server, failing over through the others. Returns the body. */
    internal suspend fun get(path: String, query: (HttpUrl.Builder) -> Unit): String = withContext(Dispatchers.IO) {
        val list = serverList()
        if (list.isEmpty()) throw IOException("No Radio Browser server found")
        var last: IOException? = null
        for (i in list.indices) {
            val idx = (current + i) % list.size
            val base = list[idx]
            val url = (base.trimEnd('/') + path).toHttpUrl().newBuilder().also(query).build()
            try {
                val body = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { res ->
                    if (res.code >= 500 || res.code == 429) throw IOException("HTTP ${res.code} from ${url.host}")
                    if (!res.isSuccessful) throw HttpError(res.code, "HTTP ${res.code} from ${url.host}")
                    res.body?.string() ?: ""
                }
                current = idx
                return@withContext body
            } catch (e: HttpError) {
                throw e // a client error: another server would answer the same
            } catch (e: IOException) {
                Log.i(TAG, "${url.host} failed (${e.message}), trying the next server")
                last = e
            }
        }
        // Every server failed: look them up again next time.
        lock.withLock { serversAt = 0L }
        throw last ?: IOException("Radio Browser unreachable")
    }

    private suspend fun serverList(): List<String> {
        lock.withLock { if (servers.isNotEmpty() && now() - serversAt < SERVERS_MS) return servers }
        val found = ServerDiscovery.order(discover(), random)
        lock.withLock {
            if (found.isNotEmpty()) { servers = found; serversAt = now(); current = 0 }
            return servers
        }
    }

    class HttpError(val code: Int, message: String) : IOException(message)

    companion object {
        const val TAG = "VesperRadio"
        const val USER_AGENT = "EchoHome/0.1 (smart display radio)"
        const val CACHE_MS = 10 * 60_000L
        const val SERVERS_MS = 6 * 3_600_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

        fun parseStations(body: String): List<Station> = try {
            Station.listFromJson(JSONArray(body))
        } catch (e: JSONException) {
            throw IOException("Bad station list: ${e.message}")
        }
    }
}

/** Finds Radio Browser's servers (see [RadioBrowser]). */
object ServerDiscovery {
    const val DNS_NAME = "all.api.radio-browser.info"
    private const val SUFFIX = ".api.radio-browser.info"

    suspend fun discover(http: OkHttpClient): List<String> = withContext(Dispatchers.IO) {
        val fromDns = try { dns() } catch (e: IOException) { Log.i(RadioBrowser.TAG, "DNS lookup failed: ${e.message}"); emptyList() }
        if (fromDns.isNotEmpty()) return@withContext fromDns
        // No reverse DNS on this network: the servers list, from whichever server the name points at.
        try { fromServersEndpoint(http, "https://$DNS_NAME/json/servers") } catch (e: IOException) {
            Log.w(RadioBrowser.TAG, "No servers: ${e.message}"); emptyList()
        }
    }

    /** All A/AAAA records of [DNS_NAME], each reverse-resolved to its name. */
    private fun dns(): List<String> = InetAddress.getAllByName(DNS_NAME).mapNotNull { a ->
        a.canonicalHostName.takeIf { it.endsWith(SUFFIX) }
    }.distinct().map { "https://$it" }

    /** Parses a `/json/servers` answer ([{"name": "de1.api.radio-browser.info", "ip": ...}]). */
    fun fromServersEndpoint(http: OkHttpClient, url: String): List<String> {
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", RadioBrowser.USER_AGENT).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body?.string() ?: ""
        }
        return parseServers(body).map { "https://$it" }
    }

    fun parseServers(body: String): List<String> = try {
        val a = JSONArray(body)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.trim()?.lowercase() }
            .filter { it.endsWith(SUFFIX) && it.length > SUFFIX.length }.distinct()
    } catch (e: JSONException) {
        throw IOException("Bad server list")
    }

    /** Distinct, shuffled (the docs: randomise and use the first, then the next on failure). */
    fun order(servers: List<String>, random: Random): List<String> = servers.distinct().shuffled(random)
}
