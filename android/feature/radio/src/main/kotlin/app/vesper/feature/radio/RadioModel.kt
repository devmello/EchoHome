package app.vesper.feature.radio

import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.fake.FakeMediaSource
import java.util.Locale

/**
 * Only one source plays on the Echo. Audio focus does most of it (AirPlay, Spotify and other apps
 * take AUDIOFOCUS_GAIN, and so does the radio); this covers receivers that start without taking
 * focus (an AirPlay 2 sender before PCM flows, a phone resuming over AVRCP). Same rules as
 * Spotify's Arbitration. The iPhone's own player (AMS, OTHER_APP "iphone") isn't audio on the
 * Echo, so it's left alone. Pure.
 */
object RadioArbitration {
    private val RECEIVERS = setOf(MediaSourceKind.AIRPLAY, MediaSourceKind.BLUETOOTH, MediaSourceKind.SPOTIFY)

    fun othersPlaying(sessions: List<MediaSession>, ownId: String): Set<String> =
        sessions.filter { it.sourceId != ownId && it.sourceId != FakeMediaSource.ID && it.state.playing && it.state.source in RECEIVERS }
            .map { it.sourceId }.toSet()

    /** A receiver started since [before] while the radio is on: stop the radio. */
    fun shouldStopRadio(before: Set<String>, now: Set<String>, radioOn: Boolean): Boolean = radioOn && (now - before).isNotEmpty()

    /** The radio just started: the receivers to pause. */
    fun toPause(sessions: List<MediaSession>, ownId: String): List<MediaSession> {
        val ids = othersPlaying(sessions, ownId)
        return sessions.filter { it.sourceId in ids }
    }
}

/** The radio screen's curated genres: label, Radio Browser tag, accent colour. "Local" = by country. */
data class Genre(val id: String, val label: String, val tag: String?, val color: Int) {
    companion object {
        val ALL = listOf(
            Genre("jazz", "Jazz", "jazz", 0xFFFF9F0A.toInt()),
            Genre("lofi", "Lo-fi", "lofi", 0xFFBF5AF2.toInt()),
            Genre("classical", "Classical", "classical", 0xFFFFD60A.toInt()),
            Genre("ambient", "Ambient", "ambient", 0xFF64D2FF.toInt()),
            Genre("news", "News", "news", 0xFFFF453A.toInt()),
            Genre("indie", "Indie", "indie", 0xFF30D158.toInt()),
            Genre("electronic", "Electronic", "electronic", 0xFF0A84FF.toInt()),
            Genre("local", "Local", null, 0xFFFF375F.toInt()),
        )

        fun byId(id: String) = ALL.firstOrNull { it.id == id.lowercase(Locale.ROOT) }
    }
}

/** Voice / search query helpers. Pure. */
object RadioQuery {
    private val FAVOURITES = Regex("^(my )?(favou?rites?|favou?rite (station|radio)|saved( stations)?|(the |my )?radio|last (station|one)|what i (was )?(listening to|played))$")
    private val FILLER = Regex("\\b(radio|station|stations|channel|music|fm|please|some|the|a|an|play|internet)\\b")

    fun wantsFavourite(q: String) = FAVOURITES.matches(RadioLibrary.norm(q))

    /** "some jazz radio" → "jazz" (for the tag search; the name search keeps the whole query). */
    fun tagOf(q: String): String = RadioLibrary.norm(q).replace(FILLER, " ").replace(Regex("\\s+"), " ").trim()

    /** A country named in the query ("France", "german") → its ISO code, from the platform's country names. */
    fun countryCode(q: String, names: Map<String, String> = countryNames()): String? {
        val n = RadioLibrary.norm(q)
        if (n.isEmpty()) return null
        names[n]?.let { return it }
        return names.entries.firstOrNull { (name, _) -> name.length > 3 && (n == name || n.endsWith(" $name") || n.startsWith("$name ")) }?.value
    }

    fun countryNames(): Map<String, String> {
        val m = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val l = Locale("", code)
            m[RadioLibrary.norm(l.getDisplayCountry(Locale.ENGLISH))] = code
        }
        m["usa"] = "US"; m["us"] = "US"; m["america"] = "US"; m["uk"] = "GB"; m["britain"] = "GB"; m["england"] = "GB"
        return m
    }
}

/** Sample stations for demo mode / no network. Invented names; they play silently ("demo:" urls). */
object DemoStations {
    val ALL = listOf(
        Station("demo-1", "Harbor Lights FM", "demo:1", tags = listOf("ambient", "chillout"), countryCode = "US", country = "United States", codec = "AAC", bitrate = 128),
        Station("demo-2", "Night Swim Jazz", "demo:2", tags = listOf("jazz", "smooth jazz"), countryCode = "FR", country = "France", codec = "MP3", bitrate = 192),
        Station("demo-3", "Small Orbits Radio", "demo:3", tags = listOf("lofi", "electronic"), countryCode = "DE", country = "Germany", codec = "AAC", bitrate = 96),
        Station("demo-4", "Paper Satellites", "demo:4", tags = listOf("indie", "rock"), countryCode = "GB", country = "United Kingdom", codec = "MP3", bitrate = 128),
        Station("demo-5", "Low Tide Classical", "demo:5", tags = listOf("classical"), countryCode = "AT", country = "Austria", codec = "OGG", bitrate = 160),
        Station("demo-6", "Morning Wire", "demo:6", tags = listOf("news", "talk"), countryCode = "US", country = "United States", codec = "MP3", bitrate = 64),
        Station("demo-7", "Greenhouse Electronic", "demo:7", tags = listOf("electronic", "house"), countryCode = "NL", country = "Netherlands", codec = "AAC", bitrate = 128),
    )

    /** "Artist - Title" lines the demo stations "send" (sample data from the designs). */
    val SONGS = listOf(
        "Marisol Vey - Low Tide", "Ines Calder - Slow Bloom", "Harbor Lights - Slow Tide",
        "The Northern Lakes - Paper Satellites", "Ines Calder - Greenhouse", "Marisol Vey - Small Orbits",
    )

    fun forGenre(g: Genre, country: String): List<Station> =
        if (g.tag == null) ALL.filter { it.countryCode == country }.ifEmpty { ALL.take(3) } else ALL.filter { s -> s.tags.any { it.contains(g.tag) } }.ifEmpty { ALL.take(3) }

    fun search(q: String): List<Station> {
        val n = RadioLibrary.norm(q)
        return ALL.filter { s -> RadioLibrary.norm(s.name).contains(n) || s.tags.any { it.contains(n) } || RadioLibrary.norm(s.country).contains(n) }
    }
}
