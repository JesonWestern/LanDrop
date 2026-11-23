package com.landrop.transfer.storage

import com.landrop.transfer.model.FileItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「最近文件」按日期分段（纯函数，可 JVM 单测）。
 *
 * 参考系统文件管理器：最近的按天分段，标题「今天 / 昨天 / M月d日」，
 * 不是今年的补年份「yyyy年M月d日」——否则 2024 的文件会跟今年的混成一片。
 *
 * 实现要点：
 *  - 日期换算必须**注入 ZoneId**：设备时区可以是任意值，凌晨 0:30 的文件
 *    在 UTC 与 UTC+8 下属于不同的一天，测试用固定 zone 才能稳定断言；
 *  - 段序 = 日期倒序；段内序 = mtime 倒序（mtime 相同再按 path 定序，保证输出确定）；
 *  - mtime <= 0（MediaStore 偶发脏数据）按"当前时间"归入今天，避免冒出一个 1970 年分组。
 */
data class RecentSection(val title: String, val items: List<FileItem>)

/**
 * @param items 最近文件（顺序无所谓，函数自己会重排）
 * @param nowMs "今天"的判定基准（毫秒时间戳），测试里传固定值
 * @param zone 用来把时间戳换算成日期的时区
 */
fun groupRecentByDate(items: List<FileItem>, nowMs: Long, zone: ZoneId): List<RecentSection> {
    if (items.isEmpty()) return emptyList()
    val today = dateOf(nowMs, zone)
    val yesterday = today.minusDays(1)

    // 先按日期归组（LinkedHashMap 无所谓，最后统一按日期倒序排）
    val buckets = HashMap<LocalDate, MutableList<FileItem>>()
    for (item in items) {
        val date = dateOf(item.mtime, zone, fallback = today)
        buckets.getOrPut(date) { ArrayList() }.add(item)
    }

    return buckets.entries
        .sortedByDescending { it.key }
        .map { (date, list) ->
            // 段内 mtime 倒序；mtime 相同用 path 定序，避免不同设备/不同 JDK 下顺序漂移
            list.sortWith(
                compareByDescending<FileItem> { it.mtime }.thenBy { it.path }
            )
            RecentSection(
                title = sectionTitle(date, today, yesterday),
                items = list,
            )
        }
}

private fun dateOf(
    ms: Long,
    zone: ZoneId,
    fallback: LocalDate? = null,
): LocalDate {
    if (ms <= 0L) return fallback ?: LocalDate.ofEpochDay(0)
    return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}

private fun sectionTitle(date: LocalDate, today: LocalDate, yesterday: LocalDate): String = when {
    date == today -> "今天"
    date == yesterday -> "昨天"
    date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
    else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}
