package com.echobedside.feature.phone.ams

/** The iPhone's Now Playing as AMS last reported it. Pure values. */
data class AmsNowPlaying(
    /** The app's localized name, e.g. "Spotify", "Podcasts". Empty = nothing is loaded. */
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val state: Int = Ams.STATE_PAUSED,
    val rate: Float = 0f,
    val elapsedMs: Long = 0,
    /** Clock time (the session's `now`) when [elapsedMs] arrived. */
    val sampledAt: Long = 0,
    /** 0..1, the iPhone's volume. */
    val volume: Float? = null,
    /** Remote Command ids the player accepts right now. */
    val commands: Set<Int> = emptySet(),
) {
    val playing: Boolean get() = state == Ams.STATE_PLAYING || state == Ams.STATE_FAST_FORWARDING || state == Ams.STATE_REWINDING
    val empty: Boolean get() = title.isBlank() && artist.isBlank()
}

/**
 * Turns AMS notifications into [AmsNowPlaying] (pure, JVM-tested).
 *
 * A truncated value is shown as it came, and its full text is fetched through Entity Attribute
 * (write the ids, read the value) one at a time. A value that changes in the meantime wins over
 * the read. Titles never go to the log.
 */
class AmsSession(
    private val io: Io,
    private val onChange: (AmsNowPlaying) -> Unit,
    private val now: () -> Long,
) {
    interface Io {
        /** Write `[entity, attribute]` to Entity Attribute, then read it; answer via [onAttributeRead]. */
        fun readAttribute(entity: Int, attribute: Int)
        /** Write one Remote Command id. */
        fun command(id: Int)
    }

    var state = AmsNowPlaying()
        private set

    /** No truncated value is waiting for its full text (safe to look the track up). */
    val settled: Boolean get() = reading == null && pendingReads.isEmpty()

    private val pendingReads = ArrayDeque<Pair<Int, Int>>()
    private var reading: Pair<Int, Int>? = null
    /** Values that changed after a read was requested: a late read must not overwrite them. */
    private val changedSinceRead = HashSet<Pair<Int, Int>>()

    fun onEntityUpdate(bytes: ByteArray) {
        val u = Ams.parseUpdate(bytes) ?: return
        val key = u.entity to u.attribute
        if (reading == key) changedSinceRead += key
        pendingReads.remove(key)
        val next = apply(state, u.entity, u.attribute, u.value)
        if (u.truncated) { pendingReads.addLast(key); pump() }
        publish(next)
    }

    fun onCommands(bytes: ByteArray) {
        publish(state.copy(commands = Ams.parseCommands(bytes)))
    }

    /** The Entity Attribute read finished. [value] null = the read failed (keep the truncated text). */
    fun onAttributeRead(value: ByteArray?) {
        val key = reading ?: return
        reading = null
        val stale = changedSinceRead.remove(key)
        if (value != null && !stale) publish(apply(state, key.first, key.second, Ams.utf8(value, 0, value.size)))
        pump()
    }

    /** Sends [id] if the player lists it. Returns false otherwise. */
    fun command(id: Int): Boolean {
        if (id !in state.commands) return false
        io.command(id)
        return true
    }

    fun reset() {
        pendingReads.clear(); reading = null; changedSinceRead.clear()
        publish(AmsNowPlaying())
    }

    private fun pump() {
        if (reading != null) return
        val k = pendingReads.removeFirstOrNull() ?: return
        reading = k
        io.readAttribute(k.first, k.second)
    }

    private fun publish(next: AmsNowPlaying) {
        if (next == state) return
        state = next
        onChange(next)
    }

    private fun apply(s: AmsNowPlaying, entity: Int, attribute: Int, v: String): AmsNowPlaying = when (entity) {
        Ams.ENTITY_PLAYER -> when (attribute) {
            Ams.PLAYER_NAME -> s.copy(app = v.trim())
            Ams.PLAYER_PLAYBACK_INFO -> Ams.parsePlaybackInfo(v)?.let { p ->
                s.copy(state = p.state, rate = p.rate, elapsedMs = p.elapsedMs, sampledAt = now())
            } ?: s
            Ams.PLAYER_VOLUME -> s.copy(volume = v.trim().toFloatOrNull()?.coerceIn(0f, 1f))
            else -> s
        }
        Ams.ENTITY_TRACK -> when (attribute) {
            Ams.TRACK_ARTIST -> s.copy(artist = v.trim())
            Ams.TRACK_ALBUM -> s.copy(album = v.trim())
            Ams.TRACK_TITLE -> s.copy(title = v.trim())
            Ams.TRACK_DURATION -> s.copy(durationMs = Ams.parseSeconds(v))
            else -> s
        }
        else -> s
    }
}
