package app.vesper.feature.camera.face

import app.vesper.feature.camera.capture.YPlane
import java.nio.ByteBuffer

/**
 * A drawn cartoon face (ellipses: skin, hair, eyes, brows, nose, mouth) that BlazeFace detects
 * with a score of ~0.9. Used by the self-test bench and unit tests, so models can be checked and
 * timed on the Echo without a real person or a camera, and no real face is ever stored.
 * Same drawing as the desktop reference script (docs/status/camera.md, "Self-test").
 */
object SyntheticFace {
    /** 0xRRGGBB pixels, [w]×[h], face centred at ([cx], [cy]) with half-width [r]. */
    fun rgb(w: Int, h: Int, cx: Float, cy: Float, r: Float): IntArray {
        val out = IntArray(w * h)
        fun inE(x: Float, y: Float, ex: Float, ey: Float, rx: Float, ry: Float): Boolean {
            val dx = (x - ex) / rx; val dy = (y - ey) / ry
            return dx * dx + dy * dy < 1f
        }
        for (j in 0 until h) for (i in 0 until w) {
            val x = i.toFloat(); val y = j.toFloat()
            var c = 0x5A6E8C // background (90, 110, 140)
            if (inE(x, y, cx, cy, r, r * 1.3f)) c = 0xE0AC8C
            if (inE(x, y, cx, cy - r * 0.5f, r * 1.05f, r) && y < cy - r * 0.7f) c = 0x32231A
            for (ex in floatArrayOf(-0.38f, 0.38f)) {
                if (inE(x, y, cx + ex * r, cy - 0.18f * r, r * 0.16f, r * 0.09f)) c = 0x281E1E
                if (inE(x, y, cx + ex * r, cy - 0.42f * r, r * 0.22f, r * 0.05f)) c = 0x3C281E
            }
            if (inE(x, y, cx, cy + 0.2f * r, r * 0.08f, r * 0.18f)) c = 0xC88C73
            if (inE(x, y, cx, cy + 0.55f * r, r * 0.35f, r * 0.08f)) c = 0x963C3C
            out[j * w + i] = c
        }
        return out
    }

    /** The drawn eye centres (image-left first), for checking detector landmarks. */
    fun eyes(cx: Float, cy: Float, r: Float) = floatArrayOf(cx - 0.38f * r, cy - 0.18f * r, cx + 0.38f * r, cy - 0.18f * r)

    /** The face as an NV21 camera frame ([w], [h] even). */
    fun frame(w: Int, h: Int, cx: Float, cy: Float, r: Float): YPlane {
        val px = rgb(w, h, cx, cy, r)
        val buf = ByteArray(w * h * 3 / 2)
        for (k in px.indices) {
            val c = px[k]
            val rr = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            buf[k] = (0.299f * rr + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        for (j in 0 until h step 2) for (i in 0 until w step 2) {
            val c = px[j * w + i]
            val rr = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val u = (-0.168736f * rr - 0.331264f * g + 0.5f * b + 128.5f).toInt().coerceIn(0, 255)
            val v = (0.5f * rr - 0.418688f * g - 0.081312f * b + 128.5f).toInt().coerceIn(0, 255)
            val o = w * h + (j / 2) * w + i
            buf[o] = v.toByte(); buf[o + 1] = u.toByte()
        }
        return YPlane(ByteBuffer.wrap(buf), w, h, w, 1, 0L, uvOffset = w * h)
    }
}
