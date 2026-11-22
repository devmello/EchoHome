package app.vesper.feature.home.glance

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One HTTP client for the glance sources (Open-Meteo, iCal, Yahoo chart, Nominatim, OSRM).
 * Every request identifies the app with a generic User-Agent (the services' usage policies ask
 * for one) and carries no personal data beyond what each request needs.
 */
object Net {
    const val USER_AGENT = "Vesper/0.1 (personal bedside display; Android)"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
            .build()
    }

    class HttpError(val code: Int, message: String) : IOException(message)

    /** GET → body text, off the main thread. Throws IOException. */
    suspend fun getText(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw HttpError(res.code, "HTTP ${res.code} from ${req.url.host}")
            res.body!!.string()
        }
    }
}
