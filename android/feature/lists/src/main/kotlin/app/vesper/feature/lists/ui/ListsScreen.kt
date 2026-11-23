package app.vesper.feature.lists.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.vesper.core.design.Backdrop
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.ListItem
import app.vesper.core.model.ListNames
import app.vesper.core.model.Profile
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderStore
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Lists screen (pager order 35, after Timers; `--es screen lists`): two glass platters over
 * a cool blue gradient (pre-blurred once), Reminders on the left and the shopping list on the right.
 *  - Tap a row to check it off (again to un-check). A due or missed reminder has a Snooze pill.
 *  - Long-press a row for a red Delete pill (the pager owns horizontal swipes).
 *  - Drag up/down inside a platter to scroll it. "Clear N checked" under the list.
 *  - The list title switches lists when there's more than one (shopping, to-do, …).
 * Night: black, red outlines and text. Draws only on changes and at the minute (relative times).
 *
 * Args: `list` <name> opens that list. Debug builds: `seed 1` adds sample reminders and items;
 * `reminder due|missed` adds a reminder that is due now / was missed and goes back to StandBy,
 * so the island and banner show.
 */
@SuppressLint("ViewConstructor")
class ListsScreen(context: Context, private val services: VesperServices) : View(context), VesperScreen {
    private val tTitle = SpecText(28f, 800, -0.025f)
    private val tSummary = SpecText(14f, 600)
    private val tCard = SpecText(21f, 700, -0.01f)
    private val tCount = SpecText(15f, 600)
    private val tRow = SpecText(19f, 600, -0.005f)
    private val tLabel = SpecText(15f, 600)
    private val tPill = SpecText(14f, 700)
    private val tEmpty = SpecText(18f, 700)
    private val tHint = SpecText(15f, 600).apply { paint.fontFeatureSettings = "" }
    private val tInitial = SpecText(11f, 800)

    private val glassL = GlassPainter()
    private val glassR = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val bgMatrix = Matrix().apply { setScale(2f, 2f) }

    private var bg: Bitmap? = null
    private var backdrop: Backdrop? = null

    private var night = false
    private var shown = false
    private var reminders: List<Reminder> = emptyList()
    private var lists: Map<String, List<ListItem>> = emptyMap()
    private var listName = ListNames.SHOPPING
    private var leftRows: List<Row> = emptyList()
    private var rightRows: List<Row> = emptyList()
    private var summary = ""

    private val scroll = floatArrayOf(0f, 0f)
    /** Long-pressed row showing its Delete pill: card (0 left, 1 right) and id. */
    private var armed: Pair<Int, String>? = null
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val fitCache = HashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = Runnable { rebuild(); scheduleMinute() }

    private val icBell = icon("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0")
    private val icCart = icon("M2.5 4h2.2l2.4 10.3a1 1 0 0 0 1 .7h8.7a1 1 0 0 0 1-.7L20 7.5H6M9 20.2h.01M17 20.2h.01")
    private val icList = icon("M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01")
    private val icCheck = icon("m5 12.5 4.5 4.5L19 7.5")
    private val icChevron = icon("m7 10 5 5 5-5")

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(services.reminders.reminders) { reminders = it; rebuild() }
        observeWhileAttached(services.lists.lists) { lists = it; rebuild() }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; glassL.night = night; glassR.night = night; invalidate() } }
    }

    // ---------------- lifecycle ----------------

    override fun onScreenShown(args: Bundle?) {
        shown = true
        args?.getString("list")?.let { listName = ListNames.normalize(it) }
        if (services.config.debug) debugArgs(args)
        ensureBackground()
        rebuild()
        scheduleMinute()
    }

    override fun onScreenHidden() {
        shown = false
        armed = null
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(longPress)
    }

    private fun scheduleMinute() {
        handler.removeCallbacks(minuteTick)
        if (!shown) return
        val now = System.currentTimeMillis()
        handler.postDelayed(minuteTick, 60_000L - now % 60_000L + 50L)
    }

    private fun ensureBackground() {
        if (bg != null) return
        val b = renderBackground()
        bg = b
        // backdrop-filter blur(20px) saturate(1.8) brightness(1.15) at full size = 10 px on this half-size image
        val bd = Backdrop(PreBlur.blur(b, 10f, 2), 0f, 0f, 960f, 480f)
        backdrop = bd
        glassL.backdrop = bd; glassR.backdrop = bd
    }

    /** A deep blue gradient with soft blue, violet and green light, rendered once at 480×240 and drawn ×2. */
    private fun renderBackground(): Bitmap {
        val w = 480f; val h = 240f
        val b = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w, h, 0xFF0B1A38.toInt(), 0xFF120A26.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        fun blob(cx: Float, cy: Float, r: Float, color: Int, a: Float) {
            p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, a), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
        blob(0.18f * w, 0.25f * h, 0.42f * w, 0xFF0A84FF.toInt(), 0.55f)
        blob(0.66f * w, 0.05f * h, 0.34f * w, 0xFFBF5AF2.toInt(), 0.32f)
        blob(0.86f * w, 0.92f * h, 0.40f * w, 0xFF30D158.toInt(), 0.26f)
        blob(0.40f * w, 1.05f * h, 0.36f * w, 0xFF64D2FF.toInt(), 0.22f)
        return b
    }

    // ---------------- data ----------------

    private fun listNames(): List<String> = (listOf(ListNames.SHOPPING) + lists.keys.sorted()).distinct()

    private fun rebuild() {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val profiles = services.profiles.profiles.value
        leftRows = ListsModel.reminderRows(reminders, now, zone, DateFormat.is24HourFormat(context)) { pid ->
            pid?.takeIf { it != Profile.SHARED_ID }?.let { id -> profiles.firstOrNull { it.id == id } }?.let { it.name.take(1).uppercase() to it.color }
        }
        val items = lists[listName].orEmpty()
        rightRows = ListsModel.itemRows(items)
        summary = ListsModel.summary(reminders, items, ListNames.title(listName))
        if (armed != null && (if (armed!!.first == 0) leftRows else rightRows).none { it.id == armed!!.second }) armed = null
        clampScroll()
        fitCache.clear()
        invalidate()
    }

    // ---------------- geometry ----------------

    private fun cardX(card: Int) = if (card == 0) LEFT_X else RIGHT_X
    private fun rowsOf(card: Int) = if (card == 0) leftRows else rightRows
    private fun footer(card: Int) = card == 1 && rightRows.any { it.checked }
    private fun viewTop() = CARD_T + HEADER_H
    private fun viewBottom(card: Int) = CARD_B - if (footer(card)) FOOTER_H else 10f
    private fun maxScroll(card: Int) = max(0f, rowsOf(card).size * ROW_H - (viewBottom(card) - viewTop()))
    private fun clampScroll() { for (i in 0..1) scroll[i] = scroll[i].coerceIn(0f, maxScroll(i)) }

    // ---------------- drawing ----------------

    override fun onDraw(c: Canvas) {
        hits.clear()
        if (night) c.drawColor(Color.BLACK) else bg?.let { c.drawBitmap(it, bgMatrix, bgPaint) }
        tTitle.draw(c, "Lists", 32f, tTitle.baselineIn(22f, 28f * 1.1f), ink(Color.WHITE))
        tSummary.draw(c, summary, 32f, tSummary.baselineIn(22f + 28f * 1.1f + 2f), ink(withAlpha(Color.WHITE, 0.78f)))
        drawCard(c, 0)
        drawCard(c, 1)
        val hint = if (reminders.none { it.open } && rightRows.isEmpty()) "Try “Hey Nova, remind me to take out the trash at 7”"
            else "Tap to check off · hold to delete"
        tHint.draw(c, hint, 480f - tHint.width(hint) / 2, 480f - 18f - tHint.descent, ink(withAlpha(Color.WHITE, 0.66f)))
    }

    private fun drawCard(c: Canvas, card: Int) {
        val x = cardX(card)
        val r = x + CARD_W
        (if (card == 0) glassL else glassR).draw(c, x, CARD_T, r, CARD_B, 34f)

        // header: disc + icon, title, count
        val cy = CARD_T + 36f
        paint.style = Paint.Style.FILL
        paint.color = ink(if (card == 0) VesperColors.BLUE else VesperColors.GREEN)
        c.drawCircle(x + 38f, cy, 18f, paint)
        val glyph = if (card == 0) icBell else if (listName == ListNames.SHOPPING) icCart else icList
        drawIcon(c, glyph, x + 38f, cy, 20f, if (night) Color.BLACK else if (card == 0) Color.WHITE else 0xFF04210E.toInt(), 2.2f)
        val title = if (card == 0) "Reminders" else ListNames.title(listName)
        val tx = x + 68f
        val tEnd = tCard.draw(c, title, tx, tCard.baselineCentered(cy), ink(Color.WHITE))
        if (card == 1 && listNames().size > 1) {
            drawIcon(c, icChevron, tEnd + 14f, cy + 1f, 18f, ink(withAlpha(Color.WHITE, 0.7f)), 2.4f)
            hit(tx - 50f, CARD_T, tEnd + 30f, CARD_T + HEADER_H) { cycleList() }
        }
        val count = countText(card)
        tCount.draw(c, count, r - 22f - tCount.width(count), tCount.baselineCentered(cy), ink(withAlpha(Color.WHITE, 0.62f)))
        paint.color = ink(withAlpha(Color.WHITE, 0.1f))
        c.drawRect(x + 20f, CARD_T + HEADER_H - 2f, r - 20f, CARD_T + HEADER_H - 1.5f, paint)

        val rows = rowsOf(card)
        val top = viewTop()
        val bottom = viewBottom(card)
        if (rows.isEmpty()) {
            val (a, b) = if (card == 0) "No reminders" to "Say “Remind me to call Mom at 6”"
                else "Nothing on the list" to "Say “Add milk and eggs to my ${title.lowercase()} list”"
            val mid = (top + bottom) / 2f
            tEmpty.draw(c, a, x + CARD_W / 2 - tEmpty.width(a) / 2, mid - 6f, ink(withAlpha(Color.WHITE, 0.82f)))
            val bb = fit(tLabel, b, CARD_W - 40f)
            tLabel.draw(c, bb, x + CARD_W / 2 - tLabel.width(bb) / 2, mid + 22f, ink(withAlpha(Color.WHITE, 0.55f)))
        } else {
            c.save()
            c.clipRect(x, top, r, bottom)
            val first = (scroll[card] / ROW_H).toInt().coerceAtLeast(0)
            var i = first
            while (i < rows.size) {
                val y = top + i * ROW_H - scroll[card]
                if (y > bottom) break
                drawRow(c, card, rows[i], x, y, top, bottom, last = i == rows.size - 1)
                i++
            }
            c.restore()
        }
        if (footer(card)) {
            val n = rows.count { it.checked }
            val s = "Clear $n checked"
            val w = tPill.width(s) + 32f
            val by = CARD_B - FOOTER_H / 2f - 4f
            rect.set(r - 20f - w, by - 18f, r - 20f, by + 18f)
            paint.color = ink(withAlpha(Color.WHITE, 0.14f))
            c.drawRoundRect(rect, 18f, 18f, paint)
            tPill.draw(c, s, rect.left + 16f, tPill.baselineCentered(by), ink(Color.WHITE))
            hit(rect.left, rect.top - 6f, rect.right, rect.bottom + 6f) { services.lists.clear(listName, onlyChecked = true) }
        }
    }

    private fun drawRow(c: Canvas, card: Int, row: Row, x: Float, y: Float, top: Float, bottom: Float, last: Boolean) {
        val r = x + CARD_W
        val cy = y + ROW_H / 2f
        val isArmed = armed?.let { it.first == card && it.second == row.id } == true
        val tint = when {
            isArmed -> withAlpha(VesperColors.RED, 0.16f)
            row.tone == Row.Tone.DUE -> withAlpha(VesperColors.BLUE, 0.24f)
            else -> 0
        }
        if (tint != 0) {
            paint.color = ink(tint)
            rect.set(x + 10f, y + 3f, r - 10f, y + ROW_H - 3f)
            c.drawRoundRect(rect, 18f, 18f, paint)
        }

        // checkbox
        val bx = x + 38f
        if (row.checked) {
            paint.color = ink(VesperColors.GREEN)
            c.drawCircle(bx, cy, 13f, paint)
            drawIcon(c, icCheck, bx, cy, 18f, if (night) Color.BLACK else 0xFF04210E.toInt(), 3f)
        } else {
            stroke.strokeWidth = 2f
            stroke.color = ink(if (row.tone == Row.Tone.MISSED) withAlpha(VesperColors.RED, 0.95f) else withAlpha(Color.WHITE, 0.55f))
            c.drawCircle(bx, cy, 12f, stroke)
        }

        // right side: Delete pill (armed), Snooze pill (due / missed reminders), label
        var right = r - 22f
        if (isArmed) {
            right = pill(c, "Delete", right, cy, VesperColors.RED, Color.WHITE) { delete(card, row.id) }
        } else if (card == 0 && !row.checked && row.tone != Row.Tone.NORMAL) {
            right = pill(c, "Snooze", right, cy, withAlpha(Color.WHITE, 0.16f), Color.WHITE) { services.reminders.snooze(row.id, ReminderStore.SNOOZE_MINUTES) }
        }
        if (!isArmed && row.label.isNotEmpty()) {
            val col = when (row.tone) {
                Row.Tone.DUE -> VesperColors.CYAN
                Row.Tone.MISSED -> VesperColors.RED
                Row.Tone.NORMAL -> withAlpha(Color.WHITE, if (row.checked) 0.4f else 0.66f)
            }
            val label = fit(tLabel, row.label, 170f)
            val lw = tLabel.width(label)
            tLabel.draw(c, label, right - lw, tLabel.baselineCentered(cy), ink(col))
            right -= lw + 10f
        }
        row.person?.let { (letter, color) ->
            paint.color = ink(color or 0xFF000000.toInt())
            c.drawCircle(right - 10f, cy, 10f, paint)
            tInitial.draw(c, letter, right - 10f - tInitial.width(letter) / 2, tInitial.baselineCentered(cy), if (night) Color.BLACK else Color.WHITE)
            right -= 28f
        }

        // text
        val tx = x + 64f
        val text = fit(tRow, row.text, right - 12f - tx)
        val tw = tRow.width(text)
        tRow.draw(c, text, tx, tRow.baselineCentered(cy), ink(withAlpha(Color.WHITE, if (row.checked) 0.42f else 1f)))
        if (row.checked) {
            paint.color = ink(withAlpha(Color.WHITE, 0.42f))
            c.drawRect(tx, cy - 0.75f, tx + tw, cy + 0.75f, paint)
        }

        if (!last) {
            paint.color = ink(withAlpha(Color.WHITE, 0.08f))
            c.drawRect(tx, y + ROW_H - 0.5f, r - 20f, y + ROW_H, paint)
        }
        // the row toggles; pills registered above win (first hit)
        val ht = max(top, y); val hb = min(bottom, y + ROW_H)
        if (hb > ht) hit(x, ht, r, hb) { toggle(card, row) }
    }

    /** A 36 px pill ending at [right]; returns the x left of it. */
    private inline fun pill(c: Canvas, s: String, right: Float, cy: Float, bg: Int, fg: Int, crossinline onTap: () -> Unit): Float {
        val w = tPill.width(s) + 30f
        rect.set(right - w, cy - 18f, right, cy + 18f)
        paint.color = ink(bg)
        c.drawRoundRect(rect, 18f, 18f, paint)
        val solid = Color.alpha(bg) == 255
        tPill.draw(c, s, right - w + 15f, tPill.baselineCentered(cy), if (!night) fg else if (solid) Color.BLACK else Night.COLOR)
        hit(rect.left - 6f, rect.top - 6f, rect.right + 6f, rect.bottom + 6f) { onTap() }
        return right - w - 12f
    }

    private fun countText(card: Int): String = if (card == 0) {
        val next = reminders.filter { it.state == Reminder.State.PENDING }.minByOrNull { it.dueAtMillis }
        val missed = reminders.count { it.state == Reminder.State.MISSED }
        when {
            missed > 0 -> "$missed missed"
            next != null -> "${reminders.count { it.open }} open"
            else -> ""
        }
    } else {
        val left = rightRows.count { !it.checked }
        if (rightRows.isEmpty()) "" else if (left == 0) "Done" else "$left left"
    }

    private fun ink(color: Int) = Night.map(color, night)

    private fun hit(l: Float, t: Float, r: Float, b: Float, action: () -> Unit) { hits += RectF(l, t, r, b) to action }

    private fun fit(t: SpecText, s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        val key = "${t.size}|${maxW.toInt()}|$s"
        return fitCache.getOrPut(key) {
            if (t.width(s) <= maxW) s else {
                var n = s.length
                while (n > 1 && t.width(s.substring(0, n).trimEnd() + "…") > maxW) n--
                s.substring(0, n).trimEnd() + "…"
            }
        }
    }

    private fun SpecText.baselineCentered(cy: Float) = cy - normalLineHeight / 2f + ascent

    // ---------------- icons ----------------

    private class Glyph(val path: Path)

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

    /** A 24×24 stroked icon centred at ([cx], [cy]), [size] px. */
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

    // ---------------- actions ----------------

    private fun toggle(card: Int, row: Row) {
        if (armed != null) { armed = null; invalidate(); return }
        if (card == 0) {
            if (row.checked) services.reminders.reopen(row.id) else services.reminders.complete(row.id)
        } else services.lists.setChecked(listName, row.id, !row.checked)
    }

    private fun delete(card: Int, id: String) {
        armed = null
        if (card == 0) services.reminders.delete(id) else services.lists.delete(listName, id)
    }

    private fun cycleList() {
        val names = listNames()
        listName = names[(names.indexOf(listName) + 1) % names.size]
        scroll[1] = 0f
        rebuild()
    }

    // ---------------- touch ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragCard = -1
    private var scrolling = false
    private var longPressed = false
    private var startScroll = 0f
    private val longPress = Runnable {
        val row = rowAt(downX, downY) ?: return@Runnable
        longPressed = true
        armed = row
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun cardAt(x: Float, y: Float): Int = when {
        y < CARD_T || y > CARD_B -> -1
        x in LEFT_X..(LEFT_X + CARD_W) -> 0
        x in RIGHT_X..(RIGHT_X + CARD_W) -> 1
        else -> -1
    }

    private fun rowAt(x: Float, y: Float): Pair<Int, String>? {
        val card = cardAt(x, y)
        if (card < 0 || y < viewTop() || y > viewBottom(card)) return null
        val i = ((y - viewTop() + scroll[card]) / ROW_H).toInt()
        return rowsOf(card).getOrNull(i)?.let { card to it.id }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragCard = cardAt(e.x, e.y)
                scrolling = false; longPressed = false
                startScroll = if (dragCard >= 0) scroll[dragCard] else 0f
                handler.removeCallbacks(longPress)
                if (rowAt(e.x, e.y) != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (!scrolling && abs(dy) > slop && abs(dy) > abs(e.x - downX) && dragCard >= 0 && maxScroll(dragCard) > 0f) {
                    scrolling = true
                    handler.removeCallbacks(longPress)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (abs(e.x - downX) > slop || abs(dy) > slop) handler.removeCallbacks(longPress)
                if (scrolling) {
                    scroll[dragCard] = (startScroll - dy).coerceIn(0f, maxScroll(dragCard))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                if (!scrolling && !longPressed && !moved) {
                    val h = hits.firstOrNull { it.first.contains(e.x, e.y) }
                    if (h != null) h.second()
                    else if (armed != null) { armed = null; invalidate() }
                }
                scrolling = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); scrolling = false; return true }
        }
        return super.onTouchEvent(e)
    }

    // ---------------- debug ----------------

    private fun debugArgs(args: Bundle?) {
        args ?: return
        val now = System.currentTimeMillis()
        if (args.getString("seed") != null) seed(now)
        when (args.getString("reminder")) {
            "due" -> {
                services.reminders.add("Take out the trash", now + 1500L, null)
                handler.postDelayed({ services.navigator.home() }, 300)
            }
            "missed" -> {
                services.reminders.add("Call the dentist", now - 20 * 60_000L, null)
                handler.postDelayed({ services.navigator.home() }, 300)
            }
        }
    }

    /** Sample reminders and a shopping list (only what isn't there yet). */
    private fun seed(now: Long) {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun at(daysAhead: Long, h: Int, m: Int = 0) = today.plusDays(daysAhead).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val have = services.reminders.reminders.value.map { it.text }.toSet()
        val trash = at(0, 19).let { if (it > now) it else now + 2 * 3_600_000L }
        if ("Take out the trash" !in have) services.reminders.add("Take out the trash", trash, null)
        if ("Call the dentist" !in have) services.reminders.add("Call the dentist", at(1, 9), null)
        if ("Water the plants" !in have) services.reminders.add("Water the plants", at(2, 10), null)
        val r = services.lists.add(ListNames.SHOPPING, listOf("Milk", "2 lemons", "Eggs", "Sourdough bread", "Basil", "Coffee beans"))
        (r.added + r.duplicates).firstOrNull { it.text == "Coffee beans" }?.let { services.lists.setChecked(ListNames.SHOPPING, it.id, true) }
    }

    companion object {
        private const val CARD_T = 88f
        private const val CARD_B = 432f
        private const val LEFT_X = 28f
        private const val RIGHT_X = 492f
        private const val CARD_W = 440f
        private const val HEADER_H = 68f
        private const val ROW_H = 56f
        private const val FOOTER_H = 52f
    }
}
