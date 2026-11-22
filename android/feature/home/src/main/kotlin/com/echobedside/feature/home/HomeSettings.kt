package com.echobedside.feature.home

import com.echobedside.core.model.SettingsStore
import com.echobedside.feature.home.home.Slot

/**
 * Every setting `:feature:home` reads (SettingsStore, keys prefixed "home."). All are optional:
 * without them the feature auto-picks entities. There's no settings screen yet; set them from adb
 * with the launch intent (debug and release):
 *
 *     adb shell am start -n com.echobedside/.MainActivity --es screen home \
 *       --es home.set 'home.doorbell.entity=binary_sensor.front_door_ding;home.stocks.symbols=AAPL,MSFT'
 *
 * (`home.set` takes `key=value` pairs separated by `;`, only for "home." keys; an empty value removes the key.)
 */
object HomeSettings {
    /** Room order for the switcher: comma-separated HA area ids. Rooms not listed follow. */
    const val ROOMS = "home.rooms"
    /** Last selected room (area id). */
    const val SELECTED_ROOM = "home.room.selected"
    /** Per-room slot override: `home.room.<area_id>.<climate|lamp|toggles|scenes|secure>` = entity ids. */
    fun slotKey(areaId: String, slot: Slot) = "home.room.$areaId.${slot.key}"

    /** weather.* entity for the faces (default: the first weather entity). */
    const val WEATHER_ENTITY = "home.weather.entity"
    /** Place name shown on the Weather face (default: HA's location_name). */
    const val WEATHER_LOCATION = "home.weather.location"
    /** calendar.* entities, comma-separated (default: all of them). */
    const val CALENDARS = "home.calendars"
    /** Colour for one calendar, "#RRGGBB" (default: from the design palette, by order). */
    fun calendarColorKey(entityId: String) = "home.calendar.$entityId.color"

    /** Travel-time sensor (Waze / Google travel time) for the default commute, e.g. sensor.commute_to_work. */
    const val COMMUTE_SENSOR = "home.commute.sensor"
    /** Header for the default commute: "WORK" → "TO WORK". */
    const val COMMUTE_LABEL = "home.commute.label"
    /** Arrive-by time for the default commute on weekdays, "HH:mm" (e.g. 09:00). */
    const val COMMUTE_ARRIVE_BY = "home.commute.arriveBy"
    /** Minutes to arrive early (default 5). */
    const val COMMUTE_BUFFER_MIN = "home.commute.bufferMin"
    /** Origin for per-event routes (default zone.home) and the Waze region (us, eu, il, au; default us). */
    const val COMMUTE_ORIGIN = "home.commute.origin"
    const val COMMUTE_REGION = "home.commute.region"
    /** Use the next calendar event's location within this many hours (default 4; 0 = never). */
    const val COMMUTE_EVENT_HOURS = "home.commute.eventHours"
    /** Learned free-flow drive time per sensor (minutes), for the traffic chip when the sensor has no baseline. */
    fun commuteBaselineKey(sensor: String) = "home.commute.baseline.$sensor"

    /** Watchlist symbols, comma-separated (default AAPL,NVDA,TSLA,MSFT,AMZN). */
    const val STOCK_SYMBOLS = "home.stocks.symbols"
    /** Featured symbol index in the watchlist. */
    const val STOCK_FEATURED = "home.stocks.featured"

    /** Doorbell: an event.* (doorbell device class) or binary_sensor.* entity (default: auto-detect). */
    const val DOORBELL_ENTITY = "home.doorbell.entity"
    /** camera.* for the snapshot (default: the camera sharing the doorbell's name, else the first camera). */
    const val DOORBELL_CAMERA = "home.doorbell.camera"
    /** lock.* for the Unlock action (default: the first lock). */
    const val DOORBELL_LOCK = "home.doorbell.lock"
    /** script.* to run for Talk (e.g. starts two-way audio); without it Talk isn't shown. */
    const val DOORBELL_TALK = "home.doorbell.talk"

    fun list(settings: SettingsStore, key: String): List<String>? =
        settings.getString(key, "\u0000").takeIf { it != "\u0000" }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    fun string(settings: SettingsStore, key: String): String? = settings.getString(key, "").trim().ifEmpty { null }

    /** Applies `key=value;key2=value2` (the `home.set` launch extra). Only "home." keys. */
    fun applyAssignments(settings: SettingsStore, spec: String) {
        for (pair in spec.split(';')) {
            val k = pair.substringBefore('=').trim()
            if (!k.startsWith("home.") || '=' !in pair) continue
            val v = pair.substringAfter('=').trim()
            if (v.isEmpty()) settings.remove(k) else settings.putString(k, v)
        }
    }
}
