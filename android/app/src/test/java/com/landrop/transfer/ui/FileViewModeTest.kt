package com.landrop.transfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [FileViewMode] 纯函数测试：未知回落 + 循环切换。 */
class FileViewModeTest {

    @Test
    fun knownIdsParse() {
        assertEquals(FileViewMode.LIST, viewModeOf("list"))
        assertEquals(FileViewMode.COMPACT, viewModeOf("compact"))
        assertEquals(FileViewMode.GRID, viewModeOf("grid"))
    }

    @Test
    fun unknownFallsBackToList() {
        assertEquals(FileViewMode.LIST, viewModeOf(null))
        assertEquals(FileViewMode.LIST, viewModeOf(""))
        assertEquals(FileViewMode.LIST, viewModeOf("   "))
        assertEquals(FileViewMode.LIST, viewModeOf("kanban"))
        assertEquals(FileViewMode.LIST, viewModeOf("grid2"))
    }

    @Test
    fun idIsCaseInsensitive() {
        assertEquals(FileViewMode.GRID, viewModeOf("GRID"))
        assertEquals(FileViewMode.COMPACT, viewModeOf(" Compact "))
    }

    @Test
    fun nextCyclesThroughAllModes() {
        var m = FileViewMode.LIST
        val seen = mutableListOf(m.label)
        repeat(FileViewMode.entries.size - 1) {
            m = m.next()
            seen.add(m.label)
        }
        assertEquals(listOf("列表", "紧凑", "网格"), seen)
        // 再切一次回到起点，说明是环而不是线性
        assertEquals(FileViewMode.LIST, m.next())
    }

    /** 持久化写回时用的 id 必须是稳定小写，且能被 viewModeOf 还原。 */
    @Test
    fun idRoundTripsThroughParse() {
        FileViewMode.entries.forEach { m ->
            assertEquals(m, viewModeOf(m.id))
        }
    }
}
