package com.echobedside.feature.photos.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.GlassLayout
import com.echobedside.core.design.GlassTokens
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.photos.data.Album
import com.echobedside.feature.photos.data.BadImageException
import com.echobedside.feature.photos.data.Captions
import com.echobedside.feature.photos.data.DemoRepository
import com.echobedside.feature.photos.data.ImmichHttpException
import com.echobedside.feature.photos.data.ImmichRepository
import com.echobedside.feature.photos.data.PhotoAsset
import com.echobedside.feature.photos.data.PhotoRepository
import com.echobedside.feature.photos.data.Slideshow
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photo frame (Photos.dc.html): full-bleed photos from an Immich album with a 1.4 s
 * crossfade and a 9 s slow zoom (to 1.08 around a per-photo origin), the time over the photo,
 * a memory caption, and glass controls (album, pause, next) that lift in on tap and settle away
 * after 4 s.
 *
 * Cost rules (docs/architecture.md): at most the current and the next photo are decoded
 * (960×480); fades and zoom are View alpha/scale only; the text bands are hardware layers;
 * the glass samples a pre-blurred copy of the photo made once per photo off the main thread;
 * nothing runs while the screen is hidden.
 *
 * Debug args (`--es screen photos --es KEY VALUE`): `album NAME`, `seconds N`, `controls 1`
 * (show and keep the controls), `sheet 1` (open the album picker), `paused 1`, `night 1|0`.
 */
@SuppressLint("ViewConstructor")
class PhotosScreen(context: Context, private val services: EchoServices) : FrameLayout(context), EchoScreen {

    private val repo: PhotoRepository? = createRepository(context, services)
    private val settings get() = services.settings
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    // Views, bottom to top. layers[1] always holds the current photo; layers[0] the outgoing one.
    private val bottom = PhotoLayer(context)
    private val top = PhotoLayer(context)
    private val overlay = FrameOverlay(context, FrameOverlay.Part.TOP)
    private val caption = FrameOverlay(context, FrameOverlay.Part.BOTTOM)
    private val controlsBox = FrameLayout(context)
    private val capsule = GlassLayout(context)
    private val bar = ControlsBar(context)
    private val sheetBox = FrameLayout(context)
    private val sheet = GlassLayout(context)
    private val albumList = AlbumList(context)

    private class Frame(val asset: PhotoAsset, val bitmap: Bitmap, val blur: Bitmap, val pivot: Pair<Float, Float>) {
        fun recycle() { bitmap.recycle(); blur.recycle() }
    }

    private val show = Slideshow()
    private var albums: List<Album> = emptyList()
    private var album: Album? = null
    private var offline = false
    private var albumsOffline = false
    /** A quiet line shown in place of the caption when there's nothing to show. */
    private var status: String? = null

    private var current: Frame? = null
    private var outgoing: Frame? = null
    private var prepared: Frame? = null
    private var prefetchJob: Job? = null
    private var loadJob: Job? = null
    private var advancePending = false
    private var started = false
    private var shown = false
    private var lastRefresh = 0L

    private var fade: ValueAnimator? = null
    private var zoom: ObjectAnimator? = null
    private var controlsVisible = false
    private var keepControls = false
    private var controlsAnim: Animator? = null
    private var sheetOpen = false
    private var sheetAnim: Animator? = null
    private var openSheetWhenLoaded = false

    private var night = false
    private var nightDim = Night.DIM
    private var dimAnim: ValueAnimator? = null

    private var retry: (() -> Unit)? = null
    private var retryMs = RETRY_MIN_MS

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    init {
        // No background: the window is already black (one full-screen fill less per frame).
        clipChildren = false
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        for (l in listOf(bottom, top)) { l.alpha = 0f; addView(l, LayoutParams(match)) }
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, FrameOverlay.Part.TOP.height))
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, FrameOverlay.Part.BOTTOM.height).apply { topMargin = FrameOverlay.Part.BOTTOM.top })

        // Controls: right 28, bottom 26, inside a padded box so the lift shadow fades with them.
        capsule.cornerRadius = -1f
        capsule.tint = withAlpha(Color.WHITE, 0.12f) // the design's capsule fill (rgba 255,255,255,.12)
        capsule.addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, ControlsBar.HEIGHT))
        controlsBox.setPadding(SHADOW_PAD, SHADOW_PAD, SHADOW_PAD, SHADOW_PAD)
        controlsBox.clipChildren = false
        controlsBox.clipToPadding = false // the glass draws its lift shadow outside its bounds
        controlsBox.addView(capsule, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        controlsBox.visibility = INVISIBLE
        controlsBox.alpha = 0f
        addView(controlsBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 28 - SHADOW_PAD; bottomMargin = 26 - SHADOW_PAD
        })
        bar.onAlbum = { if (sheetOpen) closeSheet() else openSheet() }
        bar.onPause = { togglePause() }
        bar.onNext = { next() }

        // Album picker: pops out of the album button, above the capsule.
        sheet.cornerRadius = AlbumList.RADIUS
        sheet.tint = withAlpha(Color.WHITE, 0.12f)
        sheet.addView(albumList, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sheetBox.setPadding(SHADOW_PAD, SHADOW_PAD, SHADOW_PAD, SHADOW_PAD)
        sheetBox.clipChildren = false
        sheetBox.clipToPadding = false
        sheetBox.addView(sheet, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sheetBox.visibility = INVISIBLE
        sheetBox.alpha = 0f
        addView(sheetBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 28 - SHADOW_PAD; bottomMargin = 26 + ControlsBar.HEIGHT + 10 - SHADOW_PAD
        })
        albumList.onPick = { pick(it) }

        show.seconds = settings.getInt(KEY_SECONDS, Slideshow.DEFAULT_SECONDS).coerceIn(3, 600)
        bar.albumName = settings.getString(KEY_ALBUM_NAME, if (repo is DemoRepository) "Family" else "Album")
        updateClock()
        updateCaption()
        // Collected only while attached (the helper restarts it on re-attach).
        observeWhileAttached(services.system.state.map { it.nightActive }.distinctUntilChanged()) { setNight(it) }
    }

    // --- lifecycle ---------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (!started) start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        current?.let { top.pivotX = it.pivot.first * w; top.pivotY = it.pivot.second * h }
    }

    override fun onDetachedFromWindow() {
        stopEverything()
        scope?.cancel(); scope = null
        started = false
        listOfNotNull(current, outgoing, prepared).forEach { it.recycle() }
        current = null; outgoing = null; prepared = null
        top.bitmap = null; bottom.bitmap = null
        show.clear()
        super.onDetachedFromWindow()
    }

    override fun onScreenShown(args: Bundle?) {
        val wasShown = shown
        shown = true
        args?.let { applyArgs(it) }
        if (wasShown) return
        updateClock()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, msToNextMinute())
        if (!started) start()
        val cur = current
        if (cur == null) {
            if (prepared != null) showPrepared()
            else advancePending = true
        } else {
            resumeZoom()
            if (show.due(now())) advancePending = true
            scheduleAdvance()
            prefetch()
        }
        retry?.let { handler.removeCallbacks(retryRunnable); handler.post(retryRunnable) }
        if (SystemClock.elapsedRealtime() - lastRefresh > REFRESH_MS && current != null) refreshAssets()
    }

    override fun onScreenHidden() {
        if (!shown) return
        shown = false
        stopEverything()
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        prefetchJob?.cancel(); prefetchJob = null
        fade?.end(); fade = null
        zoom?.cancel(); zoom = null
        dimAnim?.cancel(); dimAnim = null
        if (night) setDim(Night.DIM)
        hideControlsNow()
        advancePending = false
    }

    private fun applyArgs(args: Bundle) {
        args.getString("seconds")?.toIntOrNull()?.let { show.seconds = it.coerceIn(3, 600) }
        if (args.getString("paused") == "1") { show.paused = true; bar.paused = true }
        // Preview Night mode without the light sensor (normally SystemState.nightActive drives it).
        args.getString("night")?.let { setNight(it == "1") }
        args.getString("album")?.let { name ->
            val a = albums.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (a != null && a.id != album?.id) pick(a)
            else if (a == null) settings.putString(KEY_ALBUM_NAME, name).also { settings.remove(KEY_ALBUM_ID) }
        }
        if (args.getString("controls") == "1") { keepControls = true; wake() }
        if (args.getString("sheet") == "1") { keepControls = true; wake(); if (albums.isNotEmpty()) openSheet() else openSheetWhenLoaded = true }
    }

    // --- loading -----------------------------------------------------------------------------

    private fun start() {
        val r = repo
        if (r == null) { status = null; updateCaption(); return }
        val s = scope ?: return
        started = true
        loadJob?.cancel()
        loadJob = s.launch {
            try {
                val res = r.albums()
                albums = res.value
                albumsOffline = res.offline
                albumList.albums = albums
                val a = chooseAlbum(albums)
                if (a == null) { setStatus("No albums on ${r.sourceName}"); return@launch }
                setAlbum(a)
                loadAssets(r, a, showFirst = true)
                if (openSheetWhenLoaded) { openSheetWhenLoaded = false; openSheet() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "albums: ${e.javaClass.simpleName} ${e.message}")
                started = false
                setStatus(errorText(e))
                scheduleRetry { start() }
            }
        }
    }

    private fun chooseAlbum(list: List<Album>): Album? {
        val id = settings.getString(KEY_ALBUM_ID, "")
        val name = settings.getString(KEY_ALBUM_NAME, "")
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull { name.isNotEmpty() && it.name.equals(name, ignoreCase = true) }
            ?: list.firstOrNull { it.name.equals("Family", ignoreCase = true) }
            ?: list.maxByOrNull { it.assetCount }
    }

    private fun setAlbum(a: Album) {
        album = a
        bar.albumName = a.name
        albumList.selectedId = a.id
    }

    /** Loads [a]'s assets; [showFirst] shows a photo as soon as one is ready (start / album switch). */
    private suspend fun loadAssets(r: PhotoRepository, a: Album, showFirst: Boolean) {
        val res = r.assets(a)
        lastRefresh = SystemClock.elapsedRealtime()
        setOffline(res.offline)
        show.setAssets(res.value, shuffle = r.shuffle)
        retry = null; retryMs = RETRY_MIN_MS
        when {
            res.value.isEmpty() -> setStatus("No photos in ${a.name}")
            show.next() == null && current == null -> setStatus("Offline · no saved photos yet")
            else -> {
                status = null
                updateCaption()
                if (showFirst) advancePending = true
                if (prepared?.asset?.id != show.next()?.id) { prepared?.recycle(); prepared = null }
                prefetch()
            }
        }
    }

    /** Reloads the album (new photos, back online) and keeps the current photo up. */
    private fun refreshAssets() {
        val r = repo ?: return
        val a = album ?: return
        val s = scope ?: return
        if (loadJob?.isActive == true) return
        loadJob = s.launch {
            try {
                loadAssets(r, a, showFirst = current == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh: ${e.javaClass.simpleName} ${e.message}")
                lastRefresh = SystemClock.elapsedRealtime()
                if (current == null) { setStatus(errorText(e)); scheduleRetry { refreshAssets() } }
            }
        }
    }

    private fun pick(a: Album) {
        settings.putString(KEY_ALBUM_ID, a.id)
        settings.putString(KEY_ALBUM_NAME, a.name)
        if (sheetOpen) closeSheet()
        if (a.id == album?.id) return
        val r = repo ?: return
        val s = scope ?: return
        setAlbum(a)
        prefetchJob?.cancel(); prefetchJob = null
        prepared?.recycle(); prepared = null
        show.clear()
        loadJob?.cancel()
        loadJob = s.launch {
            try {
                loadAssets(r, a, showFirst = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(errorText(e))
                scheduleRetry { pick(a) }
            }
        }
    }

    /** Downloads/decodes the next photo and its glass backdrop, off the main thread. */
    private fun prefetch() {
        val r = repo ?: return
        val s = scope ?: return
        if (prefetchJob?.isActive == true || prepared != null || fade != null) return
        prefetchJob = s.launch {
            var lastError: Exception? = null
            for (attempt in 0 until 6) {
                val a = show.next() ?: break
                try {
                    val bmp = r.frame(a)
                    val blur = withContext(Dispatchers.Default) {
                        PreBlur.blur(bmp, blurPx = 18f, downscale = 4, saturate = GlassTokens.SATURATE, brightness = 1.1f)
                    }
                    prepared = Frame(a, bmp, blur, r.pivot(a))
                    if (advancePending && (shown || current == null)) showPrepared()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImmichHttpException) {
                    lastError = e; show.markFailed(a)
                } catch (e: IOException) {
                    // Network down: play what's on disk. Cached previews never fail with IOException.
                    lastError = e
                    if (!offline) setOffline(true) else show.markFailed(a)
                } catch (e: BadImageException) {
                    lastError = e; show.markFailed(a)
                } catch (e: OutOfMemoryError) {
                    lastError = null; show.markFailed(a)
                }
            }
            if (current == null) {
                setStatus(lastError?.let { errorText(it) } ?: "No photos to show")
                scheduleRetry { show.clear(); refreshAssets() }
            }
        }
    }

    private fun setOffline(off: Boolean) {
        val r = repo ?: return
        if (off == offline) return
        offline = off
        show.playable = if (off) { a -> r.isCached(a) } else { _ -> true }
        updateCaption()
    }

    private fun setStatus(s: String?) {
        status = s
        updateCaption()
    }

    private val retryRunnable = Runnable { val r = retry; retry = null; r?.invoke() }

    private fun scheduleRetry(block: () -> Unit) {
        retry = block
        handler.removeCallbacks(retryRunnable)
        if (shown) handler.postDelayed(retryRunnable, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun errorText(e: Exception): String = when {
        e is ImmichHttpException && (e.code == 401 || e.code == 403) -> "Immich didn't accept the API key"
        e is ImmichHttpException && e.code == 404 -> "Album not found on Immich"
        e is ImmichHttpException -> "Immich error ${e.code} · retrying"
        e is IOException -> "Can't reach Immich · retrying"
        else -> "Couldn't load photos · retrying"
    }

    // --- slideshow ---------------------------------------------------------------------------

    private val advance = Runnable {
        if (!shown || show.paused) return@Runnable
        if (prepared != null) showPrepared() else { advancePending = true; prefetch() }
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        if (!shown) return
        show.msUntilDue(now())?.let { handler.postDelayed(advance, it) }
    }

    private fun togglePause() {
        show.paused = !show.paused
        bar.paused = show.paused
        if (!show.paused && show.due(now())) show.restartTimer(now() - (show.seconds - 1) * 1000L)
        scheduleAdvance()
        wake()
    }

    private fun next() {
        wake()
        if (prepared != null) showPrepared() else { advancePending = true; prefetch() }
    }

    /** Crossfades to the prepared photo (or, off screen with nothing up yet, just puts it up). */
    private fun showPrepared() {
        fade?.end() // finish the previous crossfade first (it recycles the outgoing photo)
        val p = prepared ?: return
        prepared = null
        advancePending = false
        show.advanceTo(p.asset, now())
        val old = current
        current = p
        updateCaption()

        // The current photo moves to the bottom layer with its transform; the new one fades in on top.
        zoom?.cancel(); zoom = null
        bottom.bitmap = top.bitmap
        bottom.pivotX = top.pivotX; bottom.pivotY = top.pivotY
        bottom.scaleX = top.scaleX; bottom.scaleY = top.scaleY
        bottom.alpha = if (old != null) 1f else 0f
        outgoing = old
        top.bitmap = p.bitmap
        top.pivotX = p.pivot.first * width.coerceAtLeast(1)
        top.pivotY = p.pivot.second * height.coerceAtLeast(1)
        top.scaleX = 1f; top.scaleY = 1f
        top.setNight(night, nightDim)
        bottom.setNight(night, nightDim)

        if (!shown) {
            // Neighbour page in the pager: show it still, animate once it's on screen.
            top.alpha = 1f
            finishFade()
            return
        }
        top.alpha = 0f
        val fromScale = bottom.scaleX
        var swapped = false
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            interpolator = Motion.EASE
            addUpdateListener {
                val f = it.animatedValue as Float
                top.alpha = f
                bottom.alpha = if (old != null) 1f - f else 0f
                // the outgoing photo eases back toward 1 on the same 9 s linear transition
                val s = fromScale + (1f - fromScale) * (it.currentPlayTime / ZOOM_MS.toFloat()).coerceIn(0f, 1f)
                bottom.scaleX = s; bottom.scaleY = s
                if (!swapped && f >= 0.5f) { swapped = true; updateGlassBackdrop() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fade === animation) fade = null
                    if (!swapped) updateGlassBackdrop()
                    finishFade()
                }
            })
            start()
        }
        startZoom(1f)
        scheduleAdvance()
    }

    private fun finishFade() {
        if (!shown) updateGlassBackdrop()
        bottom.alpha = 0f
        bottom.bitmap = null
        bottom.scaleX = 1f; bottom.scaleY = 1f
        outgoing?.let { o -> handler.postDelayed({ o.recycle() }, 300) }
        outgoing = null
        if (show.needsRefresh || SystemClock.elapsedRealtime() - lastRefresh > REFRESH_MS) refreshAssets()
        if (shown) prefetch()
    }

    private fun startZoom(from: Float) {
        zoom?.cancel()
        val remaining = ((ZOOM_TO - from) / (ZOOM_TO - 1f) * ZOOM_MS).toLong()
        if (remaining <= 0) return
        top.scaleX = from; top.scaleY = from
        zoom = ObjectAnimator.ofPropertyValuesHolder(top,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, ZOOM_TO),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, ZOOM_TO)).apply {
            duration = remaining
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun resumeZoom() {
        if (current != null && top.scaleX < ZOOM_TO) startZoom(top.scaleX)
    }

    // --- overlay -----------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (LocalTime.now().let { it.hour == 0 && it.minute == 0 }) updateCaption() // "N years ago today"
            handler.postDelayed(this, msToNextMinute())
        }
    }

    private fun updateClock() {
        val t = LocalTime.now()
        overlay.timeText = t.format(if (DateFormat.is24HourFormat(context)) TIME_24 else TIME_12)
        overlay.dateText = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }

    private fun updateCaption() {
        val r = repo
        val cur = current
        val a = album
        when {
            r == null -> {
                caption.captionTitle = "Photo frame"
                caption.captionSubtitle = "Add an Immich server and API key to show your albums"
            }
            cur != null && a != null -> {
                val c = Captions.caption(cur.asset, a.name, LocalDate.now(), r.sourceName, offline)
                caption.captionTitle = c.title
                caption.captionSubtitle = c.subtitle
            }
            else -> {
                caption.captionTitle = ""
                caption.captionSubtitle = status ?: ""
            }
        }
    }

    // --- controls ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop
                if (!moved && SystemClock.uptimeMillis() - downAt < 600) onTap()
            }
        }
        return true
    }

    private fun onTap() {
        if (night) nightWake()
        if (sheetOpen) { closeSheet(); return }
        wake()
    }

    private val hideControls = Runnable { if (!sheetOpen && !keepControls) setControlsVisible(false) }

    /** Tap: the controls lift into glass, then settle away after 4 s (design: wokeAt + 4000). */
    private fun wake() {
        if (repo == null) return
        handler.removeCallbacks(hideControls)
        setControlsVisible(true)
        if (!sheetOpen && !keepControls) handler.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun setControlsVisible(v: Boolean) {
        if (v == controlsVisible) return
        controlsVisible = v
        controlsAnim?.cancel()
        if (v) { updateGlassBackdrop(); controlsBox.visibility = VISIBLE }
        // opacity .3s ease; transform translateY(16→0) scale(.9→1) .45s cubic-bezier(.3,1.4,.5,1)
        val alpha = ObjectAnimator.ofFloat(controlsBox, View.ALPHA, if (v) 1f else 0f).apply {
            duration = 300; interpolator = Motion.EASE
        }
        val move = ObjectAnimator.ofPropertyValuesHolder(controlsBox,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, if (v) 0f else 16f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (v) 1f else 0.9f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (v) 1f else 0.9f)).apply {
            duration = 450; interpolator = Motion.SPRING
        }
        if (v && controlsBox.alpha == 0f) { controlsBox.translationY = 16f; controlsBox.scaleX = 0.9f; controlsBox.scaleY = 0.9f }
        controlsAnim = AnimatorSet().apply {
            playTogether(alpha, move)
            controlsBox.setLayerType(LAYER_TYPE_HARDWARE, null)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    controlsBox.setLayerType(LAYER_TYPE_NONE, null)
                    if (!controlsVisible) { controlsBox.visibility = INVISIBLE; updateGlassBackdrop() }
                }
            })
            start()
        }
    }

    private fun hideControlsNow() {
        controlsAnim?.cancel(); controlsAnim = null
        sheetAnim?.cancel(); sheetAnim = null
        controlsVisible = false; sheetOpen = false; keepControls = false
        for (b in listOf(controlsBox, sheetBox)) { b.alpha = 0f; b.visibility = INVISIBLE; b.setLayerType(LAYER_TYPE_NONE, null) }
        updateGlassBackdrop()
    }

    private fun openSheet() {
        val r = repo ?: return
        sheetOpen = true
        handler.removeCallbacks(hideControls)
        setControlsVisible(true)
        albumList.albums = albums
        albumList.selectedId = album?.id
        albumList.night = night
        albumList.status = when {
            albums.isEmpty() -> "Loading albums…"
            albumsOffline -> "Offline · saved album list"
            else -> null
        }
        // Refresh the list while it's open (new albums, counts).
        scope?.launch {
            try {
                val res = r.albums()
                albums = res.value; albumsOffline = res.offline
                if (sheetOpen) {
                    albumList.albums = albums
                    albumList.status = if (res.offline) "Offline · saved album list" else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sheetOpen) albumList.status = errorText(e)
            }
        }
        sheetBox.visibility = VISIBLE
        updateGlassBackdrop()
        sheetBox.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheetBox.viewTreeObserver.removeOnPreDrawListener(this)
                animateSheet(true)
                return true
            }
        })
        sheetBox.requestLayout()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        animateSheet(false)
        wake()
    }

    /** The sheet grows out of the album button (NOTES §3.1: menus pop out of their control). */
    private fun animateSheet(open: Boolean) {
        sheetAnim?.cancel()
        // album button centre, relative to the sheet box (both are right-aligned at 28 px)
        val albumCenterFromRight = capsule.width - (6f + (bar.width - 6f - 2f - 44f - 2f - 44f - 6f) / 2f)
        sheetBox.pivotX = sheetBox.width - SHADOW_PAD - albumCenterFromRight
        sheetBox.pivotY = sheetBox.height - SHADOW_PAD + 10f + ControlsBar.HEIGHT / 2f
        if (open && sheetBox.alpha == 0f) { sheetBox.scaleX = 0.4f; sheetBox.scaleY = 0.4f }
        val alpha = ObjectAnimator.ofFloat(sheetBox, View.ALPHA, if (open) 1f else 0f).apply {
            duration = if (open) 250 else 200; interpolator = Motion.EASE
        }
        val scale = ObjectAnimator.ofPropertyValuesHolder(sheetBox,
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (open) 1f else 0.6f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (open) 1f else 0.6f)).apply {
            duration = if (open) 450 else 220; interpolator = if (open) Motion.SPRING else Motion.EASE
        }
        sheetBox.setLayerType(LAYER_TYPE_HARDWARE, null)
        sheetAnim = AnimatorSet().apply {
            playTogether(alpha, scale)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheetBox.setLayerType(LAYER_TYPE_NONE, null)
                    if (!sheetOpen) { sheetBox.visibility = INVISIBLE; updateGlassBackdrop() }
                }
            })
            start()
        }
    }

    /**
     * Points the glass at the current photo's pre-blurred copy, placed where the photo is drawn
     * right now (it may be zoomed up to 1.08 around its pivot). Cheap: a new Backdrop wrapper.
     *
     * Hidden glass gets no backdrop: GlassTracker re-checks a glass view's window position every
     * frame while it has one, and a view inside an INVISIBLE parent never draws, so it would keep
     * invalidating and the screen would never go idle.
     */
    private fun updateGlassBackdrop() {
        val cur = current
        val bd = if (cur == null || night) null else {
            val s = top.scaleX
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            Backdrop(cur.blur, top.pivotX * (1 - s), top.pivotY * (1 - s), w * s, h * s)
        }
        val forCapsule = bd.takeIf { controlsVisible }
        val forSheet = bd.takeIf { sheetOpen }
        if (capsule.backdrop !== forCapsule) capsule.backdrop = forCapsule
        if (sheet.backdrop !== forSheet) sheet.backdrop = forSheet
    }

    // --- night -------------------------------------------------------------------------------

    private fun setNight(on: Boolean) {
        night = on
        dimAnim?.cancel()
        nightDim = Night.DIM
        overlay.night = on
        caption.night = on
        bar.night = on
        capsule.night = on
        sheet.night = on
        albumList.night = on
        setDim(if (on) Night.DIM else 1f)
        updateGlassBackdrop()
    }

    private fun setDim(d: Float) {
        nightDim = d
        val dim = if (night) d else 1f
        top.setNight(night, dim)
        bottom.setNight(night, dim)
        overlay.alpha = dim
        caption.alpha = dim
    }

    private val reDim = Runnable { animateDim(Night.DIM) }

    /** Night: a tap brings the frame to full (red) brightness for 6 s. */
    private fun nightWake() {
        animateDim(1f)
        handler.removeCallbacks(reDim)
        handler.postDelayed(reDim, Night.WAKE_SECONDS * 1000L)
    }

    private fun animateDim(to: Float) {
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(nightDim, to).apply {
            duration = 400
            interpolator = Motion.EASE
            addUpdateListener { setDim(it.animatedValue as Float) }
            start()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun msToNextMinute(): Long = 60_000L - System.currentTimeMillis() % 60_000L + 30L

    companion object {
        private const val TAG = "EchoPhotos"
        const val KEY_ALBUM_ID = "photos.album.id"
        const val KEY_ALBUM_NAME = "photos.album.name"
        const val KEY_SECONDS = "photos.seconds"

        /** Design: opacity 1.4s ease, transform 9s linear, active scale 1.08. */
        private const val FADE_MS = 1400L
        private const val ZOOM_MS = 9000L
        private const val ZOOM_TO = 1.08f
        private const val CONTROLS_MS = 4000L
        private const val SHADOW_PAD = 40
        private const val RETRY_MIN_MS = 30_000L
        private const val RETRY_MAX_MS = 10 * 60_000L
        /** Reload the album this often (new photos; back online after a network failure). */
        private const val REFRESH_MS = 15 * 60_000L

        private val TIME_12 = DateTimeFormatter.ofPattern("h:mm")
        private val TIME_24 = DateTimeFormatter.ofPattern("H:mm")

        fun createRepository(context: Context, services: EchoServices): PhotoRepository? {
            val s = services.config.secrets
            return when {
                s.immichConfigured -> ImmichRepository.create(s.immichUrl, s.immichApiKey, context.cacheDir)
                services.config.demoData -> DemoRepository()
                else -> null
            }
        }
    }
}
