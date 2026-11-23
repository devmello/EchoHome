package app.vesper.feature.lists

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.vesper.core.model.ListAddResult
import app.vesper.core.model.ListItem
import app.vesper.core.model.ListNames
import app.vesper.core.model.ListStore
import app.vesper.core.model.ListText
import app.vesper.feature.lists.engine.Codec
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [ListStore] (services.lists): [ListText] rules + filesDir/lists.json (AtomicFile, a few
 * KB, written on every change). Main thread only.
 */
class RealListStore(ctx: Context) : ListStore {
    private val file = AtomicFile(File(ctx.filesDir, "lists.json"))
    private val _lists = MutableStateFlow(load())
    override val lists: StateFlow<Map<String, List<ListItem>>> = _lists.asStateFlow()

    private inline fun <T> edit(list: String, f: (List<ListItem>) -> Pair<List<ListItem>, T>): T {
        val name = ListNames.normalize(list)
        val m = LinkedHashMap(_lists.value)
        val (items, result) = f(m[name].orEmpty())
        val capped = items.takeLast(MAX_ITEMS)
        if (capped.isEmpty()) m.remove(name) else m[name] = capped
        if (m != _lists.value) { _lists.value = m; save(m) }
        return result
    }

    override fun add(list: String, texts: List<String>): ListAddResult = edit(list) { ListText.add(it, texts, System.currentTimeMillis()) }
    override fun remove(list: String, texts: List<String>): List<ListItem> = edit(list) { ListText.remove(it, texts) }
    override fun setChecked(list: String, id: String, checked: Boolean) = edit(list) { l -> l.map { if (it.id == id) it.copy(checked = checked) else it } to Unit }
    override fun delete(list: String, id: String) = edit(list) { l -> l.filterNot { it.id == id } to Unit }
    override fun clear(list: String, onlyChecked: Boolean): Int = edit(list) { l ->
        val kept = if (onlyChecked) l.filterNot { it.checked } else emptyList()
        kept to (l.size - kept.size)
    }

    private fun load(): Map<String, List<ListItem>> = try {
        if (file.baseFile.exists()) Codec.listsFromJson(String(file.readFully())) else emptyMap()
    } catch (e: Exception) {
        Log.e(ListsFeature.TAG, "lists.json unreadable, starting empty", e)
        emptyMap()
    }

    private fun save(m: Map<String, List<ListItem>>) {
        val out = file.startWrite()
        try {
            out.write(Codec.listsToJson(m).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(ListsFeature.TAG, "Saving lists failed", e)
        }
    }

    companion object {
        /** Per list; the oldest go first beyond this. */
        const val MAX_ITEMS = 200
    }
}
