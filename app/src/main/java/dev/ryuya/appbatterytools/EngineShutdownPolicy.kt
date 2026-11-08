package dev.ryuya.appbatterytools

/** Missing lifecycle information always keeps the resource with its original owner. */
internal object EngineShutdownPolicy {
    fun mayRelease(
        destroyWithHost: Boolean?,
        completed: Boolean,
        attached: Boolean?,
        sameLock: Boolean,
    ) = destroyWithHost == true && completed && attached == false && sameLock
}
