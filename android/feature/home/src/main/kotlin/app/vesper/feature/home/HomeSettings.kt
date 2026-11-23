package app.vesper.feature.home

import app.vesper.core.model.SettingsStore
import app.vesper.feature.home.home.Slot

/**
 * Settings `:feature:home` reads, all optional (SettingsStore), declared for the web settings page
 * in [HomeSettingsSchema]:
 *  - weather: `weather.location` (+ `.place`), `weather.unit` (WeatherSettings)
 *  - calendars: `calendar.color.<n>` (URLs are secrets: `calendar.icsUrls`)
 *  - commute: `commute.*` (CommuteSettings) · stocks: `stocks.*` (StockSettings)
 *  - optional Home Assistant Home screen + doorbell: `home.*` below.
 *
 * For debugging they can also be set from adb with the launch intent:
 *
 *     adb shell am start -n app.vesper/.MainActivity --es screen commute \
 *       --es home.set 'weather.location=37.77,-122.42;weather.location.place=San Francisco'
 *
 * (`home.set` = `key=value` pairs separated by `;` for the keys above; an empty value removes one.)
 */
object HomeSettings {
    /** Room order for the switcher: comma-separated HA area ids. Rooms not listed follow. */
    const val ROOMS = "home.rooms"
    /** Last selected room (area id). */
    const val SELECTED_ROOM = "home.room.selected"
    /** Per-room slot override: `home.room.<area_id>.<climate|lamp|toggles|scenes|secure>` = entity ids ("none" = empty). */
    fun slotKey(areaId: String, slot: Slot) = "home.room.$areaId.${slot.key}"

    /** Doorbell: an event.* (doorbell device class) or binary_sensor.* entity (default: auto-detect). */
    const val DOORBELL_ENTITY = "home.doorbell.entity"
    /** camera.* for the snapshot (default: the camera sharing the doorbell's name, else the first camera). */
    const val DOORBELL_CAMERA = "home.doorbell.camera"
    /** lock.* for the Unlock action (default: the first lock). */
    const val DOORBELL_LOCK = "home.doorbell.lock"
    /** script.* to run for Talk (e.g. starts two-way audio); without it Talk isn't shown. */
    const val DOORBELL_TALK = "home.doorbell.talk"

    private val SETTABLE = listOf("home.", "weather.", "calendar.color.", "commute.", "stocks.", "leave.", "brief.")

    fun list(settings: SettingsStore, key: String): List<String>? =
        settings.getString(key, "\u0000").takeIf { it != "\u0000" }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    fun string(settings: SettingsStore, key: String): String? = settings.getString(key, "").trim().ifEmpty { null }

    /** Applies `key=value;key2=value2` (the `home.set` launch extra), only for this feature's keys. */
    fun applyAssignments(settings: SettingsStore, spec: String) {
        for (pair in spec.split(';')) {
            val k = pair.substringBefore('=').trim()
            if (SETTABLE.none { k.startsWith(it) } || '=' !in pair) continue
            val v = pair.substringAfter('=').trim()
            if (v.isEmpty()) settings.remove(k) else settings.putString(k, v)
        }
    }
}
