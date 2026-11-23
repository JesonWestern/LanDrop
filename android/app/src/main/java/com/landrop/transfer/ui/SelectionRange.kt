package com.landrop.transfer.ui

/**
 * 拖选区间计算（纯函数，便于单测）。
 * @param paths 行的文件路径；标题/空态/占位行传 null
 * @return 区间内所有文件路径（自动跳过 null）
 */
internal fun pathsInRange(paths: List<String?>, from: Int, to: Int): Set<String> {
    if (paths.isEmpty()) return emptySet()
    // -1 是拖选尚未开始的哨兵值：视为无操作
    if (from < 0 || to < 0) return emptySet()
    val lo = minOf(from, to).coerceIn(0, paths.lastIndex)
    val hi = maxOf(from, to).coerceIn(0, paths.lastIndex)
    if (lo > hi) return emptySet()
    val out = HashSet<String>(hi - lo + 1)
    for (i in lo..hi) paths.getOrNull(i)?.let { out.add(it) }
    return out
}
