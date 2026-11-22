package com.echobedside.feature.system.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.echobedside.feature.system.SystemUi
import com.echobedside.feature.system.hud.Hud
import com.echobedside.feature.system.hud.MessageKind

/**
 * Keeps `services.system` in sync with the privacy hardware (gating button latch + lens shutter).
 *
 * Event-driven where Android allows it, otherwise a cheap 1 Hz poll on a background thread that
 * runs only while our window is visible (two tiny file reads; ~0.1 ms):
 *  - the remapped mic key ([MIC_KEYCODE]) triggers an immediate read,
 *  - until the remap is installed, the button is the power key: SCREEN_OFF + a changed gating latch
 *    means it was the button, so we record the new state and turn the screen back on,
 *  - the shutter has no app-visible event at all (opening it makes PhoneWindowManager launch the
 *    camera app; see status), so it's polled.
 */
internal class HardwareMonitor(private val ui: SystemUi) {
    private val app = ui.app
    private val thread = HandlerThread("echo-hw").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var first = true
    private var lastGating: Boolean? = null
    private var lastShutter: Boolean? = null
    var shutterSource: String = "none"; private set
    /** The gating device's key layout no longer produces KEYCODE_POWER. */
    var remapInstalled: Boolean = false; private set

    private val poll = object : Runnable {
        override fun run() {
            readOnce(fromButton = false)
            if (active) bg.postDelayed(this, POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> bg.post { onScreenOff() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> bg.post { readOnce(false) }
            }
        }
    }

    init {
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        app.registerReceiver(screenReceiver, f)
        remapInstalled = detectRemap()
        bg.post { readOnce(false) }
        Log.i(TAG, "gating remap installed=$remapInstalled")
    }

    /** Overlay window visible → poll; hidden → stop (nothing runs while another app is in front). */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        bg.removeCallbacks(poll)
        if (on) bg.post(poll)
    }

    /** The remapped mic/camera key went down: the hardware latch flips within a few ms. */
    fun onMicButton() {
        val before = lastGating
        bg.postDelayed({ readOnce(true) }, 60)
        bg.postDelayed({
            readOnce(true)
            val latch = HardwareProbes.readGatingMuted()
            if (latch == null) {
                // No readable latch: keep a logical state so the button still works.
                Log.w(TAG, "gating latch unreadable after the mic key; toggling the state logically")
                val now = !(ui.services.system.state.value.micMutedHardware)
                main.post {
                    ui.services.system.update { it.copy(micMutedHardware = now) }
                    ui.showHud(Hud.Message(if (now) MessageKind.MIC_OFF else MessageKind.MIC_ON))
                }
            } else if (latch == before) {
                // The latch didn't move: the button isn't gating in hardware here, so make it the software mute.
                Log.w(TAG, "gating latch unchanged after the mic key; toggling the software mic mute")
                main.post { ui.controls.setMicMuted(!ui.services.system.state.value.micMutedSoftware) }
            }
        }, 300)
    }

    private fun onScreenOff() {
        if (remapInstalled) return
        val before = lastGating
        val now = HardwareProbes.readGatingMuted() ?: return
        if (before != null && now != before) {
            // The un-remapped button is KEY_POWER: Android just slept the screen. Record the new
            // state and wake straight back up so the HUD shows.
            Log.i(TAG, "screen off with gating $before → $now: treating as the mic button")
            applyGating(now, announce = true)
            main.post { wakeScreen() }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = app.getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "echo:mic-button")
                .acquire(1500)
        } catch (e: Exception) { Log.w(TAG, "wake failed", e) }
    }

    private fun readOnce(fromButton: Boolean) {
        HardwareProbes.readGatingMuted()?.let { if (it != lastGating) applyGating(it, announce = !first || fromButton) }
        HardwareProbes.readShutterClosed(app)?.let { (closed, src) ->
            shutterSource = src
            if (closed != lastShutter) applyShutter(closed, announce = !first)
        }
        first = false
    }

    private fun applyGating(muted: Boolean, announce: Boolean) {
        val was = lastGating
        lastGating = muted
        // the raw latch value, to confirm GATING_MUTED_VALUE against the red light
        Log.i(TAG, "gating ${HardwareProbes.GATING_STATE}=${try { java.io.File(HardwareProbes.GATING_STATE).readText().trim() } catch (_: Exception) { "?" }} → micMutedHardware=$muted (was $was)")
        main.post {
            ui.services.system.update { it.copy(micMutedHardware = muted) }
            if (announce && was != null) {
                val sw = ui.services.system.state.value.micMutedSoftware
                ui.showHud(Hud.Message(if (muted) MessageKind.MIC_OFF else if (!sw) MessageKind.MIC_ON else MessageKind.MIC_OFF))
            }
        }
    }

    private fun applyShutter(closed: Boolean, announce: Boolean) {
        val was = lastShutter
        lastShutter = closed
        main.post {
            ui.services.system.update { it.copy(shutterClosed = closed) }
            if (announce && was != null && closed) ui.showHud(Hud.Message(MessageKind.CAMERA_OFF_SHUTTER))
        }
    }

    /** True when the `gating` input device can no longer generate KEYCODE_POWER (the .kl is installed). */
    private fun detectRemap(): Boolean = try {
        val im = app.getSystemService(InputManager::class.java)
        val dev = im.inputDeviceIds.map { InputDevice.getDevice(it) }.firstOrNull { it?.name == "gating" }
        if (dev == null) false else {
            val has = dev.hasKeys(KeyEvent.KEYCODE_POWER, MIC_KEYCODE)
            !has[0] && has[1]
        }
    } catch (e: Exception) { false }

    /** For logs / status. */
    fun describe() = "gating=${lastGating} shutter=${lastShutter} via $shutterSource remap=$remapInstalled t=${SystemClock.elapsedRealtime()}"

    companion object {
        private const val TAG = "EchoSystem"
        const val POLL_MS = 1000L
        /**
         * The keycode the proposed `gating.kl` maps the button's scancode 116 to (KEYCODE_MUTE, 91:
         * "microphone mute"). Android 11 passes it to the focused window (it's in PhoneWindowManager's
         * media-key group, only diverted while a global-priority session such as a phone call is active).
         */
        const val MIC_KEYCODE = KeyEvent.KEYCODE_MUTE
    }
}
