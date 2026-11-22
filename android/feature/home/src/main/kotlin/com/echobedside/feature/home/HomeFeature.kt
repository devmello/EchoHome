package com.echobedside.feature.home

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:home` (agent F — Home + glances).
 *
 * Home Assistant Home screen (Home.dc.html), Weather/Calendar providers (binds services.weather, services.calendar), Commute (Commute.dc.html), Stocks (Stocks.dc.html), doorbell into services.notifications + island.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object HomeFeature : EchoFeature {
    override val id = "home"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
