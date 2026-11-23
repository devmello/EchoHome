package app.vesper.feature.home.radar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.IOException

/**
 * The radar's base map, drawn from Natural Earth 1:50m (public domain): land, lakes, country
 * borders and state / province lines, in `assets/radar/ne50m.bin` (279 KB, made by
 * `scripts/make-radar-map.py`). No map tiles are downloaded. Coordinates are Web Mercator units,
 * 0..65536 = the world (1 unit = 1 px at zoom 7 with 512 px tiles), kept as u16 in a CharArray
 * (~460 KB for all 115 k points). Parsing is pure; [render] draws one view into a bitmap once
 * (off the main thread) and the screen just blits it.
 */
class RadarMap private constructor(val layers: List<Layer>) {

    enum class Kind { LAND, LAKES, BORDERS, STATES }

    class Ring(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val points: CharArray) {
        val size get() = points.size / 2
        fun x(i: Int) = points[2 * i].code
        fun y(i: Int) = points[2 * i + 1].code
    }

    class Layer(val kind: Kind, val closed: Boolean, val rings: List<Ring>)

    data class Style(val ocean: Int, val land: Int, val lakes: Int, val border: Int, val state: Int, val coast: Int)

    /**
     * Draws the [w]×[h] view whose top-left is world px ([ox], [oy]) at zoom [z] into [out]
     * (created if null or the wrong size; RGB_565, no alpha needed).
     */
    fun render(ox: Double, oy: Double, z: Int, w: Int, h: Int, style: Style, out: Bitmap? = null): Bitmap {
        val bmp = out?.takeIf { it.width == w && it.height == h && it.isMutable } ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(style.ocean)
        val k = TileMath.mapScale(z)
        val world = TileMath.worldSize(z)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        // world copies left / right of the antimeridian, only when the view reaches past it
        val shifts = listOf(-world, 0.0, world).filter { s -> ox + w > s && ox < s + world }
        for (layer in layers) {
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (shift in shifts) for (r in layer.rings) {
                val l = r.minX * k + shift - ox; val rr = r.maxX * k + shift - ox
                val t = r.minY * k - oy; val b = r.maxY * k - oy
                if (rr < -2 || l > w + 2 || b < -2 || t > h + 2) continue
                if ((rr - l) < 0.6 && (b - t) < 0.6) continue
                path.moveTo((r.x(0) * k + shift - ox).toFloat(), (r.y(0) * k - oy).toFloat())
                for (i in 1 until r.size) path.lineTo((r.x(i) * k + shift - ox).toFloat(), (r.y(i) * k - oy).toFloat())
                if (layer.closed) path.close()
            }
            when (layer.kind) {
                Kind.LAND -> {
                    fill.color = style.land; c.drawPath(path, fill)
                    line.color = style.coast; line.strokeWidth = 1f; c.drawPath(path, line)
                }
                Kind.LAKES -> { fill.color = style.lakes; c.drawPath(path, fill); line.color = style.coast; line.strokeWidth = 0.8f; c.drawPath(path, line) }
                Kind.BORDERS -> { line.color = style.border; line.strokeWidth = 1.2f; c.drawPath(path, line) }
                Kind.STATES -> if (z >= 4) { line.color = style.state; line.strokeWidth = 0.8f; c.drawPath(path, line) }
            }
        }
        return bmp
    }

    companion object {
        const val ASSET = "radar/ne50m.bin"

        val DAY = Style(ocean = 0xFF0B1622.toInt(), land = 0xFF1C2530.toInt(), lakes = 0xFF0B1622.toInt(),
            border = 0x66FFFFFF, state = 0x2EFFFFFF, coast = 0x40FFFFFF)
        val NIGHT = Style(ocean = Color.BLACK, land = Color.BLACK, lakes = Color.BLACK,
            border = 0x66BE180E, state = 0x33BE180E, coast = 0x88BE180E.toInt())

        /** Parses the asset (see scripts/make-radar-map.py for the format). Throws IOException if it's malformed. */
        fun parse(b: ByteArray): RadarMap {
            var p = 0
            fun u8(): Int { if (p >= b.size) throw IOException("truncated"); return b[p++].toInt() and 0xFF }
            fun u16(): Int = (u8() shl 8) or u8()
            fun u32(): Int = (u16() shl 16) or u16()
            fun varint(): Int {
                var shift = 0; var v = 0
                while (true) {
                    val x = u8()
                    v = v or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) return v
                    shift += 7
                    if (shift > 28) throw IOException("bad varint")
                }
            }
            fun unzig(v: Int) = (v ushr 1) xor -(v and 1)
            if (b.size < 6 || String(b, 0, 4, Charsets.US_ASCII) != "VMAP") throw IOException("not a VMAP file")
            p = 4
            if (u8() != 1) throw IOException("unknown version")
            val nLayers = u8()
            val layers = ArrayList<Layer>(nLayers)
            repeat(nLayers) {
                val kind = Kind.values().getOrNull(u8()) ?: throw IOException("bad layer kind")
                val closed = u8() == 1
                val nRings = u32()
                if (nRings < 0 || nRings > 100_000) throw IOException("bad ring count")
                val rings = ArrayList<Ring>(nRings)
                repeat(nRings) {
                    val minX = u16(); val minY = u16(); val maxX = u16(); val maxY = u16()
                    val n = u32()
                    if (n < 1 || n > 1_000_000) throw IOException("bad point count")
                    val pts = CharArray(n * 2)
                    var x = u16(); var y = u16()
                    pts[0] = x.toChar(); pts[1] = y.toChar()
                    for (i in 1 until n) {
                        x += unzig(varint()); y += unzig(varint())
                        pts[2 * i] = x.toChar(); pts[2 * i + 1] = y.toChar()
                    }
                    rings += Ring(minX, minY, maxX, maxY, pts)
                }
                layers += Layer(kind, closed, rings)
            }
            return RadarMap(layers)
        }
    }
}

/**
 * The radar's in-memory tile cache: PNG bytes by URL, least recently used dropped beyond
 * [maxBytes]. Together with the decoded frames ([RadarFrames.FRAME_BYTES] × frames) it stays under
 * the 8 MB radar budget. Pure.
 */
class TileCache(private val maxBytes: Int) {
    private val map = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    var bytes = 0
        private set

    @Synchronized fun get(key: String): ByteArray? = map[key]

    @Synchronized fun put(key: String, value: ByteArray) {
        if (value.size > maxBytes) return
        map.remove(key)?.let { bytes -= it.size }
        map[key] = value
        bytes += value.size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.size
            it.remove()
        }
    }

    @Synchronized fun clear() { map.clear(); bytes = 0 }

    @Synchronized fun size() = map.size
}
