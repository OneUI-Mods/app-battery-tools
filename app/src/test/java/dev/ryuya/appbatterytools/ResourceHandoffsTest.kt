package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class ResourceHandoffsTest {
    @Test
    fun failedSubscriptionReturnsItsResource() {
        val resources = ResourceHandoffs<Any>()
        val ticket = resources.begin(Any())
        val resource = Any()
        resources.created(ticket, resource)
        val released = mutableListOf<Any>()
        assertTrue(resources.finish(ticket) { released += it }.isEmpty())
        assertSame(resource, released.single())
    }

    @Test
    fun successfulHandoffRetainsResource() {
        val resources = ResourceHandoffs<Any>()
        val ticket = resources.begin(Any())
        val resource = Any()
        resources.created(ticket, resource)
        resources.accepted(ticket, resource)
        resources.finish(ticket) { fail("An adopted resource was released") }
    }

    @Test
    fun concurrentSubscriberCanAdoptFailedSubscribersResource() {
        val resources = ResourceHandoffs<Any>()
        val owner = Any()
        val first = resources.begin(owner)
        val second = resources.begin(owner)
        val resource = Any()
        resources.created(first, resource)
        resources.finish(first) { fail("Another subscription is still in flight") }
        resources.accepted(second, resource)
        resources.finish(second) { fail("Concurrent handoff must win") }
    }

    @Test
    fun finalFailureReleasesOnlyUnacceptedResource() {
        val resources = ResourceHandoffs<Any>()
        val owner = Any()
        val first = resources.begin(owner)
        val second = resources.begin(owner)
        val resource = Any()
        resources.created(first, resource)
        val released = mutableListOf<Any>()
        resources.finish(first) { released += it }
        assertTrue(released.isEmpty())
        resources.finish(second) { released += it }
        assertEquals(1, released.size)
    }

    @Test
    fun otherOwnerCannotAdoptOrDelayCleanup() {
        val resources = ResourceHandoffs<Any>()
        val first = resources.begin(Any())
        val second = resources.begin(Any())
        val resource = Any()
        resources.created(first, resource)
        resources.accepted(second, resource)
        val released = mutableListOf<Any>()
        resources.finish(first) { released += it }
        resources.finish(second) { fail("Unowned resource") }
        assertSame(resource, released.single())
    }

    @Test
    fun existingResourcesAreNeverReturned() {
        val resources = ResourceHandoffs<Any>()
        val ticket = resources.begin(Any())
        resources.accepted(ticket, Any())
        resources.finish(ticket) { fail("This call created nothing") }
    }

    @Test
    fun separateResourcesWithEqualValuesAreNotConfused() {
        data class Resource(val id: Int)
        val resources = ResourceHandoffs<Resource>()
        val ticket = resources.begin(Any())
        val first = Resource(1)
        val second = Resource(1)
        resources.created(ticket, first)
        resources.created(ticket, second)
        resources.accepted(ticket, first)
        val released = mutableListOf<Resource>()
        resources.finish(ticket) { released += it }
        assertSame(second, released.single())
    }

    @Test
    fun failedCloseDoesNotPreventOtherCleanup() {
        val resources = ResourceHandoffs<Any>()
        val ticket = resources.begin(Any())
        resources.created(ticket, Any())
        resources.created(ticket, Any())
        var attempts = 0
        val errors =
            resources.finish(ticket) {
                attempts++
                throw IllegalStateException("Cannot release")
            }
        assertEquals(2, attempts)
        assertEquals(2, errors.size)
    }

    @Test
    fun completedOwnerCanStartFresh() {
        val resources = ResourceHandoffs<Any>()
        val owner = Any()
        repeat(2) {
            val ticket = resources.begin(owner)
            resources.created(ticket, Any())
            var released = 0
            resources.finish(ticket) { released++ }
            assertEquals(1, released)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun completedTicketCannotReleaseTwice() {
        val resources = ResourceHandoffs<Any>()
        val ticket = resources.begin(Any())
        resources.finish(ticket) {}
        resources.finish(ticket) {}
    }
}
