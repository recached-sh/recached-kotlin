package dev.recached

import dev.recached.ffi.ClientConfig
import dev.recached.ffi.RecachedClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The transport against a scripted server: what goes out on connect, what a
 * server frame does, and how drops and bad frames are recovered from.
 */
class TransportTest {
    private val db = TempDb()
    private val server = MockWebServer()

    /** One accepted WebSocket on the server side, recording what the client sent. */
    private class Peer(private val autoReply: Boolean) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()
        lateinit var socket: WebSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(text)
            if (autoReply) webSocket.send(OK)
        }

        // Complete the close handshake, as a real server does; otherwise the
        // session never ends and MockWebServer cannot shut down.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        fun next(): String = checkNotNull(received.poll(5, TimeUnit.SECONDS)) { "the client sent nothing" }

        fun awaitOpen() = checkNotNull(opened.poll(5, TimeUnit.SECONDS)) { "the client never connected" }

        /** Read the four protocol opt-ins every session starts with. */
        fun skipOptIns() {
            assertTrue(next().contains("DELTA"))
            assertTrue(next().contains("EXPIRY"))
            assertTrue(next().contains("PUSHFRAMES"))
            assertTrue(next().contains("DEDUPBARRIER"))
        }
    }

    private fun accept(autoReply: Boolean = true): Peer =
        Peer(autoReply).also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    private fun config() = RecachedConfig(url = server.url("/").toString().replaceFirst("http", "ws"))

    @Before
    fun start() = server.start()

    @After
    fun cleanup() {
        server.close()
        db.close()
    }

    @Test
    fun queuedWritesFollowTheSessionAndRetireOnReply() {
        db.open().use { it.set("a", "1") } // queued offline

        val peer = accept()
        db.open(config()).use { cache ->
            peer.awaitOpen()
            peer.skipOptIns()
            val write = peer.next()
            assertTrue(write, write.contains("DEDUP") && write.contains("SET"))
            eventually(message = "the reply to retire the write") { cache.pendingWrites.value == 0L }
            assertEquals(ConnectionState.CONNECTED, cache.connectionState.value)
        }
    }

    @Test
    fun aServerFrameReachesObservers() {
        val peer = accept()
        db.open(config()).use { cache ->
            peer.awaitOpen()
            peer.socket.send(push("keychange", "greeting", "hello"))
            runBlocking {
                withTimeout(5_000) { cache.observeString("greeting").first { it == "hello" } }
            }
        }
    }

    @Test
    fun aDroppedConnectionReconnectsAndReplaysWhatWasNotAcknowledged() {
        val first = accept(autoReply = false)
        val second = accept()
        db.open(config()).use { cache ->
            first.awaitOpen()
            first.skipOptIns()
            cache.set("a", "1")
            assertTrue(first.next().contains("SET"))
            first.socket.close(1001, "going away") // never replied

            second.awaitOpen()
            second.skipOptIns()
            assertTrue("the unacknowledged write is replayed", second.next().contains("SET"))
            eventually { cache.pendingWrites.value == 0L }
        }
    }

    @Test
    fun anUnparseableFrameDropsTheSocketAndReconnects() {
        val first = accept()
        val second = accept()
        db.open(config()).use { cache ->
            first.awaitOpen()
            first.socket.send("*not resp")
            second.awaitOpen()
            eventually { cache.connectionState.value == ConnectionState.CONNECTED }
        }
    }

    @Test
    fun aWriteRefusedForGoodIsReportedAndLeavesTheQueue() {
        val peer = accept(autoReply = false)
        db.open(config()).use { cache ->
            peer.awaitOpen()
            peer.skipOptIns()
            repeat(OPT_INS) { peer.socket.send(OK) }
            runBlocking {
                val refused = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) { cache.refusedWrites.first() }
                }
                cache.set("catalog:1", "x")
                assertTrue(peer.next().contains("SET"))
                peer.socket.send("-NOSCOPE key 'catalog:1' is read-only on this connection\r\n")
                assertEquals(
                    RefusedWrite(
                        listOf("catalog:1"),
                        "NOSCOPE key 'catalog:1' is read-only on this connection",
                        unreconciled = listOf("catalog:1"),
                    ),
                    refused.await(),
                )
            }
            eventually(message = "the refused write to leave the queue") { cache.pendingWrites.value == 0L }
        }
    }

    @Test
    fun aWriteDeferredByTheServerIsReplayedOnTheNextConnection() {
        val first = accept(autoReply = false)
        val second = accept()
        db.open(config()).use { cache ->
            first.awaitOpen()
            first.skipOptIns()
            repeat(OPT_INS) { first.socket.send(OK) }
            cache.set("a", "1")
            assertTrue(first.next().contains("SET"))
            first.socket.send("-NOSCOPE send SYNC TOKEN <token> before issuing commands\r\n")

            second.awaitOpen()
            second.skipOptIns()
            assertTrue("the deferred write is replayed", second.next().contains("SET"))
            eventually { cache.pendingWrites.value == 0L }
        }
    }

    @Test
    fun closingWhileAFrameIsHandledNeverCallsTheClosedCore() {
        val peer = accept()
        val client = RecachedClient.open(db.file.path, ClientConfig())
        val scope = CoroutineScope(SupervisorJob())
        val inCallback = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val transport = Transport(
            client = client,
            url = config().url!!,
            http = OkHttpClient(),
            scope = scope,
            onChanged = { keys ->
                if ("k" in keys) {
                    inCallback.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            },
            onUnknownChange = {},
            onRefused = {},
            onAcknowledged = {
                try {
                    client.pendingWrites()
                } catch (t: Throwable) {
                    failures += t
                }
            },
            onSyncChanged = {
                try {
                    client.syncStatus()
                } catch (t: Throwable) {
                    failures += t
                }
            },
        )
        transport.start()
        peer.awaitOpen()
        peer.socket.send(push("keychange", "k", "v"))
        assertTrue("the frame reached its callback", inCallback.await(5, TimeUnit.SECONDS))

        // What Recached.close() does, while that callback is still running.
        transport.close()
        client.close()
        release.countDown()
        Thread.sleep(300)
        scope.cancel()
        assertTrue("the core was called after close: $failures", failures.isEmpty())
    }

    @Test
    fun closingStopsReconnecting() {
        val peer = accept()
        val cache = db.open(config())
        peer.awaitOpen()
        cache.close()
        assertEquals(ConnectionState.OFFLINE, cache.connectionState.value)
        val before = server.requestCount
        Thread.sleep(1_000) // longer than the first backoff
        assertEquals(before, server.requestCount)
    }
}

/** Session opt-ins every open sends before anything else. */
private const val OPT_INS = 4

/**
 * A server frame as a RESP3 push: these scripted peers answer
 * `CLIENT PUSHFRAMES ON` with `+OK`, as a 0.5 server does, so their
 * keychanges must be pushes too — a plain array would be a reply.
 */
private fun push(vararg parts: String): String = ">" + resp(*parts).drop(1)
