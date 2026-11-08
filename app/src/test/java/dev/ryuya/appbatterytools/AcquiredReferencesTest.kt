package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class AcquiredReferencesTest {
    @Test
    fun failureReturnsJustThisCallsReference() {
        val lock = Any()
        val acquisitions = AcquiredReferences(lock)
        var references = 3 // Two existing browsers plus this failed startup.
        acquisitions.acquired(lock)
        assertEquals(1, acquisitions.rollback(false, false) { references-- })
        assertEquals(2, references)
    }

    @Test
    fun successfulStartKeepsAllReferences() {
        val lock = Any()
        val acquisitions = AcquiredReferences(lock)
        acquisitions.acquired(lock)
        assertEquals(0, acquisitions.rollback(true, false) { fail("Successful start") })
    }

    @Test
    fun registeredCallbackOwnsCleanupEvenIfCallerThrows() {
        val lock = Any()
        val acquisitions = AcquiredReferences(lock)
        acquisitions.acquired(lock)
        assertEquals(0, acquisitions.rollback(false, true) { fail("Registered callback") })
    }

    @Test
    fun unrelatedLockIsIgnored() {
        val acquisitions = AcquiredReferences(Any())
        acquisitions.acquired(Any())
        assertEquals(0, acquisitions.rollback(false, false) { fail("Wrong owner") })
    }

    @Test
    fun duplicateHandleWithNoAcquisitionDoesNothing() {
        val acquisitions = AcquiredReferences(Any())
        assertEquals(0, acquisitions.rollback(false, false) { fail("Nothing acquired") })
    }

    @Test
    fun failedStartIsNotReleasedTwice() {
        val lock = Any()
        val acquisitions = AcquiredReferences(lock)
        acquisitions.acquired(lock)
        acquisitions.rollback(false, false) {}
        assertEquals(0, acquisitions.rollback(false, false) { fail("Already released") })
    }
}
