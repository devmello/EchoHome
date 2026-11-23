package app.vesper.feature.settings

import app.vesper.core.model.DefaultSettingsSchema
import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.AlarmStore
import app.vesper.core.model.fake.FakeAlarmStore
import app.vesper.feature.settings.api.AlarmsApi
import app.vesper.feature.settings.api.Assets
import app.vesper.feature.settings.api.EventHub
import app.vesper.feature.settings.api.RemoteApi
import app.vesper.feature.settings.api.RemoteDeps
import app.vesper.core.model.*
import app.vesper.core.model.fake.FakeProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.vesper.feature.settings.api.SettingsApi
import app.vesper.feature.settings.http.HttpRequest
import app.vesper.feature.settings.pair.InMemoryPairStore
import app.vesper.feature.settings.pair.Pairing
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.net.InetAddress

/** Behaves like SharedPreferences: typed values, ClassCastException on a type mismatch. */
class FakeSettingsStore : SettingsStore {
    val map = HashMap<String, Any>()
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: SharedFlow<String> = flow
    private inline fun <reified T> read(key: String, d: T): T = map[key]?.let { it as? T ?: throw ClassCastException(key) } ?: d
    private fun write(key: String, v: Any) { map[key] = v; flow.tryEmit(key) }
    override fun getString(key: String, default: String) = read(key, default)
    override fun putString(key: String, value: String) = write(key, value)
    override fun getInt(key: String, default: Int) = read(key, default)
    override fun putInt(key: String, value: Int) = write(key, value)
    override fun getFloat(key: String, default: Float) = read(key, default)
    override fun putFloat(key: String, value: Float) = write(key, value)
    override fun getBoolean(key: String, default: Boolean) = read(key, default)
    override fun putBoolean(key: String, value: Boolean) = write(key, value)
    override fun remove(key: String) { map.remove(key); flow.tryEmit(key) }
}

/** One spec of every type, as other features would register them. */
fun demoSections(actionLog: MutableList<String> = mutableListOf()): List<SettingsSection> = listOf(
    SettingsSection("general", "General", 0, listOf(
        SettingSpec("device.name", "Name", SettingType.TEXT, "Echo Show", help = "How the Echo shows up in AirPlay, Bluetooth and here."),
        SettingSpec("settings.restartApp", "Restart app", SettingType.ACTION, help = "Restarts EchoHome on the Echo (about 5 seconds).",
            onAction = { actionLog += "restart"; "Restarting the Echo app…" }),
    )),
    SettingsSection("clock", "Clock", 10, listOf(
        SettingSpec("clock.face", "Face", SettingType.CHOICE, "digital", options = listOf("digital" to "Digital", "analog" to "Analog", "float" to "Float", "world" to "World", "solar" to "Solar", "glass" to "Glass")),
        SettingSpec("clock.seconds", "Show seconds", SettingType.BOOLEAN, "false"),
        SettingSpec("clock.color.digital", "Colour", SettingType.COLOR, "#FF9F0A"),
        SettingSpec("clock.night", "Night mode", SettingType.CHOICE, "auto", options = listOf("auto" to "Auto", "on" to "On", "off" to "Off"),
            help = "Auto turns the screen red and dim when the room is dark."),
        SettingSpec("clock.night.start", "Night: from", SettingType.TIME, "22:00"),
        SettingSpec("clock.night.end", "Night: until", SettingType.TIME, "07:00"),
        SettingSpec("clock.nightLux", "Night below", SettingType.FLOAT, "4", min = 0f, max = 40f, step = 0.5f, advanced = true, help = "Lux"),
    )),
    SettingsSection("alarm", "Alarms & timers", 40, listOf(
        SettingSpec("alarm.snooze", "Snooze minutes", SettingType.INT, "9", min = 1f, max = 20f, step = 1f),
        SettingSpec("alarm.sunrise.minutes", "Sunrise minutes", SettingType.INT, "20", min = 5f, max = 60f, step = 5f, help = "How long the screen brightens before the alarm."),
        SettingSpec("alarm.volume", "Alarm volume", SettingType.FLOAT, "0.7", min = 0f, max = 1f, step = 0.05f),
        SettingSpec("alarm.sound", "Alarm sound", SettingType.CHOICE, "system", options = listOf("system" to "Default alarm sound", "chime" to "Soft chime", "beeps" to "Beeps")),
    )),
    SettingsSection("home", "Weather & glances", 50, listOf(
        SettingSpec("weather.location", "Location", SettingType.LOCATION, "", help = "Leave on automatic to use the Echo's internet location."),
        SettingSpec("stocks.symbols", "Stocks", SettingType.TEXT_LIST, "AAPL,MSFT", help = "Ticker symbols for the Stocks screen."),
        SettingSpec("weather.units", "Units", SettingType.CHOICE, "c", options = listOf("c" to "°C", "f" to "°F")),
        SettingSpec("calendar.color.0", "Colour of calendar 1", SettingType.COLOR, "", help = "Empty: the default palette."),
    )),
    SettingsFeature.accountsSection(),
)

fun newApi(
    settings: FakeSettingsStore = FakeSettingsStore(),
    secrets: InMemorySecretStore = InMemorySecretStore(),
    clock: LongArray = longArrayOf(1_000_000L),
    actionLog: MutableList<String> = mutableListOf(),
    builtIn: Secrets = Secrets(),
    assetsDir: File? = null,
    alarms: AlarmStore = FakeAlarmStore(demo = true),
    world: TestWorld = TestWorld(secrets, alarms),
): SettingsApi {
    val schema = DefaultSettingsSchema()
    demoSections(actionLog).forEach(schema::register)
    val pairing = Pairing(InMemoryPairStore(), { clock[0] }, { 1_795_330_000_000L })
    return SettingsApi(
        schema = schema,
        settings = { settings },
        secrets = { secrets },
        effective = { builtIn },
        deviceName = { settings.getString("device.name", "Echo Show").ifBlank { "Echo Show" } },
        pairing = pairing,
        events = EventHub(heartbeatMs = 200),
        assets = Assets(cached = false) { name ->
            val dir = assetsDir ?: File("src/main/assets/settings-web")
            File(dir, name).takeIf { it.isFile }?.readBytes()
        }, // read from disk each time, so the dev server shows edits live
        runAction = { spec -> spec.onAction?.invoke() },
        alarms = AlarmsApi({ alarms }, { it() }),
        remote = world.remote(),
    )
}

fun req(
    method: String,
    path: String,
    body: String? = null,
    cookie: String? = null,
    host: String = "192.168.10.136:8080",
    headers: Map<String, String> = emptyMap(),
): HttpRequest {
    val h = HashMap<String, String>()
    h["host"] = host
    h["user-agent"] = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1"
    if (body != null) h["content-type"] = "application/json"
    if (cookie != null) h["cookie"] = "${Pairing.COOKIE}=$cookie"
    for ((k, v) in headers) h[k.lowercase()] = v
    val q = path.indexOf('?')
    return HttpRequest(method, if (q >= 0) path.substring(0, q) else path, emptyMap(), h,
        body?.toByteArray() ?: ByteArray(0), InetAddress.getByName("192.168.10.20"))
}

/** Pairs through the API and returns the cookie token. */
fun pair(api: SettingsApi): String {
    val start = org.json.JSONObject(api.handle(req("POST", "/api/pair/start", "{}")).body.decodeToString())
    val code = api.pairing.prompt.value!!.code
    val res = api.handle(req("POST", "/api/pair", "{\"pairId\":\"${start.getString("pairId")}\",\"code\":\"$code\"}"))
    val cookie = res.headers.first { it.first == "Set-Cookie" }.second
    return cookie.substringAfter("=").substringBefore(";")
}

class FakeControls : MediaControls {
    val calls = mutableListOf<String>()
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun seekTo(positionMs: Long) {}
    override fun setVolume(volume: Float) {}
    override val canSkip = true
}

class FakeTimers(private val now: () -> Long) : TimerStore {
    private val _t = MutableStateFlow<List<VesperTimer>>(emptyList())
    override val timers: StateFlow<List<VesperTimer>> = _t.asStateFlow()
    private var n = 0
    override fun start(durationMs: Long, label: String): VesperTimer? {
        if (_t.value.size >= TimerStore.MAX_TIMERS) return null
        val t = VesperTimer("t${n++}", label, durationMs, now() + durationMs, VesperTimer.State.RUNNING)
        _t.value = _t.value + t
        return t
    }
    private fun mut(id: String, f: (VesperTimer) -> VesperTimer) { _t.value = _t.value.map { if (it.id == id) f(it) else it } }
    override fun pause(id: String) = mut(id) { it.copy(state = VesperTimer.State.PAUSED, pausedRemainingMs = it.remainingMs(now())) }
    override fun resume(id: String) = mut(id) { it.copy(state = VesperTimer.State.RUNNING, endsAtElapsed = now() + it.pausedRemainingMs) }
    override fun addTime(id: String, ms: Long) = mut(id) { it.copy(endsAtElapsed = it.endsAtElapsed + ms, durationMs = it.durationMs + ms) }
    override fun cancel(id: String) { _t.value = _t.value.filterNot { it.id == id } }
    override fun dismiss(id: String) = cancel(id)
}

class FakeCrashLog(initial: List<CrashEntry> = emptyList()) : CrashLog {
    private val _e = MutableStateFlow(initial)
    override val entries: StateFlow<List<CrashEntry>> = _e.asStateFlow()
    override fun clear() { _e.value = emptyList() }
}

/** The services behind the phone remote, with fakes the tests can poke. */
class TestWorld(val secrets: SecretStore, val alarms: AlarmStore) {
    val elapsed = longArrayOf(10_000L)
    val wall = longArrayOf(1_795_330_000_000L)
    val media = DefaultMediaHub()
    val system = DefaultSystemStateStore()
    val controls = StateOnlySystemControls(system)
    val timers = FakeTimers { elapsed[0] }
    val notifications = DefaultNotificationFeed()
    val profiles = FakeProfileManager()
    val crashLog = FakeCrashLog(listOf(
        CrashEntry(1_795_000_000_000L, CrashEntry.Kind.CRASH, "main", "NullPointerException in ClockView", "at app.vesper.Clock.draw(Clock.kt:12)\nat android.view.View.draw"),
        CrashEntry(1_795_100_000_000L, CrashEntry.Kind.HANG, "main", "Main thread blocked for 6 s", "at java.lang.Thread.sleep"),
    ))
    val mediaControls = FakeControls()

    fun playSomething(playing: Boolean = true) = media.publish("test",
        MediaState("Song", "Artist", durationMs = 200_000, positionMs = 50_000, positionSampledAt = elapsed[0], playing = playing,
            source = MediaSourceKind.AIRPLAY, sourceLabel = "Maya's iPhone"), mediaControls)

    /** The settings a person's preferences override (their options are what /prefs accepts). */
    val prefSchema = DefaultSettingsSchema().apply {
        register(SettingsSection("prefs", "Prefs", 0, listOf(
            SettingSpec("clock.face", "Face", SettingType.CHOICE, "digital", options = listOf("digital" to "Digital", "glass" to "Liquid Glass", "solar" to "Solar")),
            SettingSpec("clock.glass.wallpaper", "Wallpaper", SettingType.CHOICE, "Blue", options = listOf("Blue" to "Blue", "Dusk" to "Dusk", "Graphite" to "Graphite")),
            SettingSpec("clock.night.mode", "Night mode", SettingType.CHOICE, "auto", options = listOf("auto" to "Auto", "schedule" to "Schedule", "on" to "On", "off" to "Off")),
            SettingSpec("alarm.sound", "Alarm sound", SettingType.CHOICE, "system", options = listOf("system" to "Default", "chime" to "Soft chime", "beeps" to "Beeps")),
        )))
    }

    fun remote() = RemoteApi(RemoteDeps(
        media = { media }, system = { system }, controls = { controls }, timers = { timers }, alarms = { alarms },
        notifications = { notifications }, profiles = { profiles }, secrets = { secrets }, crashLog = { crashLog },
        elapsed = { elapsed[0] }, wall = { wall[0] }, schema = { prefSchema },
    ), { it() })
}
