package app.vesper.feature.system.device

import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import app.vesper.feature.system.SystemUi

/**
 * Hardware keys, before the system sees them (registered with `host.registerKeyHandler`):
 *  - VOLUME_UP/DOWN (gpio-keys): one step on STREAM_MUSIC with flags 0 (no system panel) + our HUD.
 *    Key repeats step too, so holding a key ramps like the stock panel.
 *  - VOLUME_MUTE: toggles mute.
 *  - [HardwareMonitor.MIC_KEYCODE]: the mic button once `gating.kl` is installed.
 *  - BACK: closes the topmost open overlay (Control Center, expanded island/banner).
 */
internal class KeyRouter(private val ui: SystemUi) {
    private var backConsumed = false

    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        return when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (down) ui.controls.stepVolume(if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (down && e.repeatCount == 0) ui.controls.stepVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                true
            }
            HardwareMonitor.MIC_KEYCODE -> {
                if (down && e.repeatCount == 0) {
                    Log.i(TAG, "mic button (keycode ${e.keyCode}, scan ${e.scanCode}, device ${e.device?.name})")
                    ui.monitor.onMicButton()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    backConsumed = false
                    if (e.repeatCount == 0) for (h in ui.backHandlers.asReversed()) if (h()) { backConsumed = true; break }
                    backConsumed
                } else backConsumed.also { backConsumed = false }
            }
            else -> false
        }
    }

    companion object { private const val TAG = "VesperSystem" }
}
