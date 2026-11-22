package com.echobedside.feature.media

/** Text formatting for Now Playing (pure, unit-tested). */
object Format {
    /** "1:23", "12:05", "1:02:03" for whole seconds. */
    fun clockTime(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${pad(m)}:${pad(sec)}" else "$m:${pad(sec)}"
    }

    /**
     * Elapsed and remaining labels for the scrubber, e.g. ("1:23", "-2:24"). Elapsed counts whole
     * seconds; remaining is the rest of the (rounded) duration, so the two always add up to it,
     * as in the design (`mmss(pos)`, `'-' + mmss(dur - pos)`). Unknown duration → remaining "".
     */
    fun progressLabels(positionMs: Long, durationMs: Long): Pair<String, String> {
        val pos = positionMs.coerceAtLeast(0) / 1000
        if (durationMs <= 0) return clockTime(pos) to ""
        val dur = (durationMs + 500) / 1000
        val p = pos.coerceAtMost(dur)
        return clockTime(p) to "-" + clockTime(dur - p)
    }

    /** 0..1 progress, 0 when the duration is unknown. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** The top-bar clock: "9:41" (12 h, no AM/PM, as in the design) or "21:41". */
    fun wallClock(hour24: Int, minute: Int, is24h: Boolean): String {
        val h = if (is24h) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        return "$h:${pad(minute.toLong())}"
    }

    /** "Artist — Album", or whichever is present. */
    fun subtitle(artist: String, album: String): String = when {
        artist.isNotBlank() && album.isNotBlank() -> "$artist — $album"
        artist.isNotBlank() -> artist
        else -> album
    }

    private fun pad(v: Long) = if (v < 10) "0$v" else "$v"
}
