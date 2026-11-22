package com.echobedside.feature.home.doorbell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/** The Island.dc.html doorbell thumbnail (door, porch light) as a JPEG, for the demo camera. 2× the 230×160 thumbnail. */
object DemoSnapshot {
    fun jpeg(): ByteArray {
        val w = 460; val h = 320; val k = 2f
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF2B3340.toInt(), 0xFF1A1F28.toInt(), 0xFF3A3229.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = LinearGradient(0f, 22 * k, 0f, 152 * k, 0xFF5A4636.toInt(), 0xFF3B2D22.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(86 * k, 22 * k, 144 * k, 152 * k), 6 * k, 6 * k, p)
        p.shader = RadialGradient(157 * k, 47 * k, 30 * k, intArrayOf(0x8CFFC86E.toInt(), 0x00FFC86E), null, Shader.TileMode.CLAMP)
        c.drawCircle(157 * k, 47 * k, 30 * k, p)
        p.shader = null; p.color = 0xFFFFD27A.toInt()
        c.drawCircle(157 * k, 47 * k, 7 * k, p)
        p.shader = LinearGradient(0f, h - 30 * k, 0f, h.toFloat(), 0xFF4A4036.toInt(), 0xFF2C251F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, h - 30 * k, w.toFloat(), h.toFloat(), p)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        b.recycle()
        return out.toByteArray()
    }
}
