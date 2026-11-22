package com.echobedside.feature.settings

import com.echobedside.core.model.DefaultSettingsSchema
import com.echobedside.core.model.InMemorySecretStore
import com.echobedside.core.model.Secrets
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.core.model.SettingsStore
import com.echobedside.core.model.AlarmStore
import com.echobedside.core.model.fake.FakeAlarmStore
import com.echobedside.feature.settings.api.AlarmsApi
import com.echobedside.feature.settings.api.Assets
import com.echobedside.feature.settings.api.EventHub
import com.echobedside.feature.settings.api.SettingsApi
import com.echobedside.feature.settings.http.HttpRequest
import com.echobedside.feature.settings.pair.InMemoryPairStore
import com.echobedside.feature.settings.pair.Pairing
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
        SettingSpec("settings.restartApp", "Restart app", SettingType.ACTION, help = "Restarts Vesper on the Echo (about 5 seconds).",
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
