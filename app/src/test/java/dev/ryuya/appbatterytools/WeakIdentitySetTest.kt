package dev.ryuya.appbatterytools

import org.junit.Assert.*
import org.junit.Test

class WeakIdentitySetTest {
    @Test
    fun duplicateIdentityIsRecognized() {
        val set = WeakIdentitySet()
        val value = Any()
        assertTrue(set.add(value))
        assertFalse(set.add(value))
    }

    @Test
    fun equalObjectsWithDifferentOwnershipRemainDistinct() {
        data class Value(val number: Int)
        val set = WeakIdentitySet()
        val first = Value(1)
        val second = Value(1)
        assertEquals(first, second)
        assertTrue(set.add(first))
        assertTrue(set.add(second))
    }
}
