package com.echobedside.feature.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.MediaControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Exists only so we may call [MediaSessionManager.getActiveSessions] (that needs an enabled
 * notification listener). Media only: it ignores the notifications themselves; agent B's
 * listener owns banners. Grant once with
 * `adb shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService`.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() { AppSessions.instance?.start() }
}

/**
 * Other apps' media (NOTES §5.4 sources): every active [MediaController] is published into
 * `services.media` with working [MediaControls] and 324 px artwork. Our own package and the Bluetooth stack are skipped
 * ([SessionMapping.isMirrored]).
 */
class AppSessions(private val context: Context, private val services: EchoServices) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val tracked = HashMap<MediaSession.Token, Tracked>()
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    init { instance = this }

    /** Safe to call repeatedly; does nothing until the listener permission is granted. */
    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            started = true
            sync(msm.getActiveSessions(listener))
            Log.i(TAG, "watching media sessions")
        } catch (e: SecurityException) {
            Log.i(TAG, "media sessions not available until the listener is allowed: cmd notification allow_listener ${listener.flattenToString()}")
        }
    }

    private fun sync(list: List<MediaController>) {
        val wanted = list.filter { SessionMapping.isMirrored(it.packageName, context.packageName) }.associateBy { it.sessionToken }
        tracked.keys.filter { it !in wanted }.forEach { k -> tracked.remove(k)?.release() }
        for ((token, c) in wanted) if (token !in tracked) Tracked(c).also { tracked[token] = it }.publish()
    }

    private inner class Tracked(val controller: MediaController) : MediaControls {
        val id = SessionMapping.sourceId(controller.packageName, Integer.toHexString(System.identityHashCode(controller.sessionToken)))
        private val label = appLabel(controller.packageName)
        private var artKey: Any? = null
        private var art: Bitmap? = null
        private var released = false

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = publish()
            override fun onSessionDestroyed() { release() ; services.media.clear(id) }
        }

        init { controller.registerCallback(callback, main) }

        fun snapshot(): SessionSnapshot {
            val m = controller.metadata
            val p = controller.playbackState
            val info = controller.playbackInfo
            val remote = info?.takeIf { it.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && it.maxVolume > 0 }
                ?.let { it.currentVolume.toFloat() / it.maxVolume }
            return SessionSnapshot(
                packageName = controller.packageName,
                appLabel = label,
                title = m?.getString(MediaMetadata.METADATA_KEY_TITLE),
                displayTitle = m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                albumArtist = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                displaySubtitle = m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                playbackState = p?.state ?: SessionMapping.STATE_NONE,
                positionMs = p?.position ?: 0,
                lastPositionUpdate = p?.lastPositionUpdateTime ?: 0,
                speed = p?.playbackSpeed ?: 1f,
                actions = p?.actions ?: 0,
                remoteVolume = remote,
            )
        }

        fun publish() {
            if (released) return
            val snap = snapshot()
            val state = SessionMapping.toMediaState(snap, SystemClock.elapsedRealtime(), art)
            if (state == null) { services.media.clear(id); return }
            services.media.publish(id, state, this)
            loadArtwork(controller.metadata)
        }

        /** Scales the session's art to 324 px off the main thread, once per new picture. */
        private fun loadArtwork(m: MediaMetadata?) {
            m ?: return
            val bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val uri = (m.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))?.takeIf { it.isNotBlank() }
            val key: Any = bmp?.let { System.identityHashCode(it) } ?: uri ?: return
            if (key == artKey) return
            artKey = key
            services.scope.launch {
                val scaled = withContext(Dispatchers.IO) {
                    try {
                        val src = bmp ?: decodeUri(uri!!)
                        src?.let { s -> Artwork.centerCrop(s).also { if (bmp == null) s.recycle() } }
                    } catch (e: Exception) { Log.w(TAG, "artwork for $label failed: $e"); null }
                }
                if (released || artKey != key) { scaled?.recycle(); return@launch }
                art = scaled
                publish()
            }
        }

        private fun decodeUri(uri: String): Bitmap? {
            val u = Uri.parse(uri)
            if (u.scheme != "content" && u.scheme != "android.resource" && u.scheme != "file") return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
            return context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }

        fun release() {
            if (released) return
            released = true
            controller.unregisterCallback(callback)
            services.media.clear(id)
        }

        // MediaControls
        private val tc get() = controller.transportControls
        override fun play() = tc.play()
        override fun pause() = tc.pause()
        override fun next() = tc.skipToNext()
        override fun previous() = tc.skipToPrevious()
        override fun seekTo(positionMs: Long) = tc.seekTo(positionMs)
        /** The popover's Stop: an app session has nothing to disconnect, so stop it. */
        override fun disconnect() = tc.stop()
        override fun setVolume(volume: Float) {
            val info = controller.playbackInfo
            if (info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                controller.setVolumeTo((volume.coerceIn(0f, 1f) * info.maxVolume).roundToInt(), 0)
            } else {
                services.systemControls.setVolume(volume, showHud = false)
            }
        }
        override val canSeek: Boolean get() = SessionMapping.canSeek(snapshot())
        override val canSkip: Boolean get() = SessionMapping.canSkip(snapshot())
        override val canPause: Boolean get() = SessionMapping.canPause(snapshot())
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    companion object {
        private const val TAG = "EchoMedia"
        @Volatile var instance: AppSessions? = null; private set
    }
}
