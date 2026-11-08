package dev.ryuya.appbatterytools

import java.util.Collections
import java.util.IdentityHashMap

/** Only the verified owner's stop requests can recover from an individual platform error. */
internal class ScopedStopBatch<S : Any, C : Any>(private val scanner: S, callbacks: Collection<C>) {
    private val owned =
        Collections.newSetFromMap(IdentityHashMap<C, Boolean>()).apply { addAll(callbacks) }
    var failures: Int = 0
        private set

    var requested: Int = 0
        private set

    var firstFailure: Exception? = null
        private set

    fun <T> attempt(actualScanner: S?, callback: C?, stop: () -> T): T? {
        if (actualScanner !== scanner || callback == null || !owned.contains(callback))
            return stop()
        requested++
        return try {
            stop()
        } catch (error: Exception) {
            failures++
            if (firstFailure == null) firstFailure = error
            null
        }
    }
}
