package app.vesper.feature.photos.data

import kotlin.random.Random

/**
 * Slideshow order and timing, free of Android so it can be unit tested.
 *
 *  - The album is shuffled once per pass; at the end of a pass [needsRefresh] turns true
 *    (reload the album to pick up new photos) and a fresh shuffle starts.
 *  - [next] is the photo to prefetch; it skips assets that failed and, offline, assets that
 *    aren't in the disk cache ([playable]).
 *  - [due] says when the current photo has been up long enough (never while paused).
 */
class Slideshow(private val random: Random = Random.Default) {
    private var order: List<PhotoAsset> = emptyList()
    private var index = -1
    private val failed = HashSet<String>()

    /** Offline: only assets whose preview is cached. Online: everything. */
    var playable: (PhotoAsset) -> Boolean = { true }
    var paused = false
    /** Seconds each photo stays up (design default 8). */
    var seconds = DEFAULT_SECONDS
    /** When the current photo finished appearing (ms). */
    var shownAt = 0L
        private set
    var needsRefresh = false
        private set

    val size get() = order.size
    val current: PhotoAsset? get() = order.getOrNull(index)
    val isEmpty get() = order.isEmpty()

    /** Replaces the album. Keeps the current photo first if it's still in the album. */
    fun setAssets(assets: List<PhotoAsset>, shuffle: Boolean = true) {
        val cur = current
        val shuffled = if (shuffle) assets.shuffled(random) else assets
        order = if (cur != null && shuffled.any { it.id == cur.id }) listOf(cur) + shuffled.filter { it.id != cur.id } else shuffled
        index = if (cur != null && order.firstOrNull()?.id == cur.id) 0 else -1
        failed.retainAll(assets.map { it.id }.toSet())
        needsRefresh = false
    }

    fun clear() { order = emptyList(); index = -1; failed.clear(); needsRefresh = false }

    /** The next playable photo after the current one (wrapping), or null if there is none. */
    fun next(): PhotoAsset? = nextIndex()?.let { order[it] }

    /**
     * Moves to [asset] (normally the [next] that was prefetched) and starts its timer.
     * Wrapping past the end marks the album for a refresh.
     */
    fun advanceTo(asset: PhotoAsset, now: Long) {
        val i = order.indexOfFirst { it.id == asset.id }
        if (i < 0) return
        if (index >= 0 && i <= index) needsRefresh = true
        index = i
        shownAt = now
    }

    fun markFailed(asset: PhotoAsset) { failed += asset.id }

    /** Restarts the current photo's timer (e.g. coming back on screen after a long time away). */
    fun restartTimer(now: Long) { shownAt = now }

    fun due(now: Long): Boolean = !paused && current != null && now - shownAt >= seconds * 1000L

    /** ms until [due], 0 if already due, null while paused or empty. */
    fun msUntilDue(now: Long): Long? =
        if (paused || current == null) null else (shownAt + seconds * 1000L - now).coerceAtLeast(0)

    private fun nextIndex(): Int? {
        if (order.isEmpty()) return null
        for (step in 1..order.size) {
            val i = (index + step).mod(order.size)
            val a = order[i]
            if (a.id !in failed && playable(a)) {
                // Only the current photo is playable: there's nothing new to show.
                if (i == index) return null
                return i
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_SECONDS = 8
    }
}
