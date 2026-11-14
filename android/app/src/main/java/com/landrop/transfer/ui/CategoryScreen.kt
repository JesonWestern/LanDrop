package com.landrop.transfer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.landrop.transfer.model.FileKind
import com.landrop.transfer.storage.Catalog
import com.landrop.transfer.storage.CatalogSort
import com.landrop.transfer.storage.FsBrowser

/**
 * 「分类」页：像文件管理器那样按类型聚合，点进分类可排序、多选后发送。
 * 数据来自 [Catalog]（默认媒体库索引，设置里可切全盘扫描）。
 */
@Composable
fun CategoryScreen(devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val ctx = LocalContext.current
    val st by Catalog.state.collectAsState()
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess()) }
    var openKind by remember { mutableStateOf<FileKind?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess()
                if (permitted) Catalog.refresh(ctx)
                tick++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(permitted, tick) {
        if (permitted) Catalog.warm(ctx)
    }

    val selDevice = devices.firstOrNull { it.id == (Engine.selectedId ?: "") }
        ?: devices.firstOrNull()

    if (!permitted) {
        PermissionCard {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:${ctx.packageName}")
            runCatching { ctx.startActivity(i) }
        }
        return
    }

    val kind = openKind
    if (kind == null) {
        CategoryGrid(
            state = st,
            onOpen = {
                openKind = it
                selected = emptySet()
            },
            onToggleScan = { Catalog.setDeepScan(ctx, it) },
        )
    } else {
        val all = remember(st.items, kind) { st.items.filter { it.kind == kind } }
        var sort by remember(kind) { mutableStateOf(CatalogSort()) }
        val sorted = remember(all, sort) { sort.sort(all) }

        BackHandler { openKind = null }

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
                        .clickable { openKind = null }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
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
                            .clickable { sort = sort.toggle(k) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                if (sorted.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("这个分类下还没有文件", color = LdColors.TextFaint, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sorted, key = { it.path }) { f ->
                            FileRow(
                                item = f,
                                checked = selected.contains(f.path),
                                onToggle = { selected = toggle(selected, f.path) },
                                onOpen = { },
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }

            if (selected.isNotEmpty()) {
                val byPath = remember(sorted) { sorted.associateBy { it.path } }
                val totalBytes = selected.mapNotNull { byPath[it] }.sumOf { it.size }
                SendBar(
                    count = selected.size,
                    totalBytes = totalBytes,
                    deviceName = selDevice?.name ?: "…",
                    onSend = {
                        if (devices.isEmpty()) {
                            onNeedDevice()
                        } else {
                            val paths = selected.toList()
                            if (Engine.send(paths)) selected = emptySet()
                        }
                    },
                )
            }
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
                    checked = state.source == Catalog.Source.DEEP,
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
                "文件过多，仅索引了前 20 万个",
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
    return src + at + total
}
