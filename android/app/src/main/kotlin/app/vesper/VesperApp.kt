package app.vesper

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import app.vesper.core.data.HomeAssistantClient
import app.vesper.core.data.PrefsSettingsStore
import app.vesper.core.data.EncryptedSecretStore
import app.vesper.core.data.loadSecrets
import app.vesper.core.design.Fonts
import app.vesper.core.model.CrashEntry
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.KeyHandler
import app.vesper.core.model.Navigator
import app.vesper.core.model.OverlaySpec
import app.vesper.core.model.ScreenSpec
import app.vesper.feature.airplay.AirPlayFeature
import app.vesper.feature.alarm.AlarmFeature
import app.vesper.feature.assistant.AssistantFeature
import app.vesper.feature.camera.CameraFeature
import app.vesper.feature.clock.ClockFeature
import app.vesper.feature.home.HomeFeature
import app.vesper.feature.lists.ListsFeature
import app.vesper.feature.media.MediaFeature
import app.vesper.feature.phone.PhoneFeature
import app.vesper.feature.photos.PhotosFeature
import app.vesper.feature.settings.SettingsFeature
import app.vesper.feature.settings.setup.SetupFeature
import app.vesper.feature.system.SystemFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Install order: providers first, the clock (main consumer) last. See VesperFeature.
 * [SetupFeature] (first-run setup, in `:feature:settings`) must stay first: it tells a fresh
 * install from a set-up one by looking at the settings store before anything else writes to it.
 */
val FeatureList: List<VesperFeature> = listOf(
    SetupFeature, SystemFeature, PhoneFeature, AirPlayFeature, MediaFeature, AlarmFeature, HomeFeature, ListsFeature,
    AssistantFeature, CameraFeature, PhotosFeature, ClockFeature, SettingsFeature,
)

/** Builds the shared services and installs every feature once per process. */
class VesperApp : Application(), FeatureHost {

    override lateinit var services: VesperServices
        private set
    override val context: Context get() = this

    val screens = mutableListOf<ScreenSpec>()
    val overlays = mutableListOf<OverlaySpec>()
    val keyHandlers = mutableListOf<KeyHandler>()
    val navigator = AppNavigator()

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is recorded and the launcher comes back.
        val crashLog = FileCrashLog(java.io.File(filesDir, "crashes.json"))
        Watchdog(this, crashLog).install()
        Fonts.init(this)
        val settings = PrefsSettingsStore(this)
        val builtIn = loadSecrets()
        val secretStore = EncryptedSecretStore(this)
        val secrets = builtIn.overriddenBy(secretStore)
        val demo = settings.getBoolean(KEY_DEMO, BuildConfig.DEBUG)
        services = VesperServices(VesperConfig(BuildConfig.DEBUG, demo, secrets), settings)
        services.secrets = secretStore
        services.navigator = navigator
        services.crashLog = crashLog
        // Secrets set from the settings page apply live (features read config.secrets at use time).
        services.scope.launch {
            secretStore.changes.collect { services.config = services.config.copy(secrets = builtIn.overriddenBy(secretStore)) }
        }
        if (secrets.haConfigured) services.homeAssistant = HomeAssistantClient(secrets.haUrl, secrets.haToken, services.scope)

        for (f in FeatureList) {
            try {
                f.install(this)
            } catch (e: Exception) {
                // One broken feature must not take the launcher down.
                Log.e(TAG, "Feature ${f.id} failed to install", e)
                crashLog.add(CrashEntry(System.currentTimeMillis(), CrashEntry.Kind.CRASH, "main",
                    "Feature ${f.id} failed to install: " + Watchdog.summary(e), Watchdog.stackOf(e)))
            }
        }
        registerDebugScreens()
        screens.sortBy { it.order }
        overlays.sortBy { it.z }
        services.homeAssistant.start()
        Log.i(TAG, "Started: ${FeatureList.size} features, ${screens.size} screens, ${overlays.size} overlays, " +
            "${services.faces.faces.value.size} faces, demo=$demo, ${secrets}")
    }

    override fun registerScreen(spec: ScreenSpec) { screens.removeAll { it.id == spec.id }; screens += spec }
    override fun registerOverlay(spec: OverlaySpec) { overlays.removeAll { it.id == spec.id }; overlays += spec }
    override fun registerKeyHandler(handler: KeyHandler) { keyHandlers += handler }

    companion object {
        const val TAG = "Vesper"
        const val KEY_DEMO = "app.demo"
        const val HOME_SCREEN = "standby"
    }
}

/** The shell's [Navigator]: the activity listens to [requests] and moves the pager. */
class AppNavigator : Navigator {
    private val _current = MutableStateFlow(VesperApp.HOME_SCREEN)
    override val current: StateFlow<String> = _current.asStateFlow()
    private val _requests = MutableSharedFlow<Pair<String, Bundle?>>(extraBufferCapacity = 8)
    val requests: SharedFlow<Pair<String, Bundle?>> = _requests

    override fun show(screenId: String, args: Bundle?) { _requests.tryEmit(screenId to args) }
    override fun home() = show(VesperApp.HOME_SCREEN)
    fun setCurrent(id: String) { _current.value = id }
}
