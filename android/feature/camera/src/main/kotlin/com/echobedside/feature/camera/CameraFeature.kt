package com.echobedside.feature.camera

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:camera` (agent H — Camera: presence + profiles).
 *
 * Camera capture test, presence (Y-plane diff ~2 fps), face pipeline (NOTES §6.6), Profiles (Profiles.dc.html). Writes services.system presence/cameraInUse; binds services.profiles.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object CameraFeature : EchoFeature {
    override val id = "camera"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
