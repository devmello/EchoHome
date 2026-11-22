package app.vesper

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import app.vesper.core.design.Motion
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal pager of full-screen pages (the launcher's screens). Pages are created lazily on
 * first visit and kept. Only a clear horizontal drag is intercepted, so vertical gestures
 * (face switching, Control Center pull-down, sliders) reach the pages.
 */
@SuppressLint("ViewConstructor")
class ScreenPager(context: Context, private val pageCount: Int, private val createPage: (Int) -> View) : ViewGroup(context) {

    var onPageSelected: ((Int) -> Unit)? = null
    var currentPage = 0
        private set

    private val pages = arrayOfNulls<View>(pageCount)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    init { ensurePage(0) }

    private fun ensurePage(i: Int): View? {
        if (i !in 0 until pageCount) return null
        pages[i]?.let { return it }
        val v = createPage(i)
        pages[i] = v
        addView(v)
        return v
    }

    fun page(i: Int): View? = pages.getOrNull(i)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val exact = { s: Int -> MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) getChildAt(i).measure(exact(w), exact(h))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        pages.forEachIndexed { i, v -> v?.layout(i * w, 0, (i + 1) * w, b - t) }
        if (anim == null && !dragging) scrollTo(currentPage * w, 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (pageCount > 1 && dx > slop && dx > dy * 1.5f) {
                    dragging = true
                    anim?.cancel(); anim = null
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // neighbours must exist while dragging
                    ensurePage(currentPage - 1); ensurePage(currentPage + 1)
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return onInterceptTouchEvent(e) || true
                val max = (pageCount - 1) * width
                var nx = scrollX + (lastX - e.x).roundToInt()
                // rubber-band past the ends
                if (nx < 0 || nx > max) nx = scrollX + ((lastX - e.x) / 3f).roundToInt()
                scrollTo(nx, 0)
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    val vx = velocity!!.xVelocity
                    val target = when {
                        vx < -minFling -> currentPage + 1
                        vx > minFling -> currentPage - 1
                        else -> ((scrollX.toFloat() / width).roundToInt())
                    }
                    scrollToPage(target, animate = true)
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    fun scrollToPage(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, pageCount - 1)
        ensurePage(target)
        val changed = target != currentPage
        currentPage = target
        anim?.cancel()
        val to = target * width
        if (!animate || width == 0) {
            scrollTo(to, 0)
            anim = null
        } else {
            anim = ValueAnimator.ofInt(scrollX, to).apply {
                duration = 380
                interpolator = Motion.EASE
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
        if (changed) onPageSelected?.invoke(target)
    }
}
