package com.echobedside.feature.photos.data

import java.io.File
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last album list and each album's asset list, as small JSON files, so the frame can play
 * from the preview cache when the server is unreachable (offline mode).
 */
class MetaStore(private val dir: File) {
    init { dir.mkdirs() }

    fun saveAlbums(albums: List<Album>) = write("albums.json", JSONArray().apply {
        albums.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.assetCount)) }
    }.toString())

    fun loadAlbums(): List<Album>? = read("albums.json")?.let { s ->
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Album(o.getString("id"), o.optString("name"), o.optInt("count")) } }
    }

    fun saveAssets(albumId: String, assets: List<PhotoAsset>) = write(albumFile(albumId), JSONArray().apply {
        assets.forEach {
            put(JSONObject().put("id", it.id).put("t", it.takenAt?.toString() ?: JSONObject.NULL)
                .put("p", it.place ?: JSONObject.NULL).put("w", it.width).put("h", it.height))
        }
    }.toString())

    fun loadAssets(albumId: String): List<PhotoAsset>? = read(albumFile(albumId))?.let { s ->
        val a = JSONArray(s)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PhotoAsset(
                id = o.getString("id"),
                takenAt = if (o.isNull("t")) null else runCatching { LocalDateTime.parse(o.getString("t")) }.getOrNull(),
                place = if (o.isNull("p")) null else o.getString("p"),
                width = o.optInt("w"), height = o.optInt("h"),
            )
        }
    }

    private fun albumFile(id: String) = "album-" + id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"

    private fun write(name: String, s: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(s)
        tmp.renameTo(File(dir, name))
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
}
