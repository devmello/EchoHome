package app.vesper.feature.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.vesper.core.model.Alarm
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Debug builds only: drive the alarm and timer stores from adb (there's no alarm-editing screen in
 * the designs yet; the assistant / Home Assistant will call services.alarms). Examples:
 *
 *     B="am broadcast -a app.vesper.alarm.DEBUG -n app.vesper/app.vesper.feature.alarm.AlarmDebugReceiver"
 *     $B --es cmd add --es at +2 --es label "Wake Up" --ez sunrise true --ei sunriseMin 1
 *     $B --es cmd add --es at 07:00 --es days MON,TUE,WED,THU,FRI
 *     $B --es cmd add --es at 07:00 --es days MON,TUE,WED,THU,FRI --ez leaveBy true --ei ready 45 --ez skipHolidays true
 *     $B --es cmd shift --es id <alarm id> --ei earlier 10 --es title Dentist --ei travel 25
 *     $B --es cmd list          # logs alarms + phase (tag VesperAlarm)
 *     $B --es cmd clear         # deletes every alarm
 *     $B --es cmd snooze | stop | dismiss
 *     $B --es cmd timer --ei sec 30 --es label Tea
 *     $B --es cmd timers_clear
 */
class AlarmDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarms = AlarmRuntime.alarms ?: return
        val timers = AlarmRuntime.timers ?: return
        when (intent.getStringExtra("cmd")) {
            "add" -> {
                val at = intent.getStringExtra("at") ?: "+2"
                val time = if (at.startsWith("+")) LocalTime.now().plusMinutes(at.drop(1).toLong()) else LocalTime.parse(at.padStart(5, '0'))
                val days = intent.getStringExtra("days")?.split(',')?.filter { it.isNotBlank() }?.map { d ->
                    DayOfWeek.entries.first { it.name.startsWith(d.trim().uppercase()) }
                }?.toSet() ?: emptySet()
                val a = Alarm(
                    id = intent.getStringExtra("id") ?: "dbg-" + System.currentTimeMillis(),
                    hour = time.hour, minute = time.minute, days = days,
                    label = intent.getStringExtra("label") ?: "Wake Up",
                    sunrise = intent.getBooleanExtra("sunrise", true),
                    sunriseMinutes = intent.getIntExtra("sunriseMin", 30),
                    snoozeMinutes = intent.getIntExtra("snoozeMin", 9),
                    leaveBy = intent.getBooleanExtra("leaveBy", false),
                    readyMinutes = intent.getIntExtra("ready", 45),
                    skipHolidays = intent.getBooleanExtra("skipHolidays", false),
                )
                alarms.upsert(a)
                Log.i(AlarmRuntime.TAG, "debug: added $a; next ${alarms.next.value}")
            }
            // Plants a leave-by plan for the alarm's next date: --es id X --ei earlier 10 --es title Dentist --ei travel 25 [--ei startMin 480]
            "shift" -> {
                val id = intent.getStringExtra("id") ?: return
                val a = alarms.alarms.value.firstOrNull { it.id == id } ?: return
                val date = alarms.next.value?.takeIf { it.alarm.id == id }?.let { java.time.Instant.ofEpochMilli(it.triggerAtMillis + intent.getIntExtra("earlier", 10) * 60_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                    ?: java.time.LocalDate.now()
                val start = date.atStartOfDay(java.time.ZoneId.systemDefault()).plusMinutes(intent.getIntExtra("startMin", a.hour * 60 + a.minute + 60).toLong()).toInstant().toEpochMilli()
                alarms.debugShift(id, date, app.vesper.feature.alarm.engine.LeaveShift(intent.getIntExtra("earlier", 10), intent.getStringExtra("title") ?: "Dentist", start, intent.getIntExtra("travel", 25)))
            }
            "delete" -> intent.getStringExtra("id")?.let { alarms.delete(it) }
            "clear" -> alarms.alarms.value.forEach { alarms.delete(it.id) }
            "snooze" -> alarms.snooze()
            "stop" -> alarms.stop()
            "dismiss" -> alarms.dismissGoodMorning()
            "timer" -> timers.start(intent.getIntExtra("sec", 60) * 1000L, intent.getStringExtra("label") ?: "")
            "timers_clear" -> timers.timers.value.forEach { timers.cancel(it.id) }
            else -> {}
        }
        Log.i(AlarmRuntime.TAG, "debug: alarms=${alarms.alarms.value} next=${alarms.next.value} phase=${alarms.phase.value} timers=${timers.timers.value}")
    }
}
