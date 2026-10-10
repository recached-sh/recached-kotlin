package dev.recached

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Routes changed keys to the listeners of those keys and of patterns that
 * match them.
 *
 * Listeners are invalidation signals, not values: they are told *that* a key
 * changed and re-read it. They must return at once — they run on whichever
 * thread reported the change, the OkHttp reader for server frames and the
 * caller for local writes.
 *
 * @param anyMatches the engine's glob matcher, so routing agrees with the
 *   server about which keys a pattern covers.
 */
internal class ChangeBus(private val anyMatches: (pattern: String, keys: List<String>) -> Boolean) {
    private val keyListeners = ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>()
    private val patternListeners = ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>()

    fun onKey(key: String, listener: () -> Unit): AutoCloseable = add(keyListeners, key, listener)

    fun onPattern(pattern: String, listener: () -> Unit): AutoCloseable = add(patternListeners, pattern, listener)

    fun publish(keys: List<String>) {
        if (keys.isEmpty()) return
        for (key in keys) {
            keyListeners[key]?.forEach { it() }
        }
        // One call into the core per pattern, not per key: a reconnect
        // snapshot can name thousands of keys.
        for ((pattern, listeners) in patternListeners) {
            if (anyMatches(pattern, keys)) listeners.forEach { it() }
        }
    }

    /** Every listener re-reads: for when what changed is not known. */
    fun invalidateAll() {
        keyListeners.values.forEach { set -> set.forEach { it() } }
        patternListeners.values.forEach { set -> set.forEach { it() } }
    }

    private fun add(
        registry: ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>,
        name: String,
        listener: () -> Unit,
    ): AutoCloseable {
        registry.compute(name) { _, set -> (set ?: CopyOnWriteArraySet()).apply { add(listener) } }
        return AutoCloseable {
            registry.computeIfPresent(name) { _, set -> set.apply { remove(listener) }.takeIf { it.isNotEmpty() } }
        }
    }
}
