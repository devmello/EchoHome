package app.vesper.core.model

import app.vesper.core.model.fake.FakeAlarmStore
import app.vesper.core.model.fake.FakeCalendarProvider
import app.vesper.core.model.fake.FakeProfileManager
import app.vesper.core.model.fake.FakeTimerStore
import app.vesper.core.model.fake.FakeWeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every shared service, one instance per process (created by `:app`).
 *
 * Slots start as in-memory defaults or fakes (core.model.fake) so each feature runs alone.
 * The feature that owns a contract replaces its slot in [VesperFeature.install]:
 *   system → systemControls · alarm → alarms, timers · home → weather (Open-Meteo), calendar (iCal URLs), location ·
 *   camera → profiles (face switching) · `:app` → homeAssistant (optional), settings, config.
 * Read slots lazily (in view factories / on attach), never cache them during install.
 */
class VesperServices(
    config: VesperConfig,
    var settings: SettingsStore,
) {
    private val _config = MutableStateFlow(config)
    /** Read [config] at use time (not once at install): secrets can change while the app runs. */
    val configFlow: StateFlow<VesperConfig> = _config.asStateFlow()
    var config: VesperConfig
        get() = _config.value
        set(v) { _config.value = v }

    /** App-wide scope on the main thread. Use Dispatchers.IO / Default inside for blocking work. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var navigator: Navigator

    val island: IslandManager = DefaultIslandManager()
    val media: MediaHub = DefaultMediaHub()
    val system: SystemStateStore = DefaultSystemStateStore()
    val notifications: NotificationFeed = DefaultNotificationFeed()
    val faces: FaceRegistry = DefaultFaceRegistry()
    /** Every feature's user settings, for the web settings page (see [SettingSpec]). */
    val settingsSchema: SettingsSchema = DefaultSettingsSchema()
    /** Runtime secrets; `:app` binds the encrypted store and folds it into [config].secrets. */
    var secrets: SecretStore = InMemorySecretStore()

    var systemControls: SystemControls = StateOnlySystemControls(system)
    var alarms: AlarmStore = FakeAlarmStore(config.demoData)
    var timers: TimerStore = FakeTimerStore(island)
    var profiles: ProfileManager = FakeProfileManager()
    var weather: WeatherProvider = FakeWeatherProvider(config.demoData)
    var calendar: CalendarProvider = FakeCalendarProvider(config.demoData)
    var homeAssistant: HomeAssistant = NotConfiguredHomeAssistant
    /** Travel time from home (`:feature:home` binds its commute routing). */
    var travel: TravelEstimator = NoTravelEstimator
    /** The iPhone link: name, connection, battery (`:feature:phone` binds it). */
    var phone: StateFlow<PhoneStatus?> = MutableStateFlow(null)
    /** Crashes and hangs (`:app`'s watchdog binds it; the settings page shows it). */
    var crashLog: CrashLog = EmptyCrashLog
    /** Home location (see [HomeLocation]); `:feature:home` binds manual > auto > Livermore. */
    var location: StateFlow<HomeLocation> = MutableStateFlow(HomeLocation.default(config.demoData))

    /**
     * Every launch / new intent of the HOME activity (replays the latest), so overlays can read
     * debug extras that arrive by `onNewIntent`. Only `:app` emits.
     */
    val intents: MutableSharedFlow<Intent> = MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
}

/**
 * @param demoData show the designs' sample data where no real source is configured.
 *   On by default in debug builds; toggle with `--ez demo true|false` on the launch intent.
 */
data class VesperConfig(
    val debug: Boolean,
    val demoData: Boolean,
    val secrets: Secrets,
)

/**
 * From android/secrets.properties via `:core:data`. Everything is optional: the app must work
 * with none of it (Home Assistant is NOT required — NOTES §4.1). [toString] never prints values.
 */
class Secrets(
    /** Optional smart-home integration only (Home screen, doorbell). */
    val haUrl: String = "",
    val haToken: String = "",
    val immichUrl: String = "",
    val immichApiKey: String = "",
    /** Claude for the voice assistant. */
    val anthropicApiKey: String = "",
    /** Unused by default: stocks use a free keyless API. Kept for an optional keyed provider. */
    val stocksApiKey: String = "",
    /** Calendar subscriptions: private iCal (.ics) / webcal URLs, e.g. Google's "secret address in iCal format". */
    val calendarIcsUrls: List<String> = emptyList(),
) {
    val haConfigured get() = haUrl.isNotBlank() && haToken.isNotBlank()
    val immichConfigured get() = immichUrl.isNotBlank() && immichApiKey.isNotBlank()
    val anthropicConfigured get() = anthropicApiKey.isNotBlank()
    val calendarConfigured get() = calendarIcsUrls.isNotEmpty()

    /** Values from [store] win over these (the compiled-in ones). */
    fun overriddenBy(store: SecretStore): Secrets {
        fun v(key: String, built: String) = store.get(key)?.takeIf { it.isNotBlank() } ?: built
        return Secrets(
            haUrl = v(HA_URL, haUrl), haToken = v(HA_TOKEN, haToken),
            immichUrl = v(IMMICH_URL, immichUrl), immichApiKey = v(IMMICH_API_KEY, immichApiKey),
            anthropicApiKey = v(ANTHROPIC_API_KEY, anthropicApiKey), stocksApiKey = v(STOCKS_API_KEY, stocksApiKey),
            calendarIcsUrls = store.get(CALENDAR_ICS_URLS)?.takeIf { it.isNotBlank() }?.let(::splitUrls) ?: calendarIcsUrls,
        )
    }

    companion object {
        // The secrets.properties names, also the SecretStore keys.
        const val HA_URL = "ha.url"
        const val HA_TOKEN = "ha.token"
        const val IMMICH_URL = "immich.url"
        const val IMMICH_API_KEY = "immich.apiKey"
        const val ANTHROPIC_API_KEY = "anthropic.apiKey"
        const val STOCKS_API_KEY = "stocks.apiKey"
        const val CALENDAR_ICS_URLS = "calendar.icsUrls"
        val KEYS = listOf(ANTHROPIC_API_KEY, CALENDAR_ICS_URLS, IMMICH_URL, IMMICH_API_KEY, HA_URL, HA_TOKEN, STOCKS_API_KEY)
        /** A person's own calendars (iCal URLs, comma/newline separated) in the [SecretStore]; events get [CalendarEvent.profileId]. */
        fun calendarKeyFor(profileId: String) = "$CALENDAR_ICS_URLS.$profileId"
        fun splitUrls(s: String) = s.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
    override fun toString() =
        "Secrets(ha=$haConfigured, immich=$immichConfigured, anthropic=$anthropicConfigured, calendars=${calendarIcsUrls.size})"
}

/**
 * Persistent key-value settings (SharedPreferences in `:core:data`).
 * Namespace keys by feature: "clock.face", "clock.color.digital", "system.island.look", ...
 */
interface SettingsStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    /** Emits the key of every change. */
    val changes: SharedFlow<String>
}
