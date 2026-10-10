package dev.recached

/**
 * One key and its value, from [Recached.getMatching] or
 * [Recached.observeMatching].
 *
 * @property value the bytes of a string value, or `null` for a collection,
 *   which has no single-value form.
 */
public class Entry(public val key: String, public val value: ByteArray?) {
    /** The value as text, or `null` when absent or not valid UTF-8. */
    public val valueString: String? get() = value?.decodeUtf8OrNull()

    // Content equality, so a list of entries compares by what it holds and
    // `distinctUntilChanged` can suppress an unchanged re-read.
    override fun equals(other: Any?): Boolean =
        other is Entry && key == other.key && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()

    override fun toString(): String = "Entry(key=$key, value=${valueString ?: value?.let { "${it.size} bytes" }})"
}
