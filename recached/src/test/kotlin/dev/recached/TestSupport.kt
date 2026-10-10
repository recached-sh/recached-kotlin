package dev.recached

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

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

/**
 * A real recached-server, started from the binary scripts/build-engine.sh
 * staged, on free ports in a temporary directory so it never touches anyone's
 * data. The Gradle test task passes its path; where there is none — on a
 * device — the live tests are skipped.
 */
internal class LiveServer : AutoCloseable {
    private val workDir: File = tempDir("recached-server")
    private val process: Process
    val url: String

    init {
        val binary = System.getProperty("recached.server")?.let(::File)
        assumeTrue("no recached-server binary (live tests run on the JVM only)", binary?.canExecute() == true)
        val tcpPort = freePort()
        val wsPort = freePort()
        url = "ws://127.0.0.1:$wsPort"
        process = ProcessBuilder(binary!!.path)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(File(workDir, "server.log"))
            .apply {
                // The server binds every interface by default; a test server
                // has no business on the LAN.
                environment()["RECACHED_BIND"] = "127.0.0.1"
                environment()["RECACHED_PORT"] = "$tcpPort"
                environment()["RECACHED_WS_PORT"] = "$wsPort"
                environment()["RECACHED_METRICS_PORT"] = "0"
                environment()["RECACHED_SAVE_INTERVAL"] = "0"
            }
            .start()
        eventually(10_000, "recached-server to listen on $wsPort") {
            check(process.isAlive) { "recached-server exited: ${File(workDir, "server.log").readText()}" }
            runCatching { java.net.Socket("127.0.0.1", wsPort).close() }.isSuccess
        }
    }

    override fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
        workDir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
