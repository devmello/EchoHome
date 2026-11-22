package app.vesper.feature.camera.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 low-res YUV_420_888 preview into an [ImageReader]; only the Y plane is read.
 * Uses the lowest AE fps range the HAL offers so the sensor itself produces fewer frames.
 */
class Camera2Source(
    context: Context,
    private val preferredWidth: Int = 160,
    override var minIntervalMs: Long = 500,
) : FrameSource {
    override val name = "camera2"
    override var size: Pair<Int, Int>? = null; private set
    @Volatile override var framesSeen = 0L; private set
    /** What the HAL offered, for the status report. */
    var offeredSizes: List<Pair<Int, Int>> = emptyList(); private set
    var offeredFps: List<Pair<Int, Int>> = emptyList(); private set
    var fpsRange: Pair<Int, Int>? = null; private set
    var hardwareLevel: Int = -1; private set

    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastDelivered = 0L
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun start(listener: FrameListener, onError: (String) -> Unit) {
        if (running) return
        val id = try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Exception) { onError("cameraIdList: $e"); return } ?: run { onError("no camera"); return }

        val ch = manager.getCameraCharacteristics(id)
        hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        offeredSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }.orEmpty()
        offeredFps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty()
        val sz = pickSmallest(offeredSizes, preferredWidth) ?: run { onError("no YUV sizes"); return }
        size = sz
        fpsRange = pickLowestFps(offeredFps)

        val t = HandlerThread("echo-cam2").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true
        lastDelivered = 0L

        val r = ImageReader.newInstance(sz.first, sz.second, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                framesSeen++
                val now = SystemClock.elapsedRealtime()
                if (running && now - lastDelivered >= minIntervalMs) {
                    lastDelivered = now
                    val p = img.planes[0]
                    listener.onFrame(YPlane(p.buffer, img.width, img.height, p.rowStride, p.pixelStride, img.timestamp))
                }
            } finally { img.close() }
        }, h)

        val fail = { msg: String -> if (running) { Log.w(TAG, msg); stopInternal(); onError(msg) } }
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (!running) { cd.close(); return }
                    device = cd
                    try {
                        @Suppress("DEPRECATION")
                        cd.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!running) { s.close(); return }
                                session = s
                                try {
                                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(r.surface)
                                        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
                                    }
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) { fail("setRepeatingRequest: $e") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = fail("session configure failed")
                        }, h)
                    } catch (e: Exception) { fail("createCaptureSession: $e") }
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); fail("disconnected") }
                override fun onError(cd: CameraDevice, error: Int) { cd.close(); fail("device error $error") }
            }, h)
        } catch (e: Exception) { fail("openCamera: $e") }
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
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
    }

    companion object { const val TAG = "VesperCam2" }
}
