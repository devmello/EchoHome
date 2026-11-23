package app.vesper.feature.alarm

import android.content.Context
import android.content.Intent
import android.util.Log
import app.vesper.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What [AlarmService] should be doing. Empty = the service stops. */
internal data class SoundRequest(
    /** An alarm is ringing: the default alarm sound, ramping up. */
    val alarm: Boolean = false,
    val alarmTitle: String = "",
    /** The ringing alarm's owner's own sound (Profile.alarmSound); null = the `alarm.sound` setting. */
    val alarmSound: String? = null,
    /** A timer finished: the synthesized chime (only when no alarm is ringing). */
    val timer: Boolean = false,
    val timerTitle: String = "",
    /** Sunrise ramp: no sound, but keep the process in the foreground so the ramp keeps going. */
    val keepAlive: Boolean = false,
    val keepAliveTitle: String = "",
) {
    val any get() = alarm || timer || keepAlive
    val sounding get() = alarm || timer
}

/** Process-wide handles for the receivers and the service (they're created by the system). */
internal object AlarmRuntime {
    const val TAG = "VesperAlarm"

    var alarms: RealAlarmStore? = null
    var timers: RealTimerStore? = null
    /** For the service: sound settings. */
    var settings: SettingsStore? = null

    /** The alarm screen is on screen (attached, window visible). The ringing notification stays
     *  quiet while it is, so no heads-up covers the screen. */
    val alarmScreenShown = MutableStateFlow(false)

    private val _sound = MutableStateFlow(SoundRequest())
    val sound: StateFlow<SoundRequest> = _sound.asStateFlow()

    fun request(context: Context, transform: (SoundRequest) -> SoundRequest) {
        val old = _sound.value
        val new = transform(old)
        if (new == old) return
        _sound.value = new
        // The service collects [sound] while it runs; start it (again) whenever there's something to do.
        if (new.any && (!old.any || new.sounding != old.sounding)) {
            try {
                context.startForegroundService(Intent(context, AlarmService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Can't start AlarmService", e)
            }
        }
    }
}
