package app.vesper.feature.assistant.ui

import kotlin.math.min
import kotlin.math.sin

/**
 * The droplet's shape (Assistant.dc.html, NOTES §3.5): CSS `border-radius: r0% r1% r2% r3% / r4% r5% r6% r7%`
 * whose 8 values wobble around 50% with the voice level, drawn as a path with animated control points.
 */
object Blob {
    /** Bezier handle length for a quarter ellipse. */
    private const val K = 0.5522848f

    /** The 8 radii in % at time [t] (s) with wobble amplitude [amp] (%). */
    fun radii(t: Double, amp: Float, out: FloatArray = FloatArray(8)): FloatArray {
        for (i in 0 until 8) out[i] = (50 + amp * sin(t * (2.1 + i * 0.37) + i * 1.7)).toFloat()
        return out
    }

    /**
     * Writes the rounded shape [w]×[h] at ([left],[top]) into [sink]. [r] holds horizontal radii
     * TL TR BR BL then vertical radii TL TR BR BL, in % of the width / height. Adjacent radii that
     * add up past 100% are scaled down together, as CSS does.
     */
    fun path(sink: PathSink, left: Float, top: Float, w: Float, h: Float, r: FloatArray) {
        var ax0 = r[0] / 100f * w; var ax1 = r[1] / 100f * w; var ax2 = r[2] / 100f * w; var ax3 = r[3] / 100f * w
        var by0 = r[4] / 100f * h; var by1 = r[5] / 100f * h; var by2 = r[6] / 100f * h; var by3 = r[7] / 100f * h
        val f = min(1f, min(min(w / (ax0 + ax1), w / (ax2 + ax3)), min(h / (by1 + by2), h / (by0 + by3))))
        if (f < 1f) { ax0 *= f; ax1 *= f; ax2 *= f; ax3 *= f; by0 *= f; by1 *= f; by2 *= f; by3 *= f }
        val l = left; val t = top; val rr = left + w; val b = top + h
        sink.moveTo(l + ax0, t)
        sink.lineTo(rr - ax1, t)
        sink.cubicTo(rr - ax1 + ax1 * K, t, rr, t + by1 - by1 * K, rr, t + by1)
        sink.lineTo(rr, b - by2)
        sink.cubicTo(rr, b - by2 + by2 * K, rr - ax2 + ax2 * K, b, rr - ax2, b)
        sink.lineTo(l + ax3, b)
        sink.cubicTo(l + ax3 - ax3 * K, b, l, b - by3 + by3 * K, l, b - by3)
        sink.lineTo(l, t + by0)
        sink.cubicTo(l, t + by0 - by0 * K, l + ax0 - ax0 * K, t, l + ax0, t)
        sink.close()
    }

    /** Droplet diameter per phase (px). */
    fun size(phase: String): Float = when (phase) {
        "listen" -> 116f
        "think" -> 96f
        "answer" -> 66f
        else -> 84f
    }
}
