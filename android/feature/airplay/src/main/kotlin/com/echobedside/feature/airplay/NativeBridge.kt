package com.echobedside.feature.airplay

/**
 * JNI surface of `libecho_airplay.so` (Rust, `rust/src/lib.rs`), a thin wrapper around the
 * `shairplay` crate. Built for armeabi-v7a only; see `rust/build.sh`.
 *
 * A handle is a pointer to the native receiver: valid from [nativeStart] until [nativeStop].
 */
internal object NativeBridge {

    /** Callbacks from the receiver's own threads. Implementations must only enqueue work. */
    interface Listener {
        fun onClientConnected(addr: String)
        fun onClientDisconnected(addr: String)
        /** A new audio stream: PCM (s16 interleaved) is now available from [nativeReadPcm]. */
        fun onAudioStart(sampleRate: Int, channels: Int)
        /** The audio stream ended (teardown); [nativeReadPcm] returns -1 once drained. */
        fun onAudioStop()
        /** The sender flushed (pause or seek): drop what's buffered. */
        fun onFlush()
        /** Sender volume in dB: 0 = max, -30 = min, -144 = mute. */
        fun onVolume(db: Float)
        fun onMetadata(title: String?, artist: String?, album: String?, genre: String?, durationMs: Long)
        /** JPEG or PNG. */
        fun onCoverArt(data: ByteArray)
        /** RTP timestamps (44.1 kHz, u32 in a Long). */
        fun onProgress(start: Long, current: Long, end: Long)
        /** The sender offered DACP remote control (AirPlay 1 only). */
        fun onRemoteControl()
        fun onError(message: String)
    }

    @Volatile private var loaded: Boolean? = null

    /** Loads the library once; false if it's missing (e.g. a non-ARM emulator or JVM tests). */
    fun load(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("echo_airplay"); true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
            loaded = ok
            return ok
        }
    }

    /**
     * Starts the RTSP listener. [libMdns] = true uses the library's own mDNS responder; false (the
     * default) leaves advertising to [NsdAdvertiser]. Throws IllegalStateException on failure.
     */
    external fun nativeStart(
        name: String,
        hwaddr: ByteArray,
        port: Int,
        ap2: Boolean,
        libMdns: Boolean,
        stateDir: String,
        listener: Listener,
    ): Long

    /** `key=value` lines, parsed by [ServiceInfo.parse]. */
    external fun nativeServiceInfo(handle: Long): String

    external fun nativeStop(handle: Long)

    /** Blocks up to [timeoutMs]. Returns samples copied, 0 on timeout, -1 when the stream ended. */
    external fun nativeReadPcm(handle: Long, buf: ShortArray, timeoutMs: Int): Int

    external fun nativeDroppedSamples(handle: Long): Long

    /** Blocking DACP request (up to ~2 s). Call off the main thread. */
    external fun nativeRemote(handle: Long, cmd: Int, arg: Int): Boolean

    external fun nativeClearRemote(handle: Long)

    const val CMD_PLAY_PAUSE = 0
    const val CMD_NEXT = 1
    const val CMD_PREVIOUS = 2
    const val CMD_STOP = 3
    const val CMD_VOLUME = 4
}
