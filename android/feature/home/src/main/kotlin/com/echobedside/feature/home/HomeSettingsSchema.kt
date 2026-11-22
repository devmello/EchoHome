package com.echobedside.feature.home

import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.feature.home.commute.CommuteSettings
import com.echobedside.feature.home.glance.WeatherSettings
import com.echobedside.feature.home.stocks.StockSettings

/**
 * `:feature:home`'s sections on the web settings page. Everything applies live (each reader follows
 * SettingsStore.changes). Secrets (calendar URLs, HA url/token) are declared by `:app` (Secrets.KEYS).
 */
object HomeSettingsSchema {
    val weather = SettingsSection("weather", "Weather & location", 30, listOf(
        SettingSpec(WeatherSettings.LOCATION, "Location", SettingType.LOCATION,
            help = "Leave empty to detect it from your internet connection (Livermore, CA if that fails). Also the commute's start."),
        SettingSpec(WeatherSettings.UNIT, "Temperature unit", SettingType.CHOICE, default = "",
            options = listOf("" to "Automatic (from region)", "F" to "°F", "C" to "°C")),
    ), help = "Forecasts from Open-Meteo (CC BY 4.0).")

    val calendar = SettingsSection("calendar", "Calendars", 35, (0 until 3).map { i ->
        SettingSpec("calendar.color.$i", "Colour of calendar ${i + 1}", SettingType.COLOR, advanced = true,
            help = if (i == 0) "In the order of the iCal URLs (Secrets › calendar.icsUrls). Empty: the default palette." else null)
    }, help = "Add calendars as private iCal (.ics) URLs under Secrets.")

    val commute = SettingsSection("commute", "Commute", 40, listOf(
        SettingSpec(CommuteSettings.SOURCE, "Where to", SettingType.CHOICE, default = "auto", options = listOf(
            "auto" to "Next calendar event with a place, else the destination below",
            "event" to "Next calendar event with a place",
            "fixed" to "Always the destination below")),
        SettingSpec(CommuteSettings.DESTINATION, "Destination", SettingType.TEXT,
            help = "An address or place, e.g. \"Scotts Valley, CA\". Used on weekdays."),
        SettingSpec(CommuteSettings.ARRIVE_BY, "Arrive by", SettingType.TIME, default = "09:00"),
        SettingSpec(CommuteSettings.LABEL, "Name", SettingType.TEXT, default = "work", help = "Shown as \"TO WORK\"."),
        SettingSpec(CommuteSettings.BUFFER_MIN, "Arrive early by (min)", SettingType.INT, default = "5", min = 0f, max = 60f, step = 1f),
        SettingSpec(CommuteSettings.EVENT_HOURS, "Look ahead for events (hours)", SettingType.INT, default = "4", min = 1f, max = 12f, step = 1f, advanced = true),
        SettingSpec(CommuteSettings.HOME, "Start from", SettingType.LOCATION, advanced = true,
            help = "Empty: the weather location."),
    ), help = "Drive times from OpenStreetMap (OSRM); no live traffic.")

    val stocks = SettingsSection("stocks", "Stocks", 50, listOf(
        SettingSpec(StockSettings.SYMBOLS, "Watchlist", SettingType.TEXT_LIST, default = "AAPL,NVDA,TSLA,MSFT,AMZN",
            help = "Up to 5 ticker symbols. Quotes from Yahoo Finance, may be delayed."),
    ))

    val homeAssistant = SettingsSection("home-assistant", "Home Assistant", 60, listOf(
        SettingSpec(HomeSettings.ROOMS, "Room order", SettingType.TEXT_LIST,
            help = "Home Assistant area ids, e.g. bedroom, living_room. Rooms not listed follow."),
        SettingSpec(HomeSettings.DOORBELL_ENTITY, "Doorbell entity", SettingType.TEXT, help = "Empty: detected (event.* doorbell or binary_sensor.*_ding).", advanced = true),
        SettingSpec(HomeSettings.DOORBELL_CAMERA, "Doorbell camera", SettingType.TEXT, help = "Empty: the camera named like the doorbell.", advanced = true),
        SettingSpec(HomeSettings.DOORBELL_LOCK, "Door lock", SettingType.TEXT, help = "For the doorbell's Unlock. Empty: the first lock.", advanced = true),
        SettingSpec(HomeSettings.DOORBELL_TALK, "Talk script", SettingType.TEXT, help = "A script.* that starts two-way audio. Empty: no Talk button.", advanced = true),
    ), help = "Optional. Set ha.url and ha.token under Secrets for the Home screen and the doorbell.")

    val all = listOf(weather, calendar, commute, stocks, homeAssistant)
}
