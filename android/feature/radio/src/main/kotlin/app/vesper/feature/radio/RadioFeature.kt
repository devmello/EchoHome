package app.vesper.feature.radio

import android.os.Handler
import android.os.Looper
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.VesperFeature

/**
 * `:feature:radio`: internet radio from the Radio Browser directory, played with the platform
 * MediaPlayer (MP3 / AAC / Ogg / FLAC / HLS), with the stream's song titles (ICY metadata).
 *
 * Binds services.radio ([RadioService]: the assistant's play_radio / stop_radio) and publishes
 * the media source "radio" to services.media, so Now Playing, the island and Control Center's
 * media tile show and control it like any other source. Screen "radio" (pager order 15, after
 * Now Playing). Settings card "Radio" (order 32). Log tag `VesperRadio`.
 *
 * Nothing plays by itself: a stream opens only when a person taps a station or asks for one.
 */
object RadioFeature : VesperFeature {
    override val id = "radio"
    const val SCREEN_ID = "radio"

    override fun install(host: FeatureHost) {
        val s = host.services
        val radio = RadioService(host.context, s)
        s.radio = radio
        host.registerSettings(RadioSettings.section)
        host.registerScreen(ScreenSpec(SCREEN_ID, order = 16) { RadioScreen(it, s, radio) })
        // Once every feature is installed (arbitration reads the other media sources).
        Handler(Looper.getMainLooper()).post { radio.start() }
    }
}
