package dev.recached

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end against a real recached-server: the four things the first
 * release promises, as an app on two devices would see them.
 */
class LiveTest {
    private val server = LiveServer()
    private val phone = TempDb()
    private val tablet = TempDb()

    @After
    fun cleanup() {
        phone.close()
        tablet.close()
        server.close()
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
}
