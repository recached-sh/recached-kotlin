package dev.recached

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Everything that works with no server at all. */
class LocalTest {
    private val db = TempDb()

    @After
    fun cleanup() = db.close()

    @Test
    fun readsReturnWhatWasWritten() {
        db.open().use { cache ->
            cache.set("text", "hello")
            cache.set("bytes", byteArrayOf(0, 1, 2))
            assertEquals("hello", cache.getString("text"))
            assertArrayEquals(byteArrayOf(0, 1, 2), cache.get("bytes"))
            assertTrue(cache.exists("text"))
            assertNull(cache.get("missing"))
            assertEquals(-2L, cache.ttl("missing"))
        }
    }

    @Test
    fun countersAndJsonWork() {
        db.open().use { cache ->
            assertEquals(1L, cache.incrBy("n"))
            assertEquals(6L, cache.incrBy("n", 5))
            cache.jsonSet("doc", """{"title":"a","draft":true}""")
            cache.jsonMerge("doc", """{"draft":null}""")
            assertEquals("""{"title":"a"}""", cache.getJson("doc"))
            assertEquals("\"a\"", cache.getJson("doc", "$.title")?.removeSurrounding("[", "]"))
        }
    }

    @Test
    fun deleteReportsWhetherTheKeyExisted() {
        db.open().use { cache ->
            cache.set("k", "v")
            assertTrue(cache.delete("k"))
            assertFalse(cache.delete("k"))
        }
    }

    @Test
    fun aValueWithATtlExpires() {
        db.open().use { cache ->
            cache.set("session", "token", 2.minutes)
            assertTrue(cache.ttl("session") in 1..120)
        }
    }

    @Test
    fun aTtlUnderAMillisecondIsRoundedUpToASecondNotDown() {
        db.open().use { cache ->
            cache.set("brief", "v", 500.microseconds)
            assertEquals("v", cache.getString("brief"))
            assertTrue(cache.ttl("brief") in 0..1)
        }
    }

    @Test
    fun ttlsRoundUpToWholeSeconds() {
        assertEquals(1L, 1.microseconds.ceilSeconds())
        assertEquals(1L, 999.milliseconds.ceilSeconds())
        assertEquals(1L, 1.seconds.ceilSeconds())
        assertEquals(2L, 1001.milliseconds.ceilSeconds())
        assertEquals(120L, 2.minutes.ceilSeconds())
    }

    @Test
    fun writesAndTheirQueueSurviveReopening() {
        db.open().use { cache ->
            cache.set("a", "1")
            cache.incrBy("n", 2)
            assertEquals(2L, cache.pendingWrites.value)
        }
        db.open().use { cache ->
            assertEquals("1", cache.getString("a"))
            assertEquals("2", cache.getString("n"))
            assertEquals(2L, cache.pendingWrites.value)
        }
    }

    @Test
    fun getMatchingIsSortedByKey() {
        db.open().use { cache ->
            cache.set("p:2", "b")
            cache.set("p:1", "a")
            cache.set("q:1", "c")
            assertEquals(listOf(Entry("p:1", "a".encodeToByteArray()), Entry("p:2", "b".encodeToByteArray())), cache.getMatching("p:*"))
        }
    }

    @Test
    fun aRefusedCommandThrowsAndQueuesNothing() {
        db.open().use { cache ->
            cache.set("text", "abc")
            val error = runCatching { cache.incrBy("text") }.exceptionOrNull()
            assertTrue("$error", error is RecachedException.Command)
            assertEquals(1L, cache.pendingWrites.value)
        }
    }

    @Test
    fun aBinaryValueHasNoStringForm() {
        db.open().use { cache ->
            cache.set("bin", byteArrayOf(0xff.toByte(), 0))
            val error = runCatching { cache.getString("bin") }.exceptionOrNull()
            assertEquals("bin", (error as RecachedException.NotUtf8).key)
        }
    }

    @Test
    fun withoutAUrlTheCacheStaysOffline() {
        db.open().use { cache ->
            assertEquals(ConnectionState.OFFLINE, cache.connectionState.value)
            cache.reconnectNow() // a no-op, not an error
        }
    }

    @Test
    fun theConfigNeverPrintsItsCredentials() {
        val text = RecachedConfig(url = "wss://x", password = "hunter2", syncToken = "tok").toString()
        assertFalse(text, "hunter2" in text || "tok)" in text)
    }

    @Test
    fun aFullOutboxRefusesTheWriteAndChangesNothing() {
        db.open(RecachedConfig(maxPendingWrites = 2)).use { cache ->
            cache.set("a", "1")
            cache.set("b", "1")
            val refused = runCatching { cache.set("a", "changed") }.exceptionOrNull()
            assertTrue("expected OutboxFull, got $refused", refused is RecachedException.OutboxFull)
            refused as RecachedException.OutboxFull
            assertEquals(2L, refused.pending)
            assertEquals(2L, refused.capacity)
            assertEquals("1", cache.getString("a"))
            assertEquals(2L, cache.pendingWrites.value)
        }
        db.open(RecachedConfig(maxPendingWrites = 2)).use { reopened ->
            assertEquals("the queued writes are intact on disk", 2L, reopened.pendingWrites.value)
        }
    }

    @Test
    fun withoutAUrlTheStatusStaysDisconnected() {
        db.open().use { cache ->
            assertEquals(SyncPhase.DISCONNECTED, cache.syncStatus.value.phase)
            assertNull(cache.syncStatus.value.lastSyncedAtMillis)
        }
    }
}
