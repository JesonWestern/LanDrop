package com.landrop.transfer.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileWalkTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("landrop-filewalk").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun parentAndChildSelectionDoesNotDuplicate() {
        val folder = File(root, "folder").apply { mkdirs() }
        val a = File(folder, "a.txt").apply { writeText("a") }
        val b = File(folder, "b.txt").apply { writeText("b") }
        val entries = FileWalk.collect(listOf(folder.absolutePath, a.absolutePath, b.absolutePath))
        assertEquals(2, entries.size)
        assertEquals(setOf("folder/a.txt", "folder/b.txt"), entries.map { it.rel }.toSet())
    }

    @Test
    fun duplicateSelectedPathIsDeduped() {
        val f = File(root, "a.txt").apply { writeText("a") }
        val entries = FileWalk.collect(listOf(f.absolutePath, f.absolutePath))
        assertEquals(1, entries.size)
        assertEquals("a.txt", entries[0].rel)
    }

    @Test
    fun emptyDirectoryBecomesEntry() {
        val empty = File(root, "empty").apply { mkdirs() }
        val entries = FileWalk.collect(listOf(empty.absolutePath))
        assertEquals(1, entries.size)
        assertTrue(entries[0].isDir)
        assertEquals("empty", entries[0].rel)
    }

    @Test
    fun hiddenFilesAreSkippedInsideFolder() {
        val folder = File(root, "folder").apply { mkdirs() }
        File(folder, "visible.txt").writeText("v")
        File(folder, ".hidden.txt").writeText("h")
        val entries = FileWalk.collect(listOf(folder.absolutePath))
        assertEquals(listOf("folder/visible.txt"), entries.map { it.rel })
    }

    @Test
    fun sameFileNameFromDifferentDirsGetsUniqueRel() {
        // 接收端按 rel 定位 .part：两条同名文件如果 rel 相同会互相覆盖，必须在发送端消歧
        val d1 = File(root, "a").apply { mkdirs() }
        val d2 = File(root, "b").apply { mkdirs() }
        val f1 = File(d1, "same.txt").apply { writeText("1") }
        val f2 = File(d2, "same.txt").apply { writeText("2") }
        val entries = FileWalk.collect(listOf(f1.absolutePath, f2.absolutePath))
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.rel }.toSet().size)
        assertTrue(entries.any { it.rel == "same.txt" })
        assertTrue(entries.any { it.rel == "same (1).txt" })
    }
}
