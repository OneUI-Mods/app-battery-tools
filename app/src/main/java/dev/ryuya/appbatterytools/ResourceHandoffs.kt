package dev.ryuya.appbatterytools

import java.util.IdentityHashMap

/** Short ownership bookkeeping; no thread, timer, retained completed owner, or long-held lock. */
internal class ResourceHandoffs<R : Any> {
    internal class State<R : Any> {
        var active = 0
        val created = IdentityHashMap<R, Boolean>()
    }

    internal class Ticket<R : Any>(val owner: Any, val state: State<R>) {
        var finished = false
    }

    private val owners = IdentityHashMap<Any, State<R>>()

    @Synchronized
    fun begin(owner: Any): Ticket<R> {
        val state = owners.getOrPut(owner) { State() }
        state.active++
        return Ticket(owner, state)
    }

    @Synchronized
    fun created(ticket: Ticket<R>, resource: R) {
        check(!ticket.finished)
        ticket.state.created.putIfAbsent(resource, false)
    }

    @Synchronized
    fun accepted(ticket: Ticket<R>, resource: R?) {
        check(!ticket.finished)
        if (resource != null && ticket.state.created.containsKey(resource)) {
            ticket.state.created[resource] = true
        }
    }

    /** A concurrent subscription may adopt the resource. Decide only after all calls return. */
    @Synchronized
    fun finish(ticket: Ticket<R>, release: (R) -> Unit): List<Exception> {
        check(!ticket.finished)
        ticket.finished = true
        val state = ticket.state
        state.active--
        if (state.active != 0) return emptyList()
        owners.remove(ticket.owner)
        val failures = mutableListOf<Exception>()
        for ((resource, accepted) in state.created) {
            if (!accepted)
                try {
                    release(resource)
                } catch (error: Exception) {
                    failures += error
                }
        }
        state.created.clear()
        return failures
    }
}
