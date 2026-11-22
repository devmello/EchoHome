package com.echobedside.feature.settings.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP/1.1 server. One thread blocks in `accept()` (0% CPU while idle); each connection
 * runs on a pool thread that exits 30 s after its last connection, so an idle server is one
 * parked thread and a closed socket backlog. Connections from outside the LAN are dropped
 * before anything is read ([Lan.isLocal]).
 */
class HttpServer(
    private val handler: (HttpRequest) -> HttpResponse,
    /** Tried in order; the first that binds wins (80 needs CAP_NET_BIND_SERVICE, which apps don't have). */
    private val ports: List<Int> = listOf(80, 8080, 8081, 8088),
    private val log: (String) -> Unit = {},
) {
    @Volatile var port: Int = 0
        private set
    @Volatile private var socket: ServerSocket? = null
    private var thread: Thread? = null
    private val threadId = AtomicInteger()
    private val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "settings-http-${threadId.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Binds (on the caller's thread) and starts the accept thread. Returns the port. */
    @Synchronized
    fun start(): Int {
        socket?.let { return port }
        var last: IOException? = null
        for (p in ports) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p), 16)
                socket = s
                port = s.localPort
                break
            } catch (e: IOException) {
                last = e
                s.close()
            }
        }
        val s = socket ?: throw last ?: IOException("No port")
        thread = Thread({ acceptLoop(s) }, "settings-http-accept").apply { isDaemon = true; start() }
        log("listening on :$port")
        return port
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        thread = null
        port = 0
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val c = try { server.accept() } catch (e: IOException) { break }
            if (!Lan.isLocal(c.inetAddress)) { closeQuietly(c); continue }
            try {
                pool.execute { serve(c) }
            } catch (e: RejectedExecutionException) {
                // Too many open connections: answer 503 without a thread.
                try { c.soTimeout = 1000; HttpResponse.error(503, "Busy").writeTo(c.getOutputStream(), false) } catch (_: IOException) {}
                closeQuietly(c)
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = IDLE_TIMEOUT_MS
            c.tcpNoDelay = true
            val input = BufferedInputStream(c.getInputStream(), 8192)
            val out = BufferedOutputStream(c.getOutputStream(), 16 * 1024)
            while (true) {
                val req = try {
                    HttpParser.parse(input, c.inetAddress) ?: break
                } catch (e: HttpException) {
                    HttpResponse.error(e.status, e.message ?: "Bad request").writeTo(out, false)
                    break
                }
                val res = try { handler(req) } catch (e: Exception) {
                    log("handler failed: ${e.javaClass.simpleName}")
                    HttpResponse.error(500, "Internal error")
                }
                val keep = req.keepAlive && res.stream == null
                res.writeTo(out, keep, head = req.method == "HEAD")
                res.stream?.let { s -> c.soTimeout = 0; s(out); return }
                if (!keep) break
            }
        } catch (e: SocketTimeoutException) {
            // idle keep-alive connection
        } catch (e: SocketException) {
            // client went away
        } catch (e: IOException) {
        } finally {
            closeQuietly(c)
        }
    }

    private fun closeQuietly(c: Socket) = try { c.close() } catch (_: IOException) {}

    companion object {
        const val MAX_CONNECTIONS = 12
        const val IDLE_TIMEOUT_MS = 20_000
    }
}

/** "Is this address on the home network?" Private, link-local, loopback and IPv6 ULA only. */
object Lan {
    fun isLocal(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
        return when (a) {
            // Anything else (public, 100.64/10 carrier-grade NAT, multicast) is not the home network.
            is Inet4Address -> false
            is Inet6Address -> {
                val b = a.address
                // fc00::/7 unique local; ::ffff:a.b.c.d mapped are unwrapped by the JVM already.
                (b[0].toInt() and 0xFE) == 0xFC
            }
            else -> false
        }
    }

    /**
     * Host header check against DNS rebinding: an IP literal, `localhost`, a single-label name or
     * a local suffix (.local, .lan, .home, .home.arpa, .internal). A public name pointing at the
     * Echo means a page on the internet is talking to it.
     */
    fun isLocalHost(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        var h = hostHeader.trim().lowercase()
        if (h.startsWith("[")) return h.substringBefore(']').length > 1 // IPv6 literal
        h = h.substringBefore(':').trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || '.' !in h) return true
        if (h.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")
}
