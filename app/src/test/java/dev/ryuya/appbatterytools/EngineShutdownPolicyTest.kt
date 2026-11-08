package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class EngineShutdownPolicyTest {
    @Test
    fun completedOwnedEngineCanRelease() =
        assertTrue(EngineShutdownPolicy.mayRelease(true, true, false, true))

    @Test
    fun cachedEngineIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(false, true, false, true))

    @Test
    fun failedTeardownIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(true, false, false, true))

    @Test
    fun stillAttachedEngineIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(true, true, true, true))

    @Test
    fun missingOwnershipIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(null, true, false, true))

    @Test
    fun missingNativeStateIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(true, true, null, true))

    @Test
    fun replacementLockIsRetained() =
        assertFalse(EngineShutdownPolicy.mayRelease(true, true, false, false))
}
