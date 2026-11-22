package com.echobedside.feature.camera.capture

import java.nio.ByteBuffer

/**
 * A grey (Y / luma) plane, valid **only during the callback** that received it: the buffer
 * belongs to the camera and is reused. Copy or downscale what you need, never keep a reference.
 * Nothing here is ever written to disk (NOTES §6.6 privacy).
 */
class YPlane(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val timestampNanos: Long,
    /**
     * Where NV21 chroma (interleaved V,U, one pair per 2×2 block, rows of [rowStride] bytes) starts in
     * [buffer], or -1 when only luma is available (then colour reads come back grey).
     */
    val uvOffset: Int = -1,
) {
    /** Luma at (x, y), 0..255. */
    fun at(x: Int, y: Int): Int = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF

    /** 0xRRGGBB at (x, y): NV21 → RGB (BT.601 full range, as camera JPEG/NV21 is), or grey. */
    fun rgb(x: Int, y: Int): Int {
        val yy = at(x, y)
        if (uvOffset < 0) return (yy shl 16) or (yy shl 8) or yy
        val i = uvOffset + (y shr 1) * rowStride + (x and 1.inv())
        val v = (buffer.get(i).toInt() and 0xFF) - 128
        val u = (buffer.get(i + 1).toInt() and 0xFF) - 128
        val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (yy - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
        val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}

/** Receives frames on the camera's background thread. */
fun interface FrameListener {
    fun onFrame(frame: YPlane)
}

/**
 * The camera, as a source of low-res grey frames. One implementation per camera API
 * (Camera2 first, Camera1 as the fallback; NOTES §6.5 asks which is cheaper on this HAL).
 *
 * Implementations deliver at most one frame per [minIntervalMs] to the listener and drop the rest
 * as early as possible (before touching pixels).
 */
interface FrameSource {
    val name: String
    /** Preview size actually in use, once started. */
    val size: Pair<Int, Int>?
    /** Frames the camera produced (delivered or dropped), for measurements. */
    val framesSeen: Long
    /** Throttle; can change while running (presence 2 fps → detection burst 5 fps). */
    var minIntervalMs: Long

    /**
     * Opens the camera and starts streaming. [onError] is called (on the camera thread) if the
     * camera can't be opened or fails later; the source is then stopped.
     */
    fun start(listener: FrameListener, onError: (String) -> Unit)

    /** Stops streaming and releases the camera. Safe to call twice. Blocks until released. */
    fun stop()
}

/** Picks the cheapest preview size: the smallest area whose width is at least [minWidth]. */
fun pickSmallest(sizes: List<Pair<Int, Int>>, minWidth: Int = 160): Pair<Int, Int>? =
    sizes.filter { it.first >= minWidth }.minByOrNull { it.first * it.second }
        ?: sizes.maxByOrNull { it.first * it.second }

/** Picks the fps range with the lowest maximum (then lowest minimum): fewer frames = less CPU. */
fun pickLowestFps(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
    ranges.minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
