package app.vesper.feature.photos.data

import java.io.File

/**
 * Size-capped LRU cache of preview files in one directory (under `cacheDir`). Entries are files
 * named by key; recency is kept in memory (seeded from the files' mtimes at startup) and written
 * back as mtime on use, so it survives restarts. Thread-safe.
 *
 * @param maxBytes total size cap; the least recently used files are deleted past it
 * @param clock ms clock (tests)
 */
class PreviewCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val file: File, var size: Long, var used: Long)

    // access-ordered: iteration starts at the least recently used entry
    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var total = 0L

    init {
        dir.mkdirs()
        val files = dir.listFiles().orEmpty()
        for (f in files) if (f.name.endsWith(".tmp")) f.delete()
        files.filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.lastModified() }
            .forEach { entries[it.name] = Entry(it, it.length(), it.lastModified()); total += it.length() }
        synchronized(this) { trim() }
    }

    val sizeBytes: Long @Synchronized get() = total
    val count: Int @Synchronized get() = entries.size

    @Synchronized fun contains(key: String): Boolean = entries.containsKey(fileName(key))

    /** The cached file, marked as just used; null if absent. */
    @Synchronized fun get(key: String): File? {
        val e = entries[fileName(key)] ?: return null
        if (!e.file.exists()) { entries.remove(e.file.name); total -= e.size; return null }
        e.used = clock()
        e.file.setLastModified(e.used)
        return e.file
    }

    /** Where to write a new entry for [key]; call [commit] after the file is complete. */
    fun fileFor(key: String): File = File(dir, fileName(key))

    /** Registers a file written to [fileFor] and evicts least recently used entries past the cap. */
    @Synchronized fun commit(key: String): File? {
        val f = File(dir, fileName(key))
        if (!f.exists()) return null
        entries.remove(f.name)?.let { total -= it.size }
        val now = clock()
        f.setLastModified(now)
        entries[f.name] = Entry(f, f.length(), now)
        total += f.length()
        trim(keep = f.name)
        return f.takeIf { it.exists() }
    }

    @Synchronized fun remove(key: String) {
        entries.remove(fileName(key))?.let { total -= it.size; it.file.delete() }
    }

    private fun trim(keep: String? = null) {
        val it = entries.values.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.file.name == keep) continue
            e.file.delete()
            total -= e.size
            it.remove()
        }
    }

    companion object {
        /** ~750 photos at ~200 KB each: enough to play a whole album offline. */
        const val DEFAULT_MAX_BYTES = 150L * 1024 * 1024

        /** Immich ids are UUIDs; anything else is made filesystem-safe. */
        fun fileName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".img"
    }
}
