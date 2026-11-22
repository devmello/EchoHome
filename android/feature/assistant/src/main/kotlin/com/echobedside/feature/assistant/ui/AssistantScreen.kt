package com.echobedside.feature.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.PreBlur
import com.echobedside.core.model.AssistantPhase
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.assistant.AssistantEngine
import com.echobedside.feature.assistant.Availability
import com.echobedside.feature.assistant.audio.TAG
import com.echobedside.feature.assistant.pipeline.AssistState
import com.echobedside.feature.assistant.pipeline.DemoScripts
import com.echobedside.feature.assistant.pipeline.Phase
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The assistant screen (Assistant.dc.html): a blurred snapshot of the screen it opened over, the
 * voice light, the content and the droplet. Animates on the Choreographer only while shown
 * (30 fps when idle, 60 while listening / working / answering); nothing runs while hidden.
 *
 * Screen args (adb `--es screen assistant --es <key> <value>`): `script` a|b|c plays a demo,
 * `at` <ms> freezes it there (screenshots), `mictest` 1 captures the mic without models,
 * `listen` 1 starts a turn, `wake` 1|0 overrides the wake-word setting, `say` <text> speaks
 * with the on-device voice only, `echo` 1 listens and repeats the transcript (STT + TTS, no Claude),
 * `ask` <text> sends text to Claude.
 */
@SuppressLint("ViewConstructor")
class AssistantScreen(context: Context, private val services: EchoServices, private val engine: AssistantEngine) :
    FrameLayout(context), EchoScreen {

    private val background = BackgroundLayer(context)
    private val glow = GlowLayer(context)
    private val content = ContentLayer(context, object : ContentLayer.Actions {
        override fun chip(index: Int) {
            engine.userInteracted()
            val c = chips().getOrNull(index) ?: return
            engine.ask(c.second, c.third)
        }
        override fun suggestion(accept: Boolean) {
            engine.userInteracted()
            if (accept) contentAccepted() else engine.stopAll()
        }
        override fun openMedia() { services.navigator.show(NOW_PLAYING_SCREEN) }
    })
    private val droplet = DropletLayer(context) { engine.userInteracted(); engine.tapDroplet() }

    private var shown = false
    private var lastFrame = 0L
    private var state = AssistState()
    private var night = false

    init {
        clipChildren = false
        addView(background, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(droplet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        observeWhileAttached(combine(engine.state, engine.availability, engine.wakeActive) { s, a, w -> Triple(s, a, w) }) { (s, a, w) ->
            state = s
            content.bind(s, chips().map { ContentLayer.Chip(it.first, it.fourth) }, subtitle(a, w), status(s, a), System.currentTimeMillis())
            kick()
        }
        observeWhileAttached(services.system.state.map { it.nightActive }) { n ->
            night = n
            glow.night = n; droplet.night = n; content.night = n
        }
    }

    // ---------------- content model ----------------

    private data class Quad(val first: String, val second: String, val third: String, val fourth: Int)

    /** (label, question, demo id, dot colour). Demo mode keeps the design's chips; real turns ask things the tools can do. */
    private fun chips(): List<Quad> {
        val d = DemoScripts.all
        if (engine.useDemo()) return d.map { Quad(it.chip, it.query, it.id, it.color) }
        val ha = services.config.secrets.haConfigured
        return listOf(
            Quad(d[0].chip, d[0].query, "a", d[0].color),
            if (ha) Quad(d[1].chip, d[1].query, "b", d[1].color) else Quad("Set a 10-minute timer", "Set a timer for 10 minutes.", "b", d[1].color),
            Quad("What’s playing?", "What’s playing right now?", "c", d[2].color),
        )
    }

    private fun subtitle(a: Availability, wake: Boolean): String = when {
        engine.useDemo() -> "Say “Hey Nova”, or tap one to try"
        a == Availability.MUTED -> "The microphone is off"
        a == Availability.DISABLED -> "The assistant is turned off"
        a == Availability.NO_KEY -> "Add an Anthropic API key to talk to Claude"
        a == Availability.NO_PERMISSION -> "Allow the microphone to talk to me"
        a == Availability.NO_MODELS -> "The voice models aren’t installed yet"
        wake -> "Say “Hey Nova”, or tap one to try"
        else -> "Tap the droplet to talk, or tap one to try"
    }

    private fun status(s: AssistState, a: Availability): Pair<String, Int> = when (s.phase) {
        Phase.IDLE -> if (a == Availability.MUTED) "Mic off" to 0xFFFF453A.toInt() else "Ready" to 0xFF8E8E93.toInt()
        Phase.LISTENING -> "Listening" to 0xFFFF375F.toInt()
        Phase.WORKING -> "Thinking" to 0xFFBF5AF2.toInt()
        Phase.ANSWER -> "Answer" to 0xFF30D158.toInt()
        Phase.ERROR -> "Answer" to 0xFFFF9F0A.toInt()
    }

    private fun contentAccepted() { content.suggestionAccepted = true }

    // ---------------- frames ----------------

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!shown) return
            val now = System.currentTimeMillis()
            val active = state.phase != Phase.IDLE || engine.speaking.value || engine.micTesting
            // Idle breathing at 30 fps is enough (and half the cost).
            if (active || now - lastFrame >= 32) {
                lastFrame = now
                frame(now)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun frame(now: Long) {
        val phase = when (state.phase) {
            Phase.IDLE -> "idle"
            Phase.LISTENING -> "listen"
            Phase.WORKING -> "think"
            Phase.ANSWER, Phase.ERROR -> "answer"
        }
        val ap = when (state.phase) {
            Phase.LISTENING -> AssistantPhase.LISTENING
            Phase.WORKING -> AssistantPhase.WORKING
            Phase.ANSWER, Phase.ERROR -> if (engine.speaking.value) AssistantPhase.SPEAKING else null
            Phase.IDLE -> null
        }
        val level = engine.levelFor(ap)
        val talking = engine.speaking.value
        droplet.frame(now, phase, level, talking)
        val glowA = if (phase == "idle" && !engine.micTesting) 0.35f else 0.45f + 0.55f * level
        glow.alpha += (glowA - glow.alpha) * 0.2f
        val gs = 0.9f + 0.2f * level
        glow.scaleX += (gs - glow.scaleX) * 0.25f
        glow.scaleY = glow.scaleX
        content.frame(now)
        if (now / 1000 != lastClockSec) { lastClockSec = now / 1000; content.setClock(clockText()) }
    }

    private var lastClockSec = -1L

    private fun clockText(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY) % 12
        return "${if (h == 0) 12 else h}:%02d".format(c.get(Calendar.MINUTE))
    }

    private fun kick() {
        if (!shown) return
        Choreographer.getInstance().removeFrameCallback(callback)
        Choreographer.getInstance().postFrameCallback(callback)
    }

    // ---------------- EchoScreen ----------------

    override fun onScreenShown(args: Bundle?) {
        shown = true
        engine.refreshPermission()
        captureBackground()
        content.setClock(clockText())
        glow.alpha = 0.35f; glow.scaleX = 0.9f; glow.scaleY = 0.9f
        kick()
        handleArgs(args)
    }

    override fun onScreenHidden() {
        shown = false
        Choreographer.getInstance().removeFrameCallback(callback)
        engine.screenHidden()
        background.setSnapshot(null)
        content.backdrop = null
    }

    private fun handleArgs(args: Bundle?) {
        args ?: return
        @Suppress("DEPRECATION") fun s(k: String) = args.get(k)?.toString()
        s("wake")?.let { engine.setWakeOverride(it == "1" || it == "true") }
        when {
            s("script") != null -> engine.runDemo(s("script")!!, s("at")?.toLongOrNull())
            s("mictest") != null -> engine.setMicTest(s("mictest") == "1" || s("mictest") == "true")
            s("synth") != null -> engine.synthTest(s("synth")!!) // silent: synthesizes, plays nothing
            s("say") != null -> engine.sayTest(s("say")!!)       // plays sound
            s("ask") != null -> engine.ask(s("ask")!!)
            s("listen") != null -> engine.tapDroplet()
            s("echo") != null -> engine.echoTest()
        }
    }

    /** Snapshot of what was on screen (quarter size, without us), blurred off the main thread. */
    private fun captureBackground() {
        val root = rootView ?: return
        if (root.width == 0 || root.height == 0) return
        val small = try {
            val bmp = Bitmap.createBitmap(root.width / 4, root.height / 4, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(0.25f, 0.25f)
            val vis = visibility
            visibility = View.INVISIBLE
            try { root.draw(c) } finally { visibility = vis }
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "background snapshot failed: ${e.javaClass.simpleName}")
            null
        } ?: return
        services.scope.launch {
            val blurred = withContext(Dispatchers.Default) { BackgroundLayer.blurSnapshot(small) }
            // The glass samples what the background layer shows, blurred again (backdrop-filter).
            val composed = withContext(Dispatchers.Default) {
                val out = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
                val layer = BackgroundLayer.compose(blurred, out)
                PreBlur.blur(layer, blurPx = 4f, downscale = 1)
            }
            small.recycle()
            if (!shown) { blurred.recycle(); composed.recycle(); return@launch }
            background.setSnapshot(blurred)
            content.backdrop = Backdrop(composed, 0f, 0f, 960f, 480f)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) engine.userInteracted()
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        /** `:feature:media`'s Now Playing screen (see docs/status/assistant.md open items). */
        const val NOW_PLAYING_SCREEN = "nowplaying"
    }
}
