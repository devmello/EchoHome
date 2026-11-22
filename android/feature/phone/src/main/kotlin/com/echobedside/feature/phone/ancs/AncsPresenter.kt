package com.echobedside.feature.phone.ancs

import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.Banner
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.IslandManager
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.NotificationFeed
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns ANCS notifications from one iPhone into Echo banners and the incoming-call island.
 *
 *  - Incoming call → an [ActivityKind.CALL] live activity ("Answer on iPhone", Decline only:
 *    calls are answered on the phone and no call audio goes through the Echo). Ends on Removed.
 *  - Everything else (missed calls included) → a [Banner] tagged with the phone's name and the
 *    iOS bundle id. Removed on the phone → dismissed here.
 *  - Dismissed on the Echo → the phone's NegativeAction when that clears it there ("Clear"), never
 *    for calls and never for a negative action we don't recognise (it could be "Delete").
 *  - Silent notifications (delivered quietly on the phone) don't banner unless [showSilent].
 *
 * Call [onFeedChanged] on every banner-list change so Echo-side dismissals are noticed.
 */
class AncsPresenter(
    private val feed: NotificationFeed,
    private val island: IslandManager,
    /** Stable per-phone key for ids (the Bluetooth address). */
    private val deviceKey: String,
    private val deviceName: () -> String,
    private val perform: (uid: Long, actionId: Int) -> Unit,
    var showSilent: Boolean = false,
    var clearOnDismiss: Boolean = true,
    /** Lower-case bundle ids or app names whose notifications don't banner (calls always show). */
    var hiddenApps: Set<String> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Banners we posted and believe are still in the feed, by uid. */
    private val posted = HashMap<Long, AncsNotification>()
    private val calls = HashSet<Long>()

    fun bannerId(uid: Long) = "ancs:$deviceKey:$uid"
    fun callId(uid: Long) = "ancs-call:$deviceKey:$uid"

    fun onNotification(n: AncsNotification, change: AncsSession.Change) {
        when (change) {
            AncsSession.Change.KNOWN -> {}
            AncsSession.Change.REMOVED -> remove(n.uid)
            AncsSession.Change.POSTED, AncsSession.Change.MODIFIED -> show(n)
        }
    }

    private fun show(n: AncsNotification) {
        if (n.category == AncsCategory.INCOMING_CALL) {
            calls += n.uid
            island.publish(callActivity(n))
            return
        }
        if (n.silent && !showSilent) return
        if (hidden(n)) return
        posted[n.uid] = n
        feed.post(banner(n))
    }

    private fun remove(uid: Long) {
        if (calls.remove(uid)) island.end(callId(uid))
        if (posted.remove(uid) != null) feed.dismiss(bannerId(uid))
    }

    /** The link to the phone dropped: end calls and take its banners down (they come back as PreExisting). */
    fun clearAll() {
        calls.toList().forEach { island.end(callId(it)) }
        calls.clear()
        val ids = posted.keys.toList()
        posted.clear()
        ids.forEach { feed.dismiss(bannerId(it)) }
    }

    /** Spots banners the user dismissed on the Echo (gone from the feed, not removed by us). */
    fun onFeedChanged() {
        if (posted.isEmpty()) return
        // Read the current list, not the emitted one: an old emission must not look like a dismissal.
        val present = feed.banners.value.mapTo(HashSet()) { it.id }
        val gone = posted.filterKeys { bannerId(it) !in present }
        for ((uid, n) in gone) {
            posted.remove(uid)
            if (clearOnDismiss && negativeClears(n)) perform(uid, Ancs.ACTION_NEGATIVE)
        }
    }

    fun banner(n: AncsNotification): Banner {
        val known = AppCatalog.lookup(n.appId)
        val fallback = AppCatalog.forCategory(n.category)
        val appName = n.appName?.takeIf { it.isNotBlank() } ?: known?.name
            ?: fallback.name.ifBlank { null } ?: AppCatalog.nameFromBundle(n.appId).ifBlank { "iPhone" }
        val body = listOf(n.subtitle, n.message).filter { it.isNotBlank() }.joinToString(" · ").ifBlank {
            when (n.category) {
                AncsCategory.MISSED_CALL -> "Missed call"
                AncsCategory.VOICEMAIL -> "New voicemail"
                else -> ""
            }
        }
        val primary = if (n.hasPositiveAction && n.positiveLabel.isNotBlank())
            IslandAction("ancs-positive", n.positiveLabel, IslandAction.Style.PRIMARY) { perform(n.uid, Ancs.ACTION_POSITIVE) }
        else null
        return Banner(
            id = bannerId(n.uid),
            appName = appName,
            appColor = known?.color ?: fallback.color,
            glyph = if (n.category == AncsCategory.MISSED_CALL || n.category == AncsCategory.VOICEMAIL) "call" else known?.glyph ?: fallback.glyph,
            title = n.title.ifBlank { appName },
            body = body,
            postedAtMillis = parseDate(n.date) ?: now(),
            critical = false,
            primaryAction = primary,
            sourcePackage = n.appId.ifBlank { null },
            sourceDevice = deviceName(),
        )
    }

    fun callActivity(n: AncsNotification): LiveActivity {
        val actions = if (n.hasNegativeAction)
            listOf(IslandAction("decline", n.negativeLabel.ifBlank { "Decline" }, IslandAction.Style.DESTRUCTIVE) { perform(n.uid, Ancs.ACTION_NEGATIVE) })
        else emptyList()
        return LiveActivity(
            id = callId(n.uid),
            kind = ActivityKind.CALL,
            content = LiveContent.Call(caller = n.title.ifBlank { n.subtitle.ifBlank { "Unknown caller" } }, subtitle = "Answer on iPhone", video = false),
            actions = actions,
        )
    }

    private fun hidden(n: AncsNotification): Boolean {
        if (hiddenApps.isEmpty()) return false
        val name = n.appName ?: AppCatalog.lookup(n.appId)?.name
        return n.appId.lowercase() in hiddenApps || (name != null && name.lowercase() in hiddenApps)
    }

    companion object {
        /** The "phone.hiddenApps" setting: comma-separated bundle ids or app names. */
        fun parseAppList(s: String): Set<String> = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        /** NegativeAction labels that mean "clear this notification" (iOS uses "Clear"), lower case. */
        private val CLEAR_LABELS = setOf("clear", "dismiss", "close", "löschen", "entfernen", "effacer", "borrar",
            "eliminar", "cancella", "rimuovi", "wissen", "rensa", "fjern", "tyhjennä", "limpar", "очистить", "清除", "クリア", "지우기")

        fun negativeClears(n: AncsNotification): Boolean {
            if (!n.hasNegativeAction) return false
            if (n.category == AncsCategory.INCOMING_CALL) return false
            val label = n.negativeLabel.trim().lowercase()
            // No label (older iOS): standard notifications' negative action is Clear.
            return label.isEmpty() || label in CLEAR_LABELS
        }

        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** ANCS dates are the phone's local time without a zone; the Echo is assumed to be in the same zone. */
        fun parseDate(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
            runCatching { LocalDateTime.parse(s.trim(), DATE).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }
}
