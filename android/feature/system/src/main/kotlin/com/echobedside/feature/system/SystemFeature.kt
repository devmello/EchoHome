package com.echobedside.feature.system

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:system` (agent B — System layer).
 *
 * Volume keys in-app (no system panel), mic/camera button (gating KEY_POWER remap: ask before /system), shutter (SW_CAMERA_LENS_COVER), HUDs (HUDs.dc.html), Control Center (ControlCenter.dc.html), banners (Banners.dc.html) and the Echo Island renderer (Island*.dc.html, NOTES §5.4). Binds services.systemControls.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object SystemFeature : EchoFeature {
    override val id = "system"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
