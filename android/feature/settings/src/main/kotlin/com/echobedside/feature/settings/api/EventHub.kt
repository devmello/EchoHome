package com.echobedside.feature.settings.api

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Server-Sent Events to the open pages. Each client gets a bounded queue drained by its own
 * connection thread; nothing runs when no page is open. While a page is open, a comment line
 * every [HEARTBEAT_MS] finds dead connections (iOS drops them silently when Safari sleeps).
 */
class EventHub(private val heartbeatMs: Long = HEARTBEAT_MS) {
    private class Client(val tokenHash: String?) {
        val queue = LinkedBlockingQueue<String>(64)
        @Volatile var closed = false
    }

    private val clients = CopyOnWriteArrayList<Client>()

    val clientCount: Int get() = clients.size
    fun hasClients() = clients.isNotEmpty()

    fun publish(event: String, data: String) {
        if (clients.isEmpty()) return
        val frame = frame(event, data)
        for (c in clients) if (!c.queue.offer(frame)) { c.closed = true; c.queue.offer(CLOSE) }
    }

    /** Ends every stream opened with [tokenHash] (that browser was forgotten). */
    fun closeFor(tokenHash: String) {
        for (c in clients) if (c.tokenHash == tokenHash) { c.closed = true; c.queue.clear(); c.queue.offer(CLOSE) }
    }

    /** Blocks writing events to [out] until the client goes away. Runs on the connection's thread. */
    fun serve(out: OutputStream, tokenHash: String? = null) {
        val c = Client(tokenHash)
        if (clients.size >= MAX_CLIENTS) clients.firstOrNull()?.let { old -> old.closed = true; old.queue.offer(CLOSE); clients.remove(old) }
        clients += c
        try {
            out.write("retry: 3000\n: hello\n\n".toByteArray())
            out.flush()
            while (!c.closed) {
                val f = c.queue.poll(heartbeatMs, TimeUnit.MILLISECONDS) ?: ": ping\n\n"
                if (f === CLOSE) break
                out.write(f.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: IOException) {
        } catch (e: InterruptedException) {
        } finally {
            clients.remove(c)
        }
    }

    fun closeAll() { for (c in clients) { c.closed = true; c.queue.offer(CLOSE) } }

    companion object {
        const val HEARTBEAT_MS = 25_000L
        const val MAX_CLIENTS = 6
        private val CLOSE = String(charArrayOf('\u0000'))

        /** One SSE frame: multi-line data is split into `data:` lines; ends with a blank line. */
        fun frame(event: String, data: String): String {
            require('\n' !in event && '\r' !in event)
            val sb = StringBuilder(data.length + event.length + 16)
            sb.append("event: ").append(event).append('\n')
            for (line in data.split("\r\n", "\n", "\r")) sb.append("data: ").append(line).append('\n')
            return sb.append('\n').toString()
        }
    }
}
