package com.echobedside.feature.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that rings: keeps the process alive and the sound going while our activity is
 * in the background or the screen is off. Follows [AlarmRuntime.sound]:
 *  - alarm: the system default alarm sound on the alarm stream, looping, volume ramping up over
 *    the ramp setting; audio focus taken (music pauses / ducks); a screen wake lock; a full-screen-intent
 *    notification that opens the alarm screen if we're not in front.
 *  - timer: the synthesized chime.
 *  - keepAlive (sunrise): silent.
 * Stops itself when the request is empty.
 */
class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private var player: MediaPlayer? = null
    private var fallback: AudioTrack? = null
    private var chime: AudioTrack? = null
    private var ringStart = 0L
    private var rampMs = DEFAULT_RAMP_MS
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var restoreAlarmVolume: Int? = null
    private var current = SoundRequest()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        startForeground(NOTIFICATION_ID, notification(AlarmRuntime.sound.value))
        scope.launch { AlarmRuntime.sound.collect { apply(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs a startForeground().
        startForeground(NOTIFICATION_ID, notification(AlarmRuntime.sound.value))
        return START_NOT_STICKY
    }

    private fun apply(r: SoundRequest) {
        val old = current
        current = r
        if (!r.any) {
            stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (r != old) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(r))
        if (r.alarm) startAlarm() else stopAlarm()
        if (r.timer && !r.alarm) startChime() else stopChime()
        if (r.sounding) { takeFocus(); holdWake() } else { dropFocus(); releaseWake() }
    }

    // --- alarm sound with a volume ramp ---

    private fun startAlarm() {
        if (player != null || fallback != null) return
        val settings = AlarmRuntime.settings
        if (settings != null && AlarmSettings.volumeFloor(settings)) raiseAlarmVolumeFloor()
        rampMs = settings?.let { AlarmSettings.rampMs(it) } ?: DEFAULT_RAMP_MS
        ringStart = SystemClock.elapsedRealtime()
        val sound = settings?.let { AlarmSettings.sound(it) } ?: AlarmSettings.SOUND_SYSTEM
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        player = if (sound != AlarmSettings.SOUND_SYSTEM) null else try {
            MediaPlayer().apply {
                setAudioAttributes(ALARM_ATTRS)
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                setVolume(rampVolume(), rampVolume())
                start()
            }
        } catch (e: Exception) {
            Log.w(AlarmRuntime.TAG, "Default alarm sound failed ($uri), using the synthesized tone", e)
            null
        }
        if (player == null) fallback = runCatching {
            Tones.track(if (sound == AlarmSettings.SOUND_CHIME) Tones.timerChime() else Tones.alarmFallback()).also { it.setVolume(rampVolume()); it.play() }
        }.onFailure { Log.e(AlarmRuntime.TAG, "Alarm tone", it) }.getOrNull()
        handler.post(ramp)
        Log.i(AlarmRuntime.TAG, "Ringing: ${if (player != null) uri else sound} (ramp ${rampMs / 1000} s)")
    }

    private val ramp = object : Runnable {
        override fun run() {
            val v = rampVolume()
            player?.setVolume(v, v)
            fallback?.setVolume(v)
            if (v < 1f && (player != null || fallback != null)) handler.postDelayed(this, 250)
        }
    }

    /** Quadratic (perceptually smoother) from a whisper to full over the ramp setting (45 s). */
    private fun rampVolume(): Float {
        if (rampMs <= 0) return 1f
        val p = ((SystemClock.elapsedRealtime() - ringStart).toFloat() / rampMs).coerceIn(0f, 1f)
        return (0.04f + 0.96f * p * p).coerceIn(0f, 1f)
    }

    private fun stopAlarm() {
        handler.removeCallbacks(ramp)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        fallback?.let { runCatching { it.stop() }; it.release() }
        fallback = null
        restoreAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        restoreAlarmVolume = null
    }

    /** Setting "Make sure alarms are audible" (off by default): lift the alarm stream to 60 % for the ring, then restore. */
    private fun raiseAlarmVolumeFloor() {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val floor = ceil(max * 0.6).toInt()
            if (cur < floor) { audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0); restoreAlarmVolume = cur }
        }.onFailure { Log.w(AlarmRuntime.TAG, "Alarm volume floor", it) }
    }

    // --- timer chime ---

    private fun startChime() {
        if (chime != null) return
        chime = runCatching { Tones.track(Tones.timerChime()).also { it.setVolume(0.8f); it.play() } }
            .onFailure { Log.e(AlarmRuntime.TAG, "Timer chime", it) }.getOrNull()
    }

    private fun stopChime() {
        chime?.let { runCatching { it.stop() }; it.release() }
        chime = null
    }

    // --- focus, wake lock ---

    private fun takeFocus() {
        if (focus != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ALARM_ATTRS).build()
            .also { audio.requestAudioFocus(it) }
    }

    private fun dropFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    @Suppress("DEPRECATION")
    private fun holdWake() {
        if (wake != null) return
        // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP turns the screen on if it was off.
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "echobedside:alarm")
            .apply { acquire(15 * 60_000L) }
    }

    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }

    private fun stopAll() { stopAlarm(); stopChime(); dropFocus(); releaseWake() }

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    // --- notification ---

    private fun notification(r: SoundRequest): Notification {
        // Only a ringing alarm needs the high-importance channel (full-screen intent). Timers stay
        // quiet: the island shows them, and a heads-up would pull SystemUI's status bar in.
        val channel = if (r.alarm) CHANNEL_RING else CHANNEL_QUIET
        val title = when {
            r.alarm -> r.alarmTitle
            r.timer -> r.timerTitle
            else -> r.keepAliveTitle
        }.ifEmpty { "Echo Bedside" }
        val b = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setCategory(if (r.alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openScreen(this, if (r.timer && !r.alarm) "timers" else "alarm", 1))
        if (r.alarm) {
            b.setFullScreenIntent(openScreen(this, "alarm", 2), true)
            // If another app is in front Android shows this as a heads-up instead of opening the
            // alarm screen (background activity starts are blocked without the overlay grant):
            // make it answerable from there.
            b.addAction(Notification.Action.Builder(null, "Snooze", command(AlarmTriggerReceiver.ACTION_SNOOZE, 4)).build())
            b.addAction(Notification.Action.Builder(null, "Stop", command(AlarmTriggerReceiver.ACTION_STOP, 5)).build())
        }
        return b.build()
    }

    private fun command(action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        this, req, Intent(this, AlarmTriggerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val NOTIFICATION_ID = 0xE1A2A
        private const val CHANNEL_RING = "alarm.ring"
        private const val CHANNEL_QUIET = "alarm.quiet"
        const val DEFAULT_RAMP_MS = 45_000L
        val ALARM_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_RING) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RING, "Alarms and timers ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                    // The service plays the sound itself (ramp, alarm stream); the channel stays silent.
                    setSound(null, null)
                    setBypassDnd(true)
                },
            )
            if (nm.getNotificationChannel(CHANNEL_QUIET) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_QUIET, "Timers and sunrise", NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null) },
            )
        }

        /** Opens our launcher on [screen] (MainActivity reads `--es screen`). */
        fun openScreen(ctx: Context, screen: String, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(ctx, requestCode, launchIntent(ctx, screen), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun launchIntent(ctx: Context, screen: String): Intent =
            (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).apply {
                setPackage(ctx.packageName)
                putExtra("screen", screen)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
