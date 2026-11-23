package app.vesper.feature.radio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Fonts
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The Radio screen (pager order 15, after Now Playing; `--es screen radio`). Two glass platters
 * over a warm dark gradient (rendered once at half size and pre-blurred once for the glass):
 *  - left: what's on (cover, LIVE / connecting, station, the stream's current song, format),
 *    with favourite / play-stop / next favourite;
 *  - right: Favourites, Recent and Genres (8 curated tags + Local, by country), or search
 *    results. Tap a row to play it; the heart keeps it. Drag up/down to scroll.
 *  - the search capsule (top right) opens the keyboard; results search names, genres and countries.
 * Night: black, red text and outlines, no gradient. Draws only on changes (no animation at all).
 *
 * Args: `tab favourites|recent|genres`, `genre <id>`, `search <text>`; `demo 1` plays a sample
 * station silently (for reviews: no audio, no network).
 */
@SuppressLint("ViewConstructor")
class RadioScreen(context: Context, private val services: VesperServices, private val radio: RadioService) : FrameLayout(context), VesperScreen {
    private val canvasView = Canvas2()
    private val field = EditText(context)

    init {
        setBackgroundColor(Color.BLACK)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        field.apply {
            visibility = View.GONE
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.create(Fonts.sans, Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f)
            setTextColor(Color.WHITE)
            setHintTextColor(withAlpha(Color.WHITE, 0.5f))
            hint = "Station, genre or country"
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnEditorActionListener { v, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submitSearch(v.text.toString()); true } else false
            }
            setOnFocusChangeListener { _, has -> if (!has) closeSearch(keepText = true) }
        }
        addView(field, LayoutParams((SEARCH_R - SEARCH_L - 64f).toInt(), (SEARCH_B - SEARCH_T).toInt()).apply {
            leftMargin = (SEARCH_L + 48f).toInt(); topMargin = SEARCH_T.toInt()
        })
    }

    // ------------------------------------------------------------------ state

    private enum class Tab { FAVOURITES, RECENT, GENRES, GENRE, RESULTS }

    private var tab = Tab.FAVOURITES
    private var genre: Genre? = null
    private var query = ""
    private var results: List<Station> = emptyList()
    private var loading = false
    private var loadError: String? = null
    private var loadJob: Job? = null
    private var shown = false
    private var night = false
    private var ui = RadioService.Ui()
    private var favourites: List<Station> = emptyList()
    private var recent: List<Station> = emptyList()
    private var searching = false
    private val main = Handler(Looper.getMainLooper())

    init {
        observeWhileAttached(radio.ui) { ui = it; canvasView.invalidate() }
        observeWhileAttached(radio.library.favourites) { favourites = it; canvasView.invalidate() }
        observeWhileAttached(radio.library.recent) { recent = it; canvasView.invalidate() }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; canvasView.nightChanged() } }
    }

    override fun onScreenShown(args: Bundle?) {
        shown = true
        canvasView.ensureBackground()
        if (tab == Tab.FAVOURITES && favourites.isEmpty() && recent.isEmpty() && !services.config.demoData) tab = Tab.GENRES
        args?.let(::handleArgs)
        canvasView.invalidate()
    }

    override fun onScreenHidden() {
        shown = false
        closeSearch(keepText = true)
    }

    private fun handleArgs(a: Bundle) {
        a.getString("tab")?.let { t -> Tab.values().firstOrNull { it.name.equals(t, true) }?.let { tab = it } }
        a.getString("genre")?.let { Genre.byId(it)?.let(::openGenre) }
        a.getString("search")?.let { if (it.isNotBlank()) submitSearch(it) }
        if (a.getString("demo") != null || a.getString("radio") == "demo") radio.playStation((favourites.firstOrNull { it.isDemo } ?: DemoStations.ALL[1]))
    }

    /** Favourites to show: the saved ones, or (demo mode, none saved) sample stations. */
    private fun shownFavourites(): List<Station> = favourites.ifEmpty { if (services.config.demoData) DemoStations.ALL.take(4) else emptyList() }

    private fun rows(): List<Station> = when (tab) {
        Tab.FAVOURITES -> shownFavourites()
        Tab.RECENT -> recent.ifEmpty { if (services.config.demoData) DemoStations.ALL.drop(3).take(3) else emptyList() }
        Tab.GENRE, Tab.RESULTS -> results
        Tab.GENRES -> emptyList()
    }

    // ------------------------------------------------------------------ loading

    private fun openGenre(g: Genre) {
        genre = g
        tab = Tab.GENRE
        load {
            val country = RadioSettings.country(services.settings)
            radio.browser.search(if (g.tag == null) RadioBrowser.Query(countryCode = country, limit = 40) else RadioBrowser.Query(tag = g.tag, limit = 40))
                .ifEmpty { if (services.config.demoData) DemoStations.forGenre(g, country) else emptyList() }
        }
    }

    private fun submitSearch(text: String) {
        val q = text.trim()
        closeSearch(keepText = true)
        if (q.isEmpty()) return
        query = q
        tab = Tab.RESULTS
        load {
            val s = services.scope
            val byName = s.async { runCatching { radio.browser.search(RadioBrowser.Query(name = q, limit = 30)) } }
            val tag = RadioQuery.tagOf(q)
            val byTag = s.async { if (tag.isEmpty()) Result.success(emptyList()) else runCatching { radio.browser.search(RadioBrowser.Query(tag = tag, limit = 30)) } }
            val cc = RadioQuery.countryCode(q)
            val byCountry = s.async { if (cc == null) Result.success(emptyList()) else runCatching { radio.browser.search(RadioBrowser.Query(countryCode = cc, limit = 30)) } }
            val parts = listOf(byName.await(), byTag.await(), byCountry.await())
            if (parts.all { it.isFailure }) {
                if (services.config.demoData) DemoStations.search(q) else throw parts.first().exceptionOrNull() ?: IOException("offline")
            } else parts.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.uuid }.take(60)
        }
    }

    private fun load(block: suspend () -> List<Station>) {
        loadJob?.cancel()
        results = emptyList()
        loading = true
        loadError = null
        canvasView.scrollTo0()
        canvasView.invalidate()
        loadJob = services.scope.launch {
            try {
                results = block()
            } catch (e: IOException) {
                loadError = "Can't reach Radio Browser"
                if (services.config.demoData) { results = genre?.let { DemoStations.forGenre(it, RadioSettings.country(services.settings)) } ?: DemoStations.search(query); loadError = null }
            }
            loading = false
            canvasView.invalidate()
        }
    }

    private fun retry() {
        when (tab) {
            Tab.GENRE -> genre?.let(::openGenre)
            Tab.RESULTS -> submitSearch(query)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ search field

    private fun openSearch() {
        searching = true
        field.visibility = View.VISIBLE
        field.setText(if (tab == Tab.RESULTS) query else "")
        field.setSelection(field.text.length)
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        canvasView.invalidate()
    }

    private fun closeSearch(keepText: Boolean) {
        if (!searching) return
        searching = false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        field.visibility = View.GONE
        if (!keepText) field.setText("")
        canvasView.invalidate()
    }

    // ------------------------------------------------------------------ drawing

    private inner class Canvas2 : View(context) {
        private val tTitle = SpecText(28f, 800, -0.025f)
        private val tSummary = SpecText(14f, 600)
        private val tBadge = SpecText(12f, 800, 0.06f)
        private val tStation = SpecText(24f, 700, -0.015f)
        private val tSong = SpecText(17f, 600, -0.005f)
        private val tMeta = SpecText(15f, 600)
        private val tTab = SpecText(15f, 700)
        private val tRow = SpecText(18f, 600, -0.005f)
        private val tRowSub = SpecText(14f, 600)
        private val tTile = SpecText(17f, 700, -0.01f)
        private val tEmpty = SpecText(18f, 700)
        private val tHint = SpecText(15f, 600).apply { paint.fontFeatureSettings = "" }
        private val tInitial = SpecText(15f, 800)
        private val tSearch = SpecText(16f, 600)

        private val glassL = GlassPainter()
        private val glassR = GlassPainter()
        private val glassS = GlassPainter()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val bgMatrix = Matrix().apply { setScale(2f, 2f) }
        private val shaderMatrix = Matrix()
        private var bg: Bitmap? = null
        private val hits = ArrayList<Pair<RectF, () -> Unit>>()
        private val fitCache = HashMap<String, String>()
        private var scroll = 0f

        private val icSearch = icon("M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13M20 20l-4.8-4.8")
        private val icHeart = icon("M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z")
        private val icPlay = icon("M8 5.5v13l10.5-6.5z")
        private val icStop = icon("M7 7h10v10H7z")
        private val icNext = icon("M6 6.5v11l8-5.5zM17.5 6.5v11")
        private val icRadio = icon("M4 10h16v10H4zM7 10l10-5.5M8.5 15a1.5 1.5 0 1 0 0.01 0M14 14h3M14 17h3")
        private val icBack = icon("m14.5 6-6 6 6 6")
        private val icWave = icon("M5 9.5v5M9 7v10M13 10v4M17 8v8M21 11v2")

        fun ensureBackground() {
            if (bg != null) return
            val b = renderBackground()
            bg = b
            val bd = Backdrop(PreBlur.blur(b, 10f, 2), 0f, 0f, 960f, 480f)
            glassL.backdrop = bd; glassR.backdrop = bd; glassS.backdrop = bd
        }

        fun nightChanged() {
            glassL.night = night; glassR.night = night; glassS.night = night
            invalidate()
        }

        fun scrollTo0() { scroll = 0f }

        /** Warm and dark: deep wine to ink, with soft red, orange and violet light. 480×240, drawn ×2. */
        private fun renderBackground(): Bitmap {
            val w = 480f; val h = 240f
            val b = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            p.shader = LinearGradient(0f, 0f, w, h, 0xFF2A0B16.toInt(), 0xFF0E0A1F.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            fun blob(cx: Float, cy: Float, r: Float, color: Int, a: Float) {
                p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, a), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
            }
            blob(0.14f * w, 0.30f * h, 0.42f * w, VesperColors.RED, 0.50f)
            blob(0.55f * w, 0.02f * h, 0.30f * w, VesperColors.ORANGE, 0.30f)
            blob(0.90f * w, 0.85f * h, 0.42f * w, VesperColors.PURPLE, 0.34f)
            blob(0.35f * w, 1.05f * h, 0.34f * w, VesperColors.PINK, 0.22f)
            return b
        }

        override fun onDraw(c: Canvas) {
            hits.clear()
            if (night) c.drawColor(Color.BLACK) else bg?.let { c.drawBitmap(it, bgMatrix, bgPaint) }
            tTitle.draw(c, "Radio", 32f, tTitle.baselineIn(22f, 28f * 1.1f), ink(Color.WHITE))
            tSummary.draw(c, summary(), 32f, tSummary.baselineIn(22f + 28f * 1.1f + 2f), ink(withAlpha(Color.WHITE, 0.78f)))
            drawSearch(c)
            drawNow(c)
            drawList(c)
            val hint = if (tab == Tab.GENRES) "Say “Hey Nova, play some jazz radio”" else "Tap a station to play · ♥ keeps it in Favourites"
            tHint.draw(c, hint, 480f - tHint.width(hint) / 2, 480f - 18f - tHint.descent, ink(withAlpha(Color.WHITE, 0.62f)))
        }

        private fun summary(): String {
            val n = favourites.size
            val fav = when (n) { 0 -> "No favourites yet"; 1 -> "1 favourite"; else -> "$n favourites" }
            return "$fav · Stations from radio-browser.info"
        }

        // ---- search capsule

        private fun drawSearch(c: Canvas) {
            glassS.draw(c, SEARCH_L, SEARCH_T, SEARCH_R, SEARCH_B, -1f)
            val cy = (SEARCH_T + SEARCH_B) / 2
            drawIcon(c, icSearch, SEARCH_L + 26f, cy, 20f, ink(withAlpha(Color.WHITE, 0.8f)), 2.2f)
            if (!searching) {
                val s = if (tab == Tab.RESULTS && query.isNotEmpty()) query else "Search stations"
                tSearch.draw(c, fit(tSearch, s, SEARCH_R - SEARCH_L - 70f), SEARCH_L + 48f, tSearch.baselineCentered(cy),
                    ink(withAlpha(Color.WHITE, if (tab == Tab.RESULTS) 0.95f else 0.6f)))
            }
            hit(SEARCH_L, SEARCH_T - 6f, SEARCH_R, SEARCH_B + 6f) { if (!searching) openSearch() }
        }

        // ---- left: now playing

        private fun drawNow(c: Canvas) {
            glassL.draw(c, L_L, CARD_T, L_R, CARD_B, 34f)
            val s = ui.station
            val artL = L_L + 24f; val artT = CARD_T + 24f; val art = 112f
            rect.set(artL, artT, artL + art, artT + art)
            if (s == null) {
                paint.color = ink(withAlpha(Color.WHITE, 0.10f))
                c.drawRoundRect(rect, 26f, 26f, paint)
                drawIcon(c, icRadio, rect.centerX(), rect.centerY(), 54f, ink(withAlpha(Color.WHITE, 0.7f)), 2f)
                val t = "Pick a station"
                tStation.draw(c, t, artL, CARD_T + 186f, ink(Color.WHITE))
                val h1 = "Tap one on the right, or say"
                val h2 = "“Hey Nova, play some jazz”"
                tMeta.draw(c, h1, artL, CARD_T + 216f, ink(withAlpha(Color.WHITE, 0.62f)))
                tMeta.draw(c, h2, artL, CARD_T + 238f, ink(withAlpha(Color.WHITE, 0.62f)))
                return
            }
            drawCover(c, s)

            // badge
            val bx = artL + art + 20f
            val by = artT + 14f
            val (label, col) = when {
                ui.state == RadioPlayer.State.PLAYING -> "LIVE" to VesperColors.RED
                ui.state == RadioPlayer.State.CONNECTING -> "CONNECTING" to VesperColors.ORANGE
                ui.state == RadioPlayer.State.ERROR -> "NOT PLAYING" to withAlpha(Color.WHITE, 0.5f)
                else -> "STOPPED" to withAlpha(Color.WHITE, 0.5f)
            }
            paint.color = ink(col)
            c.drawCircle(bx + 4f, by, 4f, paint)
            tBadge.draw(c, label, bx + 14f, tBadge.baselineCentered(by), ink(col))
            val metaW = L_R - 20f - bx
            var y = by + 34f
            for (line in listOf(s.genre, s.country.ifBlank { s.countryCode }, s.copy(bitrate = if (s.bitrate > 0) s.bitrate else ui.bitrate).format)) {
                if (line.isBlank()) continue
                tMeta.draw(c, fit(tMeta, line, metaW), bx, y, ink(withAlpha(Color.WHITE, 0.62f)))
                y += 24f
            }

            val tw = L_R - L_L - 48f
            tStation.draw(c, fit(tStation, s.name, tw), artL, CARD_T + 176f, ink(Color.WHITE))
            val second = ui.error ?: ui.song?.let { song -> IcyMeta.split(song).let { (a, t) -> if (a != null) "$a — $t" else t } }
            if (second != null) {
                tSong.draw(c, fit(tSong, second, tw), artL, CARD_T + 206f, ink(if (ui.error != null) withAlpha(VesperColors.RED, 0.95f) else withAlpha(Color.WHITE, 0.82f)))
            }

            // controls: favourite, play/stop, next
            val cy = CARD_T + 290f
            val fav = radio.library.isFavourite(s.uuid)
            val cxFav = L_L + 80f; val cxPlay = (L_L + L_R) / 2; val cxNext = L_R - 80f
            paint.color = ink(withAlpha(Color.WHITE, 0.14f))
            c.drawCircle(cxFav, cy, 26f, paint)
            drawHeart(c, cxFav, cy, 24f, fav)
            if (!s.isDemo) hit(cxFav - 32f, cy - 32f, cxFav + 32f, cy + 32f) { radio.library.toggleFavourite(s) }
            paint.color = ink(Color.WHITE)
            c.drawCircle(cxPlay, cy, 34f, paint)
            fillIcon(c, if (ui.on) icStop else icPlay, cxPlay + if (ui.on) 0f else 2f, cy, 30f, if (night) Color.BLACK else 0xFF1A0B12.toInt())
            hit(cxPlay - 40f, cy - 40f, cxPlay + 40f, cy + 40f) { radio.toggle() }
            val canNext = skipCount() >= 2
            paint.color = ink(withAlpha(Color.WHITE, if (canNext) 0.14f else 0.06f))
            c.drawCircle(cxNext, cy, 26f, paint)
            fillIcon(c, icNext, cxNext, cy, 22f, ink(withAlpha(Color.WHITE, if (canNext) 0.95f else 0.3f)), strokeToo = true)
            if (canNext) hit(cxNext - 32f, cy - 32f, cxNext + 32f, cy + 32f) { radio.skip(1) }
        }

        private fun skipCount(): Int {
            val cur = ui.station?.uuid ?: return 0
            return when {
                favourites.size >= 2 && favourites.any { it.uuid == cur } -> favourites.size
                cur.startsWith("demo-") -> DemoStations.ALL.size
                else -> favourites.ifEmpty { recent }.size
            }
        }

        private fun drawCover(c: Canvas, s: Station) {
            val icon = radio.art.cachedIcon(s)
            val key = s.uuid + (icon != null)
            val cover = coverCache?.takeIf { coverKey == key } ?: RadioArt.cover(s, icon).also { coverCache?.recycle(); coverCache = it; coverKey = key }
            if (icon == null && s.favicon.isNotEmpty()) services.scope.launch { if (radio.art.icon(s) != null) invalidate() }
            val k = rect.width() / cover.width
            shaderMatrix.setScale(k, k); shaderMatrix.postTranslate(rect.left, rect.top)
            val sh = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(shaderMatrix) }
            bmpPaint.shader = sh
            bmpPaint.colorFilter = if (night) android.graphics.PorterDuffColorFilter(Night.COLOR, android.graphics.PorterDuff.Mode.MULTIPLY) else null
            c.drawRoundRect(rect, 26f, 26f, bmpPaint)
            bmpPaint.shader = null
            bmpPaint.colorFilter = null
        }

        private var coverCache: Bitmap? = null
        private var coverKey: String? = null

        // ---- right: lists

        private fun drawList(c: Canvas) {
            glassR.draw(c, R_L, CARD_T, R_R, CARD_B, 34f)
            // tabs
            val cy = CARD_T + 34f
            var x = R_L + 18f
            val tabs = buildList {
                if (tab == Tab.GENRE) add(Triple("Genres", Tab.GENRES, true))
                add(Triple("Favourites", Tab.FAVOURITES, false))
                add(Triple("Recent", Tab.RECENT, false))
                if (tab != Tab.GENRE) add(Triple("Genres", Tab.GENRES, false))
                if (tab == Tab.RESULTS) add(Triple("Results", Tab.RESULTS, false))
            }
            for ((label, t, back) in tabs) {
                val active = t == tab
                val textW = tTab.width(label)
                val w = textW + 28f + if (back) 16f else 0f
                rect.set(x, cy - 17f, x + w, cy + 17f)
                if (active) { paint.color = ink(withAlpha(Color.WHITE, 0.18f)); c.drawRoundRect(rect, 17f, 17f, paint) }
                var tx = x + 14f
                if (back) { drawIcon(c, icBack, tx + 5f, cy, 16f, ink(withAlpha(Color.WHITE, 0.85f)), 2.4f); tx += 16f }
                tTab.draw(c, label, tx, tTab.baselineCentered(cy), ink(withAlpha(Color.WHITE, if (active) 1f else 0.62f)))
                val target = t
                hit(rect.left, rect.top - 8f, rect.right, rect.bottom + 8f) { selectTab(target) }
                x += w + 6f
            }
            if (tab == Tab.GENRE) genre?.let { g ->
                val w = tTab.width(g.label) + 28f
                rect.set(x, cy - 17f, x + w, cy + 17f)
                paint.color = ink(withAlpha(g.color, 0.34f)); c.drawRoundRect(rect, 17f, 17f, paint)
                tTab.draw(c, g.label, x + 14f, tTab.baselineCentered(cy), ink(Color.WHITE))
            }
            paint.color = ink(withAlpha(Color.WHITE, 0.1f))
            c.drawRect(R_L + 20f, LIST_T - 2f, R_R - 20f, LIST_T - 1.5f, paint)

            if (tab == Tab.GENRES) { drawGenres(c); return }
            val rows = rows()
            val mid = (LIST_T + CARD_B) / 2f
            when {
                loading && rows.isEmpty() -> centred(c, "Loading stations…", null, mid)
                loadError != null && rows.isEmpty() -> {
                    centred(c, loadError!!, "Check the Wi-Fi, then tap here to try again", mid)
                    hit(R_L, LIST_T, R_R, CARD_B) { retry() }
                }
                rows.isEmpty() -> when (tab) {
                    Tab.FAVOURITES -> centred(c, "No favourites yet", "Tap ♥ on a station to keep it here", mid)
                    Tab.RECENT -> centred(c, "Nothing played yet", "Stations you play show up here", mid)
                    Tab.RESULTS -> centred(c, "No stations found", "Try a name, a genre like jazz, or a country", mid)
                    else -> centred(c, "No stations here right now", null, mid)
                }
                else -> {
                    clampScroll(rows.size)
                    c.save()
                    c.clipRect(R_L, LIST_T, R_R, LIST_B)
                    var i = (scroll / ROW_H).toInt().coerceAtLeast(0)
                    while (i < rows.size) {
                        val y = LIST_T + i * ROW_H - scroll
                        if (y > LIST_B) break
                        drawRow(c, rows[i], y, last = i == rows.size - 1)
                        i++
                    }
                    c.restore()
                }
            }
        }

        private fun drawRow(c: Canvas, s: Station, y: Float, last: Boolean) {
            val cy = y + ROW_H / 2f
            val current = ui.station?.uuid == s.uuid
            if (current) {
                paint.color = ink(withAlpha(VesperColors.RED, if (ui.on) 0.22f else 0.12f))
                rect.set(R_L + 10f, y + 3f, R_R - 10f, y + ROW_H - 3f)
                c.drawRoundRect(rect, 18f, 18f, paint)
            }
            // icon
            val il = R_L + 22f
            rect.set(il, cy - 20f, il + 40f, cy + 20f)
            val icon = radio.art.cachedIcon(s)
            if (icon != null && !night) {
                paint.color = Color.WHITE
                c.drawRoundRect(rect, 10f, 10f, paint)
                val k = min(34f / icon.width, 34f / icon.height)
                val w = icon.width * k; val h = icon.height * k
                c.drawBitmap(icon, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), bmpPaint)
            } else {
                paint.color = ink(Color.HSVToColor(floatArrayOf(RadioArt.hue(s.name), 0.6f, 0.55f)))
                c.drawRoundRect(rect, 10f, 10f, paint)
                val t = RadioArt.initials(s.name)
                tInitial.draw(c, t, rect.centerX() - tInitial.width(t) / 2, tInitial.baselineCentered(cy), if (night) Color.BLACK else Color.WHITE)
                if (s.favicon.isNotEmpty() && icon == null && !night) requestIcon(s)
            }
            // heart
            val hx = R_R - 40f
            val fav = favourites.any { it.uuid == s.uuid }
            drawHeart(c, hx, cy, 22f, fav)
            val top = max(LIST_T, y); val bottom = min(LIST_B, y + ROW_H)
            if (bottom > top && !s.isDemo) hit(hx - 26f, top, hx + 26f, bottom) { radio.library.toggleFavourite(s) }
            // live marker
            var right = hx - 28f
            if (current && ui.on) {
                drawIcon(c, icWave, right - 10f, cy, 20f, ink(VesperColors.RED), 2.2f)
                right -= 30f
            }
            // text
            val tx = il + 54f
            val w = right - tx
            tRow.draw(c, fit(tRow, s.name, w), tx, cy - 3f, ink(Color.WHITE))
            val sub = listOf(s.subtitle, s.format).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            tRowSub.draw(c, fit(tRowSub, sub, w), tx, cy + 17f, ink(withAlpha(Color.WHITE, 0.58f)))
            if (!last) {
                paint.color = ink(withAlpha(Color.WHITE, 0.08f))
                c.drawRect(tx, y + ROW_H - 0.5f, R_R - 20f, y + ROW_H, paint)
            }
            if (bottom > top) hit(R_L, top, R_R, bottom) { radio.playStation(s) }
        }

        private val pendingIcons = HashSet<String>()

        private fun requestIcon(s: Station) {
            if (!pendingIcons.add(s.favicon)) return
            services.scope.launch {
                radio.art.icon(s)
                pendingIcons.remove(s.favicon)
                if (shown) invalidate()
            }
        }

        private fun drawGenres(c: Canvas) {
            val cols = 4
            val gap = 12f
            val x0 = R_L + 20f
            val w = (R_R - R_L - 40f - gap * (cols - 1)) / cols
            val h = 112f
            val y0 = LIST_T + 16f
            Genre.ALL.forEachIndexed { i, g ->
                val l = x0 + (i % cols) * (w + gap)
                val t = y0 + (i / cols) * (h + gap)
                rect.set(l, t, l + w, t + h)
                paint.color = ink(withAlpha(g.color, 0.30f))
                c.drawRoundRect(rect, 22f, 22f, paint)
                paint.color = ink(g.color)
                c.drawCircle(l + 26f, t + 28f, 9f, paint)
                val label = if (g.tag == null) "Local · ${RadioSettings.country(services.settings)}" else g.label
                tTile.draw(c, fit(tTile, label, w - 28f), l + 16f, t + h - 20f, ink(Color.WHITE))
                hit(l, t, l + w, t + h) { openGenre(g) }
            }
        }

        private fun centred(c: Canvas, a: String, b: String?, mid: Float) {
            val cx = (R_L + R_R) / 2
            tEmpty.draw(c, a, cx - tEmpty.width(a) / 2, mid - 6f, ink(withAlpha(Color.WHITE, 0.85f)))
            if (b != null) tMeta.draw(c, b, cx - tMeta.width(b) / 2, mid + 22f, ink(withAlpha(Color.WHITE, 0.55f)))
        }

        private fun drawHeart(c: Canvas, cx: Float, cy: Float, size: Float, on: Boolean) {
            if (on) fillIcon(c, icHeart, cx, cy, size, ink(VesperColors.PINK), strokeToo = true)
            else drawIcon(c, icHeart, cx, cy, size, ink(withAlpha(Color.WHITE, 0.75f)), 2f)
        }

        private fun ink(color: Int) = Night.map(color, night)

        private fun hit(l: Float, t: Float, r: Float, b: Float, action: () -> Unit) { hits += RectF(l, t, r, b) to action }

        private fun fit(t: SpecText, s: String, maxW: Float): String {
            if (maxW <= 0f) return ""
            val key = "${t.size}|${maxW.toInt()}|$s"
            return fitCache.getOrPut(key) {
                if (fitCache.size > 400) fitCache.clear()
                if (t.width(s) <= maxW) s else {
                    var n = s.length
                    while (n > 1 && t.width(s.substring(0, n).trimEnd() + "…") > maxW) n--
                    s.substring(0, n).trimEnd() + "…"
                }
            }
        }

        private fun SpecText.baselineCentered(cy: Float) = cy - normalLineHeight / 2f + ascent

        private inner class Glyph(val path: Path)

        private fun icon(d: String): Glyph {
            val p = Path()
            SvgPath.parse(d, object : PathSink {
                override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
                override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
                override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
                override fun close() = p.close()
            })
            return Glyph(p)
        }

        private fun drawIcon(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, strokeW: Float) {
            val s = size / 24f
            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.scale(s, s)
            stroke.color = color
            stroke.strokeWidth = strokeW / s
            c.drawPath(g.path, stroke)
            c.restore()
        }

        private fun fillIcon(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, strokeToo: Boolean = false) {
            val s = size / 24f
            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.scale(s, s)
            paint.color = color
            c.drawPath(g.path, paint)
            if (strokeToo) { stroke.color = color; stroke.strokeWidth = 1.6f; c.drawPath(g.path, stroke) }
            c.restore()
        }

        // ---- touch

        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var startScroll = 0f

        private fun maxScroll(n: Int) = max(0f, n * ROW_H - (LIST_B - LIST_T))
        private fun clampScroll(n: Int) { scroll = scroll.coerceIn(0f, maxScroll(n)) }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = false; startScroll = scroll
                    if (searching) closeSearch(keepText = true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - downY
                    val inList = downX in R_L..R_R && downY in LIST_T..LIST_B
                    if (!dragging && inList && abs(dy) > slop && abs(dy) > abs(e.x - downX) && maxScroll(rows().size) > 0f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) { scroll = (startScroll - dy).coerceIn(0f, maxScroll(rows().size)); invalidate() }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                    if (!dragging && !moved) hits.firstOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
            }
            return super.onTouchEvent(e)
        }
    }

    private fun selectTab(t: Tab) {
        if (t == tab && t != Tab.GENRES) return
        tab = t
        if (t != Tab.GENRE) genre = null
        canvasView.scrollTo0()
        canvasView.invalidate()
    }

    companion object {
        private const val CARD_T = 88f
        private const val CARD_B = 432f
        private const val L_L = 28f
        private const val L_R = 388f
        private const val R_L = 404f
        private const val R_R = 932f
        private const val LIST_T = CARD_T + 68f
        private const val LIST_B = CARD_B - 8f
        private const val ROW_H = 58f
        private const val SEARCH_L = 600f
        private const val SEARCH_T = 26f
        private const val SEARCH_R = 932f
        private const val SEARCH_B = 70f
    }
}
