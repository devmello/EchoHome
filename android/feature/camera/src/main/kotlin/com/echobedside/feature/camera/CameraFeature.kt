package com.echobedside.feature.camera

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.OverlaySpec
import com.echobedside.core.model.Profile
import com.echobedside.core.model.ScreenSpec
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.feature.camera.face.ModelFiles
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

    /**
     * "Camera & people" on the web settings page. Everything applies live (the pipeline and the
     * profile manager watch these keys). Enrolment stays on the Echo: it needs the camera.
     */
    fun settingsSection() = SettingsSection(
        id = "camera", title = "Camera & people", order = 60,
        help = "The camera only looks for motion and faces; no image is ever saved. Faces are added on the Echo itself (Profiles screen), since that needs the camera.",
        settings = listOf(
            SettingSpec("camera.stage.presence", "Presence detection", SettingType.BOOLEAN,
                CameraPipeline.STAGE_DEFAULTS.getValue("presence").toString(),
                help = "Use the camera to notice when someone is in the room (dims an empty room, wakes face matching). Off keeps the camera off."),
            SettingSpec(CameraPipeline.KEY_DUTY, "Duty-cycled presence", SettingType.BOOLEAN, CameraPipeline.DUTY_DEFAULT.toString(),
                help = "Stop the camera between presence samples: about a third less CPU, slower to notice motion.", advanced = true),
            SettingSpec(FaceProfileManager.KEY_AUTO, "Auto by face", SettingType.BOOLEAN, "true",
                help = "Switch to a person's profile when the camera recognises them."),
            SettingSpec(CameraPipeline.KEY_EMBEDDER, "Face model", SettingType.CHOICE, ModelFiles.EMBEDDERS[0].key,
                help = "Which on-device model recognises faces. Changing it means adding each person's face again.",
                options = ModelFiles.EMBEDDERS.map { it.key to it.title }, advanced = true),
            SettingSpec(CameraPipeline.KEY_THRESHOLD, "Match threshold", SettingType.FLOAT, ModelFiles.EMBEDDERS[0].policy.floor.toString(),
                help = "Lowest similarity that counts as a match. Each person's own threshold comes from their enrolment and is never below this. " +
                    "Higher means fewer wrong matches and more \"Who's there?\".",
                min = 0.25f, max = 0.6f, step = 0.01f, advanced = true),
            SettingSpec(FaceProfileManager.KEY_TIMEOUT_MIN, "Return to Shared after", SettingType.INT, FaceProfileManager.TIMEOUT_DEFAULT_MIN.toString(),
                help = "Minutes of an empty room before a personal profile goes back to Shared (with Auto by face on).", min = 1f, max = 120f, step = 1f),
        ),
    )

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

        host.registerSettings(settingsSection())
        host.registerOverlay(OverlaySpec("camera", z = 250) { c -> CameraOverlayView(c, services, pipeline) })
        host.registerScreen(ScreenSpec("profiles", order = 910, inPager = false) { c -> ProfilesView(c, services, manager, pipeline) })
        host.registerScreen(ScreenSpec("profiles-enrol", order = 911, inPager = false) { c -> EnrolView(c, services, manager, pipeline) })
        host.registerScreen(ScreenSpec("camera-probe", order = 900, inPager = false) { c -> CaptureProbeView(c, services) })
        host.registerScreen(ScreenSpec("camera-bench", order = 901, inPager = false) { c -> CameraBenchView(c, services, pipeline) })
        host.registerScreen(ScreenSpec("profiles-demo", order = 902, inPager = false) { c -> ProfilesDemoView(c, services) })
    }
}
