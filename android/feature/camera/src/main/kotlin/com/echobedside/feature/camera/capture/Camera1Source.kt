@file:Suppress("DEPRECATION")

package com.echobedside.feature.camera.capture

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Legacy Camera1 preview (NV21; the first width×height bytes are the Y plane), with a reused
 * callback buffer so nothing is allocated per frame. Fallback for the Android 7-era HAL.
 */
class Camera1Source(
    private val preferredWidth: Int = 160,
    override var minIntervalMs: Long = 500,
) : FrameSource {
    override val name = "camera1"
    override var size: Pair<Int, Int>? = null; private set
    @Volatile override var framesSeen = 0L; private set
    var offeredSizes: List<Pair<Int, Int>> = emptyList(); private set
    var offeredFps: List<Pair<Int, Int>> = emptyList(); private set
    var fpsRange: Pair<Int, Int>? = null; private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var lastDelivered = 0L
    @Volatile private var running = false

    override fun start(listener: FrameListener, onError: (String) -> Unit) {
        if (running) return
        val t = HandlerThread("echo-cam1").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true
        lastDelivered = 0L
        // Camera1 delivers callbacks on the looper of the thread that opened it.
        h.post {
            try {
                val n = Camera.getNumberOfCameras()
                if (n == 0) throw IllegalStateException("no camera")
                var id = 0
                val info = Camera.CameraInfo()
                for (i in 0 until n) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break } }
                val cam = Camera.open(id)
                camera = cam
                cam.setErrorCallback { err, _ -> Log.w(TAG, "camera error $err"); if (running) { stopInternal(); onError("camera error $err") } }
                val p = cam.parameters
                offeredSizes = p.supportedPreviewSizes.map { it.width to it.height }
                offeredFps = p.supportedPreviewFpsRange.map { it[0] to it[1] }
                val sz = pickSmallest(offeredSizes, preferredWidth) ?: throw IllegalStateException("no preview sizes")
                size = sz
                p.setPreviewSize(sz.first, sz.second)
                p.previewFormat = ImageFormat.NV21
                pickLowestFps(offeredFps)?.let { fpsRange = it; p.setPreviewFpsRange(it.first, it.second) }
                cam.parameters = p
                // A preview target is mandatory; an unattached SurfaceTexture is never drawn.
                val st = SurfaceTexture(0).also { texture = it }
                cam.setPreviewTexture(st)
                val bufSize = sz.first * sz.second * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
                val buf = ByteArray(bufSize)
                val wrap = ByteBuffer.wrap(buf)
                cam.addCallbackBuffer(buf)
                cam.setPreviewCallbackWithBuffer { data, c ->
                    framesSeen++
                    val now = SystemClock.elapsedRealtime()
                    if (running && now - lastDelivered >= minIntervalMs) {
                        lastDelivered = now
                        listener.onFrame(YPlane(wrap, sz.first, sz.second, sz.first, 1, SystemClock.elapsedRealtimeNanos()))
                    }
                    if (running) c.addCallbackBuffer(data)
                }
                cam.startPreview()
            } catch (e: Exception) {
                Log.w(TAG, "open failed", e)
                stopInternal()
                onError("camera1: $e")
            }
        }
    }

    override fun stop() {
        if (!running && thread == null) return
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        val h = handler
        val t = thread
        if (h != null && t != null && t.isAlive) {
            if (t.looper.thread == Thread.currentThread()) closeAll()
            else {
                val done = CountDownLatch(1)
                h.post { closeAll(); done.countDown() }
                done.await(2, TimeUnit.SECONDS)
            }
            t.quitSafely()
        } else closeAll()
        thread = null; handler = null
    }

    private fun closeAll() {
        camera?.let { c ->
            try { c.setPreviewCallbackWithBuffer(null) } catch (_: Exception) {}
            try { c.stopPreview() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        camera = null
        texture?.release(); texture = null
    }

    companion object { const val TAG = "EchoCam1" }
}
