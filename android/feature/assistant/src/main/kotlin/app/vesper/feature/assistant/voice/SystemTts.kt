package app.vesper.feature.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.vesper.feature.assistant.audio.AudioFocusDucker
import app.vesper.feature.assistant.audio.TAG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/**
 * An installed Android TTS engine (e.g. eSpeak NG or RHVoice from F-Droid) as the assistant's voice,
 * through `android.speech.tts`. Pieces are queued with QUEUE_ADD as Claude streams them; the engine
 * plays them back to back. The Echo has no engine out of the box.
 */
class SystemTtsSpeaker(private val context: Context, private val engine: String?) : Speaker {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var speakingSince = 0L
    private val ids = AtomicInteger()

    override fun prepare() {
        if (tts != null) return
        val ready = CountDownLatch(1)
        var ok = false
        val t0 = SystemClock.elapsedRealtime()
        val t = TextToSpeech(context, { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }, engine)
        if (!ready.await(INIT_MS, TimeUnit.MILLISECONDS) || !ok) { t.shutdown(); throw IllegalStateException("tts init") }
        t.setAudioAttributes(AudioFocusDucker.SPEECH_ATTRIBUTES)
        tts = t
        Log.i(TAG, "system TTS ready in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    override fun speak(text: String, speed: Float): Boolean = open(speed).run { add(text); finish() }

    override fun open(speed: Float): Utterance {
        prepare()
        val t = tts ?: throw IllegalStateException("tts")
        t.setSpeechRate(speed)
        val done = HashSet<String>()
        val lock = Object()
        var last: String? = null
        var stopped = false
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { if (speakingSince == 0L) speakingSince = SystemClock.elapsedRealtime() }
            override fun onDone(id: String) = synchronized(lock) { done += id; lock.notifyAll() }
            @Deprecated("old API") override fun onError(id: String) = synchronized(lock) { done += id; lock.notifyAll() }
            override fun onStop(id: String, interrupted: Boolean) = synchronized(lock) { stopped = true; lock.notifyAll() }
        })
        return object : Utterance {
            override fun add(text: String) {
                val id = "a" + ids.incrementAndGet()
                synchronized(lock) { last = id }
                t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            }
            override fun finish(): Boolean {
                val end = SystemClock.elapsedRealtime() + MAX_SPEAK_MS
                synchronized(lock) {
                    while (!stopped && last != null && last !in done && SystemClock.elapsedRealtime() < end) lock.wait(100)
                }
                speakingSince = 0L
                return !stopped
            }
            override fun cancel() = stop()
        }
    }

    /** The engine doesn't give us its samples: a speech-like wobble while it talks. */
    override val level: Float get() {
        val s = speakingSince
        if (s == 0L || tts?.isSpeaking != true) return 0f
        val x = (SystemClock.elapsedRealtime() - s) / 1000.0
        return (0.35 + 0.3 * abs(sin(x * 7.0)) * abs(sin(x * 2.3 + 1))).toFloat()
    }

    override fun stop() { tts?.stop(); speakingSince = 0L }

    override fun release() { tts?.shutdown(); tts = null }

    companion object {
        const val INIT_MS = 4000L
        const val MAX_SPEAK_MS = 60_000L

        /** Installed TTS engines (package names); needs the <queries> entry in the manifest. */
        fun engines(context: Context): List<String> =
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }.distinct()
    }
}

/**
 * [base] (sherpa-onnx) plus the voice "system" when an Android TTS engine is installed. Choosing it
 * speaks through that engine; every other voice is a sherpa-onnx voice.
 */
class VoiceWithSystemTts(private val base: VoiceStack, private val system: Speaker?) : VoiceStack by base {
    @Volatile private var useSystem = false

    override val ttsAvailable get() = if (useSystem) system != null else base.ttsAvailable
    override fun speaker(): Speaker = if (useSystem && system != null) system else base.speaker()
    override fun voices() = (if (system != null) listOf(SYSTEM to "System voice (installed TTS engine)") else emptyList()) + base.voices()
    override val defaultVoice get() = if (system != null) SYSTEM else base.defaultVoice

    override fun selectVoice(id: String) {
        val sys = id == SYSTEM && system != null
        if (sys != useSystem) (if (useSystem) system else base.speaker())?.release()
        useSystem = sys
        if (!sys) base.selectVoice(id)
    }

    companion object { const val SYSTEM = "system" }
}
