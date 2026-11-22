package app.vesper.feature.settings.api

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * The web app's static files (assets/settings-web/ on the Echo), loaded on first request and kept
 * with a gzipped copy and an ETag (well under 100 KB in all).
 */
class Assets(private val cached: Boolean = true, private val load: (String) -> ByteArray?) {
    class Asset(val bytes: ByteArray, val gzip: ByteArray?, val mime: String, val etag: String)

    private val cache = ConcurrentHashMap<String, Asset>()

    fun get(name: String): Asset? {
        if (!NAME.matches(name)) return null
        val mime = mime(name) ?: return null
        cache[name]?.let { return it }
        val bytes = load(name) ?: return null
        val gz = if (mime.startsWith("image/png")) null else gzip(bytes).takeIf { it.size < bytes.size }
        val etag = "\"" + MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) } + "\""
        return Asset(bytes, gz, mime, etag).also { if (cached) cache[name] = it }
    }

    companion object {
        private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,60}$")

        fun mime(name: String): String? = when (name.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "webmanifest" -> "application/manifest+json"
            else -> null
        }

        fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream(b.size / 3 + 64).also { o ->
            GZIPOutputStream(o).use { it.write(b) }
        }.toByteArray()
    }
}
