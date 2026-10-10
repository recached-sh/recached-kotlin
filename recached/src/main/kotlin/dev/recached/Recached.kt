package dev.recached

import dev.recached.ffi.ClientConfig
import dev.recached.ffi.RecachedClient
import dev.recached.ffi.anyKeyMatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Recached cache on the device: a local copy of the keys you [watch], kept in
 * sync with the server and saved to disk.
 *
 * - **Reads** ([get], [getString], [getMatching], …) come from local memory.
 *   They never wait on the network, so they are safe on the main thread.
 * - **Writes** ([set], [delete], [incrBy], …) apply locally, are saved together
 *   with their place in the outbox before the call returns, and go to the
 *   server now or after the next reconnect. Each reaches the server exactly
 *   once, even if the app is killed in between.
 * - **Observers** ([observe], [observeMatching]) emit the current value and
 *   then every change, from this device or any other. A write to one key
 *   wakes only that key's observers.
 *
 * Open one per database file and keep it for the life of the process; on
 * Android, use `Recached.open(context, …)`. [close] it to stop syncing.
 */
public class Recached private constructor(
    private val client: RecachedClient,
    config: RecachedConfig,
    http: OkHttpClient,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = ChangeBus(::anyKeyMatches)
    private val mutablePending = MutableStateFlow(client.pendingWrites().toLong())
    private val mutableRefused =
        MutableSharedFlow<RefusedWrite>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pendingLock = Any()
    private val closeHooks = mutableListOf<AutoCloseable>()

    private val transport: Transport? = config.url?.let { url ->
        Transport(
            client = client,
            url = url,
            http = http,
            scope = scope,
            onChanged = bus::publish,
            onUnknownChange = bus::invalidateAll,
            onRefused = { mutableRefused.tryEmit(it) },
            onAcknowledged = ::refreshPending,
        )
    }

    /** Where the connection stands. Always [ConnectionState.OFFLINE] without a URL. */
    public val connectionState: StateFlow<ConnectionState> =
        transport?.state ?: MutableStateFlow(ConnectionState.OFFLINE)

    /** Writes not yet acknowledged by the server, including ones from earlier runs. */
    public val pendingWrites: StateFlow<Long> get() = mutablePending

    /**
     * Writes the server refused for good — a key outside the sync token's
     * scope or read-only to it, a wrong type — which left the queue without
     * reaching the server. Collect it for the life of the cache: refusals made
     * while nothing collects are not kept, beyond a buffer of the last 64.
     *
     * A refusal that can clear, such as a sync token the server has not
     * accepted yet, is not reported: the write stays queued and is retried on
     * every reconnect, with a growing backoff, until it is accepted.
     */
    public val refusedWrites: SharedFlow<RefusedWrite> get() = mutableRefused

    init {
        transport?.start()
    }

    // ── reads: local memory ──────────────────────────────────────────────

    /** The value's bytes, or `null` when absent, expired, or a collection. */
    public fun get(key: String): ByteArray? = client.get(key)

    /**
     * The value as text, or `null` when absent.
     *
     * @throws RecachedException.NotUtf8 when the value is binary.
     */
    public fun getString(key: String): String? = core { client.getString(key) }

    /** JSON at [path] (the whole document by default), serialised. */
    public fun getJson(key: String, path: String? = null): String? = client.getJson(key, path)

    public fun exists(key: String): Boolean = client.exists(key)

    /** Seconds until the key expires: `-1` without an expiry, `-2` when absent. */
    public fun ttl(key: String): Long = client.ttl(key)

    /** Every local key matching a glob [pattern] (`*`, `?`), sorted by key. */
    public fun getMatching(pattern: String): List<Entry> =
        client.getMatching(pattern).map { Entry(it.key, it.value) }

    // ── writes: local, durable, then synced ──────────────────────────────

    public fun set(key: String, value: String): Unit = set(key, value.encodeToByteArray())

    public fun set(key: String, value: ByteArray): Unit = write(key) { client.set(key, value) }

    /** Set a value that expires after [ttl], rounded up to whole seconds. */
    public fun set(key: String, value: String, ttl: Duration): Unit = set(key, value.encodeToByteArray(), ttl)

    /** Set a value that expires after [ttl], rounded up to whole seconds. */
    public fun set(key: String, value: ByteArray, ttl: Duration) {
        require(ttl.isPositive()) { "ttl must be positive" }
        write(key) { client.setEx(key, value, ttl.ceilSeconds().toULong()) }
    }

    /** Delete a key. Returns whether it existed locally. */
    public fun delete(key: String): Boolean = write(key) { client.del(key) }

    /**
     * Add [delta] to an integer counter and return the new local value.
     *
     * Queued offline as a delta, so it merges with other clients' increments
     * on reconnect instead of overwriting them.
     */
    public fun incrBy(key: String, delta: Long = 1): Long = write(key) { client.incrBy(key, delta) }

    /** Set JSON at [path] (`$`, the whole document, by default). */
    public fun jsonSet(key: String, json: String, path: String = "$"): Unit =
        write(key) { client.jset(key, path, json) }

    /** Apply an RFC 7386 JSON Merge Patch to the whole document. */
    public fun jsonMerge(key: String, patch: String): Unit = write(key) { client.jmerge(key, patch) }

    private inline fun <T> write(key: String, block: () -> T): T =
        try {
            core(block)
        } finally {
            // Also after a Storage error, which leaves the change applied in
            // memory; a refused command changed nothing, and observers re-read
            // the same value and emit nothing.
            bus.publish(listOf(key))
            refreshPending()
        }

    // ── sync ─────────────────────────────────────────────────────────────

    /**
     * Keep every key matching [pattern] in sync: the server sends their
     * current state, then every change, and after each reconnect a fresh
     * snapshot that also removes keys deleted meanwhile.
     *
     * Not remembered across [open]s; call it again at startup.
     */
    public fun watch(pattern: String): Unit = client.watch(pattern)

    /** Stop syncing [pattern]. Local copies of its keys stay readable. */
    public fun unwatch(pattern: String): Unit = client.unwatch(pattern)

    /** Replace the sync token, e.g. after refreshing it. Sent now and on every reconnect. */
    public fun setSyncToken(token: String): Unit = client.setSyncToken(token)

    /**
     * Connect now instead of waiting out the backoff, when a socket is not
     * already open. On Android this happens by itself when a network becomes
     * available and when the app returns to the foreground.
     */
    public fun reconnectNow() {
        transport?.reconnectNow()
    }

    // ── observing ────────────────────────────────────────────────────────

    /**
     * The value of [key] now, then after every change to it — from this
     * device, from the server, or from a reconnect snapshot. Equal consecutive
     * values are not repeated.
     */
    public fun observe(key: String): Flow<ByteArray?> =
        invalidations { bus.onKey(key, it) }
            .map { get(key) }
            .distinctUntilChanged { old, new -> old.contentEquals(new) }

    /** [observe] as text; a binary value reads as `null`. */
    public fun observeString(key: String): Flow<String?> =
        invalidations { bus.onKey(key, it) }
            .map { get(key)?.decodeUtf8OrNull() }
            .distinctUntilChanged()

    /** [getMatching] for [pattern] now, then after every change to a matching key. */
    public fun observeMatching(pattern: String): Flow<List<Entry>> =
        invalidations { bus.onPattern(pattern, it) }
            .map { getMatching(pattern) }
            .distinctUntilChanged()

    /**
     * One signal on collection, then one per change. Registered before the
     * first signal, so a change between the first read and the registration
     * cannot be missed; conflated, so a slow collector re-reads once rather
     * than once per change, and never misses the last one.
     */
    private fun invalidations(register: (() -> Unit) -> AutoCloseable): Flow<Unit> =
        callbackFlow {
            val registration = register { trySend(Unit) }
            send(Unit)
            awaitClose { registration.close() }
        }.buffer(Channel.CONFLATED)

    // ── lifecycle ────────────────────────────────────────────────────────

    internal fun onClose(hook: AutoCloseable) {
        synchronized(closeHooks) { closeHooks += hook }
    }

    /** Stop syncing and release the database. Queued writes stay on disk for the next open. */
    override fun close() {
        synchronized(closeHooks) {
            closeHooks.forEach { it.close() }
            closeHooks.clear()
        }
        transport?.close()
        scope.cancel()
        client.close()
    }

    /** Read and publish as one step, so a slower caller cannot publish an older count. */
    private fun refreshPending() {
        synchronized(pendingLock) {
            mutablePending.value = client.pendingWrites().toLong()
        }
    }

    public companion object {
        /**
         * Open the cache stored in [file], creating it if needed, and start
         * syncing when [config] has a URL.
         *
         * Everything saved by an earlier run is readable at once, before any
         * connection, and writes still waiting for the server go out on the
         * first one.
         *
         * @param http the client the WebSocket is opened with; share the app's
         *   to reuse its connection pool, interceptors and TLS settings.
         */
        @JvmStatic
        @JvmOverloads
        public fun open(file: File, config: RecachedConfig = RecachedConfig(), http: OkHttpClient = defaultHttp): Recached {
            file.parentFile?.mkdirs()
            val client = core {
                RecachedClient.open(
                    file.path,
                    ClientConfig(
                        password = config.password,
                        syncToken = config.syncToken,
                        maxPendingWrites = config.maxPendingWrites?.toUInt(),
                    ),
                )
            }
            return Recached(client, config, http)
        }

        /**
         * Pings keep NAT mappings alive and notice a socket the network
         * dropped without a close — otherwise a phone that changed networks
         * can sit on a dead connection until TCP gives up.
         */
        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
    }
}

/** Whole seconds, rounded up: a positive duration under a second is one second, never zero. */
internal fun Duration.ceilSeconds(): Long {
    val whole = inWholeSeconds
    return if (whole.seconds < this) whole + 1 else whole
}
