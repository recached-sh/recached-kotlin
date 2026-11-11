package dev.recached

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What only a device can check: the Android entry point, where the database
 * lands, the connectivity hooks, and — given a server — the sync lifecycle
 * through that entry point across a process-style close and reopen. The JVM
 * suite in src/test runs here too.
 *
 * To also sync with a real server from a phone, forward its port and pass it:
 *
 *     adb reverse tcp:6380 tcp:6380
 *     ./gradlew :recached:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.recachedUrl=ws://127.0.0.1:6380
 *
 * CI runs a server on the runner, which the emulator reaches as 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val run = System.nanoTime()
    private val name = "androidtest-$run"

    /** Nothing listens on port 1: a cache opened with it stays offline and queues. */
    private val unreachable = RecachedConfig(url = "ws://127.0.0.1:1")

    @After
    fun cleanup() {
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("androidtest-$run") }?.forEach { it.delete() }
    }

    private fun serverUrl(): String {
        val url = InstrumentationRegistry.getArguments().getString("recachedUrl")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.recachedUrl=…", url != null)
        return url!!
    }

    /** A second device: its own database, the same server. */
    private fun otherDevice(url: String): Recached = Recached.open(context, RecachedConfig(url = url), "$name-other")

    private fun Recached.awaitReady(timeoutMs: Long = 10_000): SyncStatus =
        runBlocking { withTimeout(timeoutMs) { syncStatus.first { it.phase == SyncPhase.READY } } }

    @Test
    fun opensInTheNoBackupDirectoryAndSurvivesReopening() {
        Recached.open(context, RecachedConfig(), name).use { it.set("k", "v") }
        assertTrue(File(context.noBackupFilesDir, "$name.db").isFile)
        Recached.open(context, RecachedConfig(), name).use { assertEquals("v", it.getString("k")) }
    }

    @Test
    fun connectivityHooksAttachAndDetachCleanly() {
        val cache = Recached.open(context, unreachable, name)
        eventually(message = "a failed attempt") { cache.connectionState.value == ConnectionState.WAITING }
        cache.reconnectNow()
        cache.close()
        assertEquals(ConnectionState.OFFLINE, cache.connectionState.value)
    }

    @Test
    fun syncsWithARealServerWhenGivenOne() {
        val url = serverUrl()
        Recached.open(context, RecachedConfig(url = url), name).use { cache ->
            eventually(message = "a connection to $url") { cache.connectionState.value == ConnectionState.CONNECTED }
            val key = "android:$run"
            cache.watch(key)
            cache.set(key, "from the device")
            eventually(message = "the server to acknowledge the write") { cache.pendingWrites.value == 0L }
        }
    }

    // ── offline SQLite restoration ───────────────────────────────────────

    @Test
    fun offlineWritesSurviveReopeningAndReachTheServerExactlyOnce() {
        val url = serverUrl()
        val counter = "android:replay:$run:n"
        val text = "android:replay:$run:s"
        Recached.open(context, unreachable, name).use { offline ->
            offline.incrBy(counter, 2)
            offline.incrBy(counter, 3)
            offline.set(text, "queued offline")
            assertEquals(3L, offline.pendingWrites.value)
        }
        // Reopened from SQLite alone, as after the process was killed.
        Recached.open(context, unreachable, name).use { restored ->
            assertEquals("5", restored.getString(counter))
            assertEquals("queued offline", restored.getString(text))
            assertEquals("the queue is restored from disk", 3L, restored.pendingWrites.value)
        }
        Recached.open(context, RecachedConfig(url = url), name).use { online ->
            eventually(timeoutMs = 10_000, message = "the queued writes to be acknowledged") {
                online.pendingWrites.value == 0L
            }
        }
        // Read back through a different device: the server applied each
        // queued INCRBY once, however often the queue was restored.
        otherDevice(url).use { other ->
            other.watch("android:replay:$run:*")
            eventually(timeoutMs = 10_000, message = "the other device's snapshot") {
                other.getString(counter) == "5" && other.getString(text) == "queued offline"
            }
        }
    }

    @Test
    fun aColdStartWithoutANetworkReadsTheLastSynchronizedState() {
        val url = serverUrl()
        val key = "android:cold:$run"
        otherDevice(url).use { writer ->
            writer.set(key, "server state")
            eventually(message = "the write to be acknowledged") { writer.pendingWrites.value == 0L }
        }
        Recached.open(context, RecachedConfig(url = url), name).use { online ->
            online.watch(key)
            eventually(timeoutMs = 10_000, message = "the snapshot") { online.getString(key) == "server state" }
        }
        Recached.open(context, unreachable, name).use { offline ->
            assertEquals("server state", offline.getString(key))
        }
    }

    // ── outbox overflow ──────────────────────────────────────────────────

    @Test
    fun aFullOutboxRefusesAndKeepsTheQueueIntactAcrossReopening() {
        val config = unreachable.copy(maxPendingWrites = 2)
        Recached.open(context, config, name).use { cache ->
            cache.set("a", "1")
            cache.set("b", "1")
            val refused = runCatching { cache.set("a", "changed") }.exceptionOrNull()
            assertTrue("expected OutboxFull, got $refused", refused is RecachedException.OutboxFull)
            assertEquals("a refused write changes nothing", "1", cache.getString("a"))
        }
        Recached.open(context, config, name).use { reopened ->
            assertEquals(2L, reopened.pendingWrites.value)
            assertEquals("1", reopened.getString("a"))
        }
    }

    @Test
    fun evictingTheOldestWhenFullIsOptInAndKeepsTheCap() {
        val config = unreachable.copy(maxPendingWrites = 2, evictOldestWhenFull = true)
        Recached.open(context, config, name).use { cache ->
            cache.set("a", "1")
            cache.set("b", "1")
            cache.set("c", "1")
            assertEquals(2L, cache.pendingWrites.value)
            assertEquals("the evicted write's local value stays", "1", cache.getString("a"))
        }
        Recached.open(context, config, name).use { reopened ->
            assertEquals(2L, reopened.pendingWrites.value)
        }
    }

    // ── reconnection lifecycle ───────────────────────────────────────────

    @Test
    fun theStatusIsReadyAgainAfterEachReopenAndCarriesTheSyncTime() {
        val url = serverUrl()
        val pattern = "android:life:$run:*"
        Recached.open(context, unreachable, name).use { it.set("android:life:$run:1", "queued") }
        val first =
            Recached.open(context, RecachedConfig(url = url), name).use { cache ->
                cache.watch(pattern)
                val ready = cache.awaitReady()
                // READY only after the replay and the snapshot.
                assertEquals(0L, cache.pendingWrites.value)
                eventually(message = "the pattern's snapshot") {
                    cache.subscriptionStates.value[pattern] == SubscriptionState.Current
                }
                assertNotNull("READY carries the time it was reached", ready.lastSyncedAtMillis)
                ready.lastSyncedAtMillis!!
            }
        Recached.open(context, RecachedConfig(url = url), name).use { again ->
            again.watch(pattern)
            val ready = again.awaitReady()
            assertTrue("a later session syncs later", ready.lastSyncedAtMillis!! >= first)
            assertEquals("queued", again.getString("android:life:$run:1"))
        }
    }

    @Test
    fun closingWhileConnectedLeavesTheCacheOfflineAndReopenable() {
        val url = serverUrl()
        val cache = Recached.open(context, RecachedConfig(url = url), name)
        eventually(message = "a connection") { cache.connectionState.value == ConnectionState.CONNECTED }
        cache.close()
        assertEquals(ConnectionState.OFFLINE, cache.connectionState.value)
        Recached.open(context, RecachedConfig(url = url), name).use { reopened ->
            eventually(message = "a connection after reopening") {
                reopened.connectionState.value == ConnectionState.CONNECTED
            }
        }
    }
}
