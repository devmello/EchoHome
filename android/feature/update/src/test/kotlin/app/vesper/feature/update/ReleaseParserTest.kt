package app.vesper.feature.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseParserTest {
    private val sha = "ab".repeat(32)

    private fun body(meta: String = """{"versionCode":268,"versionName":"0.2.268","apk":"EchoHome-0.2.268.apk","sha256":"$sha","size":1234,"minSdk":30}""") =
        "## What's new\n- Faster face check\n- Fixes the snooze label\n\nSHA-256 (EchoHome-0.2.268.apk): $sha\n\n<!-- echohome-update $meta -->\n"

    private fun release(body: String = body(), assets: JSONArray = JSONArray().put(asset()), extra: (JSONObject) -> Unit = {}) =
        JSONObject().put("tag_name", "v0.2.268").put("name", "EchoHome 0.2.268").put("draft", false).put("prerelease", false)
            .put("published_at", "2026-11-24T10:00:00Z").put("body", body).put("assets", assets).also(extra).toString()

    private fun asset(name: String = "EchoHome-0.2.268.apk", size: Long = 1234, digest: String? = "sha256:$sha") = JSONObject()
        .put("name", name).put("size", size)
        .put("browser_download_url", "https://github.com/devmello/EchoHome/releases/download/v0.2.268/$name")
        .also { if (digest != null) it.put("digest", digest) }

    private fun fails(json: String, contains: String) {
        try { ReleaseParser.parse(json); fail("expected a parse error") } catch (e: ReleaseParseException) {
            assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
        }
    }

    @Test fun parsesTheScriptsFormat() {
        val r = ReleaseParser.parse(release())
        assertEquals(268L, r.versionCode)
        assertEquals("0.2.268", r.versionName)
        assertEquals("v0.2.268", r.tag)
        assertEquals(sha, r.sha256)
        assertEquals(1234L, r.size)
        assertEquals("EchoHome-0.2.268.apk", r.apkName)
        assertEquals("https://github.com/devmello/EchoHome/releases/download/v0.2.268/EchoHome-0.2.268.apk", r.apkUrl)
        assertEquals("## What's new\n- Faster face check\n- Fixes the snooze label", r.notes)
    }

    @Test fun shaIsCaseInsensitive() {
        val r = ReleaseParser.parse(release(body(""" {"versionCode":268,"apk":"EchoHome-0.2.268.apk","sha256":"${sha.uppercase()}"} """), JSONArray().put(asset(digest = null))))
        assertEquals(sha, r.sha256)
        assertEquals("0.2.268", r.versionName) // from the tag
        assertEquals(1234L, r.size) // from the asset
    }

    @Test fun refusesWithoutUpdateInfo() = fails(release(body = "Just notes"), "no EchoHome update info")
    @Test fun refusesBrokenInfo() = fails(release(body = "<!-- echohome-update {not json -->"), "no EchoHome update info")
    @Test fun refusesMissingVersionCode() = fails(release(body("""{"apk":"EchoHome-0.2.268.apk","sha256":"$sha"}""")), "versionCode")
    @Test fun refusesBadSha() = fails(release(body("""{"versionCode":268,"apk":"EchoHome-0.2.268.apk","sha256":"1234"}""")), "SHA-256")
    @Test fun refusesMissingAsset() = fails(release(assets = JSONArray().put(asset(name = "other.apk"))), "missing")
    @Test fun refusesSizeMismatch() = fails(release(assets = JSONArray().put(asset(size = 999))), "size")
    @Test fun refusesDigestMismatch() = fails(release(assets = JSONArray().put(asset(digest = "sha256:" + "cd".repeat(32)))), "GitHub")
    @Test fun refusesPrerelease() = fails(release { it.put("prerelease", true) }, "prerelease")
    @Test fun refusesNonJson() = fails("<html>", "isn't JSON")

    @Test fun notesAreCutAndCleaned() {
        val long = "x".repeat(5000)
        val n = ReleaseParser.notes("$long\n<!-- echohome-update {} -->")
        assertTrue(n.length <= ReleaseParser.MAX_NOTES)
        assertTrue(n.endsWith("…"))
        assertFalse(ReleaseParser.notes("a\n\n\n\nb\nSHA-256: x").contains("SHA"))
        assertEquals("a\n\nb", ReleaseParser.notes("a\r\n\r\n\r\n\r\nb"))
    }

    @Test fun releaseJsonRoundTrip() {
        val r = ReleaseParser.parse(release())
        assertEquals(r, Release.fromJson(JSONObject(r.toJson().toString())))
    }
}
