package com.echobedside.feature.home.stocks

import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One watchlist row. [series] = the last session's closes, 5-minute bars from 9:30 (≤ 78 points). */
data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val percent: Double,
    val previousClose: Double?,
    val series: List<Double>,
    /** Bars the full session would have (78 for 9:30–16:00), so a partial day draws partway across. */
    val sessionBars: Int = 78,
    /** Watchlist sparkline points (the design's demo uses a separate 40-point walk). */
    val spark: List<Double> = series,
) {
    /** The chart's dashed line: the previous close, in the same units as [series]. */
    val baseline: Double? get() = previousClose ?: series.firstOrNull()
}

data class Watchlist(val quotes: List<Quote>, val asOfMillis: Long, val demo: Boolean, val source: String)

/**
 * Twelve Data (https://twelvedata.com/docs) — chosen for its free "Basic" plan: 800 API credits a
 * day, 8 a minute, US equities, batch requests (one credit per symbol), personal use.
 *
 *  - `/quote?symbol=A,B,…` → price, change, percent_change, previous_close, name (1 credit/symbol)
 *  - `/time_series?symbol=A,B,…&interval=5min&outputsize=78` → the session's 5-minute closes
 *
 * Budget with 5 symbols, only while the Stocks screen is on and the market is open:
 * quotes every 5 min + series every 15 min ≈ 5 × (78 + 26) = 520 credits a day worst case.
 * The two batches are never sent in the same minute (8 credits/min).
 */
class TwelveDataSource(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val base: HttpUrl = "https://api.twelvedata.com/".toHttpUrl(),
) {
    class QuoteError(message: String, val rateLimited: Boolean = false) : IOException(message)

    data class QuoteData(val name: String, val close: Double, val change: Double, val percent: Double, val previousClose: Double?)

    suspend fun quotes(symbols: List<String>): Map<String, QuoteData> =
        parseQuotes(get("quote", symbols, emptyMap()), symbols)

    suspend fun series(symbols: List<String>): Map<String, List<Pair<LocalDateTime, Double>>> =
        parseSeries(get("time_series", symbols, mapOf("interval" to "5min", "outputsize" to "78", "timezone" to "America/New_York")), symbols)

    private suspend fun get(path: String, symbols: List<String>, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment(path)
            .addQueryParameter("symbol", symbols.joinToString(","))
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        // The key goes in a header, so it never shows up in URLs or logs.
        val req = Request.Builder().url(url).header("Authorization", "apikey $apiKey").build()
        http.newCall(req).execute().use { res ->
            if (res.code == 429) throw QuoteError("Rate limited", rateLimited = true)
            if (!res.isSuccessful) throw QuoteError("HTTP ${res.code}")
            val body = JSONObject(res.body!!.string())
            if (body.optString("status") == "error") throw QuoteError(body.optString("message", "error"), body.optInt("code") == 429)
            body
        }
    }

    companion object {
        /** One symbol → a flat object; several → keyed by symbol. Errors per symbol are skipped. */
        private fun perSymbol(body: JSONObject, symbols: List<String>): Map<String, JSONObject> =
            if (symbols.size == 1) mapOf(symbols[0] to body)
            else symbols.mapNotNull { s -> body.optJSONObject(s)?.takeIf { it.optString("status") != "error" }?.let { s to it } }.toMap()

        fun parseQuotes(body: JSONObject, symbols: List<String>): Map<String, QuoteData> =
            perSymbol(body, symbols).mapNotNull { (s, q) ->
                val close = q.optString("close").toDoubleOrNull() ?: return@mapNotNull null
                s to QuoteData(
                    name = q.optString("name", s),
                    close = close,
                    change = q.optString("change").toDoubleOrNull() ?: 0.0,
                    percent = q.optString("percent_change").toDoubleOrNull() ?: 0.0,
                    previousClose = q.optString("previous_close").toDoubleOrNull(),
                )
            }.toMap()

        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Values come newest first; returns oldest first, only the latest day's bars. */
        fun parseSeries(body: JSONObject, symbols: List<String>): Map<String, List<Pair<LocalDateTime, Double>>> =
            perSymbol(body, symbols).mapValues { (_, o) ->
                val v = o.optJSONArray("values") ?: return@mapValues emptyList()
                val bars = (0 until v.length()).mapNotNull { i ->
                    val b = v.optJSONObject(i) ?: return@mapNotNull null
                    val t = runCatching { LocalDateTime.parse(b.optString("datetime"), TS) }.getOrNull() ?: return@mapNotNull null
                    val c = b.optString("close").toDoubleOrNull() ?: return@mapNotNull null
                    t to c
                }.sortedBy { it.first }
                val day: LocalDate? = bars.lastOrNull()?.first?.toLocalDate()
                bars.filter { it.first.toLocalDate() == day }
            }

        /** Index of a 5-minute bar from 9:30 ET (0 … 77). */
        fun barIndex(t: LocalDateTime): Int = ((t.hour * 60 + t.minute) - (9 * 60 + 30)) / 5
    }
}

/** Stocks.dc.html sample watchlist (labelled demo on screen). */
object DemoStocks {
    private data class D(val sym: String, val name: String, val price: Double, val pct: Double, val seed: Int)
    private val LIST = listOf(
        D("AAPL", "Apple", 241.62, 1.24, 11), D("NVDA", "NVIDIA", 168.05, 2.87, 23), D("TSLA", "Tesla", 352.18, -1.92, 37),
        D("MSFT", "Microsoft", 498.40, 0.41, 41), D("AMZN", "Amazon", 226.75, -0.63, 53),
    )

    /** The design's LCG random walk, so the demo charts match the canvas exactly. */
    fun series(seed: Int, n: Int, drift: Double): List<Double> {
        var s = seed.toDouble()
        fun rnd(): Double { s = (s * 9301 + 49297) % 233280; return s / 233280 }
        val out = ArrayList<Double>(n); out += 0.0
        for (i in 1 until n) out += out[i - 1] + (rnd() - 0.5) * 2 + drift
        return out
    }

    fun watchlist(now: Long): Watchlist = Watchlist(LIST.map { d ->
        val delta = d.price * d.pct / (100 + d.pct)
        // The walk isn't in price units, so no previous close: the baseline is the first point, as in the design.
        Quote(d.sym, d.name, d.price, delta, d.pct, null, series(d.seed, 78, d.pct / 14), 78, series(d.seed, 40, d.pct / 14))
    }, now, demo = true, source = "Demo")
}
