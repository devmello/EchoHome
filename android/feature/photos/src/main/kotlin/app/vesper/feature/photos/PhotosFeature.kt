package app.vesper.feature.photos

import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.OverlaySpec
import app.vesper.core.model.ScreenSpec
import app.vesper.feature.photos.ui.PhotosScreen

/**
 * `:feature:photos` (agent I — Photos): the Immich photo frame (Photos.dc.html).
 *
 *  - Screen "photos" in the pager at order 70.
 *  - Settings card "Photo frame" ([PhotoSettings]): album, seconds per photo, shuffle, show after
 *    idle, never at night. The album becomes a list once albums can be listed.
 *  - Overlay "photos-idle" (z 1000, draws nothing, consumes nothing) + a key handler: the idle
 *    watcher that opens the frame from StandBy.
 *
 * Source ([PhotoLibrary]): Immich when `immich.url` / `immich.apiKey` are set (secrets.properties
 * or the settings page, applied live), else the procedural stand-ins in demo mode, else a quiet
 * "add a server" state.
 */
object PhotosFeature : VesperFeature {
    override val id = "photos"

    override fun install(host: FeatureHost) {
        val library = PhotoLibrary(host.context, host.services)
        host.registerSettings(PhotoSettings.section(null))
        library.start()
        host.registerScreen(ScreenSpec("photos", order = 70, title = "Photos") { ctx -> PhotosScreen(ctx, host.services, library) })
        host.registerOverlay(OverlaySpec("photos-idle", z = 1000) { ctx -> IdleWatcher(ctx, host.services) })
        host.registerKeyHandler { _ -> IdleWatcher.current?.get()?.onInput(); false }
    }
}
