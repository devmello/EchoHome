package app.vesper.feature.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.Fonts
import app.vesper.core.design.withAlpha
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Station pictures: the favicon from Radio Browser (fetched once, decoded at [ICON_PX], at most
 * [MAX_BYTES] downloaded, LRU of [MAX_ICONS] ≈ 0.6 MB), and the square cover Now Playing shows,
 * drawn from it (or from the name's initials over a colour picked from the name). No real album art.
 */
class RadioArt(private val http: OkHttpClient) {
    private val icons = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX_ICONS
    }
    /** Favicons that failed: not retried this session. */
    private val failed = HashSet<String>()

    fun cachedIcon(s: Station): Bitmap? = synchronized(icons) { icons[s.favicon] }

    suspend fun icon(s: Station): Bitmap? {
        if (s.favicon.isEmpty()) return null
        synchronized(icons) { icons[s.favicon]?.let { return it }; if (s.favicon in failed) return null }
        val bmp = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(s.favicon).header("User-Agent", RadioBrowser.USER_AGENT).build()).execute().use { res ->
                    val body = res.body
                    if (!res.isSuccessful || body == null || (body.contentLength() > MAX_BYTES)) null
                    else decode(body.byteStream().readNBytesCompat(MAX_BYTES))
                }
            } catch (e: IOException) { null } catch (e: IllegalArgumentException) { null }
        }
        synchronized(icons) { if (bmp != null) icons[s.favicon] = bmp else failed += s.favicon }
        return bmp
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        bytes ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = max(raw.width, raw.height)
        if (side <= ICON_PX) return raw
        val k = ICON_PX.toFloat() / side
        return Bitmap.createScaledBitmap(raw, (raw.width * k).toInt().coerceAtLeast(1), (raw.height * k).toInt().coerceAtLeast(1), true)
            .also { if (it !== raw) raw.recycle() }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    companion object {
        const val ICON_PX = 96
        const val MAX_ICONS = 32
        const val MAX_BYTES = 512 * 1024
        const val COVER_PX = 324

        /** A deep colour from the station's name (stable). */
        fun hue(name: String): Float = (abs(name.lowercase(Locale.ROOT).hashCode()) % 360).toFloat()

        fun initials(name: String): String {
            val words = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> "FM"
                words.size == 1 -> words[0].take(2).uppercase(Locale.ROOT)
                else -> (words[0].take(1) + words[1].take(1)).uppercase(Locale.ROOT)
            }
        }

        /** The Now Playing cover: a soft gradient with the station's icon (or initials) in the middle. */
        fun cover(s: Station, icon: Bitmap?): Bitmap {
            val n = COVER_PX
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val h = hue(s.name)
            val top = Color.HSVToColor(floatArrayOf(h, 0.72f, 0.62f))
            val bottom = Color.HSVToColor(floatArrayOf((h + 40f) % 360f, 0.8f, 0.24f))
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            p.shader = LinearGradient(0f, 0f, n.toFloat(), n.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
            p.shader = RadialGradient(n * 0.3f, n * 0.25f, n * 0.7f, withAlpha(Color.WHITE, 0.28f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
            p.shader = null
            // concentric "broadcast" rings
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            for (i in 1..4) {
                p.color = withAlpha(Color.WHITE, 0.10f - i * 0.018f)
                c.drawCircle(n / 2f, n / 2f, 70f + i * 34f, p)
            }
            p.style = Paint.Style.FILL
            if (icon != null) {
                val box = 168f
                val rect = RectF((n - box) / 2, (n - box) / 2, (n + box) / 2, (n + box) / 2)
                p.color = Color.WHITE
                c.drawRoundRect(rect, 36f, 36f, p)
                val k = minOf((box - 24f) / icon.width, (box - 24f) / icon.height)
                val w = icon.width * k; val hh = icon.height * k
                val dst = RectF(n / 2f - w / 2, n / 2f - hh / 2, n / 2f + w / 2, n / 2f + hh / 2)
                c.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            } else {
                val t = initials(s.name)
                p.color = Color.WHITE
                p.typeface = Fonts.sans
                p.textSize = 112f
                p.fontVariationSettings = Fonts.variation(800, 32f)
                p.textAlign = Paint.Align.CENTER
                val fm = p.fontMetrics
                c.drawText(t, n / 2f, n / 2f - (fm.ascent + fm.descent) / 2, p)
            }
            return b
        }
    }
}
