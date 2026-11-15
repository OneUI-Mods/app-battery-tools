package dev.ryuya.appbatterytools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryReadinessTest {
    @Test
    fun unfinishedDiscoveryRemainsRetryable() {
        listOf(null, "waiting", "preparing").forEach { assertTrue(DiscoveryReadiness.pending(it)) }
    }

    @Test
    fun cachedFailureOrUnsupportedHostDoesNotKeepAListenerAlive() {
        listOf("failed", "native-unavailable", "not_installed_or_untrusted", "ready").forEach {
            assertFalse(DiscoveryReadiness.pending(it))
        }
    }
}
