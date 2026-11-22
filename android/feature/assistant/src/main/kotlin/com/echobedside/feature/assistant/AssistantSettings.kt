package com.echobedside.feature.assistant

import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.core.model.SettingsStore
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_DISMISS_S
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_ENABLED
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_FOLLOW_UP
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_KEEP_STT
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_KEEP_TTS
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_MODEL
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_NAME
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_OPEN_ON_WAKE
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_SPEAK
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_SPEAK_AT_NIGHT
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_SPEECH_RATE
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_VOICE
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_WAKE
import com.echobedside.feature.assistant.AssistantEngine.Companion.KEY_WAKE_SENSITIVITY
import com.echobedside.feature.assistant.llm.ClaudeModels

/**
 * The "Assistant" card on the settings page. Every value applies live (the engine observes
 * [SettingsStore.changes]). The Anthropic API key is a SECRET declared by the settings feature;
 * the assistant reads it per request.
 */
object AssistantSettings {
    const val ORDER = 60

    /** Wake word sensitivity → the keyword spotter's trigger threshold (lower = triggers more easily). */
    val SENSITIVITY = linkedMapOf("low" to 0.35f, "normal" to 0.25f, "high" to 0.15f)
    const val SENSITIVITY_DEFAULT = "normal"

    fun threshold(settings: SettingsStore): Float =
        SENSITIVITY[settings.getString(KEY_WAKE_SENSITIVITY, SENSITIVITY_DEFAULT)] ?: SENSITIVITY.getValue(SENSITIVITY_DEFAULT)

    fun section(voices: List<Pair<String, String>>, defaultVoice: String) = SettingsSection(
        id = "assistant",
        title = "Assistant",
        order = ORDER,
        help = "Tap the droplet to talk. Speech is recognised on this display; only the words you say " +
            "after it starts listening go to Claude. Add the Anthropic API key in the API keys section.",
        settings = listOf(
            SettingSpec(KEY_ENABLED, "Assistant", SettingType.BOOLEAN, "true",
                help = "Off: the microphone is never used by the assistant."),
            SettingSpec(KEY_WAKE, "Wake word", SettingType.BOOLEAN, "false",
                help = "Listen for “Hey Nova” while idle. The microphone and a small keyword model then run " +
                    "all the time, on this display only (about 40 MB more memory and a few percent of the CPU)."),
            SettingSpec(KEY_WAKE_SENSITIVITY, "Wake word sensitivity", SettingType.CHOICE, SENSITIVITY_DEFAULT,
                help = "Higher reacts more easily, but wakes by mistake more often.",
                options = listOf("low" to "Low", "normal" to "Normal", "high" to "High")),
            SettingSpec(KEY_OPEN_ON_WAKE, "Open on wake word", SettingType.BOOLEAN, "true",
                help = "The wake word opens the assistant screen, then goes back to the clock after the answer."),
            SettingSpec(KEY_MODEL, "Claude model", SettingType.CHOICE, ClaudeModels.DEFAULT,
                help = "Haiku answers fastest. Sonnet and Opus think harder but take longer and cost more per question.",
                options = ClaudeModels.OPTIONS),
            SettingSpec(KEY_FOLLOW_UP, "Follow-up listening", SettingType.BOOLEAN, "true",
                help = "When Claude answers with a question, listen for your reply without the wake word or a tap."),
            SettingSpec(KEY_SPEAK, "Speak answers", SettingType.BOOLEAN, AssistantEngine.SPEAK_DEFAULT.toString(),
                help = "Speak answers with the on-device voice. Off by default: on this display the voice takes about 35 s to load and speaks slower than real time, so answers are shown instead."),
            SettingSpec(KEY_SPEAK_AT_NIGHT, "Speak at night", SettingType.BOOLEAN, "false",
                help = "While the display is in night mode, answers are shown but not spoken unless this is on."),
            SettingSpec(KEY_VOICE, "Voice", SettingType.CHOICE, defaultVoice,
                help = "On-device voices pushed to the display (see the assistant setup guide to add more).",
                options = voices.ifEmpty { listOf(defaultVoice to "Amy (US English)") }),
            SettingSpec(KEY_SPEECH_RATE, "Speaking speed", SettingType.FLOAT, "1.0", min = 0.7f, max = 1.4f, step = 0.05f),
            SettingSpec(KEY_DISMISS_S, "Keep the answer on screen (seconds)", SettingType.INT,
                AssistantEngine.DISMISS_S.toString(), min = 5f, max = 60f, step = 1f),
            SettingSpec(KEY_NAME, "Assistant name", SettingType.TEXT, "Nova", advanced = true,
                help = "What Claude calls itself. The wake phrase is set separately (kws/keywords.txt)."),
            SettingSpec(KEY_KEEP_STT, "Keep speech recognition loaded", SettingType.BOOLEAN, "false", advanced = true,
                help = "Starts listening faster, but keeps about 40 MB in memory."),
            SettingSpec(KEY_KEEP_TTS, "Keep the voice loaded", SettingType.BOOLEAN, "false", advanced = true,
                help = "Starts speaking faster (the voice takes about 35 s to load), but keeps about 70 MB in memory."),
        ),
    )
}
