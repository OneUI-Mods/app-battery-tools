package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class ScopedStopBatchTest {
    @Test
    fun individualFailureDoesNotSkipRemainingCallbacks() {
        val scanner = Any()
        val callbacks = List(3) { Any() }
        val batch = ScopedStopBatch(scanner, callbacks)
        val attempts = mutableListOf<Any>()
        for (callback in callbacks) batch.attempt(scanner, callback) {
            attempts += callback
            if (callback === callbacks[0]) throw IllegalStateException("One scan failed")
        }
        assertEquals(callbacks, attempts)
        assertEquals(3, batch.requested)
        assertEquals(1, batch.failures)
    }

    @Test
    fun allFailuresRemainBoundedToOriginalRequests() {
        val scanner = Any()
        val callbacks = List(3) { Any() }
        val batch = ScopedStopBatch(scanner, callbacks)
        for (callback in callbacks) batch.attempt(scanner, callback) {
            throw SecurityException("Permission gone")
        }
        assertEquals(3, batch.requested)
        assertEquals(3, batch.failures)
        assertTrue(batch.firstFailure is SecurityException)
    }

    @Test
    fun successfulStopPreservesResult() {
        val scanner = Any()
        val callback = Any()
        val batch = ScopedStopBatch(scanner, listOf(callback))
        assertEquals("original", batch.attempt(scanner, callback) { "original" })
        assertEquals(0, batch.failures)
    }

    @Test(expected = IllegalStateException::class)
    fun foreignScannerFailurePassesThrough() {
        val callback = Any()
        ScopedStopBatch(Any(), listOf(callback)).attempt(Any(), callback) {
            throw IllegalStateException()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun foreignCallbackFailurePassesThrough() {
        val scanner = Any()
        ScopedStopBatch(scanner, listOf(Any())).attempt(scanner, Any()) {
            throw IllegalStateException()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCallbackIsNotReinterpreted() {
        val scanner = Any()
        ScopedStopBatch(scanner, listOf(Any())).attempt(scanner, null) {
            throw IllegalArgumentException()
        }
    }

    @Test(expected = LinkageError::class)
    fun vmAndLinkageErrorsAreNotSwallowed() {
        val scanner = Any()
        val callback = Any()
        ScopedStopBatch(scanner, listOf(callback)).attempt(scanner, callback) {
            throw LinkageError()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun callbacksWithEqualValuesDoNotShareOwnership() {
        data class Callback(val id: Int)
        val scanner = Any()
        ScopedStopBatch(scanner, listOf(Callback(1))).attempt(scanner, Callback(1)) {
            throw IllegalStateException()
        }
    }
}
