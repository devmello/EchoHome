package com.echobedside.feature.photos

import android.content.Context
import android.util.Log
import com.echobedside.core.model.EchoServices
import com.echobedside.feature.photos.data.Album
import com.echobedside.feature.photos.data.DemoRepository
import com.echobedside.feature.photos.data.ImmichRepository
import com.echobedside.feature.photos.data.PhotoRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one photo source per process, shared by the screen and the settings card (one disk cache
 * instance, one album list). Rebuilt whenever the Immich URL / key or the demo flag change
 * (`services.configFlow`: secrets can be set from the settings page while the app runs):
 * Immich when configured, else the demo stand-ins in demo mode, else null ("add a server").
 */
internal class PhotoLibrary(private val context: Context, private val services: EchoServices) {
    private data class Source(val url: String, val key: String, val demo: Boolean)

    private val _repo = MutableStateFlow<PhotoRepository?>(null)
    val repo: StateFlow<PhotoRepository?> = _repo.asStateFlow()
    /** False until the first source is built (the screen waits instead of showing "add a server"). */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var albumsJob: Job? = null

    fun start() {
        services.scope.launch {
            services.configFlow
                .map { Source(it.secrets.immichUrl.trim(), it.secrets.immichApiKey.trim(), it.demoData) }
                .distinctUntilChanged()
                .collect { s ->
                    // Building ImmichRepository scans the cache directory: off the main thread.
                    val r = withContext(Dispatchers.IO) {
                        when {
                            s.url.isNotEmpty() && s.key.isNotEmpty() -> ImmichRepository.create(s.url, s.key, context.cacheDir)
                            s.demo -> DemoRepository()
                            else -> null
                        }
                    }
                    _repo.value = r
                    _ready.value = true
                    Log.i(TAG, "source: ${r?.javaClass?.simpleName ?: "none"}")
                    registerSettings(null)
                    if (r != null) loadAlbumsForSettings(r)
                }
        }
    }

    /** The screen calls this when it lists albums, so the settings card shows the same list. */
    fun onAlbums(r: PhotoRepository, albums: List<Album>) {
        if (r === _repo.value) registerSettings(albums)
    }

    private fun loadAlbumsForSettings(r: PhotoRepository) {
        albumsJob?.cancel()
        albumsJob = services.scope.launch {
            try {
                onAlbums(r, r.albums().value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "albums for settings: ${e.javaClass.simpleName}") // stays a TEXT album name
            }
        }
    }

    private var lastAlbums: List<Album>? = null

    private fun registerSettings(albums: List<Album>?) {
        if (albums != null && albums == lastAlbums) return
        lastAlbums = albums
        services.settingsSchema.register(PhotoSettings.section(albums))
    }

    companion object { private const val TAG = "EchoPhotos" }
}
