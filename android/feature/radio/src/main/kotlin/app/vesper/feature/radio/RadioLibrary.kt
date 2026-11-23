package app.vesper.feature.radio

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Favourites (in the order they were added, newest first) and recently played stations, saved to
 * [file] (`files/radio.json`) on every change, written to a temp file and renamed so a crash never
 * leaves half a file. Main-thread object; the writes are tiny (≤ [MAX_FAVOURITES] + [MAX_RECENT] stations).
 */
class RadioLibrary(private val file: File) {
    private val _favourites = MutableStateFlow<List<Station>>(emptyList())
    private val _recent = MutableStateFlow<List<Station>>(emptyList())
    val favourites: StateFlow<List<Station>> = _favourites.asStateFlow()
    val recent: StateFlow<List<Station>> = _recent.asStateFlow()

    init { load() }

    fun isFavourite(uuid: String) = _favourites.value.any { it.uuid == uuid }

    /** Adds or removes [s]; returns whether it's a favourite now. */
    fun toggleFavourite(s: Station): Boolean {
        val on = !isFavourite(s.uuid)
        setFavourite(s, on)
        return on
    }

    fun setFavourite(s: Station, on: Boolean) {
        val rest = _favourites.value.filterNot { it.uuid == s.uuid }
        _favourites.value = if (on) (listOf(s) + rest).take(MAX_FAVOURITES) else rest
        save()
    }

    /** [s] started playing: first in Recent; a favourite's saved copy is refreshed too. */
    fun played(s: Station) {
        if (s.isDemo) return
        _recent.value = (listOf(s) + _recent.value.filterNot { it.uuid == s.uuid }).take(MAX_RECENT)
        _favourites.value = _favourites.value.map { if (it.uuid == s.uuid) s else it }
        save()
    }

    /** Replace saved copies with fresh ones from the directory (stream URLs move). */
    fun refresh(fresh: List<Station>) {
        if (fresh.isEmpty()) return
        val byId = fresh.associateBy { it.uuid }
        _favourites.value = _favourites.value.map { byId[it.uuid] ?: it }
        _recent.value = _recent.value.map { byId[it.uuid] ?: it }
        save()
    }

    fun clearRecent() { _recent.value = emptyList(); save() }

    /** The best saved match for a spoken name: exact, then prefix, then every word contained. */
    fun match(query: String): Station? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val all = (_favourites.value + _recent.value).distinctBy { it.uuid }
        return all.firstOrNull { norm(it.name) == q }
            ?: all.firstOrNull { norm(it.name).startsWith(q) }
            ?: q.split(' ').filter { it.length > 1 }.takeIf { it.isNotEmpty() }?.let { words ->
                all.firstOrNull { s -> val n = norm(s.name); words.all { it in n } }
            }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            _favourites.value = Station.listFromJson(o.optJSONArray("favourites") ?: JSONArray()).take(MAX_FAVOURITES)
            _recent.value = Station.listFromJson(o.optJSONArray("recent") ?: JSONArray()).take(MAX_RECENT)
        } catch (e: IOException) {
            Log.w(RadioBrowser.TAG, "radio.json unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(RadioBrowser.TAG, "radio.json unreadable: ${e.message}")
        }
    }

    private fun save() {
        val o = JSONObject()
            .put("version", 1)
            .put("favourites", JSONArray(_favourites.value.map(Station::toJson)))
            .put("recent", JSONArray(_recent.value.map(Station::toJson)))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
        } catch (e: IOException) {
            Log.w(RadioBrowser.TAG, "radio.json not saved: ${e.message}")
        }
    }

    companion object {
        const val MAX_FAVOURITES = 40
        const val MAX_RECENT = 12

        fun norm(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
