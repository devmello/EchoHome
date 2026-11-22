package com.echobedside.feature.system.banner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.Banner
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.system.SystemUi
import com.echobedside.feature.system.ui.EASE
import com.echobedside.feature.system.ui.GlassSpec
import com.echobedside.feature.system.ui.Icons
import com.echobedside.feature.system.ui.Snapshot
import com.echobedside.feature.system.ui.SysGlassPainter
import com.echobedside.feature.system.ui.Tween
import com.echobedside.feature.system.ui.baselineCentered
import com.echobedside.feature.system.ui.bezier
import com.echobedside.feature.system.ui.drawCentered
import com.echobedside.feature.system.ui.drawRight
import com.echobedside.feature.system.ui.ellipsize

/**
 * Notification banners (Banners.dc.html), overlay z 400.
 * Compact: a 620×88 glass banner 14 px from the top (spring 0.55 s cubic-bezier(0.3, 1.4, 0.5, 1)),
 * the previous one tucked behind (580×84, scale 0.94). Tap → the StandBy-size view over the
 * blurred screen (blur 26, 45 %), with the one tinted primary action and Dismiss. Swipe up hides.
 * Only the banner takes touches; the expanded view takes all of them.
 */
@SuppressLint("ViewConstructor")
internal class BannerOverlay(context: Context, private val ui: SystemUi) : View(context) {
    private val s = ui.services
    private val q = BannerQueue()
    private val handler = Handler(Looper.getMainLooper())
    private var feed: List<Banner> = emptyList()
    private var dnd = false
    private var night = false

    private val glass = SysGlassPainter(GlassSpec.BANNER)
    private val backGlass = SysGlassPainter(GlassSpec.BANNER_BACK)
    private val btnGlass = SysGlassPainter(GlassSpec.BANNER_BUTTON)
    private val primaryGlass = SysGlassPainter(GlassSpec.BANNER_PRIMARY)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val r = RectF()

    // compact animation
    private var shown: Banner? = null
    private var back: Banner? = null
    private val y = Tween(-120f)
    private val scale = Tween(0.85f)
    private val opacity = Tween(0f)
    private val backY = Tween(-110f)
    private val backOpacity = Tween(0f)
    // expanded
    private val expandA = Tween(0f)
    private var expandedBg: Bitmap? = null
    private var expandedBanner: Banner? = null

    private val tTitle = SpecText(18f, 700, -0.01f)
    private val tNow = SpecText(14f, 600)
    private val tBody = SpecText(16f, 500)
    private val tApp = SpecText(20f, 700)
    private val tBigTitle = SpecText(52f, 800, -0.03f)
    private val tBigBody = SpecText(30f, 500, -0.015f)
    private val tButton = SpecText(22f, 700)

    private var downY = 0f
    private var downX = 0f
    private var downTarget = 0 // 0 none, 1 compact, 2 expanded
    private val primaryRect = RectF()
    private val dismissRect = RectF()

    private val tick = Runnable { if (q.tick(SystemClock.uptimeMillis())) apply(); schedule() }

    init {
        observeWhileAttached(s.notifications.banners) { feed = it; refresh() }
        observeWhileAttached(s.system.state) { st ->
            dnd = st.doNotDisturb
            if (st.nightActive != night) { night = st.nightActive; for (g in arrayOf(glass, backGlass, btnGlass, primaryGlass)) g.night = night; invalidate() }
        }
        observeWhileAttached(ui.debug) { b -> b?.getString("banner")?.toIntOrNull()?.let { postDemo(it) } }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.backHandlers += backHandler
    }

    override fun onDetachedFromWindow() { ui.backHandlers -= backHandler; handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    private val backHandler: () -> Boolean = { if (q.expanded) { close(dismiss = false); true } else false }

    private fun refresh() {
        if (q.update(feed, dnd, SystemClock.uptimeMillis())) apply()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        q.deadline()?.let { handler.postAtTime(tick, it) }
    }

    private fun apply() {
        val cur = q.current
        ui.bannerShowing.value = cur != null
        if (q.expanded) { invalidate(); return }
        if (cur != null) {
            if (shown == null || opacity.target == 0f) {
                takeBackdrop()
                y.snap(-120f); scale.snap(0.85f); opacity.snap(0f)
            }
            shown = cur
            y.to(0f, 550, DROP); scale.to(1f, 550, DROP); opacity.to(1f, 250, EASE)
            announceForAccessibility("${cur.appName}: ${cur.title}. ${cur.body}")
        } else {
            y.to(-120f, 550, DROP); scale.to(0.85f, 550, DROP); opacity.to(0f, 250, EASE)
        }
        val b = q.behind
        if (b != null) { back = b; backY.to(12f, 500, EASE); backOpacity.to(0.8f, 400, EASE) }
        else { backY.to(-110f, 500, EASE); backOpacity.to(0f, 400, EASE) }
        invalidate()
    }

    private fun takeBackdrop() {
        val old = glass.backdrop
        val bd = Snapshot.region(this, Rect(150, 0, 810, 130), 20f, 1.9f, 1.1f)
        glass.backdrop = bd; backGlass.backdrop = bd
        old?.bitmap?.recycle()
    }

    // --- expanded ---

    private fun expand() {
        val cur = q.current ?: return
        val act = context as? Activity
        q.expand()
        expandedBanner = cur
        val open = {
            expandA.snap(0f); expandA.to(1f, 400, EASE)
            opacity.to(0f, 200, EASE)
            ui.bannerShowing.value = true
            invalidate()
        }
        if (act == null) open() else Snapshot.window(act) { bmp ->
            if (bmp != null) {
                // the screen underneath: blur(26px), opacity .45 over black
                val bl = Snapshot.blurSmall(bmp, 26f, 1f, 1f)
                bmp.recycle()
                expandedBg?.recycle(); expandedBg = bl
                val bd = com.echobedside.core.design.Backdrop(Snapshot.blurSmall(bl, 18f, 1.8f, 1.15f), 0f, 0f, 960f, 480f)
                btnGlass.backdrop?.bitmap?.recycle()
                btnGlass.backdrop = bd; primaryGlass.backdrop = bd
            }
            open()
        }
    }

    private fun close(dismiss: Boolean) {
        val b = q.current
        if (dismiss && b != null) {
            s.notifications.dismiss(b.id)
            BannerListenerService.cancel(b)
        }
        q.close(SystemClock.uptimeMillis())
        expandA.to(0f, 300, EASE)
        // the next banner (if any) comes in compact
        shown = null
        apply()
        schedule()
    }

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        val now = Tween.now()
        var running = false
        for (t in arrayOf(y, scale, opacity, backY, backOpacity, expandA)) running = t.step(now) or running

        if (expandA.value > 0.004f) drawExpanded(canvas, expandedBanner ?: q.current, expandA.value)
        else if (!running && expandedBg != null) {
            expandedBg?.recycle(); expandedBg = null
            btnGlass.backdrop?.bitmap?.recycle(); btnGlass.backdrop = null; primaryGlass.backdrop = null
        }

        if (backOpacity.value > 0.004f) back?.let {
            canvas.save()
            canvas.translate(0f, backY.value)
            canvas.scale(0.94f, 0.94f, 480f, 22f + 42f)
            backGlass.alpha = backOpacity.value
            backGlass.draw(canvas, 190f, 22f, 770f, 106f, 30f, 0f, backY.value)
            canvas.restore()
        }
        val b = shown
        if (b != null && opacity.value > 0.004f) {
            canvas.save()
            canvas.translate(0f, y.value)
            canvas.scale(scale.value, scale.value, 480f, 14f + 44f)
            drawCompact(canvas, b, opacity.value)
            canvas.restore()
        } else if (b != null && !running && opacity.target == 0f) {
            shown = null
            glass.backdrop?.bitmap?.recycle(); glass.backdrop = null; backGlass.backdrop = null
        }
        if (!running && q.current == null && expandA.value <= 0f) ui.bannerShowing.value = false
        if (running) postInvalidateOnAnimation()
    }

    private fun ink(c: Int, a: Float = 1f) = withAlpha(if (night) Night.COLOR else c, Color.alpha(c) / 255f * a)

    /** left 50% − 310, top 14, 620×88, r32, padding 0 22 0 16, gap 14. */
    private fun drawCompact(c: Canvas, b: Banner, a: Float) {
        val l = 170f; val t = 14f
        glass.alpha = a
        glass.draw(c, l, t, l + 620f, t + 88f, 32f, 0f, y.value)
        // app tile 54 r15 + 28 px glyph
        val tl = l + 16f; val tcy = t + 44f
        r.set(tl, tcy - 27f, tl + 54f, tcy + 27f)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = ink(b.appColor or 0xFF000000.toInt(), a)
        c.drawRoundRect(r, 15f, 15f, paint)
        Icons.glyph(b.glyph).drawCentered(c, r.centerX(), tcy, 28f, if (night) withAlpha(Color.BLACK, a) else withAlpha(Color.WHITE, a), paint)
        val tx = tl + 54f + 14f
        val right = l + 620f - 22f
        val when_ = b.sourceDevice?.let { tNow.ellipsize(it, 170f) + " · " + ago(b.postedAtMillis) } ?: ago(b.postedAtMillis)
        val nowW = tNow.width(when_)
        val lh1 = tTitle.normalLineHeight; val lh2 = tBody.normalLineHeight
        val top = tcy - (lh1 + 2f + lh2) / 2f
        tTitle.draw(c, tTitle.ellipsize(b.title, right - tx - nowW - 10f), tx, tTitle.baselineIn(top, lh1), ink(Color.WHITE, a))
        tNow.drawRight(c, when_, right, tTitle.baselineIn(top, lh1), ink(withAlpha(Color.WHITE, 0.7f), a))
        tBody.draw(c, tBody.ellipsize(b.body, right - tx), tx, tBody.baselineIn(top + lh1 + 2f, lh2), ink(withAlpha(Color.WHITE, 0.86f), a))
    }

    private fun drawExpanded(c: Canvas, b: Banner?, a: Float) {
        // background: the screen, blurred, at 45 % over black
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = withAlpha(Color.BLACK, a); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        expandedBg?.let { bg ->
            m.setScale(960f / bg.width, 480f / bg.height)
            bmpPaint.alpha = (255 * 0.45f * a).toInt()
            c.drawBitmap(bg, m, bmpPaint)
        }
        b ?: return
        // header: 64 tile r18 + 34 glyph, "App · now" 20/700 .8 (gap 14), at left 64 top 56
        r.set(64f, 56f, 128f, 120f)
        paint.color = ink(b.appColor or 0xFF000000.toInt(), a)
        c.drawRoundRect(r, 18f, 18f, paint)
        Icons.glyph(b.glyph).drawCentered(c, 96f, 88f, 34f, if (night) withAlpha(Color.BLACK, a) else withAlpha(Color.WHITE, a), paint)
        val appLine = listOfNotNull(b.appName, b.sourceDevice, ago(b.postedAtMillis)).joinToString(" · ")
        tApp.draw(c, tApp.ellipsize(appLine, 960f - 64f - 142f), 64f + 64f + 14f, tApp.baselineCentered(88f), ink(withAlpha(Color.WHITE, 0.8f), a))
        // title 52/800 lh 1.05, margin-top 26; body 30/500 lh 1.25 .88, margin-top 10
        val maxW = 960f - 128f
        var top = 120f + 26f
        val titleLines = wrap(tBigTitle, b.title, maxW, 2)
        for (line in titleLines) { tBigTitle.draw(c, line, 64f, tBigTitle.baselineIn(top, 52f * 1.05f), ink(Color.WHITE, a)); top += 52f * 1.05f }
        top += 10f
        val bodyLines = wrap(tBigBody, b.body, maxW, if (titleLines.size > 1) 2 else 3)
        for (line in bodyLines) { tBigBody.draw(c, line, 64f, tBigBody.baselineIn(top, 30f * 1.25f), ink(withAlpha(Color.WHITE, 0.88f), a)); top += 30f * 1.25f }
        // buttons: bottom 34, left/right 64, gap 14, 72 tall r36 — the one tinted primary + Dismiss
        val bt = 480f - 34f - 72f
        val primary = b.primaryAction
        if (primary != null) {
            val w = (maxW - 14f) / 2f
            primaryRect.set(64f, bt, 64f + w, bt + 72f)
            dismissRect.set(64f + w + 14f, bt, 896f, bt + 72f)
            primaryGlass.alpha = a
            primaryGlass.draw(c, primaryRect.left, primaryRect.top, primaryRect.right, primaryRect.bottom, 36f)
            tButton.drawCentered(c, tButton.ellipsize(primary.label, w - 40f), primaryRect.centerX(), tButton.baselineCentered(primaryRect.centerY()), ink(Color.WHITE, a))
        } else {
            primaryRect.setEmpty()
            dismissRect.set(64f, bt, 896f, bt + 72f)
        }
        btnGlass.alpha = a
        btnGlass.draw(c, dismissRect.left, dismissRect.top, dismissRect.right, dismissRect.bottom, 36f)
        tButton.drawCentered(c, "Dismiss", dismissRect.centerX(), tButton.baselineCentered(dismissRect.centerY()), ink(Color.WHITE, a))
    }

    private fun wrap(t: SpecText, text: String, maxW: Float, maxLines: Int): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var line = ""
        var i = 0
        while (i < words.size) {
            val cand = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (t.width(cand) <= maxW || line.isEmpty()) { line = cand; i++ }
            else {
                if (lines.size == maxLines - 1) break
                lines += line; line = ""
            }
        }
        val rest = if (i < words.size) (line + " " + words.subList(i, words.size).joinToString(" ")).trim() else line
        if (rest.isNotEmpty()) lines += t.ellipsize(rest, maxW)
        return lines
    }

    private fun ago(at: Long): String {
        val d = (System.currentTimeMillis() - at) / 60_000
        return when { d < 1 -> "now"; d < 60 -> "${d}m ago"; else -> "${d / 60}h ago" }
    }

    // --- touch ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downTarget = when {
                    q.expanded -> 2
                    shown != null && opacity.target > 0f && e.x in 170f..790f && e.y in 0f..(14f + 88f + 6f) -> 1
                    else -> 0
                }
                return downTarget != 0
            }
            MotionEvent.ACTION_MOVE -> return downTarget != 0
            MotionEvent.ACTION_UP -> {
                val dy = e.y - downY
                when (downTarget) {
                    1 -> if (dy < -24f) { q.close(SystemClock.uptimeMillis()); apply(); schedule() } else if (kotlin.math.abs(dy) < 16f) expand()
                    2 -> when {
                        primaryRect.contains(e.x, e.y) -> {
                            val act: IslandAction? = (expandedBanner ?: q.current)?.primaryAction
                            try { act?.onInvoke?.invoke() } catch (ex: Exception) { android.util.Log.w("EchoSystem", "banner action", ex) }
                            close(dismiss = true)
                        }
                        dismissRect.contains(e.x, e.y) -> close(dismiss = true)
                        else -> close(dismiss = false)
                    }
                }
                downTarget = 0
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downTarget = 0; return true }
        }
        return false
    }

    // --- debug samples (Banners.dc.html) ---

    private fun postDemo(i: Int) {
        val list = listOf(
            Banner("demo:msg", "Messages", 0xFF30D158.toInt(), "message", "Maya", "Landing at 6:40. Can you grab me from arrivals?",
                primaryAction = IslandAction("reply", "Reply by voice", IslandAction.Style.PRIMARY) { s.navigator.show("assistant") }),
            Banner("demo:door", "Front Door", 0xFFFF9F0A.toInt(), "doorbell", "Someone’s at the door", "Doorbell rang · tap for the live camera", critical = true,
                primaryAction = IslandAction("camera", "Show camera", IslandAction.Style.PRIMARY) {}),
            Banner("demo:washer", "Home", 0xFF0A84FF.toInt(), "home", "Washer finished", "52-minute cycle done in the laundry room",
                primaryAction = IslandAction("remind", "Remind me in 15 min", IslandAction.Style.PRIMARY) {}),
            Banner("demo:cal", "Calendar", 0xFFFF453A.toInt(), "calendar", "Team sync in 10 minutes", "9:30 AM · Video call · 4 people",
                primaryAction = IslandAction("join", "Join call", IslandAction.Style.PRIMARY) {}),
            // 4: as `:feature:phone` posts an iPhone (ANCS) notification: iOS bundle id, no Android icon, no reply
            Banner("demo:ios", "Messages", 0xFF30D158.toInt(), "message", "Maya", "Running 10 min late, save me a seat!",
                sourcePackage = "com.apple.MobileSMS", sourceDevice = "Maya’s iPhone"),
            // 5: an iPhone call banner (answered on the phone)
            Banner("demo:ioscall", "Phone", 0xFF30D158.toInt(), "call", "Maya", "Incoming call · Answer on iPhone", critical = true,
                sourcePackage = "com.apple.mobilephone", sourceDevice = "Maya’s iPhone"),
        )
        val b = list[i.coerceIn(0, list.size - 1)].copy(postedAtMillis = System.currentTimeMillis())
        s.notifications.post(b)
    }

    companion object {
        val DROP = bezier(0.3f, 1.4f, 0.5f, 1f)
    }
}
