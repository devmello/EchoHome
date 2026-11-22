package app.vesper.feature.media

import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The user's media settings (web settings page), all applied live. */
object MediaSettings {
    /** Mirror other apps' media sessions (needs the notification-listener grant). */
    const val OTHER_APPS = "media.otherApps"
    /** Keep the background drifting while paused; off = it settles after 30 s (saves power). */
    const val PAUSED_DRIFT = "media.pausedDrift"
    /** Show the MUSIC live activity in the island. */
    const val ISLAND = "media.island"

    val section = SettingsSection(
        id = "media",
        title = "Music",
        order = 30,
        settings = listOf(
            SettingSpec(
                OTHER_APPS, "Show other apps' music", SettingType.BOOLEAN, "true",
                help = "Music playing in other apps on the Echo shows on Now Playing and in the island, with working controls.",
            ),
            SettingSpec(
                ISLAND, "Show music in the island", SettingType.BOOLEAN, "true",
                help = "A small player at the top of other screens while music is playing.",
            ),
            SettingSpec(
                PAUSED_DRIFT, "Keep the background moving while paused", SettingType.BOOLEAN, "false",
                help = "Off: the colours behind Now Playing settle 30 seconds after you pause, so the screen draws nothing.",
            ),
        ),
    )

    fun otherApps(s: SettingsStore) = s.getBoolean(OTHER_APPS, true)
    fun island(s: SettingsStore) = s.getBoolean(ISLAND, true)
    fun pausedDrift(s: SettingsStore) = s.getBoolean(PAUSED_DRIFT, false)

    /** The current value of [key], then every change to it. */
    fun <T> watch(store: SettingsStore, key: String, read: (SettingsStore) -> T): Flow<T> =
        store.changes.filter { it == key }.map { read(store) }.onStart { emit(read(store)) }.distinctUntilChanged()
}
