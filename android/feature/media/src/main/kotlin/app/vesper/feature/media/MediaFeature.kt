package app.vesper.feature.media

import android.os.Handler
import android.os.Looper
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec

/**
 * `:feature:media` (agent D — Now Playing + media).
 *
 *  - Now Playing screen ([NowPlayingScreen], "nowplaying", pager order 10).
 *  - The MUSIC live activity for the island ([MusicIsland]).
 *  - Other apps' media sessions into `services.media` ([AppSessions], needs the listener grant).
 *  - Sample media while nothing real is playing and demo data is on ([DemoMedia]).
 */
object MediaFeature : VesperFeature {
    override val id = "media"

    /** Set when demo data is on; the screen uses it for Disconnect and debug args. */
    internal var demo: DemoMedia? = null
        private set

    override fun install(host: FeatureHost) {
        val services = host.services
        host.registerScreen(ScreenSpec(NowPlayingScreen.ID, order = 10) { ctx -> NowPlayingScreen(ctx, services) })
        host.registerSettings(MediaSettings.section)
        MusicIsland(services.media, services.island, services.scope, services.settings).start()
        val sessions = AppSessions(host.context, services)
        // After install: the listener grant check talks to system services.
        Handler(Looper.getMainLooper()).post { sessions.start() }
        if (services.config.demoData) demo = DemoMedia(services.media, services.scope).also { it.watch() }
    }
}
