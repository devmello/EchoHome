package app.vesper.feature.home

import android.os.Bundle
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.feature.home.alerts.LeaveNowWatcher
import app.vesper.feature.home.alerts.RainAlertWatcher
import app.vesper.feature.home.brief.BriefView
import app.vesper.feature.home.brief.BriefWatcher
import app.vesper.feature.home.commute.CommuteView
import app.vesper.feature.home.commute.HomeTravelEstimator
import app.vesper.feature.home.glance.PersonalCalendars
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.Nowcast
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.vesper.feature.home.doorbell.DemoSnapshot
import app.vesper.feature.home.doorbell.DoorbellWatcher
import app.vesper.feature.home.glance.AutoLocation
import app.vesper.feature.home.glance.IcsCalendarProvider
import app.vesper.feature.home.glance.LocationResolver
import app.vesper.feature.home.glance.NetworkChanges
import app.vesper.feature.home.glance.OpenMeteoWeatherProvider
import app.vesper.feature.home.ha.DemoHomeAssistant
import app.vesper.feature.home.home.HomeScreenView
import app.vesper.feature.home.lamp.LampScreenView
import app.vesper.feature.home.stocks.StocksView
import app.vesper.feature.home.radar.RadarScreen

/**
 * `:feature:home` (agent F — glances + optional smart home). Works with no Home Assistant (NOTES §4.1):
 *  - services.weather → Open-Meteo for the manual location, else the IP-detected one, else
 *    San Francisco (works with no settings). Demo mode with no manual location: the sample weather.
 *  - services.calendar → the iCal URLs in secrets (`calendar.icsUrls`, followed live); none = the fake.
 *  - services.location → manual > IP-detected > San Francisco (demo: Santa Cruz unless manual).
 *  - Settings sections: [HomeSettingsSchema].
 *  - Commute (order 40): Nominatim + OSRM, no key. Stocks (order 50): Yahoo chart, no key.
 *  - Home (order 20) + doorbell: only with Home Assistant configured. In demo mode without HA the
 *    Home screen still shows, driven by [DemoHomeAssistant] and labelled demo.
 *  - Lamp (`lamp`, not in the pager): one light in detail, opened from the Home lamp tile. Same rules.
 */
object HomeFeature : VesperFeature {
    override val id = "home"

    private var demoHa: DemoHomeAssistant? = null
    private var doorbell: DoorbellWatcher? = null

    override fun install(host: FeatureHost) {
        val s = host.services
        // services.location: manual > (demo: Santa Cruz) > IP-detected (daily, and after a network
        // change) > San Francisco. Weather and commute read it; in demo with nothing manual, weather
        // keeps the sample data.
        val location = LocationResolver(s.settings, s.scope, s.config.demoData, AutoLocation(s.settings), NetworkChanges.flow(host.context))
        s.location = location.location
        location.start()
        val weather = OpenMeteoWeatherProvider(s.settings, s.scope, fallback = s.weather, location = location.location)
        s.weather = weather
        weather.start()

        // Always bound: the iCal URLs are a secret that can be set or changed live (configFlow).
        // With none it mirrors the fake (sample calendar in demo mode, empty otherwise).
        // Plus each profile's own calendars (SecretStore `calendar.icsUrls.<profileId>`): their events carry profileId.
        val personal = PersonalCalendars.flow({ s.profiles.profiles }, { s.secrets })
        val cal = IcsCalendarProvider(s.configFlow.map { it.secrets.calendarIcsUrls }, host.context.cacheDir, s.settings, s.scope, fallback = s.calendar, personal = personal)
        s.calendar = cal
        cal.start()

        // Travel time from home (the commute's Nominatim + OSRM); the leave-by alarm, leave-now and the brief read it.
        s.travel = HomeTravelEstimator(s.settings, location.location)

        HomeSettingsSchema.all.forEach(host::registerSettings)

        // Island alerts and the morning brief. Everything they read from other features is looked up lazily.
        val rain = RainAlertWatcher(s.settings, weather.weather, s.island, s.scope).also { it.start() }
        val leave = LeaveNowWatcher(s.settings, { s.calendar }, { s.travel }, { s.profiles.active.value }, s.island, s.scope,
            changes = { s.profiles.active.map { } }).also { it.start() }
        BriefWatcher(s.settings, { s.system }, { s.alarms }, { s.navigator }, s.scope).start()
        host.registerScreen(ScreenSpec(BriefWatcher.SCREEN_ID, order = 90, inPager = false) { ctx -> BriefView(ctx, host.services) })
        // The brief shows the leave time and rain itself; the island would sit over its greeting.
        host.services.island.hideOnScreen(BriefWatcher.SCREEN_ID)
        if (s.config.debug) debugIntents(s, rain, leave)

        host.registerScreen(ScreenSpec("commute", order = 40) { ctx -> CommuteView(ctx, host.services) })
        host.registerScreen(ScreenSpec("stocks", order = 50) { ctx -> StocksView(ctx, host.services) })
        // Weather radar (RainViewer over a drawn Natural Earth map), opened from the Weather face / widget.
        host.registerScreen(ScreenSpec(RadarScreen.SCREEN_ID, order = 55, inPager = false) { ctx -> RadarScreen(ctx, host.services) })

        val haConfigured = s.config.secrets.haConfigured
        if (haConfigured) {
            // services.homeAssistant is bound by :app before install when HA is configured.
            doorbell = DoorbellWatcher(s.homeAssistant, s.settings, s.notifications, s.island, s.scope).also { it.start() }
            host.registerScreen(ScreenSpec("home", order = 20) { ctx -> HomeScreenView(ctx, host.services, host.services.homeAssistant, demo = false) })
            // Light detail, opened from the Home lamp tile (long press) or `--es screen lamp --es entity <id>`.
            host.registerScreen(ScreenSpec(LampScreenView.SCREEN_ID, order = 25, inPager = false) { ctx -> LampScreenView(ctx, host.services, host.services.homeAssistant, demo = false) })
        } else if (s.config.demoData) {
            host.registerScreen(ScreenSpec(LampScreenView.SCREEN_ID, order = 25, inPager = false) { ctx -> LampScreenView(ctx, host.services, demoHome(host), demo = true) })
            host.registerScreen(ScreenSpec("home", order = 20) { ctx ->
                val ha = demoHome(host)
                object : HomeScreenView(ctx, host.services, ha, demo = true, defaults = DemoHomeAssistant::demoDefaults) {
                    override fun onScreenShown(args: Bundle?) {
                        super.onScreenShown(args)
                        // Debug: `--es screen home --es doorbell ring` runs the doorbell flow on the demo home.
                        if (args?.getString("doorbell") == "ring") demoRing(host)
                    }
                }
            })
        }
    }

    /**
     * Debug launch extras (nobody can wait for real rain at the Echo): `--es rain_demo 15` shows "Rain in
     * 15 min" (`off` removes it), `--es leave_demo 30` a Dentist 30 min from now with a 20 min drive.
     */
    private fun debugIntents(s: app.vesper.core.model.VesperServices, rain: RainAlertWatcher, leave: LeaveNowWatcher) {
        s.scope.launch {
            s.intents.collect { i ->
                i.getStringExtra("rain_demo")?.let { v ->
                    val m = v.toIntOrNull()
                    rain.demoNowcast = if (m == null) null else Nowcast(System.currentTimeMillis() + m * 60_000L, listOf(if (m >= 60) 3f else 1f, 1f))
                }
                i.getStringExtra("leave_demo")?.toIntOrNull()?.let { m ->
                    val start = System.currentTimeMillis() + m * 60_000L
                    leave.demo(CalendarEvent("demo-leave", "Dentist", "Downtown", start, start + 3_600_000L, color = 0xFFFF9F0A.toInt(), location = "Downtown, 120 Pacific Ave"), 20)
                }
            }
        }
    }

    /** One demo home for the Home and Lamp screens, so both show (and change) the same lights. */
    private fun demoHome(host: FeatureHost): DemoHomeAssistant = demoHa ?: DemoHomeAssistant(host.services.scope).also { demoHa = it }

    private fun demoRing(host: FeatureHost) {
        val ha = demoHa ?: return
        val s = host.services
        if (ha.cameraImage == null) ha.cameraImage = DemoSnapshot.jpeg()
        if (doorbell == null) doorbell = DoorbellWatcher(ha, s.settings, s.notifications, s.island, s.scope).also { it.start() }
        ha.ringDoorbell()
    }
}

