package app.vesper.feature.sleep

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.model.SleepSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drawn artwork for each sleep sound (Now Playing, the island, Control Center): content, not
 * glass. Painted once per sound at [SIZE] px (~400 KB) and cached for the last two sounds.
 */
internal object SleepArt {
    const val SIZE = 320

    /** The sound's accent: the screen's selected icon and the art's light. */
    fun accent(s: SleepSound): Int = when (s) {
        SleepSound.RAIN -> 0xFF64D2FF.toInt()
        SleepSound.OCEAN -> 0xFF0A84FF.toInt()
        SleepSound.WHITE -> 0xFFF2F2F7.toInt()
        SleepSound.PINK -> 0xFFFF6F91.toInt()
        SleepSound.BROWN -> 0xFFD49A6A.toInt()
        SleepSound.FAN -> 0xFF8E9DFF.toInt()
        SleepSound.CRICKETS -> 0xFF30D158.toInt()
    }

    private val cache = LinkedHashMap<SleepSound, Bitmap>()

    fun get(s: SleepSound): Bitmap = cache[s] ?: draw(s).also {
        cache[s] = it
        // keep two (a crossfade); the rest are dropped (not recycled: the hub may still show them)
        while (cache.size > 2) cache.remove(cache.keys.first())
    }

    fun draw(s: SleepSound, size: Int = SIZE): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val k = size / 320f
        c.scale(k, k)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val rnd = Random(s.ordinal * 7919 + 17)
        fun grad(top: Int, bottom: Int) { p.shader = LinearGradient(0f, 0f, 0f, 320f, top, bottom, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 320f, 320f, p); p.shader = null }
        fun glow(x: Float, y: Float, r: Float, color: Int, a: Float) {
            p.shader = RadialGradient(x, y, r, intArrayOf(alpha(color, a), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 320f, 320f, p); p.shader = null
        }
        fun grain(n: Int, color: Int, a: Float, r: Float = 1.1f) {
            p.color = alpha(color, a)
            repeat(n) { c.drawCircle(rnd.nextFloat() * 320f, rnd.nextFloat() * 320f, r * (0.5f + rnd.nextFloat()), p) }
        }
        when (s) {
            SleepSound.RAIN -> {
                grad(0xFF0E2A3F.toInt(), 0xFF06121D.toInt())
                glow(90f, 60f, 220f, 0xFF64D2FF.toInt(), 0.35f)
                p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
                repeat(70) {
                    val x = rnd.nextFloat() * 360f - 20f; val y = rnd.nextFloat() * 300f - 20f; val l = 16f + rnd.nextFloat() * 30f
                    p.strokeWidth = 1.2f + rnd.nextFloat() * 1.4f
                    p.color = alpha(Color.WHITE, 0.15f + rnd.nextFloat() * 0.35f)
                    c.drawLine(x, y, x - l * 0.28f, y + l, p)
                }
                // ripples on the puddle
                repeat(6) {
                    val x = 40f + rnd.nextFloat() * 240f; val y = 252f + rnd.nextFloat() * 50f; val w = 26f + rnd.nextFloat() * 40f
                    p.strokeWidth = 1.6f; p.color = alpha(0xFF9FE3FF.toInt(), 0.35f)
                    c.drawOval(RectF(x - w, y - w * 0.22f, x + w, y + w * 0.22f), p)
                    p.color = alpha(0xFF9FE3FF.toInt(), 0.18f)
                    c.drawOval(RectF(x - w * 1.6f, y - w * 0.36f, x + w * 1.6f, y + w * 0.36f), p)
                }
                p.style = Paint.Style.FILL
            }
            SleepSound.OCEAN -> {
                grad(0xFF0B1E48.toInt(), 0xFF041026.toInt())
                glow(230f, 80f, 200f, 0xFF7FB2FF.toInt(), 0.35f)
                p.color = alpha(0xFFF4F1E6.toInt(), 0.92f)
                c.drawCircle(232f, 84f, 30f, p)
                val bands = intArrayOf(0xFF0E4C7A.toInt(), 0xFF0A6A93.toInt(), 0xFF0C88A6.toInt(), 0xFF1BA3B5.toInt())
                for (i in bands.indices) {
                    val base = 168f + i * 38f
                    val path = Path().apply {
                        moveTo(0f, base)
                        var x = 0f
                        while (x <= 320f) { lineTo(x, base + 10f * sin((x / 320f * 2f * PI * (1.4f + i * 0.3f) + i).toFloat())); x += 8f }
                        lineTo(320f, 320f); lineTo(0f, 320f); close()
                    }
                    p.color = bands[i]; c.drawPath(path, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = alpha(Color.WHITE, 0.45f - i * 0.07f)
                    c.drawPath(path, p); p.style = Paint.Style.FILL
                }
            }
            SleepSound.WHITE, SleepSound.PINK, SleepSound.BROWN -> {
                val (top, bottom, light) = when (s) {
                    SleepSound.WHITE -> Triple(0xFF3A3D46.toInt(), 0xFF15161A.toInt(), 0xFFFFFFFF.toInt())
                    SleepSound.PINK -> Triple(0xFF5A1F3A.toInt(), 0xFF1E0A16.toInt(), 0xFFFF8FB0.toInt())
                    else -> Triple(0xFF4A2C17.toInt(), 0xFF1A0E07.toInt(), 0xFFE7A86F.toInt())
                }
                grad(top, bottom)
                glow(160f, 150f, 210f, light, 0.30f)
                // the spectrum as soft hills: white is flat, pink tilts, brown is all low end
                val tilt = when (s) { SleepSound.WHITE -> 0f; SleepSound.PINK -> 0.5f; else -> 1f }
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                for (row in 0 until 5) {
                    val y0 = 96f + row * 34f
                    val path = Path()
                    var x = 24f
                    path.moveTo(x, y0)
                    while (x <= 296f) {
                        val f = 1f - tilt * (x - 24f) / 272f
                        val amp = 4f + 22f * f * (0.4f + rnd.nextFloat() * 0.6f)
                        path.lineTo(x, y0 + (if (((x / 6f).toInt()) % 2 == 0) -amp else amp) * (0.6f + 0.4f * (1 - row / 5f)))
                        x += 6f + 10f * tilt
                    }
                    p.strokeWidth = 2.2f; p.color = alpha(light, 0.55f - row * 0.08f)
                    c.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
                grain(1400, light, 0.10f)
            }
            SleepSound.FAN -> {
                grad(0xFF1F2547.toInt(), 0xFF0B0D1E.toInt())
                glow(160f, 160f, 190f, 0xFF8E9DFF.toInt(), 0.30f)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                for (r in 0 until 6) { p.color = alpha(Color.WHITE, 0.22f - r * 0.025f); c.drawCircle(160f, 160f, 54f + r * 18f, p) }
                p.style = Paint.Style.FILL
                p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
                for (i in 0 until 3) {
                    val a = (i * 2 * PI / 3 + 0.4).toFloat()
                    val blade = Path().apply {
                        moveTo(160f, 160f)
                        cubicTo(160f + 90f * cos(a - 0.5f), 160f + 90f * sin(a - 0.5f), 160f + 120f * cos(a + 0.15f), 160f + 120f * sin(a + 0.15f),
                            160f + 70f * cos(a + 0.55f), 160f + 70f * sin(a + 0.55f))
                        close()
                    }
                    p.color = alpha(0xFFC9D0FF.toInt(), 0.55f); c.drawPath(blade, p)
                }
                p.maskFilter = null
                p.color = 0xFFE6E9FF.toInt(); c.drawCircle(160f, 160f, 14f, p)
            }
            SleepSound.CRICKETS -> {
                grad(0xFF101A3A.toInt(), 0xFF050913.toInt())
                glow(240f, 70f, 160f, 0xFFFFE9A8.toInt(), 0.22f)
                grain(70, Color.WHITE, 0.7f, 1.3f)
                p.color = 0xFFFFF1C9.toInt(); c.drawCircle(240f, 72f, 26f, p)
                p.color = 0xFF111B3B.toInt(); c.drawCircle(252f, 64f, 24f, p)
                // grass
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                repeat(120) {
                    val x = rnd.nextFloat() * 330f - 5f; val h = 40f + rnd.nextFloat() * 90f; val lean = (rnd.nextFloat() - 0.5f) * 40f
                    p.strokeWidth = 2f + rnd.nextFloat() * 2f
                    p.color = if (rnd.nextFloat() < 0.5f) 0xFF0D3A24.toInt() else 0xFF145232.toInt()
                    val path = Path().apply { moveTo(x, 330f); quadTo(x + lean * 0.3f, 330f - h * 0.6f, x + lean, 330f - h) }
                    c.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
                // fireflies
                p.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                repeat(9) { p.color = alpha(0xFFD4FF7A.toInt(), 0.8f); c.drawCircle(30f + rnd.nextFloat() * 260f, 170f + rnd.nextFloat() * 90f, 3f, p) }
                p.maskFilter = null
            }
        }
        return b
    }

    private fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)
}
