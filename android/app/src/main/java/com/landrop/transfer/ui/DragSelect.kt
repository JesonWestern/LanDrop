package com.landrop.transfer.ui

import com.landrop.transfer.model.FileItem

/**
 * 拖选相关的**纯函数**（不依赖 Compose / Android，可 JVM 单测）。
 *
 * 1.3 起拖选的语义变了：**同一个手势既能选中也能取消选中**。
 * 按下前先看锚点行当前是否已选中 —— 已选中，说明用户多半想"把这串勾去掉"，
 * 于是本次拖动进入 [DragMode.DESELECT]；否则进 [DragMode.SELECT]。
 * 这样"只能拖动选中、无法拖动取消"的问题就被消掉了，且不引入第二个入口。
 *
 * 区间计算**复用** [pathsInRange]，不另写一套：行列表里混着分区标题/空态
 * （path = null）时的跳过语义、越界钳制语义必须只有一种口径。
 */
enum class DragMode { SELECT, DESELECT }

/** 锚点已选中 → 本次拖动为取消模式；否则为选中模式。 */
fun dragModeFor(anchorWasSelected: Boolean): DragMode =
    if (anchorWasSelected) DragMode.DESELECT else DragMode.SELECT

/**
 * 把 `[from, to]` 区间的行按 [mode] 合并进当前选中集合。
 *
 * @param cur 当前选中集合（不变式：函数不修改入参，返回新集合）
 * @param rowPaths 行的文件路径，标题/空态/占位行为 null
 * @param from/to 锚点行与当前命中行的 index；-1 视为无操作
 * @return SELECT → 区间并集；DESELECT → 区间差集
 */
fun applyDragRange(
    cur: Set<String>,
    rowPaths: List<String?>,
    from: Int,
    to: Int,
    mode: DragMode,
): Set<String> {
    val range = pathsInRange(rowPaths, from, to)
    if (range.isEmpty()) return cur
    return when (mode) {
        DragMode.SELECT -> cur + range
        DragMode.DESELECT -> cur - range
    }
}

/** 全选：当前作用域（本页签全部可见行）的所有文件路径。 */
fun selectAll(rowPaths: List<String?>): Set<String> =
    rowPaths.filterNotNull().toSet()

/**
 * 反选：作用域内每行取反。
 *
 * 作用域外的旧选中项**保留**（而不是悄悄丢掉）：拖选/全选都以可见行为准，
 * 不做隐式清理，避免把用户在上一次操作里勾的东西吞掉。
 */
fun invertSelection(cur: Set<String>, rowPaths: List<String?>): Set<String> {
    val scope = rowPaths.filterNotNull().toSet()
    if (scope.isEmpty()) return cur
    val kept = cur.filterNot { it in scope }
    val flipped = scope - cur
    return kept.toSet() + flipped
}

/** 选中项总字节数（按路径查 FileItem；目录 size 为 0，天然不计入）。 */
fun selectionBytes(paths: Set<String>, byPath: Map<String, FileItem>): Long =
    paths.sumOf { p -> byPath[p]?.size ?: 0L }
