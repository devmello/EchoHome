package com.echobedside.feature.photos

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:photos` (agent I — Photos).
 *
 * Immich photo frame (Photos.dc.html): album, cached 960-px previews, prefetch, crossfade + slow zoom, time overlay, glass controls.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object PhotosFeature : EchoFeature {
    override val id = "photos"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
