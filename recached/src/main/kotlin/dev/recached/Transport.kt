package dev.recached

import dev.recached.ffi.FrameSink
import dev.recached.ffi.RecachedClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Owns the WebSocket and drives the core through it: opens the socket, hands
 * the core a sink on open, feeds it every frame, and reconnects after a drop
 * with the backoff the core computes.
 *
 * Every call into the core that depends on which socket is current runs under
 * [lock], checked against [generation], so a late callback from a socket
 * already replaced or closed changes nothing. The lock order is always this
 * lock, then the core's — the core never calls back into this class.
 */
internal class Transport(
    private val client: RecachedClient,
    private val url: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Keys a server frame changed; called outside [lock]. */
    private val onChanged: (List<String>) -> Unit,
    /** A frame changed something that could not be saved, so its keys are unknown. */
    private val onUnknownChange: () -> Unit,
    /** The server refused a queued write for good; called outside [lock]. */
    private val onRefused: (RefusedWrite) -> Unit,
    /**
     * The pending-write count may have moved. Called under [lock], so the core
     * is still open: it may read the core, and must not wait on anything else.
     */
    private val onAcknowledged: () -> Unit,
) {
    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0L
    private var retry: Job? = null
    private var closed = false

    private val mutableState = MutableStateFlow(ConnectionState.CONNECTING)
    val state: StateFlow<ConnectionState> get() = mutableState

    fun start() = connect()

    /** Connect now unless a socket is already open or opening. */
    fun reconnectNow() = connect()

    /** Close the socket for good; no further attempts are made. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            retry?.cancel()
            generation++
            socket?.close(NORMAL_CLOSURE, null)
            socket = null
            client.connectionClosed()
            mutableState.value = ConnectionState.OFFLINE
        }
    }

    private fun connect() {
        synchronized(lock) {
            if (closed || socket != null) return
            retry?.cancel()
            retry = null
            val current = ++generation
            mutableState.value = ConnectionState.CONNECTING
            // Asynchronous: the listener's callbacks arrive on OkHttp's thread
            // and wait for this lock.
            socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(current))
        }
    }

    private inner class Listener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!isCurrent()) return
                // Sends the session and every queued write through the sink.
                client.connectionOpened(Sink(webSocket))
                mutableState.value = ConnectionState.CONNECTED
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = receive(webSocket, text.encodeToByteArray())

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(webSocket, bytes.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()

        private fun receive(webSocket: WebSocket, frame: ByteArray) {
            // Every call into the core stays under the lock, behind the
            // isCurrent check: once close() has run, the core may be destroyed.
            val outcome = synchronized(lock) {
                if (!isCurrent()) return
                val outcome = try {
                    client.frameReceived(frame)
                } catch (_: dev.recached.ffi.RecachedException) {
                    // Applied in memory but not saved, and its keys are lost
                    // with the error: let every observer re-read.
                    null
                }
                onAcknowledged()
                outcome
            }
            if (outcome == null) {
                onUnknownChange()
            } else {
                onChanged(outcome.changedKeys)
                outcome.refused?.let { onRefused(RefusedWrite(it.keys, it.reason)) }
                // Reply matching can no longer be trusted, or the server
                // deferred a write: the reconnect rebuilds the one and replays
                // the other.
                if (outcome.reconnect) webSocket.cancel()
            }
        }

        private fun dropped() {
            synchronized(lock) {
                if (!isCurrent()) return
                socket = null
                val wait = client.connectionClosed().toLong()
                if (closed) return
                mutableState.value = ConnectionState.WAITING
                retry = scope.launch {
                    delay(wait)
                    connect()
                }
            }
        }

        private fun isCurrent() = !closed && generation == this@Transport.generation
    }

    /**
     * Where the core sends frames. Called under the core's lock, so it must
     * only queue: OkHttp's `send` does exactly that, in call order. Text when
     * the frame is valid UTF-8, binary otherwise, as the server expects.
     */
    private class Sink(private val webSocket: WebSocket) : FrameSink {
        override fun send(frame: ByteArray) {
            val text = frame.decodeUtf8OrNull()
            if (text != null) webSocket.send(text) else webSocket.send(frame.toByteString())
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
