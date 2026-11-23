package app.vesper.core.model

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Echo Island (NOTES §5.4): a live surface 12 px below the top edge showing ongoing things.
 * Producers (timers, alarm, assistant, AirPlay, doorbell, calls) publish [LiveActivity] items to
 * the [IslandManager]; the renderer in `:feature:system` decides what's shown.
 */
data class LiveActivity(
    val id: String,
    val kind: ActivityKind,
    val content: LiveContent,
    val actions: List<IslandAction> = emptyList(),
    /** Alerts (doorbell, call, alarm ringing, timer done) expand on their own. */
    val alert: Boolean = kind.alertByDefault,
    /** Auto-end after this SystemClock.elapsedRealtime(); null = until ended. */
    val staleAfterElapsed: Long? = null,
    /** Screen id where this activity's full screen lives; the island hides it while that screen is open. */
    val ownerScreen: String? = null,
) {
    val priority: Int get() = kind.priority
}

/** Priority order from NOTES §5.4, highest first. */
enum class ActivityKind(val priority: Int, val alertByDefault: Boolean) {
    CALL(100, true),
    DOORBELL(90, true),
    ALARM_RINGING(80, true),
    TIMER_DONE(70, true),
    ASSISTANT(60, false),
    MUSIC(50, false),
    TIMER(40, false),
    /** "Leave in 10 min" before an event with a location. */
    LEAVE_NOW(65, true),
    /** "Rain in 20 min". */
    WEATHER_ALERT(35, false),
    AIRPLAY_CONNECTED(30, false),
    BLUETOOTH_CONNECTED(29, false),
    /** iPhone battery (on connect for a few seconds, and when low). */
    PHONE_BATTERY(28, false),
    /** The camera is checking who's there (a short burst), then "Hi <name>" for 4 s; above music so it's seen. */
    FACE_CHECK(55, false),
}

/** What the island shows. The renderer lays out compact (44 px) and expanded forms per type. */
sealed interface LiveContent {
    /** Running or paused timer. [endsAtElapsed] is SystemClock.elapsedRealtime() at zero. */
    data class Timer(
        val label: String,
        val durationMs: Long,
        val endsAtElapsed: Long,
        val pausedRemainingMs: Long? = null,
    ) : LiveContent {
        fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long =
            pausedRemainingMs ?: (endsAtElapsed - now).coerceAtLeast(0)
    }

    data class TimerDone(val label: String) : LiveContent

    data class AlarmRinging(val label: String, val timeText: String) : LiveContent

    /** Music: reads the live position from [MediaHub]; this is the identity + art. */
    data class Music(
        val title: String,
        val artist: String,
        val artwork: Bitmap?,
        val playing: Boolean,
        val source: MediaSourceKind,
    ) : LiveContent

    data class Assistant(val phase: AssistantPhase, /** 0..1 voice level for the bars. */ val level: Float = 0f) : LiveContent

    data class Doorbell(val camera: String, val snapshot: Bitmap? = null) : LiveContent

    /**
     * Incoming call. From the iPhone (ANCS) calls are answered on the phone: the producer offers
     * only Decline/Silence actions and [subtitle] says e.g. "Answer on iPhone".
     */
    data class Call(val caller: String, val subtitle: String, val video: Boolean) : LiveContent

    data class AirPlayConnected(val deviceName: String) : LiveContent

    /** A phone is connected over Bluetooth (audio and/or notifications). */
    /** Leave at [leaveAtMillis] (wall clock) for [title]; [travelMinutes] door to door. */
    data class LeaveNow(val title: String, val leaveAtMillis: Long, val travelMinutes: Int, val destination: String? = null) : LiveContent

    /** Rain starting in [minutes] (0 = now) from the nowcast; [heavy] ≥ 2.5 mm per 15 min. */
    data class RainSoon(val minutes: Int, val heavy: Boolean = false) : LiveContent

    data class PhoneBattery(val deviceName: String, val percent: Int, val charging: Boolean?) : LiveContent

    data class BluetoothConnected(val deviceName: String, val audio: Boolean, val notifications: Boolean) : LiveContent

    /** The camera is matching a face ("Checking face" + spinner). Ends when the burst ends. */
    data object FaceCheck : LiveContent

    /** The face check matched [name]: their avatar ([color], first letter) and "Hi <name>", while the greeting is up. */
    data class FaceHello(val name: String, val color: Int) : LiveContent
}

enum class AssistantPhase { LISTENING, WORKING, SPEAKING }

/**
 * @param style PRIMARY is tinted (one per surface, Liquid Glass rule 4), DESTRUCTIVE is red
 *   (Decline), ACCEPT is green (Accept call).
 */
data class IslandAction(
    val id: String,
    val label: String,
    val style: Style = Style.NORMAL,
    val onInvoke: () -> Unit,
) {
    enum class Style { NORMAL, PRIMARY, DESTRUCTIVE, ACCEPT }
}

interface IslandManager {
    /** Every live activity, unordered. Use [visible] for what the island shows. */
    val activities: StateFlow<List<LiveActivity>>

    /** Adds the activity, or replaces the one with the same id. */
    fun publish(activity: LiveActivity)
    fun update(id: String, transform: (LiveActivity) -> LiveActivity)
    fun end(id: String)

    /** Screen ids that hide the whole island while they're the current screen (see [hideOnScreen]). */
    val hiddenOnScreens: StateFlow<Set<String>> get() = NO_SCREENS

    /**
     * Hides the island entirely while [screenId] is current (a full-screen moment whose layout the
     * island would cover, e.g. the ringing / snoozed alarm). Call once at registration; the island
     * comes back when another screen is shown.
     */
    fun hideOnScreen(screenId: String, hidden: Boolean = true) {}

    /**
     * Main + bead: the two highest-priority activities that aren't stale and whose
     * [LiveActivity.ownerScreen] isn't [currentScreen]. Nothing while [currentScreen] is in [hiddenOnScreens].
     */
    fun visible(currentScreen: String?, now: Long = SystemClock.elapsedRealtime()): List<LiveActivity> =
        if (currentScreen != null && currentScreen in hiddenOnScreens.value) emptyList()
        else activities.value
            .filter { (it.staleAfterElapsed == null || it.staleAfterElapsed > now) && (it.ownerScreen == null || it.ownerScreen != currentScreen) }
            .sortedByDescending { it.priority }
            .take(2)

    companion object {
        private val NO_SCREENS: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
    }
}

class DefaultIslandManager : IslandManager {
    private val _activities = MutableStateFlow<List<LiveActivity>>(emptyList())
    override val activities: StateFlow<List<LiveActivity>> = _activities.asStateFlow()
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    override val hiddenOnScreens: StateFlow<Set<String>> = _hidden.asStateFlow()

    override fun hideOnScreen(screenId: String, hidden: Boolean) =
        _hidden.update { if (hidden) it + screenId else it - screenId }

    override fun publish(activity: LiveActivity) =
        _activities.update { list -> list.filterNot { it.id == activity.id } + activity }

    override fun update(id: String, transform: (LiveActivity) -> LiveActivity) =
        _activities.update { list -> list.map { if (it.id == id) transform(it) else it } }

    override fun end(id: String) = _activities.update { list -> list.filterNot { it.id == id } }
}
