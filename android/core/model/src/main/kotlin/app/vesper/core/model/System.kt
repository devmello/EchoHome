package app.vesper.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Device-wide state shared by every feature (NOTES §5.3, §5.5). Written by the feature that owns
 * each signal, read by everyone:
 *  - micMutedHardware, shutterClosed, volume, brightness, dnd: `:feature:system`
 *  - micMutedSoftware: `:feature:system` (Control Center) and `:feature:assistant`
 *  - nightActive: `:feature:clock` (light sensor + setting)
 *  - presence, cameraInUse: `:feature:camera`
 */
data class SystemState(
    /** The physical mic/camera button (the `gating` device). */
    val micMutedHardware: Boolean = false,
    /** The Control Center mic toggle. */
    val micMutedSoftware: Boolean = false,
    /** The Control Center camera toggle. */
    val cameraDisabled: Boolean = false,
    /** SW_CAMERA_LENS_COVER from gpio-keys. */
    val shutterClosed: Boolean = false,
    /** Something is sampling the camera right now (shows the indicator). */
    val cameraInUse: Boolean = false,
    val doNotDisturb: Boolean = false,
    val nightMode: NightMode = NightMode.AUTO,
    /** Night mode is currently applied (red + dim). */
    val nightActive: Boolean = false,
    /** Latest ambient light, lux; null until the sensor reports. */
    val ambientLux: Float? = null,
    /** 0..1 screen brightness. */
    val brightness: Float = 0.6f,
    /** 0..1 music volume. */
    val volume: Float = 0.5f,
    val presence: Presence = Presence.UNKNOWN,
) {
    val micMuted: Boolean get() = micMutedHardware || micMutedSoftware
    /** Camera must not be used: the button, the toggle or the shutter says so. */
    /** The mic button is mic-only (user, Nov 22, 2026), so it doesn't block the camera. */
    val cameraBlocked: Boolean get() = cameraDisabled || shutterClosed
}

enum class NightMode { AUTO, ON, OFF }

enum class Presence { UNKNOWN, PRESENT, EMPTY }

interface SystemStateStore {
    val state: StateFlow<SystemState>
    fun update(transform: (SystemState) -> SystemState)
}

class DefaultSystemStateStore : SystemStateStore {
    private val _state = MutableStateFlow(SystemState())
    override val state: StateFlow<SystemState> = _state.asStateFlow()
    override fun update(transform: (SystemState) -> SystemState) = _state.update(transform)
}

/**
 * Actions that change the device. `:feature:system` binds the real implementation; the default
 * only updates [SystemStateStore] so UIs work without it. Each returns false when the action
 * isn't possible yet (e.g. Wi-Fi needs the priv-app install, NOTES §5.5).
 */
interface SystemControls {
    /** [showHud] = show the brightness capsule (Control Center / keys / voice); silent by default (sunrise ramp, auto). */
    fun setBrightness(value: Float, showHud: Boolean = false): Boolean
    fun setVolume(value: Float, showHud: Boolean = true): Boolean
    fun setMicMuted(muted: Boolean): Boolean
    fun setCameraDisabled(disabled: Boolean): Boolean
    fun setDoNotDisturb(on: Boolean): Boolean
    fun setNightMode(mode: NightMode): Boolean
    fun setWifiEnabled(on: Boolean): Boolean
    fun setBluetoothEnabled(on: Boolean): Boolean
    /** Opens Control Center (e.g. from a long-press or a debug intent). */
    fun showControlCenter() {}
}

open class StateOnlySystemControls(private val store: SystemStateStore) : SystemControls {
    override fun setBrightness(value: Float, showHud: Boolean): Boolean { store.update { it.copy(brightness = value.coerceIn(0f, 1f)) }; return true }
    override fun setVolume(value: Float, showHud: Boolean): Boolean { store.update { it.copy(volume = value.coerceIn(0f, 1f)) }; return true }
    override fun setMicMuted(muted: Boolean): Boolean { store.update { it.copy(micMutedSoftware = muted) }; return true }
    override fun setCameraDisabled(disabled: Boolean): Boolean { store.update { it.copy(cameraDisabled = disabled) }; return true }
    override fun setDoNotDisturb(on: Boolean): Boolean { store.update { it.copy(doNotDisturb = on) }; return true }
    override fun setNightMode(mode: NightMode): Boolean { store.update { it.copy(nightMode = mode) }; return true }
    override fun setWifiEnabled(on: Boolean): Boolean = false
    override fun setBluetoothEnabled(on: Boolean): Boolean = false
}
