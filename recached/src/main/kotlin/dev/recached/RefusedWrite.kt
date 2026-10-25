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
 * @property unreconciled those of [keys] no watched pattern covers: their
 *   local value is the refused one and nothing will correct it, so show the
 *   write as failed and rewrite or re-read them.
 */
public data class RefusedWrite(
    public val keys: List<String>,
    public val reason: String,
    public val unreconciled: List<String> = emptyList(),
)
