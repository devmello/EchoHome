package com.echobedside.feature.camera

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.camera.capture.CaptureProbeView

/**
 * `:feature:camera` (agent H — Camera: presence + profiles).
 *
 * Camera capture test, presence (Y-plane diff ~2 fps), face pipeline (NOTES §6.6), Profiles (Profiles.dc.html). Writes services.system presence/cameraInUse; binds services.profiles.
 */
object CameraFeature : EchoFeature {
    override val id = "camera"

    override fun install(host: FeatureHost) {
        host.registerScreen(ScreenSpec("camera-probe", order = 900, inPager = false) { ctx -> CaptureProbeView(ctx, host.services) })
    }
}
