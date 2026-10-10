package dev.recached

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What only a device can check: the Android entry point, where the database
 * lands, and the connectivity hooks. The JVM suite in src/test runs here too.
 *
 * To also sync with a real server from a phone, forward its port and pass it:
 *
 *     adb reverse tcp:6380 tcp:6380
 *     ./gradlew :recached:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.recachedUrl=ws://127.0.0.1:6380
 */
@RunWith(AndroidJUnit4::class)
class AndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "androidtest-${System.nanoTime()}"

    @After
    fun cleanup() {
        File(context.noBackupFilesDir, "$name.db").delete()
        File(context.noBackupFilesDir, "$name.db-wal").delete()
        File(context.noBackupFilesDir, "$name.db-shm").delete()
    }

    @Test
    fun opensInTheNoBackupDirectoryAndSurvivesReopening() {
        Recached.open(context, RecachedConfig(), name).use { it.set("k", "v") }
        assertTrue(File(context.noBackupFilesDir, "$name.db").isFile)
        Recached.open(context, RecachedConfig(), name).use { assertEquals("v", it.getString("k")) }
    }

    @Test
    fun connectivityHooksAttachAndDetachCleanly() {
        // Nothing listens on port 1: the attempt fails and waits to retry.
        val cache = Recached.open(context, RecachedConfig(url = "ws://127.0.0.1:1"), name)
        eventually(message = "a failed attempt") { cache.connectionState.value == ConnectionState.WAITING }
        cache.reconnectNow()
        cache.close()
        assertEquals(ConnectionState.OFFLINE, cache.connectionState.value)
    }

    @Test
    fun syncsWithARealServerWhenGivenOne() {
        val url = InstrumentationRegistry.getArguments().getString("recachedUrl")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.recachedUrl=…", url != null)
        Recached.open(context, RecachedConfig(url = url), name).use { cache ->
            eventually(message = "a connection to $url") { cache.connectionState.value == ConnectionState.CONNECTED }
            val key = "android:${System.nanoTime()}"
            cache.watch(key)
            cache.set(key, "from the device")
            eventually(message = "the server to acknowledge the write") { cache.pendingWrites.value == 0L }
        }
    }
}
