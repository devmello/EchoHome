package app.vesper.feature.update

import android.util.Log
import app.vesper.core.model.Banner
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.VesperFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * `:feature:update`: over-the-network updates from GitHub Releases (docs/status/update.md).
 *
 * Once a day (02:30 + a few minutes, or soon after start when a check is overdue) it asks
 * GitHub for the latest release; a higher versionCode is downloaded, checked (SHA-256, package,
 * versionCode, same signing certificate as the installed app) and installed silently with
 * PackageInstaller in the night window when the room is quiet ([QuietWindow]), or right away
 * from the settings page's "Install now". [UpdateGuard] (called by `:app` before any feature)
 * rolls a crash-looping update back to the kept previous APK.
 *
 * Debug builds: launch extras `--es updateFeed <url>` (a test feed; `-` clears it),
 * `--ez updateCheck true`, `--ez updateInstall true`.
 */
object UpdateFeature : VesperFeature {
    override val id = "update"
    const val KEY_ENABLED = "update.enabled"
    const val KEY_AUTO = "update.auto"
    const val KEY_CHECK = "update.check"
    const val KEY_INSTALL = "update.install"
    const val SECTION = "update"

    @Volatile internal var manager: UpdateManager? = null
        private set

    override fun install(host: FeatureHost) {
        val services = host.services
        val m = UpdateManager(host.context, services).also { manager = it }
        host.registerSettings(section(texts(m.status.value)))
        val scope = services.scope
        scope.launch {
            m.status.map { texts(it) }.distinctUntilChanged().collect { host.registerSettings(section(it)) }
        }
        scope.launch {
            services.settings.changes.collect { k -> if (k == KEY_ENABLED || k == KEY_AUTO) m.reschedule() }
        }
        if (services.config.debug) scope.launch {
            services.intents.collect { intent ->
                val ex = intent.extras ?: return@collect
                ex.getString("updateFeed")?.let { m.setFeedOverride(it); Log.i(Installer.TAG, "debug feed: ${DebugFeed.overrideUrl(services.settings) ?: "GitHub"}") }
                if (ex.getBoolean("updateCheck")) m.check(manual = true)
                if (ex.getBoolean("updateInstall")) Log.i(Installer.TAG, "debug install: " + m.installNow())
            }
        }
        UpdateGuard.justUpdatedManually?.let { name ->
            scope.launch {
                delay(4_000)
                services.notifications.post(Banner(id = "update-done", appName = "EchoHome", appColor = 0xFF0A84FF.toInt(),
                    title = "Updated to $name", body = m.status.value.state.latest?.notes?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160) ?: ""))
            }
        }
        m.reschedule()
    }

    internal data class Texts(val status: String, val install: String)

    /** The "Updates" card. Its texts carry the live status, so it is re-registered when they change. */
    internal fun section(t: Texts) = SettingsSection(SECTION, "Updates", 95, listOf(
        SettingSpec(KEY_CHECK, "Check now", SettingType.ACTION, help = t.status,
            onAction = { manager?.check(manual = true); "Checking GitHub for a new version…" }),
        SettingSpec(KEY_INSTALL, "Install now", SettingType.ACTION, help = t.install,
            onAction = { manager?.installNow() }),
        SettingSpec(KEY_AUTO, "Install automatically", SettingType.BOOLEAN, "true",
            help = "At night (3–5 AM) when nobody is in the room, nothing is playing and no alarm is due within 90 minutes."),
        SettingSpec(KEY_ENABLED, "Check for updates", SettingType.BOOLEAN, "true", advanced = true,
            help = "Once a day EchoHome asks GitHub (github.com/devmello/EchoHome) for the latest release. Off: no requests at all."),
    ), help = "New versions come from GitHub Releases, signed with the same key as this app. A version that keeps crashing goes back to the previous one by itself.")

    internal fun texts(st: UpdateManager.Status): Texts {
        val s = st.state
        val latest = s.latest
        val newer = VersionPolicy.isNewer(latest, st.installed)
        val status = buildString {
            append("Installed: ${st.installedName} (${st.installed})")
            append("\nLast check: ")
            append(if (s.lastCheckAt == 0L) "never" else "${UpdateGuard.stamp(s.lastCheckAt)}, ${s.lastCheckResult}")
            st.busy?.let { append("\nNow: $it") }
            s.history.lastOrNull()?.let { append("\nLast event: $it") }
        }
        val installHelp = if (newer && latest != null) {
            "Available: ${latest.versionName} (${latest.versionCode})" +
                (if (latest.versionCode in s.skipped) ", skipped automatically after it crashed" else "") +
                (if (latest.notes.isNotBlank()) "\n" + latest.notes else "")
        } else "Checks first, then installs a newer version right away. EchoHome restarts (about a minute)."
        return Texts(status, installHelp)
    }
}
