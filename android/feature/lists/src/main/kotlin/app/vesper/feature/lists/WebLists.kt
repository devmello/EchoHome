package app.vesper.feature.lists

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import app.vesper.core.model.ListNames
import app.vesper.core.model.VesperServices
import app.vesper.feature.lists.engine.WebSync
import java.time.ZoneId
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Two-way sync between the stores and the settings page's TEXT_LIST rows ([ListsSettings]):
 * every store change writes the canonical lines (the page updates live through its change
 * events); every page save is diffed ([WebSync.diff]) into deletes and adds. Writing the same
 * value again is a no-op, so the two directions don't loop. Main thread.
 */
internal class WebLists(private val ctx: Context, private val services: VesperServices) {
    private val zone get() = ZoneId.systemDefault()
    private val h24 get() = DateFormat.is24HourFormat(ctx)

    fun start() {
        val s = services
        s.scope.launch { s.reminders.reminders.collect { write(ListsSettings.WEB_REMINDERS, WebSync.join(reminderLines())) } }
        s.scope.launch { s.lists.lists.collect { write(ListsSettings.WEB_SHOPPING, WebSync.join(itemLines())) } }
        s.scope.launch {
            s.settings.changes.filter { it == ListsSettings.WEB_REMINDERS || it == ListsSettings.WEB_SHOPPING }.collect { key ->
                try { if (key == ListsSettings.WEB_REMINDERS) fromPageReminders() else fromPageShopping() }
                catch (e: Exception) { Log.w(ListsFeature.TAG, "settings page edit failed", e) }
            }
        }
    }

    private fun reminderLines() = WebSync.reminderLines(services.reminders.reminders.value, zone, h24)
    private fun itemLines() = WebSync.itemLines(services.lists.items(ListNames.SHOPPING))

    private fun write(key: String, value: String) {
        if (services.settings.getString(key, "") != value) services.settings.putString(key, value)
    }

    private fun fromPageReminders() {
        val sent = WebSync.split(services.settings.getString(ListsSettings.WEB_REMINDERS, ""))
        val (gone, added) = WebSync.diff(reminderLines(), sent)
        if (gone.isEmpty() && added.isEmpty()) return
        val store = services.reminders
        gone.forEach(store::delete)
        val now = System.currentTimeMillis()
        for (line in added) {
            val p = WebSync.parseNew(line, now, zone)
            if (p == null) Log.i(ListsFeature.TAG, "settings page: no future time in a new reminder, ignored")
            else store.add(p.first, p.second, null)
        }
        // Lines that didn't parse disappear: put back the canonical value.
        write(ListsSettings.WEB_REMINDERS, WebSync.join(reminderLines()))
    }

    private fun fromPageShopping() {
        val sent = WebSync.split(services.settings.getString(ListsSettings.WEB_SHOPPING, ""))
        val (gone, added) = WebSync.diff(itemLines(), sent)
        if (gone.isEmpty() && added.isEmpty()) return
        gone.forEach { services.lists.delete(ListNames.SHOPPING, it) }
        if (added.isNotEmpty()) services.lists.add(ListNames.SHOPPING, added)
        write(ListsSettings.WEB_SHOPPING, WebSync.join(itemLines()))
    }
}
