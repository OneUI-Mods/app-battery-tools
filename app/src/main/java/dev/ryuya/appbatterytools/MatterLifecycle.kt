package dev.ryuya.appbatterytools

/**
 * No native calls under this monitor; an in-flight creator must finish before a pending release.
 */
internal class MatterLifecycle {
    private var creators = 0
    private var requested = false

    @Synchronized
    fun creating() {
        creators++
    }

    @Synchronized
    fun created(controllerPresent: Boolean): Boolean {
        check(creators > 0)
        creators--
        if (controllerPresent) requested = false
        return drain(controllerPresent)
    }

    @Synchronized
    fun stopped(controllerPresent: Boolean): Boolean {
        requested = true
        return drain(controllerPresent)
    }

    private fun drain(controllerPresent: Boolean): Boolean {
        if (requested && creators == 0 && !controllerPresent) {
            requested = false
            return true
        }
        return false
    }
}
