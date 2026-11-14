package com.landrop.transfer.storage

import com.landrop.transfer.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSortTest {

    private fun f(name: String, size: Long, mtime: Long): FileItem =
        FileItem(name = name, path = "/sdcard/$name", isDir = false, size = size, mtime = mtime)

    @Test
    fun nameAscending() {
        val list = listOf(f("c.txt", 1, 3), f("a.txt", 2, 2), f("b.txt", 3, 1))
        val r = CatalogSort(CatalogSort.Key.NAME, asc = true).sort(list)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), r.map { it.name })
    }

    @Test
    fun sizeDescending() {
        val list = listOf(f("a", 10, 1), f("b", 300, 2), f("c", 20, 3))
        val r = CatalogSort(CatalogSort.Key.SIZE, asc = false).sort(list)
        assertEquals(listOf("b", "c", "a"), r.map { it.name })
    }

    @Test
    fun timeDescendingIsDefault() {
        val list = listOf(f("old", 1, 100), f("new", 1, 900), f("mid", 1, 500))
        val r = CatalogSort().sort(list)
        assertEquals(listOf("new", "mid", "old"), r.map { it.name })
    }

    @Test
    fun toggleSameKeyFlipsDirection() {
        val s = CatalogSort(CatalogSort.Key.TIME, asc = false)
        assertEquals(true, s.toggle(CatalogSort.Key.TIME).asc)
        // 换键时回到该键的默认方向
        assertEquals(false, s.toggle(CatalogSort.Key.SIZE).asc)
    }

    @Test
    fun tiesFallBackToName() {
        val list = listOf(f("b", 5, 1), f("a", 5, 1), f("c", 5, 1))
        val r = CatalogSort(CatalogSort.Key.SIZE, asc = true).sort(list)
        assertEquals(listOf("a", "b", "c"), r.map { it.name })
    }

    @Test
    fun emptyAndSingleAreReturnedAsIs() {
        assertEquals(0, CatalogSort().sort(emptyList()).size)
        assertEquals(listOf("x"), CatalogSort().sort(listOf(f("x", 1, 1))).map { it.name })
    }
}
