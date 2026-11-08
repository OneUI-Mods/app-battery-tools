package dev.ryuya.appbatterytools

import java.util.IdentityHashMap

/** Own only registrations made by the area service, never another location consumer. */
internal class LocationLeases<O : Any, C : Any, L : Any>(
    private val remove: (C, L) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private data class Lease<C, L>(val client: C, val callback: L)

    private val leases = IdentityHashMap<O, MutableList<Lease<C, L>>>()

    @Synchronized
    fun start(owner: O) {
        leases.getOrPut(owner) { mutableListOf() }
    }

    @Synchronized
    fun registered(owner: O, client: C, callback: L) {
        val current = leases[owner]
        if (current == null) {
            // Destruction may win a registration race. Do not resurrect the old owner.
            release(Lease(client, callback))
        } else if (current.none { it.client === client && it.callback === callback }) {
            current.add(Lease(client, callback))
        }
    }

    data class Removal(val requested: Int, val failed: Int)

    @Synchronized
    fun stop(owner: O): Removal {
        val released = leases.remove(owner) ?: return Removal(0, 0)
        val failures = released.count { !release(it) }
        return Removal(released.size, failures)
    }

    private fun release(lease: Lease<C, L>): Boolean =
        try {
            remove(lease.client, lease.callback)
            true
        } catch (error: Exception) {
            failure(error)
            false
        }
}
