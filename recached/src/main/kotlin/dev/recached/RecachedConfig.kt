package dev.recached

/**
 * How a [Recached] connects.
 *
 * @property url the server's WebSocket endpoint, e.g. `wss://cache.example.com`.
 *   `null` keeps the cache local: reads and writes work, and writes queue
 *   until a later open supplies a URL.
 * @property password sent as `AUTH` each time the socket opens.
 * @property syncToken sent as `SYNC TOKEN` each time the socket opens; replace
 *   it later with [Recached.setSyncToken].
 * @property maxPendingWrites cap on writes waiting for the server. Past it a
 *   write throws [RecachedException.OutboxFull] and changes nothing. `null`
 *   keeps the core's default of 10,000.
 * @property evictOldestWhenFull past [maxPendingWrites], drop the oldest queued
 *   write instead of refusing the new one, as recached-kotlin 0.1 did. A
 *   dropped write never reaches the server.
 */
public data class RecachedConfig(
    val url: String? = null,
    val password: String? = null,
    val syncToken: String? = null,
    val maxPendingWrites: Int? = null,
    val evictOldestWhenFull: Boolean = false,
) {
    init {
        require(maxPendingWrites == null || maxPendingWrites > 0) { "maxPendingWrites must be positive" }
    }

    // A data class prints every property; the credentials must not reach logs.
    override fun toString(): String =
        "RecachedConfig(url=$url, password=${password?.let { "***" }}, " +
            "syncToken=${syncToken?.let { "***" }}, maxPendingWrites=$maxPendingWrites, " +
            "evictOldestWhenFull=$evictOldestWhenFull)"
}
