package app.vesper.feature.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * - `MY_PACKAGE_REPLACED`: the new version is installed (this process is already the new code).
 *   Bring the HOME activity back if the screen is on; with the screen off Android starts HOME
 *   when it wakes, and the screen isn't lit at 3 AM.
 * - [ACTION_TICK]: the updater's alarm (daily check, quiet-window retries).
 * - [ACTION_INSTALL_STATUS]: PackageInstaller's answer. For our own package a success usually
 *   never arrives (the process is replaced); a failure does.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "package replaced: now ${UpdateGuard.installedName(context)}")
                val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
                if (interactive) {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { try { context.startActivity(it) } catch (e: Exception) { Log.w(TAG, "relaunch: $e") } }
                }
            }
            ACTION_TICK -> UpdateFeature.manager?.onTick()
            ACTION_INSTALL_STATUS -> onStatus(context, intent)
        }
    }

    private fun onStatus(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val downgrade = intent.getBooleanExtra(EXTRA_DOWNGRADE, false)
        Log.i(TAG, "install status $status ${msg.take(200)} (downgrade=$downgrade)")
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val text = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Android asked for a confirmation: INSTALL_PACKAGES isn't granted (one-time setup from the Mac)"
            else -> "Install failed: " + msg.ifBlank { "status $status" }.take(160)
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Never leave a session waiting for a tap nobody makes.
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }?.let {
                try { ctx.packageManager.packageInstaller.abandonSession(it) } catch (_: Exception) {}
            }
        }
        val store = UpdateStore.of(ctx.filesDir)
        val p = store.get().pending
        if (p?.rollback == true || downgrade) {
            rollbackFailed(ctx, p?.from ?: UpdateGuard.installedVersion(ctx), text)
            return
        }
        store.update { it.copy(pending = null, lastCheckResult = text).withHistory("${UpdateGuard.stamp(System.currentTimeMillis())}: $text") }
        UpdateFeature.manager?.refresh()
    }

    companion object {
        const val TAG = Installer.TAG
        const val ACTION_TICK = "app.vesper.update.TICK"
        const val ACTION_INSTALL_STATUS = "app.vesper.update.INSTALL_STATUS"
        const val EXTRA_DOWNGRADE = "downgrade"

        /** The rollback didn't install: remember it and restart into safe mode (no crash loop). */
        fun rollbackFailed(ctx: Context, version: Long, reason: String) {
            UpdateStore.of(ctx.filesDir).update {
                it.copy(pending = null, rollbackFailed = version)
                    .withHistory("${UpdateGuard.stamp(System.currentTimeMillis())}: going back failed: ${reason.take(160)}")
            }
            Log.e(TAG, "rollback of $version failed: $reason; restarting in safe mode")
            restartSoon(ctx)
            Process.killProcess(Process.myPid())
        }

        fun restartSoon(ctx: Context) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
            val pi = PendingIntent.getActivity(ctx, 8, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
        }

        fun schedule(ctx: Context, atMillis: Long?) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (atMillis == null) { am.cancel(pi); return }
            am.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 5 * 60_000L, pi)
        }
    }
}
