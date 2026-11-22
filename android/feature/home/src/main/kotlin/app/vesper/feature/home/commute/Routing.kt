package app.vesper.feature.home.commute

import app.vesper.core.model.SettingsStore
import app.vesper.feature.home.glance.Net
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Place text → lat/lon with OpenStreetMap Nominatim (https://nominatim.org, no key).
 * Usage policy (operations.osmfoundation.org/policies/nominatim): at most 1 request a second,
 * an identifying User-Agent, cache results, no bulk/autocomplete use. So: one request at a time
 * with a ≥ 1.1 s gap, results cached forever in settings (keyed by a hash of the text), and
 * only the event's location text is sent (no name, no title, no home address).
 */
class Geocoder(
    private val settings: SettingsStore,
    private val client: OkHttpClient = Net.http,
    private val base: HttpUrl = "https://nominatim.openstreetmap.org/search".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var last = 0L

    suspend fun geocode(text: String): LatLon? {
        val q = text.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return null
        parseLatLon(q)?.let { return it }
        val key = "commute.geo." + sha(q.lowercase())
        settings.getString(key, "").let { c -> if (c == "-") return null; parseLatLon(c)?.let { return it } }
        return lock.withLock {
            val wait = last + 1100 - clock()
            if (wait > 0) delay(wait)
            last = clock()
            val url = base.newBuilder().addQueryParameter("q", q).addQueryParameter("format", "jsonv2").addQueryParameter("limit", "1").build()
            val res = parse(JSONArray(Net.getText(client, url.toString(), mapOf("Accept-Language" to Locale.getDefault().toLanguageTag()))))
            // "-" caches a miss, so an unknown place isn't asked for again.
            settings.putString(key, res?.let { "${it.lat},${it.lon}" } ?: "-")
            res
        }
    }

    companion object {
        fun parse(arr: JSONArray): LatLon? {
            val o = arr.optJSONObject(0) ?: return null
            val lat = o.optString("lat").toDoubleOrNull() ?: return null
            val lon = o.optString("lon").toDoubleOrNull() ?: return null
            return LatLon(lat, lon)
        }

        /** "36.97,-122.03" (a location that's already coordinates). */
        fun parseLatLon(s: String): LatLon? {
            val m = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").find(s) ?: return null
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
        }

        private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}

/**
 * Driving routes from the public OSRM instance run by FOSSGIS (routing.openstreetmap.de, OSM data,
 * no key). No live traffic: times are typical free-flow estimates. Policy: light, non-commercial
 * use with an identifying User-Agent; we ask once per trip and cache for 30 minutes.
 */
class Router(
    private val client: OkHttpClient = Net.http,
    private val base: HttpUrl = "https://routing.openstreetmap.de/routed-car/route/v1/driving/".toHttpUrl(),
) {
    suspend fun routes(from: LatLon, to: LatLon): List<Route> {
        fun c(p: LatLon) = String.format(Locale.US, "%.5f,%.5f", p.lon, p.lat)
        val url = base.newBuilder().addPathSegment("${c(from)};${c(to)}")
            .addQueryParameter("alternatives", "true").addQueryParameter("steps", "true")
            .addQueryParameter("overview", "simplified").addQueryParameter("geometries", "geojson").build()
        return parse(JSONObject(Net.getText(client, url.toString())))
    }

    companion object {
        /** OSRM `route` response → routes, fastest first. The main road is the longest named step. */
        fun parse(body: JSONObject): List<Route> {
            if (body.optString("code") != "Ok") return emptyList()
            val arr = body.optJSONArray("routes") ?: return emptyList()
            val out = ArrayList<Route>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val roads = HashMap<String, Double>()
                val legs = r.optJSONArray("legs")
                for (l in 0 until (legs?.length() ?: 0)) {
                    val steps = legs!!.optJSONObject(l)?.optJSONArray("steps") ?: continue
                    for (s in 0 until steps.length()) {
                        val st = steps.optJSONObject(s) ?: continue
                        val name = roadName(st.optString("ref"), st.optString("name")) ?: continue
                        roads[name] = (roads[name] ?: 0.0) + st.optDouble("distance", 0.0)
                    }
                }
                val coords = r.optJSONObject("geometry")?.optJSONArray("coordinates")
                val path = (0 until (coords?.length() ?: 0)).mapNotNull { k ->
                    coords!!.optJSONArray(k)?.let { LatLon(it.optDouble(1), it.optDouble(0)) }
                }
                out += Route(
                    minutes = (r.optDouble("duration", 0.0) / 60).roundToInt().coerceAtLeast(1),
                    km = r.optDouble("distance", 0.0) / 1000,
                    via = roads.maxByOrNull { it.value }?.key,
                    path = path,
                )
            }
            return out.sortedBy { it.minutes }
        }

        /** Prefer the short ref ("CA 17" → "Hwy 17", "I 280" → "I-280"), else the street name. */
        fun roadName(ref: String, name: String): String? {
            val r = ref.split(';').firstOrNull()?.trim().orEmpty()
            if (r.isNotEmpty()) {
                Regex("^(?:[A-Z]{2}|US) (\\d+[A-Z]?)$").find(r)?.let { return "Hwy " + it.groupValues[1] }
                Regex("^I (\\d+)$").find(r)?.let { return "I-" + it.groupValues[1] }
                return r
            }
            return name.trim().ifEmpty { null }
        }
    }
}
