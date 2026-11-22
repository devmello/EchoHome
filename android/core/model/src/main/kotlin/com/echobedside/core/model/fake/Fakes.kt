package com.echobedside.core.model.fake

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.Alarm
import com.echobedside.core.model.AlarmPhase
import com.echobedside.core.model.AlarmStore
import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.CalendarProvider
import com.echobedside.core.model.EchoTimer
import com.echobedside.core.model.HourForecast
import com.echobedside.core.model.IslandManager
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaHub
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.MediaState
import com.echobedside.core.model.NextAlarm
import com.echobedside.core.model.Profile
import com.echobedside.core.model.ProfileManager
import com.echobedside.core.model.SwitchReason
import com.echobedside.core.model.TimerStore
import com.echobedside.core.model.Weather
import com.echobedside.core.model.WeatherCondition
import com.echobedside.core.model.WeatherProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * In-memory fakes for every contract, using the designs' sample data (NOTES §2.1: all sample).
 * They let each feature run alone. Real implementations replace them in EchoServices.
 */

/** Next occurrence of [alarm] after [from], in the device zone. */
fun nextTrigger(alarm: Alarm, from: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
    var day = from.toLocalDate()
    repeat(8) {
        val t = day.atTime(alarm.hour, alarm.minute)
        if (t.isAfter(from) && (alarm.days.isEmpty() || t.dayOfWeek in alarm.days)) return t.atZone(zone).toInstant().toEpochMilli()
        day = day.plusDays(1)
    }
    error("unreachable")
}

/** In-memory alarms; with [demo] it starts with the designs' 7:00 alarm. Never rings by itself. */
class FakeAlarmStore(demo: Boolean = true) : AlarmStore {
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val _alarms = MutableStateFlow(if (!demo) emptyList() else
        listOf(Alarm("demo-7am", 7, 0, emptySet(), "Wake up"), Alarm("demo-weekday", 6, 30, weekdays, "Gym", enabled = false)))
    private val _next = MutableStateFlow<NextAlarm?>(null)
    private val _phase = MutableStateFlow<AlarmPhase>(AlarmPhase.Idle)
    override val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    override val next: StateFlow<NextAlarm?> = _next.asStateFlow()
    override val phase: StateFlow<AlarmPhase> = _phase.asStateFlow()

    init { recompute() }

    private fun recompute() {
        _next.value = _alarms.value.filter { it.enabled }.map { NextAlarm(it, nextTrigger(it)) }.minByOrNull { it.triggerAtMillis }
    }

    override fun upsert(alarm: Alarm) { _alarms.update { l -> l.filterNot { it.id == alarm.id } + alarm }; recompute() }
    override fun delete(id: String) { _alarms.update { l -> l.filterNot { it.id == id } }; recompute() }
    override fun setEnabled(id: String, enabled: Boolean) { _alarms.update { l -> l.map { if (it.id == id) it.copy(enabled = enabled) else it } }; recompute() }

    /** Demo helper: start ringing now. */
    fun ringNow(alarm: Alarm = _alarms.value.first()) { _phase.value = AlarmPhase.Ringing(alarm, System.currentTimeMillis()) }

    override fun snooze() {
        val p = _phase.value as? AlarmPhase.Ringing ?: return
        _phase.value = AlarmPhase.Snoozed(p.alarm, System.currentTimeMillis() + p.alarm.snoozeMinutes * 60_000L)
    }
    override fun stop() {
        val a = when (val p = _phase.value) { is AlarmPhase.Ringing -> p.alarm; is AlarmPhase.Snoozed -> p.alarm; else -> return }
        _phase.value = AlarmPhase.GoodMorning(a)
    }
    override fun dismissGoodMorning() { _phase.value = AlarmPhase.Idle }
}

/** Timers that tick with a main-thread Handler and mirror themselves to the island. */
class FakeTimerStore(private val island: IslandManager? = null) : TimerStore {
    private val _timers = MutableStateFlow<List<EchoTimer>>(emptyList())
    override val timers: StateFlow<List<EchoTimer>> = _timers.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            _timers.update { l -> l.map { if (it.state == EchoTimer.State.RUNNING && it.endsAtElapsed <= now) it.copy(state = EchoTimer.State.DONE) else it } }
            sync()
            if (_timers.value.any { it.state == EchoTimer.State.RUNNING }) handler.postDelayed(this, 250)
        }
    }

    override fun start(durationMs: Long, label: String): EchoTimer? {
        if (_timers.value.size >= TimerStore.MAX_TIMERS) return null
        val t = EchoTimer(UUID.randomUUID().toString(), label, durationMs, SystemClock.elapsedRealtime() + durationMs, EchoTimer.State.RUNNING)
        _timers.update { it + t }
        restartTick()
        return t
    }
    override fun pause(id: String) = mutate(id) { if (it.state == EchoTimer.State.RUNNING) it.copy(state = EchoTimer.State.PAUSED, pausedRemainingMs = it.remainingMs()) else it }
    override fun resume(id: String) = mutate(id) { if (it.state == EchoTimer.State.PAUSED) it.copy(state = EchoTimer.State.RUNNING, endsAtElapsed = SystemClock.elapsedRealtime() + it.pausedRemainingMs) else it }
    override fun addTime(id: String, ms: Long) = mutate(id) {
        when (it.state) {
            EchoTimer.State.RUNNING -> it.copy(endsAtElapsed = it.endsAtElapsed + ms, durationMs = it.durationMs + ms)
            EchoTimer.State.PAUSED -> it.copy(pausedRemainingMs = it.pausedRemainingMs + ms, durationMs = it.durationMs + ms)
            EchoTimer.State.DONE -> it
        }
    }
    override fun cancel(id: String) { _timers.update { l -> l.filterNot { it.id == id } }; sync() }
    override fun dismiss(id: String) = cancel(id)

    private fun mutate(id: String, f: (EchoTimer) -> EchoTimer) { _timers.update { l -> l.map { if (it.id == id) f(it) else it } }; restartTick() }
    private fun restartTick() { handler.removeCallbacks(tick); tick.run() }

    private fun sync() {
        val island = island ?: return
        val ids = _timers.value.map { "timer:" + it.id }.toSet()
        island.activities.value.filter { it.id.startsWith("timer:") && it.id !in ids }.forEach { island.end(it.id) }
        for (t in _timers.value) {
            val content = if (t.state == EchoTimer.State.DONE) LiveContent.TimerDone(t.label)
                else LiveContent.Timer(t.label, t.durationMs, t.endsAtElapsed, if (t.state == EchoTimer.State.PAUSED) t.pausedRemainingMs else null)
            val kind = if (t.state == EchoTimer.State.DONE) ActivityKind.TIMER_DONE else ActivityKind.TIMER
            val existing = island.activities.value.firstOrNull { it.id == "timer:" + t.id }
            if (existing == null || existing.content != content) island.publish(LiveActivity("timer:" + t.id, kind, content, ownerScreen = "timers"))
        }
    }
}

class FakeProfileManager : ProfileManager {
    private val _profiles = MutableStateFlow(listOf(
        Profile.SHARED,
        Profile("maya", "Maya", 0xFFFF375F.toInt(), faceId = "analog", enrolled = true),
        Profile("sam", "Sam", 0xFF30D158.toInt(), faceId = "digital", enrolled = true),
    ))
    private val _active = MutableStateFlow(Profile.SHARED)
    private val _auto = MutableStateFlow(true)
    override val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    override val active: StateFlow<Profile> = _active.asStateFlow()
    override val autoByFace: StateFlow<Boolean> = _auto.asStateFlow()
    override fun switchTo(profileId: String, reason: SwitchReason) { _profiles.value.firstOrNull { it.id == profileId }?.let { _active.value = it } }
    override fun setAutoByFace(enabled: Boolean) { _auto.value = enabled }
    override fun reject(profileId: String) { _active.value = Profile.SHARED }
    override fun upsert(profile: Profile) = _profiles.update { l -> l.filterNot { it.id == profile.id } + profile }
    override fun delete(profileId: String) = _profiles.update { l -> l.filterNot { it.id == profileId } }
}

/** Digital.dc.html sample: 54°, H:61° L:47°, partly cloudy night, Santa Cruz. null when [demo] is off. */
class FakeWeatherProvider(demo: Boolean) : WeatherProvider {
    override val weather: StateFlow<Weather?> = MutableStateFlow(if (!demo) null else run {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val top = now - now % hour
        Weather(
            temperature = 54, high = 61, low = 47, condition = WeatherCondition.PARTLY_CLOUDY, isNight = true,
            hourly = (1..6).map { HourForecast(top + it * hour, 54 - it / 2, WeatherCondition.PARTLY_CLOUDY, if (it > 4) 30 else 10) },
            tomorrowSummary = "Rain tomorrow from 9 AM", location = "Santa Cruz",
        )
    })
}

/** Calendar.dc.html sample events (minutes since midnight today / tomorrow). Empty when [demo] is off. */
class FakeCalendarProvider(demo: Boolean) : CalendarProvider {
    override val events: StateFlow<List<CalendarEvent>> = MutableStateFlow(if (!demo) emptyList() else run {
        val zone = ZoneId.systemDefault()
        fun at(day: LocalDate, min: Int) = day.atStartOfDay(zone).plusMinutes(min.toLong()).toInstant().toEpochMilli()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        fun ev(id: String, d: LocalDate, s: Int, e: Int, title: String, sub: String, color: Long) =
            CalendarEvent(id, title, sub, at(d, s), at(d, e), color = color.toInt())
        listOf(
            ev("1", today, 600, 690, "Farmers market", "Downtown · with Maya", 0xFF30D158),
            ev("2", today, 870, 900, "Family call", "Video call", 0xFF0A84FF),
            ev("3", today, 1140, 1260, "Dinner with Maya", "Seabright · 2 people", 0xFFFF9F0A),
            ev("4", tomorrow, 570, 600, "Team sync", "Video call · 4 people", 0xFFBF5AF2),
            ev("5", tomorrow, 720, 780, "Lunch", "Penny Ice Creamery", 0xFFFF375F),
        )
    })
}

/**
 * NowPlaying.dc.html sample tracks as a fake AirPlay source, for building Now Playing and the
 * music island before `:feature:airplay` lands. Call [start] from a debug path only.
 */
class FakeMediaSource(private val hub: MediaHub) : MediaControls {
    private data class Track(val title: String, val artist: String, val album: String, val durS: Int)
    private val tracks = listOf(
        Track("Low Tide Radio", "Marisol Vey", "Coastline Hours", 227),
        Track("Paper Satellites", "The Northern Lakes", "Small Orbits", 252),
        Track("Afterglow (Slow)", "Kito Rae", "Night Swim", 201),
    )
    private var i = 0
    private var state = MediaState("", "", source = MediaSourceKind.AIRPLAY)

    fun start() { load(0, 83_000, playing = true) }
    fun stop() = hub.clear(ID)

    private fun load(index: Int, posMs: Long, playing: Boolean) {
        i = (index + tracks.size) % tracks.size
        val t = tracks[i]
        state = MediaState(t.title, t.artist, t.album, null, t.durS * 1000L, posMs, SystemClock.elapsedRealtime(), playing,
            MediaSourceKind.AIRPLAY, "Maya's iPhone", volume = state.volume ?: 0.6f)
        hub.publish(ID, state, this)
    }
    private fun pos() = state.positionNow()
    override fun play() = load(i, pos(), true)
    override fun pause() = load(i, pos(), false)
    override fun next() = load(i + 1, 0, state.playing)
    override fun previous() = load(if (pos() > 3000) i else i - 1, 0, state.playing)
    override fun seekTo(positionMs: Long) = load(i, positionMs, state.playing)
    override fun setVolume(volume: Float) { state = state.copy(volume = volume); hub.publish(ID, state, this) }

    companion object { const val ID = "fake-airplay" }
}
