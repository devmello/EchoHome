package com.echobedside.feature.photos

import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.core.model.SettingsStore
import com.echobedside.feature.photos.data.Album
import com.echobedside.feature.photos.data.Slideshow

/**
 * The "Photo frame" card on the web settings page. The Immich URL and API key are SECRETs
 * declared by `:feature:settings` (keys `immich.url`, `immich.apiKey`); this module reads them
 * from `services.config.secrets` at use time.
 */
object PhotoSettings {
    const val SECTION_ID = "photos"
    const val ORDER = 70

    const val ALBUM_ID = "photos.album.id"
    const val ALBUM_NAME = "photos.album.name"
    const val SECONDS = "photos.seconds"
    const val SHUFFLE = "photos.shuffle"
    /** Open the frame after this many minutes without a touch on StandBy; 0 = never. */
    const val IDLE_MINUTES = "photos.idleMinutes"
    /** Don't open it by itself at night, and leave it if Night mode starts. */
    const val NOT_AT_NIGHT = "photos.notAtNight"

    const val DEFAULT_IDLE_MINUTES = 0
    const val MAX_IDLE_MINUTES = 240

    /** All the keys whose change the screen applies live. */
    val LIVE_KEYS = setOf(ALBUM_ID, ALBUM_NAME, SECONDS, SHUFFLE)

    /**
     * [albums] known (listed from Immich or the demo set): a CHOICE of them. Not known yet
     * (no server, offline at start): a TEXT album name, matched case-insensitively.
     */
    fun section(albums: List<Album>?): SettingsSection {
        val album = if (!albums.isNullOrEmpty()) {
            SettingSpec(
                key = ALBUM_ID, title = "Album", type = SettingType.CHOICE,
                default = albums.firstOrNull { it.name.equals("Family", ignoreCase = true) }?.id ?: albums.maxBy { it.assetCount }.id,
                options = albums.map { it.id to "${it.name} (${it.assetCount})" },
                help = "Also on the frame: tap the photo, then the album button.",
            )
        } else {
            SettingSpec(
                key = ALBUM_NAME, title = "Album name", type = SettingType.TEXT,
                default = "Family",
                help = "The Immich album to show. Becomes a list once the Echo can reach Immich.",
            )
        }
        return SettingsSection(
            id = SECTION_ID, title = "Photo frame", order = ORDER,
            help = "Photos from an Immich album. Set the Immich server and API key under Accounts.",
            settings = listOf(
                album,
                SettingSpec(SECONDS, "Seconds per photo", SettingType.INT, Slideshow.DEFAULT_SECONDS.toString(),
                    min = 3f, max = 600f, step = 1f,
                    help = "With 9 s or more the slow zoom finishes and the frame rests between photos."),
                SettingSpec(SHUFFLE, "Shuffle", SettingType.BOOLEAN, "true",
                    help = "Off: album order."),
                SettingSpec(IDLE_MINUTES, "Show after idle (minutes)", SettingType.INT, DEFAULT_IDLE_MINUTES.toString(),
                    min = 0f, max = MAX_IDLE_MINUTES.toFloat(), step = 1f,
                    help = "Opens the frame from StandBy after this long without a touch. 0 = only when you swipe to it."),
                SettingSpec(NOT_AT_NIGHT, "Never at night", SettingType.BOOLEAN, "true",
                    help = "In Night mode the frame doesn't open by itself, and goes back to the clock if it did."),
            ),
        )
    }

    fun seconds(s: SettingsStore) = int(s, SECONDS, Slideshow.DEFAULT_SECONDS).coerceIn(3, 600)
    fun shuffle(s: SettingsStore) = bool(s, SHUFFLE, true)
    fun notAtNight(s: SettingsStore) = bool(s, NOT_AT_NIGHT, true)
    fun idleMinutes(s: SettingsStore) = int(s, IDLE_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(0, MAX_IDLE_MINUTES)

    /*
     * The settings page may store a value as a string ("7", "true") or typed; SharedPreferences
     * throws on a type mismatch, so try each form.
     */
    private fun str(s: SettingsStore, key: String): String? = runCatching { s.getString(key, "") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun int(s: SettingsStore, key: String, default: Int): Int =
        str(s, key)?.trim()?.toFloatOrNull()?.toInt()
            ?: runCatching { s.getInt(key, default) }.getOrNull()
            ?: runCatching { s.getFloat(key, default.toFloat()).toInt() }.getOrDefault(default)

    private fun bool(s: SettingsStore, key: String, default: Boolean): Boolean =
        str(s, key)?.trim()?.toBooleanStrictOrNull()
            ?: runCatching { s.getBoolean(key, default) }.getOrDefault(default)

    /**
     * Whether the idle watcher should open the frame now: enabled, idle long enough, on the
     * StandBy screen (never over Now Playing, an alarm, …), and not at night if that's off.
     */
    fun shouldAutoShow(idleMs: Long, idleMinutes: Int, currentScreen: String, night: Boolean, notAtNight: Boolean): Boolean =
        idleMinutes > 0 && idleMs >= idleMinutes * 60_000L && currentScreen == STANDBY && !(night && notAtNight)

    const val STANDBY = "standby"
}
