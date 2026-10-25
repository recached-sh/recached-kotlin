package dev.recached

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end against a real recached-server: the four things the first
 * release promises, as an app on two devices would see them.
 */
class LiveTest {
    private lateinit var server: LiveServer
    private val phone = TempDb()
    private val tablet = TempDb()

    // In @Before, not a field initializer, so a missing server binary is a
    // skip: JUnit reports an assumption failed in a constructor as a failure.
    @Before
    fun startServer() {
        server = LiveServer()
    }

    @After
    fun cleanup() {
        phone.close()
        tablet.close()
        if (::server.isInitialized) server.close()
    }

    private fun TempDb.connect() = open(RecachedConfig(url = server.url))

    private fun Recached.awaitConnected() =
        eventually(message = "a connection") { connectionState.value == ConnectionState.CONNECTED }

    @Test
    fun aWriteOnOneDeviceUpdatesAnObserverOnAnother() {
        phone.connect().use { a ->
            tablet.connect().use { b ->
                a.watch("note:*")
                a.awaitConnected()
                b.awaitConnected()

                b.set("note:1", "from the tablet")
                runBlocking {
                    withTimeout(5_000) { a.observeString("note:1").first { it == "from the tablet" } }
                }
                eventually(message = "the write to be acknowledged") { b.pendingWrites.value == 0L }
            }
        }
    }

    @Test
    fun writesMadeOfflineReachTheServerOnConnect() {
        phone.open().use { offline ->
            offline.set("draft", "written in airplane mode")
            assertEquals(1L, offline.pendingWrites.value)
        }
        phone.connect().use { a ->
            eventually(message = "the queued write to be acknowledged") { a.pendingWrites.value == 0L }
        }
        tablet.connect().use { b ->
            b.watch("draft")
            eventually(message = "the write to reach another device") {
                b.getString("draft") == "written in airplane mode"
            }
        }
    }

    @Test
    fun aKeyDeletedWhileOfflineIsGoneAfterReconnectAndOnDisk() {
        tablet.connect().use { b ->
            b.set("cart:keep", "1")
            b.set("cart:gone", "2")
            eventually { b.pendingWrites.value == 0L }

            phone.connect().use { a ->
                a.watch("cart:*")
                eventually(message = "the snapshot") { a.exists("cart:gone") }
            }
            b.delete("cart:gone") // while the phone is closed
            eventually { b.pendingWrites.value == 0L }
        }

        phone.connect().use { a ->
            assertTrue("before reconnecting, the device still has it", a.exists("cart:gone"))
            a.watch("cart:*")
            runBlocking {
                withTimeout(5_000) { a.observe("cart:gone").first { it == null } }
            }
        }
        phone.open().use { coldStart ->
            assertFalse(coldStart.exists("cart:gone"))
            assertEquals("1", coldStart.getString("cart:keep"))
        }
    }

    @Test
    fun aColdStartOfflineShowsTheLastSyncedServerState() {
        tablet.connect().use { b ->
            b.jsonSet("profile", """{"name":"Ana"}""")
            eventually { b.pendingWrites.value == 0L }
        }
        phone.connect().use { a ->
            a.watch("profile")
            eventually(message = "the snapshot") { a.exists("profile") }
        }
        phone.open().use { offline ->
            assertEquals("""{"name":"Ana"}""", offline.getJson("profile"))
        }
    }

    @Test
    fun theStatusIsReadyOnlyAfterReplayAndHydration() {
        phone.open().use { offline -> offline.set("queued:1", "v") }
        phone.connect().use { a ->
            a.watch("queued:*")
            val phases = mutableListOf<SyncPhase>()
            runBlocking {
                withTimeout(5_000) {
                    a.syncStatus.first {
                        phases += it.phase
                        it.phase == SyncPhase.READY
                    }
                }
            }
            assertEquals(0L, a.pendingWrites.value)
            assertTrue(
                "the queued write was replayed before READY: $phases",
                phases.indexOf(SyncPhase.REPLAYING) in 0 until phases.indexOf(SyncPhase.READY),
            )
            assertTrue("lastSyncedAt is set at READY", a.syncStatus.value.lastSyncedAtMillis != null)
            // watch() may run before or after the socket opens; either way
            // the pattern ends current.
            eventually(message = "the pattern's snapshot") {
                a.subscriptionStates.value["queued:*"] == SubscriptionState.Current
            }
            assertEquals("v", a.getString("queued:1"))
        }
    }

    @Test
    fun aPatternWatchedLaterHydratesWhileTheConnectionStaysReady() {
        tablet.connect().use { b ->
            b.set("late:1", "x")
            eventually { b.pendingWrites.value == 0L }
        }
        phone.connect().use { a ->
            a.watch("early:*")
            eventually(message = "ready") { a.syncStatus.value.phase == SyncPhase.READY }
            a.watch("late:*")
            eventually(message = "the late pattern's snapshot") {
                a.subscriptionStates.value["late:*"] == SubscriptionState.Current
            }
            assertEquals(SyncPhase.READY, a.syncStatus.value.phase)
            assertEquals("x", a.getString("late:1"))
        }
    }
}
