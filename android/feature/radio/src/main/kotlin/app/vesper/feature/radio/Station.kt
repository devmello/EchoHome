package app.vesper.feature.radio

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One Radio Browser station (the fields we use). [bitrate] is in kbps (0 = unknown). */
data class Station(
    val uuid: String,
    val name: String,
    /** The stream: Radio Browser's `url_resolved` (playlists and redirects decoded), else `url`. */
    val url: String,
    val homepage: String = "",
    val favicon: String = "",
    val tags: List<String> = emptyList(),
    val countryCode: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Boolean = false,
    val lastCheckOk: Boolean = true,
) {
    /** Sample stations ("demo:" urls) play silently: no network, no audio. */
    val isDemo get() = url.startsWith(DEMO_SCHEME)

    /** "MP3 · 128 kbps", "AAC", "HLS". */
    val format: String get() {
        val c = when {
            hls -> "HLS"
            codec.isBlank() || codec.equals("UNKNOWN", true) -> ""
            else -> codec.uppercase(Locale.ROOT)
        }
        return listOfNotNull(c.ifEmpty { null }, bitrate.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ")
    }

    /** "Jazz · Smooth jazz" (first two tags, capitalised). */
    val genre: String get() = tags.take(2).joinToString(" · ") { t -> t.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** The list row's second line: genre · country (or format when there's neither). */
    val subtitle: String get() = listOf(genre, country.ifBlank { countryCode }).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { format }

    fun toJson(): JSONObject = JSONObject()
        .put("stationuuid", uuid).put("name", name).put("url_resolved", url).put("homepage", homepage)
        .put("favicon", favicon).put("tags", tags.joinToString(",")).put("countrycode", countryCode).put("country", country)
        .put("codec", codec).put("bitrate", bitrate).put("hls", if (hls) 1 else 0).put("lastcheckok", if (lastCheckOk) 1 else 0)

    companion object {
        const val DEMO_SCHEME = "demo:"

        /** One station object from the API (or our own [toJson]); null if it has no name or stream. */
        fun fromJson(o: JSONObject): Station? {
            val uuid = o.optString("stationuuid").trim()
            val name = o.optString("name").trim().replace(Regex("\\s+"), " ")
            val url = o.optString("url_resolved").trim().ifEmpty { o.optString("url").trim() }
            if (uuid.isEmpty() || name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith(DEMO_SCHEME))) return null
            return Station(
                uuid = uuid,
                name = name,
                url = url,
                homepage = o.optString("homepage").trim(),
                favicon = o.optString("favicon").trim().takeIf { it.startsWith("http") }.orEmpty(),
                tags = o.optString("tags").split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && it.length <= 32 }.distinct(),
                countryCode = o.optString("countrycode").trim().uppercase(Locale.ROOT),
                country = o.optString("country").trim(),
                codec = o.optString("codec").trim(),
                bitrate = flexInt(o, "bitrate"),
                hls = flexInt(o, "hls") == 1 || o.optBoolean("hls", false),
                lastCheckOk = !o.has("lastcheckok") || flexInt(o, "lastcheckok") == 1 || o.optBoolean("lastcheckok", false),
            )
        }

        fun listFromJson(a: JSONArray): List<Station> = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }

        /** Numbers that some servers send as strings ("128") and newer ones as ints or booleans. */
        private fun flexInt(o: JSONObject, key: String): Int = when (val v = o.opt(key)) {
            is Number -> v.toInt()
            is Boolean -> if (v) 1 else 0
            is String -> v.trim().toIntOrNull() ?: 0
            else -> 0
        }

        /** Codecs the platform MediaPlayer plays from a stream (plus HLS). */
        private val PLAYABLE = setOf("MP3", "AAC", "AAC+", "OGG", "OPUS", "FLAC", "")

        fun playable(s: Station): Boolean = s.hls || s.codec.uppercase(Locale.ROOT).let { it in PLAYABLE || it == "UNKNOWN" }
    }
}
