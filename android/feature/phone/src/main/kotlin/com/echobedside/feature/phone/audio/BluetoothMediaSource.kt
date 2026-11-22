package com.echobedside.feature.phone.audio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaHub
import com.echobedside.core.model.MediaState
import kotlin.math.roundToInt

/**
 * The phone's Now Playing while it streams to the Echo as a Bluetooth speaker (A2DP sink +
 * AVRCP controller, both built into this ROM), published to [MediaHub] as source "bluetooth".
 *
 * Cheapest path on Android 11: the Bluetooth app's own `BluetoothMediaBrowserService`
 * (com.android.bluetooth, exported, no permission) hands out the AVRCP controller's media session
 * token, so a framework [MediaBrowser] + [MediaController] gets metadata, play state and
 * transport controls with callbacks only (no polling, no notification-listener access, no hidden
 * APIs). `:feature:media` skips sessions from com.android.bluetooth, so this is the only copy.
 *
 * Limits of this stack: cover art is off ("Cover Artwork Enabled: False" in dumpsys, a ROM
 * overlay), AVRCP 1.6 has no seek-to-position, and volume is the Echo's own music stream (the
 * iPhone mirrors it with AVRCP absolute volume).
 */
class BluetoothMediaSource(
    private val context: Context,
    private val handler: Handler,
    private val hub: () -> MediaHub,
    /** The phone's name for the "from" label, e.g. "Maya's iPhone". */
    private val label: () -> String,
    private val onPlayingChanged: (Boolean) -> Unit = {},
    /** Now Playing's Disconnect: drop the A2DP link to the phone (it stays paired). */
    private val disconnectAudio: () -> Boolean = { false },
) {
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var published: MediaState? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    var enabled = true
        set(v) { field = v; if (!v) clear() else refresh() }

    val connected: Boolean get() = controller != null

    fun start() {
        if (browser != null) return
        val b = MediaBrowser(context, SERVICE, connection, null)
        browser = b
        try { b.connect() } catch (e: Exception) { Log.w(TAG, "MediaBrowser.connect", e); browser = null }
        runCatching { context.registerReceiver(volumeReceiver, IntentFilter(VOLUME_CHANGED)) }
    }

    fun stop() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        browser?.let { runCatching { it.disconnect() } }
        browser = null
        runCatching { context.unregisterReceiver(volumeReceiver) }
        clear()
    }

    /** Re-read after the A2DP / AVRCP connection or the label changes. */
    fun refresh() {
        val c = controller ?: run { clear(); return }
        publish(c.metadata, c.playbackState)
    }

    private fun clear() {
        if (published != null) { published = null; hub().clear(AvrcpMapping.SOURCE_ID); onPlayingChanged(false) }
    }

    private fun publish(md: MediaMetadata?, ps: PlaybackState?) {
        if (!enabled) { clear(); return }
        val snap = if (md == null || ps == null) null else AvrcpSnapshot(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            state = ps.state,
            positionMs = ps.position,
            updatedAtElapsed = ps.lastPositionUpdateTime,
            artwork = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
        )
        val state = AvrcpMapping.toState(snap, label(), volume(), SystemClock.elapsedRealtime())?.let { it.copy(route = AvrcpMapping.route(it.sourceLabel)) }
        if (state == null) { clear(); return }
        if (AvrcpMapping.sameForUi(published, state)) return
        val wasPlaying = published?.playing == true
        published = state
        hub().publish(AvrcpMapping.SOURCE_ID, state, controls)
        if (wasPlaying != state.playing) onPlayingChanged(state.playing)
    }

    private fun volume(): Float? {
        val a = audio ?: return null
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private val controls = object : MediaControls {
        private val t get() = controller?.transportControls
        override fun play() { t?.play() }
        override fun pause() { t?.pause() }
        override fun next() { t?.skipToNext() }
        override fun previous() { t?.skipToPrevious() }
        override fun seekTo(positionMs: Long) { /* AVRCP 1.6 has no absolute seek */ }
        override fun setVolume(volume: Float) {
            val a = audio ?: return
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // No FLAG_SHOW_UI: our own HUD (`:feature:system`) shows volume.
            a.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceIn(0f, 1f) * max).roundToInt(), 0)
        }
        // AVRCP 1.6 passthrough: play/pause/next/previous work; there's no absolute seek.
        override val canSeek: Boolean get() = false
        override val canSkip: Boolean get() = true
        override val canPause: Boolean get() = true
        override fun disconnect() {
            if (!disconnectAudio()) t?.pause()
        }
    }

    private val connection = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val b = browser ?: return
            val c = try { MediaController(context, b.sessionToken) } catch (e: Exception) { Log.w(TAG, "controller", e); return }
            controller?.unregisterCallback(controllerCallback)
            controller = c
            c.registerCallback(controllerCallback, handler)
            Log.i(TAG, "Bluetooth media session connected (${c.packageName})")
            publish(c.metadata, c.playbackState)
        }

        override fun onConnectionSuspended() { controller?.unregisterCallback(controllerCallback); controller = null; clear() }
        override fun onConnectionFailed() { Log.w(TAG, "BluetoothMediaBrowserService refused the connection"); controller = null; browser = null; clear() }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish(metadata, controller?.playbackState) }
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish(controller?.metadata, state) }
        override fun onSessionDestroyed() {
            controller = null; clear()
            // The Bluetooth app restarted (e.g. Bluetooth toggled): reconnect.
            browser?.let { runCatching { it.disconnect() } }; browser = null
            runCatching { context.unregisterReceiver(volumeReceiver) }
            handler.postDelayed({ start() }, 2_000)
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val cur = published ?: return
            val v = volume()
            if (v != cur.volume) { published = cur.copy(volume = v); hub().publish(AvrcpMapping.SOURCE_ID, published!!, controls) }
        }
    }

    companion object {
        const val TAG = "EchoPhone"
        val SERVICE = ComponentName("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService")
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
