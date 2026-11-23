package app.vesper.feature.lists

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderStore
import app.vesper.core.model.VesperServices
import app.vesper.feature.lists.engine.Codec
import app.vesper.feature.lists.engine.Evaluation
import app.vesper.feature.lists.engine.ReminderEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [ReminderStore] (services.reminders): [ReminderEngine] + persistence
 * (filesDir/reminders.json, AtomicFile) + one exact wake-up (`setExactAndAllowWhileIdle` at the
 * engine's next state change → [ReminderTriggerReceiver] → [evaluate]). [ReminderSystemReceiver]
 * re-evaluates after boot, clock and time-zone changes and app updates, and the feature evaluates
 * once at start, so a reminder that fell due while the Echo was off shows as missed.
 * Side effects of each evaluation (chime, island, banner) are [ReminderAlerts]. Main thread only.
 */
class RealReminderStore(private val ctx: Context, private val services: VesperServices) : ReminderStore {
    private val file = AtomicFile(File(ctx.filesDir, "reminders.json"))
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val alerts = ReminderAlerts(services, this)
    private var saved: List<Reminder>
    private val _reminders: MutableStateFlow<List<Reminder>>
    override val reminders: StateFlow<List<Reminder>>

    init {
        val loaded = load()
        saved = loaded
        _reminders = MutableStateFlow(ReminderEngine.sorted(loaded))
        reminders = _reminders.asStateFlow()
    }

    override fun add(text: String, dueAtMillis: Long, profileId: String?): Reminder {
        val r = ReminderEngine.newReminder(text, dueAtMillis, profileId, now())
        change(_reminders.value + r)
        return _reminders.value.firstOrNull { it.id == r.id } ?: r
    }

    override fun update(id: String, text: String?, dueAtMillis: Long?): Reminder? {
        if (_reminders.value.none { it.id == id }) return null
        change(ReminderEngine.update(_reminders.value, id, text, dueAtMillis))
        return _reminders.value.firstOrNull { it.id == id }
    }

    override fun complete(id: String) = change(ReminderEngine.complete(_reminders.value, id, now()))
    override fun reopen(id: String) = change(ReminderEngine.reopen(_reminders.value, id, now()))
    override fun snooze(id: String, minutes: Int) = change(ReminderEngine.snooze(_reminders.value, id, minutes, now()))
    override fun delete(id: String) = change(ReminderEngine.delete(_reminders.value, id))

    /** Open reminders right now (for the assistant's limit check). */
    fun openCount() = _reminders.value.count { it.open }

    private fun change(list: List<Reminder>) {
        _reminders.value = list
        evaluate("change")
    }

    /** Advances the state machine to now, applies the side effects, saves and reschedules. */
    fun evaluate(reason: String) {
        val before = _reminders.value
        val ev: Evaluation = ReminderEngine.evaluate(before, now())
        _reminders.value = ev.reminders
        if (ev.newlyDue.isNotEmpty()) Log.i(ListsFeature.TAG, "${ev.newlyDue.size} reminder(s) due ($reason)")
        alerts.apply(ev)
        if (ev.reminders != saved) save(ev.reminders)
        schedule(ev.nextWakeAt)
    }

    private fun schedule(at: Long?) {
        val pi = PendingIntent.getBroadcast(ctx, REQ_WAKE,
            Intent(ctx, ReminderTriggerReceiver::class.java).setAction(ReminderTriggerReceiver.ACTION_DUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) am.cancel(pi) else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun load(): List<Reminder> = try {
        if (file.baseFile.exists()) Codec.remindersFromJson(String(file.readFully())) else emptyList()
    } catch (e: Exception) {
        Log.e(ListsFeature.TAG, "reminders.json unreadable, starting empty", e)
        emptyList()
    }

    private fun save(list: List<Reminder>) {
        val out = file.startWrite()
        try {
            out.write(Codec.remindersToJson(list).toByteArray())
            file.finishWrite(out)
            saved = list
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(ListsFeature.TAG, "Saving reminders failed", e)
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val REQ_WAKE = 41
    }
}
