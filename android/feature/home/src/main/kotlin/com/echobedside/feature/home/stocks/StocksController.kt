package com.echobedside.feature.home.stocks

import com.echobedside.core.model.SettingsStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

object StockSettings {
    /** Watchlist, comma-separated, up to 5 (default AAPL,NVDA,TSLA,MSFT,AMZN). */
    const val SYMBOLS = "stocks.symbols"
    /** Featured row index. */
    const val FEATURED = "stocks.featured"
}

/**
 * Keeps the watchlist fresh only while the Stocks screen is visible ([start]/[stop]).
 * Market open: every 5 min. Closed: one fetch after the session ended, then nothing until the
 * next open. [source] null = the design's demo data.
 */
class StocksController(
    private val source: QuoteSource?,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val everyMs: Long = 5 * 60_000L,
) {
    private val _state = MutableStateFlow<Watchlist?>(null)
    val state: StateFlow<Watchlist?> = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var fetchedAt = 0L
    private var fetchedSymbols: List<String> = emptyList()
    private var job: Job? = null
    private var watch: Job? = null

    val symbols: List<String> get() = settings.getString(StockSettings.SYMBOLS, "").split(',').map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }.take(5).ifEmpty { listOf("AAPL", "NVDA", "TSLA", "MSFT", "AMZN") }

    var featured: Int
        get() = (settings.getString(StockSettings.FEATURED, "1").toIntOrNull() ?: 1).coerceIn(0, 4)
        set(v) = settings.putString(StockSettings.FEATURED, v.toString())

    fun start() {
        if (job != null) return
        if (source == null) { _state.value = DemoStocks.watchlist(clock()); return }
        job = loop(source)
        // A new watchlist from the settings page fetches at once (stale() sees the symbols changed).
        watch = scope.launch { settings.changes.filter { it == StockSettings.SYMBOLS }.collect { job?.cancel(); job = loop(source) } }
    }

    private fun loop(source: QuoteSource): Job = run {
        scope.launch {
            while (true) {
                val now = clock()
                if (stale(now)) {
                    try {
                        _state.value = Watchlist(source.quotes(symbols), clock(), demo = false, source = source.name)
                        fetchedAt = clock(); fetchedSymbols = symbols; _error.value = null
                    } catch (_: IOException) {
                        _error.value = "Couldn’t update quotes"
                    }
                }
                delay(when {
                    _error.value != null -> everyMs
                    MarketHours.isOpen(now) -> everyMs
                    else -> (MarketHours.nextOpen(now) - now).coerceIn(60_000L, 3 * 3_600_000L)
                })
            }
        }
    }

    fun stop() { job?.cancel(); job = null; watch?.cancel(); watch = null }

    /** Open: older than 5 min. Closed: fetched before the last session ended (+20 min for final prints). */
    fun stale(now: Long): Boolean {
        if (fetchedAt == 0L || fetchedSymbols != symbols) return true
        if (MarketHours.isOpen(now)) return now - fetchedAt >= everyMs
        val d = MarketHours.lastSession(now)
        val settled = d.atTime(MarketHours.closeTime(d)).atZone(MarketHours.ZONE).toInstant().toEpochMilli() + 20 * 60_000L
        return fetchedAt < settled && now >= settled
    }
}
