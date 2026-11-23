package app.vesper.feature.spotify

/**
 * JNI surface of `libvesper_spotify.so` (Rust, `rust/src/lib.rs`), a wrapper around librespot 0.8.0
 * (Spotify Connect). Built for armeabi-v7a only; see `rust/build.sh`.
 *
 * A handle is a pointer to the native receiver: valid from [nativeStart] until [nativeStop].
 * Native code never logs credentials or tokens (lines go through a redactor, tag `VesperSpotify`).
 */
internal object NativeBridge {

    /** Callbacks from the native dispatcher thread. Implementations must only enqueue work. */
    interface Listener {
        /** [phase] is one of the PHASE_* values; [detail] is a short reason (may be empty). */
        fun onState(phase: Int, detail: String)
        /** The Spotify app controlling the Echo, e.g. "Maya's iPhone". */
        fun onClient(name: String)
        /** A new track (or episode: [artist] = the show). [coverUrl] is an https URL or empty. */
        fun onTrack(title: String, artist: String, album: String, durationMs: Long, coverUrl: String)
        /** [state] is PLAY_* ; [positionMs] is the position at this moment. */
        fun onPlayback(state: Int, positionMs: Long)
        /** Seek or position correction. */
        fun onPosition(positionMs: Long)
        /** Nothing loaded any more (another device took over, or the session ended). */
        fun onStopped()
        /** The Spotify app's volume, 0..65535. */
        fun onVolume(volume: Int)
        /** librespot opened (true) or closed (false, pause / stop) its audio output. */
        fun onSink(open: Boolean)
    }

    @Volatile private var loaded: Boolean? = null

    /** Loads the library once; false if it's missing (e.g. a non-ARM emulator or JVM tests). */
    fun load(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("vesper_spotify"); true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
            loaded = ok
            return ok
        }
    }

    /**
     * Starts the zeroconf server on [port] (0 = any) and the receiver. With [libMdns] the library
     * advertises itself; otherwise Kotlin registers it with NsdManager on [nativePort].
     * [credentialsDir] holds the cached login blob (noBackupFilesDir). Throws IllegalStateException.
     */
    external fun nativeStart(
        name: String,
        deviceId: String,
        port: Int,
        bitrateKbps: Int,
        libMdns: Boolean,
        credentialsDir: String,
        tmpDir: String,
        initialVolume: Int,
        listener: Listener,
    ): Long

    /** The zeroconf HTTP port. */
    external fun nativePort(handle: Long): Int

    /** Stops everything (up to ~6 s). Not on the main thread. */
    external fun nativeStop(handle: Long)

    /** Blocks up to [timeoutMs]. Returns s16 samples copied (44.1 kHz stereo), 0 on timeout, -1 when stopped. */
    external fun nativeReadPcm(handle: Long, buf: ShortArray, timeoutMs: Int): Int

    /** Releases a reader blocked in [nativeReadPcm] (it returns 0). */
    external fun nativeWakeReader(handle: Long)

    /** Queues a CMD_* (non-blocking). */
    external fun nativeCommand(handle: Long, cmd: Int, arg: Long): Boolean

    const val CMD_PLAY = 0
    const val CMD_PAUSE = 1
    const val CMD_NEXT = 2
    const val CMD_PREVIOUS = 3
    const val CMD_SEEK = 4
    const val CMD_VOLUME = 5
    const val CMD_DISCONNECT = 6
    const val CMD_RECONNECT = 7
    const val CMD_FORGET = 8

    const val PHASE_IDLE = 0
    const val PHASE_CONNECTING = 1
    const val PHASE_CONNECTED = 2
    const val PHASE_OFFLINE = 3
    const val PHASE_PREMIUM_REQUIRED = 4
    const val PHASE_LOGIN_FAILED = 5
    /** The native player died and the receiver stopped; the service restarts it. */
    const val PHASE_FAILED = 6

    const val PLAY_PAUSED = 0
    const val PLAY_PLAYING = 1
    const val PLAY_LOADING = 2

    /** librespot always outputs 44.1 kHz stereo. */
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2

    /** The DNS-SD service and TXT records librespot's discovery expects (zeroconf spec). */
    const val SERVICE_TYPE = "_spotify-connect._tcp"
    val TXT = listOf("VERSION" to "1.0", "CPath" to "/")
}
