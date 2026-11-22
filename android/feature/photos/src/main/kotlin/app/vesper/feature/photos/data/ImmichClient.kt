package app.vesper.feature.photos.data

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Immich REST client (API key auth, header `x-api-key`). Blocking: call it on
 * Dispatchers.IO. Endpoints used:
 *  - `GET  /api/albums`                               albums (owned + shared with the key's user)
 *  - `GET  /api/albums/{id}?withoutAssets=false`      album with its assets
 *  - `POST /api/search/metadata {albumIds:[id]}`      fallback when the server leaves `assets` out
 *  - `GET  /api/assets/{id}/thumbnail?size=preview`   the preview JPEG/WebP (server default 1440 px)
 *
 * [baseUrl] may be given with or without the trailing `/api`. The key is never logged.
 */
class ImmichClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttp(),
) {
    /** The server root, e.g. http://immich.local:2283/ (without /api). */
    val root: HttpUrl = normalize(baseUrl)

    fun albums(): List<Album> {
        val arr = JSONArray(getString(api("albums")))
        return (0 until arr.length()).map { parseAlbum(arr.getJSONObject(it)) }
    }

    /** Still images of an album (videos, trashed and archived assets left out), in album order. */
    fun albumAssets(albumId: String): List<PhotoAsset> {
        val url = api("albums", albumId).newBuilder().addQueryParameter("withoutAssets", "false").build()
        val album = JSONObject(getString(url))
        val assets = album.optJSONArray("assets")
        if (assets != null && (assets.length() > 0 || album.optInt("assetCount", 0) == 0)) return parseAssets(assets)
        return searchAlbumAssets(albumId)
    }

    /** Newer servers may omit album `assets`; page through search/metadata instead. */
    private fun searchAlbumAssets(albumId: String): List<PhotoAsset> {
        val out = ArrayList<PhotoAsset>()
        var page: String? = "1"
        var guard = 0
        while (page != null && guard++ < 50) {
            val body = JSONObject()
                .put("albumIds", JSONArray().put(albumId))
                .put("type", "IMAGE")
                .put("withExif", true)
                .put("size", PAGE_SIZE)
                .put("page", page.toIntOrNull() ?: 1)
            val req = request(api("search", "metadata"))
                .post(body.toString().toRequestBody(JSON))
                .build()
            val res = JSONObject(execString(req))
            val assets = res.optJSONObject("assets") ?: break
            out += parseAssets(assets.optJSONArray("items") ?: JSONArray())
            page = if (assets.isNull("nextPage")) null else assets.optString("nextPage").takeIf { it.isNotEmpty() }
        }
        return out
    }

    /** Downloads the asset's preview to [dest] (written to a temp file, then renamed). Returns its size. */
    fun downloadPreview(assetId: String, dest: File): Long {
        val req = request(previewUrl(assetId)).header("Accept", "image/*").build()
        http.newCall(req).execute().use { res ->
            check(res)
            val body = res.body ?: throw IOException("empty preview body")
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            if (tmp.length() == 0L) { tmp.delete(); throw IOException("empty preview") }
            if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("rename failed") }
            return dest.length()
        }
    }

    fun previewUrl(assetId: String): HttpUrl =
        api("assets", assetId, "thumbnail").newBuilder().addQueryParameter("size", "preview").build()

    // --- plumbing ---

    private fun api(vararg segments: String): HttpUrl {
        val b = root.newBuilder().addPathSegment("api")
        for (s in segments) b.addPathSegment(s)
        return b.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("x-api-key", apiKey)
        .header("Accept", "application/json")

    private fun getString(url: HttpUrl): String = execString(request(url).build())

    private fun execString(req: Request): String = http.newCall(req).execute().use { res ->
        check(res)
        res.body?.string() ?: throw IOException("empty body")
    }

    private fun check(res: Response) {
        if (!res.isSuccessful) {
            // The path only; never the headers (the key).
            throw ImmichHttpException(res.code, "Immich ${res.code} for ${res.request.url.encodedPath}")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PAGE_SIZE = 500

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun normalize(baseUrl: String): HttpUrl {
            var s = baseUrl.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            if (s.endsWith("/api")) s = s.removeSuffix("/api")
            return "$s/".toHttpUrl()
        }

        fun parseAlbum(o: JSONObject) = Album(
            id = o.getString("id"),
            name = o.optString("albumName", "Album"),
            assetCount = o.optInt("assetCount", 0),
        )

        fun parseAssets(arr: JSONArray): List<PhotoAsset> {
            val out = ArrayList<PhotoAsset>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type", "IMAGE") != "IMAGE") continue
                if (o.optBoolean("isTrashed", false) || o.optBoolean("isArchived", false)) continue
                if (o.optString("visibility", "timeline") == "hidden" || o.optString("visibility") == "locked") continue
                out += parseAsset(o)
            }
            return out
        }

        fun parseAsset(o: JSONObject): PhotoAsset {
            val exif = o.optJSONObject("exifInfo")
            fun str(obj: JSONObject?, k: String) = obj?.takeIf { !it.isNull(k) }?.optString(k)?.trim()?.takeIf { it.isNotEmpty() }
            val place = str(exif, "city") ?: str(exif, "state") ?: str(exif, "country")
            val taken = parseLocal(str(o, "localDateTime"))
                ?: parseLocal(str(exif, "dateTimeOriginal"))
                ?: parseLocal(str(o, "fileCreatedAt"))
            var w = exif?.optInt("exifImageWidth", 0) ?: 0
            var h = exif?.optInt("exifImageHeight", 0) ?: 0
            // EXIF orientation 5–8 = rotated 90°: the preview is upright, so swap.
            val orient = str(exif, "orientation")?.toIntOrNull() ?: 1
            if (orient in 5..8) { val t = w; w = h; h = t }
            return PhotoAsset(id = o.getString("id"), takenAt = taken, place = place, width = w, height = h)
        }

        /**
         * Immich's `localDateTime` is the local wall time written with a `Z` suffix
         * ("2025-10-12T18:30:00.000Z" = 18:30 where it was taken), so the zone is dropped, not applied.
         */
        fun parseLocal(s: String?): LocalDateTime? {
            if (s.isNullOrBlank()) return null
            return try {
                OffsetDateTime.parse(s).toLocalDateTime()
            } catch (_: DateTimeParseException) {
                try { LocalDateTime.parse(s.removeSuffix("Z")) } catch (_: DateTimeParseException) { null }
            }
        }
    }
}
