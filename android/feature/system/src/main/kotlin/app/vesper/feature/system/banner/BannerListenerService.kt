package app.vesper.feature.system.banner

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.vesper.core.model.Banner
import app.vesper.core.model.IslandAction
import app.vesper.feature.system.SystemFeature
import java.lang.ref.WeakReference

/**
 * Android notifications → `services.notifications` (NOTES §5.5). Enable once with
 * `adb shell cmd notification allow_listener app.vesper/app.vesper.feature.system.banner.BannerListenerService`.
 *
 * Skips our own app, ongoing/foreground-service notifications, group summaries, media-style ones
 * (`:feature:media` reads media sessions itself) and anything below default importance.
 * Calls and alarms are critical (shown in Do Not Disturb).
 */
class BannerListenerService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        instance = WeakReference(this)
        Log.i(TAG, "notification listener connected")
        try { activeNotifications?.forEach { onPosted(it, initial = true) } } catch (e: Exception) { Log.w(TAG, "initial list", e) }
    }

    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) = onPosted(sbn, initial = false)

    private fun onPosted(sbn: StatusBarNotification, initial: Boolean) {
        if (initial) return // only new ones become banners
        val banner = toBanner(sbn) ?: return
        main.post { SystemFeature.ui?.services?.notifications?.post(banner) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val id = ID_PREFIX + sbn.key
        main.post { SystemFeature.ui?.services?.notifications?.dismiss(id) }
    }

    private fun toBanner(sbn: StatusBarNotification): Banner? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return null
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT || ranking.isSuspended) return null
        }
        val ex = n.extras ?: return null
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return null
        val pm = packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: PackageManager.NameNotFoundException) { sbn.packageName }
        val glyph = when (n.category) {
            Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL -> "message"
            Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL -> "call"
            Notification.CATEGORY_ALARM -> "alarm"
            Notification.CATEGORY_EVENT, Notification.CATEGORY_REMINDER -> "calendar"
            else -> null
        }
        val critical = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM
        val color = if (n.color != Notification.COLOR_DEFAULT) n.color or 0xFF000000.toInt() else PALETTE[(sbn.packageName.hashCode() and 0x7fffffff) % PALETTE.size]
        // the one tinted action: a reply becomes "Reply by voice" (handed to the assistant), else the first action
        val actions = n.actions.orEmpty()
        val reply = actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val primary = when {
            reply != null -> IslandAction("reply", "Reply by voice", IslandAction.Style.PRIMARY) {
                SystemFeature.ui?.services?.navigator?.show("assistant", android.os.Bundle().apply { putString("reply_to", sbn.key); putString("reply_title", title) })
            }
            actions.isNotEmpty() -> actions[0].let { a ->
                IslandAction("action0", a.title?.toString() ?: "Open", IslandAction.Style.PRIMARY) { try { a.actionIntent?.send() } catch (e: Exception) { Log.w(TAG, "action", e) } }
            }
            n.contentIntent != null -> IslandAction("open", "Open", IslandAction.Style.PRIMARY) { try { n.contentIntent.send() } catch (e: Exception) { Log.w(TAG, "open", e) } }
            else -> null
        }
        return Banner(ID_PREFIX + sbn.key, app, color, glyph, title.ifEmpty { app }, body, sbn.postTime, critical, primary, sbn.packageName)
    }

    companion object {
        private const val TAG = "VesperSystem"
        const val ID_PREFIX = "n:"
        private var instance: WeakReference<BannerListenerService>? = null
        private val PALETTE = intArrayOf(0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF64D2FF.toInt())

        /** Dismissed in our UI → clear the Android notification too. */
        fun cancel(b: Banner) {
            if (!b.id.startsWith(ID_PREFIX)) return
            try { instance?.get()?.cancelNotification(b.id.removePrefix(ID_PREFIX)) } catch (e: Exception) { Log.w(TAG, "cancel", e) }
        }
    }
}
