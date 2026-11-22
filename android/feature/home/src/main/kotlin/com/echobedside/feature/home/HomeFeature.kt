package com.echobedside.feature.home

import android.os.Bundle
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.home.commute.CommuteView
import com.echobedside.feature.home.doorbell.DemoSnapshot
import com.echobedside.feature.home.doorbell.DoorbellWatcher
import com.echobedside.feature.home.glance.AutoLocation
import com.echobedside.feature.home.glance.IcsCalendarProvider
import com.echobedside.feature.home.glance.LocationResolver
import com.echobedside.feature.home.glance.NetworkChanges
import com.echobedside.feature.home.glance.OpenMeteoWeatherProvider
import com.echobedside.feature.home.ha.DemoHomeAssistant
import com.echobedside.feature.home.home.HomeScreenView
import com.echobedside.feature.home.stocks.StocksView

/**
 * `:feature:home` (agent F — glances + optional smart home). Works with no Home Assistant (NOTES §4.1):
 *  - services.weather → Open-Meteo for the manual location, else the IP-detected one, else
 *    Livermore (works with no settings). Demo mode with no manual location: the sample weather.
 *  - services.calendar → the iCal URLs in secrets (`calendar.icsUrls`); none = the fake stays.
 *  - Commute (order 40): Nominatim + OSRM, no key. Stocks (order 50): Yahoo chart, no key.
 *  - Home (order 20) + doorbell: only with Home Assistant configured. In demo mode without HA the
 *    Home screen still shows, driven by [DemoHomeAssistant] and labelled demo.
 */
object HomeFeature : EchoFeature {
    override val id = "home"

    private var demoHa: DemoHomeAssistant? = null
    private var doorbell: DoorbellWatcher? = null

    override fun install(host: FeatureHost) {
        val s = host.services
        // services.location: manual > (demo: Santa Cruz) > IP-detected (daily, and after a network
        // change) > Livermore. Weather and commute read it; in demo with nothing manual, weather
        // keeps the sample data.
        val location = LocationResolver(s.settings, s.scope, s.config.demoData, AutoLocation(s.settings), NetworkChanges.flow(host.context))
        s.location = location.location
        location.start()
        val weather = OpenMeteoWeatherProvider(s.settings, s.scope, fallback = s.weather, location = location.location)
        s.weather = weather
        weather.start()

        if (s.config.secrets.calendarConfigured) {
            val cal = IcsCalendarProvider(s.config.secrets.calendarIcsUrls, host.context.cacheDir, s.settings, s.scope)
            s.calendar = cal
            cal.start()
        }

        host.registerScreen(ScreenSpec("commute", order = 40) { ctx -> CommuteView(ctx, host.services) })
        host.registerScreen(ScreenSpec("stocks", order = 50) { ctx -> StocksView(ctx, host.services) })

        val haConfigured = s.config.secrets.haConfigured
        if (haConfigured) {
            // services.homeAssistant is bound by :app before install when HA is configured.
            doorbell = DoorbellWatcher(s.homeAssistant, s.settings, s.notifications, s.island, s.scope).also { it.start() }
            host.registerScreen(ScreenSpec("home", order = 20) { ctx -> HomeScreenView(ctx, host.services, host.services.homeAssistant, demo = false) })
        } else if (s.config.demoData) {
            host.registerScreen(ScreenSpec("home", order = 20) { ctx ->
                val ha = DemoHomeAssistant(host.services.scope).also { demoHa = it }
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

    private fun demoRing(host: FeatureHost) {
        val ha = demoHa ?: return
        val s = host.services
        if (ha.cameraImage == null) ha.cameraImage = DemoSnapshot.jpeg()
        if (doorbell == null) doorbell = DoorbellWatcher(ha, s.settings, s.notifications, s.island, s.scope).also { it.start() }
        ha.ringDoorbell()
    }
}

