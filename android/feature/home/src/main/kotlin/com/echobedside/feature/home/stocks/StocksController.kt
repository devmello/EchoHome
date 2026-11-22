package com.echobedside.feature.home.stocks

import com.echobedside.core.model.SettingsStore
import com.echobedside.feature.home.HomeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the watchlist fresh only while the Stocks screen is visible ([start]/[stop]).
 * Market open: quotes every 5 min, intraday series every 15 min, never both in one minute.
 * Market closed: one fetch for the last session, then nothing until the next open.
 * Without an API key it serves [DemoStocks].
 */
class StocksController(
    private val source: TwelveDataSource?,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val quoteEveryMs: Long = 5 * 60_000L,
    private val seriesEveryMs: Long = 15 * 60_000L,
    /** Twelve Data's free plan allows 8 credits a minute; a 5-symbol batch is 5. */
    private val batchGapMs: Long = 61_000L,
) {
    private val _state = MutableStateFlow<Watchlist?>(null)
    val state: StateFlow<Watchlist?> = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var quotes: Map<String, TwelveDataSource.QuoteData> = emptyMap()
    private var series: Map<String, List<Pair<java.time.LocalDateTime, Double>>> = emptyMap()
    private var quotesAt = 0L
    private var seriesAt = 0L
    private var lastBatchAt = 0L
    private var job: Job? = null

    val symbols: List<String> get() = HomeSettings.list(settings, HomeSettings.STOCK_SYMBOLS)?.map { it.uppercase() }?.take(5)
        ?: listOf("AAPL", "NVDA", "TSLA", "MSFT", "AMZN")

    var featured: Int
        get() = settings.getString(HomeSettings.STOCK_FEATURED, "1").toIntOrNull() ?: 1
        set(v) = settings.putString(HomeSettings.STOCK_FEATURED, v.toString())

    fun start() {
        if (job != null) return
        if (source == null) { _state.value = DemoStocks.watchlist(clock()); return }
        job = scope.launch {
            while (true) {
                val now = clock()
                val open = MarketHours.isOpen(now)
                val session = MarketHours.lastSession(now).atTime(MarketHours.OPEN).atZone(MarketHours.ZONE).toInstant().toEpochMilli()
                // Closed: data fetched after the last session opened is final.
                val quotesStale = quotesAt == 0L || (if (open) now - quotesAt >= quoteEveryMs else quotesAt < session || needsCloseRefresh(now))
                val seriesStale = seriesAt == 0L || (if (open) now - seriesAt >= seriesEveryMs else seriesAt < session || needsCloseRefresh(now, seriesAt))
                if (quotesStale) batch { quotes = source.quotes(symbols); quotesAt = clock() }
                else if (seriesStale) batch { series = source.series(symbols); seriesAt = clock() }
                publish()
                delay(when {
                    _error.value != null -> 5 * 60_000L // back off (bad key, limit, offline)
                    open || quotesStale || seriesStale -> 30_000L
                    else -> (MarketHours.nextOpen(now) - now).coerceIn(60_000L, 6 * 3_600_000L)
                })
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** After today's close, refresh once so the closing prices replace intraday ones. */
    private fun needsCloseRefresh(now: Long, at: Long = quotesAt): Boolean {
        if (MarketHours.phase(now) != MarketHours.Phase.AFTER_CLOSE) return false
        val d = MarketHours.lastSession(now)
        val close = d.atTime(MarketHours.closeTime(d)).atZone(MarketHours.ZONE).toInstant().toEpochMilli()
        return at < close + 20 * 60_000L && now > close + 20 * 60_000L
    }

    private suspend fun batch(block: suspend () -> Unit) {
        val wait = lastBatchAt + batchGapMs - clock()
        if (wait > 0) delay(wait)
        lastBatchAt = clock()
        try { block(); _error.value = null }
        catch (e: TwelveDataSource.QuoteError) { _error.value = if (e.rateLimited) "Quote limit reached · retrying" else "Couldn’t update quotes" }
        catch (e: java.io.IOException) { _error.value = "Couldn’t update quotes" }
    }

    private fun publish() {
        if (quotes.isEmpty()) return
        val now = clock()
        val day = MarketHours.lastSession(now)
        val bars = if (MarketHours.isEarlyClose(day)) 42 else 78
        val list = symbols.mapNotNull { s ->
            val q = quotes[s] ?: return@mapNotNull null
            val pts = series[s].orEmpty().filter { TwelveDataSource.barIndex(it.first) in 0 until bars }.map { it.second }
            Quote(s, shortName(q.name), q.close, q.change, q.percent, q.previousClose,
                // Until the series arrives, a flat line at the price.
                pts.ifEmpty { listOf(q.previousClose ?: q.close, q.close) }, bars)
        }
        _state.value = Watchlist(list, quotesAt, demo = false, source = "Twelve Data")
    }

    companion object {
        /** "Apple Inc." → "Apple", "NVIDIA Corporation" → "NVIDIA" (as in the design). */
        fun shortName(n: String): String = n.replace(Regex(",?\\s+(Inc\\.?|Corporation|Corp\\.?|Co\\.?|Ltd\\.?|plc|Holdings|Company|Class [A-C]|N\\.V\\.|S\\.A\\.)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s+(Inc\\.?|Corporation|Corp\\.?)$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { n }
    }
}
