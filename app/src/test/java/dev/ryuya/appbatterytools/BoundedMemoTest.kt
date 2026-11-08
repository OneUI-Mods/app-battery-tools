package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class BoundedMemoTest {
    @Test
    fun nullResultsRetainTheOriginalNullableContract() {
        val cache = BoundedMemo<String, Any?>()
        var calls = 0
        repeat(2) {
            assertNull(
                cache.getOrCompute("nullable") {
                    calls++
                    null
                },
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun repeatedInputComputesOnce() {
        val cache = BoundedMemo<String, Int>()
        var calls = 0
        repeat(20) { assertEquals(1, cache.getOrCompute("same") { ++calls }) }
        assertEquals(1, calls)
    }

    @Test
    fun changedRouteIsNotReused() {
        val cache = BoundedMemo<Triple<String, String, String>, String>()
        assertEquals("one", cache.getOrCompute(Triple("usb", "a", "name")) { "one" })
        assertEquals("two", cache.getOrCompute(Triple("usb", "b", "name")) { "two" })
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = BoundedMemo<String, Int>(2)
        cache.getOrCompute("a") { 1 }
        cache.getOrCompute("b") { 2 }
        cache.getOrCompute("a") { error("must reuse") }
        cache.getOrCompute("c") { 3 }
        assertEquals(2, cache.size)
        assertEquals(20, cache.getOrCompute("b") { 20 })
    }

    @Test
    fun exceptionsAreNotTurnedIntoCachedValues() {
        val cache = BoundedMemo<String, Int>()
        try {
            cache.getOrCompute("a") { error("failed") }
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(7, cache.getOrCompute("a") { 7 })
    }

    @Test
    fun closingAResourceInvalidatesOnlyThatInstance() {
        val cache = BoundedMemo<String, Any>()
        val first = Any()
        val second = Any()
        cache.getOrCompute("a") { first }
        cache.getOrCompute("b") { second }
        cache.invalidate(first)
        assertEquals(1, cache.size)
        assertSame(second, cache.getOrCompute("b") { Any() })
    }

    @Test
    fun concurrentReadersCreateOneValue() {
        val cache = BoundedMemo<String, Any>()
        val created = java.util.concurrent.atomic.AtomicInteger()
        val threads =
            List(12) {
                Thread {
                    cache.getOrCompute("one") {
                        created.incrementAndGet()
                        Any()
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, created.get())
    }
}
