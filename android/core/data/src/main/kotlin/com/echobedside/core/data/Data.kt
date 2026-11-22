package com.echobedside.core.data

import android.content.Context
import android.content.SharedPreferences
import com.echobedside.core.model.Secrets
import com.echobedside.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Secrets compiled in from android/secrets.properties (see secrets.properties.example). */
fun loadSecrets(): Secrets = Secrets(
    haUrl = BuildConfig.HA_URL,
    haToken = BuildConfig.HA_TOKEN,
    immichUrl = BuildConfig.IMMICH_URL,
    immichApiKey = BuildConfig.IMMICH_API_KEY,
    anthropicApiKey = BuildConfig.ANTHROPIC_API_KEY,
    stocksApiKey = BuildConfig.STOCKS_API_KEY,
    calendarIcsUrls = BuildConfig.CALENDAR_ICS_URLS.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() },
)

/** [SettingsStore] on one SharedPreferences file. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("echo_settings", Context.MODE_PRIVATE)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val changes: SharedFlow<String> = _changes.asSharedFlow()
    // Held strongly: SharedPreferences keeps listeners in a weak map.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) _changes.tryEmit(key) }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}
