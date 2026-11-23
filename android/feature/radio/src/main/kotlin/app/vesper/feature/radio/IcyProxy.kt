package app.vesper.feature.radio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A loopback HTTP relay between MediaPlayer and a Shoutcast / Icecast stream, so we can read the
 * song titles: MediaPlayer can't ask for ICY metadata or hand it back. MediaPlayer opens
 * `http://127.0.0.1:<port>/stream`; we open the real stream with OkHttp (`Icy-MetaData: 1`, the
 * app's User-Agent, HTTPS handled by OkHttp), strip the metadata blocks out with [IcyStripper]
 * and pass the audio through unchanged. One upstream at a time: a new MediaPlayer connection
 * closes the previous one. Bound to the loopback address only, so nothing else on the network
 * can use it. Not used for HLS (MediaPlayer plays those directly).
 *
 * [onTitle] / [onHeaders] are called on the relay thread.
 */
class IcyProxy(
    private val http: OkHttpClient,
    private val upstreamUrl: String,
    private val onTitle: (String?) -> Unit,
    private val onHeaders: (bitrateKbps: Int?, contentType: String?) -> Unit = { _, _ -> },
) {
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var active: Pair<Socket, Call>? = null

    /** Starts listening; returns the URL to give MediaPlayer. */
    fun start(): String {
        val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = s
        Thread({ acceptLoop(s) }, "VesperRadioProxy").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${s.localPort}/stream"
    }

    fun stop() {
        closed = true
        try { server?.close() } catch (_: IOException) {}
        closeActive()
    }

    private fun closeActive() {
        active?.let { (sock, call) -> call.cancel(); try { sock.close() } catch (_: IOException) {} }
        active = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val client = try { s.accept() } catch (e: IOException) { break }
            Thread({ relay(client) }, "VesperRadioRelay").apply { isDaemon = true }.start()
        }
    }

    private fun relay(client: Socket) {
        val call = http.newCall(Request.Builder().url(upstreamUrl)
            .header("User-Agent", RadioBrowser.USER_AGENT)
            .header("Icy-MetaData", "1")
            .header("Accept", "*/*")
            .build())
        synchronized(this) {
            if (closed) { try { client.close() } catch (_: IOException) {}; return }
            closeActive()
            active = client to call
        }
        try {
            client.tcpNoDelay = true
            readRequest(client.getInputStream())
            call.execute().use { res ->
                val out = BufferedOutputStream(client.getOutputStream(), 16 * 1024)
                if (!res.isSuccessful) {
                    out.write("HTTP/1.1 ${res.code} Upstream\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val type = res.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "audio/mpeg"
                onHeaders(res.header("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull(), type)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
                val metaInt = res.header("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                val stripper = metaInt?.let { IcyStripper(it) { block -> onTitle(IcyMeta.streamTitle(block)) } }
                val input = res.body!!.byteStream()
                val buf = ByteArray(8 * 1024)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (stripper != null) stripper.feed(buf, 0, n, out) else out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (e: SocketException) {
            // MediaPlayer closed its side (stop, reconnect): normal.
        } catch (e: IOException) {
            if (!closed) Log.i(RadioBrowser.TAG, "relay ended: ${e.message}")
        } finally {
            try { client.close() } catch (_: IOException) {}
            synchronized(this) { if (active?.first === client) active = null }
        }
    }

    /** Reads and ignores the request head (MediaPlayer's GET, maybe a Range). */
    private fun readRequest(input: InputStream) {
        var last = 0
        var matched = 0 // of \r\n\r\n
        var count = 0
        while (count < 8192) {
            val b = input.read()
            if (b < 0) return
            count++
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\n'.code && last == '\n'.code -> 4
                else -> 0
            }
            last = b
            if (matched == 4) return
        }
    }
}
