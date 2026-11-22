package app.vesper.feature.photos.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The memory caption under the photo (Photos.dc.html):
 *   title    "Big Sur"                                  (24 px / 700)
 *   subtitle "October 2025 · Immich · Family album"     (15 px / 600, 82 %)
 *
 * The "when" part is "N years ago today" on the anniversary, "N years ago this week" within
 * three days of it, and the month + year otherwise. Without a place the "when" becomes the title.
 */
object Captions {
    data class Caption(val title: String, val subtitle: String)

    fun caption(
        asset: PhotoAsset,
        albumName: String,
        today: LocalDate,
        source: String = "Immich",
        offline: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): Caption {
        val whenText = asset.takenAt?.let { whenText(it, today, locale) }
        val src = if (offline) "Offline" else source
        val tail = listOf(src, albumLabel(albumName)).joinToString(" · ")
        val place = asset.place?.takeIf { it.isNotBlank() }
        return when {
            place != null -> Caption(place, listOfNotNull(whenText, tail).joinToString(" · "))
            whenText != null -> Caption(whenText, tail)
            else -> Caption(albumName, src)
        }
    }

    /** "Family" → "Family album"; names that already say album/photos are kept as they are. */
    fun albumLabel(name: String): String {
        val n = name.trim()
        val l = n.lowercase(Locale.ROOT)
        return if (l.endsWith("album") || l.endsWith("photos") || l.endsWith("pictures")) n else "$n album"
    }

    fun whenText(taken: LocalDateTime, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val d = taken.toLocalDate()
        val years = yearsAgoNear(d, today)
        return when {
            years != null && years.second == 0 -> "${years.first} ${plural(years.first)} ago today"
            years != null -> "${years.first} ${plural(years.first)} ago this week"
            else -> d.month.getDisplayName(TextStyle.FULL, locale) + " " + d.year
        }
    }

    /**
     * (N, dayOffset) when [taken] falls within ±3 days of today's date N ≥ 1 years earlier,
     * else null. Feb 29 counts as Feb 28 in other years.
     */
    fun yearsAgoNear(taken: LocalDate, today: LocalDate): Pair<Int, Int>? {
        val n = today.year - taken.year
        if (n < 1) return null
        val anniversary = runCatching { taken.withYear(today.year) }.getOrNull() ?: return null
        val off = ChronoUnit.DAYS.between(anniversary, today).toInt()
        if (abs(off) > 3) return null
        return n to off
    }

    private fun plural(n: Int) = if (n == 1) "year" else "years"
}
