package com.echobedside.feature.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo as PmServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.DeviceName
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.MediaControls
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The AirPlay receiver as a foreground service (NOTES §6): runs the native receiver, advertises it
 * over mDNS, plays audio with [AudioPlayer], and publishes what's playing into `services.media`
 * plus an AIRPLAY_CONNECTED live activity into `services.island` while a sender is connected.
 *
 * Every native event is posted to one [HandlerThread] ("airplay-events"), which owns all state.
 * Idle (nobody streaming), the only threads are the library's two tokio workers parked in epoll.
 */
class AirPlayService : Service() {

    private lateinit var events: HandlerThread
    private lateinit var h: Handler
    private lateinit var services: EchoServices
    private lateinit var nsd: NsdAdvertiser
    private lateinit var names: SenderNameResolver
    private val dacp = Executors.newSingleThreadExecutor { r -> Thread(r, "airplay-dacp") }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Owned by the events thread.
    @Volatile private var handle = 0L
    /** Guards the native handle between DACP calls (their own thread) and nativeStop. */
    private val nativeLock = Any()
    private var info: ServiceInfo? = null
    private var libMdns = false
    @Volatile private var state = ReceiverState()
    private var artwork: Bitmap? = null
    private var player: AudioPlayer? = null
    private var publishedMedia = false
    private var publishedIsland: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var focusMuted = false
    private var pausedForFocus = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debugReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(null), PmServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val s = AirPlayFeature.services
        if (s == null) {
            Log.e(TAG, "AirPlayFeature not installed; stopping")
            stopSelf(); return
        }
        services = s
        events = HandlerThread("airplay-events").apply { start() }
        h = Handler(events.looper)
        nsd = NsdAdvertiser(this)
        names = SenderNameResolver(this, h)
        h.post { startReceiver() }
        mainScope.launch {
            services.settings.changes.collect { key ->
                // On / off (airplay.enabled, system.cc.airplay) is AirPlayFeature's job: it stops us.
                if (key in AirPlayFeature.RESTART_KEYS) h.post { restartReceiver("settings changed") }
            }
        }
        watchNetwork()
        if (services.config.debug) registerDebugReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        mainScope.cancel()
        if (::h.isInitialized) {
            h.post { stopReceiver() }
            events.quitSafely()
            events.join(3_000)
        }
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        debugReceiver?.let { unregisterReceiver(it) }
        dacp.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------
    // Receiver lifecycle (events thread)
    // ------------------------------------------------------------------------------------------

    private fun startReceiver() {
        if (handle != 0L) return
        if (!NativeBridge.load()) {
            Log.e(TAG, "libecho_airplay.so missing (armeabi-v7a only); AirPlay off")
            return
        }
        val settings = services.settings
        val name = settings.getString(AirPlayFeature.KEY_NAME, "").ifBlank { DeviceName.get(settings) }
        // AirPlay 1 by default (user decision): the iPhone accepts play/pause/skip over DACP; no multi-room needed.
        val ap2 = settings.getString(AirPlayFeature.KEY_MODE, AirPlayFeature.MODE_AP1) == AirPlayFeature.MODE_AP2
        libMdns = settings.getString(AirPlayFeature.KEY_MDNS, AirPlayFeature.MDNS_NSD) == AirPlayFeature.MDNS_LIB
        val started = SystemClock.elapsedRealtime()
        handle = try {
            NativeBridge.nativeStart(name, hwaddr(), PORT, ap2, libMdns, File(filesDir, "airplay").path, listener)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "receiver failed to start: ${e.message}")
            h.postDelayed({ startReceiver() }, RETRY_MS)
            return
        }
        val i = ServiceInfo.parse(NativeBridge.nativeServiceInfo(handle))
        info = i
        state = state.copy(ap2 = ap2)
        if (libMdns) setMulticastLock(true) else nsd.register(i)
        Log.i(TAG, "receiver up as \"$name\" (${if (ap2) "AirPlay 2" else "AirPlay 1"}, mDNS ${if (libMdns) "library" else "NsdManager"}) " +
            "port ${i.port} in ${SystemClock.elapsedRealtime() - started} ms")
    }

    private fun stopReceiver() {
        endSession()
        state = ReceiverState()
        publish()
        nsd.unregister()
        names.cancel()
        setMulticastLock(false)
        if (handle != 0L) {
            val hd = handle
            handle = 0L
            synchronized(nativeLock) { NativeBridge.nativeStop(hd) }
        }
        info = null
    }

    private fun restartReceiver(why: String) {
        Log.i(TAG, "$why: restarting receiver")
        stopReceiver()
        startReceiver()
    }

    /** Stable per-device MAC-style id: iPhones remember receivers by it. */
    private fun hwaddr(): ByteArray {
        val settings = services.settings
        val saved = settings.getString(AirPlayFeature.KEY_HWADDR, "")
        if (saved.length == 12) {
            runCatching { return ByteArray(6) { saved.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        }
        val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
        b[0] = ((b[0].toInt() or 0x02) and 0xFE).toByte() // locally administered, unicast
        settings.putString(AirPlayFeature.KEY_HWADDR, b.joinToString("") { "%02x".format(it) })
        return b
    }

    // ------------------------------------------------------------------------------------------
    // Native events → state
    // ------------------------------------------------------------------------------------------

    private val listener = object : NativeBridge.Listener {
        override fun onClientConnected(addr: String) {
            // Synchronously: the library browses for the sender's DACP service during SETUP.
            setMulticastLock(true)
            h.post { clientConnected(addr) }
        }
        override fun onClientDisconnected(addr: String) { h.post { clientDisconnected(addr) } }
        override fun onAudioStart(sampleRate: Int, channels: Int) { h.post { audioStart(sampleRate, channels) } }
        override fun onAudioStop() { h.post { audioStop() } }
        override fun onFlush() { h.post { player?.flush(); update { Reducer.flush(it, now()) } } }
        override fun onVolume(db: Float) {
            h.post {
                update { Reducer.volume(it, db) }
                player?.setGain(AirPlayMath.gain(db))
            }
        }
        override fun onMetadata(title: String?, artist: String?, album: String?, genre: String?, durationMs: Long) {
            h.post {
                Log.i(TAG, "metadata: \"$title\" / \"$artist\" / \"$album\" (${durationMs} ms)")
                update { Reducer.metadata(it, title, artist, album, durationMs, now()) }
                if (state.artworkAt == null) artwork = null
            }
        }
        override fun onCoverArt(data: ByteArray) { h.post { coverArt(data) } }
        override fun onProgress(start: Long, current: Long, end: Long) {
            h.post { update { Reducer.progress(it, start, current, end, now()) } }
        }
        override fun onRemoteControl() {
            h.post { Log.i(TAG, "DACP remote control available"); update { Reducer.remote(it, true) } }
        }
        override fun onError(message: String) { Log.w(TAG, "receiver error: $message") }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private inline fun update(transform: (ReceiverState) -> ReceiverState) {
        val next = transform(state)
        if (next != state) {
            state = next
            publish()
        }
    }

    private fun clientConnected(ip: String) {
        val firstFromIp = !state.connections.containsKey(ip)
        update { Reducer.clientConnected(it, ip) }
        Log.i(TAG, "client connected $ip (${state.connections.values.sum()} connections)")
        // A picker probing GET /info disconnects at once; a real sender stays.
        h.postDelayed({ if (state.connections.containsKey(ip)) update { Reducer.markConnected(it) } }, CONNECTED_AFTER_MS)
        if (firstFromIp) names.resolve(ip) { name -> update { Reducer.senderName(it, ip, name) } }
    }

    private fun clientDisconnected(ip: String) {
        update { Reducer.clientDisconnected(it, ip) }
        Log.i(TAG, "client disconnected $ip (${state.connections.values.sum()} left)")
        if (!state.anyConnection) {
            endSession()
            artwork = null
            names.cancel()
            if (handle != 0L) NativeBridge.nativeClearRemote(handle)
            if (!libMdns) setMulticastLock(false)
            publish()
        }
    }

    private fun audioStart(sampleRate: Int, channels: Int) {
        stopPlayer()
        if (handle == 0L) return
        Log.i(TAG, "audio stream ${sampleRate}Hz/${channels}ch")
        update { Reducer.audioStart(it, now(), sampleRate, channels) }
        player = AudioPlayer(handle, sampleRate, channels) { flowing -> h.post { onFlowing(flowing) } }.also {
            it.setGain(state.volumeDb?.let(AirPlayMath::gain) ?: 1f)
            it.muted = state.locallyPaused || focusMuted
            it.start()
        }
        acquireStreamLocks()
    }

    private fun audioStop() {
        Log.i(TAG, "audio stream ended")
        endSession()
        update { Reducer.audioStop(it, now()) }
    }

    /** Stops playback and releases everything a stream holds. */
    private fun endSession() {
        stopPlayer()
        releaseStreamLocks()
        abandonFocus()
        focusMuted = false
        pausedForFocus = false
    }

    private fun stopPlayer() {
        player?.let {
            it.stop()
            it.join(AudioPlayer.POLL_MS + 500L) // must finish before the native handle can go away
        }
        player = null
    }

    private fun onFlowing(flowing: Boolean) {
        if (flowing && !hasFocus) requestFocus()
        update { Reducer.flowing(it, flowing, now()) }
        if (state.connected.not()) update { Reducer.markConnected(it) }
    }

    private fun coverArt(data: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) {
            Log.w(TAG, "artwork: ${data.size} bytes didn't decode")
            return
        }
        Log.i(TAG, "artwork ${bounds.outWidth}x${bounds.outHeight} → ${bmp.width}x${bmp.height} (${data.size} bytes)")
        artwork = bmp
        state = Reducer.coverArt(state, now())
        publish()
    }

    // ------------------------------------------------------------------------------------------
    // Publishing into services.media / services.island
    // ------------------------------------------------------------------------------------------

    private fun publish() {
        val media = state.toMediaState(artwork)
        if (media != null) {
            services.media.publish(SOURCE_ID, media, controls)
            publishedMedia = true
            Log.i(TAG, "hub: \"${media.title}\" / \"${media.artist}\" / \"${media.album}\" art=${media.artwork?.let { "${it.width}x${it.height}" }} " +
                "pos=${media.positionMs}/${media.durationMs} playing=${media.playing} from=\"${media.sourceLabel}\" vol=${media.volume}")
        } else if (publishedMedia) {
            services.media.clear(SOURCE_ID)
            publishedMedia = false
            Log.i(TAG, "hub: cleared")
        }
        val label = if (state.connected) state.senderName ?: ReceiverState.DEFAULT_SENDER_LABEL else null
        if (label != publishedIsland) {
            if (label != null) {
                services.island.publish(LiveActivity(ISLAND_ID, ActivityKind.AIRPLAY_CONNECTED, LiveContent.AirPlayConnected(label)))
            } else {
                services.island.end(ISLAND_ID)
            }
            publishedIsland = label
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(label))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Remote control (services.media → sender)
    // ------------------------------------------------------------------------------------------

    private val controls = object : MediaControls {
        override fun play() { h.post { doPlay() } }
        override fun pause() { h.post { doPause() } }
        override fun next() { h.post { remote(NativeBridge.CMD_NEXT) } }
        override fun previous() { h.post { remote(NativeBridge.CMD_PREVIOUS) } }
        override fun seekTo(positionMs: Long) {} // DACP has no seek in the library; Now Playing hides it.
        override fun setVolume(volume: Float) { h.post { doSetVolume(volume) } }
        override val canSeek: Boolean get() = false
        // AirPlay 2 senders take no commands from us: skip does nothing, pause only mutes here.
        override val canSkip: Boolean get() = state.hasRemote
        override val canPause: Boolean get() = state.hasRemote
        /** The library can't drop one client, so restart the receiver (~10 ms): every sender is cut off. */
        override fun disconnect() { h.post { if (handle != 0L) restartReceiver("disconnect") } }
    }

    private fun doPlay() {
        when {
            state.locallyPaused -> { update { Reducer.locallyPaused(it, false, now()) }; player?.muted = focusMuted }
            state.hasRemote && !state.playing -> remote(NativeBridge.CMD_PLAY_PAUSE)
        }
    }

    private fun doPause() {
        when {
            state.hasRemote && state.playing -> remote(NativeBridge.CMD_PLAY_PAUSE)
            // AirPlay 2 has no remote control for third-party receivers: mute here instead.
            state.streaming && !state.locallyPaused -> { update { Reducer.locallyPaused(it, true, now()) }; player?.muted = true }
        }
    }

    private fun doSetVolume(fraction: Float) {
        val db = AirPlayMath.dbForFraction(fraction)
        update { Reducer.volume(it, db) }
        player?.setGain(AirPlayMath.gain(db))
        if (state.hasRemote) remote(NativeBridge.CMD_VOLUME, AirPlayMath.dacpVolume(fraction))
    }

    private fun remote(cmd: Int, arg: Int = 0) {
        val hd = handle
        if (hd == 0L || !state.hasRemote) return
        dacp.execute {
            synchronized(nativeLock) {
                if (handle == hd) {
                    val ok = NativeBridge.nativeRemote(hd, cmd, arg)
                    Log.i(TAG, "DACP cmd $cmd($arg) → ${if (ok) "ok" else "failed"}")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Audio focus, locks
    // ------------------------------------------------------------------------------------------

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false) // the system ducks us for short sounds
            .setOnAudioFocusChangeListener(::onFocusChange, h)
            .build()
            .also { focusRequest = it }
        hasFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) setFocusMuted(false)
        Log.i(TAG, "audio focus ${if (hasFocus) "granted" else "denied"}")
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        hasFocus = false
    }

    private fun onFocusChange(change: Int) {
        Log.i(TAG, "audio focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                setFocusMuted(false)
                if (pausedForFocus) remote(NativeBridge.CMD_PLAY_PAUSE)
                pausedForFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false
                setFocusMuted(true)
                if (state.hasRemote && state.playing && !pausedForFocus) {
                    remote(NativeBridge.CMD_PLAY_PAUSE)
                    pausedForFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks us.
        }
    }

    private fun setFocusMuted(muted: Boolean) {
        focusMuted = muted
        player?.muted = muted || state.locallyPaused
    }

    private fun acquireStreamLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EchoBedside:airplay").apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EchoBedside:airplay").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseStreamLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    @Synchronized
    private fun setMulticastLock(on: Boolean) {
        val held = multicastLock
        if (on && held == null) {
            multicastLock = applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("EchoBedside:airplay").apply { setReferenceCounted(false); acquire() }
        } else if (!on && held != null) {
            if (held.isHeld) held.release()
            multicastLock = null
        }
    }

    /** Re-announce after Wi-Fi comes back (the system daemon drops registrations with the network). */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lost = false
            override fun onLost(network: Network) { lost = true }
            override fun onAvailable(network: Network) {
                if (!lost) return
                lost = false
                h.post { info?.let { if (!libMdns) { Log.i(TAG, "network back: re-announcing"); nsd.register(it) } } }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        networkCallback = cb
    }

    // ------------------------------------------------------------------------------------------
    // Notification, debug hooks
    // ------------------------------------------------------------------------------------------

    private fun notification(sender: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AirPlay receiver", NotificationManager.IMPORTANCE_MIN))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPlay")
            .setContentText(if (sender != null) "Connected: $sender" else "Ready")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * Debug builds only: `adb shell am broadcast -a com.echobedside.airplay.DEBUG` with
     * `--es mode ap1|ap2`, `--es mdns nsd|lib`, `--es name "…"`, `--ez dump true`,
     * `--es cmd play|pause|next|previous` or `--ef volume 0.5`.
     */
    private fun registerDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val st = services.settings
                intent.getStringExtra("mode")?.let { st.putString(AirPlayFeature.KEY_MODE, it) }
                intent.getStringExtra("mdns")?.let { st.putString(AirPlayFeature.KEY_MDNS, it) }
                intent.getStringExtra("name")?.let { st.putString(AirPlayFeature.KEY_NAME, it) }
                when (intent.getStringExtra("cmd")) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.next()
                    "previous" -> controls.previous()
                }
                if (intent.hasExtra("volume")) controls.setVolume(intent.getFloatExtra("volume", 0.5f))
                if (intent.getBooleanExtra("dump", false)) h.post {
                    Log.i(TAG, "dump: handle=${handle != 0L} info=$info state=$state player=${player != null} focus=$hasFocus " +
                        "dropped=${if (handle != 0L) NativeBridge.nativeDroppedSamples(handle) else 0} hub=${services.media.current.value?.state}")
                }
            }
        }
        registerReceiver(r, IntentFilter(DEBUG_ACTION))
        debugReceiver = r
    }

    companion object {
        private const val TAG = "EchoAirPlay"
        const val SOURCE_ID = "airplay"
        const val ISLAND_ID = "airplay"
        private const val CHANNEL_ID = "airplay"
        private const val NOTIFICATION_ID = 0xA1A
        private const val PORT = 7000
        private const val RETRY_MS = 15_000L
        private const val CONNECTED_AFTER_MS = 3_000L
        /** Artwork is decoded to at most this size (Now Playing draws it well under 600 px). */
        private const val ARTWORK_MAX_PX = 600
        const val DEBUG_ACTION = "com.echobedside.airplay.DEBUG"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }
    }
}
