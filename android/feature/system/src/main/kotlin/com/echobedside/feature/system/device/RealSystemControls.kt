package com.echobedside.feature.system.device

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.echobedside.core.model.NightMode
import com.echobedside.core.model.SystemControls
import com.echobedside.feature.system.SystemUi
import com.echobedside.feature.system.hud.Hud
import com.echobedside.feature.system.hud.LevelKind
import com.echobedside.feature.system.hud.MessageKind
import kotlin.math.roundToInt

/**
 * `services.systemControls`, for real (NOTES §5.5). What each needs on this build:
 *  - brightness: `Settings.System.SCREEN_BRIGHTNESS` with the WRITE_SETTINGS app-op
 *    (`appops set com.echobedside WRITE_SETTINGS allow`); without it, the activity window's brightness.
 *  - volume: AudioManager STREAM_MUSIC, flags 0 (no system panel).
 *  - mic: AudioManager.setMicrophoneMute (MODIFY_AUDIO_SETTINGS, normal permission). The assistant
 *    also stops its stream on `micMuted`.
 *  - camera: state only; `:feature:camera` stops sampling on `cameraBlocked`.
 *  - Do Not Disturb: NotificationManager.setInterruptionFilter, needs policy access
 *    (`cmd notification allow_dnd com.echobedside`). Our banners obey `doNotDisturb` either way.
 *  - Bluetooth: BluetoothAdapter.enable/disable (BLUETOOTH_ADMIN, normal on API 30).
 *  - Wi-Fi: not possible for a normal app since Android 10 → returns false; Control Center opens
 *    the system Wi-Fi panel instead. Needs the priv-app install (NOTES §4).
 */
internal class RealSystemControls(private val ui: SystemUi) : SystemControls {
    private val app = ui.app
    private val store = ui.services.system
    private val audio = app.getSystemService(AudioManager::class.java)
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    fun start() {
        if (started) return
        started = true
        syncVolume(); syncBrightness(); syncDnd(); syncMic()
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    VOLUME_CHANGED -> syncVolume()
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> syncDnd()
                    AudioManager.ACTION_MICROPHONE_MUTE_CHANGED -> syncMic()
                }
            }
        }, IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
        })
        app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
            object : ContentObserver(main) { override fun onChange(selfChange: Boolean) = syncBrightness() })
        ui.connectivity.start()
    }

    // --- volume ---

    private val maxVol get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun volumeNow(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol

    /** Volume keys: one step, no system panel, our HUD. */
    fun stepVolume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        syncVolume()
        ui.showHud(Hud.Level(LevelKind.VOLUME, volumeNow(), audio.isStreamMute(AudioManager.STREAM_MUSIC)))
    }

    override fun setVolume(value: Float, showHud: Boolean): Boolean {
        val idx = (value.coerceIn(0f, 1f) * maxVol).roundToInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        store.update { it.copy(volume = value.coerceIn(0f, 1f)) }
        if (showHud) ui.showHud(Hud.Level(LevelKind.VOLUME, idx.toFloat() / maxVol))
        return true
    }

    private fun syncVolume() { val v = volumeNow(); store.update { if (it.volume == v) it else it.copy(volume = v) } }

    // --- brightness ---

    val canWriteSettings get() = Settings.System.canWrite(app)

    override fun setBrightness(value: Float): Boolean = setBrightness(value, showHud = false)

    /** [showHud]: automation and voice show the capsule; the Control Center slider doesn't (it's on screen). */
    fun setBrightness(value: Float, showHud: Boolean): Boolean {
        val v = value.coerceIn(0f, 1f)
        store.update { it.copy(brightness = v) }
        if (showHud) ui.showHud(Hud.Level(LevelKind.BRIGHTNESS, v))
        if (canWriteSettings) {
            try {
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (1 + v * 254).roundToInt())
                clearWindowBrightness()
                return true
            } catch (e: Exception) { Log.w(TAG, "brightness setting failed", e) }
        }
        // Fallback: only while our window is in front.
        val act = ui.activity ?: return false
        act.window.attributes = act.window.attributes.apply { screenBrightness = v.coerceAtLeast(0.01f) }
        return false
    }

    private fun clearWindowBrightness() {
        val act = ui.activity ?: return
        if (act.window.attributes.screenBrightness >= 0f) act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
    }

    private fun syncBrightness() {
        val raw = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (raw < 0) return
        val v = ((raw - 1) / 254f).coerceIn(0f, 1f)
        store.update { if (kotlin.math.abs(it.brightness - v) < 0.004f) it else it.copy(brightness = v) }
    }

    // --- mic / camera ---

    override fun setMicMuted(muted: Boolean): Boolean {
        val was = store.state.value.micMutedSoftware
        var ok = true
        try { audio.isMicrophoneMute.let { if (it != muted) audio.setMicrophoneMute(muted) } } catch (e: Exception) { ok = false; Log.w(TAG, "mic mute failed", e) }
        store.update { it.copy(micMutedSoftware = muted) }
        if (was != muted) {
            val hw = store.state.value.micMutedHardware
            ui.showHud(Hud.Message(if (muted || hw) MessageKind.MIC_OFF else MessageKind.MIC_ON))
        }
        return ok
    }

    private fun syncMic() {
        val m = try { audio.isMicrophoneMute } catch (_: Exception) { return }
        store.update { if (it.micMutedSoftware == m) it else it.copy(micMutedSoftware = m) }
    }

    override fun setCameraDisabled(disabled: Boolean): Boolean {
        val was = store.state.value.cameraDisabled
        store.update { it.copy(cameraDisabled = disabled) }
        if (disabled && !was) ui.showHud(Hud.Message(MessageKind.CAMERA_OFF))
        return true
    }

    // --- DND / night ---

    val hasDndAccess get() = nm.isNotificationPolicyAccessGranted

    override fun setDoNotDisturb(on: Boolean): Boolean {
        store.update { it.copy(doNotDisturb = on) }
        if (!hasDndAccess) return false
        return try {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        } catch (e: Exception) { Log.w(TAG, "DND failed", e); false }
    }

    private fun syncDnd() {
        if (!hasDndAccess) return
        val on = nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        store.update { if (it.doNotDisturb == on) it else it.copy(doNotDisturb = on) }
    }

    override fun setNightMode(mode: NightMode): Boolean {
        store.update { it.copy(nightMode = mode) }
        ui.services.settings.putString("system.night.mode", mode.name)
        return true
    }

    // --- radios ---

    @Suppress("DEPRECATION")
    override fun setWifiEnabled(on: Boolean): Boolean = try {
        // Always false for a non-privileged app targeting API 29+.
        app.getSystemService(WifiManager::class.java).setWifiEnabled(on)
    } catch (_: Exception) { false }

    /** What Control Center does when Wi-Fi can't be switched: the system Wi-Fi panel. */
    fun openWifiPanel() {
        val i = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { (ui.activity ?: app).startActivity(i) } catch (e: Exception) {
            try { app.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun setBluetoothEnabled(on: Boolean): Boolean {
        val bt = BluetoothAdapter.getDefaultAdapter() ?: return false
        return try { if (on) bt.enable() else bt.disable() } catch (e: SecurityException) { Log.w(TAG, "bluetooth", e); false }
            .also { ui.connectivity.refresh() }
    }

    override fun showControlCenter() = ui.requestControlCenter(true)

    companion object {
        private const val TAG = "EchoSystem"
        /** Hidden AudioManager.VOLUME_CHANGED_ACTION (stable since API 1). */
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}
