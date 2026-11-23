package com.landrop.transfer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.storage.FsBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「文件」页：像系统文件管理器一样浏览真实目录。
 *
 * 1.2 交互：
 *  - 点文件 → 系统默认应用打开；点目录 → 进入；
 *  - 右侧 56dp 选择区切换选中，按住该区域上下拖动可连续选中；
 *  - 系统返回手势：先清选择，再回上一级目录，根目录时交给系统退出。
 */
@Composable
fun FileScreen(devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember { FsBrowser.root() }
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess(ctx)) }
    var curDirPath by rememberSaveable { mutableStateOf(root.absolutePath) }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    val selected = rememberStringSetState()
    var tick by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var showHint by rememberSaveable { mutableStateOf(!Engine.settings.selectionHintSeen) }

    val curDir = remember(curDirPath, root) {
        val f = File(curDirPath)
        if (f.isDirectory && isUnderRoot(f, root)) f else root
    }

    val grant = rememberGrantStorageAccess { tick++ }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess(ctx)
                tick++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(curDir, tick, permitted) {
        if (!permitted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val list = FsBrowser.listDir(curDir)
            val rec = if (curDir.absolutePath == root.absolutePath) {
                FsBrowser.recentFiles(ctx, 12)
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                items = list
                recent = rec
            }
        }
    }

    BackHandler(enabled = selected.value.isNotEmpty() || curDir.absolutePath != root.absolutePath) {
        if (selected.value.isNotEmpty()) {
            selected.value = emptySet()
        } else {
            val parent = curDir.parentFile
            curDirPath = if (parent != null && isUnderRoot(parent, root)) parent.absolutePath
            else root.absolutePath
        }
    }

    LaunchedEffect(selected.value) {
        if (selected.value.isNotEmpty() && showHint) {
            showHint = false
            Engine.settings.selectionHintSeen = true
        }
    }

    val selDevice = devices.firstOrNull { it.id == (Engine.selectedId ?: "") }
        ?: devices.firstOrNull()

    // 平铺行模型：分区标题也占一个 index，拖选命中时跳过
    val filePaths = remember(items) { items.mapTo(HashSet()) { it.path } }
    val rows = remember(items, recent, filePaths) {
        buildList {
            if (recent.isNotEmpty()) {
                val extra = recent.filterNot { filePaths.contains(it.path) }
                if (extra.isNotEmpty()) {
                    add(ListRow.Header("最近文件", "recent"))
                    extra.forEach { add(ListRow.Item(it, "recent:")) }
                }
            }
            if (items.isEmpty()) add(ListRow.Empty("这个文件夹是空的"))
            val dirs = items.filter { it.isDir }
            val files = items.filter { !it.isDir }
            if (dirs.isNotEmpty()) {
                add(ListRow.Header("文件夹", "dirs"))
                dirs.forEach { add(ListRow.Item(it)) }
            }
            if (files.isNotEmpty()) {
                add(ListRow.Header("文件", "files"))
                files.forEach { add(ListRow.Item(it)) }
            }
            add(ListRow.Bottom)
        }
    }

    val byPath = remember(items, recent) { (items + recent).associateBy { it.path } }
    val selectedBytes = remember(selected.value, byPath) {
        selected.value.sumOf { byPath[it]?.size ?: 0L }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragLast by remember { mutableIntStateOf(-1) }

    val rowPaths = remember(rows) { rows.map { (it as? ListRow.Item)?.item?.path } }
    fun selectRange(a: Int, b: Int) {
        val add = pathsInRange(rowPaths, a, b)
        if (add.isNotEmpty()) selected.value = selected.value + add
    }

    fun openItem(item: FileItem) {
        if (item.isDir) {
            selected.value = emptySet()
            curDirPath = item.path
        } else {
            openFile(ctx, item.path, item.mime, item.contentUri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        DevicePickerRow(devices) { Engine.select(it) }

        if (!permitted) {
            PermissionCard(legacy = isLegacyStorage(), onGrant = grant)
            return@Column
        }

        Breadcrumb(root = root, cur = curDir) {
            selected.value = emptySet()
            curDirPath = it.absolutePath
        }

        if (showHint && selected.value.isEmpty()) {
            Text(
                "点右侧圆圈多选；按住圆圈上下拖动可连续选中",
                color = LdColors.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .gutterDragSelect(
                        listState = listState,
                        gutterWidth = GUTTER_WIDTH_DP.dp,
                        rows = { rows },
                        onDragStart = { idx, item ->
                            dragLast = idx
                            selected.value = selected.value + item.item.path
                        },
                        onDragOver = { idx, _ ->
                            if (dragLast >= 0 && dragLast != idx) selectRange(dragLast, idx)
                            dragLast = idx
                        },
                    ),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { idx, row ->
                    when (row) {
                        is ListRow.Header -> SectionHeader(row.text)
                        is ListRow.Empty -> EmptyHint(row.text)
                        is ListRow.Item -> FileRow(
                            item = row.item,
                            checked = selected.value.contains(row.item.path),
                            onToggle = { selected.value = toggle(selected.value, row.item.path) },
                            onOpen = { openItem(row.item) },
                        )
                        ListRow.Bottom -> Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        if (selected.value.isNotEmpty()) {
            SendBar(
                count = selected.value.size,
                totalBytes = selectedBytes,
                deviceName = selDevice?.name ?: "…",
                busy = sending,
                onSend = {
                    if (devices.isEmpty()) {
                        onNeedDevice()
                    } else {
                        sending = true
                        val paths = selected.value.toList()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Engine.send(paths) }
                            sending = false
                            if (ok) selected.value = emptySet()
                            else userToast(ctx, "没有可发送的文件，或设备已离线")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DevicePickerRow(devices: List<DeviceInfo>, onSelect: (String) -> Unit) {
    val selId = (Engine.selectedId ?: devices.firstOrNull()?.id)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发送到", color = LdColors.TextFaint, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            if (devices.isEmpty()) {
                Text(
                    "未发现设备，请确认对方已打开内网快传",
                    color = LdColors.Amber,
                    fontSize = 12.sp,
                )
            } else {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    devices.forEach { d ->
                        val sel = d.id == selId
                        Row(
                            Modifier
                                .background(
                                    if (sel) LdColors.AccentDim else LdColors.Surface2,
                                    RoundedCornerShape(20.dp)
                                )
                                .border(
                                    1.dp,
                                    if (sel) LdColors.Accent else LdColors.Border,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { onSelect(d.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(
                                if (d.plat == "android") LdColors.Green else LdColors.Accent,
                                6.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                d.name,
                                fontSize = 12.sp,
                                color = if (sel) Color.White else LdColors.TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(root: File, cur: File, onGo: (File) -> Unit) {
    val rel = cur.absolutePath.removePrefix(root.absolutePath).trim('/')
    val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "内部存储",
            color = if (parts.isEmpty()) LdColors.Text else LdColors.Accent,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable { onGo(root) }
                .padding(vertical = 6.dp),
        )
        var acc = root
        for (p in parts) {
            Text("  ›  ", color = LdColors.TextFaint, fontSize = 12.sp)
            acc = File(acc, p)
            val target = acc
            val last = p == parts.last()
            Text(
                p,
                color = if (last) LdColors.Text else LdColors.Accent,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onGo(target) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** 父目录必须在根目录之内，防止返回/向上时退到 /storage/emulated 这种不可浏览的层级。 */
internal fun isUnderRoot(f: File, root: File): Boolean {
    val fp = f.absolutePath
    val rp = root.absolutePath
    return fp == rp || fp.startsWith(rp.trimEnd('/') + "/")
}
