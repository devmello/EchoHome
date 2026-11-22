package com.echobedside.feature.camera.profiles

import android.util.Log
import com.echobedside.feature.camera.face.FaceTemplates
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Face templates on disk, one encrypted file per person in [dir] (the app's no-backup dir, so
 * they never leave the device in a backup). Only embeddings: no images, no crops.
 */
class TemplateStore(private val dir: File, private val box: SecretBox) {
    private val cache = HashMap<String, FaceTemplates?>()

    @Synchronized fun save(t: FaceTemplates) {
        dir.mkdirs()
        val plain = ByteArrayOutputStream().also { bo ->
            DataOutputStream(bo).use { o ->
                o.writeUTF(t.embedderId)
                o.writeInt(t.vectors.size)
                o.writeInt(t.vectors.firstOrNull()?.size ?: 0)
                for (v in t.vectors) for (x in v) o.writeFloat(x)
            }
        }.toByteArray()
        val f = file(t.profileId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(box.seal(plain, aad(t.profileId)))
        plain.fill(0)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        cache[t.profileId] = t
    }

    /** null when nobody's enrolled under [profileId], or the file can't be opened (wrong key, tampered). */
    @Synchronized fun load(profileId: String): FaceTemplates? = cache.getOrPut(profileId) {
        val f = file(profileId)
        if (!f.exists()) return@getOrPut null
        try {
            val plain = box.open(f.readBytes(), aad(profileId))
            DataInputStream(ByteArrayInputStream(plain)).use { i ->
                val emb = i.readUTF()
                val n = i.readInt()
                val dim = i.readInt()
                require(n in 0..32 && dim in 0..65536)
                FaceTemplates(profileId, emb, List(n) { FloatArray(dim) { i.readFloat() } })
            }.also { plain.fill(0) }
        } catch (e: Exception) {
            Log.w(TAG, "templates for $profileId unreadable: ${e.message}")
            null
        }
    }

    @Synchronized fun delete(profileId: String) {
        file(profileId).delete()
        cache.remove(profileId)
    }

    @Synchronized fun has(profileId: String) = file(profileId).exists()

    private fun file(profileId: String) = File(dir, "${safe(profileId)}.tpl")

    companion object {
        const val TAG = "EchoFaces"
        fun aad(profileId: String) = "echobedside.face:$profileId".toByteArray()
        /** Profile ids are ours, but never let one escape the directory. */
        fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }
}
