package com.landrop.transfer.storage

import com.landrop.transfer.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [groupRecentByDate] 纯函数测试：今天/昨天/跨年/M月d日、段序、段内序、午夜边界、ZoneId 注入。 */
class RecentGroupTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun item(path: String, mtime: Long) = FileItem(
        name = path.substringAfterLast('/'),
        path = path,
        isDir = false,
        size = 1L,
        mtime = mtime,
    )

    // 基准"现在"= 2026-11-23 12:00（Asia/Shanghai）
    private val now = at(LocalDate.of(2026, 11, 23), 12, 0)
    private val today = LocalDate.of(2026, 11, 23)
    private val yesterday = today.minusDays(1)

    @Test
    fun todayAndYesterdayTitles() {
        val items = listOf(
            item("/a", at(today, 9)),
            item("/b", at(yesterday, 20)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("今天", "昨天"), out.map { it.title })
    }

    @Test
    fun olderDateUsesMonthDayWithoutYear() {
        val items = listOf(
            item("/a", at(today, 9)),
            item("/old", at(LocalDate.of(2026, 3, 5), 8)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("今天", "3月5日"), out.map { it.title })
    }

    @Test
    fun crossYearAddsYear() {
        val items = listOf(item("/last-year", at(LocalDate.of(2025, 12, 31), 23)))
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("2025年12月31日"), out.map { it.title })
    }

    @Test
    fun sectionsAreOrderedByDateDescending() {
        val items = listOf(
            item("/old", at(LocalDate.of(2026, 1, 2), 8)),
            item("/today1", at(today, 10)),
            item("/mid", at(LocalDate.of(2026, 6, 1), 8)),
            item("/yesterday1", at(yesterday, 10)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("今天", "昨天", "6月1日", "1月2日"), out.map { it.title })
    }

    @Test
    fun itemsInsideSectionAreOrderedByMtimeDescending() {
        val items = listOf(
            item("/a", at(today, 1)),
            item("/b", at(today, 23)),
            item("/c", at(today, 12)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(1, out.size)
        assertEquals(listOf("/b", "/c", "/a"), out[0].items.map { it.path })
    }

    @Test
    fun sameTimestampFallsBackToPathForStableOrder() {
        val t = at(today, 12)
        val items = listOf(item("/z", t), item("/a", t), item("/m", t))
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("/a", "/m", "/z"), out[0].items.map { it.path })
    }

    /** 午夜边界：23:59:59 属于前一天，00:00 属于今天。 */
    @Test
    fun midnightBoundaryBelongsToCorrectDay() {
        val tJustBefore = at(yesterday, 23, 59)
        val tMidnight = at(today, 0, 0)
        val items = listOf(item("/before", tJustBefore), item("/midnight", tMidnight))
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("今天", "昨天"), out.map { it.title })
        assertEquals("/midnight", out[0].items.single().path)
        assertEquals("/before", out[1].items.single().path)
    }

    /** ZoneId 注入：同一时间戳在 UTC 与 UTC+8 下可能落在不同的一天。 */
    @Test
    fun zoneIdChangesWhichDayAnItemBelongsTo() {
        // 2026-11-23 00:30 +08:00 == 2026-11-22 16:30 UTC
        val ts = at(today, 0, 30)
        val inShanghai = groupRecentByDate(listOf(item("/x", ts)), now, zone)
        assertEquals(listOf("今天"), inShanghai.map { it.title })

        // 换到 UTC 视角，"今天"是 2026-11-23 UTC；该时间戳是 11-22 16:30 UTC → 昨天
        val utcNow = LocalDateTime.of(2026, 11, 23, 12, 0)
            .atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val inUtc = groupRecentByDate(listOf(item("/x", ts)), utcNow, ZoneOffset.UTC)
        assertEquals(listOf("昨天"), inUtc.map { it.title })
    }

    /** nowMs 换算出的"今天"同样跟 zone 走。 */
    @Test
    fun todayFollowsTheInjectedZone() {
        // 现在是 UTC 11-23 00:30，对 +08:00 而言已经是 11-23 08:30（同一个日期，这里换个极端值验证）
        val ts = at(LocalDate.of(2026, 11, 22), 20, 0)
        assertEquals(
            listOf("昨天"),
            groupRecentByDate(listOf(item("/x", ts)), now, zone).map { it.title },
        )
        val laterNow = at(today, 23)
        assertEquals(
            listOf("昨天"),
            groupRecentByDate(listOf(item("/x", ts)), laterNow, zone).map { it.title },
        )
    }

    @Test
    fun emptyInputYieldsNoSections() {
        assertTrue(groupRecentByDate(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun itemsInSameDayShareOneSection() {
        val items = listOf(
            item("/a", at(today, 8)),
            item("/b", at(today, 18)),
            item("/c", at(yesterday, 18)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(2, out.size)
        assertEquals(2, out[0].items.size)
        assertEquals(1, out[1].items.size)
    }

    /** mtime <= 0 的脏数据归入今天，不应该冒出一个 1970 年分组。 */
    @Test
    fun nonPositiveMtimeFallsIntoTodaysSection() {
        val items = listOf(item("/dirty", 0L), item("/ok", at(today, 9)))
        val out = groupRecentByDate(items, now, zone)
        assertEquals(listOf("今天"), out.map { it.title })
        assertEquals(2, out[0].items.size)
    }

    /** 分组不丢数据：所有输入项都必须出现在输出里恰好一次。 */
    @Test
    fun groupingPreservesAllItems() {
        val items = listOf(
            item("/a", at(today, 1)),
            item("/b", at(yesterday, 1)),
            item("/c", at(LocalDate.of(2024, 7, 9), 1)),
            item("/d", at(LocalDate.of(2025, 2, 28), 1)),
        )
        val out = groupRecentByDate(items, now, zone)
        assertEquals(items.size, out.sumOf { it.items.size })
        assertEquals(items.map { it.path }.toSet(), out.flatMap { it.items }.map { it.path }.toSet())
    }
}
