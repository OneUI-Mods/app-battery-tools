package dev.ryuya.appbatterytools

import java.lang.reflect.Field
import java.util.Timer

/** Only the reviewed owner's timer; the original method still clears its own field. */
internal class OwnedTimerCleanup(private val field: Field) {
    init {
        require(field.type == Timer::class.java)
        field.isAccessible = true
    }

    fun beforeStop(owner: Any?): Boolean {
        val timer = field.get(owner) as? Timer ?: return false
        timer.cancel()
        return true
    }
}
