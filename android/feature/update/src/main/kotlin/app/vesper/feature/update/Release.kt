package app.vesper.feature.update

import org.json.JSONException
import org.json.JSONObject

/**
 * One published EchoHome release, from GitHub's `GET /repos/devmello/EchoHome/releases/latest`.
 *
 * `scripts/echo-release.sh` writes the machine-readable part into the release body as an HTML
 * comment (invisible on GitHub):
 * `<!-- echohome-update {"versionCode":268,"versionName":"0.2.268","apk":"EchoHome-0.2.268.apk","sha256":"…","size":…} -->`
 * The APK is the release asset named by `apk`. The manifest is only a hint: the APK's own
 * signature, package and versionCode are checked before install ([ApkCheck]).
 */
data class Release(
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    /** Plain text from the release body (the comment and the checksum line removed). */
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    /** Lower-case hex. */
    val sha256: String,
    /** Bytes; 0 = unknown. */
    val size: Long,
    val publishedAt: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName).put("tag", tag).put("notes", notes)
        .put("apkName", apkName).put("apkUrl", apkUrl).put("sha256", sha256).put("size", size).put("publishedAt", publishedAt)

    companion object {
        fun fromJson(o: JSONObject): Release = Release(
            o.getLong("versionCode"), o.getString("versionName"), o.optString("tag"), o.optString("notes"),
            o.getString("apkName"), o.getString("apkUrl"), o.getString("sha256"), o.optLong("size"), o.optString("publishedAt"),
        )
    }
}

class ReleaseParseException(message: String) : Exception(message)

object ReleaseParser {
    const val MARKER = "<!-- echohome-update "
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    const val MAX_NOTES = 1200

    /** Parses one release object of the GitHub API. Throws [ReleaseParseException] with a short reason. */
    fun parse(json: String): Release {
        val o = try { JSONObject(json) } catch (e: JSONException) { throw ReleaseParseException("The release feed isn't JSON") }
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) throw ReleaseParseException("Latest release is a draft or prerelease")
        val body = o.optString("body", "")
        val meta = meta(body) ?: throw ReleaseParseException("Release ${o.optString("tag_name")} has no EchoHome update info")
        val versionCode = meta.optLong("versionCode", 0)
        if (versionCode <= 0) throw ReleaseParseException("Release has no versionCode")
        val versionName = meta.optString("versionName").ifBlank { o.optString("tag_name").removePrefix("v") }
        val sha = meta.optString("sha256").lowercase()
        if (!HEX64.matches(sha)) throw ReleaseParseException("Release has no valid SHA-256")
        val apkName = meta.optString("apk")
        if (apkName.isBlank()) throw ReleaseParseException("Release names no APK")
        val assets = o.optJSONArray("assets") ?: throw ReleaseParseException("Release has no assets")
        var asset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == apkName) { asset = a; break }
        }
        asset ?: throw ReleaseParseException("Release asset $apkName is missing")
        val url = asset.optString("browser_download_url")
        if (url.isBlank()) throw ReleaseParseException("Release asset has no download URL")
        val metaSize = meta.optLong("size", 0)
        val assetSize = asset.optLong("size", 0)
        if (metaSize > 0 && assetSize > 0 && metaSize != assetSize) throw ReleaseParseException("APK size in the release doesn't match the asset")
        // GitHub computes "sha256:<hex>" for every asset; if present it must agree with ours.
        val digest = asset.optString("digest", "")
        if (digest.startsWith("sha256:") && digest.removePrefix("sha256:").lowercase() != sha) {
            throw ReleaseParseException("APK checksum in the release doesn't match GitHub's")
        }
        return Release(versionCode, versionName, o.optString("tag_name"), notes(body), apkName, url, sha,
            if (metaSize > 0) metaSize else assetSize, o.optString("published_at"))
    }

    fun meta(body: String): JSONObject? {
        val start = body.indexOf(MARKER)
        if (start < 0) return null
        val end = body.indexOf("-->", start)
        if (end < 0) return null
        return try { JSONObject(body.substring(start + MARKER.length, end).trim()) } catch (e: JSONException) { null }
    }

    /** The human part of the body: no update comment, no checksum line, at most [MAX_NOTES] chars. */
    fun notes(body: String): String {
        var b = body
        val start = b.indexOf(MARKER)
        if (start >= 0) {
            val end = b.indexOf("-->", start)
            b = b.removeRange(start, if (end < 0) b.length else end + 3)
        }
        val text = b.replace("\r\n", "\n").lines()
            .filterNot { it.trimStart().startsWith("SHA-256") }
            .joinToString("\n").trim()
            .replace(Regex("\n{3,}"), "\n\n")
        return if (text.length <= MAX_NOTES) text else text.take(MAX_NOTES - 1).trimEnd() + "…"
    }
}
