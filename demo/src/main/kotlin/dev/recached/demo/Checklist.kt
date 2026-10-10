package dev.recached.demo

import dev.recached.ConnectionState
import dev.recached.Entry
import dev.recached.Recached
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random

/** One checklist item, stored as a JSON string under `todo:<created>-<random>`. */
data class Item(val key: String, val title: String, val done: Boolean) {
    companion object {
        fun from(entry: Entry): Item? {
            val json = try {
                JSONObject(entry.valueString ?: return null)
            } catch (_: JSONException) {
                return null
            }
            return Item(entry.key, json.optString("title"), json.optBoolean("done"))
        }

        fun json(title: String, done: Boolean): String =
            JSONObject().put("title", title).put("done", done).toString()
    }
}

/**
 * The whole app's data, on top of [Recached]. Every write is local first:
 * it shows at once, is saved before the call returns, and syncs when it can.
 */
class Checklist(private val cache: Recached) {
    /** Items in creation order (keys sort by time), updated on every change from anywhere. */
    val items: Flow<List<Item>> = cache.observeMatching(PATTERN).map { entries -> entries.mapNotNull(Item::from) }

    val connectionState: StateFlow<ConnectionState> = cache.connectionState
    val pendingWrites: StateFlow<Long> = cache.pendingWrites

    fun add(title: String) {
        val text = title.trim()
        if (text.isEmpty()) return
        // Zero-padded time first, so keys sort in creation order across devices.
        val key = "todo:%013d-%04x".format(System.currentTimeMillis(), Random.nextInt(0x10000))
        cache.set(key, Item.json(text, done = false))
    }

    fun toggle(item: Item) = cache.set(item.key, Item.json(item.title, !item.done))

    fun delete(item: Item) {
        cache.delete(item.key)
    }

    companion object {
        const val PATTERN = "todo:*"
    }
}
