package app.vesper.feature.home.radar

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web Mercator ("slippy map") maths for the radar: world pixel coordinates at a zoom level with
 * [TILE] px tiles, and which tiles cover a viewport centred on a place. Pure; unit-tested.
 * x wraps around the antimeridian; y is clamped to the map (±85.05°).
 */
object TileMath {
    /** RainViewer serves 256 or 512 px tiles; 512 needs a quarter of the requests (rate limit). */
    const val TILE = 512
    const val MAX_LAT = 85.05112878

    fun worldSize(z: Int): Double = TILE.toDouble() * (1 shl z)

    fun lonToX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)

    fun latToY(lat: Double, z: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(z)
    }

    fun xToLon(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0

    fun yToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(z)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** One tile to fetch: [x] already wrapped into 0 until 2^z; ([left], [top]) = its corner in the view. */
    data class TileRef(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double)

    /** World px of the view's top-left corner for a [w]×[h] view centred on ([lat], [lon]). */
    fun viewOrigin(lat: Double, lon: Double, z: Int, w: Int, h: Int): Pair<Double, Double> =
        (lonToX(lon, z) - w / 2.0) to (latToY(lat, z) - h / 2.0)

    /** The tiles covering a [w]×[h] view centred on ([lat], [lon]) at zoom [z]. */
    fun tilesFor(lat: Double, lon: Double, z: Int, w: Int, h: Int): List<TileRef> {
        val (ox, oy) = viewOrigin(lat, lon, z, w, h)
        val n = 1 shl z
        val x0 = floor(ox / TILE).toInt()
        val x1 = floor((ox + w - 1) / TILE).toInt()
        val y0 = floor(oy / TILE).toInt().coerceAtLeast(0)
        val y1 = floor((oy + h - 1) / TILE).toInt().coerceAtMost(n - 1)
        val out = ArrayList<TileRef>()
        for (ty in y0..y1) for (tx in x0..x1) {
            out += TileRef(z, ((tx % n) + n) % n, ty, tx * TILE.toDouble() - ox, ty * TILE.toDouble() - oy)
        }
        return out
    }

    /**
     * Factor from the base map's units (Web Mercator 0..65536 = the world, see RadarMap) to view px
     * at zoom [z]: 65536 units = [worldSize].
     */
    fun mapScale(z: Int): Double = worldSize(z) / 65536.0

    /** Kilometres across a [w] px wide view at [lat] (for the scale label). */
    fun kmAcross(lat: Double, z: Int, w: Int): Double =
        w / worldSize(z) * 40_075.016686 * kotlin.math.cos(Math.toRadians(lat))
}
