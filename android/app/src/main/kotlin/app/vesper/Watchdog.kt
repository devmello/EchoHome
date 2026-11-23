package app.vesper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.CrashEntry
import app.vesper.core.model.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the launcher alive (NOTES §8.1, Nov 22 evening):
 *  - **Crash:** records it ([FileCrashLog]), kills the process without Android's "keeps stopping"
 *    dialog and brings MainActivity back with an alarm (~1.5 s; 2 min if it crashed 4 times in
 *    10 min, so a crash loop can't spin the CPU).
 *  - **Hang:** a background thread pings the main thread every [PING_MS]. Unanswered for
 *    [HANG_REPORT_MS] → a HANG entry with the main thread's stack; for [HANG_RESTART_MS] → restart.
 * The settings page shows the log (Diagnostics).
 */
internal class Watchdog(private val context: Context, private val log: FileCrashLog) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastPong = SystemClock.uptimeMillis()
    @Volatile private var pinged = 0L

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e -> onCrash(t, e) }
        Thread({ watchMain() }, "vesper-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun onCrash(t: Thread, e: Throwable) {
        try {
            Log.e(TAG, "crash on ${t.name}", e)
            log.add(CrashEntry(System.currentTimeMillis(), CrashEntry.Kind.CRASH, t.name, summary(e), stackOf(e)))
            scheduleRestart(if (log.recentCrashes(10 * 60_000L) >= 4) 120_000L else 1_500L)
        } catch (_: Throwable) {}
        Process.killProcess(Process.myPid())
        System.exit(10)
    }

    private fun watchMain() {
        var reported = false
        while (true) {
            try { Thread.sleep(PING_MS) } catch (_: InterruptedException) { return }
            val now = SystemClock.uptimeMillis()
            if (pinged == 0L) { pinged = now; main.post { lastPong = SystemClock.uptimeMillis(); pinged = 0L; reported = false }; continue }
            val stuck = now - pinged
            if (stuck >= HANG_REPORT_MS && !reported) {
                reported = true
                val st = Looper.getMainLooper().thread.stackTrace
                val stack = st.take(40).joinToString("\n") { "  at $it" }
                Log.w(TAG, "main thread stuck ${stuck / 1000}s\n$stack")
                log.add(CrashEntry(System.currentTimeMillis(), CrashEntry.Kind.HANG, "main",
                    "Main thread unresponsive for ${stuck / 1000} s" + (st.firstOrNull()?.let { " in ${it.className.substringAfterLast('.')}.${it.methodName}" } ?: ""), stack))
            }
            if (stuck >= HANG_RESTART_MS) {
                Log.e(TAG, "main thread stuck ${stuck / 1000}s: restarting")
                scheduleRestart(1_500L)
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        context.getSystemService(AlarmManager::class.java)
            ?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }

    companion object {
        const val TAG = "VesperWatchdog"
        const val PING_MS = 5_000L
        const val HANG_REPORT_MS = 10_000L
        const val HANG_RESTART_MS = 45_000L

        /** Exception class + a short message (messages can carry data, so they're cut). */
        fun summary(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.last()
            val msg = (root.message ?: "").take(160)
            return root.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
        }

        fun stackOf(e: Throwable): String = Log.getStackTraceString(e).take(6_000)
    }
}

/** The crash/hang log in `files/crashes.json` (last [MAX] entries), as the [CrashLog] contract. */
internal class FileCrashLog(private val file: File) : CrashLog {
    private val _entries = MutableStateFlow(load())
    override val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    @Synchronized fun add(e: CrashEntry) {
        val l = (_entries.value + e).takeLast(MAX)
        _entries.value = l
        save(l)
    }

    @Synchronized override fun clear() { _entries.value = emptyList(); file.delete() }

    fun recentCrashes(windowMs: Long): Int {
        val since = System.currentTimeMillis() - windowMs
        return _entries.value.count { it.kind == CrashEntry.Kind.CRASH && it.atMillis >= since }
    }

    private fun load(): List<CrashEntry> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                CrashEntry(o.getLong("at"), CrashEntry.Kind.valueOf(o.getString("kind")), o.optString("thread"), o.optString("summary"), o.optString("stack"))
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun save(l: List<CrashEntry>) {
        try {
            val a = JSONArray()
            for (e in l) a.put(JSONObject().put("at", e.atMillis).put("kind", e.kind.name).put("thread", e.thread).put("summary", e.summary).put("stack", e.stack))
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    companion object { const val MAX = 20 }
}
