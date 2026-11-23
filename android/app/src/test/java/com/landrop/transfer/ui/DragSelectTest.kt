package com.landrop.transfer.ui

import com.landrop.transfer.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DragSelect] 纯函数测试：区间、null 跳过、边界钳制、全选/反选、字节求和。 */
class DragSelectTest {

    private fun item(path: String, size: Long = 0L) = FileItem(
        name = path.substringAfterLast('/'),
        path = path,
        isDir = false,
        size = size,
        mtime = 0L,
    )

    /** 行列表里混着两个分区标题（null）。 */
    private val rows = listOf(null, "a", "b", null, "c", "d", null)

    // ---- dragModeFor ---------------------------------------------------------

    @Test
    fun dragModeFromAnchorState() {
        assertEquals(DragMode.SELECT, dragModeFor(anchorWasSelected = false))
        assertEquals(DragMode.DESELECT, dragModeFor(anchorWasSelected = true))
    }

    // ---- applyDragRange: SELECT ---------------------------------------------

    @Test
    fun selectForwardRange() {
        val out = applyDragRange(emptySet(), rows, 1, 4, DragMode.SELECT)
        assertEquals(setOf("a", "b", "c"), out)
    }

    @Test
    fun selectReverseRangeIsSameSet() {
        assertEquals(
            applyDragRange(emptySet(), rows, 1, 4, DragMode.SELECT),
            applyDragRange(emptySet(), rows, 4, 1, DragMode.SELECT),
        )
    }

    @Test
    fun selectIsUnionWithExisting() {
        val out = applyDragRange(setOf("x", "d"), rows, 2, 4, DragMode.SELECT)
        assertEquals(setOf("x", "d", "b", "c"), out)
    }

    @Test
    fun selectSkipsNullRows() {
        val out = applyDragRange(emptySet(), rows, 1, 1, DragMode.SELECT)
        assertEquals(setOf("a"), out)
        // 区间只覆盖标题行时没有任何变化
        assertEquals(setOf("a"), applyDragRange(setOf("a"), rows, 0, 0, DragMode.SELECT))
        assertEquals(setOf("a"), applyDragRange(setOf("a"), rows, 3, 3, DragMode.SELECT))
    }

    @Test
    fun selectClampsOutOfBoundsIndexes() {
        // 上界越界会被钳制到 lastIndex（下界为负是"无操作"哨兵，见下个用例）
        val out = applyDragRange(emptySet(), rows, 0, 99, DragMode.SELECT)
        assertEquals(setOf("a", "b", "c", "d"), out)
    }

    @Test
    fun selectInvalidSentinelIsNoop() {
        // -1 是"拖选尚未开始"的哨兵值；任何负下界都按 pathsInRange 的既有语义视为无操作
        assertEquals(emptySet<String>(), applyDragRange(emptySet(), rows, -1, 2, DragMode.SELECT))
        assertEquals(setOf("z"), applyDragRange(setOf("z"), rows, 3, -1, DragMode.SELECT))
        assertEquals(
            setOf("z"),
            applyDragRange(setOf("z"), rows, -5, 99, DragMode.SELECT),
        )
    }

    @Test
    fun selectOnEmptyRowListIsNoop() {
        assertEquals(setOf("keep"), applyDragRange(setOf("keep"), emptyList(), 0, 5, DragMode.SELECT))
    }

    // ---- applyDragRange: DESELECT -------------------------------------------

    @Test
    fun deselectRemovesRange() {
        val cur = setOf("a", "b", "c", "d", "keep")
        assertEquals(setOf("d", "keep"), applyDragRange(cur, rows, 1, 4, DragMode.DESELECT))
    }

    @Test
    fun deselectReverseRangeSameAsForward() {
        val cur = setOf("a", "b", "c")
        assertEquals(
            applyDragRange(cur, rows, 1, 4, DragMode.DESELECT),
            applyDragRange(cur, rows, 4, 1, DragMode.DESELECT),
        )
    }

    @Test
    fun deselectKeepsSelectionOutsideRange() {
        // 区间 1..2 只覆盖 a、b，选中的 c、d 必须原样保留
        val cur = setOf("c", "d")
        assertEquals(cur, applyDragRange(cur, rows, 1, 2, DragMode.DESELECT))
        // 区间扩到 1..4 才动到 c
        assertEquals(setOf("d"), applyDragRange(cur, rows, 1, 4, DragMode.DESELECT))
    }

    @Test
    fun deselectIsIdempotent() {
        // 区间外还有一项，确保第一次取消后集合非空，才能验证"再取消一次不会继续掉"
        val once = applyDragRange(setOf("a", "b", "out"), rows, 1, 2, DragMode.DESELECT)
        val twice = applyDragRange(once, rows, 1, 2, DragMode.DESELECT)
        assertEquals(setOf("out"), once)
        assertEquals(once, twice)
    }

    @Test
    fun deselectSkipsNullRowsButStillRemovesFileRows() {
        val cur = setOf("a", "c")
        // 区间含两个标题行（index 0、3），只有真实文件行被移除
        assertEquals(setOf("c"), applyDragRange(cur, rows, 0, 3, DragMode.DESELECT))
    }

    /** 从锚点重算基线的关键性质：拖出去再拖回来要回到初始状态。 */
    @Test
    fun dragOutAndBackRestoresBaseline() {
        val base = setOf("a", "b")
        val out = applyDragRange(base, rows, 1, 5, DragMode.SELECT)
        assertEquals(setOf("a", "b", "c", "d"), out)
        val back = applyDragRange(base, rows, 1, 1, DragMode.SELECT)
        assertEquals(base, back)
    }

    // ---- selectAll / invertSelection ---------------------------------------

    @Test
    fun selectAllSkipsNull() {
        assertEquals(setOf("a", "b", "c", "d"), selectAll(rows))
    }

    @Test
    fun selectAllOnEmptyRows() {
        assertEquals(emptySet<String>(), selectAll(emptyList()))
        assertEquals(emptySet<String>(), selectAll(listOf(null, null)))
    }

    @Test
    fun invertFillsGaps() {
        // 空选中 → 作用域全选
        assertEquals(setOf("a", "b", "c", "d"), invertSelection(emptySet(), rows))
        // 选了 b、d → 剩下 a、c
        assertEquals(setOf("a", "c"), invertSelection(setOf("b", "d"), rows))
        // 全选 → 清空（作用域内）
        assertEquals(emptySet<String>(), invertSelection(setOf("a", "b", "c", "d"), rows))
    }

    @Test
    fun invertIsInvolutionOnSameScope() {
        val cur = setOf("a", "c")
        val once = invertSelection(cur, rows)
        assertEquals(cur, invertSelection(once, rows))
    }

    @Test
    fun invertKeepsOutOfScopeEntries() {
        // 作用域外的旧选中项保留，不做隐式清理
        val out = invertSelection(setOf("keep", "a"), rows)
        assertEquals(setOf("keep", "b", "c", "d"), out)
    }

    @Test
    fun invertOnEmptyScopeIsNoop() {
        assertEquals(setOf("a"), invertSelection(setOf("a"), emptyList()))
        assertEquals(setOf("a"), invertSelection(setOf("a"), listOf(null, null)))
    }

    // ---- selectionBytes ----------------------------------------------------

    @Test
    fun bytesSumSelected() {
        val byPath = mapOf(
            "a" to item("a", 100),
            "b" to item("b", 200),
            "c" to item("c", 300),
        )
        assertEquals(300L, selectionBytes(setOf("a", "b"), byPath))
        assertEquals(600L, selectionBytes(setOf("a", "b").plus("c"), byPath))
    }

    @Test
    fun bytesIgnoresUnknownPaths() {
        val byPath = mapOf("a" to item("a", 100))
        assertEquals(100L, selectionBytes(setOf("a", "gone"), byPath))
        assertEquals(0L, selectionBytes(setOf("gone"), byPath))
    }

    @Test
    fun bytesEmptySelection() {
        val byPath = mapOf("a" to item("a", 100))
        assertEquals(0L, selectionBytes(emptySet(), byPath))
        assertEquals(0L, selectionBytes(setOf("a"), emptyMap()))
    }

    @Test
    fun bytesDirectoryCountsAsZero() {
        val dir = FileItem("photos", "/photos", isDir = true, size = 0L, mtime = 0L)
        val file = item("/photos.jpg", 512)
        assertEquals(512L, selectionBytes(setOf("/photos", "/photos.jpg"), mapOf(
            "/photos" to dir, "/photos.jpg" to file,
        )))
    }
}
