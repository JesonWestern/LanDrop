package com.landrop.transfer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.landrop.transfer.model.FileItem

/**
 * 文件/分类/传输列表统一使用的扁平行模型。
 *
 * 只有把「分区标题 + 文件行」摊平成一个列表，拖选时 LazyListState.layoutInfo 的 index
 * 才能和行一一对应（旧实现由多段 items + item 拼成，无法做命中测试）。
 */
sealed interface ListRow {
    val key: String

    data class Header(val text: String, val id: String) : ListRow {
        override val key: String get() = "h:$id"
    }

    data class Item(val item: FileItem, private val prefix: String = "") : ListRow {
        override val key: String get() = prefix + item.path
    }

    data class Empty(val text: String) : ListRow {
        override val key: String get() = "empty"
    }

    data object Bottom : ListRow {
        override val key: String get() = "bottom"
    }
}

/** 可保存的 Set<String> 状态（选中集合）。 */
@Composable
internal fun rememberStringSetState(initial: Set<String> = emptySet()): MutableState<Set<String>> =
    rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        )
    ) { mutableStateOf(initial) }

/** 可保存的 Set<Int> 状态（传输页展开集合）。 */
@Composable
internal fun rememberIntSetState(initial: Set<Int> = emptySet()): MutableState<Set<Int>> =
    rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        )
    ) { mutableStateOf(initial) }

/**
 * 从右侧选择区按下并拖动来连续选中。
 *
 * 手法的关键点：
 *  - 挂在 LazyColumn 外层，用 [PointerEventPass.Initial] 抢在 scrollable/clickable 之前看到事件；
 *  - **不消费 down/up，只消费移动**：轻点仍由选择区自己的 toggleable 处理，列表正文照常滚动，
 *    只有「按下点在右侧 gutter 且确实移动了」才进入拖选并阻止滚动；
 *  - 命中测试用 layoutInfo 的 offset/size，命中 Header/空态时跳过；
 *  - 回调带 index，调用方用「上一命中 → 当前命中」补齐区间，快速拖动不漏行。
 */
@Composable
internal fun Modifier.gutterDragSelect(
    listState: LazyListState,
    gutterWidth: Dp,
    rows: () -> List<ListRow>,
    onDragStart: (Int, ListRow.Item) -> Unit,
    onDragOver: (Int, ListRow.Item) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val currentRows by rememberUpdatedState(rows)
    val startCb by rememberUpdatedState(onDragStart)
    val overCb by rememberUpdatedState(onDragOver)
    val gutterPx = with(density) { gutterWidth.toPx() }
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x < size.width - gutterPx) return@awaitEachGesture

            fun hit(y: Float): Pair<Int, ListRow.Item>? {
                val info = listState.layoutInfo.visibleItemsInfo
                for (i in info) {
                    if (y >= i.offset && y < i.offset + i.size) {
                        val row = currentRows().getOrNull(i.index)
                        return if (row is ListRow.Item) i.index to row else null
                    }
                }
                return null
            }

            var lastY = down.position.y
            var moved = false
            var anchored = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (moved) ch.consume()
                    break
                }
                if (!moved && ch.position.y == lastY) continue
                if (!moved) {
                    moved = true
                    hit(down.position.y)?.let {
                        anchored = true
                        startCb(it.first, it.second)
                    }
                }
                ch.consume()
                lastY = ch.position.y
                if (anchored) hit(lastY)?.let { overCb(it.first, it.second) }
            }
        }
    }
}
