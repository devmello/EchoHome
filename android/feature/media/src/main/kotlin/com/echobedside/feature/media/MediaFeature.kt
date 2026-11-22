package com.echobedside.feature.media

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:media` (agent D — Now Playing + media).
 *
 * Now Playing screen (NowPlaying.dc.html), media island states, other apps' sessions (MediaSessionManager via NotificationListener) into services.media.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object MediaFeature : EchoFeature {
    override val id = "media"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
