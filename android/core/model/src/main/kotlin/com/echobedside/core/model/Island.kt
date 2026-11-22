package com.echobedside.core.model

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
    AIRPLAY_CONNECTED(30, false),
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

    data class Call(val caller: String, val subtitle: String, val video: Boolean) : LiveContent

    data class AirPlayConnected(val deviceName: String) : LiveContent
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

    /**
     * Main + bead: the two highest-priority activities that aren't stale and whose
     * [LiveActivity.ownerScreen] isn't [currentScreen].
     */
    fun visible(currentScreen: String?, now: Long = SystemClock.elapsedRealtime()): List<LiveActivity> =
        activities.value
            .filter { (it.staleAfterElapsed == null || it.staleAfterElapsed > now) && (it.ownerScreen == null || it.ownerScreen != currentScreen) }
            .sortedByDescending { it.priority }
            .take(2)
}

class DefaultIslandManager : IslandManager {
    private val _activities = MutableStateFlow<List<LiveActivity>>(emptyList())
    override val activities: StateFlow<List<LiveActivity>> = _activities.asStateFlow()

    override fun publish(activity: LiveActivity) =
        _activities.update { list -> list.filterNot { it.id == activity.id } + activity }

    override fun update(id: String, transform: (LiveActivity) -> LiveActivity) =
        _activities.update { list -> list.map { if (it.id == id) transform(it) else it } }

    override fun end(id: String) = _activities.update { list -> list.filterNot { it.id == id } }
}
