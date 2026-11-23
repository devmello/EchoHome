package app.vesper.feature.sleep

import app.vesper.core.model.SettingsStore
import app.vesper.core.model.SleepSound
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MemSettings : SettingsStore {
    val map = HashMap<String, Any>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: SharedFlow<String> = _changes
    override fun getString(key: String, default: String) = map[key] as? String ?: default
    override fun putString(key: String, value: String) { map[key] = value; _changes.tryEmit(key) }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value; _changes.tryEmit(key) }
    override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    override fun putFloat(key: String, value: Float) { map[key] = value; _changes.tryEmit(key) }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value; _changes.tryEmit(key) }
    override fun remove(key: String) { map.remove(key); _changes.tryEmit(key) }
}

internal class FakeOutput : SleepOutput {
    override var onFinished: (() -> Unit)? = null
    val log = ArrayList<String>()
    var sound: SleepSound? = null
    var lastEndsAt: Long? = null
    var duckedNow = false
    var playing = false
    override fun play(sound: SleepSound, level: Float, endsAt: Long?) { log += "play ${sound.id}"; this.sound = sound; this.lastEndsAt = endsAt; playing = true }
    override fun stop() { log += "stop"; playing = false }
    override fun setEndsAt(endsAt: Long?) { lastEndsAt = endsAt }
    override fun setDucked(ducked: Boolean) { duckedNow = ducked }
    override fun setLevel(level: Float) {}
}

internal class FakeFocus(var grant: Boolean = true) : FocusGate {
    var listener: ((Int) -> Unit)? = null
    var held = false
    var requests = 0
    override fun request(onChange: (Int) -> Unit): Boolean { requests++; listener = onChange; held = grant; return grant }
    override fun abandon() { held = false }
}

internal class FakeScheduler : SleepScheduler {
    var at: Long? = null
    private var r: (() -> Unit)? = null
    override fun at(atElapsed: Long, r: () -> Unit) { at = atElapsed; this.r = r }
    override fun cancel() { at = null; r = null }
    /** Runs the pending task if it's due by [now]. */
    fun advance(now: Long) {
        while (true) {
            val t = at ?: return
            if (t > now) return
            val task = r; at = null; r = null
            task?.invoke()
        }
    }
}
