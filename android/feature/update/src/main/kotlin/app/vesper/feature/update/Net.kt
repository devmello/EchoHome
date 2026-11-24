package app.vesper.feature.update

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One HTTP answer; [body] streams (never held in memory). */
class HttpResult(
    val code: Int,
    private val headerFn: (String) -> String?,
    val body: InputStream?,
    private val closer: () -> Unit,
) : Closeable {
    fun header(name: String): String? = headerFn(name)
    override fun close() = closer()
}

fun interface Fetcher {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

/**
 * The only place the updater talks to the internet: HTTPS to GitHub (the API for the feed, the
 * release download and its redirect to githubusercontent). Every hop, redirects included, is
 * checked against [allowed]. Generic User-Agent, no device data (docs/software-updates.md §10).
 */
class GitHubFetcher : Fetcher {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor(Interceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || !allowed(url.host)) throw IOException("Refused to fetch from ${url.host}")
            chain.proceed(chain.request())
        })
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        for ((k, v) in headers) b.header(k, v)
        val resp = client.newCall(b.build()).execute()
        return HttpResult(resp.code, { resp.header(it) }, resp.body?.byteStream(), { resp.close() })
    }

    companion object {
        const val USER_AGENT = "EchoHome-updater"
        fun allowed(host: String): Boolean = host == "api.github.com" || host == "github.com" ||
            host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }
}
