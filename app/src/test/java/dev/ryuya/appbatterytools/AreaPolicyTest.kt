package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class AreaPolicyTest {
    @Test
    fun reviewedOffStopsOnlyTheAreaService() {
        assertTrue(AreaPolicy.shouldSuppress(true, false))
    }

    @Test
    fun onKeepsOriginalBehavior() {
        assertFalse(AreaPolicy.shouldSuppress(true, true))
    }

    @Test
    fun missingSettingsNeverDisableNotifications() {
        assertFalse(AreaPolicy.shouldSuppress(true, null))
    }

    @Test
    fun unknownDartModelNeverUsesAnObsoleteFlag() {
        assertFalse(AreaPolicy.shouldSuppress(false, false))
        assertFalse(AreaPolicy.shouldSuppress(false, true))
        assertFalse(AreaPolicy.shouldSuppress(false, null))
    }
}
