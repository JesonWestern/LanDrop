package com.landrop.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionRangeTest {

    private val paths = listOf(null, "a", null, "b", "c", null)

    @Test
    fun forwardRange() {
        assertEquals(setOf("a", "b", "c"), pathsInRange(paths, 1, 4))
    }

    @Test
    fun reverseRange() {
        assertEquals(setOf("a", "b", "c"), pathsInRange(paths, 4, 1))
    }

    @Test
    fun skipsNonFileRows() {
        assertEquals(setOf("b", "c"), pathsInRange(paths, 2, 4))
    }

    @Test
    fun clampsIndexes() {
        assertEquals(setOf("a", "b", "c"), pathsInRange(paths, 0, 99))
        assertEquals(emptySet<String>(), pathsInRange(paths, 4, 99).minus(setOf("c")))
    }

    @Test
    fun emptyOrInvalid() {
        assertEquals(emptySet<String>(), pathsInRange(emptyList(), 0, 1))
        assertEquals(emptySet<String>(), pathsInRange(paths, -1, 2))
    }
}
