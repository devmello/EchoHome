package app.vesper.feature.home.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the radar loop for one view (centre + zoom) while the radar screen is shown, and nothing
 * otherwise: the manifest, then for each of the last hour's frames (≤ [MAX_FRAMES]) its tiles,
 * composited into one half-size frame bitmap ([FRAME_W]×[FRAME_H], drawn ×2: the radar is smoothed
 * anyway). Newest frame first, so something shows quickly. Refreshes the manifest every
 * [REFRESH_MS] and only fetches frames it doesn't have. PNGs stay in [tiles] (≤ [TILE_CACHE_BYTES]);
 * frames + tiles ≤ 8 MB. [hide] cancels everything; [trim] drops the memory.
 */
class RadarFrames(
    private val client: RainViewerClient,
    private val scope: CoroutineScope,
    private val sampleWhenOffline: Boolean,
) {
    data class Target(val lat: Double, val lon: Double, val zoom: Int)

    class Frame(val time: Long, val bitmap: Bitmap, val sample: Boolean = false)

    enum class Status { LOADING, READY, OFFLINE, SAMPLE }

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    /** Oldest first. */
    val frames: StateFlow<List<Frame>> = _frames.asStateFlow()
    private val _status = MutableStateFlow(Status.LOADING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val tiles = TileCache(TILE_CACHE_BYTES)
    private var job: Job? = null
    private var builtFor: Target? = null

    fun show(t: Target) {
        if (job?.isActive == true && builtFor == t) return
        job?.cancel()
        if (builtFor != t) { _frames.value = emptyList(); _status.value = Status.LOADING }
        builtFor = t
        job = scope.launch {
            while (isActive) {
                try {
                    refresh(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.i(TAG, "radar: ${e.message}")
                    if (_frames.value.isEmpty()) {
                        if (sampleWhenOffline) { _frames.value = withContext(Dispatchers.Default) { sampleFrames(t) }; _status.value = Status.SAMPLE }
                        else _status.value = Status.OFFLINE
                    }
                }
                delay(if (_status.value == Status.READY) REFRESH_MS else RETRY_MS)
            }
        }
    }

    fun hide() { job?.cancel(); job = null }

    /** Frees the frames and tiles (the screen has been hidden for a while). */
    fun trim() {
        hide()
        _frames.value = emptyList()
        builtFor = null
        tiles.clear()
    }

    private suspend fun refresh(t: Target) {
        val manifest = client.manifest()
        val wanted = RainViewer.lastHour(manifest, max = MAX_FRAMES)
        if (wanted.isEmpty()) throw IOException("no radar frames")
        val have = _frames.value.filter { !it.sample }.associateBy { it.time }
        val refs = TileMath.tilesFor(t.lat, t.lon, t.zoom, VIEW_W, VIEW_H)
        var list = _frames.value.filter { f -> !f.sample && wanted.any { it.time == f.time } }
        _frames.value = list
        // newest first so the screen has the current picture as soon as possible
        for (frame in wanted.reversed()) {
            if (have.containsKey(frame.time)) continue
            val bmp = compose(manifest.host, frame, refs)
            list = (list + Frame(frame.time, bmp)).sortedBy { it.time }
            _frames.value = list
            _status.value = Status.READY
        }
        if (list.isNotEmpty()) _status.value = Status.READY
    }

    private suspend fun compose(host: String, frame: RainViewer.Frame, refs: List<TileMath.TileRef>): Bitmap {
        val out = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (r in refs) {
            val url = RainViewer.tileUrl(host, frame, r.z, r.x, r.y)
            val bytes = tiles.get(url) ?: client.tile(url)?.also { tiles.put(url, it) } ?: continue
            withContext(Dispatchers.Default) {
                val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = SCALE }) ?: return@withContext
                c.drawBitmap(tile, (r.left / SCALE).toFloat(), (r.top / SCALE).toFloat(), paint)
                tile.recycle()
            }
        }
        return out
    }

    /** Demo / offline review: a few rain cells drifting east over the last hour (sample data). */
    private fun sampleFrames(t: Target): List<Frame> {
        val now = System.currentTimeMillis() / 1000 / 600 * 600
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cells = listOf(
            floatArrayOf(0.30f, 0.45f, 70f, 0.9f), floatArrayOf(0.42f, 0.62f, 46f, 0.7f),
            floatArrayOf(0.62f, 0.30f, 58f, 0.8f), floatArrayOf(0.18f, 0.70f, 36f, 0.6f),
        )
        return (0 until MAX_FRAMES).map { i ->
            val b = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            for ((k, cell) in cells.withIndex()) {
                val drift = i * 7f
                val cx = cell[0] * FRAME_W + drift + 6f * sin(i * 0.7f + k)
                val cy = cell[1] * FRAME_H + 4f * cos(i * 0.5f + k)
                val r = cell[2] * (0.9f + 0.1f * sin(i + k.toFloat()))
                p.shader = RadialGradient(cx, cy, r, intArrayOf(0xE0FFD24D.toInt(), 0xC04DA3FF.toInt(), 0x802E6BD9.toInt(), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
            }
            Frame(now - (MAX_FRAMES - 1 - i) * 600L, b, sample = true)
        }
    }

    companion object {
        const val TAG = "VesperRadar"
        const val VIEW_W = 960
        const val VIEW_H = 480
        const val SCALE = 2
        const val FRAME_W = VIEW_W / SCALE
        const val FRAME_H = VIEW_H / SCALE
        /** The last hour at RainViewer's 10-minute steps: −60 … 0 min. */
        const val MAX_FRAMES = 7
        const val FRAME_BYTES = FRAME_W * FRAME_H * 4
        /** 7 frames × 450 KB + 4 MB of PNGs < 8 MB. */
        const val TILE_CACHE_BYTES = 4 * 1024 * 1024
        const val REFRESH_MS = 5 * 60_000L
        const val RETRY_MS = 60_000L
    }
}
