package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class LocationLeasesTest {
    private class Fixture {
        val removed = mutableListOf<Pair<Any, Any>>()
        val errors = mutableListOf<Throwable>()
        val leases = LocationLeases<Any, Any, Any>({ c, l -> removed += c to l }, errors::add)
    }

    @Test
    fun endingAreaServiceReleasesItsExactClientAndListener() {
        val f = Fixture()
        val owner = Any()
        val client = Any()
        val callback = Any()
        f.leases.start(owner)
        f.leases.registered(owner, client, callback)
        assertTrue(f.removed.isEmpty()) // Enabled notifications keep their original request.
        assertEquals(LocationLeases.Removal(1, 0), f.leases.stop(owner))
        assertSame(client, f.removed.single().first)
        assertSame(callback, f.removed.single().second)
    }

    @Test
    fun restartingServiceCannotRemoveNewServicesRegistration() {
        val f = Fixture()
        val old = Any()
        val fresh = Any()
        val callback = Any()
        f.leases.start(old)
        f.leases.registered(old, Any(), callback)
        f.leases.start(fresh)
        f.leases.registered(fresh, Any(), Any())
        f.leases.stop(old)
        assertEquals(1, f.removed.size)
        assertSame(callback, f.removed.single().second)
        assertEquals(LocationLeases.Removal(1, 0), f.leases.stop(fresh))
    }

    @Test
    fun repeatedStopIsIdempotent() {
        val f = Fixture()
        val owner = Any()
        f.leases.start(owner)
        f.leases.registered(owner, Any(), Any())
        f.leases.stop(owner)
        assertEquals(LocationLeases.Removal(0, 0), f.leases.stop(owner))
        assertEquals(1, f.removed.size)
    }

    @Test
    fun repeatedStartOfSameServiceRetainsAllOfItsDistinctCallbacks() {
        val f = Fixture()
        val owner = Any()
        repeat(20) {
            f.leases.start(owner)
            f.leases.registered(owner, Any(), Any())
        }
        assertTrue(f.removed.isEmpty())
        assertEquals(LocationLeases.Removal(20, 0), f.leases.stop(owner))
    }

    @Test
    fun duplicateTrackingDoesNotDoubleRemove() {
        val f = Fixture()
        val owner = Any()
        val client = Any()
        val listener = Any()
        f.leases.start(owner)
        repeat(3) { f.leases.registered(owner, client, listener) }
        assertEquals(LocationLeases.Removal(1, 0), f.leases.stop(owner))
    }

    @Test
    fun callbacksUseIdentityNotUserDefinedEquality() {
        data class Equal(val value: Int)
        val f = Fixture()
        val owner = Any()
        val client = Any()
        f.leases.start(owner)
        f.leases.registered(owner, client, Equal(1))
        f.leases.registered(owner, client, Equal(1))
        assertEquals(LocationLeases.Removal(2, 0), f.leases.stop(owner))
    }

    @Test
    fun registrationFinishingAfterStopIsImmediatelyReleased() {
        val f = Fixture()
        val owner = Any()
        val client = Any()
        val listener = Any()
        f.leases.start(owner)
        f.leases.stop(owner)
        f.leases.registered(owner, client, listener)
        assertEquals(1, f.removed.size)
        assertEquals(LocationLeases.Removal(0, 0), f.leases.stop(owner))
    }

    @Test
    fun oneRemovalFailureDoesNotPreventOthersAndIsReportedAsFailure() {
        val errors = mutableListOf<Throwable>()
        var attempts = 0
        val leases =
            LocationLeases<Any, Any, Any>(
                { _, _ -> if (++attempts == 1) error("Binder disconnected") },
                errors::add,
            )
        val owner = Any()
        leases.start(owner)
        repeat(3) { leases.registered(owner, Any(), Any()) }
        assertEquals(LocationLeases.Removal(3, 1), leases.stop(owner))
        assertEquals(3, attempts)
        assertEquals(1, errors.size)
        assertEquals(LocationLeases.Removal(0, 0), leases.stop(owner))
    }

    @Test
    fun serviceThatNeverRegisteredLocationDoesNotRemoveAnything() {
        val f = Fixture()
        val owner = Any()
        f.leases.start(owner)
        assertEquals(LocationLeases.Removal(0, 0), f.leases.stop(owner))
        assertTrue(f.removed.isEmpty())
    }
}
