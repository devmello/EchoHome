package com.echobedside.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.assistant.audio.AndroidMic
import com.echobedside.feature.assistant.audio.AudioFocusDucker
import com.echobedside.feature.assistant.llm.AssistantAgent
import com.echobedside.feature.assistant.llm.ClaudeClient
import com.echobedside.feature.assistant.llm.ClaudeModels
import com.echobedside.feature.assistant.llm.EchoTools
import com.echobedside.feature.assistant.ui.AssistantScreen
import com.echobedside.feature.assistant.voice.SherpaVoice
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.map

/**
 * `:feature:assistant` (agent G — Voice assistant).
 *
 * The assistant screen + droplet (Assistant.dc.html, opened with `navigator.show("assistant")`),
 * on-device wake word / STT / TTS (sherpa-onnx), Claude with the display's tools, and the
 * ASSISTANT live activity. Setup: docs/assistant-setup.md.
 */
object AssistantFeature : EchoFeature {
    override val id = "assistant"

    /** For the app (e.g. a key handler for the mic button) once one is wired; null before install. */
    @Volatile var engine: AssistantEngine? = null
        private set

    override fun install(host: FeatureHost) {
        val ctx = host.context.applicationContext
        val services = host.services
        // One thread for every model call (sherpa objects aren't thread-safe), below UI priority.
        val voiceThread = Executors.newSingleThreadExecutor { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); r.run() }, "assistant-voice")
        }.asCoroutineDispatcher()
        val ttsThread = Executors.newSingleThreadExecutor { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); r.run() }, "assistant-tts")
        }.asCoroutineDispatcher()
        // /sdcard/Android/data/com.echobedside/files/voice (created here so adb can push into it).
        val root = ctx.getExternalFilesDir("voice") ?: File(ctx.filesDir, "voice")
        val voice = SherpaVoice(root)
        val agent = AssistantAgent(
            api = ClaudeClient({ services.config.secrets.anthropicApiKey }),
            tools = EchoTools({ services }),
            model = { ClaudeModels.of(services.settings.getString(AssistantEngine.KEY_MODEL, ClaudeModels.DEFAULT)) },
            name = { services.settings.getString(AssistantEngine.KEY_NAME, "Nova") },
            location = { services.location.value.name.ifBlank { services.weather.weather.value?.location.orEmpty() } },
        )
        val engine = AssistantEngine(
            scope = services.scope,
            system = services.system,
            island = services.island,
            settings = services.settings,
            navigator = { runCatching { services.navigator }.getOrNull() },
            mic = AndroidMic(),
            ducker = AudioFocusDucker(ctx),
            voice = voice,
            // The key is read per request; the page can set it at runtime (configFlow emits then).
            agent = { if (services.config.secrets.anthropicConfigured) agent else null },
            keyConfigured = services.configFlow.map { it.secrets.anthropicConfigured },
            hasMicPermission = { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            voiceThread = voiceThread,
            ttsThread = ttsThread,
            demoData = services.config.demoData,
        )
        this.engine = engine
        host.registerSettings(AssistantSettings.section(voice.voices(), voice.defaultVoice))
        host.registerScreen(ScreenSpec(AssistantEngine.SCREEN_ID, order = 60, inPager = false) { AssistantScreen(it, services, engine) })
        // After every feature installed (slots may be replaced during install).
        Handler(Looper.getMainLooper()).post { engine.start() }
    }
}
