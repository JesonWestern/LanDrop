package com.landrop.transfer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FileScreen(devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val ctx = LocalContext.current
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess()) }
    var root by remember { mutableStateOf(FsBrowser.root()) }
    var curDir by remember { mutableStateOf(FsBrowser.root()) }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess()
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
            val rec = if (curDir.absolutePath == root.absolutePath) FsBrowser.recentFiles(ctx, 12)
            else emptyList()
            withContext(Dispatchers.Main) {
                items = list
                recent = rec
            }
        }
    }

    val selDevice = devices.firstOrNull { it.id == (Engine.selectedId ?: "") }
        ?: devices.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：目标设备
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
                            val sel = d.id == selDevice?.id
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
                                    .clickable { Engine.select(d.id) }
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

        if (!permitted) {
            PermissionCard {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:${ctx.packageName}")
                runCatching { ctx.startActivity(i) }
            }
            return@Column
        }

        // 面包屑
        Breadcrumb(root = root, cur = curDir, onGo = { curDir = it })

        Box(Modifier.weight(1f)) {
            val dirs = items.filter { it.isDir }
            val files = items.filter { !it.isDir }
            LazyColumn(Modifier.fillMaxSize()) {
                if (curDir.absolutePath == root.absolutePath && recent.isNotEmpty()) {
                    item { SectionHeader("最近文件") }
                    items(recent, key = { "r" + it.path }) { f ->
                        FileRow(
                            item = f,
                            checked = selected.contains(f.path),
                            onToggle = { selected = toggle(selected, f.path) },
                            onOpen = { },
                        )
                    }
                }
                if (items.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("这个文件夹是空的", color = LdColors.TextFaint, fontSize = 13.sp)
                        }
                    }
                }
                if (dirs.isNotEmpty()) {
                    item { SectionHeader("文件夹") }
                    items(dirs, key = { it.path }) { f ->
                        FileRow(
                            item = f,
                            checked = selected.contains(f.path),
                            onToggle = { selected = toggle(selected, f.path) },
                            onOpen = { curDir = File(f.path) },
                        )
                    }
                }
                if (files.isNotEmpty()) {
                    item { SectionHeader("文件") }
                    items(files, key = { it.path }) { f ->
                        FileRow(
                            item = f,
                            checked = selected.contains(f.path),
                            onToggle = { selected = toggle(selected, f.path) },
                            onOpen = { },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        // 底部发送条
        if (selected.isNotEmpty()) {
            val totalBytes = (items + recent)
                .associateBy { it.path }
                .let { byPath -> selected.mapNotNull { byPath[it] } }
                .filter { !it.isDir }
                .sumOf { it.size }
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
            modifier = Modifier.clickable { onGo(root) },
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
                modifier = Modifier.clickable { onGo(target) },
            )
        }
    }
}
