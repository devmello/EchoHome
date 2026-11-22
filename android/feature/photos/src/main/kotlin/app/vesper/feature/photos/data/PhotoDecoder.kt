package app.vesper.feature.photos.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import app.vesper.core.design.PreBlur
import java.io.File
import java.io.IOException

/**
 * Decodes a cached preview into one screen-sized frame (960×480, see [DecodeMath]). Runs on a
 * background thread. The intermediate decode uses `inSampleSize` and is recycled at once, so a
 * frame costs 1.8 MB (ARGB_8888: RGB_565 bands visibly in skies and the scrim gradients).
 */
object PhotoDecoder {
    const val FRAME_W = 960
    const val FRAME_H = 480

    fun decodeFrame(file: File, w: Int = FRAME_W, h: Int = FRAME_H): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw BadImageException("not an image")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = DecodeMath.inSampleSize(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeFile(file.path, opts) ?: throw BadImageException("decode failed")
        try {
            return compose(src, w, h)
        } finally {
            src.recycle()
        }
    }

    /** Lays [src] out on a new [w]×[h] frame: cover-crop, or fitted over a blurred fill. */
    fun compose(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val cover = DecodeMath.coverSource(src.width, src.height, w, h, biasTop = 0.5f)
        if (DecodeMath.fits(src.width, src.height)) {
            // Fill: the photo itself, cover-cropped, tiny, blurred and darkened.
            val small = Bitmap.createBitmap(w / 8, h / 8, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(src, cover.toAndroid(), Rect(0, 0, small.width, small.height), paint)
            val fill = PreBlur.blur(small, blurPx = 4f, downscale = 1, saturate = 1.1f, brightness = 0.55f)
            small.recycle()
            c.drawBitmap(fill, null, Rect(0, 0, w, h), paint)
            fill.recycle()
            val d = DecodeMath.fitDest(src.width, src.height, w, h)
            c.drawBitmap(src, null, d.toAndroid(), paint)
        } else {
            val s = DecodeMath.coverSource(src.width, src.height, w, h)
            c.drawBitmap(src, s.toAndroid(), Rect(0, 0, w, h), paint)
        }
        return out
    }

    private fun DecodeMath.Rect.toAndroid() = Rect(left, top, right, bottom)
}
