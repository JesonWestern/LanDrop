package com.landrop.transfer.storage

import com.landrop.transfer.model.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPolicyTest {

    private fun row(
        id: Long,
        path: String,
        size: Long,
        mime: String = "image/jpeg",
        name: String = path.substringAfterLast('/'),
    ) = MediaRow(id, path, name, size, 1000L, mime)

    @Test
    fun visiblePathRules() {
        assertTrue(CatalogPolicy.isVisiblePath("/storage/emulated/0/DCIM/a.jpg"))
        assertFalse(CatalogPolicy.isVisiblePath("/storage/emulated/0/Android/data/x/a.jpg"))
        assertFalse(CatalogPolicy.isVisiblePath("/storage/emulated/0/Android/obb/x/a.obb"))
        assertFalse(CatalogPolicy.isVisiblePath("/storage/emulated/0/Pictures/.gs/a.jpg"))
        assertFalse(CatalogPolicy.isVisiblePath("/storage/emulated/0/.hidden"))
        assertTrue(CatalogPolicy.isVisiblePath("/storage/emulated/0/a.txt"))
    }

    @Test
    fun dropsDirectoriesStaleHiddenDuplicatesAndZeroSize() {
        val rows = listOf(
            // 目录行：mime 为空、size=4096
            row(1, "/storage/emulated/0/Android", 4096, mime = ""),
            // 已删除的陈旧行
            row(2, "/storage/emulated/0/stale.png", 1024),
            // 隐藏父目录
            row(3, "/storage/emulated/0/Pictures/.gs/a.jpg", 2048),
            // 重复路径
            row(4, "/storage/emulated/0/DCIM/ok.jpg", 3000),
            row(5, "/storage/emulated/0/DCIM/ok.jpg", 3000),
            // 零字节
            row(6, "/storage/emulated/0/DCIM/zero.jpg", 0),
            // 正常
            row(7, "/storage/emulated/0/DCIM/ok2.jpg", 5000),
        )
        val items = CatalogPolicy.toItems(
            rows = rows,
            isDir = { it.endsWith("/Android") },
            exists = { it != "/storage/emulated/0/stale.png" },
            uriOf = { "content://media/external/file/$it" },
        )
        assertEquals(listOf("/storage/emulated/0/DCIM/ok.jpg", "/storage/emulated/0/DCIM/ok2.jpg"),
            items.map { it.path })
        assertEquals("content://media/external/file/4", items[0].contentUri)
        assertEquals(FileKind.IMAGE, items[0].kind)
    }

    @Test
    fun bucketsAggregateCountsAndBytes() {
        val items = listOf(
            com.landrop.transfer.model.FileItem("a.jpg", "/a.jpg", false, 100, 0L),
            com.landrop.transfer.model.FileItem("b.png", "/b.png", false, 200, 0L),
            com.landrop.transfer.model.FileItem("c.pdf", "/c.pdf", false, 300, 0L),
        )
        val buckets = CatalogPolicy.buckets(items)
        val img = buckets.first { it.kind == FileKind.IMAGE }
        val doc = buckets.first { it.kind == FileKind.DOC }
        assertEquals(2, img.count)
        assertEquals(300L, img.bytes)
        assertEquals(1, doc.count)
        assertEquals(300L, doc.bytes)
        assertEquals(items.size, buckets.sumOf { it.count })
    }

    @Test
    fun truncationFlagBoundary() {
        // 请求的是 LIMIT+1 行：恰好 LIMIT 行不算截断，超过才算
        assertFalse(CatalogPolicy.truncated(CatalogPolicy.LIMIT))
        assertTrue(CatalogPolicy.truncated(CatalogPolicy.LIMIT + 1))
    }

    @Test
    fun rowsWithoutIdProduceNoUri() {
        val items = CatalogPolicy.toItems(
            rows = listOf(row(0, "/storage/emulated/0/a.jpg", 10)),
            isDir = { false },
            exists = { true },
            uriOf = { "should-not-be-used" },
        )
        assertEquals("", items.single().contentUri)
    }
}
