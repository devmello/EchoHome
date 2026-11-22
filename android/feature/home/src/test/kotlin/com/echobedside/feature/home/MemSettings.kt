package com.echobedside.feature.home

import com.echobedside.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** In-memory [SettingsStore] for tests. */
class MemSettings(vararg initial: Pair<String, Any>) : SettingsStore {
    val map = HashMap<String, Any>(initial.toMap())
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: SharedFlow<String> = _changes
    private fun put(k: String, v: Any) { map[k] = v; _changes.tryEmit(k) }
    override fun getString(key: String, default: String) = map[key]?.let { it as String } ?: default // throws like SharedPreferences
    override fun putString(key: String, value: String) = put(key, value)
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) = put(key, value)
    override fun getFloat(key: String, default: Float) = map[key]?.let { it as Float } ?: default
    override fun putFloat(key: String, value: Float) = put(key, value)
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) = put(key, value)
    override fun remove(key: String) { map.remove(key); _changes.tryEmit(key) }
}
