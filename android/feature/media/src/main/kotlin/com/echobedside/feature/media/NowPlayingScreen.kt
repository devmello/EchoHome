package com.echobedside.feature.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.Motion
import com.echobedside.core.design.PreBlur
import com.echobedside.core.model.DeviceName
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaSession
import com.echobedside.core.model.MediaState
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.observeWhileAttached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.WeakHashMap

/**
 * Now Playing (design/NowPlaying.dc.html), 1:1 at 960×480.
 *
 * Motion budget (NOTES §5.2 / §3.5): while shown and something is loaded, one 1 s linear ticker
 * steps the three blob translations and redraws the scrubber; nothing else animates except
 * presses, the art spring and the 1.2 s palette crossfade. Paused, the drift settles after
 * [PAUSED_DRIFT_MS]. Empty or hidden: no frames at all.
 *
 * Debug args (`--es np <cmd>`): empty, demo, paused, playing, next, popover, night, day, bt, ap2, preview-off, perf:<flags>.
 */
@SuppressLint("ViewConstructor")
class NowPlayingScreen(context: Context, private val services: EchoServices) : FrameLayout(context), EchoScreen {

    companion object {
        const val ID = "nowplaying"
        /** How long the drift continues after pause, unless "Keep the background moving while paused" is on. */
        private const val PAUSED_DRIFT_MS = 30_000L
        private val palettes = WeakHashMap<Bitmap, AmbientPalette>()
        private const val DEBUG_SRC = "debug-preview"
    }

    /** The `np=bt` / `np=ap2` preview sources: inert controls with the source's capabilities. */
    private class DebugControls(override val canSkip: Boolean) : MediaControls {
        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
        override val canSeek = false
    }

    private val ambient = AmbientView(context)
    private val art = ArtView(context)
    private val info = InfoView(context)
    private val progress = ProgressView(context)
    private val prev = TransportButton(context, R.drawable.media_ic_prev, 40f)
    private val play = TransportButton(context, R.drawable.media_ic_pause, 54f)
    private val next = TransportButton(context, R.drawable.media_ic_next, 40f)
    private val volume = VolumeCapsule(context)
    private val route = RouteButton(context)
    private val scrim = object : View(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) closePopover()
            return true
        }
    }
    private val popover = RoutePopover(context)
    private val empty = EmptyView(context)
    private val trackViews: List<View>

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var trackKey: String? = null
    private var shown = false
    private var night = false
    private var pausedAt = 0L
    private var keepDrift = MediaSettings.pausedDrift(services.settings)
    private var paletteShown = AmbientPalette.NEUTRAL
    private var paletteAnim: ValueAnimator? = null
    private var popoverOpen = false
    private var pendingCmd: String? = null

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            ambient.setFraction(it.animatedFraction)
            if (session?.state?.playing == true) progress.invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) = onTick()
        })
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            updateClock()
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000 - now % 60_000 + 20)
        }
    }

    init {
        clipChildren = false
        fun add(v: View, l: Float, t: Float, w: Float, h: Float) =
            addView(v, LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = l.toInt(); topMargin = t.toInt() })

        add(ambient, 0f, 0f, NP.W / AmbientView.SCALE, NP.H / AmbientView.SCALE)
        add(art, NP.ART_L - ArtView.M, NP.ART_T - ArtView.M, ArtView.SIZE + 2 * ArtView.M, ArtView.SIZE + 2 * ArtView.M)
        add(info, 0f, 0f, NP.W, NP.ARTIST_T + 40f)
        add(progress, NP.COL_L, NP.seekT - 10f, NP.COL_W, 10f + 22f + 4f + Text.label.normalLineHeight + 6f)
        // Transport row: 80 + 40 + 96 + 40 + 80, centred in the column.
        val rowL = NP.COL_L + (NP.COL_W - 336f) / 2f
        val cy = NP.transportT + NP.TRANSPORT_H / 2f
        for ((b, x, s) in listOf(Triple(prev, rowL, 80f), Triple(play, rowL + 120f, 96f), Triple(next, rowL + 256f, 80f))) {
            add(b.lift, x, cy - s / 2f, s, s)
            add(b, x, cy - s / 2f, s, s)
        }
        add(volume, NP.COL_L, NP.BAR_T, NP.VOLUME_W, NP.BAR_H)
        add(route, NP.ROUTE_L, NP.BAR_T, NP.BAR_H, NP.BAR_H)
        add(scrim, 0f, 0f, NP.W, NP.H)
        add(popover, NP.COL_R - NP.POPOVER_W, NP.POPOVER_BOTTOM - 200f, NP.POPOVER_W, 200f)
        add(empty, 0f, 0f, NP.W, NP.H)
        trackViews = listOf(art, progress, prev.lift, prev, play.lift, play, next.lift, next, volume, route)
        scrim.visibility = GONE
        popover.visibility = GONE

        prev.onTap = { session?.controls?.let { if (it.canSkip) it.previous() } }
        next.onTap = { session?.controls?.let { if (it.canSkip) it.next() } }
        play.onTap = { session?.let { if (!it.state.playing) it.controls.play() else if (it.controls.canPause) it.controls.pause() } }
        progress.position = { session?.state?.positionNow() ?: 0L }
        progress.onSeek = { ms -> session?.controls?.seekTo(ms) }
        volume.onChange = { v -> setVolume(v) }
        route.setOnClickListener { if (popoverOpen) closePopover() else openPopover() }
        popover.onButton = { disconnect() }

        // Settings apply live: the device name (popover, empty state) and the paused drift.
        empty.deviceName = DeviceName.get(services.settings)
        observeWhileAttached(services.settings.changes) { key ->
            when (key) {
                DeviceName.KEY -> {
                    empty.deviceName = DeviceName.get(services.settings)
                    if (popoverOpen) fillPopover()
                }
                MediaSettings.PAUSED_DRIFT -> { keepDrift = MediaSettings.pausedDrift(services.settings); updateMotion() }
            }
        }
        observeWhileAttached(services.media.current) { bind(it) }
        observeWhileAttached(services.system.state) { st ->
            if (st.nightActive != night) applyNight(st.nightActive)
            if (session?.state?.volume == null) volume.setValue(st.volume, animate = shown)
        }
        bind(services.media.current.value)
    }

    // --- binding ---

    private fun bind(s: MediaSession?) {
        val was = session
        session = s
        if (s == null) {
            trackKey = null
            closePopover()
            for (v in trackViews) v.visibility = INVISIBLE
            info.showTrack = false
            empty.visibility = VISIBLE
            ambient.blobsVisible = false
            ambient.setPalette(AmbientPalette(0xFF000000.toInt(), 0, 0, 0))
            paletteShown = AmbientPalette(0xFF000000.toInt(), 0, 0, 0)
            updateMotion()
            return
        }
        val st = s.state
        if (was == null) {
            for (v in trackViews) v.visibility = VISIBLE
            empty.visibility = GONE
            info.showTrack = true
            ambient.blobsVisible = true
        }
        val key = "${s.sourceId}|${st.title}|${st.artist}|${st.album}|${System.identityHashCode(st.artwork)}"
        if (key != trackKey) {
            trackKey = key
            // No artwork (Bluetooth AVRCP often has none, apps load it late): a procedural cover
            // from the album/title, which also gives the ambient its colours.
            val cover = st.artwork ?: st.title.takeIf { it.isNotBlank() }?.let { DemoArt.forAlbum(st.album, it) }
            art.art = cover
            info.title = st.title
            info.subtitle = Format.subtitle(st.artist, st.album)
            loadPalette(cover, key)
        }
        info.sourceIcon = sourceIcon(st.source)
        route.icon = if (st.source == MediaSourceKind.BLUETOOTH) R_bluetooth else R_airplay
        info.sourceText = when (st.source) {
            MediaSourceKind.AIRPLAY -> "AirPlay from " + st.sourceLabel.ifBlank { "iPhone" }
            MediaSourceKind.BLUETOOTH -> "Bluetooth from " + st.sourceLabel.ifBlank { "iPhone" }
            MediaSourceKind.SPOTIFY -> "Spotify Connect" + (st.sourceLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            MediaSourceKind.OTHER_APP -> "Playing from " + st.sourceLabel.ifBlank { "an app" }
        }
        // Play/pause glyph (the play triangle sits 6 px right, as in the design) and the art spring.
        play.setIcon(if (st.playing) R.drawable.media_ic_pause else R.drawable.media_ic_play, if (st.playing) 0f else 3f)
        // Unsupported controls are dimmed and inert (AirPlay 2 senders can't skip; some can't pause).
        prev.available = s.controls.canSkip
        next.available = s.controls.canSkip
        play.available = !st.playing || s.controls.canPause
        val scale = if (st.playing) 1f else 0.86f
        if (was == null || !shown) { art.animate().cancel(); art.scaleX = scale; art.scaleY = scale }
        else if (art.scaleX != scale || (was.state.playing != st.playing)) {
            art.animate().scaleX(scale).scaleY(scale).setDuration(550).setInterpolator(Motion.SPRING_SOFT).start()
        }
        if (!st.playing && (was == null || was.state.playing)) pausedAt = SystemClock.elapsedRealtime()
        progress.duration = st.durationMs
        progress.canSeek = s.controls.canSeek
        progress.invalidate()
        volume.setValue(st.volume ?: services.system.state.value.volume, animate = shown && was != null)
        if (popoverOpen) fillPopover()
        runPending()
        updateMotion()
    }

    private fun sourceIcon(k: MediaSourceKind) = when (k) {
        MediaSourceKind.AIRPLAY -> R_airplay
        MediaSourceKind.BLUETOOTH -> R_bluetooth
        MediaSourceKind.SPOTIFY, MediaSourceKind.OTHER_APP -> R_note
    }

    private fun loadPalette(artwork: Bitmap?, key: String) {
        if (artwork == null) { applyPalette(AmbientPalette.NEUTRAL); return }
        palettes[artwork]?.let { applyPalette(it); return }
        services.scope.launch {
            val p = withContext(Dispatchers.Default) { PaletteExtractor.extract(Artwork.samplePixels(artwork)) }
            palettes[artwork] = p
            android.util.Log.d("EchoMedia", "palette %s: base=%06X a=%06X b=%06X c=%06X".format(key.substringAfter('|').substringBefore('|'),
                p.base and 0xFFFFFF, p.a and 0xFFFFFF, p.b and 0xFFFFFF, p.c and 0xFFFFFF))
            if (trackKey == key) applyPalette(p)
        }
    }

    /** 1.2 s ease crossfade of the ambient colours (design: `transition: background 1.2s ease`). */
    private fun applyPalette(p: AmbientPalette) {
        paletteAnim?.cancel()
        val from = paletteShown
        if (!shown || from.base == 0xFF000000.toInt() && from.a == 0) {
            paletteShown = p; ambient.setPalette(p); rebuildBackdrop(); return
        }
        paletteAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = Motion.EASE
            addUpdateListener {
                val t = it.animatedFraction
                val cur = AmbientPalette(blend(from.base, p.base, t), blend(from.a, p.a, t), blend(from.b, p.b, t), blend(from.c, p.c, t))
                paletteShown = cur
                ambient.setPalette(cur)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { paletteShown = p; ambient.setPalette(p); rebuildBackdrop() }
            })
            start()
        }
    }

    /** The glass controls sample a pre-blurred copy of the ambient layer (no live blur). */
    private fun rebuildBackdrop() {
        val snap = ambient.snapshot()
        val bd = Backdrop(PreBlur.blur(snap, blurPx = 4f, downscale = 1), 0f, 0f, NP.W, NP.H)
        snap.recycle()
        for (g in listOf(volume, route, prev.lift, play.lift, next.lift)) g.backdrop = bd
    }

    private fun applyNight(on: Boolean) {
        night = on
        ambient.night = on; art.night = on; info.night = on; progress.night = on; empty.night = on
        for (b in listOf(prev, play, next)) b.night = on
        volume.night = on; route.night = on; popover.night = on
        updateMotion()
    }

    // --- motion ---

    private fun wallSeconds() = System.currentTimeMillis() / 1000.0

    private fun updateMotion() {
        val s = session
        val drift = shown && s != null && !night &&
            (s.state.playing || keepDrift || SystemClock.elapsedRealtime() - pausedAt < PAUSED_DRIFT_MS)
        if (drift && !ticker.isStarted) {
            ambient.jumpTo(wallSeconds())
            ambient.step(wallSeconds())
            ticker.start()
        } else if (!drift && ticker.isStarted) {
            ticker.cancel()
        }
    }

    private fun onTick() {
        ambient.step(wallSeconds())
        val s = session
        if (s != null && !s.state.playing && !keepDrift && SystemClock.elapsedRealtime() - pausedAt >= PAUSED_DRIFT_MS) {
            // Let the current step finish, then stop drawing.
            main.postDelayed({ updateMotion() }, 1000)
        }
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        info.clock = Format.wallClock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(context))
    }

    // --- controls ---

    private fun setVolume(v: Float) {
        val s = session
        if (s?.state?.volume != null) s.controls.setVolume(v) else services.systemControls.setVolume(v, showHud = false)
    }

    private fun fillPopover() {
        val s = session ?: return
        val st = s.state
        val device = DeviceName.get(services.settings)
        when (st.source) {
            MediaSourceKind.OTHER_APP -> {
                popover.header = "PLAYING FROM"
                popover.name = st.sourceLabel.ifBlank { "App" }
                popover.subtitle = "Playing on $device"
                popover.chips = st.route
                popover.buttonLabel = "Stop"
                popover.deviceIcon = R_note
            }
            MediaSourceKind.BLUETOOTH -> {
                popover.header = "BLUETOOTH"
                popover.name = st.sourceLabel.ifBlank { "iPhone" }
                popover.subtitle = "Connected to $device"
                popover.chips = st.route
                popover.buttonLabel = "Disconnect"
                popover.deviceIcon = R_phone
            }
            MediaSourceKind.AIRPLAY, MediaSourceKind.SPOTIFY -> {
                popover.header = if (st.source == MediaSourceKind.SPOTIFY) "SPOTIFY CONNECT" else "AIRPLAY RECEIVER"
                popover.name = st.sourceLabel.ifBlank { "iPhone" }
                popover.subtitle = "Streaming to $device"
                // The sender's route ("AirPlay 2", codec, rate) from the receiver; the demo carries the design's.
                popover.chips = st.route
                popover.buttonLabel = "Disconnect"
                popover.deviceIcon = R_phone
            }
        }
        val h = popover.contentHeight()
        (popover.layoutParams as LayoutParams).apply { height = h.toInt(); topMargin = (NP.POPOVER_BOTTOM - h).toInt() }
        popover.requestLayout()
        popover.invalidate()
    }

    private fun openPopover() {
        if (session == null || popoverOpen) return
        popoverOpen = true
        fillPopover()
        // Its backdrop: everything under it, blurred once (24 px, brightness .95), at open time.
        val snap = Bitmap.createBitmap((NP.W / 4).toInt(), (NP.H / 4).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(snap)
        c.scale(0.25f, 0.25f)
        draw(c)
        popover.backdrop = Backdrop(PreBlur.blur(snap, blurPx = 6f, downscale = 1, brightness = 0.95f), 0f, 0f, NP.W, NP.H)
        snap.recycle()
        route.open = true
        scrim.visibility = VISIBLE
        popover.visibility = VISIBLE
        // Pop out of the button: scale from the button's centre.
        val lp = popover.layoutParams as LayoutParams
        popover.pivotX = NP.ROUTE_L + NP.BAR_H / 2f - lp.leftMargin
        popover.pivotY = NP.BAR_T + NP.BAR_H / 2f - lp.topMargin
        popover.alpha = 0f; popover.scaleX = 0.4f; popover.scaleY = 0.4f
        popover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.SPRING).withEndAction(null).start()
    }

    private fun closePopover() {
        if (!popoverOpen) return
        popoverOpen = false
        route.open = false
        scrim.visibility = GONE
        if (!shown) { popover.visibility = GONE; return }
        popover.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200).setInterpolator(Motion.EASE)
            .withEndAction { if (!popoverOpen) popover.visibility = GONE }.start()
    }

    private fun disconnect() {
        val s = session ?: return
        closePopover()
        // AirPlay / Bluetooth drop the sender, app sessions stop, the demo goes back to empty.
        s.controls.disconnect()
    }

    // --- EchoScreen ---

    override fun onScreenShown(args: Bundle?) {
        args?.getString("np")?.let { pendingCmd = it }
        if (!shown) {
            shown = true
            minuteTick.run()
        }
        runPending()
        updateMotion()
    }

    override fun onScreenHidden() {
        shown = false
        main.removeCallbacksAndMessages(null)
        ticker.cancel()
        paletteAnim?.end()
        art.animate().cancel()
        session?.let { val s = if (it.state.playing) 1f else 0.86f; art.scaleX = s; art.scaleY = s }
        closePopover()
    }

    private fun runPending() {
        val cmd = pendingCmd ?: return
        val demo = MediaFeature.demo
        val s = session
        if (s == null && cmd in setOf("paused", "playing", "next", "popover")) return // wait for a session
        // Clear first: the controls publish synchronously and re-enter bind() → runPending().
        pendingCmd = null
        when (cmd) {
            "empty" -> demo?.stopByUser()
            "demo" -> demo?.restart()
            "paused" -> s?.controls?.pause()
            "playing" -> s?.controls?.play()
            "next" -> s?.controls?.next()
            "popover" -> if (isLaidOut) openPopover() else post { openPopover() }
            // Debug only: preview Night mode (the clock feature owns nightActive; its sensor logic may flip it back).
            "night", "day" -> services.system.update { it.copy(nightActive = cmd == "night") }
            // Debug only: a sample Bluetooth source as :feature:phone will publish it (no art, no seek).
            "bt" -> services.media.publish(DEBUG_SRC, MediaState("Slow Bloom", "Ines Calder", "Greenhouse", null, 214_000, 61_000,
                SystemClock.elapsedRealtime(), true, MediaSourceKind.BLUETOOTH, "Maya's iPhone", route = listOf("Bluetooth", "AAC")),
                DebugControls(canSkip = true))
            // Debug only: an AirPlay 2 sender as C publishes it (no skip, no seek).
            "ap2" -> services.media.publish(DEBUG_SRC, MediaState("Paper Satellites", "The Northern Lakes", "Small Orbits",
                DemoArt.forAlbum("Small Orbits", ""), 252_000, 40_000, SystemClock.elapsedRealtime(), true, MediaSourceKind.AIRPLAY,
                "Maya's iPhone", route = listOf("AirPlay 2", "ALAC", "44.1 kHz")), DebugControls(canSkip = false))
            "bt-off", "preview-off" -> services.media.clear(DEBUG_SRC)
            // Debug only: hide view groups to profile GPU cost, e.g. "perf:ar" (a ambient, r art, g glass, t text).
            else -> if (cmd.startsWith("perf:")) {
                val f = cmd.removePrefix("perf:")
                fun vis(on: Boolean) = if (on) VISIBLE else INVISIBLE
                ambient.visibility = vis('a' !in f); art.visibility = vis('r' !in f)
                volume.visibility = vis('g' !in f); route.visibility = vis('g' !in f)
                info.visibility = vis('t' !in f); progress.visibility = vis('t' !in f)
                for (b in listOf(prev, play, next)) b.visibility = vis('t' !in f)
            }
        }
    }
}
