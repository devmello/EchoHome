package app.vesper.feature.home.radar

import app.vesper.feature.home.glance.Net
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * RainViewer's free weather-maps API (no key): `public/weather-maps.json` lists the past ~2 h of
 * radar frames (one per 10 min), each a tile path on `host`. Terms we follow (Nov 2026):
 * personal use, credit "Weather data by RainViewer" shown with the map, max zoom [MAX_ZOOM],
 * only the Universal Blue colour scheme ([COLOR_UNIVERSAL_BLUE]), 100 requests / IP / minute
 * (we stay under [RateLimiter] = 60), no nowcast or satellite any more.
 */
object RainViewer {
    const val MANIFEST_URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val MAX_ZOOM = 7
    const val COLOR_UNIVERSAL_BLUE = 2
    const val ATTRIBUTION = "Weather data by RainViewer · rainviewer.com"

    data class Frame(
        /** Unix seconds (UTC) the radar image is for. */
        val time: Long,
        /** e.g. "/v2/radar/1732340400" (or the newer hashed form); tile URLs are host + path + … */
        val path: String,
    )

    data class Manifest(val generated: Long, val host: String, val past: List<Frame>, val nowcast: List<Frame>)

    fun parse(body: String): Manifest = try {
        val o = JSONObject(body)
        val host = o.getString("host").trim().trimEnd('/')
        if (!host.startsWith("https://") && !host.startsWith("http://")) throw IOException("bad host")
        val radar = o.optJSONObject("radar") ?: JSONObject()
        Manifest(o.optLong("generated"), host, frames(radar.optJSONArray("past")), frames(radar.optJSONArray("nowcast")))
    } catch (e: JSONException) {
        throw IOException("Bad RainViewer manifest: ${e.message}")
    }

    private fun frames(a: JSONArray?): List<Frame> {
        a ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val f = a.optJSONObject(i) ?: return@mapNotNull null
            val t = f.optLong("time", -1L)
            val p = f.optString("path").trim()
            if (t <= 0 || !p.startsWith("/")) null else Frame(t, p)
        }.sortedBy { it.time }.distinctBy { it.time }
    }

    /** The frames of the last [windowSec] up to the newest past frame, at most [max] (oldest dropped). */
    fun lastHour(m: Manifest, windowSec: Long = 3_600, max: Int = 7): List<Frame> {
        val newest = m.past.lastOrNull() ?: return emptyList()
        return m.past.filter { it.time >= newest.time - windowSec }.takeLast(max)
    }

    /** `{host}{path}/{size}/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`; zoom capped at [MAX_ZOOM]. */
    fun tileUrl(host: String, frame: Frame, z: Int, x: Int, y: Int, size: Int = TileMath.TILE,
                color: Int = COLOR_UNIVERSAL_BLUE, smooth: Boolean = true, snow: Boolean = false): String {
        require(z in 0..MAX_ZOOM) { "RainViewer serves zoom 0..$MAX_ZOOM" }
        return "$host${frame.path}/$size/$z/$x/$y/$color/${if (smooth) 1 else 0}_${if (snow) 1 else 0}.png"
    }
}

/** At most [max] requests in any [windowMs] (sliding window). Pure apart from the clock. */
class RateLimiter(private val max: Int = 60, private val windowMs: Long = 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val times = ArrayDeque<Long>()

    /** 0 = go now (and the request is counted); else wait this long and ask again. */
    @Synchronized fun tryAcquire(): Long {
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= windowMs) times.removeFirst()
        if (times.size < max) { times.addLast(t); return 0L }
        return (times.first() + windowMs - t).coerceAtLeast(1L)
    }
}

/** HTTP for the radar: the manifest and tile PNGs, through one [RateLimiter]. */
class RainViewerClient(
    private val http: OkHttpClient = Net.http,
    private val manifestUrl: String = RainViewer.MANIFEST_URL,
    private val limiter: RateLimiter = RateLimiter(),
) {
    private val lock = Mutex()

    suspend fun manifest(): RainViewer.Manifest = RainViewer.parse(String(get(manifestUrl, MAX_MANIFEST), Charsets.UTF_8))

    /** One tile PNG (null = 404: no data there). */
    suspend fun tile(url: String): ByteArray? = try { get(url, MAX_TILE) } catch (e: Net.HttpError) { if (e.code == 404) null else throw e }

    private suspend fun get(url: String, limit: Int): ByteArray {
        lock.withLock {
            while (true) {
                val wait = limiter.tryAcquire()
                if (wait == 0L) break
                delay(wait)
            }
        }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw Net.HttpError(res.code, "HTTP ${res.code} from ${res.request.url.host}")
                val body = res.body ?: throw IOException("empty")
                if (body.contentLength() > limit) throw IOException("too big")
                val bytes = body.bytes()
                if (bytes.size > limit) throw IOException("too big")
                bytes
            }
        }
    }

    companion object {
        const val MAX_MANIFEST = 256 * 1024
        const val MAX_TILE = 512 * 1024
    }
}
