package com.echobedside.feature.airplay

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:airplay` (agent C — AirPlay audio).
 *
 * Audio-only AirPlay 2 receiver (NOTES §6) as a foreground service publishing into services.media (MediaHub) and an AIRPLAY_CONNECTED / MUSIC LiveActivity.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object AirPlayFeature : EchoFeature {
    override val id = "airplay"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
