package com.echobedside.feature.camera.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import com.echobedside.feature.camera.capture.YPlane
import java.nio.ShortBuffer

/**
 * `android.media.FaceDetector` (built in, no model file, no licence question). It needs an
 * RGB_565 bitmap with an even width, so the Y plane is written as grey RGB_565 into a reused
 * bitmap. Finds eye midpoint + eye distance + confidence; no landmarks, no pose (pose is 0).
 */
class LegacyFaceFinder(private val maxFaces: Int = 2) : FaceFinder {
    override val name = "android.media.FaceDetector"
    private var w = 0
    private var h = 0
    private var bitmap: Bitmap? = null
    private var pixels: ShortBuffer? = null
    private var detector: FaceDetector? = null
    private val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
    private val mid = PointF()

    override fun find(frame: YPlane): List<FaceBox> {
        val fw = frame.width and 1.inv()
        val fh = frame.height
        if (fw != w || fh != h) setup(fw, fh)
        val px = pixels!!
        px.clear()
        val buf = frame.buffer
        val rs = frame.rowStride
        val ps = frame.pixelStride
        for (y in 0 until fh) {
            val row = y * rs
            for (x in 0 until fw) {
                val v = buf.get(row + x * ps).toInt() and 0xFF
                px.put((((v shr 3) shl 11) or ((v shr 2) shl 5) or (v shr 3)).toShort())
            }
        }
        px.rewind()
        val bm = bitmap!!
        bm.copyPixelsFromBuffer(px)
        val n = detector!!.findFaces(bm, faces)
        val out = ArrayList<FaceBox>(n)
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            f.getMidPoint(mid)
            FaceBox(mid.x, mid.y, f.eyesDistance(), f.confidence()).sane(fw, fh)?.let { out += it }
            faces[i] = null
        }
        out.sortByDescending { it.eyeDist }
        return out
    }

    private fun setup(fw: Int, fh: Int) {
        close()
        w = fw; h = fh
        bitmap = Bitmap.createBitmap(fw, fh, Bitmap.Config.RGB_565)
        pixels = ShortBuffer.allocate(fw * fh)
        detector = FaceDetector(fw, fh, maxFaces)
    }

    override fun close() {
        bitmap?.recycle(); bitmap = null
        pixels = null; detector = null
        w = 0; h = 0
    }
}
