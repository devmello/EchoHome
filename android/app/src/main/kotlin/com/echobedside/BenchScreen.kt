package com.echobedside

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.EchoColors
import com.echobedside.core.design.GlassLayout
import com.echobedside.core.design.GlassSlider
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.SpecText
import com.echobedside.core.model.EchoScreen

/**
 * Debug-only benchmark + glass check (`--es screen bench`): a wallpaper, its pre-blurred backdrop,
 * glass capsule / tinted circle / slider moving every frame, and 252 px text. Use it to measure
 * the Views + :core:design baseline with ./scripts/echo-perf.sh.
 */
@SuppressLint("ViewConstructor")
class BenchScreen(context: Context) : FrameLayout(context), EchoScreen {
    private val wallpaper: Bitmap = makeWallpaper()
    private val backdrop: Backdrop = PreBlur.backdrop(wallpaper, 960f, 480f)
    private val text = SpecText(252f, 600, -0.045f)
    private val capsule = GlassLayout(context).apply { backdrop = this@BenchScreen.backdrop }
    private val tinted = GlassLayout(context).apply { backdrop = this@BenchScreen.backdrop; tint = EchoColors.ORANGE }
    private val slider = GlassSlider(context, vertical = true).apply { glass.backdrop = this@BenchScreen.backdrop; value = 0.6f }
    private var t = 0f
    private var anim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        addView(capsule, LayoutParams(360, 88).apply { leftMargin = 60; topMargin = 40 })
        addView(tinted, LayoutParams(96, 96).apply { leftMargin = 480; topMargin = 340 })
        addView(slider, LayoutParams(96, 300).apply { leftMargin = 820; topMargin = 90 })
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(wallpaper, 0f, 0f, null)
        text.draw(canvas, "9:41", 40f + 120f * t, 330f, Color.WHITE)
    }

    override fun onScreenShown(args: Bundle?) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                t = it.animatedValue as Float
                capsule.translationX = 300f * t
                tinted.translationY = -200f * t
                slider.value = 0.2f + 0.7f * t
                invalidate()
            }
            start()
        }
    }

    override fun onScreenHidden() { anim?.cancel(); anim = null }

    private fun makeWallpaper(): Bitmap {
        val b = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 960f, 480f, 0xFF0B2329.toInt(), 0xFF140726.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 960f, 480f, p)
        for ((x, y, r, col) in listOf(Quad(220f, 140f, 260f, 0xFFE0784C), Quad(700f, 330f, 300f, 0xFF1E6A78), Quad(560f, 90f, 180f, 0xFFE8418C))) {
            p.shader = RadialGradient(x, y, r, col.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        return b
    }

    private data class Quad(val x: Float, val y: Float, val r: Float, val c: Long)
}

/** Registers debug-only screens. */
internal fun EchoApp.registerDebugScreens() {
    if (!BuildConfig.DEBUG) return
    registerScreen(com.echobedside.core.model.ScreenSpec("bench", order = 1000, inPager = false) { BenchScreen(it) })
}
