package app.vesper.feature.home.alerts

import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.CalendarProvider
import app.vesper.core.model.IslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Profile
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.TravelEstimator
import app.vesper.feature.home.commute.CommuteMath
import app.vesper.feature.home.commute.CommuteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Leave-now maths and rules (pure, unit-tested). */
object LeaveRule {
    const val DEFAULT_BUFFER_MIN = 5
    const val DEFAULT_LEAD_MIN = 15
    /** The reminder ends this long after the leave time (and never later than the event start). */
    const val END_AFTER_LEAVE_MS = 10 * 60_000L
    /** Events further away than this aren't looked at. */
    const val HORIZON_MS = 6 * 3_600_000L

    /** start − travel − buffer. */
    fun leaveAt(startMillis: Long, travelMinutes: Int, bufferMin: Int = DEFAULT_BUFFER_MIN): Long =
        startMillis - (travelMinutes + bufferMin) * 60_000L

    enum class Phase { LATER, SHOW, DONE }

    /** LATER until [leadMin] before the leave time, SHOW until the event starts or 10 min after the leave time, then DONE. */
    fun phase(now: Long, startMillis: Long, leaveAt: Long, leadMin: Int): Phase {
        val endAt = minOf(startMillis, leaveAt + END_AFTER_LEAVE_MS)
        return when {
            now >= endAt -> Phase.DONE
            now >= leaveAt - leadMin * 60_000L -> Phase.SHOW
            else -> Phase.LATER
        }
    }

    /** Today's timed events that have somewhere to drive to and haven't started, soonest first. [active]: whose calendar counts (null = everyone's). */
    fun candidates(events: List<CalendarEvent>, now: Long, active: Profile?): List<CalendarEvent> =
        events.filter { e ->
            !e.allDay && !e.location.isNullOrBlank() && !CommuteMath.looksVirtual(e.location.orEmpty()) &&
                e.startMillis > now && e.startMillis - now <= HORIZON_MS &&
                (e.profileId == null || active == null || active.id == Profile.SHARED_ID || e.profileId == active.id)
        }.sortedBy { it.startMillis }

    /** What the island shows. */
    data class Plan(val event: CalendarEvent, val travelMinutes: Int, val leaveAt: Long) {
        val endAt get() = minOf(event.startMillis, leaveAt + END_AFTER_LEAVE_MS)
        /** "Downtown" from "Downtown, 120 Pacific Ave". */
        val destination: String get() = event.location.orEmpty().substringBefore('\n').substringBefore(',').trim()
    }

    /** The plan to show now: of those in [Phase.SHOW], the one to leave for first. */
    fun pick(plans: List<Plan>, now: Long, leadMin: Int): Plan? =
        plans.filter { phase(now, it.event.startMillis, it.leaveAt, leadMin) == Phase.SHOW }.minByOrNull { it.leaveAt }

    /** Milliseconds until the first plan that is still LATER enters its window (null = none). */
    fun untilNext(plans: List<Plan>, now: Long, leadMin: Int): Long? =
        plans.filter { phase(now, it.event.startMillis, it.leaveAt, leadMin) == Phase.LATER }
            .minOfOrNull { it.leaveAt - leadMin * 60_000L - now }
}

/**
 * "Leave now" on the island: for today's timed events with a place, [LeaveRule.leaveAt] = start −
 * travel − buffer (`commute.bufferMin`, 5), and ~[AlertSettings.LEAVE_LEAD] min before it the island
 * shows `LiveContent.LeaveNow`. It follows travel updates and ends at the event start or 10 min after
 * the leave time. Travel comes from `services.travel` (cached there). Wakeups: only the next window
 * (at most every 15 min to re-check travel), once a minute while showing, and calendar/setting changes.
 */
class LeaveNowWatcher(
    private val settings: SettingsStore,
    private val calendar: () -> CalendarProvider,
    private val travel: () -> TravelEstimator,
    private val activeProfile: () -> Profile?,
    private val island: IslandManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val changes: () -> Flow<Unit> = { kotlinx.coroutines.flow.emptyFlow() },
) {
    private var shown: LeaveRule.Plan? = null
    private var started = false
    /** Debug: a made-up event and its travel minutes (the `leave_demo` launch extra). */
    @Volatile private var demo: Pair<CalendarEvent, Int>? = null
    private val kick = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            yield() // the other features finish installing first (calendar and travel are read lazily)
            merge(calendar().events.map { }, settings.changes.filter { it.startsWith("leave.") || it == CommuteSettings.BUFFER_MIN }.map { }, kick, changes())
                .onStart { emit(Unit) }.collectLatest { run() }
        }
    }

    fun demo(event: CalendarEvent, travelMinutes: Int) { demo = event to travelMinutes; kick.tryEmit(Unit) }

    /** One evaluation loop; restarted (collectLatest) on every calendar / setting change. */
    private suspend fun run() {
        while (true) {
            if (!AlertSettings.bool(settings, AlertSettings.LEAVE, true)) { end("off"); return }
            val now = clock()
            val lead = AlertSettings.int(settings, AlertSettings.LEAVE_LEAD, LeaveRule.DEFAULT_LEAD_MIN).coerceIn(1, 120)
            val buffer = AlertSettings.int(settings, CommuteSettings.BUFFER_MIN, LeaveRule.DEFAULT_BUFFER_MIN).coerceIn(0, 60)
            val d = demo
            val events = if (d != null) listOf(d.first) else calendar().events.value
            val plans = LeaveRule.candidates(events, now, activeProfile()).mapNotNull { e ->
                val minutes = d?.second ?: travel().minutesTo(e.location.orEmpty(), e.startMillis) ?: return@mapNotNull null
                LeaveRule.Plan(e, minutes, LeaveRule.leaveAt(e.startMillis, minutes, buffer))
            }
            val pick = LeaveRule.pick(plans, now, lead)
            if (pick == null) end("none") else show(pick)
            // Sleep until something can change: a minute while showing, else the next window (re-checking travel at least every 15 min).
            val next = LeaveRule.untilNext(plans, now, lead)
            val unknownTravel = events.any { e -> LeaveRule.candidates(listOf(e), now, activeProfile()).isNotEmpty() && plans.none { it.event.id == e.id } }
            val sleep = when {
                pick != null -> 60_000L - clock() % 60_000L + 50
                next != null -> minOf(next + 1_000L, 15 * 60_000L)
                unknownTravel -> 10 * 60_000L
                else -> return // nothing coming up: wait for a calendar change
            }
            delay(sleep.coerceAtLeast(15_000L))
        }
    }

    private fun show(p: LeaveRule.Plan) {
        val content = LiveContent.LeaveNow(p.event.title, p.leaveAt, p.travelMinutes, p.destination.ifEmpty { null })
        if (shown?.event?.id == p.event.id) {
            if (shown == p) return
            shown = p
            island.update(ID) { it.copy(content = content, staleAfterElapsed = staleAt(p)) }
            Log.i(TAG, "leave now: updated, ${p.travelMinutes} min drive")
            return
        }
        shown = p
        island.publish(LiveActivity(id = ID, kind = ActivityKind.LEAVE_NOW, content = content,
            // The island drops it by itself at the end time even if this process stops evaluating.
            staleAfterElapsed = staleAt(p)))
        Log.i(TAG, "leave now: published, leave at ${p.leaveAt}, ${p.travelMinutes} min drive")
    }

    private fun staleAt(p: LeaveRule.Plan) = elapsed() + (p.endAt - clock()).coerceAtLeast(0L) + 30_000L

    private fun end(why: String) {
        if (shown == null) return
        shown = null
        island.end(ID)
        Log.i(TAG, "leave now: ended ($why)")
    }

    companion object {
        const val ID = "leave-now"
        const val TAG = "VesperLeave"
    }
}
