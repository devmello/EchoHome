package com.echobedside.core.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The Echo's name wherever it shows on the network: AirPlay, Bluetooth, the settings page. */
object DeviceName {
    const val KEY = "device.name"
    const val DEFAULT = "Echo Show"
    fun get(settings: SettingsStore): String = settings.getString(KEY, DEFAULT).ifBlank { DEFAULT }
}

enum class SettingType {
    TEXT, BOOLEAN, INT, FLOAT,
    /** One of [SettingSpec.options]. */
    CHOICE,
    /** Comma-separated in the store. */
    TEXT_LIST,
    /** "HH:mm". */
    TIME,
    /** "#RRGGBB". */
    COLOR,
    /** "lat,lon" plus an optional "<key>.place" text setting the page fills from a search. */
    LOCATION,
    /** Stored in [SecretStore], never sent back to the page (it only shows set / not set). */
    SECRET,
    /** A button; runs [SettingSpec.onAction] on the main thread. */
    ACTION,
}

/**
 * One user-editable setting, declared by the feature that reads it. The web settings page
 * (`:feature:settings`) builds its UI from these. Values live in [SettingsStore] under [key]
 * (SECRET: in [SecretStore]). **Apply changes live**: observe [SettingsStore.changes] — the user
 * never restarts the app after changing a setting.
 */
data class SettingSpec(
    val key: String,
    val title: String,
    val type: SettingType,
    /** As a string: "true", "7", "0.5", "ap1", "#FF375F", "07:00", "a,b" (TEXT_LIST). */
    val default: String = "",
    val help: String? = null,
    /** CHOICE: value to label, in display order. */
    val options: List<Pair<String, String>> = emptyList(),
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    /** Shown only with the page's "Advanced" switch on. */
    val advanced: Boolean = false,
    /** ACTION only (e.g. "Forget iPhone", "Restart"). Return a short result message for the page, or null. */
    val onAction: (() -> String?)? = null,
)

/** A group of settings: one card on the page. [order] sorts sections (General 0, then 10, 20, …). */
data class SettingsSection(
    val id: String,
    val title: String,
    val order: Int,
    val settings: List<SettingSpec>,
    val help: String? = null,
)

/** Every feature's settings, registered in [EchoFeature.install] via [FeatureHost.registerSettings]. */
interface SettingsSchema {
    val sections: StateFlow<List<SettingsSection>>
    /** Adds or replaces the section with the same id. */
    fun register(section: SettingsSection)
    fun spec(key: String): SettingSpec? = sections.value.firstNotNullOfOrNull { s -> s.settings.firstOrNull { it.key == key } }
}

class DefaultSettingsSchema : SettingsSchema {
    private val _sections = MutableStateFlow<List<SettingsSection>>(emptyList())
    override val sections: StateFlow<List<SettingsSection>> = _sections.asStateFlow()
    override fun register(section: SettingsSection) =
        _sections.update { l -> (l.filterNot { it.id == section.id } + section).sortedBy { it.order } }
}

/**
 * Secrets set at runtime (API keys, private URLs), encrypted at rest by `:core:data`. Keys are the
 * `secrets.properties` names ([Secrets.KEYS]). A value set here wins over the compiled-in one.
 * Never log values; [changes] emits keys only.
 */
interface SecretStore {
    fun get(key: String): String?
    /** null or blank clears it (back to the compiled-in value, if any). */
    fun set(key: String, value: String?)
    fun isSet(key: String): Boolean = !get(key).isNullOrBlank()
    val changes: SharedFlow<String>
}

/** For tests and before `:app` binds the encrypted one. */
class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val changes: SharedFlow<String> = _changes.asSharedFlow()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value.isNullOrBlank()) map.remove(key) else map[key] = value
        _changes.tryEmit(key)
    }
}
