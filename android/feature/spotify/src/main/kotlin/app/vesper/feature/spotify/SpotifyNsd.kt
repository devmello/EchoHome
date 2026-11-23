package app.vesper.feature.spotify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises `_spotify-connect._tcp` (TXT `VERSION=1.0`, `CPath=/`) through Android's system mDNS
 * daemon, the way `:feature:airplay` advertises `_raop._tcp`: the daemon answers queries, so the
 * app does no mDNS work and holds no multicast lock while idle.
 */
internal class SpotifyNsd(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(name: String, port: Int) {
        unregister()
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = NativeBridge.SERVICE_TYPE
            setPort(port)
            for ((k, v) in NativeBridge.TXT) setAttribute(k, v)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "mDNS registered \"${info.serviceName}\" ${NativeBridge.SERVICE_TYPE} :$port") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.e(TAG, "mDNS registration failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) { Log.i(TAG, "mDNS unregistered") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS unregister failed: $code") }
        }
        listener = l
        try {
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: RuntimeException) {
            listener = null
            Log.e(TAG, "mDNS register: $e")
        }
    }

    @Synchronized
    fun unregister() {
        val l = listener ?: return
        listener = null
        try { nsd.unregisterService(l) } catch (e: IllegalArgumentException) { /* never registered */ }
    }

    private companion object { const val TAG = "VesperSpotify" }
}
