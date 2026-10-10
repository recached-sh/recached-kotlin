package dev.recached

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Strict UTF-8 decoding: `null` rather than U+FFFD for invalid input. */
internal fun ByteArray.decodeUtf8OrNull(): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
