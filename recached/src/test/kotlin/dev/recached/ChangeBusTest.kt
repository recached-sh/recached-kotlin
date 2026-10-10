package dev.recached

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeBusTest {
    private val calls = mutableListOf<String>()
    private val bus = ChangeBus { pattern, keys -> keys.any { it.startsWith(pattern.removeSuffix("*")) } }

    @Test
    fun aKeyListenerHearsOnlyItsKey() {
        bus.onKey("a") { calls += "a" }
        bus.publish(listOf("b"))
        assertEquals(emptyList<String>(), calls)
        bus.publish(listOf("b", "a"))
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun aPatternListenerHearsOncePerChangeSet() {
        bus.onPattern("cart:*") { calls += "cart" }
        bus.publish(listOf("cart:1", "cart:2", "user:1"))
        assertEquals(listOf("cart"), calls)
        bus.publish(listOf("user:2"))
        assertEquals(listOf("cart"), calls)
    }

    @Test
    fun closingARegistrationStopsIt() {
        val a = bus.onKey("k") { calls += "a" }
        bus.onKey("k") { calls += "b" }
        a.close()
        a.close() // idempotent
        bus.publish(listOf("k"))
        assertEquals(listOf("b"), calls)
    }

    @Test
    fun invalidateAllReachesEveryone() {
        bus.onKey("k") { calls += "k" }
        bus.onPattern("p:*") { calls += "p" }
        bus.invalidateAll()
        assertEquals(listOf("k", "p"), calls)
    }
}
