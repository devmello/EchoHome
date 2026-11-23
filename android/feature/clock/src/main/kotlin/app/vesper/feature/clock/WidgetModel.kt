package app.vesper.feature.clock

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.SettingsStore
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/** The widgets a StandBy stack can show, in stack order (a swipe up goes to the next one). */
enum class WidgetKind(val id: String, val title: String) {
    CLOCK("clock", "Clock"),
    WEATHER("weather", "Weather"),
    CALENDAR("calendar", "Calendar"),
    ALARM("alarm", "Next alarm"),
    TIMERS("timers", "Timers"),
    NOW_PLAYING("nowplaying", "Now Playing"),
    AIR("air", "Air & UV"),
    SUN_MOON("sunmoon", "Sun & Moon");

    companion object {
        fun byId(id: String?): WidgetKind? = id?.trim()?.lowercase()?.let { s -> entries.firstOrNull { it.id == s } }
    }
}

/** The two stacks' settings and the order arithmetic (pure, unit-tested). */
object WidgetStacks {
    const val KEY_LEFT = "clock.widgets.left"
    const val KEY_RIGHT = "clock.widgets.right"
    const val KEY_WALLPAPER = "clock.widgets.wallpaper"
    val DEFAULT_LEFT = WidgetKind.CLOCK
    val DEFAULT_RIGHT = WidgetKind.WEATHER
    const val DEFAULT_WALLPAPER = "Graphite"
    /** No wallpaper: the tiles are glass over black (rim and fill only). */
    const val WALLPAPER_BLACK = "Black"

    val ORDER: List<WidgetKind> = WidgetKind.entries

    fun options(): List<Pair<String, String>> = ORDER.map { it.id to it.title }

    /** A stored value back to a widget; unknown or empty values fall back to [default]. */
    fun parse(value: String?, default: WidgetKind): WidgetKind = WidgetKind.byId(value) ?: default

    fun wrap(i: Int, n: Int): Int = ((i % n) + n) % n

    /** The widget [dir] steps from [kind] (+1 next, -1 previous), wrapping around the ends. */
    fun step(kind: WidgetKind, dir: Int, order: List<WidgetKind> = ORDER): WidgetKind {
        val i = order.indexOf(kind).coerceAtLeast(0)
        return order[wrap(i + dir, order.size)]
    }

    fun indexOf(kind: WidgetKind, order: List<WidgetKind> = ORDER): Int = order.indexOf(kind)
}

/** One stack's current widget, persisted under [key] (`clock.widgets.left` / `.right`). */
class WidgetStackState(private val settings: SettingsStore, val key: String, val default: WidgetKind) {
    var current: WidgetKind = read()
        private set

    private fun read(): WidgetKind = WidgetStacks.parse(settings.getString(key, default.id), default)

    /** A swipe: steps by [dir], stores it and returns the new widget. */
    fun move(dir: Int): WidgetKind {
        current = WidgetStacks.step(current, dir)
        settings.putString(key, current.id)
        return current
    }

    /** The setting changed (web settings page, a launch extra): true if the widget is a different one now. */
    fun reload(): Boolean {
        val k = read()
        if (k == current) return false
        current = k
        return true
    }
}

/** Gesture decisions for a stack (pure). */
object StackGesture {
    enum class Axis { UNDECIDED, VERTICAL, HORIZONTAL }

    /**
     * Claims a drag only once it is clearly vertical (past [slop] and 1.2× steeper than wide);
     * anything wider than tall is left to the pager, which takes it at 1.5× wider than tall.
     */
    fun decide(dx: Float, dy: Float, slop: Float): Axis {
        val ax = abs(dx); val ay = abs(dy)
        if (ay > slop && ay > ax * 1.2f) return Axis.VERTICAL
        if (ax > slop && ax >= ay) return Axis.HORIZONTAL
        return Axis.UNDECIDED
    }

    /**
     * Where a released drag goes: +1 next (dragged / flung up), -1 previous (down), 0 back.
     * [dy] is the finger's offset (negative = up), [vy] px/s. Past a quarter of [height] commits;
     * a fling commits in its direction unless it goes against the drag.
     */
    fun settle(dy: Float, vy: Float, height: Float, minFling: Float): Int {
        if (vy <= -minFling) return if (dy < 0) 1 else 0
        if (vy >= minFling) return if (dy > 0) -1 else 0
        return when {
            dy <= -height / 4f -> 1
            dy >= height / 4f -> -1
            else -> 0
        }
    }
}

/** What the widgets show, computed from the services' data (pure, unit-tested). */
object WidgetData {
    class AlarmPick(val alarm: Alarm, val triggerAtMillis: Long, val reason: String?)

    /**
     * The alarm to show. Nobody recognised ([personId] null): the store's next alarm. A person:
     * their own alarms plus the shared ones (profileId null); the store's next is used when it is
     * one of those (it knows about leave-by and holidays, and carries the reason).
     */
    fun alarmFor(personId: String?, alarms: List<Alarm>, next: NextAlarm?, now: Long, zone: TimeZone = TimeZone.getDefault()): AlarmPick? {
        val n = next?.takeIf { it.triggerAtMillis > now }
        if (personId == null) return n?.let { AlarmPick(it.alarm, it.triggerAtMillis, it.reason) }
        if (n != null && (n.alarm.profileId == personId || n.alarm.profileId == null)) return AlarmPick(n.alarm, n.triggerAtMillis, n.reason)
        return alarms.filter { it.enabled && (it.profileId == personId || it.profileId == null) }
            .mapNotNull { a -> Personal.nextTrigger(a, now, zone)?.let { AlarmPick(a, it, null) } }
            .minByOrNull { it.triggerAtMillis }
    }

    /** Events for [personId] (theirs + shared), or everyone's when nobody is recognised. */
    fun visible(personId: String?, events: List<CalendarEvent>): List<CalendarEvent> =
        if (personId == null) events else events.filter { it.profileId == personId || it.profileId == null }

    /** Today's events still to come (all-day ones first, then timed ones not yet over), at most [limit]. */
    fun today(events: List<CalendarEvent>, now: Long, limit: Int = 3, zone: TimeZone = TimeZone.getDefault()): List<CalendarEvent> {
        val end = dayStart(now, zone, 1)
        val start = dayStart(now, zone, 0)
        val allDay = events.filter { it.allDay && it.startMillis < end && it.endMillis > start }
        val timed = events.filter { !it.allDay && it.endMillis > now && it.startMillis < end }.sortedBy { it.startMillis }
        return (allDay + timed).take(limit)
    }

    /** The first timed event tomorrow (shown when today has nothing left). */
    fun firstTomorrow(events: List<CalendarEvent>, now: Long, zone: TimeZone = TimeZone.getDefault()): CalendarEvent? {
        val s = dayStart(now, zone, 1); val e = dayStart(now, zone, 2)
        return events.filter { !it.allDay && it.startMillis >= s && it.startMillis < e }.minByOrNull { it.startMillis }
    }

    fun dayStart(now: Long, zone: TimeZone, addDays: Int): Long = Calendar.getInstance(zone).run {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, addDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Timer countdown: "4:05", "1:02:09"; whole seconds rounded up (a timer reads 0:00 only when done). */
    fun countdown(ms: Long): String {
        val s = ceil(ms.coerceAtLeast(0) / 1000.0).toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val ss = sec.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** "in 45 min", "in 7 h 12 min", "in 3 h"; "now" under a minute. */
    fun inText(ms: Long): String {
        val min = (ms + 59_999) / 60_000
        if (min <= 0) return "now"
        val h = min / 60; val m = min % 60
        return when {
            h == 0L -> "in $m min"
            m == 0L -> "in $h h"
            else -> "in $h h $m min"
        }
    }

    fun uvCategory(uv: Float): String = when {
        uv < 3f -> "Low"
        uv < 6f -> "Moderate"
        uv < 8f -> "High"
        uv < 11f -> "Very high"
        else -> "Extreme"
    }

    /** US AQI category colour (EPA scale, Apple system colours where they exist). */
    fun aqiColor(aqi: Int): Int = when {
        aqi <= 50 -> 0xFF30D158.toInt()
        aqi <= 100 -> 0xFFFFD60A.toInt()
        aqi <= 150 -> 0xFFFF9F0A.toInt()
        aqi <= 200 -> 0xFFFF453A.toInt()
        aqi <= 300 -> 0xFFBF5AF2.toInt()
        else -> 0xFFA2264B.toInt()
    }

    fun uvColor(uv: Float): Int = when {
        uv < 3f -> 0xFF30D158.toInt()
        uv < 6f -> 0xFFFFD60A.toInt()
        uv < 8f -> 0xFFFF9F0A.toInt()
        uv < 11f -> 0xFFFF453A.toInt()
        else -> 0xFFBF5AF2.toInt()
    }

    /** Mean synodic month in days. */
    private const val SYNODIC = 29.530589

    /**
     * The next of full / new moon after an elongation (degrees, 0 = new, 180 = full), and the
     * whole days until it (rounded). E.g. at 150° → ("Full moon", 2).
     */
    fun nextMoonEvent(elongation: Double): Pair<String, Int> {
        val perDay = 360.0 / SYNODIC
        val toFull = ((180.0 - elongation) % 360.0 + 360.0) % 360.0
        val toNew = ((360.0 - elongation) % 360.0 + 360.0) % 360.0
        return if (toFull <= toNew) "Full moon" to (toFull / perDay + 0.5).toInt()
        else "New moon" to (toNew / perDay + 0.5).toInt()
    }
}
