package com.echobedside.feature.photos

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.photos.ui.PhotosScreen

/**
 * `:feature:photos` (agent I — Photos): the Immich photo frame (Photos.dc.html).
 *
 * One pager screen, "photos" at order 70. Source: Immich when `immich.url` / `immich.apiKey`
 * are set in secrets.properties, else the procedural stand-ins in demo mode, else a quiet
 * "add a server" state. Settings: `photos.album.id`, `photos.album.name`, `photos.seconds`.
 */
object PhotosFeature : EchoFeature {
    override val id = "photos"

    override fun install(host: FeatureHost) {
        host.registerScreen(ScreenSpec("photos", order = 70) { ctx -> PhotosScreen(ctx, host.services) })
    }
}
