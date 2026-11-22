package com.echobedside.feature.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.data.loadSecrets
import com.echobedside.core.model.DeviceName
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.SettingSpec
import com.echobedside.feature.settings.api.AlarmsApi
import com.echobedside.feature.settings.api.Assets
import com.echobedside.feature.settings.api.EventHub
import com.echobedside.feature.settings.api.SettingsApi
import com.echobedside.feature.settings.http.HttpServer
import com.echobedside.feature.settings.pair.PairStore
import com.echobedside.feature.settings.pair.PairedBrowser
import com.echobedside.feature.settings.pair.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Where a phone reaches the settings page. */
data class WebAddress(val ip: String, val port: Int, val host: String? = null) {
    private val portSuffix get() = if (port == 80) "" else ":$port"
    val url: String get() = "http://$ip$portSuffix/"
    /** No trailing slash: "http://192.168.10.136:8080" fits a version 2 code (bigger modules, easier to scan). */
    val qrUrl: String get() = "http://$ip$portSuffix"
    /** "192.168.10.136:8080" for the Echo's screen. */
    val display: String get() = "$ip$portSuffix"
    val hostDisplay: String? get() = host?.let { "$it$portSuffix" }
}

/**
 * The web settings service: HTTP server + pairing + SSE + mDNS advertisement, for the life of
 * the process. Idle cost: one thread parked in accept(), the system mDNS daemon answering for
 * us, and a network callback (no polling).
 */
internal class WebSettings(private val app: Context, private val services: EchoServices) {
    val pairing = Pairing(PrefsPairStore(app), SystemClock::elapsedRealtime)
    val events = EventHub()
    private val main = Handler(Looper.getMainLooper())
    private val builtIn by lazy { loadSecrets() }

    val api = SettingsApi(
        schema = services.settingsSchema,
        settings = { services.settings },
        secrets = { services.secrets },
        effective = { builtIn },
        deviceName = { DeviceName.get(services.settings) },
        pairing = pairing,
        events = events,
        assets = Assets { name -> try { app.assets.open("settings-web/$name").use { it.readBytes() } } catch (e: java.io.IOException) { null } },
        runAction = ::runOnMain,
        alarms = AlarmsApi({ services.alarms }, ::onMain),
    )
    private val server = HttpServer(api::handle, log = { Log.i(TAG, it) })

    private val _address = MutableStateFlow<WebAddress?>(null)
    val address: StateFlow<WebAddress?> = _address.asStateFlow()
    @Volatile private var port = 0
    private var ip: String? = null

    fun start() {
        Thread({
            try {
                port = server.start()
            } catch (e: Exception) {
                Log.e(TAG, "settings server failed to start: ${e.javaClass.simpleName}")
                return@Thread
            }
            main.post { onStarted() }
        }, "settings-start").start()
        wireEvents()
    }

    private fun onStarted() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val v4 = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                main.post { setIp(v4) }
            }
            override fun onLost(network: Network) { main.post { setIp(null) } }
        })
        advertise()
    }

    private fun setIp(v4: String?) {
        if (v4 == ip) return
        ip = v4
        _address.value = v4?.let { WebAddress(it, port, mdnsHost) }
        if (v4 != null) Log.i(TAG, "settings page at http://$v4:$port/")
    }

    // --- mDNS (_http._tcp via the system daemon) ---

    private val nsd by lazy { app.getSystemService(NsdManager::class.java) }
    private var registration: NsdManager.RegistrationListener? = null
    /** Safari can't reach a .local name on this Android build (the daemon's host is not ours), see status. */
    private val mdnsHost: String? = null

    private fun advertise() {
        val p = port // not inside apply{}: there `port` would be NsdServiceInfo's own (0)
        if (p <= 0) return
        registration?.let { try { nsd.unregisterService(it) } catch (e: IllegalArgumentException) {} }
        registration = null
        val info = NsdServiceInfo()
        info.serviceName = DeviceName.get(services.settings)
        info.serviceType = "_http._tcp"
        info.port = p
        info.setAttribute("path", "/")
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "mDNS _http._tcp registered as ${i.serviceName} :$p") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS registration failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            registration = l
        } catch (e: RuntimeException) {
            // The page still works by IP; never take the launcher down over mDNS.
            Log.w(TAG, "mDNS registration refused: ${e.javaClass.simpleName}")
        }
    }

    // --- live sync to open pages ---

    private fun wireEvents() {
        val scope = services.scope
        scope.launch {
            services.settings.changes.collect { key ->
                if (key == DeviceName.KEY && port != 0) advertise()
                if (events.hasClients()) api.changeJson(key)?.let { events.publish("change", it.toString()) }
            }
        }
        scope.launch {
            services.secrets.changes.collect { key ->
                if (events.hasClients()) api.changeJson(key)?.let { events.publish("change", it.toString()) }
            }
        }
        scope.launch { services.settingsSchema.sections.drop(1).collect { if (events.hasClients()) events.publish("schema", "{}") } }
        scope.launch { pairing.browsers.drop(1).collect { if (events.hasClients()) events.publish("pairs", "{}") } }
        // services.alarms is bound by :feature:alarm, installed before this feature.
        scope.launch {
            val store = services.alarms
            combine(store.alarms, store.next) { a, n -> a to n }.drop(1).collect { (a, n) ->
                if (events.hasClients()) events.publish("alarms", AlarmsApi.listJson(a, n).toString())
            }
        }
    }

    /** ACTIONs run on the main thread (SettingSpec contract). */
    private fun runOnMain(spec: SettingSpec): String? {
        val action = spec.onAction ?: return null
        return try { onMain(action) as String? } catch (e: TimeoutException) { "Still running on the Echo" }
    }

    /** Runs [block] on the main thread and waits for it (up to 10 s) from an HTTP thread. */
    private fun onMain(block: () -> Any?): Any? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<Any?>>()
        val done = CountDownLatch(1)
        main.post { result.set(runCatching(block)); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw TimeoutException()
        return result.get().getOrThrow()
    }

    companion object { const val TAG = "EchoSettingsWeb" }
}

/** Paired browsers in a private prefs file (token hashes only). */
internal class PrefsPairStore(context: Context) : PairStore {
    private val prefs = context.getSharedPreferences("settings_web", Context.MODE_PRIVATE)

    override fun load(): List<PairedBrowser> = try {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PairedBrowser(o.getString("id"), o.getString("hash"), o.optString("name"), o.optLong("created"), o.optLong("seen"))
        }
    } catch (e: Exception) { emptyList() }

    override fun save(list: List<PairedBrowser>) {
        val a = JSONArray()
        for (b in list) a.put(JSONObject().put("id", b.id).put("hash", b.tokenHash).put("name", b.name).put("created", b.createdAt).put("seen", b.lastSeenAt))
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    private companion object { const val KEY = "browsers" }
}
