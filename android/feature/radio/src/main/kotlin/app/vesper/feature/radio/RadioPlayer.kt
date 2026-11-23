package app.vesper.feature.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import okhttp3.OkHttpClient

/**
 * Plays one stream with the platform MediaPlayer (MP3, AAC / AAC+, Ogg, FLAC over HTTP(S), and
 * HLS). Shoutcast / Icecast streams go through [IcyProxy] for the song titles; if that fails
 * (an odd server), the same station is retried once straight from its URL. A stream that ends
 * by itself reconnects once. Main thread only. Holds a partial wake lock and a Wi-Fi lock only
 * while a stream is open.
 */
class RadioPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    private val listener: Listener,
) {
    enum class State { IDLE, CONNECTING, PLAYING, ERROR }

    interface Listener {
        fun onState(state: State, error: String?)
        /** ICY StreamTitle (null = the station sent an empty one). */
        fun onTitle(title: String?)
        fun onBitrate(kbps: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var proxy: IcyProxy? = null
    private var station: Station? = null
    private var direct = false
    private var reconnects = 0
    private var gain = 1f
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    var state = State.IDLE
        private set

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) fail("The station didn't answer")
    }

    fun play(s: Station) {
        release()
        station = s
        direct = s.hls || s.url.contains(".m3u8")
        reconnects = 0
        open()
    }

    fun stop() {
        release()
        station = null
        set(State.IDLE, null)
    }

    /** 0..1 on top of the music volume (ducking, transient focus loss). */
    fun setGain(g: Float) {
        gain = g.coerceIn(0f, 1f)
        try { mp?.setVolume(gain, gain) } catch (_: IllegalStateException) {}
    }

    private fun open() {
        val s = station ?: return
        set(State.CONNECTING, null)
        locks(true)
        val url = if (direct) s.url else try {
            IcyProxy(http, s.url, onTitle = { t -> main.post { if (station === s) listener.onTitle(t) } },
                onHeaders = { br, _ -> if (br != null && br > 0) main.post { if (station === s) listener.onBitrate(br) } })
                .also { proxy = it }.start()
        } catch (e: IOException) {
            Log.w(RadioBrowser.TAG, "proxy didn't start: ${e.message}")
            direct = true
            s.url
        }
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setOnPreparedListener { if (mp === it) { it.setVolume(gain, gain); it.start(); main.removeCallbacks(connectTimeout); set(State.PLAYING, null) } }
        p.setOnErrorListener { it, what, extra ->
            if (mp === it) {
                Log.w(RadioBrowser.TAG, "MediaPlayer error $what/$extra (${if (direct) "direct" else "proxy"}) for ${s.name}")
                if (!direct) { direct = true; reopen() } else fail("This station's stream didn't play")
            }
            true
        }
        p.setOnCompletionListener {
            if (mp === it) {
                if (reconnects++ < 1) { Log.i(RadioBrowser.TAG, "stream ended, reconnecting"); reopen() } else fail("The stream ended")
            }
        }
        p.setOnInfoListener { it, what, _ ->
            if (mp === it) when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> set(State.CONNECTING, null)
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> set(State.PLAYING, null)
            }
            false
        }
        try {
            if (direct) p.setDataSource(context, Uri.parse(url), mapOf("User-Agent" to RadioBrowser.USER_AGENT)) else p.setDataSource(url)
            p.prepareAsync()
            main.removeCallbacks(connectTimeout)
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(RadioBrowser.TAG, "setDataSource failed: ${e.message}")
            if (!direct) { direct = true; reopen() } else fail("This station's address doesn't work")
        }
    }

    private fun reopen() {
        val s = station ?: return
        val keepDirect = direct
        val keepReconnects = reconnects
        release()
        station = s
        direct = keepDirect
        reconnects = keepReconnects
        main.post { if (station === s) open() }
    }

    private fun fail(message: String) {
        release()
        set(State.ERROR, message)
    }

    private fun release() {
        main.removeCallbacks(connectTimeout)
        mp?.let { p ->
            mp = null
            try { p.stop() } catch (_: IllegalStateException) {}
            p.release()
        }
        proxy?.stop()
        proxy = null
        locks(false)
    }

    private fun set(s: State, error: String?) {
        if (s == state && error == null) return
        state = s
        listener.onState(s, error)
    }

    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun locks(on: Boolean) {
        try {
            if (on) {
                if (wake == null) wake = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vesper:radio").apply { setReferenceCounted(false); acquire(8 * 3_600_000L) }
                if (wifi == null) wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Vesper:radio").apply { setReferenceCounted(false); acquire() }
            } else {
                wake?.let { if (it.isHeld) it.release() }; wake = null
                wifi?.let { if (it.isHeld) it.release() }; wifi = null
            }
        } catch (e: SecurityException) {
            Log.w(RadioBrowser.TAG, "locks: ${e.message}")
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
