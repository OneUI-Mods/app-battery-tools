package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class MatterLifecycleTest {
    @Test
    fun stoppedControllerCanRelease() {
        assertTrue(MatterLifecycle().stopped(false))
    }

    @Test
    fun liveControllerPreventsRelease() {
        assertFalse(MatterLifecycle().stopped(true))
    }

    @Test
    fun releaseWaitsForAnInFlightCreatorToFail() {
        val life = MatterLifecycle()
        life.creating()
        assertFalse(life.stopped(false))
        assertTrue(life.created(false))
    }

    @Test
    fun replacementControllerCancelsOldRelease() {
        val life = MatterLifecycle()
        life.creating()
        assertFalse(life.stopped(false))
        assertFalse(life.created(true))
    }

    @Test
    fun multipleCreatorsFinishBeforeCleanup() {
        val life = MatterLifecycle()
        life.creating()
        life.creating()
        assertFalse(life.stopped(false))
        assertFalse(life.created(false))
        assertTrue(life.created(false))
    }

    @Test
    fun restartedLifecycleCanStopAgain() {
        val life = MatterLifecycle()
        assertTrue(life.stopped(false))
        life.creating()
        assertFalse(life.created(true))
        assertTrue(life.stopped(false))
    }
}
