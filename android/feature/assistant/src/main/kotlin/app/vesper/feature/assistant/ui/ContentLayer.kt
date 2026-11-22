package app.vesper.feature.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import app.vesper.core.design.Backdrop
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.feature.assistant.pipeline.AssistState
import app.vesper.feature.assistant.pipeline.Phase
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import app.vesper.feature.assistant.pipeline.StepStatus
import kotlin.math.min

/**
 * Everything on the assistant screen except the background, the voice light and the droplet
 * (Assistant.dc.html, device px at 960×480): status pill + clock, the idle title and chips, the
 * live transcript, "Working on it…" with tool steps, the revealed answer and its result cards.
 * Layout is computed on [bind]; [frame] only advances the reveal, the card fade and the spinner,
 * and invalidates when something visible changed.
 */
@SuppressLint("ViewConstructor")
class ContentLayer(context: Context, private val actions: Actions) : View(context) {

    interface Actions {
        fun chip(index: Int)
        fun suggestion(accept: Boolean)
        fun openMedia()
    }

    class Chip(val label: String, val color: Int)

    var night = false
        set(v) { if (field != v) { field = v; glass.night = v; invalidate() } }
    var backdrop: Backdrop?
        get() = glass.backdrop
        set(v) { glass.backdrop = v; invalidate() }

    // ---- text styles ----
    private val tPill = SpecText(14f, 600, -0.01f)
    private val tClock = SpecText(17f, 600)
    private val tTitle = SpecText(46f, 700, -0.03f)
    private val tSub = SpecText(18f, 500)
    private val tChip = SpecText(16f, 600, -0.01f)
    private val tListen = SpecText(44f, 650, -0.028f)
    private val tQuery = SpecText(20f, 600, -0.01f)
    private val tWorking = SpecText(40f, 700, -0.03f)
    private val tStep = SpecText(15f, 600)
    private val tAnswer = SpecText(29f, 600, -0.02f)
    private val t14s = SpecText(14f, 600)
    private val t14m = SpecText(14f, 500)
    private val t15m = SpecText(15f, 500)
    private val t15b = SpecText(15f, 700)
    private val t15s = SpecText(15f, 600)
    private val t17b = SpecText(17f, 700, -0.015f)
    private val t20b = SpecText(20f, 700, -0.015f)
    private val t22b = SpecText(22f, 700, -0.015f)
    private val t22bm = SpecText(22f, 700, -0.02f)
    private val t26b = SpecText(26f, 700, -0.02f)

    private val glass = GlassPainter()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()

    // ---- model ----
    private var state = AssistState()
    private var chips: List<Chip> = emptyList()
    private var subtitle = ""
    private var statusText = "Ready"
    private var statusDot = 0xFF8E8E93.toInt()
    private var clock = ""
    var suggestionAccepted = false
        set(v) { if (field != v) { field = v; invalidate() } }

    // ---- layout ----
    private val chipRects = ArrayList<RectF>()
    private var listenLines: List<Line> = emptyList()
    private var answerWords: List<String> = emptyList()
    private var answerLines: List<Line> = emptyList()
    private var queryText = ""
    private val stepRects = ArrayList<RectF>()
    private var cardsTop = 234f
    private var showCards = true
    private val hit = HashMap<String, RectF>()

    // ---- animation ----
    private var revealStart = -1L
    private var shownWords = 0
    private var cardsStart = -1L
    private var cardsAlpha = 0f
    private var cardsShift = 16f
    private var spin = 0f
    private val stepFade = HashMap<String, Tween>()
    private var lastAnswer = ""

    private class Line(val words: List<Pair<String, Boolean>>, val width: Float)

    /** New state from the engine (main thread). */
    fun bind(s: AssistState, chips: List<Chip>, subtitle: String, status: Pair<String, Int>, now: Long) {
        val prev = state
        state = s
        this.chips = chips
        this.subtitle = subtitle
        statusText = status.first; statusDot = status.second
        if (s.phase == Phase.LISTENING) layoutListening(s)
        queryText = "“" + s.query + "”"
        if (s.phase == Phase.WORKING || s.phase == Phase.ANSWER) layoutSteps(s, now)
        if ((s.phase == Phase.ANSWER || s.phase == Phase.ERROR) && (s.answer != lastAnswer || prev.phase != s.phase)) {
            lastAnswer = s.answer
            answerWords = s.answer.split(' ').filter { it.isNotEmpty() }
            revealStart = now; shownWords = 0; cardsStart = -1L; cardsAlpha = 0f; cardsShift = 16f
            suggestionAccepted = false
            layoutAnswer()
        }
        if (s.phase != Phase.ANSWER && s.phase != Phase.ERROR) { lastAnswer = ""; revealStart = -1L }
        if (s.phase == Phase.IDLE) layoutChips()
        invalidate()
    }

    fun setClock(text: String) { if (text != clock) { clock = text; invalidate() } }

    /** Advances the reveal / cards / spinner. Returns true while something is still animating. */
    fun frame(now: Long): Boolean {
        var animating = false
        if (revealStart >= 0) {
            val n = min(answerWords.size, ((now - revealStart) / REVEAL_WORD_MS).toInt() + 1)
            if (n != shownWords) { shownWords = n; invalidate() }
            if (n < answerWords.size) animating = true
            else if (cardsStart < 0 && state.cards.isNotEmpty()) cardsStart = now
        }
        if (cardsStart >= 0) {
            val ta = ((now - cardsStart) / 400f).coerceIn(0f, 1f)
            val ts = ((now - cardsStart) / 500f).coerceIn(0f, 1f)
            val a = Motion.EASE.getInterpolation(ta)
            val sh = 16f * (1f - CARD_SPRING.getInterpolation(ts))
            if (a != cardsAlpha || sh != cardsShift) { cardsAlpha = a; cardsShift = sh; invalidate() }
            if (ts < 1f) animating = true
        }
        for (tw in stepFade.values) if (tw.step(now)) { animating = true; invalidate() }
        if (state.phase == Phase.WORKING && state.steps.any { it.status == StepStatus.BUSY }) {
            spin = ((now / 1000.0 * 60.0) % 360.0).toFloat()
            invalidate()
            animating = true
        }
        return animating
    }

    // ---------------- layout ----------------

    private fun layoutChips() {
        chipRects.clear()
        val widths = chips.map { 16f + 8f + 9f + tChip.width(it.label) + 20f }
        val maxW = 960f - 120f
        // Rows of chips, centered (flex-wrap).
        var i = 0
        var top = 232f
        while (i < widths.size) {
            var w = widths[i]; var j = i + 1
            while (j < widths.size && w + 12f + widths[j] <= maxW) { w += 12f + widths[j]; j++ }
            var x = (960f - w) / 2f
            for (k in i until j) { chipRects += RectF(x, top, x + widths[k], top + 48f); x += widths[k] + 12f }
            top += 48f + 12f
            i = j
        }
    }

    private fun layoutListening(s: AssistState) {
        val words = ArrayList<Pair<String, Boolean>>()
        s.heard.split(' ').filter { it.isNotEmpty() }.forEach { words += it to true }
        s.unheard.split(' ').filter { it.isNotEmpty() }.forEach { words += it to false }
        val lines = wrap(words, tListen, 832f)
        // Long requests: keep the last 4 lines on screen.
        listenLines = if (lines.size > 4) lines.takeLast(4) else lines
    }

    private fun layoutAnswer() {
        val lines = wrap(answerWords.map { it to true }, tAnswer, 832f)
        val lh = 29f * 1.26f
        val maxLines = if (state.cards.isEmpty()) 7 else 5
        answerLines = lines.take(maxLines)
        val textBottom = 112f + answerLines.size * lh
        cardsTop = maxOf(234f, textBottom + 14f)
        showCards = state.cards.isNotEmpty() && cardsTop + cardHeight() <= 480f - 26f - 66f - 8f
    }

    private fun cardHeight(): Float {
        val c = state.cards
        return when {
            c.any { it is ResultCard.Weather || it is ResultCard.AlarmSuggestion } -> 136f
            c.any { it is ResultCard.Media } -> 124f
            else -> 120f
        }
    }

    private fun layoutSteps(s: AssistState, now: Long) {
        stepRects.clear()
        var x = 64f
        var top = 130f + tWorking.normalLineHeight + 22f
        for (st in s.steps) {
            val w = 12f + 16f + 8f + tStep.width(st.label) + 16f
            if (x + w > 896f && x > 64f) { x = 64f; top += 40f + 10f }
            stepRects += RectF(x, top, x + w, top + 40f)
            x += w + 10f
            val target = if (st.status == StepStatus.PENDING) 0.35f else 1f
            val tw = stepFade.getOrPut(st.id) { Tween(target, 300, Motion.EASE) }
            if (tw.target != target) tw.set(target, now)
        }
        stepFade.keys.retainAll(s.steps.map { it.id }.toSet())
    }

    private fun wrap(words: List<Pair<String, Boolean>>, t: SpecText, maxW: Float): List<Line> {
        val out = ArrayList<Line>()
        val space = t.width(" ")
        var cur = ArrayList<Pair<String, Boolean>>()
        var w = 0f
        for (word in words) {
            val ww = t.width(word.first)
            if (cur.isNotEmpty() && w + space + ww > maxW) { out += Line(cur, w); cur = ArrayList(); w = 0f }
            w += if (cur.isEmpty()) ww else space + ww
            cur += word
        }
        if (cur.isNotEmpty()) out += Line(cur, w)
        return out
    }

    // ---------------- drawing ----------------

    private fun c(color: Int) = Night.map(color, night)
    private fun white(a: Float) = c(withAlpha(Color.WHITE, a))

    override fun onDraw(canvas: Canvas) {
        hit.clear()
        drawStatus(canvas)
        when (state.phase) {
            Phase.IDLE -> drawIdle(canvas)
            Phase.LISTENING -> drawListening(canvas)
            Phase.WORKING -> { drawQuery(canvas); drawWorking(canvas) }
            Phase.ANSWER, Phase.ERROR -> { if (state.query.isNotEmpty()) drawQuery(canvas); drawAnswer(canvas) }
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val tw = tPill.width(statusText)
        val w = 12f + 8f + 8f + tw + 14f
        glass.alpha = 1f
        glass.draw(canvas, 28f, 22f, 28f + w, 58f, 18f)
        fill.color = c(statusDot)
        canvas.drawCircle(28f + 12f + 4f, 40f, 4f, fill)
        tPill.draw(canvas, statusText, 28f + 12f + 8f + 8f, tPill.baselineIn(22f, 36f), white(1f))
        if (clock.isNotEmpty()) {
            val cw = tClock.width(clock)
            tClock.draw(canvas, clock, 960f - 32f - cw, tClock.baselineIn(28f), white(0.86f))
        }
    }

    private fun drawIdle(canvas: Canvas) {
        val title = "How can I help?"
        tTitle.draw(canvas, title, (960f - tTitle.width(title)) / 2f, tTitle.baselineIn(104f), white(1f))
        val subTop = 104f + tTitle.normalLineHeight + 8f
        tSub.draw(canvas, subtitle, (960f - tSub.width(subtitle)) / 2f, tSub.baselineIn(subTop), white(0.66f))
        chips.forEachIndexed { i, chip ->
            val r = chipRects.getOrNull(i) ?: return@forEachIndexed
            glass.draw(canvas, r.left, r.top, r.right, r.bottom, 24f)
            fill.color = c(chip.color)
            canvas.drawCircle(r.left + 16f + 4f, r.centerY(), 4f, fill)
            tChip.draw(canvas, chip.label, r.left + 16f + 8f + 9f, tChip.baselineIn(r.top, 48f), white(1f))
            hit["chip$i"] = r
        }
    }

    private fun drawListening(canvas: Canvas) {
        val lh = 44f * 1.18f
        listenLines.forEachIndexed { i, line ->
            var x = 64f
            val base = tListen.baselineIn(116f + i * lh, lh)
            for ((k, word) in line.words.withIndex()) {
                val s = if (k == 0) word.first else " " + word.first
                x = tListen.draw(canvas, s, x, base, white(if (word.second) 1f else 0.28f))
            }
        }
    }

    private fun drawQuery(canvas: Canvas) {
        var q = queryText
        if (tQuery.width(q) > 832f) {
            var n = q.length
            while (n > 2 && tQuery.width(q.substring(0, n) + "…") > 832f) n--
            q = q.substring(0, n).trimEnd() + "…"
        }
        tQuery.draw(canvas, q, 64f, tQuery.baselineIn(80f), white(0.58f))
    }

    private fun drawWorking(canvas: Canvas) {
        tWorking.draw(canvas, "Working on it…", 64f, tWorking.baselineIn(130f), white(0.92f))
        state.steps.forEachIndexed { i, st ->
            val r = stepRects.getOrNull(i) ?: return@forEachIndexed
            val a = stepFade[st.id]?.value ?: 1f
            val save = canvas.saveLayerAlpha(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, (a * 255).toInt())
            fill.color = white(0.1f)
            canvas.drawRoundRect(r, 20f, 20f, fill)
            var x = r.left + 12f
            when (st.status) {
                StepStatus.DONE -> { drawCheck(canvas, x, r.centerY() - 8f, 16f, c(0xFF30D158.toInt()), 3f); x += 16f + 8f }
                StepStatus.FAILED -> { drawCross(canvas, x, r.centerY() - 8f, 16f, c(0xFFFF9F0A.toInt())); x += 16f + 8f }
                StepStatus.BUSY -> { drawSpinner(canvas, x + 1f, r.centerY() - 7f); x += 14f + 8f }
                StepStatus.PENDING -> {}
            }
            tStep.draw(canvas, st.label, x, tStep.baselineIn(r.top, 40f), if (st.status == StepStatus.PENDING) white(0.6f) else white(1f))
            canvas.restoreToCount(save)
        }
    }

    private fun drawAnswer(canvas: Canvas) {
        val lh = 29f * 1.26f
        var left = shownWords
        for ((i, line) in answerLines.withIndex()) {
            if (left <= 0) break
            val words = line.words.take(left).joinToString(" ") { it.first }
            left -= line.words.size
            tAnswer.draw(canvas, words, 64f, tAnswer.baselineIn(112f + i * lh, lh), white(1f))
        }
        if (!showCards || cardsAlpha <= 0f) return
        val save = canvas.saveLayerAlpha(0f, cardsTop - 40f, 960f, 480f, (cardsAlpha * 255).toInt())
        canvas.translate(0f, cardsTop + cardsShift)
        drawCards(canvas)
        canvas.restoreToCount(save)
    }

    // ---------------- cards ----------------

    private fun drawCards(canvas: Canvas) {
        val cards = state.cards
        val media = cards.filterIsInstance<ResultCard.Media>().firstOrNull()
        val weather = cards.filterIsInstance<ResultCard.Weather>().firstOrNull()
        when {
            weather != null -> {
                drawWeather(canvas, weather, 64f, 270f)
                val rest = cards.firstOrNull { it !== weather }
                when (rest) {
                    is ResultCard.AlarmSuggestion -> drawSuggestion(canvas, rest, 64f + 270f + 16f, 896f)
                    is ResultCard.Action -> drawActionWide(canvas, rest, 64f + 270f + 16f, 896f)
                    else -> {}
                }
            }
            media != null -> drawMedia(canvas, media)
            else -> {
                val tiles = cards.filterIsInstance<ResultCard.Action>().take(3)
                val w = (832f - 2 * 14f) / 3f
                tiles.forEachIndexed { i, a -> drawTile(canvas, a, 64f + i * (w + 14f), w) }
            }
        }
    }

    private fun drawWeather(canvas: Canvas, w: ResultCard.Weather, left: Float, width: Float) {
        rect.set(left, 0f, left + width, 136f)
        fill.color = white(0.08f)
        canvas.drawRoundRect(rect, 26f, 26f, fill)
        val l = left + 20f
        t14s.draw(canvas, w.label, l, t14s.baselineIn(18f, 28f), white(0.7f))
        drawIcon(canvas, if (w.rain) ICON_RAIN else ICON_CLOUD, left + width - 20f - 28f, 18f, 28f, c(0xFF64D2FF.toInt()), strokeW = 2f)
        // Bottom block: headline 26 + 4 + detail 14, bottom-aligned in the 18 px padding.
        val detailTop = 136f - 18f - t14m.normalLineHeight
        val headTop = detailTop - 4f - t26b.normalLineHeight
        t26b.draw(canvas, ellipsize(w.headline, t26b, width - 40f), l, t26b.baselineIn(headTop), white(1f))
        t14m.draw(canvas, ellipsize(w.detail, t14m, width - 40f), l, t14m.baselineIn(detailTop), white(0.7f))
    }

    private fun drawSuggestion(canvas: Canvas, s: ResultCard.AlarmSuggestion, left: Float, right: Float) {
        // The canvas is translated to the cards row; tell the glass where that is so its backdrop lines up.
        glass.draw(canvas, left, 0f, right, 136f, 26f, 0f, cardsTop + cardsShift)
        val l = left + 20f
        if (!suggestionAccepted) {
            t20b.draw(canvas, s.title, l, t20b.baselineIn(18f), white(1f))
            t14m.draw(canvas, ellipsize(s.detail, t14m, right - left - 40f), l, t14m.baselineIn(18f + t20b.normalLineHeight + 3f), white(0.7f))
            val bTop = 136f - 18f - 44f
            val w1 = 20f + t15b.width(s.accept) + 20f
            rect.set(l, bTop, l + w1, bTop + 44f)
            fill.color = c(0xFF0A84FF.toInt())
            canvas.drawRoundRect(rect, 22f, 22f, fill)
            t15b.draw(canvas, s.accept, l + 20f, t15b.baselineIn(bTop, 44f), white(1f))
            hit["accept"] = RectF(rect).also { it.offset(0f, cardsTop + cardsShift) }
            val x2 = l + w1 + 10f
            val w2 = 18f + t15s.width(s.decline) + 18f
            rect.set(x2, bTop, x2 + w2, bTop + 44f)
            fill.color = white(0.14f)
            canvas.drawRoundRect(rect, 22f, 22f, fill)
            t15s.draw(canvas, s.decline, x2 + 18f, t15s.baselineIn(bTop, 44f), white(1f))
            hit["decline"] = RectF(rect).also { it.offset(0f, cardsTop + cardsShift) }
        } else {
            fill.color = c(0xFF30D158.toInt())
            canvas.drawCircle(l + 26f, 68f, 26f, fill)
            drawCheck(canvas, l + 13f, 55f, 26f, c(0xFF04210E.toInt()), 3f)
            val tx = l + 52f + 14f
            val blockH = t22b.normalLineHeight + 3f + t14m.normalLineHeight
            val top = 68f - blockH / 2f
            t22b.draw(canvas, s.doneTitle, tx, t22b.baselineIn(top), white(1f))
            t14m.draw(canvas, s.doneDetail, tx, t14m.baselineIn(top + t22b.normalLineHeight + 3f), white(0.7f))
        }
    }

    /** A real turn's action next to the weather card: the tile, stretched. */
    private fun drawActionWide(canvas: Canvas, a: ResultCard.Action, left: Float, right: Float) {
        canvas.save()
        canvas.translate(0f, 8f) // 120-high tile centred against the 136-high weather card
        drawTile(canvas, a, left, right - left)
        canvas.restore()
    }

    private fun drawTile(canvas: Canvas, a: ResultCard.Action, left: Float, width: Float) {
        rect.set(left, 0f, left + width, 120f)
        fill.color = white(0.08f)
        canvas.drawRoundRect(rect, 24f, 24f, fill)
        val (tint, ink, icon) = iconStyle(a.icon)
        val l = left + 18f
        rect.set(l, 16f, l + 38f, 54f)
        fill.color = c(tint)
        canvas.drawRoundRect(rect, 12f, 12f, fill)
        drawIcon(canvas, icon, l + 9f, 25f, 20f, c(ink), strokeW = 0f)
        if (a.ok) drawCheck(canvas, left + width - 18f - 20f, 25f, 20f, c(0xFF30D158.toInt()), 3f)
        else drawCross(canvas, left + width - 18f - 20f, 25f, 20f, c(0xFFFF9F0A.toInt()))
        val stateTop = 120f - 16f - t14m.normalLineHeight
        val nameTop = stateTop - 2f - t17b.normalLineHeight
        t17b.draw(canvas, ellipsize(a.name, t17b, width - 36f), l, t17b.baselineIn(nameTop), white(1f))
        t14m.draw(canvas, ellipsize(a.state, t14m, width - 36f), l, t14m.baselineIn(stateTop), white(0.7f))
    }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPath = Path()

    private fun drawMedia(canvas: Canvas, m: ResultCard.Media) {
        rect.set(64f, 0f, 624f, 124f)
        fill.color = white(0.08f)
        canvas.drawRoundRect(rect, 26f, 26f, fill)
        // Art: night sea (the design's CSS gradients), 96 px with radius 14.
        val ax = 64f + 14f; val ay = 14f
        canvas.save()
        artPath.reset(); artPath.addRoundRect(RectF(ax, ay, ax + 96f, ay + 96f), 14f, 14f, Path.Direction.CW)
        canvas.clipPath(artPath)
        artPaint.shader = LinearGradient(ax + 48f - 20f, ay - 6f, ax + 48f + 20f, ay + 102f,
            intArrayOf(c(0xFF0D2A66.toInt()), c(0xFF050D26.toInt()), c(0xFF02050F.toInt())), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(ax, ay, ax + 96f, ay + 96f, artPaint)
        artPaint.shader = RadialGradient(ax + 72f, ay + 24f, 22f, intArrayOf(withAlpha(c(0xFFA0E1FF.toInt()), 0.45f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(ax + 72f, ay + 24f, 22f, artPaint)
        artPaint.shader = null
        artPaint.color = c(0xFFEAF7FF.toInt())
        canvas.drawCircle(ax + 72f, ay + 24f, 10f, artPaint)
        // Waves: repeating-radial-gradient(ellipse 70% 40% at 50% 120%) over a 116×60 box from y=46:
        // 1.5 px rings every 7.5 px along the horizontal radius, ellipse ratio (0.7·116)/(0.4·60).
        canvas.clipRect(ax, ay + 46f, ax + 96f, ay + 96f)
        stroke.color = withAlpha(c(0xFF3FD0FF.toInt()), 0.6f); stroke.strokeWidth = 1.5f
        val cx = ax + 48f; val cy = ay + 46f + 1.2f * 60f
        val ratio = (0.7f * 116f) / (0.4f * 60f)
        for (k in 1..40) {
            val rx = k * 7.5f - 0.75f; val ry = rx / ratio
            if (cy - ry > ay + 96f) continue
            if (cy - ry < ay + 40f) break
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, stroke)
        }
        canvas.restore()
        val tx = ax + 96f + 18f
        val btnW = 18f + t15b.width("Open") + 18f
        val btnL = 624f - 14f - btnW
        val maxW = btnL - 18f - tx
        val blockH = t22bm.normalLineHeight + 2f + t15m.normalLineHeight
        val top = 62f - blockH / 2f
        t22bm.draw(canvas, ellipsize(m.title, t22bm, maxW), tx, t22bm.baselineIn(top), white(1f))
        t15m.draw(canvas, ellipsize(m.subtitle, t15m, maxW), tx, t15m.baselineIn(top + t22bm.normalLineHeight + 2f), white(0.7f))
        rect.set(btnL, 40f, btnL + btnW, 84f)
        fill.color = white(0.14f)
        canvas.drawRoundRect(rect, 22f, 22f, fill)
        t15b.draw(canvas, "Open", btnL + 18f, t15b.baselineIn(40f, 44f), white(1f))
        hit["open"] = RectF(rect).also { it.offset(0f, cardsTop + cardsShift) }
    }

    // ---------------- icons ----------------

    private val iconPath = Path()
    private val iconMatrix = Matrix()
    private val iconCache = HashMap<String, Path>()

    private fun svg(d: String): Path = iconCache.getOrPut(d) { Path().also { SvgPath.parse(d, AndroidPathSink(it)) } }

    /** Draws a 24-unit SVG icon at (x, y) with [size]; [strokeW] > 0 = stroked (in 24-unit px), else filled. */
    private fun drawIcon(canvas: Canvas, d: String, x: Float, y: Float, size: Float, color: Int, strokeW: Float) {
        val s = size / 24f
        iconMatrix.setScale(s, s); iconMatrix.postTranslate(x, y)
        // An icon may be several subpaths separated by '|' (stroked parts).
        iconPath.reset()
        for (part in d.split('|')) iconPath.addPath(svg(part))
        iconPath.transform(iconMatrix)
        if (strokeW > 0f) {
            stroke.color = color; stroke.strokeWidth = strokeW * s
            canvas.drawPath(iconPath, stroke)
        } else {
            fill.color = color
            canvas.drawPath(iconPath, fill)
        }
    }

    private fun drawCheck(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, w: Float) = drawIcon(canvas, ICON_CHECK, x, y, size, color, w)

    private fun drawCross(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) = drawIcon(canvas, ICON_CROSS, x, y, size, color, 3f)

    private fun drawSpinner(canvas: Canvas, x: Float, y: Float) {
        // 14 px, 2 px border at .3 with a white top quarter, rotated with the droplet's spin.
        stroke.strokeWidth = 2f
        stroke.strokeCap = Paint.Cap.BUTT
        rect.set(x + 1f, y + 1f, x + 13f, y + 13f)
        stroke.color = white(0.3f)
        canvas.drawOval(rect, stroke)
        stroke.color = white(1f)
        canvas.drawArc(rect, -135f + spin, 90f, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun iconStyle(i: Icon): Triple<Int, Int, String> = when (i) {
        Icon.LAMP -> Triple(0xFFFFB340.toInt(), 0xFF3A1A00.toInt(), ICON_LAMP)
        Icon.TV -> Triple(0xFF0A84FF.toInt(), Color.WHITE, ICON_TV)
        Icon.PAUSE -> Triple(withAlpha(Color.WHITE, 0.18f), Color.WHITE, ICON_PAUSE)
        Icon.PLAY -> Triple(withAlpha(Color.WHITE, 0.18f), Color.WHITE, ICON_PLAY)
        Icon.TIMER -> Triple(0xFFFF9F0A.toInt(), 0xFF3A1A00.toInt(), ICON_TIMER)
        Icon.ALARM -> Triple(0xFFFF9F0A.toInt(), 0xFF3A1A00.toInt(), ICON_ALARM)
        Icon.POWER -> Triple(0xFF30D158.toInt(), 0xFF04210E.toInt(), ICON_POWER)
        Icon.CALENDAR -> Triple(0xFFFF453A.toInt(), Color.WHITE, ICON_CALENDAR)
        Icon.BRIGHTNESS -> Triple(0xFFFFD60A.toInt(), 0xFF3A2A00.toInt(), ICON_SUN)
    }

    private fun ellipsize(s: String, t: SpecText, maxW: Float): String {
        if (t.width(s) <= maxW) return s
        var n = s.length
        while (n > 1 && t.width(s.substring(0, n) + "…") > maxW) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    // ---------------- touch ----------------

    private var downOn: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOn = hit.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
                return downOn != null
            }
            MotionEvent.ACTION_UP -> {
                val k = downOn ?: return false
                downOn = null
                if (hit[k]?.contains(e.x, e.y) != true) return true
                when {
                    k.startsWith("chip") -> actions.chip(k.removePrefix("chip").toInt())
                    k == "accept" -> actions.suggestion(true)
                    k == "decline" -> actions.suggestion(false)
                    k == "open" -> actions.openMedia()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> downOn = null
        }
        return downOn != null
    }

    companion object {
        const val REVEAL_WORD_MS = 70L
        private val CARD_SPRING = PathInterpolator(0.3f, 1.3f, 0.5f, 1f)

        const val ICON_CHECK = "m5 12.5 4.5 4.5L19 7.5"
        const val ICON_CROSS = "M7 7l10 10M17 7 7 17"
        const val ICON_RAIN = "M7 15h10a4 4 0 0 0 .4-8 6 6 0 0 0-11.5 1.6A3.3 3.3 0 0 0 7 15z|M8 18.5 7 21M12 18.5l-1 2.5M16 18.5l-1 2.5"
        const val ICON_CLOUD = "M7 17h10a4 4 0 0 0 .4-8 6 6 0 0 0-11.5 1.6A3.3 3.3 0 0 0 7 17z"
        const val ICON_LAMP = "M12 2a7 7 0 0 0-4 12.74V17a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2v-2.26A7 7 0 0 0 12 2zM10 20h4v1a2 2 0 0 1-4 0z"
        const val ICON_TV = "M3 5.5A1.5 1.5 0 0 1 4.5 4h15A1.5 1.5 0 0 1 21 5.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 15.5zM8 19.5a.75.75 0 0 1 .75-.75h6.5a.75.75 0 0 1 0 1.5h-6.5A.75.75 0 0 1 8 19.5z"
        const val ICON_PAUSE = "M7 4.5h3.2v15H7zM13.8 4.5H17v15h-3.2z"
        const val ICON_PLAY = "M8 4.8v14.4a.8.8 0 0 0 1.2.7l11.3-7.2a.8.8 0 0 0 0-1.4L9.2 4.1A.8.8 0 0 0 8 4.8z"
        const val ICON_TIMER = "M10 2h4v2h-4zM12 5a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm1 3v5.6l3.2 1.9-1 1.7L11 14.7V8z"
        const val ICON_ALARM = "M12 5a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm1 3v4.6l3 1.8-1 1.7-4-2.4V8zM3.3 6.6 7.1 3.4l1.3 1.5-3.8 3.2zM16.9 3.4l3.8 3.2-1.3 1.5-3.8-3.2z"
        const val ICON_POWER = "M11 2h2v10h-2zM6.3 5.2l1.4 1.4A6.5 6.5 0 1 0 16.3 6.6l1.4-1.4A8.5 8.5 0 1 1 6.3 5.2z"
        const val ICON_SUN = "M12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10zM11 1h2v3h-2zM11 20h2v3h-2zM1 11h3v2H1zM20 11h3v2h-3zM4.2 5.6l1.4-1.4 2.1 2.1-1.4 1.4zM16.3 17.7l1.4-1.4 2.1 2.1-1.4 1.4zM4.2 18.4l2.1-2.1 1.4 1.4-2.1 2.1zM16.3 6.3l2.1-2.1 1.4 1.4-2.1 2.1z"
        const val ICON_CALENDAR = "M7 2h2v2h6V2h2v2h2a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2zM5 9v10h14V9z"
    }
}
