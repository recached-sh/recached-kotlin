package dev.recached

/**
 * A write the server will never apply, from [Recached.refusedWrites].
 *
 * Its local effect stays: the cache cannot undo it. Keys a [Recached.watch]
 * pattern covers get the server's values back from a fresh snapshot a moment
 * later; any other key keeps the refused value until the app changes it.
 *
 * @property keys the keys the write would have changed.
 * @property reason the server's error, such as
 *   `NOSCOPE key 'k' is read-only on this connection`.
 */
public data class RefusedWrite(public val keys: List<String>, public val reason: String)
