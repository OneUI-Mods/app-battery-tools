package dev.ryuya.appbatterytools

/** A failed, completed handoff has no consumer for a subsequently opened native client. */
internal object LateClientPolicy {
    fun shouldClose(
        completed: Boolean,
        failedCompletion: Boolean,
        adopted: Any?,
        incoming: Any,
    ): Boolean = completed && !failedCompletion && adopted == null
}
