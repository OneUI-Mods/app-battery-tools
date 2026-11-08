package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class ScanRegistryTest {
    @Test
    fun cancelledQueuedActionDoesNotTouchAReplacementUsingTheSameToken() {
        data class Request(val filter: String)
        val scans = ScanRegistry<String, Request>()
        val old = Request("same")
        scans.add("token", old, true)
        scans.transition(false)
        scans.remove("token")
        val replacement = Request("same")
        scans.add("token", replacement, false)
        assertFalse(scans.isCurrent("token", old, false))
        assertTrue(scans.isCurrent("token", replacement, false))
    }

    @Test
    fun aNewConnectivityPhaseInvalidatesAnOlderQueuedAction() {
        val scans = ScanRegistry<String, Any>()
        val request = Any()
        scans.add("a", request, true)
        scans.transition(false)
        scans.transition(true)
        assertFalse(scans.isCurrent("a", request, false))
        assertTrue(scans.isCurrent("a", request, true))
    }

    @Test
    fun pauseAndReconnectResumeExactlyOnce() {
        val scans = ScanRegistry<String, String>()
        scans.add("callback", "filters and original settings", true)
        assertEquals(listOf("callback" to "filters and original settings"), scans.transition(false))
        assertTrue(scans.transition(false).isEmpty())
        assertEquals(listOf("callback" to "filters and original settings"), scans.transition(true))
        assertTrue(scans.transition(true).isEmpty())
    }

    @Test
    fun explicitStopDuringPauseNeverResurrects() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "request", true)
        scans.transition(false)
        assertFalse(scans.remove("a")!!.active)
        assertTrue(scans.transition(true).isEmpty())
    }

    @Test
    fun scanRequestedOfflineWaitsForConnection() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "offline", false)
        assertTrue(scans.transition(false).isEmpty())
        assertEquals(listOf("a" to "offline"), scans.transition(true))
    }

    @Test
    fun failureOnlyRemovesItsOwner() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "one", false)
        scans.add("b", "two", false)
        scans.remove("a")
        assertEquals(listOf("b" to "two"), scans.transition(true))
    }

    @Test
    fun cancelledTokenCanBeReusedWithNewSettings() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "old", false)
        scans.remove("a")
        scans.add("a", "new", false)
        assertEquals(listOf("a" to "new"), scans.transition(true))
    }

    @Test
    fun capacityBoundsRetainedCallbacks() {
        val scans = ScanRegistry<Int, String>(2)
        scans.add(1, "one", true)
        scans.add(2, "two", false)
        assertFalse(scans.canAdd())
        scans.remove(1)
        assertTrue(scans.canAdd())
    }

    @Test
    fun bluetoothResetDropsAllOldRegistrations() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "one", false)
        scans.clear()
        assertEquals(0, scans.size)
        assertTrue(scans.transition(true).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateDoesNotOverwriteOriginal() {
        val scans = ScanRegistry<String, String>()
        scans.add("a", "one", true)
        scans.add("a", "different", false)
    }
}
