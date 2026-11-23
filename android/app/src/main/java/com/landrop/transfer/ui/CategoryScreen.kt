package com.landrop.transfer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.FileKind
import com.landrop.transfer.storage.Catalog
import com.landrop.transfer.storage.CatalogSort
import com.landrop.transfer.storage.FsBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「分类」页：按类型聚合，点进分类可排序、多选发送。
 * 数据来自 [Catalog]（默认媒体库索引，设置里可切全盘扫描）。
 */
@Composable
fun CategoryScreen(devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val ctx = LocalContext.current
    val st by Catalog.state.collectAsState()
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess(ctx)) }
    var openKindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = remember(openKindName) {
        openKindName?.let { n -> FileKind.entries.firstOrNull { it.name == n } }
    }
    val selected = rememberStringSetState()
    var tick by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }

    val grant = rememberGrantStorageAccess { tick++ }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess(ctx)
                if (permitted) Catalog.refresh(ctx)
                tick++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(permitted) {
        if (permitted) Catalog.warm(ctx)
    }

    if (!permitted) {
        Column(Modifier.fillMaxSize()) {
            PermissionCard(legacy = isLegacyStorage(), onGrant = grant)
        }
        return
    }

    val selDevice = devices.firstOrNull { it.id == (Engine.selectedId ?: "") }
        ?: devices.firstOrNull()

    if (kind == null) {
        CategoryGrid(
            state = st,
            onOpen = {
                openKindName = it.name
                selected.value = emptySet()
            },
            onToggleScan = { Catalog.setDeepScan(ctx, it) },
        )
        return
    }

    // ---- 分类详情 ----
    var sortKeyName by rememberSaveable { mutableStateOf(CatalogSort.Key.TIME.name) }
    var sortAsc by rememberSaveable { mutableStateOf(false) }
    val sort = CatalogSort(CatalogSort.Key.valueOf(sortKeyName), sortAsc)

    val all = remember(st.items, kind) { st.items.filter { it.kind == kind } }
    var sorted by remember(kind) { mutableStateOf<List<FileItem>>(emptyList()) }
    var sorting by remember(kind) { mutableStateOf(false) }
    LaunchedEffect(all, sort) {
        sorting = true
        sorted = withContext(Dispatchers.Default) { sort.sort(all) }
        sorting = false
    }

    BackHandler {
        if (selected.value.isNotEmpty()) selected.value = emptySet() else openKindName = null
    }

    val rows = remember(sorted) {
        buildList {
            if (sorted.isEmpty()) add(ListRow.Empty("这个分类下还没有文件"))
            sorted.forEach { add(ListRow.Item(it)) }
            add(ListRow.Bottom)
        }
    }
    val byPath = remember(sorted) { sorted.associateBy { it.path } }
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ 返回",
                color = LdColors.Accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { openKindName = null; selected.value = emptySet() }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
            FileGlyph(kind = kind, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                kind.label,
                color = LdColors.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (sorting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = LdColors.TextFaint,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text("${all.size} 个", color = LdColors.TextFaint, fontSize = 12.sp)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CatalogSort.Key.entries.forEach { k ->
                val on = sort.key == k
                val arrow = when {
                    !on -> ""
                    sort.asc -> " ↑"
                    else -> " ↓"
                }
                Text(
                    k.label + arrow,
                    color = if (on) Color.White else LdColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            if (on) LdColors.AccentDim else LdColors.Surface2,
                            RoundedCornerShape(16.dp)
                        )
                        .border(
                            1.dp,
                            if (on) LdColors.Accent else LdColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            if (sort.key == k) sortAsc = !sortAsc else {
                                sortKeyName = k.name
                                sortAsc = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
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
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    when (row) {
                        is ListRow.Header -> SectionHeader(row.text)
                        is ListRow.Empty -> EmptyHint(row.text)
                        is ListRow.Item -> FileRow(
                            item = row.item,
                            checked = selected.value.contains(row.item.path),
                            onToggle = { selected.value = toggle(selected.value, row.item.path) },
                            onOpen = {
                                openFile(ctx, row.item.path, row.item.mime, row.item.contentUri)
                            },
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
private fun CategoryGrid(
    state: Catalog.State,
    onOpen: (FileKind) -> Unit,
    onToggleScan: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("分类", color = LdColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    sourceLine(state),
                    color = LdColors.TextFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("全盘扫描", color = LdColors.TextDim, fontSize = 11.sp)
                Switch(
                    // 扫描期间保持 ON：旧实现只等 emit 才改 source，开关会立刻弹回 OFF
                    checked = state.scanning || state.source == Catalog.Source.DEEP,
                    onCheckedChange = onToggleScan,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LdColors.Accent,
                        uncheckedThumbColor = LdColors.TextFaint,
                        uncheckedTrackColor = LdColors.Surface3,
                    ),
                )
            }
        }

        if (state.scanning) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(LdColors.Surface2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在扫描：已索引 ${state.scanFound} 个文件",
                    color = LdColors.TextDim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (state.error.isNotBlank()) {
            Text(
                state.error,
                color = LdColors.Red,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.truncated) {
            Text(
                "文件过多：仅统计最近 ${com.landrop.transfer.storage.CatalogPolicy.LIMIT} 条索引",
                color = LdColors.Amber,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(FileKind.entries.toList()) { k ->
                val b = state.buckets.firstOrNull { it.kind == k }
                CategoryCard(
                    kind = k,
                    count = b?.count ?: 0,
                    bytes = b?.bytes ?: 0L,
                    loading = state.loading && state.buckets.isEmpty(),
                    scanning = state.scanning && state.source == Catalog.Source.MEDIA && state.buckets.isNotEmpty(),
                    onClick = { if ((b?.count ?: 0) > 0) onOpen(k) },
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    kind: FileKind,
    count: Int,
    bytes: Long,
    loading: Boolean,
    scanning: Boolean,
    onClick: () -> Unit,
) {
    val enabled = count > 0 && !loading
    Column(
        Modifier
            .fillMaxWidth()
            .background(LdColors.Surface, CardShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
    ) {
        FileGlyph(kind = kind, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(kind.label, color = LdColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(
            when {
                loading -> "统计中…"
                count <= 0 -> "暂无文件"
                scanning -> "$count 个 · ${fmtBytes(bytes)} · 扫描中"
                else -> "$count 个 · ${fmtBytes(bytes)}"
            },
            color = if (enabled) LdColors.TextFaint else LdColors.TextFaint.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

private fun sourceLine(s: Catalog.State): String {
    val src = if (s.source == Catalog.Source.DEEP) "全盘扫描" else "媒体库索引"
    val at = if (s.generatedAt > 0) " · " + fmtTime(s.generatedAt) else ""
    val total = if (s.items.isEmpty()) "" else " · 共 ${s.items.size} 个文件"
    val trunc = if (s.truncated) " · 已截断" else ""
    return src + at + total + trunc
}
