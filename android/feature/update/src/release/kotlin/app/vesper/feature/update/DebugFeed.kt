package app.vesper.feature.update

import app.vesper.core.model.SettingsStore

/** Release builds: no test feed, GitHub only (the debug version is in `src/debug`). */
internal object DebugFeed {
    @Suppress("UNUSED_PARAMETER") fun overrideUrl(settings: SettingsStore): String? = null
    @Suppress("UNUSED_PARAMETER") fun set(settings: SettingsStore, url: String?) {}
    @Suppress("UNUSED_PARAMETER") fun fetcher(feedUrl: String, fallback: Fetcher): Fetcher = fallback
}
