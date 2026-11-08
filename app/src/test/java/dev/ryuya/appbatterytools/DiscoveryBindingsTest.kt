package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class DiscoveryBindingsTest {
    @Test(expected = IllegalArgumentException::class)
    fun descriptorCannotReplaceAnEnabledFlag() {
        DiscoveryBindings.checked(
            mapOf("weather_timer" to "Lowner;->timer:Ljava/util/Timer;"),
            setOf("enabled", "weather_timer"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorCannotReplaceReadiness() {
        DiscoveryBindings.checked(mapOf("ready" to "wrong type"), setOf("enabled"))
    }

    @Test
    fun distinctDescriptorKeysRetainTheirValue() {
        val bindings = mapOf("weather_timer_field" to "Lowner;->timer:Ljava/util/Timer;")
        assertEquals(
            bindings,
            DiscoveryBindings.checked(bindings, setOf("enabled", "weather_timer")),
        )
    }
}
