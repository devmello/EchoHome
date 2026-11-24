package app.vesper.feature.update

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The crash-loop safety net. `:app` calls [onAppStart] first thing in `Application.onCreate`
 * (after the watchdog, before any feature): it finishes the bookkeeping of an install the
 * updater committed, counts starts of a freshly updated version ([CrashLoop]) and, after
 * [CrashLoop.MAX_FAILED_STARTS] starts that didn't stay up, installs the kept previous APK
 * (a downgrade) and marks the bad version as skipped.
 *
 * Returns null for a normal start, or a message: then the app starts in safe mode (no features,
 * just that text) while the rollback installs, so the broken code doesn't run again.
 */
object UpdateGuard {
    private const val TAG = Installer.TAG
    /** A committed install that the new version never confirmed is given up after this. */
    private const val PENDING_TIMEOUT_MS = 10 * 60_000L

    /** True when this process is the first start after an install the user asked for ("Install now"). */
    @Volatile var justUpdatedManually: String? = null
        private set

    fun onAppStart(ctx: Context): String? = try {
        start(ctx)
    } catch (e: Exception) {
        // Never take the launcher down from here.
        Log.e(TAG, "update guard failed", e)
        null
    }

    private fun start(ctx: Context): String? {
        val store = UpdateStore.of(ctx.filesDir)
        val now = System.currentTimeMillis()
        val installed = installedVersion(ctx)
        val name = installedName(ctx)
        var s = store.get()

        s.pending?.let { p ->
            if (installed == p.to) {
                s = if (p.rollback) {
                    s.copy(pending = null, lastCheckResult = "Went back to $name; the version that crashed is skipped")
                        .withHistory("${stamp(now)}: went back to $name (${p.from} crashed on start)")
                } else {
                    if (p.manual) justUpdatedManually = name
                    s.ready?.let { File(it.path).delete() }
                    s.copy(pending = null, ready = null, guard = CrashLoop.Guard(version = installed, armedAt = now), lastCheckResult = "Updated to $name")
                        .withHistory("${stamp(now)}: updated to $name")
                }
                Log.i(TAG, "install of ${p.to} confirmed (rollback=${p.rollback})")
            } else if (p.rollback && installed == p.from) {
                // Restarted while going back (the bad version is still installed): try again, in safe mode.
                val prev = s.previous
                if (now - p.at > PENDING_TIMEOUT_MS || prev == null || !File(prev.path).exists()) {
                    s = s.copy(pending = null, rollbackFailed = installed).withHistory("${stamp(now)}: going back to ${p.toName} didn't finish")
                } else {
                    store.update { s }
                    rollback(ctx, prev, installed)
                    return "EchoHome $name didn't start properly.\nGoing back to ${prev.versionName}…"
                }
            } else if (now - p.at > PENDING_TIMEOUT_MS) {
                s = s.copy(pending = null).withHistory("${stamp(now)}: install of ${p.toName} didn't finish")
            }
        }
        if (s.rollbackFailed != 0L && s.rollbackFailed != installed) s = s.copy(rollbackFailed = 0)
        if (s.rollbackFailed == installed) {
            store.update { s }
            return "EchoHome $name keeps crashing and going back to the previous version failed.\n" +
                "From the Mac: ./scripts/build-install.sh --release, or adb shell pm uninstall-system-updates app.vesper"
        }

        val reason = if (s.guard.armed) lastExitReason(ctx) else null
        val (guard, decision) = CrashLoop.onStart(s.guard, installed, now, CrashLoop.couldBeCrash(reason))
        s = s.copy(guard = guard)
        when (decision) {
            CrashLoop.Decision.NONE -> { store.update { s }; return null }
            CrashLoop.Decision.WATCH -> {
                store.update { s }
                Log.i(TAG, "watching $installed: start with ${guard.failedStarts} failed before (last exit reason $reason)")
                Handler(Looper.getMainLooper()).postDelayed({
                    store.update { it.copy(guard = if (it.guard.version == installed) CrashLoop.markStable(it.guard) else it.guard) }
                    Log.i(TAG, "$installed stayed up ${CrashLoop.STABLE_MS / 1000} s")
                }, CrashLoop.STABLE_MS)
                return null
            }
            CrashLoop.Decision.ROLLBACK -> {}
        }

        val prev = s.previous
        val prevFile = prev?.let { File(it.path) }
        if (prev == null || prevFile == null || !prevFile.exists() || prev.versionCode == installed) {
            store.update { s.copy(guard = CrashLoop.Guard(), skipped = s.skipped + installed).withHistory("${stamp(now)}: $name crashed ${guard.failedStarts} times on start; no previous version kept to go back to") }
            Log.e(TAG, "$installed crash-loops and there is no previous APK")
            return null
        }
        Log.e(TAG, "$installed didn't stay up ${guard.failedStarts} times: rolling back to ${prev.versionCode}")
        store.update {
            s.copy(guard = CrashLoop.Guard(), skipped = s.skipped + installed,
                pending = UpdateState.Pending(from = installed, to = prev.versionCode, toName = prev.versionName, at = now, manual = false, rollback = true))
                .withHistory("${stamp(now)}: $name crashed ${guard.failedStarts} times on start; going back to ${prev.versionName}")
        }
        rollback(ctx, prev, installed)
        return "EchoHome $name didn't start properly.\nGoing back to ${prev.versionName}…"
    }

    /** Installs the kept previous APK over [installed] (a downgrade), off the main thread. */
    private fun rollback(ctx: Context, prev: UpdateState.Previous, installed: Long) {
        Thread({
            try {
                val f = File(prev.path)
                if (prev.sha256.isNotEmpty() && Download.sha256(f) != prev.sha256) throw IllegalStateException("the kept APK is damaged")
                Installer.install(ctx, f, downgrade = true)
            } catch (e: Exception) {
                Log.e(TAG, "rollback failed", e)
                UpdateReceiver.rollbackFailed(ctx, installed, e.message ?: e.javaClass.simpleName)
            }
        }, "update-rollback").start()
    }

    fun installedVersion(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Why this app's previous process ended (ApplicationExitInfo.REASON_*), or null if unknown. */
    private fun lastExitReason(ctx: Context): Int? = try {
        ctx.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(ctx.packageName, 0, 1)?.firstOrNull()?.reason
    } catch (e: Exception) { Log.w(TAG, "exit reasons: $e"); null }

    internal fun stamp(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
