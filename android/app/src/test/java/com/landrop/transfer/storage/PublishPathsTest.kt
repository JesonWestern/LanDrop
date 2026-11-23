package com.landrop.transfer.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishPathsTest {

    @Test
    fun splitWithSubdirectory() {
        val (rel, sub, name) = PublishPaths.split("a/b/c.txt")
        assertEquals("a/b/c.txt", rel)
        assertEquals("a/b", sub)
        assertEquals("c.txt", name)
    }

    @Test
    fun splitTopLevel() {
        val (rel, sub, name) = PublishPaths.split("c.txt")
        assertEquals("c.txt", rel)
        assertEquals("", sub)
        assertEquals("c.txt", name)
    }

    @Test
    fun normalizesBackslashesAndLeadingSlash() {
        val (rel, sub, name) = PublishPaths.split("\\a\\b\\c.txt")
        assertEquals("a/b/c.txt", rel)
        assertEquals("a/b", sub)
        assertEquals("c.txt", name)
    }

    @Test
    fun displayDir() {
        assertEquals("Download/LanDrop", PublishPaths.displayDir(""))
        assertEquals("Download/LanDrop/x/y", PublishPaths.displayDir("x/y"))
    }

    @Test
    fun numberedName() {
        assertEquals("a.txt", PublishPaths.numberedName("a.txt", 0))
        assertEquals("a (1).txt", PublishPaths.numberedName("a.txt", 1))
        assertEquals("noext (2)", PublishPaths.numberedName("noext", 2))
        assertEquals(".hidden (1)", PublishPaths.numberedName(".hidden", 1))
    }
}
