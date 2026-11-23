package app.vesper.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sleep sounds (`:feature:sleep` synthesises them on the device; no audio files). */
enum class SleepSound(val id: String, val title: String) {
    RAIN("rain", "Rain"),
    OCEAN("ocean", "Ocean"),
    WHITE("white", "White noise"),
    PINK("pink", "Pink noise"),
    BROWN("brown", "Brown noise"),
    FAN("fan", "Fan"),
    CRICKETS("crickets", "Crickets");

    companion object {
        /** By id or title, any case ("rain", "Brown noise", "brown"); null if unknown. */
        fun of(s: String?): SleepSound? {
            val k = s?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.id == k || it.title.lowercase() == k || it.title.lowercase() == "$k noise" }
        }
    }
}

/**
 * What the sleep player is doing. Times are `SystemClock.elapsedRealtime()`.
 * [sound] is set while a sound is loaded (playing or paused); null = stopped.
 */
data class SleepState(
    val sound: SleepSound? = null,
    val playing: Boolean = false,
    /** The sleep timer: 15 / 30 / 60 / 90, or 0 = plays until stopped. */
    val timerMinutes: Int = 0,
    /** When the sound is silent and stops (the end of the fade-out); null without a timer. */
    val endsAtElapsed: Long? = null,
    /** The last [SleepSounds.FADE_OUT_MS] before [endsAtElapsed]. */
    val fading: Boolean = false,
)

/** Bedtime routine overrides; null = the saved setting. */
data class BedtimeRequest(
    val sound: SleepSound? = null,
    /** 0 = no timer. */
    val timerMinutes: Int? = null,
    val playSound: Boolean? = null,
    val dim: Boolean? = null,
)

/** What the bedtime routine did (for the assistant's answer and the sleep screen). */
data class BedtimeReport(
    /** Wall clock of the next alarm, null if none is set. */
    val alarmAtMillis: Long?,
    val alarmLabel: String?,
    val dndOn: Boolean,
    /** Wall clock when Do Not Disturb turns itself off (the alarm, or the fallback wake time). */
    val dndUntilMillis: Long?,
    val nightOn: Boolean,
    val sound: SleepSound?,
    val timerMinutes: Int,
    /** 0..1 brightness it dimmed to, null = not dimmed. */
    val dimmedTo: Float?,
    /** Steps that were asked for but didn't work ("Do Not Disturb", "Night mode", "Sleep sound"). */
    val failed: List<String> = emptyList(),
)

/**
 * Sleep sounds + the bedtime routine (`:feature:sleep` binds `services.sleep`). Nothing plays
 * until [play] or [bedtime] is called: the player never starts by itself, not after a restart.
 * While playing it is also a media session in [MediaHub] ([SOURCE_ID]), so Now Playing, the
 * island and Control Center show it; AirPlay / Bluetooth / Spotify starting pauses it.
 */
interface SleepSounds {
    val state: StateFlow<SleepState>
    /** false = no player in this build (the default slot). */
    val available: Boolean get() = true
    /** Starts [sound] (null = the saved default) with [timerMinutes] (null = the saved default, 0 = off). */
    fun play(sound: SleepSound? = null, timerMinutes: Int? = null): Boolean
    fun stop()
    /** Restarts the sleep timer from now; 0 = off. */
    fun setTimer(minutes: Int)
    /** Next alarm, Do Not Disturb until it, Night mode, the sleep sound with its timer, optional dim. */
    fun bedtime(request: BedtimeRequest = BedtimeRequest()): BedtimeReport

    companion object {
        const val SOURCE_ID = "sleep"
        const val SCREEN_ID = "sleep"
        const val FADE_OUT_MS = 30_000L
        /** The timer choices, in the order the screen shows them (0 = off). */
        val TIMER_CHOICES = listOf(15, 30, 60, 90, 0)
    }
}

/** Until `:feature:sleep` binds the real one. */
object NoSleepSounds : SleepSounds {
    private val idle = MutableStateFlow(SleepState()).asStateFlow()
    override val state: StateFlow<SleepState> = idle
    override val available: Boolean get() = false
    override fun play(sound: SleepSound?, timerMinutes: Int?): Boolean = false
    override fun stop() {}
    override fun setTimer(minutes: Int) {}
    override fun bedtime(request: BedtimeRequest) = BedtimeReport(null, null, false, null, false, null, 0, null, listOf("Bedtime"))
}
