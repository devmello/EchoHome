package app.vesper.feature.system.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

class AndroidPathSink(val path: Path = Path()) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = path.close()
}

/**
 * An icon from the designs: SVG path data in a [viewBox]-unit square, drawn stroked
 * (stroke-linecap/linejoin round, like every icon in design/) or filled.
 */
class Icon(d: String, val viewBox: Float = 24f, val stroke: Float = 2.2f, val fill: Boolean = false) {
    val path: Path = AndroidPathSink().also { SvgPath.parse(d, it) }.path

    /** Draws the icon [size] px square with its top-left at ([x], [y]). [strokeOverride] in viewBox units. */
    fun draw(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, paint: Paint = shared, strokeOverride: Float = -1f) {
        val s = size / viewBox
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(s, s)
        paint.color = color
        paint.shader = null
        if (fill) {
            paint.style = Paint.Style.FILL
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (strokeOverride > 0) strokeOverride else stroke
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /** Draws it centred on ([cx], [cy]). */
    fun drawCentered(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint = shared, strokeOverride: Float = -1f) =
        draw(canvas, cx - size / 2, cy - size / 2, size, color, paint, strokeOverride)

    companion object {
        /** Main-thread only. */
        val shared = Paint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Every icon used by the system layer, copied from the design files. */
object Icons {
    // HUDs.dc.html / ControlCenter.dc.html (viewBox 24)
    val volume by lazy { Icon("M11 5 6 9H3v6h3l5 4zM15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13") }
    val volumeMuted by lazy { Icon("M11 5 6 9H3v6h3l5 4zM16 9l6 6M22 9l-6 6") }
    val sun by lazy { Icon("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4") }
    val micOff by lazy { Icon("M3 3l18 18M9 9v3a3 3 0 0 0 5.1 2.1M15 9.3V6a3 3 0 0 0-5.9-.8M19 11a7 7 0 0 1-1.2 3.9M5 11a7 7 0 0 0 11.2 5.6M12 18v3") }
    val mic by lazy { Icon("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM19 11a7 7 0 0 1-14 0M12 18v3") }
    val camOff by lazy { Icon("M3 3l18 18M7.5 6H4.5A1.5 1.5 0 0 0 3 7.5v10A1.5 1.5 0 0 0 4.5 19h13M10 4h5l1.5 2h3A1.5 1.5 0 0 1 21 7.5V16M10 10.5a3 3 0 0 0 4 4") }
    val cam by lazy { Icon("M3 7.5A1.5 1.5 0 0 1 4.5 6h3L9 4h6l1.5 2h3A1.5 1.5 0 0 1 21 7.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5zM12 10a3 3 0 1 0 0 6 3 3 0 0 0 0-6z") }
    val wifi by lazy { Icon("M2 8.8a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01") }
    val bluetooth by lazy { Icon("M7 7l10 10-5 4V3l5 4L7 17") }
    val airplay by lazy { Icon("M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1M12 15l5 6H7z") }
    val spotify by lazy { Icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM7.5 9.5c3-1 6.5-.7 9 .8M8 12.8c2.5-.7 5.2-.4 7.4.8M8.6 15.8c2-.5 4-.3 5.8.6") }
    val moon by lazy { Icon("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z", fill = true) }
    val night by lazy { Icon("M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9zM17 3v4M15 5h4", fill = true) }
    val timer by lazy { Icon("M12 8v5l3 2M9 2h6M12 4a9 9 0 1 0 0 18 9 9 0 0 0 0-18z") }
    val autoFace by lazy { Icon("M3 8V6a3 3 0 0 1 3-3h2M16 3h2a3 3 0 0 1 3 3v2M21 16v2a3 3 0 0 1-3 3h-2M8 21H6a3 3 0 0 1-3-3v-2M15 11a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", stroke = 2.4f) }
    val gear by lazy {
        Icon("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z", stroke = 2f)
    }

    // Transport (viewBox 32, filled)
    val prev by lazy { Icon("M16 9.2a1.4 1.4 0 0 0-2.1-1.2l-9.5 6.8a1.5 1.5 0 0 0 0 2.4l9.5 6.8a1.4 1.4 0 0 0 2.1-1.2zM29 9.2a1.4 1.4 0 0 0-2.1-1.2l-9.5 6.8a1.5 1.5 0 0 0 0 2.4l9.5 6.8a1.4 1.4 0 0 0 2.1-1.2z", 32f, fill = true) }
    val next by lazy { Icon("M3 9.2a1.4 1.4 0 0 1 2.1-1.2l9.5 6.8a1.5 1.5 0 0 1 0 2.4l-9.5 6.8A1.4 1.4 0 0 1 3 22.8zM16 9.2a1.4 1.4 0 0 1 2.1-1.2l9.5 6.8a1.5 1.5 0 0 1 0 2.4l-9.5 6.8a1.4 1.4 0 0 1-2.1-1.2z", 32f, fill = true) }
    val pause32 by lazy { Icon("M8.8 5h2.8a1.8 1.8 0 0 1 1.8 1.8v18.4a1.8 1.8 0 0 1-1.8 1.8H8.8A1.8 1.8 0 0 1 7 25.2V6.8A1.8 1.8 0 0 1 8.8 5zM20.4 5h2.8A1.8 1.8 0 0 1 25 6.8v18.4a1.8 1.8 0 0 1-1.8 1.8h-2.8a1.8 1.8 0 0 1-1.8-1.8V6.8A1.8 1.8 0 0 1 20.4 5z", 32f, fill = true) }
    val play32 by lazy { Icon("M8 6.3a1.6 1.6 0 0 1 2.4-1.4l16 9.7a1.6 1.6 0 0 1 0 2.8l-16 9.7A1.6 1.6 0 0 1 8 25.7z", 32f, fill = true) }

    // Island timer actions (viewBox 24)
    val pause24 by lazy { Icon("M7 4.5h3.4v15H7zM13.6 4.5H17v15h-3.6z", fill = true) }
    val play24 by lazy { Icon("M7 4.7a1.2 1.2 0 0 1 1.8-1l10.5 7.3a1.2 1.2 0 0 1 0 2l-10.5 7.3A1.2 1.2 0 0 1 7 19.3z", fill = true) }
    val close by lazy { Icon("M6 6l12 12M18 6 6 18", stroke = 2.8f) }
    val phone by lazy { Icon("M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z", fill = true) }
    val video by lazy { Icon("M3 7.5A1.5 1.5 0 0 1 4.5 6h9A1.5 1.5 0 0 1 15 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 3 16.5zM16 10.5l4.2-2.6a.5.5 0 0 1 .8.4v7.4a.5.5 0 0 1-.8.4L16 13.5z", fill = true) }
    val alarm by lazy { Icon("M12 7v5l3 2M5 3 2 6M22 6l-3-3M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z") }

    // Banners.dc.html app glyphs (viewBox 24, stroked)
    val message by lazy { Icon("M21 11.5a8.4 8.4 0 0 1-12.6 7.3L3 20.5l1.7-5A8.5 8.5 0 1 1 21 11.5z") }
    val bell by lazy { Icon("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0") }
    val washer by lazy { Icon("M5 3h14a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM12 9a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM7.5 6h.01M10.5 6h.01") }
    // banner glyphs for iPhone apps (`:feature:phone` AppCatalog keys), same 24 grid and 2.2 stroke
    val mail by lazy { Icon("M4.5 6h15A1.5 1.5 0 0 1 21 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16.5v-9A1.5 1.5 0 0 1 4.5 6zM3.5 7l8.5 6 8.5-6") }
    val music by lazy { Icon("M9 18V5.5l11-2V16M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z") }
    val news by lazy { Icon("M4 5h12v14H6a2 2 0 0 1-2-2zM16 9h4v8a2 2 0 0 1-2 2h-2M7.5 9h5M7.5 12.5h5M7.5 16h3") }
    val heart by lazy { Icon("M12 20s-7-4.4-9-8.6C1.6 8.3 3.6 5 7 5c2 0 3.3 1.1 5 3 1.7-1.9 3-3 5-3 3.4 0 5.4 3.3 4 6.4C19 15.6 12 20 12 20z") }
    val location by lazy { Icon("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM12 7a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z") }
    val calendar by lazy { Icon("M4 5h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM16 3v4M8 3v4M3 10h18") }

    /** Banner glyph key → icon ("message", "doorbell", "home", "calendar"; anything else: a bell). */
    fun glyph(key: String?): Icon = when (key) {
        "message", "messages", "sms", "chat" -> message
        "doorbell", "door" -> bell
        "home", "washer", "appliance" -> washer
        "calendar", "event" -> calendar
        "call", "phone" -> phone
        "timer" -> timer
        "alarm" -> alarm
        "camera" -> cam
        "mail", "email" -> mail
        "video", "facetime" -> video
        "music", "podcast" -> music
        "news" -> news
        "heart", "health" -> heart
        "location", "map", "maps" -> location
        "bell" -> bell
        else -> bell
    }
}
