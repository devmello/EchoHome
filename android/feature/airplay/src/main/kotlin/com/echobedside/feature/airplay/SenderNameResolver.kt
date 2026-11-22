package com.echobedside.feature.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.util.Log
import java.net.InetAddress

/**
 * Finds a friendly name ("Maya's iPhone") for a sender IP. The AirPlay library only reports the
 * address, so: Apple devices advertise `_companion-link._tcp` under their device name; resolve
 * those until one matches the IP. Falls back to the router's reverse DNS ("Mayas-iPhone.lan").
 * Runs once per new sender; everything happens on [handler]'s thread.
 */
internal class SenderNameResolver(context: Context, private val handler: Handler) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val cache = HashMap<String, String>()
    private var active: Lookup? = null

    fun resolve(ip: String, onName: (String) -> Unit) {
        cache[ip]?.let { onName(it); return }
        if (active?.ip == ip) return
        active?.finish()
        active = Lookup(ip) { name ->
            cache[ip] = name
            onName(name)
        }.also { it.start() }
    }

    fun cancel() {
        active?.finish()
        active = null
    }

    private inner class Lookup(val ip: String, val onName: (String) -> Unit) {
        private val pending = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var done = false
        private var started = false

        private val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { fallback() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { handler.post { pending += info; next() } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        fun start() {
            try {
                nsd.discoverServices(COMPANION, NsdManager.PROTOCOL_DNS_SD, discovery)
                started = true
            } catch (e: Exception) {
                fallback(); return
            }
            handler.postDelayed({ if (!done) fallback() }, TIMEOUT_MS)
        }

        private fun next() {
            if (done || resolving) return
            val info = pending.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    handler.post { resolving = false; next() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    handler.post {
                        resolving = false
                        if (!done && info.host?.hostAddress == ip) {
                            Log.i(TAG, "sender $ip is \"${info.serviceName}\"")
                            finish(); onName(info.serviceName)
                        } else next()
                    }
                }
            })
        }

        /** No mDNS match: try reverse DNS on a background thread (it can block for seconds). */
        fun fallback() {
            if (done) return
            finish()
            Thread({
                val host = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { null }
                val name = host?.let { prettyHostName(it, ip) } ?: return@Thread
                handler.post { onName(name) }
            }, "airplay-rdns").start()
        }

        fun finish() {
            if (done) return
            done = true
            if (started) try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) {}
            if (active === this) active = null
        }
    }

    companion object {
        private const val TAG = "EchoAirPlay"
        private const val COMPANION = "_companion-link._tcp"
        private const val TIMEOUT_MS = 6_000L

        /** "Mayas-iPhone.lan" → "Mayas iPhone"; null if it's just the IP again. */
        fun prettyHostName(host: String, ip: String): String? {
            if (host.isBlank() || host == ip) return null
            val first = host.substringBefore('.')
            if (first.isEmpty() || first.all { it.isDigit() || it == '-' }) return null
            return first.replace('-', ' ').trim().ifEmpty { null }
        }
    }
}
