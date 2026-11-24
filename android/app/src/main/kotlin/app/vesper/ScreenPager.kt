package app.vesper

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import app.vesper.core.design.Motion
import app.vesper.core.design.QuietWork
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal pager of full-screen pages (the launcher's screens). Pages are created lazily and
 * kept. Only a clear horizontal drag is intercepted, so vertical gestures (face switching,
 * Control Center pull-down, sliders) reach the pages.
 *
 * The current page's neighbours are built ahead, on quiet moments ([QuietWork]) once the pager
 * has rested [WARM_DELAY_MS] after attaching, and their display lists are recorded while they're
 * still off screen ([prerender]). Built on the first drag instead, the Widgets page cost the
 * first StandBy → Widgets swipe ~160 ms of construction plus ~50 ms of recording (34-40 % janky
 * frames over the first swipes after a start, against ~4 % later).
 *
 * Pages are keyed (the screen ids). [setPages] changes the list live (the user's Screens setting):
 * pages that stay keep their views, the ones that leave are removed and freed, and new ones are
 * built ahead on quiet moments like any neighbour.
 */
@SuppressLint("ViewConstructor")
class ScreenPager(context: Context, keys: List<String>, private val createPage: (String) -> View) : ViewGroup(context) {

    var onPageSelected: ((Int) -> Unit)? = null
    var currentPage = 0
        private set

    private var keys: List<String> = keys.ifEmpty { listOf("") }
    val pageCount: Int get() = keys.size
    private val pages = HashMap<String, View>()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    /** Pages that left the list ([setPages]): invisible, removed one per quiet moment ([warmStep]). */
    private val retired = LinkedHashMap<String, View>()
    private val prerendered = HashSet<String>()
    private val lastPrerender = HashMap<String, Long>()
    private val scratch = RenderNode("pager-prerender")
    private var warmAllowed = false
    private val allowWarm = Runnable { warmAllowed = true; warm.start() }
    private val warm = QuietWork { warmStep() }
    private val rewarm = Runnable { scheduleWarm() }

    init { ensurePage(0) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // not during startup: the first StandBy frames and the features' own start come first
        if (!warmAllowed) postDelayed(allowWarm, WARM_DELAY_MS) else warm.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(allowWarm); removeCallbacks(rewarm)
        warm.stop()
        super.onDetachedFromWindow()
    }

    private fun scheduleWarm() { if (warmAllowed && isAttachedToWindow) warm.start() }

    /**
     * One unit of ahead-of-time work per quiet moment, only while the pager rests: build a
     * missing neighbour of the current page, or record a neighbour that changed since it was last
     * recorded. Returns false when there's nothing left (restarted by [scheduleWarm]).
     */
    private fun warmStep(): Boolean {
        if (dragging || anim != null || width == 0) return false // [scrollToPage] restarts it at rest
        retired.keys.firstOrNull()?.let { removeView(retired.remove(it)); return true }
        val now = SystemClock.uptimeMillis()
        var later = Long.MAX_VALUE
        for (i in intArrayOf(currentPage + 1, currentPage - 1)) {
            val key = keys.getOrNull(i) ?: continue
            val p = pages[key]
            if (p == null) { ensurePage(i); return true }
            if (p.isLayoutRequested || !p.isLaidOut) return false // the next traversal lays it out; [onLayout] restarts this
            if (key in prerendered && !p.isDirty) continue
            val wait = (lastPrerender[key] ?: 0L) + PRERENDER_EVERY_MS - now
            if (wait > 0) { later = minOf(later, wait); continue }
            prerender(p)
            prerendered += key
            lastPrerender[key] = now
            return true
        }
        if (later != Long.MAX_VALUE) { removeCallbacks(rewarm); postDelayed(rewarm, later) }
        return false
    }

    /**
     * Records [page]'s views into their display lists while it's off screen (the pager's own
     * draw skips it there): drawing it into a scratch [RenderNode] makes every child view record
     * its own node, which the first frame of a swipe onto the page then only replays.
     */
    private fun prerender(page: View) {
        scratch.setPosition(0, 0, page.width, page.height)
        val c = scratch.beginRecording(page.width, page.height)
        try { page.draw(c) } finally { scratch.endRecording(); scratch.discardDisplayList() }
    }

    /** A neighbour changed while off screen (e.g. a widget built ahead): record it again on a quiet moment. */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (child !== page(currentPage) && !warm.isRunning) scheduleWarm()
    }

    private fun ensurePage(i: Int): View? {
        val key = keys.getOrNull(i) ?: return null
        pages[key]?.let { return it }
        retired.remove(key)?.let { it.visibility = VISIBLE; pages[key] = it; return it } // back before it was freed
        val v = createPage(key)
        pages[key] = v
        addView(v)
        return v
    }

    fun page(i: Int): View? = keys.getOrNull(i)?.let { pages[it] }

    /**
     * Replaces the page list, showing page [current] of [newKeys] without animation. Pages that
     * stay keep their views (the current one isn't rebuilt or re-recorded, only moved); pages that
     * left turn invisible at once and are removed (freed) on quiet moments. The caller tells
     * screens they were hidden / shown.
     */
    fun setPages(newKeys: List<String>, current: Int) {
        val next = newKeys.ifEmpty { listOf("") }
        if (next == keys) return
        val keep = next.toSet()
        for (k in pages.keys - keep) {
            // Detaching a big page (Widgets) costs a frame: hide it now, remove it on a quiet moment.
            val v = pages.remove(k) ?: continue
            v.visibility = INVISIBLE
            retired[k] = v
            prerendered -= k; lastPrerender -= k
        }
        keys = next
        anim?.cancel(); anim = null
        dragging = false
        currentPage = current.coerceIn(0, pageCount - 1)
        ensurePage(currentPage)
        requestLayout() // [onLayout] places the pages at their new indices and scrolls to [currentPage]
        scheduleWarm()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val exact = { s: Int -> MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) getChildAt(i).measure(exact(w), exact(h))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        keys.forEachIndexed { i, k -> pages[k]?.layout(i * w, 0, (i + 1) * w, b - t) }
        if (anim == null && !dragging) scrollTo(currentPage * w, 0)
        scheduleWarm()
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
            scheduleWarm()
        } else {
            anim = ValueAnimator.ofInt(scrollX, to).apply {
                duration = 380
                interpolator = Motion.EASE
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { if (anim === a) { anim = null; scheduleWarm() } }
                })
                start()
            }
        }
        if (changed) onPageSelected?.invoke(target)
    }

    private companion object {
        /** Ahead-of-time page work waits this long after the pager attaches (startup). */
        const val WARM_DELAY_MS = 2_500L
        /** At most this often per page, in case a hidden page keeps invalidating. */
        const val PRERENDER_EVERY_MS = 1_000L
    }
}
