package dev.ryuya.appbatterytools

/** Counts only successful acquisitions made by this synchronous start call. */
internal class AcquiredReferences<R : Any>(private val expected: R) {
    private var count = 0

    fun acquired(resource: R) {
        if (resource === expected) count++
    }

    fun rollback(completed: Boolean, registered: Boolean, release: (R) -> Unit): Int {
        val owned = count
        count = 0
        if (completed || registered) return 0
        repeat(owned) { release(expected) }
        return owned
    }
}
