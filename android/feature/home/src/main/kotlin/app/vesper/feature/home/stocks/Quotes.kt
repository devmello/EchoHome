package app.vesper.feature.home.stocks

import app.vesper.feature.home.glance.Net
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/** One watchlist row. [series] = the session's 5-minute closes from 9:30 (≤ [sessionBars] points). */
data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val percent: Double,
    val previousClose: Double?,
    val series: List<Double>,
    /** Bars the full session has (78 for 9:30–16:00), so a partial day draws partway across. */
    val sessionBars: Int = 78,
    /** Watchlist sparkline points (the design's demo uses a separate 40-point walk). */
    val spark: List<Double> = series,
) {
    /** The chart's dashed line: the previous close, in the same units as [series]. */
    val baseline: Double? get() = previousClose ?: series.firstOrNull()
}

data class Watchlist(val quotes: List<Quote>, val asOfMillis: Long, val demo: Boolean, val source: String)

/** Where quotes come from (swappable: the free source is unofficial). */
interface QuoteSource {
    val name: String
    /** One quote per symbol that loaded; throws IOException if none did. */
    suspend fun quotes(symbols: List<String>): List<Quote>
}

/**
 * Yahoo Finance's public chart endpoint (`query1.finance.yahoo.com/v8/finance/chart/<SYM>?range=1d&interval=5m`):
 * no key, no account; price, previous close, name and the day's 5-minute bars in one request per symbol.
 * It is not an official, documented API: Yahoo's terms allow personal, non-commercial use, quotes
 * may be delayed (~15 min for some exchanges), and it can change or rate-limit without notice —
 * hence [QuoteSource]. We ask only while the Stocks screen is on and the market is open
 * (5 requests every 5 minutes), plus once after the close.
 */
class YahooChartSource(
    private val client: OkHttpClient = Net.http,
    private val base: HttpUrl = "https://query1.finance.yahoo.com/v8/finance/chart/".toHttpUrl(),
) : QuoteSource {
    override val name = "Yahoo Finance"

    override suspend fun quotes(symbols: List<String>): List<Quote> {
        var error: IOException? = null
        val out = symbols.mapNotNull { s ->
            try {
                val url = base.newBuilder().addPathSegment(s).addQueryParameter("range", "1d").addQueryParameter("interval", "5m").build()
                parse(JSONObject(Net.getText(client, url.toString())), s)
            } catch (e: IOException) { error = e; null } catch (e: org.json.JSONException) { error = IOException(e.message); null }
        }
        if (out.isEmpty() && symbols.isNotEmpty()) throw error ?: IOException("No quotes")
        return out
    }

    companion object {
        fun parse(body: JSONObject, symbol: String): Quote? {
            val r = body.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
            val meta = r.optJSONObject("meta") ?: return null
            val price = meta.optDouble("regularMarketPrice").takeUnless { it.isNaN() } ?: return null
            val prev = meta.optDouble("previousClose").takeUnless { it.isNaN() } ?: meta.optDouble("chartPreviousClose").takeUnless { it.isNaN() }
            val ts = r.optJSONArray("timestamp")
            val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
            val regular = meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
            val start = regular?.optLong("start") ?: 0L
            val end = regular?.optLong("end") ?: Long.MAX_VALUE
            val all = ArrayList<Pair<Long, Double>>()
            if (ts != null && closes != null) for (i in 0 until minOf(ts.length(), closes.length())) {
                val c = closes.optDouble(i)
                if (!c.isNaN()) all += ts.optLong(i) to c
            }
            // Regular session only; if the period Yahoo reports isn't the one charted, keep all bars.
            val pts = all.filter { it.first in start until end }.ifEmpty { all }.map { it.second }
            val bars = if (end != Long.MAX_VALUE && start > 0) ((end - start) / 300).toInt().coerceIn(1, 78) else 78
            val change = if (prev != null) price - prev else 0.0
            return Quote(
                symbol = symbol,
                name = shortName(meta.optString("shortName").ifEmpty { meta.optString("longName").ifEmpty { symbol } }),
                price = price,
                change = change,
                percent = if (prev != null && prev != 0.0) change / prev * 100 else 0.0,
                previousClose = prev,
                series = pts.ifEmpty { listOfNotNull(prev, price) },
                sessionBars = bars,
            )
        }

        /** "Apple Inc." → "Apple", "NVIDIA Corporation" → "NVIDIA", "Amazon.com, Inc." → "Amazon.com". */
        fun shortName(n: String): String =
            n.replace(Regex(",?\\s+(Inc\\.?|Corporation|Corp\\.?|Co\\.?|Ltd\\.?|plc|Holdings|Company|N\\.V\\.|S\\.A\\.)$", RegexOption.IGNORE_CASE), "")
                .trim().ifEmpty { n }
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
