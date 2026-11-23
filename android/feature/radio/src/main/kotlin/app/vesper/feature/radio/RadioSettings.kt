package app.vesper.feature.radio

import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import java.util.Locale

/** The radio's settings (web settings page). Both are read at use time, so changes apply at once. */
object RadioSettings {
    /** Report each play to Radio Browser's click counter (its popularity ranking). */
    const val REPORT_PLAYS = "radio.reportPlays"
    /** Country for the "Local" genre (ISO code); empty = the system's region. */
    const val COUNTRY = "radio.country"

    val section = SettingsSection(
        id = "radio",
        title = "Radio",
        order = 32,
        help = "Internet radio from the Radio Browser directory (radio-browser.info, free and community run). Nothing plays until you pick a station or ask for one.",
        settings = listOf(
            SettingSpec(
                REPORT_PLAYS, "Count plays on Radio Browser", SettingType.BOOLEAN, "true",
                help = "When you play a station, tell Radio Browser (only the station's id) so popular stations rank higher for everyone, as its guidelines ask.",
            ),
            SettingSpec(
                COUNTRY, "Country for Local stations", SettingType.TEXT, "",
                help = "Two-letter country code such as US, GB or DE. Empty: the Echo's region.",
                placeholder = Locale.getDefault().country.ifEmpty { "US" },
            ),
        ),
    )

    fun reportPlays(s: SettingsStore) = s.getBoolean(REPORT_PLAYS, true)

    fun country(s: SettingsStore): String =
        s.getString(COUNTRY, "").trim().uppercase(Locale.ROOT).takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: Locale.getDefault().country.uppercase(Locale.ROOT).ifEmpty { "US" }
}
