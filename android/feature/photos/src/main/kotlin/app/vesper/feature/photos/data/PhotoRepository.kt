package app.vesper.feature.photos.data

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the photo frame plays from: Immich, or procedural stand-ins in demo mode. */
interface PhotoRepository {
    /** Shown in the caption: "October 2025 · Immich · Family album". */
    val sourceName: String
    /** Shuffle the album (Immich) or play it in order (demo: the design's sequence). */
    val shuffle: Boolean get() = true
    suspend fun albums(): Loaded<List<Album>>
    suspend fun assets(album: Album): Loaded<List<PhotoAsset>>
    /** Offline playback: only assets whose preview is on disk. */
    fun isCached(asset: PhotoAsset): Boolean
    /** A 960×480 frame, downloaded/decoded off the main thread. Throws on failure. */
    suspend fun frame(asset: PhotoAsset): Bitmap
    /** Slow-zoom origin (fractions of the screen). */
    fun pivot(asset: PhotoAsset): Pair<Float, Float> = asset.pivotX to asset.pivotY
}

/** Immich with the disk cache: previews are downloaded once and replayed from disk when offline. */
class ImmichRepository(
    private val client: ImmichClient,
    private val cache: PreviewCache,
    private val meta: MetaStore,
) : PhotoRepository {
    override val sourceName = "Immich"

    override suspend fun albums(): Loaded<List<Album>> = withContext(Dispatchers.IO) {
        try {
            val a = client.albums().sortedBy { it.name.lowercase() }
            meta.saveAlbums(a)
            Loaded(a, offline = false)
        } catch (e: IOException) {
            Loaded(meta.loadAlbums() ?: throw e, offline = true)
        }
    }

    override suspend fun assets(album: Album): Loaded<List<PhotoAsset>> = withContext(Dispatchers.IO) {
        try {
            val a = client.albumAssets(album.id)
            meta.saveAssets(album.id, a)
            Loaded(a, offline = false)
        } catch (e: IOException) {
            if (e is ImmichHttpException && e.code in 400..499) throw e // bad key or album gone: say so
            Loaded(meta.loadAssets(album.id) ?: throw e, offline = true)
        }
    }

    override fun isCached(asset: PhotoAsset) = cache.contains(asset.id)

    override suspend fun frame(asset: PhotoAsset): Bitmap = withContext(Dispatchers.IO) {
        val file = cache.get(asset.id) ?: run {
            client.downloadPreview(asset.id, cache.fileFor(asset.id))
            cache.commit(asset.id) ?: throw IOException("cache commit failed")
        }
        try {
            PhotoDecoder.decodeFrame(file)
        } catch (e: BadImageException) {
            cache.remove(asset.id) // corrupt download: fetch it again next time
            throw e
        }
    }

    companion object {
        fun create(url: String, apiKey: String, cacheRoot: File) = ImmichRepository(
            ImmichClient(url, apiKey),
            PreviewCache(File(cacheRoot, "photos/previews")),
            MetaStore(File(cacheRoot, "photos/meta")),
        )
    }
}

/**
 * Demo mode (`services.config.demoData`): the design's three stand-in photos (sample data) plus
 * variants, in three albums. One "photo" is dated three years ago today to show the memory caption.
 */
class DemoRepository(private val today: () -> LocalDate = LocalDate::now) : PhotoRepository {
    override val sourceName = "Immich"
    override val shuffle get() = false

    private data class Demo(val asset: PhotoAsset, val spec: StandIns.Spec)

    private fun d(id: String, place: String, y: Int, m: Int, day: Int, spec: StandIns.Spec) =
        Demo(PhotoAsset(id, LocalDateTime.of(y, m, day, 17, 30), place, 960, 480), spec)

    private val albums: Map<Album, List<Demo>> by lazy {
        val t = today()
        val ago3 = t.minusYears(3)
        val family = listOf(
            d("demo-a", "Big Sur", 2025, 10, 12, StandIns.Spec(StandIns.Kind.COAST)),
            d("demo-b", "Yosemite Valley", 2026, 1, 18, StandIns.Spec(StandIns.Kind.VALLEY)),
            d("demo-c", "Night market, Taipei", 2025, 7, 4, StandIns.Spec(StandIns.Kind.LANTERNS)),
            d("demo-d", "Santa Cruz", ago3.year, ago3.monthValue, ago3.dayOfMonth, StandIns.Spec(StandIns.Kind.COAST, mirror = true, hue = -18f)),
        )
        val trips = listOf(
            d("demo-t1", "Lake Tahoe", 2024, 2, 9, StandIns.Spec(StandIns.Kind.VALLEY, mirror = true, hue = -24f)),
            d("demo-t2", "Lisbon", 2024, 6, 21, StandIns.Spec(StandIns.Kind.LANTERNS, hue = 28f, seed = 11)),
            d("demo-t3", "Point Reyes", 2023, 9, 30, StandIns.Spec(StandIns.Kind.COAST, hue = 14f)),
            d("demo-t4", "Zermatt", 2025, 12, 27, StandIns.Spec(StandIns.Kind.VALLEY, hue = 16f)),
            d("demo-t5", "Kyoto", 2024, 4, 2, StandIns.Spec(StandIns.Kind.LANTERNS, mirror = true, seed = 7)),
        )
        val night = listOf(
            d("demo-n1", "Lantern festival", 2025, 2, 12, StandIns.Spec(StandIns.Kind.LANTERNS, seed = 19)),
            d("demo-n2", "Harbour lights", 2024, 12, 31, StandIns.Spec(StandIns.Kind.LANTERNS, hue = -40f, seed = 23)),
            d("demo-n3", "Old town", 2023, 11, 3, StandIns.Spec(StandIns.Kind.LANTERNS, mirror = true, hue = 60f, seed = 31)),
        )
        linkedMapOf(
            Album("demo-family", "Family", family.size) to family,
            Album("demo-trips", "Trips", trips.size) to trips,
            Album("demo-night", "Night walks", night.size) to night,
        )
    }

    private val byId by lazy { albums.values.flatten().associateBy { it.asset.id } }

    override suspend fun albums() = Loaded(albums.keys.toList(), offline = false)

    override suspend fun assets(album: Album) =
        Loaded(albums.entries.firstOrNull { it.key.id == album.id }?.value?.map { it.asset } ?: emptyList(), offline = false)

    override fun isCached(asset: PhotoAsset) = true

    override suspend fun frame(asset: PhotoAsset): Bitmap = withContext(Dispatchers.Default) {
        StandIns.render(byId[asset.id]?.spec ?: throw BadImageException("unknown demo photo"))
    }

    override fun pivot(asset: PhotoAsset): Pair<Float, Float> {
        val spec = byId[asset.id]?.spec ?: return super.pivot(asset)
        val (x, y) = StandIns.pivot(spec.kind)
        return (if (spec.mirror) 1f - x else x) to y
    }
}
