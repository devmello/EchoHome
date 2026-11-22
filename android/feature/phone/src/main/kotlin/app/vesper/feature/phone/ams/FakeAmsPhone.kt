package app.vesper.feature.phone.ams

/**
 * A byte-exact AMS Media Source, for tests and the debug demo (sample data). It builds the
 * notifications an iPhone sends, cut to MTU − 3 with the Truncated flag the way iOS does, and
 * answers Entity Attribute reads with the full value.
 */
class FakeAmsPhone(var mtu: Int = 23) {
    val values = HashMap<Pair<Int, Int>, String>()
    var commands: Set<Int> = setOf(Ams.CMD_PLAY, Ams.CMD_PAUSE, Ams.CMD_TOGGLE_PLAY_PAUSE, Ams.CMD_NEXT_TRACK, Ams.CMD_PREVIOUS_TRACK, Ams.CMD_VOLUME_UP, Ams.CMD_VOLUME_DOWN)
    private var asked: Pair<Int, Int>? = null

    /** One Entity Update notification for [value] (stored, so a later read returns it whole). */
    fun update(entity: Int, attribute: Int, value: String): ByteArray {
        values[entity to attribute] = value
        val v = value.toByteArray(Charsets.UTF_8)
        val room = mtu - 3 - 3 // ATT notification header, then AMS's 3-byte header
        val cut = v.size > room
        val body = if (cut) v.copyOf(room) else v
        return byteArrayOf(entity.toByte(), attribute.toByte(), (if (cut) Ams.FLAG_TRUNCATED else 0).toByte()) + body
    }

    fun track(title: String, artist: String, album: String, durationS: Double): List<ByteArray> = listOf(
        update(Ams.ENTITY_TRACK, Ams.TRACK_ARTIST, artist),
        update(Ams.ENTITY_TRACK, Ams.TRACK_ALBUM, album),
        update(Ams.ENTITY_TRACK, Ams.TRACK_TITLE, title),
        update(Ams.ENTITY_TRACK, Ams.TRACK_DURATION, "%.3f".format(java.util.Locale.US, durationS)),
    )

    fun player(app: String, state: Int, elapsedS: Double, volume: Float = 0.5f): List<ByteArray> = listOf(
        update(Ams.ENTITY_PLAYER, Ams.PLAYER_NAME, app),
        playback(state, elapsedS),
        update(Ams.ENTITY_PLAYER, Ams.PLAYER_VOLUME, "%.2f".format(java.util.Locale.US, volume)),
    )

    fun playback(state: Int, elapsedS: Double, rate: Double = if (state == Ams.STATE_PLAYING) 1.0 else 0.0): ByteArray =
        update(Ams.ENTITY_PLAYER, Ams.PLAYER_PLAYBACK_INFO, "%d,%.1f,%.3f".format(java.util.Locale.US, state, rate, elapsedS))

    fun commandList(): ByteArray = commands.sorted().map { it.toByte() }.toByteArray()

    /** Entity Attribute: the write names the attribute, the read returns it whole. */
    fun writeAttribute(b: ByteArray) { asked = (b[0].toInt() and 0xFF) to (b[1].toInt() and 0xFF) }
    fun readAttribute(): ByteArray? = asked?.let { values[it] }?.toByteArray(Charsets.UTF_8)
}
