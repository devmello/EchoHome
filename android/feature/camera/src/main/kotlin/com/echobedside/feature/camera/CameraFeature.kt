package com.echobedside.feature.camera

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.OverlaySpec
import com.echobedside.core.model.Profile
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.camera.capture.CaptureProbeView
import com.echobedside.feature.camera.profiles.FaceProfileManager
import com.echobedside.feature.camera.profiles.SecretBox
import com.echobedside.feature.camera.profiles.TemplateStore
import com.echobedside.feature.camera.ui.CameraBenchView
import com.echobedside.feature.camera.ui.CameraOverlayView
import com.echobedside.feature.camera.ui.DemoStep
import com.echobedside.feature.camera.ui.EnrolView
import com.echobedside.feature.camera.ui.ProfilesDemoView
import com.echobedside.feature.camera.ui.ProfilesView
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * `:feature:camera` (agent H — Camera: presence + profiles), NOTES §6.5 / §6.6.
 *
 *  - Binds `services.profiles` with [FaceProfileManager] (persisted, Auto by face, Not me, timeout).
 *  - Writes `services.system` `presence` and `cameraInUse` from [CameraPipeline].
 *  - Overlay "camera" (z 250): camera label, empty-room dim, "Who's there?", "Hi <name>".
 *  - Screens (not in the pager): "profiles" (settings), "profiles-enrol" (`--es profile <id>`),
 *    and debug "camera-probe", "camera-bench", "profiles-demo".
 */
object CameraFeature : EchoFeature {
    override val id = "camera"

    private val _demo = MutableStateFlow<Pair<DemoStep, Profile?>?>(null)
    /** A forced Profiles step for design review (see ProfilesDemoView). */
    val demo: StateFlow<Pair<DemoStep, Profile?>?> = _demo.asStateFlow()
    fun showDemo(step: DemoStep?, profile: Profile?) { _demo.value = step?.let { it to profile } }

    lateinit var pipeline: CameraPipeline
        private set
    lateinit var manager: FaceProfileManager
        private set

    override fun install(host: FeatureHost) {
        val services = host.services
        val ctx = host.context
        val templates = TemplateStore(File(ctx.noBackupFilesDir, "faces"), SecretBox.androidKeystore())
        manager = FaceProfileManager(services.settings, templates, services.scope,
            services.system.state.map { it.presence }, services.config.demoData)
        services.profiles = manager
        pipeline = CameraPipeline(ctx, services, manager)
        pipeline.start()

        host.registerOverlay(OverlaySpec("camera", z = 250) { c -> CameraOverlayView(c, services, pipeline) })
        host.registerScreen(ScreenSpec("profiles", order = 910, inPager = false) { c -> ProfilesView(c, services, manager, pipeline) })
        host.registerScreen(ScreenSpec("profiles-enrol", order = 911, inPager = false) { c -> EnrolView(c, services, manager, pipeline) })
        host.registerScreen(ScreenSpec("camera-probe", order = 900, inPager = false) { c -> CaptureProbeView(c, services) })
        host.registerScreen(ScreenSpec("camera-bench", order = 901, inPager = false) { c -> CameraBenchView(c, services, pipeline) })
        host.registerScreen(ScreenSpec("profiles-demo", order = 902, inPager = false) { c -> ProfilesDemoView(c, services) })
    }
}
