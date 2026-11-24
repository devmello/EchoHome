package app.vesper.feature.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.Presence
import app.vesper.core.model.VesperServices
import app.vesper.core.model.VesperTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Checks GitHub Releases, downloads and verifies the APK, and installs it at a quiet time or on
 * request. All network and file work runs on one low-priority thread; decisions about the room
 * (alarms, media, presence) are read on the main thread. State: [UpdateStore].
 */
internal class UpdateManager(private val ctx: Context, private val services: VesperServices) {
    private val store = UpdateStore.of(ctx.filesDir)
    private val dir = store.dir
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "updater")
    }
    @Volatile private var busy: String? = null
    private val installed = UpdateGuard.installedVersion(ctx)
    private val installedName = UpdateGuard.installedName(ctx)

    data class Status(val state: UpdateState, val busy: String?, val installed: Long, val installedName: String)
    private val _status = MutableStateFlow(Status(store.get(), null, installed, installedName))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val settings get() = services.settings
    /** One client for the process (its connection pool and threads idle out). */
    private val github by lazy { GitHubFetcher() }
    val checksEnabled get() = settings.getBoolean(UpdateFeature.KEY_ENABLED, true)
    val autoInstall get() = settings.getBoolean(UpdateFeature.KEY_AUTO, true)

    init { store.onChange = { refresh() } }

    fun refresh() { _status.value = Status(store.get(), busy, installed, installedName) }

    private fun setBusy(text: String?) { busy = text; refresh() }

    // --- scheduling ---

    /** Re-arms the alarm for the next check or quiet-window retry. */
    fun reschedule() {
        val s = store.get()
        val jitter = settings.getInt(KEY_JITTER, -1).let { if (it in 0..29) it else Random.nextInt(30).also { j -> settings.putInt(KEY_JITTER, j) } }
        val waiting = autoInstall && readyToInstall(s, manual = false) != null
        val at = Schedule.nextWake(ZonedDateTime.now(), s.lastCheckAt, jitter, checksEnabled, waiting)
        UpdateReceiver.schedule(ctx, at)
        Log.i(TAG, "next wake ${at?.let { UpdateGuard.stamp(it) } ?: "none"} (waiting=$waiting)")
    }

    /** The alarm fired (main thread). */
    fun onTick() {
        val s = store.get()
        val now = System.currentTimeMillis()
        if (checksEnabled && now - s.lastCheckAt > 20 * 3_600_000L) check(manual = false)
        else { tryAutoInstall(); reschedule() }
    }

    // --- check / download ---

    fun check(manual: Boolean, thenInstall: Boolean = false) {
        if (busy != null) return
        setBusy("Checking…")
        worker.execute {
            try {
                val release = fetchLatest()
                val s = store.get()
                Log.i(TAG, "check (manual=$manual): ${s.lastCheckResult}")
                if (release != null && VersionPolicy.isNewer(release, installed)) {
                    val wantDownload = thenInstall || (autoInstall && VersionPolicy.canAutoInstall(release, installed, s.skipped) && release.sha256 !in s.refused)
                    if (wantDownload) download(release)
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed: $e")
                store.update { it.copy(lastCheckAt = System.currentTimeMillis(), lastCheckResult = "Check failed: ${short(e)}") }
            } finally {
                setBusy(null)
                main.post {
                    if (thenInstall) installNow(fromCheck = true) else { tryAutoInstall(); reschedule() }
                }
            }
        }
    }

    /** GET the feed (conditional); returns the latest release or null if none. */
    private fun fetchLatest(): Release? {
        val override = DebugFeed.overrideUrl(settings)
        val url = override ?: FEED_URL
        val fetcher = if (override != null) DebugFeed.fetcher(override, github) else github
        val s = store.get()
        val headers = mutableMapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        if (s.etag.isNotEmpty() && s.latest != null) headers["If-None-Match"] = s.etag
        val now = System.currentTimeMillis()
        fetcher.get(url, headers).use { r ->
            when (r.code) {
                304 -> { record(now, s.latest, s.etag); return s.latest }
                200 -> {
                    val body = r.body?.bufferedReader()?.use { it.readText() } ?: throw Download.Failed("Empty answer from GitHub")
                    val release = try { ReleaseParser.parse(body) } catch (e: ReleaseParseException) {
                        store.update { it.copy(lastCheckAt = now, lastCheckResult = e.message ?: "Bad release", latest = null, etag = "") }
                        return null
                    }
                    record(now, release, r.header("ETag") ?: "")
                    return release
                }
                404 -> { store.update { it.copy(lastCheckAt = now, lastCheckResult = "No release published yet", latest = null, etag = "") }; return null }
                403, 429 -> throw Download.Failed("GitHub's rate limit; trying again later")
                else -> throw Download.Failed("GitHub answered HTTP ${r.code}")
            }
        }
    }

    private fun record(now: Long, release: Release?, etag: String) {
        store.update {
            val text = when {
                release == null -> "No release published yet"
                !VersionPolicy.isNewer(release, installed) -> "Up to date"
                release.versionCode in it.skipped -> "${release.versionName} is available (skipped after a rollback; Install now to try it)"
                it.ready?.versionCode == release.versionCode -> "${release.versionName} is downloaded and ready"
                else -> "${release.versionName} is available"
            }
            it.copy(lastCheckAt = now, lastCheckResult = text, latest = release, etag = etag)
        }
    }

    /** Downloads (resuming a partial file), verifies and records [release] as ready. Worker thread. */
    private fun download(release: Release) {
        val s = store.get()
        s.ready?.let { if (it.versionCode == release.versionCode && it.sha256 == release.sha256 && File(it.path).exists()) return }
        val free = ctx.filesDir.usableSpace
        val need = release.size * 3 + 64L * 1024 * 1024
        if (release.size > 0 && free < need) {
            store.update { it.copy(lastCheckResult = "Not enough space for ${release.versionName}: ${need / 1_048_576} MB needed, ${free / 1_048_576} MB free") }
            return
        }
        dir.mkdirs()
        // Leftovers of other versions.
        dir.listFiles()?.filter { it.name.endsWith(".part") && it.name != partName(release) }?.forEach { it.delete() }
        val part = File(dir, partName(release))
        val override = DebugFeed.overrideUrl(settings)
        val fetcher = if (override != null) DebugFeed.fetcher(override, github) else github
        setBusy("Downloading ${release.versionName}…")
        var shown = -1L
        Download.fetch(fetcher, release.apkUrl, release.size, part) { done ->
            // Every 10 %: each change re-renders the open settings pages.
            val pct = if (release.size > 0) done * 100 / release.size / 10 * 10 else -1
            if (pct != shown) { shown = pct; setBusy("Downloading ${release.versionName}: $pct %") }
        }
        setBusy("Verifying ${release.versionName}…")
        val sha = Download.sha256(part)
        val problem = if (sha != release.sha256) "checksum doesn't match" else ApkCheck.problem(ctx, part, release.versionCode)
        if (problem != null) {
            part.delete()
            Log.w(TAG, "refused ${release.versionName}: $problem")
            store.update { it.copy(refused = it.refused + release.sha256, lastCheckResult = "Refused ${release.versionName}: $problem")
                .withHistory("${UpdateGuard.stamp(System.currentTimeMillis())}: refused ${release.versionName}: $problem") }
            return
        }
        val ready = File(dir, "ready.apk")
        ready.delete()
        if (!part.renameTo(ready)) throw Download.Failed("Couldn't store the download")
        store.update { it.copy(ready = UpdateState.Ready(release.versionCode, release.versionName, sha, ready.path),
            lastCheckResult = "${release.versionName} is downloaded and ready") }
        Log.i(TAG, "${release.versionName} (${release.versionCode}) downloaded and verified")
    }

    private fun partName(r: Release) = "download-${r.versionCode}-${r.sha256.take(12)}.apk.part"

    // --- install ---

    /** The verified download that may be installed now, or null. */
    private fun readyToInstall(s: UpdateState, manual: Boolean): UpdateState.Ready? {
        val r = s.ready ?: return null
        if (r.versionCode <= installed || !File(r.path).exists()) return null
        if (!manual && r.versionCode in s.skipped) return null
        // The release was withdrawn or replaced since the download: don't install the old file.
        val latest = s.latest
        if (latest != null && (latest.versionCode != r.versionCode || latest.sha256 != r.sha256)) return null
        return r
    }

    /** Main thread: installs a ready update if auto-install is on and the room is quiet. */
    fun tryAutoInstall() {
        if (!autoInstall || busy != null) return
        val r = readyToInstall(store.get(), manual = false) ?: return
        when (val d = QuietWindow.decide(quietInputs())) {
            QuietDecision.Install -> install(r, manual = false)
            is QuietDecision.Wait -> Log.i(TAG, "${r.versionName} waits: ${d.reason}")
        }
    }

    /** "Install now" (main thread). Returns the message for the settings page. */
    fun installNow(fromCheck: Boolean = false): String {
        if (busy != null) return busy!!
        val s = store.get()
        val r = readyToInstall(s, manual = true)
        if (r == null) {
            val latest = s.latest
            if (!fromCheck) { check(manual = true, thenInstall = true); return "Checking for an update…" }
            return when {
                latest == null || !VersionPolicy.canManualInstall(latest, installed) -> "Already up to date ($installedName)"
                else -> s.lastCheckResult
            }
        }
        val island = services.island.activities.value
        val d = QuietWindow.decideManual(
            alarmActive = services.alarms.phase.value.let { it is AlarmPhase.Ringing || it is AlarmPhase.Sunrise },
            callOrDoorbell = island.any { it.kind == ActivityKind.CALL || it.kind == ActivityKind.DOORBELL },
        )
        if (d is QuietDecision.Wait) { store.update { it.copy(lastCheckResult = d.reason) }; refresh(); return d.reason }
        install(r, manual = true)
        return "Installing ${r.versionName}; EchoHome restarts in about a minute"
    }

    private fun install(r: UpdateState.Ready, manual: Boolean) {
        if (!Installer.hasPermission(ctx)) {
            store.update { it.copy(lastCheckResult = "Can't install: INSTALL_PACKAGES isn't granted (one-time setup from the Mac, see docs/status/update.md)") }
            refresh(); return
        }
        setBusy("Installing ${r.versionName}…")
        worker.execute {
            try {
                val f = File(r.path)
                if (Download.sha256(f) != r.sha256) throw Download.Failed("the downloaded file changed")
                ApkCheck.problem(ctx, f, r.versionCode)?.let { throw Download.Failed(it) }
                // The rollback target: a copy of what is installed now.
                val prev = File(dir, "previous.apk")
                Installer.copyInstalled(ctx, prev)
                val prevSha = Download.sha256(prev)
                store.update {
                    it.copy(previous = UpdateState.Previous(installed, installedName, prevSha, prev.path),
                        pending = UpdateState.Pending(installed, r.versionCode, r.versionName, System.currentTimeMillis(), manual))
                }
                Log.i(TAG, "installing ${r.versionName} (${r.versionCode}) over $installedName, manual=$manual")
                Installer.install(ctx, f, downgrade = false)
                // The system replaces this process when the install completes.
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                store.update { it.copy(pending = null, lastCheckResult = "Install failed: ${short(e)}")
                    .withHistory("${UpdateGuard.stamp(System.currentTimeMillis())}: install of ${r.versionName} failed: ${short(e)}") }
                setBusy(null)
            }
        }
    }

    private fun quietInputs(): QuietInputs {
        val island = services.island.activities.value
        return QuietInputs(
            now = ZonedDateTime.now(),
            nextAlarmAt = services.alarms.next.value?.triggerAtMillis,
            alarmActive = services.alarms.phase.value !is AlarmPhase.Idle,
            islandBusy = island.any { it.kind in BUSY_KINDS },
            mediaPlaying = services.media.sessions.value.any { it.state.playing },
            timerRunning = services.timers.timers.value.any { it.state != VesperTimer.State.PAUSED },
            someonePresent = services.system.state.value.presence == Presence.PRESENT,
        )
    }

    /** Debug: a different feed. Forgets what the old feed said. */
    fun setFeedOverride(url: String?) {
        DebugFeed.set(settings, url)
        store.update { it.copy(etag = "", latest = null, lastCheckResult = "Feed: ${DebugFeed.overrideUrl(settings) ?: "GitHub"}") }
        refresh()
    }

    private fun short(e: Exception) = (e.message ?: e.javaClass.simpleName).take(160)

    companion object {
        const val TAG = Installer.TAG
        const val FEED_URL = "https://api.github.com/repos/devmello/EchoHome/releases/latest"
        const val KEY_JITTER = "update.checkJitterMin"
        val BUSY_KINDS = setOf(ActivityKind.CALL, ActivityKind.DOORBELL, ActivityKind.ALARM_RINGING, ActivityKind.TIMER_DONE,
            ActivityKind.ASSISTANT, ActivityKind.REMINDER)
    }
}
