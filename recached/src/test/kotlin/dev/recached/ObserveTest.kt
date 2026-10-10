package dev.recached

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserveTest {
    private val db = TempDb()

    @After
    fun cleanup() = db.close()

    /** Collect [flow] in the background; each value lands in the returned channel. */
    private fun <T> CoroutineScope.record(flow: Flow<T>): Channel<T> {
        val seen = Channel<T>(Channel.UNLIMITED)
        launch(Dispatchers.Default) { flow.collect { seen.send(it) } }
        return seen
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

    @Test
    fun observeEmitsTheCurrentValueThenEachChangeOnce() = runBlocking {
        db.open().use { cache ->
            cache.set("k", "1")
            val seen = record(cache.observeString("k"))
            assertEquals("1", seen.next())

            cache.set("other", "x")
            cache.set("k", "2")
            assertEquals("2", seen.next())

            cache.set("k", "2") // unchanged: not repeated
            cache.delete("k")
            assertNull(seen.next())
            stopCollecting()
        }
    }

    @Test
    fun observeMatchingFollowsTheSetOfKeys() = runBlocking {
        db.open().use { cache ->
            cache.set("cart:1", "apple")
            val seen = record(cache.observeMatching("cart:*"))
            assertEquals(listOf("cart:1"), seen.next().map { it.key })

            cache.set("user:1", "x") // outside the pattern
            cache.set("cart:2", "pear")
            assertEquals(listOf("cart:1", "cart:2"), seen.next().map { it.key })

            cache.delete("cart:1")
            assertEquals(listOf("cart:2"), seen.next().map { it.key })
            stopCollecting()
        }
    }

    @Test
    fun aSlowCollectorStillEndsOnTheLatestValue() = runBlocking {
        db.open().use { cache ->
            cache.set("n", "0")
            val seen = Channel<String?>(Channel.UNLIMITED)
            launch(Dispatchers.Default) {
                cache.observeString("n").collect {
                    delay(50) // slower than the writes
                    seen.send(it)
                }
            }
            assertEquals("0", seen.next())
            repeat(100) { cache.set("n", "${it + 1}") }
            var last: String? = null
            while (last != "100") last = seen.next()
            stopCollecting()
        }
    }

    @Test
    fun aBinaryValueObservesAsNullText() = runBlocking {
        db.open().use { cache ->
            cache.set("bin", byteArrayOf(0xff.toByte()))
            assertNull(record(cache.observeString("bin")).next())
            assertEquals(1, record(cache.observe("bin")).next()?.size)
            stopCollecting()
        }
    }
}

/** Cancel the collectors and wait for them, so none is mid-read when the cache closes. */
private suspend fun CoroutineScope.stopCollecting() =
    coroutineContext[Job]!!.children.toList().forEach { it.cancelAndJoin() }
