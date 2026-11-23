package app.vesper.feature.home.brief

import android.util.Log
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.AlarmStore
import app.vesper.core.model.Navigator
import app.vesper.core.model.Presence
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.SystemStateStore
import app.vesper.feature.home.alerts.AlertSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** When the morning brief shows (pure, unit-tested). */
object BriefRules {
    /** First presence counts between these hours (local), 05:00 inclusive to 11:00 exclusive. */
    const val PRESENCE_FROM_HOUR = 5
    const val PRESENCE_TO_HOUR = 11
    /** Presence must have been EMPTY or UNKNOWN for at least this long ("after the night"). */
    const val ABSENT_MS = 3 * 3_600_000L
    /** After an alarm's Good morning, a wider window (an early alarm, a late lie-in). */
    const val ALARM_FROM_HOUR = 4
    const val ALARM_TO_HOUR = 12

    fun day(now: Long, zone: ZoneId): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    private fun hour(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).hour

    /**
     * Somebody appeared ([Presence.PRESENT] after EMPTY / UNKNOWN). [lastPresentAt] is when the Echo
     * last saw somebody before (0 = never: no brief, we can't tell it was a night). [alarmIdle]: no
     * alarm is ramping, ringing, snoozed or in its Good morning (those hand over to [fromAlarm]).
     */
    fun fromPresence(now: Long, zone: ZoneId, lastPresentAt: Long, lastShownDay: String?, alarmIdle: Boolean, enabled: Boolean = true): Boolean =
        enabled && alarmIdle && lastShownDay != day(now, zone) &&
            hour(now, zone) in PRESENCE_FROM_HOUR until PRESENCE_TO_HOUR &&
            lastPresentAt > 0 && now - lastPresentAt >= ABSENT_MS

    /** The alarm's Good morning is done (Done tapped, or it timed out). */
    fun fromAlarm(now: Long, zone: ZoneId, lastShownDay: String?, enabled: Boolean = true): Boolean =
        enabled && lastShownDay != day(now, zone) && hour(now, zone) in ALARM_FROM_HOUR until ALARM_TO_HOUR

    fun alarmIdle(phase: AlarmPhase) = phase is AlarmPhase.Idle
}

/**
 * Opens the brief screen ([BriefView], id "brief") once each morning: on the first
 * [Presence.PRESENT] between 05:00 and 11:00 after ≥ 3 h without anybody, or when the alarm's Good
 * morning ends ([BriefRules]). Never while an alarm is ramping or ringing. The day and when somebody
 * was last seen are kept in settings so a restart doesn't repeat or forget.
 */
class BriefWatcher(
    private val settings: SettingsStore,
    private val system: () -> SystemStateStore,
    private val alarms: () -> AlarmStore,
    private val navigator: () -> Navigator,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val handoverDelayMs: Long = 600L,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            yield() // other features bind their services while installing; read them after that
            var prev = system().state.value.presence
            system().state.map { it.presence }.distinctUntilChanged().collect { cur ->
                val now = clock()
                if (prev == Presence.PRESENT && cur != Presence.PRESENT) settings.putString(AlertSettings.BRIEF_LAST_PRESENT, now.toString())
                if (cur == Presence.PRESENT && prev != Presence.PRESENT &&
                    BriefRules.fromPresence(now, zone(), AlertSettings.long(settings, AlertSettings.BRIEF_LAST_PRESENT),
                        lastShown(), BriefRules.alarmIdle(alarms().phase.value), enabled())) show("presence")
                prev = cur
            }
        }
        scope.launch {
            yield()
            var wasMorning = alarms().phase.value is AlarmPhase.GoodMorning
            // Whose alarm the Good morning was for: the brief after it is that person's (their calendar).
            var owner = (alarms().phase.value as? AlarmPhase.GoodMorning)?.alarm?.profileId
            alarms().phase.collect { p ->
                val morning = p is AlarmPhase.GoodMorning
                if (p is AlarmPhase.GoodMorning) owner = p.alarm.profileId
                if (wasMorning && !morning && p is AlarmPhase.Idle) {
                    // The alarm screen takes itself back to StandBy when Good morning ends: let it, then open the brief.
                    val forWhom = owner
                    delay(handoverDelayMs)
                    if (BriefRules.fromAlarm(clock(), zone(), lastShown(), enabled()) && alarms().phase.value is AlarmPhase.Idle) show("alarm", forWhom)
                }
                wasMorning = morning
            }
        }
    }

    private fun enabled() = AlertSettings.bool(settings, AlertSettings.BRIEF, true)
    private fun lastShown() = settings.getString(AlertSettings.BRIEF_LAST_DAY, "").ifEmpty { null }

    /** [profileId]: the brief is for that person (after their alarm), else for whoever is recognised. */
    private fun show(why: String, profileId: String? = null) {
        settings.putString(AlertSettings.BRIEF_LAST_DAY, BriefRules.day(clock(), zone()))
        Log.i(TAG, "morning brief: shown ($why${if (profileId != null) ", for a person" else ""})")
        navigator().show(SCREEN_ID, profileId?.let { android.os.Bundle().apply { putString(ARG_PROFILE, it) } })
    }

    companion object {
        const val SCREEN_ID = "brief"
        const val TAG = "VesperBrief"
        /** Screen arg: the profile id the brief is for (an alarm owner). */
        const val ARG_PROFILE = "brief.profile"
    }
}
