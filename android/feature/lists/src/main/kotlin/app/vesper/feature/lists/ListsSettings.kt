package app.vesper.feature.lists

import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore

/**
 * The "Reminders & lists" card on the settings page. The two TEXT_LIST rows are live views of the
 * stores ([WebLists] keeps them in sync both ways): remove a line to delete it, add one to add it.
 */
internal object ListsSettings {
    const val CHIME = "reminders.chime"
    const val WEB_REMINDERS = "reminders.web"
    const val WEB_SHOPPING = "lists.web.shopping"

    val section = SettingsSection(
        id = "lists", title = "Reminders & lists", order = 45,
        help = "Also by voice: “remind me to take out the trash at 7 PM”, “add milk and eggs to my shopping list”.",
        settings = listOf(
            SettingSpec(CHIME, "Chime when a reminder is due", SettingType.BOOLEAN, "true",
                help = "Quieter in Night mode, silent in Do Not Disturb. The reminder still shows on the screen."),
            SettingSpec(WEB_REMINDERS, "Reminders", SettingType.TEXT_LIST, "",
                help = "Upcoming and missed reminders. Remove one to delete it. Add one with its time, e.g. " +
                    "“tomorrow 8am call the dentist” or “in 20 minutes check the oven”."),
            SettingSpec(WEB_SHOPPING, "Shopping list", SettingType.TEXT_LIST, "",
                help = "What's still to buy (items checked off on the Echo aren't shown). Duplicates are merged."),
        ),
    )

    fun chime(s: SettingsStore) = s.getBoolean(CHIME, true)
}
