package app.vesper.feature.settings.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.VesperColors
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.settings.WebAddress
import app.vesper.feature.settings.WebSettings
import kotlinx.coroutines.flow.combine

/**
 * Screen "settings-pair": how to reach the settings page from a phone or Mac. A glass sheet over
 * black with a QR code of the page's URL, the address to type, and the paired-browser count.
 * The 6-digit code itself appears in [CodeOverlayView] when a browser taps Pair. Static: it
 * redraws only when the address, the paired list or Night mode changes.
 */
@SuppressLint("ViewConstructor")
internal class PairScreenView(context: Context, private val services: VesperServices, private val web: WebSettings) : View(context), VesperScreen {
    private val glass = GlassPainter()
    private val title = SpecText(32f, 800, -0.02f)
    private val body = SpecText(17f, 500)
    private val url = SpecText(26f, 700, -0.01f)
    private val small = SpecText(15f, 600)
    private val button = SpecText(17f, 700)

    private var address: WebAddress? = null
    private var qr: Qr? = null
    private var paired = 0
    private var night = false

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(combine(web.address, web.pairing.browsers, services.system.state) { a, b, s -> Triple(a, b.size, s.nightActive) }) { (a, n, nt) ->
            if (a != address) { address = a; qr = a?.let { runCatching { Qr.encode(it.qrUrl) }.getOrNull() } }
            paired = n; night = nt
            glass.night = nt
            invalidate()
        }
    }

    override fun onScreenShown(args: Bundle?) { invalidate() }

    private fun c(color: Int) = Night.map(color, night)

    override fun onDraw(canvas: Canvas) {
        // Sheet: 832×384, centred.
        glass.draw(canvas, SHEET_L, SHEET_T, SHEET_R, SHEET_B, 44f)

        // QR plate (plain fill on the sheet: no glass on glass).
        val pl = SHEET_L + 40f; val pt = SHEET_T + 40f; val ps = SHEET_B - SHEET_T - 80f
        val a = address
        val q = qr
        if (q != null) {
            Draw.roundRect(canvas, pl, pt, pl + ps, pt + ps, 24f, if (night) Night.COLOR else Color.WHITE)
            val module = Math.floor((ps / (q.size + 8)).toDouble()).toFloat()
            val side = module * q.size
            Draw.qr(canvas, q, pl + (ps - side) / 2f, pt + (ps - side) / 2f, side, Color.BLACK)
        } else {
            Draw.roundRect(canvas, pl, pt, pl + ps, pt + ps, 24f, withAlpha(c(Color.WHITE), 0.08f))
            Draw.centered(canvas, small, "No Wi-Fi", pl + ps / 2f, pt + ps / 2f + 5f, c(VesperColors.SECONDARY))
        }

        // Text column.
        val x = pl + ps + 44f
        val maxW = SHEET_R - 40f - x
        var y = SHEET_T + 40f
        title.draw(canvas, "Settings on your phone", x, title.baselineIn(y, 40f), c(Color.WHITE))
        y += 40f + 10f
        val intro = if (a != null) "Scan the code with your iPhone camera, or open this address in Safari on a phone or Mac:"
        else "Connect the Echo to Wi-Fi to change its settings from a phone or Mac."
        for (line in Draw.wrap(body, intro, maxW)) {
            body.draw(canvas, line, x, body.baselineIn(y, 24f), c(withAlpha(Color.WHITE, 0.72f)))
            y += 24f
        }
        if (a != null) {
            y += 14f
            val shown = a.display
            val w = url.width(shown) + 36f
            Draw.roundRect(canvas, x, y, x + w, y + 54f, 16f, c(withAlpha(Color.WHITE, 0.12f)))
            url.draw(canvas, shown, x + 18f, url.baselineIn(y, 54f), c(Color.WHITE))
            y += 54f
            a.hostDisplay?.let {
                y += 8f
                small.draw(canvas, "or $it", x, small.baselineIn(y, 20f), c(VesperColors.SECONDARY))
                y += 20f
            }
            y += 16f
            for (line in Draw.wrap(body, "Tap Pair on the page, then type the code that appears here.", maxW)) {
                body.draw(canvas, line, x, body.baselineIn(y, 24f), c(withAlpha(Color.WHITE, 0.72f)))
                y += 24f
            }
        }

        // Bottom row: paired count + Done (the one tinted action).
        val by = SHEET_B - 40f - 44f
        val count = when (paired) { 0 -> "No browsers paired yet"; 1 -> "1 browser paired"; else -> "$paired browsers paired" }
        small.draw(canvas, count, x, small.baselineIn(by, 44f), c(withAlpha(Color.WHITE, 0.5f)))
        Draw.roundRect(canvas, DONE_L, by, SHEET_R - 40f, by + 44f, 22f, if (night) withAlpha(Night.COLOR, 0.35f) else VesperColors.PRIMARY)
        Draw.centered(canvas, button, "Done", (DONE_L + SHEET_R - 40f) / 2f, button.baselineIn(by, 44f), c(Color.WHITE))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val by = SHEET_B - 40f - 44f
            val onDone = e.x in DONE_L - 10f..SHEET_R - 30f && e.y in by - 10f..by + 54f
            val outside = e.x !in SHEET_L..SHEET_R || e.y !in SHEET_T..SHEET_B
            if (onDone || outside) services.navigator.home()
        }
        return true
    }

    private companion object {
        const val SHEET_L = 64f
        const val SHEET_T = 48f
        const val SHEET_R = 896f
        const val SHEET_B = 432f
        const val DONE_L = SHEET_R - 40f - 120f
    }
}
