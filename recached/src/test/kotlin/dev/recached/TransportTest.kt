package dev.recached

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
            assertTrue(peer.next().contains("DELTA"))
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
            peer.socket.send(resp("keychange", "greeting", "hello"))
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
            first.next() // CLIENT DELTA ON
            cache.set("a", "1")
            assertTrue(first.next().contains("SET"))
            first.socket.close(1001, "going away") // never replied

            second.awaitOpen()
            second.next()
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
