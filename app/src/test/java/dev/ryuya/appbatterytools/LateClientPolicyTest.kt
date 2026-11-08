package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class LateClientPolicyTest {
    @Test
    fun failedHandoffDoesNotAdoptALateNativeClient() {
        assertTrue(LateClientPolicy.shouldClose(true, false, null, Any()))
    }

    @Test
    fun anAdoptedClientIsPreserved() {
        val client = Any()
        assertFalse(LateClientPolicy.shouldClose(true, false, client, client))
    }

    @Test
    fun anotherSuccessfulHandoffIsPreservedConservatively() {
        assertFalse(LateClientPolicy.shouldClose(true, false, Any(), Any()))
    }

    @Test
    fun incompleteHandoffIsPreserved() {
        assertFalse(LateClientPolicy.shouldClose(false, false, null, Any()))
    }

    @Test
    fun exceptionalHandoffDoesNotGuessOwnership() {
        assertFalse(LateClientPolicy.shouldClose(true, true, null, Any()))
    }
}
