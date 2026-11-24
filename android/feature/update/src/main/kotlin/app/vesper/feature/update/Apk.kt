package app.vesper.feature.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Download with resume, then SHA-256. Streams to disk, never into memory. */
internal object Download {
    class Failed(message: String) : Exception(message)

    /** Returns the complete file (its SHA-256 is not checked here). [progress] gets bytes so far. */
    fun fetch(fetcher: Fetcher, url: String, size: Long, part: File, progress: (Long) -> Unit = {}): File {
        if (size > 0 && part.length() > size) part.delete()
        val have = part.length()
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        fetcher.get(url, headers).use { r ->
            val append = when (r.code) {
                206 -> {
                    val range = r.header("Content-Range") ?: ""
                    if (!range.startsWith("bytes $have-")) throw Failed("Download resumed at the wrong place")
                    true
                }
                200 -> false
                else -> throw Failed("Download failed (HTTP ${r.code})")
            }
            val body = r.body ?: throw Failed("Download had no body")
            var done = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (size > 0 && done > size) throw Failed("Download is bigger than the release says")
                    if (done - lastReport >= 1_000_000) { lastReport = done; progress(done) }
                }
                out.fd.sync()
            }
        }
        if (size > 0 && part.length() != size) throw Failed("Download incomplete (${part.length()} of $size bytes)")
        return part
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Checks an APK before it may be installed: package, version, and the signer of the installed app. */
internal object ApkCheck {
    /** Returns null when [apk] is OK to install, else a short reason. */
    fun problem(ctx: Context, apk: File, expectedVersion: Long): String? {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
            ?: return "The download isn't a valid APK"
        if (archive.packageName != ctx.packageName) return "The APK is for another app (${archive.packageName})"
        if (archive.longVersionCode != expectedVersion) return "The APK is version ${archive.longVersionCode}, the release says $expectedVersion"
        val theirs = signers(archive) ?: return "The APK isn't signed"
        @Suppress("DEPRECATION")
        val mine = signers(pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES))
            ?: return "Can't read this app's own signature"
        if (theirs != mine) return "The APK is signed with a different key"
        return null
    }

    /** SHA-256 of each current signing certificate. */
    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String>? {
        val certs: Array<Signature>? = p.signingInfo?.apkContentsSigners ?: p.signatures
        if (certs.isNullOrEmpty()) return null
        return certs.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

/** Silent installs through PackageInstaller (needs INSTALL_PACKAGES, a privileged permission). */
internal object Installer {
    const val TAG = "VesperUpdate"
    // PackageManager.INSTALL_REQUEST_DOWNGRADE / INSTALL_ALLOW_DOWNGRADE (hidden).
    private const val INSTALL_REQUEST_DOWNGRADE = 0x00000080
    private const val INSTALL_ALLOW_DOWNGRADE = 0x00100000

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED

    /**
     * Writes [apk] into a session and commits it. For our own package the system kills this
     * process when the install completes, so the result usually reaches the *new* version (or no
     * one); the caller records what it is doing in the state file first.
     */
    fun install(ctx: Context, apk: File, downgrade: Boolean) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        if (downgrade) requestDowngrade(params)
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                FileInputStream(apk).use { input ->
                    s.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        s.fsync(out)
                    }
                }
                val intent = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(UpdateReceiver.EXTRA_DOWNGRADE, downgrade)
                // Mutable: PackageInstaller fills in the status extras.
                val sender = PendingIntent.getBroadcast(ctx, if (downgrade) 2 else 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                Log.i(TAG, "committing session $id (${apk.length()} bytes, downgrade=$downgrade)")
                s.commit(sender.intentSender)
            }
        } catch (e: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            throw e
        }
    }

    /** SessionParams.setRequestDowngrade is a SystemApi (hidden from apps); the app is a system app with usesNonSdkApi. */
    private fun requestDowngrade(p: PackageInstaller.SessionParams) {
        try {
            p.javaClass.getMethod("setRequestDowngrade", Boolean::class.javaPrimitiveType).invoke(p, true)
        } catch (e: Exception) { Log.w(TAG, "setRequestDowngrade: $e") }
        try {
            val f = p.javaClass.getField("installFlags")
            f.setInt(p, f.getInt(p) or INSTALL_REQUEST_DOWNGRADE or INSTALL_ALLOW_DOWNGRADE)
        } catch (e: Exception) { Log.w(TAG, "installFlags: $e") }
    }

    /** Copies the installed APK (the rollback target) to [dest]. */
    fun copyInstalled(ctx: Context, dest: File) {
        val src = File(ctx.applicationInfo.sourceDir)
        val tmp = File(dest.path + ".tmp")
        FileInputStream(src).use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024); out.fd.sync() } }
        if (!tmp.renameTo(dest)) throw IOException("rename failed")
    }
}
