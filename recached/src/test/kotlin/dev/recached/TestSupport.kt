package dev.recached

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/** A fresh database file in a temporary directory, deleted with it. */
internal class TempDb : AutoCloseable {
    val dir: File = tempDir("recached-test")
    val file: File = File(dir, "cache.db")

    fun open(config: RecachedConfig = RecachedConfig()): Recached = Recached.open(file, config)

    override fun close() {
        dir.deleteRecursively()
    }
}

/** A new empty directory. Not java.nio.file: the suite also runs on API 24 devices. */
internal fun tempDir(prefix: String): File =
    File.createTempFile(prefix, "").apply {
        delete()
        mkdirs()
    }

/** RESP array frame, as the server sends it. */
internal fun resp(vararg parts: String): String =
    buildString {
        append("*${parts.size}\r\n")
        for (p in parts) append("$${p.encodeToByteArray().size}\r\n$p\r\n")
    }

internal const val OK = "+OK\r\n"

/** Poll [condition] until it holds, failing after [timeoutMs]. */
internal fun eventually(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) =
    runBlocking {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(20)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $message", e)
        }
    }
