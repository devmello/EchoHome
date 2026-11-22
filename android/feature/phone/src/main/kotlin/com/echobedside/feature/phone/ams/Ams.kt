package com.echobedside.feature.phone.ams

import java.util.UUID

/**
 * Apple Media Service (AMS) wire format, from Apple's "Apple Media Service Specification" (v1.0).
 * Pure Kotlin, JVM-tested.
 *
 * The iPhone is the Media Source (GATT server); the Echo is the Media Remote (GATT client), on the
 * same encrypted LE link and bond as ANCS. AMS reports whatever the iPhone's Now Playing is, wherever
 * its audio goes (speaker, AirPods, the car, the Echo over A2DP or AirPlay).
 *
 *  - Remote Command (write / notify): notifications list the command ids available right now;
 *    writing one byte sends that command.
 *  - Entity Update (write with response / notify): write `[EntityID, AttributeID…]` to subscribe.
 *    Each notification is `[EntityID, AttributeID, EntityUpdateFlags, value…]`, value UTF-8 without
 *    a NUL, cut to fit the MTU when [FLAG_TRUNCATED] is set.
 *  - Entity Attribute (write / read): write `[EntityID, AttributeID]`, then read the full value.
 */
object Ams {
    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

    // EntityID
    const val ENTITY_PLAYER = 0
    const val ENTITY_QUEUE = 1
    const val ENTITY_TRACK = 2

    // PlayerAttributeID
    /** The app's localized name, e.g. "Spotify" (AMS doesn't send the bundle id). */
    const val PLAYER_NAME = 0
    /** "PlaybackState,PlaybackRate,ElapsedTime", e.g. "1,1.0,61.250". */
    const val PLAYER_PLAYBACK_INFO = 1
    /** "0.0"…"1.0". */
    const val PLAYER_VOLUME = 2

    // TrackAttributeID
    const val TRACK_ARTIST = 0
    const val TRACK_ALBUM = 1
    const val TRACK_TITLE = 2
    /** Seconds, e.g. "214.000". */
    const val TRACK_DURATION = 3

    // EntityUpdateFlags
    const val FLAG_TRUNCATED = 1

    // PlaybackState
    const val STATE_PAUSED = 0
    const val STATE_PLAYING = 1
    const val STATE_REWINDING = 2
    const val STATE_FAST_FORWARDING = 3

    // RemoteCommandID
    const val CMD_PLAY = 0
    const val CMD_PAUSE = 1
    const val CMD_TOGGLE_PLAY_PAUSE = 2
    const val CMD_NEXT_TRACK = 3
    const val CMD_PREVIOUS_TRACK = 4
    const val CMD_VOLUME_UP = 5
    const val CMD_VOLUME_DOWN = 6
    const val CMD_ADVANCE_REPEAT_MODE = 7
    const val CMD_ADVANCE_SHUFFLE_MODE = 8
    const val CMD_SKIP_FORWARD = 9
    const val CMD_SKIP_BACKWARD = 10

    // ATT errors
    const val ERROR_INVALID_STATE = 0xA0
    const val ERROR_INVALID_COMMAND = 0xA1
    const val ERROR_ABSENT_ATTRIBUTE = 0xA2

    /** The two Entity Update subscriptions the Echo writes, in order. */
    val SUBSCRIBE_TRACK = subscribe(ENTITY_TRACK, TRACK_ARTIST, TRACK_ALBUM, TRACK_TITLE, TRACK_DURATION)
    val SUBSCRIBE_PLAYER = subscribe(ENTITY_PLAYER, PLAYER_NAME, PLAYER_PLAYBACK_INFO, PLAYER_VOLUME)

    fun subscribe(entity: Int, vararg attributes: Int): ByteArray =
        ByteArray(1 + attributes.size).also { b -> b[0] = entity.toByte(); attributes.forEachIndexed { i, a -> b[i + 1] = a.toByte() } }

    fun attributeRequest(entity: Int, attribute: Int) = byteArrayOf(entity.toByte(), attribute.toByte())

    fun command(id: Int) = byteArrayOf(id.toByte())

    data class EntityUpdate(val entity: Int, val attribute: Int, val flags: Int, val value: String) {
        val truncated: Boolean get() = flags and FLAG_TRUNCATED != 0
    }

    /** One Entity Update notification, or null if it's shorter than its 3-byte header. */
    fun parseUpdate(b: ByteArray): EntityUpdate? {
        if (b.size < 3) return null
        return EntityUpdate(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF, utf8(b, 3, b.size - 3, cut = b[2].toInt() and FLAG_TRUNCATED != 0))
    }

    /** A Remote Command notification: the ids the current player accepts. */
    fun parseCommands(b: ByteArray): Set<Int> = b.mapTo(HashSet()) { it.toInt() and 0xFF }

    data class PlaybackInfo(val state: Int, val rate: Float, val elapsedMs: Long)

    /** "1,1.0,61.250" → PLAYING at 1×, 61.25 s in. Empty fields (no player) parse as paused at 0. */
    fun parsePlaybackInfo(s: String): PlaybackInfo? {
        val p = s.split(',')
        if (p.size < 3) return null
        val state = p[0].trim().toIntOrNull() ?: return null
        val rate = p[1].trim().toFloatOrNull() ?: 0f
        val elapsed = p[2].trim().toDoubleOrNull() ?: 0.0
        return PlaybackInfo(state, rate, (elapsed * 1000).toLong().coerceAtLeast(0))
    }

    /** "214.000" → 214000. Blank or junk → 0. */
    fun parseSeconds(s: String): Long = ((s.trim().toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    /**
     * UTF-8 decoding that drops a multi-byte sequence the iPhone cut in half at the MTU
     * ([cut]), instead of showing a replacement character.
     */
    fun utf8(b: ByteArray, off: Int, len: Int, cut: Boolean = false): String {
        var end = off + len
        if (cut && end > off) {
            // Walk back over continuation bytes to the last lead byte; drop it if its sequence is incomplete.
            var i = end - 1
            while (i > off && (b[i].toInt() and 0xC0) == 0x80) i--
            val lead = b[i].toInt() and 0xFF
            val need = when { lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; lead >= 0xC0 -> 2; else -> 1 }
            if (end - i < need) end = i
        }
        return String(b, off, end - off, Charsets.UTF_8)
    }
}
