package com.echobedside.feature.phone.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.EchoColors
import com.echobedside.core.design.GlassPainter
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.phone.PhoneController
import com.echobedside.feature.phone.PhoneInfo
import com.echobedside.feature.phone.PhoneState

/**
 * "phone-pairing" (off the pager; opened from Control Center's Bluetooth cell or a pairing
 * request). No artboard exists for it yet (NOTES §2.2 lists phone pairing under first-run), so it
 * borrows the expanded banner's layout (Banners.dc.html): 64 px margins, a 64 px r18 tile with
 * "label · detail" 20/700, a 52/800 title, 22 px body, 72 px r36 buttons at bottom 34 with the
 * one tinted primary action. The paired phone sits on one glass card (rows and switches inside
 * it are plain fills: no glass on glass). Over black, so glass is rim + fill only.
 *
 * While shown, the Echo is discoverable (system prompt) and advertises at the faster interval.
 */
@SuppressLint("ViewConstructor")
open class PairingScreen(context: Context, private val services: EchoServices, private val controller: PhoneController?) : View(context), EchoScreen {

    /** Demo / screenshot state; when set it replaces the controller's. */
    var demoState: PhoneState? = null
        set(v) { field = v; v?.let { bind(it) } }

    private var s = PhoneState()
    private var night = false

    private val tLabel = SpecText(20f, 700)
    private val tTitle = SpecText(52f, 800, -0.03f)
    private val tBody = SpecText(22f, 500, -0.01f)
    private val tCode = SpecText(96f, 700, -0.02f)
    private val tCard = SpecText(16f, 600)
    private val tName = SpecText(28f, 700, -0.02f)
    private val tSmall = SpecText(16f, 500)
    private val tRow = SpecText(20f, 600, -0.01f)
    private val tButton = SpecText(22f, 700)
    private val tStatus = SpecText(18f, 600)

    private val cardGlass = GlassPainter()
    private val statusGlass = GlassPainter()
    private val doneGlass = GlassPainter()
    private val primaryGlass = GlassPainter().apply { tint = withAlpha(EchoColors.BLUE, 0.62f) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    private val glyph = Path()

    private val card = RectF(560f, 56f, 896f, 352f)
    private val notifSwitch = RectF()
    private val audioSwitch = RectF()
    private val forgetRect = RectF()
    private val doneRect = RectF()
    private val primaryRect = RectF()
    private val pairRect = RectF()
    private val cancelRect = RectF()

    private var notifKnob = 1f
    private var audioKnob = 1f
    private var forgetArmedUntil = 0L

    init {
        setBackgroundColor(Color.BLACK)
        if (controller != null) observeWhileAttached(controller.state) { if (demoState == null) bind(it) }
        observeWhileAttached(services.system.state) { st -> if (st.nightActive != night) { night = st.nightActive; listOf(cardGlass, statusGlass, doneGlass, primaryGlass).forEach { it.night = night }; invalidate() } }
        buildGlyph()
    }

    private var bound = false

    private fun bind(n: PhoneState) {
        val old = s
        s = n
        if (!bound) {
            bound = true
            notifKnob = if (n.notificationsEnabled) 1f else 0f
            audioKnob = if (n.audioEnabled) 1f else 0f
        } else {
            if (old.notificationsEnabled != n.notificationsEnabled) animateKnob(true, n.notificationsEnabled)
            if (old.audioEnabled != n.audioEnabled) animateKnob(false, n.audioEnabled)
        }
        invalidate()
    }

    override fun onScreenShown(args: Bundle?) { controller?.setPairingMode(true, context as? Activity) }
    override fun onScreenHidden() { controller?.setPairingMode(false, null); forgetArmedUntil = 0 }

    private fun ink(c: Int) = Night.map(c, night)

    // --- drawing ---

    override fun onDraw(c: Canvas) {
        val white = ink(Color.WHITE)
        // header: 64 tile r18 + bluetooth glyph, "Bluetooth · Echo name" 20/700 .8 (Banners expanded header)
        r.set(64f, 56f, 128f, 120f)
        paint.color = ink(EchoColors.BLUE)
        c.drawRoundRect(r, 18f, 18f, paint)
        drawGlyph(c, 96f, 88f, 34f, if (night) Color.BLACK else Color.WHITE)
        tLabel.draw(c, "Bluetooth · ${s.echoName}", 142f, tLabel.baselineIn(56f, 64f), withAlpha(white, 0.8f))

        val prompt = s.pairing
        if (prompt != null) {
            val code = prompt.passkey?.let { if (it.length == 6) it.substring(0, 3) + " " + it.substring(3) else it }
            tTitle.draw(c, if (code != null) "Confirm the code" else "Confirm pairing", 64f, tTitle.baselineIn(146f, 55f), white)
            if (code != null) tCode.draw(c, code, 60f, tCode.baselineIn(206f, 110f), white)
            wrap(c, tBody, if (code != null) "“${prompt.deviceName}” should show the same code. Tap Pair on both."
                else "Tap Pair on “${prompt.deviceName}” and on the Echo.",
                64f, if (code != null) 322f else 222f, 470f, 30f, withAlpha(white, 0.78f))
        } else {
            tTitle.draw(c, "Pair your iPhone", 64f, tTitle.baselineIn(146f, 55f), white)
            var y = 222f
            y = wrap(c, tBody, "Speaker: on your iPhone, open Settings › Bluetooth and tap “${s.echoName}”.", 64f, y, 470f, 30f, withAlpha(white, 0.78f)) + 12f
            wrap(c, tBody, "Notifications: in a Bluetooth app such as nRF Connect, connect to “${s.echoName}”, pair, then turn on Share System Notifications.",
                64f, y, 470f, 30f, withAlpha(white, 0.78f))
        }

        drawCard(c, white)
        drawStatus(c, white)
        drawButtons(c, white)
    }

    private fun drawCard(c: Canvas, white: Int) {
        // Without a phone the card only holds two short lines: keep it compact.
        card.bottom = if (s.phones.isEmpty()) card.top + 128f else 352f
        cardGlass.draw(c, card.left, card.top, card.right, card.bottom, 32f)
        val x = card.left + 24f
        val phone: PhoneInfo? = s.phones.firstOrNull()
        tCard.draw(c, if (phone != null) "Paired phone" else "No phone yet", x, tCard.baselineIn(card.top + 22f, 20f), withAlpha(white, 0.6f))
        if (phone == null) {
            wrap(c, tSmall, "Notifications and Now Playing show up here once your iPhone is paired.", x, card.top + 52f, card.width() - 48f, 22f, withAlpha(white, 0.6f))
            notifSwitch.setEmpty(); audioSwitch.setEmpty(); forgetRect.setEmpty()
            return
        }
        tName.draw(c, ellipsize(tName, phone.name, card.width() - 48f), x, tName.baselineIn(card.top + 46f, 34f), white)
        val sub = when {
            phone.notificationsConnected && phone.audioConnected -> "Connected · notifications and audio"
            phone.notificationsConnected -> "Connected · notifications"
            phone.audioConnected -> "Connected · audio"
            else -> "Not connected"
        }
        val dot = if (phone.notificationsConnected || phone.audioConnected) EchoColors.GREEN else withAlpha(Color.WHITE, 0.35f)
        paint.color = ink(dot)
        c.drawCircle(x + 5f, card.top + 94f, 5f, paint)
        tSmall.draw(c, sub, x + 18f, tSmall.baselineIn(card.top + 83f, 22f), withAlpha(white, 0.6f))

        // rows: Notifications / Audio, 64 tall, 0.5 px separators
        val rowTop = card.top + 120f
        drawRow(c, "Notifications", rowTop, notifSwitch, notifKnob, white)
        drawRow(c, "Audio", rowTop + 64f, audioSwitch, audioKnob, white)
        sep(c, rowTop + 128f)
        val armed = System.currentTimeMillis() < forgetArmedUntil
        val label = if (armed) "Tap again to forget" else "Forget This iPhone"
        forgetRect.set(card.left, rowTop + 128f, card.right, card.bottom)
        tRow.draw(c, label, x, tRow.baselineIn(rowTop + 128f, card.bottom - rowTop - 128f), ink(EchoColors.RED))
    }

    private fun drawRow(c: Canvas, label: String, top: Float, sw: RectF, knob: Float, white: Int) {
        sep(c, top)
        tRow.draw(c, label, card.left + 24f, tRow.baselineIn(top, 64f), white)
        sw.set(card.right - 24f - 64f, top + 13f, card.right - 24f, top + 51f)
        val off = withAlpha(0xFF787880.toInt(), 0.32f)
        paint.color = ink(blend(off, EchoColors.GREEN, knob))
        c.drawRoundRect(sw, 19f, 19f, paint)
        paint.color = if (night) Night.COLOR else Color.WHITE
        val kx = sw.left + 19f + (sw.width() - 38f) * knob
        c.drawCircle(kx, sw.centerY(), 16f, paint)
    }

    private fun sep(c: Canvas, y: Float) {
        paint.color = withAlpha(ink(Color.WHITE), 0.12f)
        c.drawRect(card.left + 24f, y, card.right - 24f, y + 0.5f, paint)
    }

    private fun drawStatus(c: Canvas, white: Int) {
        val text = s.status.ifEmpty { "Starting Bluetooth…" }
        val w = 24f + 10f + 12f + tStatus.width(text) + 24f
        val top = 480f - 34f - 52f
        statusGlass.draw(c, 64f, top, 64f + w, top + 52f, -1f)
        val dot = when {
            !s.bluetoothOn -> withAlpha(Color.WHITE, 0.35f)
            s.pairing != null -> EchoColors.ORANGE
            s.phones.any { it.notificationsConnected || it.audioConnected } -> EchoColors.GREEN
            s.advertising -> EchoColors.BLUE
            else -> withAlpha(Color.WHITE, 0.35f)
        }
        paint.color = ink(dot)
        c.drawCircle(64f + 24f + 5f, top + 26f, 5f, paint)
        tStatus.draw(c, text, 64f + 24f + 10f + 12f, tStatus.baselineIn(top, 52f), withAlpha(white, 0.86f))
    }

    private fun drawButtons(c: Canvas, white: Int) {
        val top = 480f - 34f - 72f
        if (s.pairing?.canConfirm == true) {
            // The Echo answers the pairing itself: Pair (the one tinted action) + Cancel, in place of Done.
            pairRect.set(736f, top, 896f, top + 72f)
            cancelRect.set(pairRect.left - 14f - 160f, top, pairRect.left - 14f, top + 72f)
            doneGlass.draw(c, cancelRect.left, cancelRect.top, cancelRect.right, cancelRect.bottom, 36f)
            centered(c, tButton, "Cancel", cancelRect, white)
            primaryGlass.draw(c, pairRect.left, pairRect.top, pairRect.right, pairRect.bottom, 36f)
            centered(c, tButton, "Pair", pairRect, white)
            doneRect.setEmpty(); primaryRect.setEmpty()
            return
        }
        pairRect.setEmpty(); cancelRect.setEmpty()
        doneRect.set(736f, top, 896f, top + 72f)
        doneGlass.draw(c, doneRect.left, doneRect.top, doneRect.right, doneRect.bottom, 36f)
        centered(c, tButton, "Done", doneRect, white)
        if (!s.bluetoothOn) {
            primaryRect.set(doneRect.left - 14f - 260f, top, doneRect.left - 14f, top + 72f)
            primaryGlass.draw(c, primaryRect.left, primaryRect.top, primaryRect.right, primaryRect.bottom, 36f)
            centered(c, tButton, "Turn On Bluetooth", primaryRect, white)
        } else primaryRect.setEmpty()
    }

    private fun centered(c: Canvas, t: SpecText, s: String, box: RectF, color: Int) =
        t.draw(c, s, box.centerX() - t.width(s) / 2f, t.baselineIn(box.top, box.height()), color)

    /** Word-wraps [text] into [maxW], line boxes of [lh] from [top]; returns the bottom. */
    private fun wrap(c: Canvas, t: SpecText, text: String, x: Float, top: Float, maxW: Float, lh: Float, color: Int): Float {
        var y = top
        var line = ""
        for (word in text.split(' ')) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (t.width(trial) > maxW && line.isNotEmpty()) { t.draw(c, line, x, t.baselineIn(y, lh), color); y += lh; line = word } else line = trial
        }
        if (line.isNotEmpty()) { t.draw(c, line, x, t.baselineIn(y, lh), color); y += lh }
        return y
    }

    private fun ellipsize(t: SpecText, s: String, maxW: Float): String {
        if (t.width(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && t.width("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 255) * (1 - f) + ((b shr sh) and 255) * f).toInt() shl sh
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** The Bluetooth rune, 24-unit viewBox, stroked like the banner glyphs (2.2). */
    private fun buildGlyph() {
        glyph.reset()
        glyph.moveTo(7f, 7f); glyph.lineTo(17f, 17f); glyph.lineTo(12f, 22f); glyph.lineTo(12f, 2f); glyph.lineTo(17f, 7f); glyph.lineTo(7f, 17f)
    }

    private fun drawGlyph(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(size / 24f, size / 24f)
        stroke.color = color
        stroke.strokeWidth = 2.2f
        c.drawPath(glyph, stroke)
        c.restore()
    }

    private fun animateKnob(notif: Boolean, on: Boolean) {
        val from = if (notif) notifKnob else audioKnob
        ValueAnimator.ofFloat(from, if (on) 1f else 0f).apply {
            duration = 350
            interpolator = Motion.SPRING_SOFT
            addUpdateListener { v -> if (notif) notifKnob = v.animatedValue as Float else audioKnob = v.animatedValue as Float; invalidate() }
            start()
        }
    }

    // --- touch ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val x = e.x; val y = e.y
        val pad = 12f
        fun hit(rr: RectF) = !rr.isEmpty && x >= rr.left - pad && x <= rr.right + pad && y >= rr.top - pad && y <= rr.bottom + pad
        val phone = s.phones.firstOrNull()
        when {
            hit(pairRect) -> if (demoState != null) demoState = s.copy(pairing = null) else controller?.confirmPairing(true)
            hit(cancelRect) -> if (demoState != null) demoState = s.copy(pairing = null) else controller?.confirmPairing(false)
            hit(doneRect) -> services.navigator.home()
            hit(primaryRect) -> runCatching { @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()?.enable() }
            hit(notifSwitch) -> if (demoState != null) demoState = s.copy(notificationsEnabled = !s.notificationsEnabled) else controller?.let { it.notificationsEnabled = !it.notificationsEnabled }
            hit(audioSwitch) -> if (demoState != null) demoState = s.copy(audioEnabled = !s.audioEnabled) else controller?.let { it.audioEnabled = !it.audioEnabled }
            phone != null && forgetRect.contains(x, y) -> {
                if (System.currentTimeMillis() < forgetArmedUntil) {
                    forgetArmedUntil = 0
                    if (demoState != null) demoState = s.copy(phones = emptyList()) else controller?.forget(phone.address)
                } else {
                    forgetArmedUntil = System.currentTimeMillis() + 3000
                    postDelayed({ invalidate() }, 3050)
                }
                invalidate()
            }
        }
        return true
    }
}
