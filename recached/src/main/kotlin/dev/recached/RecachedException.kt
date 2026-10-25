package dev.recached

import dev.recached.ffi.RecachedException as FfiException

/** Everything a [Recached] call can fail with. */
public sealed class RecachedException(message: String) : Exception(message) {
    /**
     * The on-device database could not be read or written. On a write,
     * nothing happened: the change was not applied, saved or sent.
     */
    public class Storage(message: String) : RecachedException(message)

    /**
     * The engine refused the command — a `WRONGTYPE`, an invalid JSON path, a
     * counter that is not an integer. Nothing was applied or queued.
     */
    public class Command(message: String) : RecachedException(message)

    /** The key holds bytes that are not valid UTF-8; read it with [Recached.get]. */
    public class NotUtf8(public val key: String) : RecachedException("value of \"$key\" is not valid UTF-8")

    /**
     * The write was refused before anything changed: [pending] writes wait
     * for the server and the queue holds [capacity]
     * ([RecachedConfig.maxPendingWrites]). Every queued write is intact;
     * retry once [Recached.pendingWrites] drops.
     */
    public class OutboxFull(public val pending: Long, public val capacity: Long) :
        RecachedException("outbox full: $pending writes pending, capacity $capacity")

    /** This session used every write id it can carry; reopen the cache. Nothing was written. */
    public class WriteIdsExhausted : RecachedException("write ids exhausted; reopen the cache")
}

/** Run a call into the core, rethrowing its errors as this package's. */
internal inline fun <T> core(block: () -> T): T =
    try {
        block()
    } catch (e: FfiException) {
        throw when (e) {
            is FfiException.Storage -> RecachedException.Storage(e.reason)
            is FfiException.Command -> RecachedException.Command(e.reason)
            is FfiException.NotUtf8 -> RecachedException.NotUtf8(e.key)
            is FfiException.OutboxFull -> RecachedException.OutboxFull(e.pending.toLong(), e.capacity.toLong())
            is FfiException.WriteIdsExhausted -> RecachedException.WriteIdsExhausted()
        }
    }
