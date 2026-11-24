package app.vesper.feature.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Everything the updater keeps across process deaths, in `files/updates/state.json`. Older and
 * newer versions read the same file (a rollback lands on the previous version, which must see
 * the skip list), so fields are only ever added; unknown ones are ignored.
 */
data class UpdateState(
    val lastCheckAt: Long = 0,
    /** Short text for the settings page: "Up to date", "0.2.270 is ready", an error. */
    val lastCheckResult: String = "",
    val etag: String = "",
    /** The latest release the feed returned (also kept for 304 answers). */
    val latest: Release? = null,
    /** A downloaded and verified APK waiting to be installed. */
    val ready: Ready? = null,
    /** Versions rolled back after a crash loop: never installed automatically again. */
    val skipped: Set<Long> = emptySet(),
    /** APK checksums that failed verification (not downloaded again automatically). */
    val refused: Set<String> = emptySet(),
    /** An install committed and not yet seen from the new version. */
    val pending: Pending? = null,
    /** The kept copy of the version before the last update (the rollback target). */
    val previous: Previous? = null,
    val guard: CrashLoop.Guard = CrashLoop.Guard(),
    /** Set when a rollback of this version failed: start in safe mode instead of looping. */
    val rollbackFailed: Long = 0,
    /** Newest last, at most [HISTORY]. */
    val history: List<String> = emptyList(),
) {
    data class Ready(val versionCode: Long, val versionName: String, val sha256: String, val path: String)
    data class Pending(val from: Long, val to: Long, val toName: String, val at: Long, val manual: Boolean, val rollback: Boolean = false)
    data class Previous(val versionCode: Long, val versionName: String, val sha256: String, val path: String)

    fun withHistory(line: String) = copy(history = (history + line).takeLast(HISTORY))

    fun toJson(): JSONObject = JSONObject().apply {
        put("lastCheckAt", lastCheckAt); put("lastCheckResult", lastCheckResult); put("etag", etag)
        latest?.let { put("latest", it.toJson()) }
        ready?.let { put("ready", JSONObject().put("versionCode", it.versionCode).put("versionName", it.versionName).put("sha256", it.sha256).put("path", it.path)) }
        put("skipped", JSONArray().apply { skipped.sorted().forEach { put(it) } })
        put("refused", JSONArray().apply { refused.sorted().forEach { put(it) } })
        pending?.let { put("pending", JSONObject().put("from", it.from).put("to", it.to).put("toName", it.toName).put("at", it.at).put("manual", it.manual).put("rollback", it.rollback)) }
        previous?.let { put("previous", JSONObject().put("versionCode", it.versionCode).put("versionName", it.versionName).put("sha256", it.sha256).put("path", it.path)) }
        put("guard", JSONObject().put("version", guard.version).put("armedAt", guard.armedAt).put("failedStarts", guard.failedStarts).put("lastStartAt", guard.lastStartAt))
        put("rollbackFailed", rollbackFailed)
        put("history", JSONArray().apply { history.forEach { put(it) } })
    }

    companion object {
        const val HISTORY = 8

        fun fromJson(o: JSONObject): UpdateState {
            fun longs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet()
            fun strings(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val g = o.optJSONObject("guard")
            return UpdateState(
                lastCheckAt = o.optLong("lastCheckAt"),
                lastCheckResult = o.optString("lastCheckResult"),
                etag = o.optString("etag"),
                latest = o.optJSONObject("latest")?.let { runCatching { Release.fromJson(it) }.getOrNull() },
                ready = o.optJSONObject("ready")?.let { Ready(it.getLong("versionCode"), it.optString("versionName"), it.getString("sha256"), it.getString("path")) },
                skipped = longs("skipped"),
                refused = strings("refused").toSet(),
                pending = o.optJSONObject("pending")?.let { Pending(it.getLong("from"), it.getLong("to"), it.optString("toName"), it.optLong("at"), it.optBoolean("manual"), it.optBoolean("rollback")) },
                previous = o.optJSONObject("previous")?.let { Previous(it.getLong("versionCode"), it.optString("versionName"), it.optString("sha256"), it.getString("path")) },
                guard = if (g == null) CrashLoop.Guard() else CrashLoop.Guard(g.optLong("version"), g.optLong("armedAt"), g.optInt("failedStarts"), g.optLong("lastStartAt")),
                rollbackFailed = o.optLong("rollbackFailed"),
                history = strings("history"),
            )
        }
    }
}

/** The state file, process-wide. Writes are atomic (temp file, fsync, rename). */
class UpdateStore(val dir: File) {
    private val file = File(dir, "state.json")
    @Volatile private var cached: UpdateState? = null
    /** Called after every change (any thread). */
    @Volatile var onChange: ((UpdateState) -> Unit)? = null

    @Synchronized fun get(): UpdateState = cached ?: load().also { cached = it }

    fun update(transform: (UpdateState) -> UpdateState): UpdateState {
        val (next, changed) = synchronized(this) {
            val next = transform(get())
            val changed = next != cached
            if (changed) { save(next); cached = next }
            next to changed
        }
        if (changed) onChange?.invoke(next)
        return next
    }

    private fun load(): UpdateState = try {
        if (file.exists()) UpdateState.fromJson(JSONObject(file.readText())) else UpdateState()
    } catch (e: Exception) { UpdateState() }

    private fun save(s: UpdateState) {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        FileOutputStream(tmp).use { out -> out.write(s.toJson().toString().toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
    }

    companion object {
        @Volatile private var instance: UpdateStore? = null
        fun of(filesDir: File): UpdateStore = instance ?: synchronized(this) {
            instance ?: UpdateStore(File(filesDir, "updates")).also { instance = it }
        }
    }
}
