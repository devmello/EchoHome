package com.echobedside.feature.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the receiver through Android's system mDNS daemon (NsdManager): `_raop._tcp` and,
 * for AirPlay 2, `_airplay._tcp`, with the TXT records the native receiver reports. The daemon
 * answers queries, so the app does no mDNS work while idle.
 */
internal class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    @Synchronized
    fun register(info: ServiceInfo) {
        unregister()
        add(info.raopName, "_raop._tcp", info.port, info.raopTxt)
        if (info.ap2) add(info.airplayName, "_airplay._tcp", info.port, info.airplayTxt)
    }

    @Synchronized
    fun unregister() {
        for (l in listeners) {
            try { nsd.unregisterService(l) } catch (e: IllegalArgumentException) { /* never registered */ }
        }
        listeners.clear()
    }

    private fun add(name: String, type: String, port: Int, txt: List<Pair<String, String>>) {
        val service = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            for ((k, v) in txt) {
                try { setAttribute(k, v) } catch (e: IllegalArgumentException) { Log.w(TAG, "TXT $k skipped: ${e.message}") }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "mDNS registered ${info.serviceName} $type :$port") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.e(TAG, "mDNS $type registration failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) { Log.i(TAG, "mDNS unregistered ${info.serviceName}") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS $type unregister failed: $code") }
        }
        listeners += listener
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private companion object { const val TAG = "EchoAirPlay" }
}
