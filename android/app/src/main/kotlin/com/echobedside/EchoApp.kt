package com.echobedside

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.echobedside.core.data.HomeAssistantClient
import com.echobedside.core.data.PrefsSettingsStore
import com.echobedside.core.data.EncryptedSecretStore
import com.echobedside.core.data.loadSecrets
import com.echobedside.core.design.Fonts
import com.echobedside.core.model.EchoConfig
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.KeyHandler
import com.echobedside.core.model.Navigator
import com.echobedside.core.model.OverlaySpec
import com.echobedside.core.model.ScreenSpec
import com.echobedside.feature.airplay.AirPlayFeature
import com.echobedside.feature.alarm.AlarmFeature
import com.echobedside.feature.assistant.AssistantFeature
import com.echobedside.feature.camera.CameraFeature
import com.echobedside.feature.clock.ClockFeature
import com.echobedside.feature.home.HomeFeature
import com.echobedside.feature.media.MediaFeature
import com.echobedside.feature.phone.PhoneFeature
import com.echobedside.feature.photos.PhotosFeature
import com.echobedside.feature.system.SystemFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Install order: providers first, the clock (main consumer) last. See EchoFeature. */
val FeatureList: List<EchoFeature> = listOf(
    SystemFeature, PhoneFeature, AirPlayFeature, MediaFeature, AlarmFeature, HomeFeature,
    AssistantFeature, CameraFeature, PhotosFeature, ClockFeature,
)

/** Builds the shared services and installs every feature once per process. */
class EchoApp : Application(), FeatureHost {

    override lateinit var services: EchoServices
        private set
    override val context: Context get() = this

    val screens = mutableListOf<ScreenSpec>()
    val overlays = mutableListOf<OverlaySpec>()
    val keyHandlers = mutableListOf<KeyHandler>()
    val navigator = AppNavigator()

    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        val settings = PrefsSettingsStore(this)
        val builtIn = loadSecrets()
        val secretStore = EncryptedSecretStore(this)
        val secrets = builtIn.overriddenBy(secretStore)
        val demo = settings.getBoolean(KEY_DEMO, BuildConfig.DEBUG)
        services = EchoServices(EchoConfig(BuildConfig.DEBUG, demo, secrets), settings)
        services.secrets = secretStore
        services.navigator = navigator
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
        const val TAG = "EchoBedside"
        const val KEY_DEMO = "app.demo"
        const val HOME_SCREEN = "standby"
    }
}

/** The shell's [Navigator]: the activity listens to [requests] and moves the pager. */
class AppNavigator : Navigator {
    private val _current = MutableStateFlow(EchoApp.HOME_SCREEN)
    override val current: StateFlow<String> = _current.asStateFlow()
    private val _requests = MutableSharedFlow<Pair<String, Bundle?>>(extraBufferCapacity = 8)
    val requests: SharedFlow<Pair<String, Bundle?>> = _requests

    override fun show(screenId: String, args: Bundle?) { _requests.tryEmit(screenId to args) }
    override fun home() = show(EchoApp.HOME_SCREEN)
    fun setCurrent(id: String) { _current.value = id }
}
