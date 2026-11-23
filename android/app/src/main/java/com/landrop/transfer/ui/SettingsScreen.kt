package com.landrop.transfer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.storage.Catalog
import com.landrop.transfer.storage.FsBrowser

@Composable
fun SettingsScreen(devices: List<DeviceInfo>) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Engine.settings.deviceName) }
    var conns by remember { mutableStateOf(Engine.settings.dataConns) }
    var speedConns by remember { mutableStateOf(Engine.settings.speedConns) }
    var speedSecs by remember { mutableStateOf(Engine.settings.speedSecs) }
    var autoTrust by remember { mutableStateOf(Engine.settings.autoAcceptTrusted) }
    var deepScan by remember { mutableStateOf(Engine.settings.deepScan) }
    val cat by Catalog.state.collectAsState()
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess(ctx)) }
    var uiRev by remember { mutableIntStateOf(0) }

    val grant = rememberGrantStorageAccess {
        permitted = FsBrowser.hasAllFilesAccess(ctx)
        uiRev++
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess(ctx)
                deepScan = Engine.settings.deepScan
                uiRev++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(permitted) {
        if (permitted) Catalog.warm(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "设置",
            color = LdColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )

        Section("本机") {
            Text("设备名称（对方看到的名字）", color = LdColors.TextFaint, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LdColors.Accent,
                    unfocusedBorderColor = LdColors.Border,
                    focusedTextColor = LdColors.Text,
                    unfocusedTextColor = LdColors.Text,
                    cursorColor = LdColors.Accent,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("保存名称", primary = true) {
                    if (name.isNotBlank()) {
                        Engine.setDeviceName(name.trim())
                        uiRev++
                    }
                }
                Pill("恢复默认") {
                    name = Build.MODEL
                    Engine.setDeviceName(name)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "设备 ID：${Engine.settings.deviceId.take(13)}…  ·  当前发现 ${devices.size} 台设备",
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
            )
        }

        Section("传输") {
            StepperRow("并发连接数", conns, 1, 6) {
                conns = it
                Engine.settings.dataConns = it
            }
            Text(
                "并发越高大文件吞吐越好，但低端机或慢速 WiFi 上建议 1–2；手机之间互传建议 3。",
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow("信任的设备自动接收", autoTrust) {
                autoTrust = it
                Engine.settings.autoAcceptTrusted = it
            }
            Text(
                "关闭后，每次接收都需要你在手机上确认。首次接收会自动把对方加入信任列表。",
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val trustedCount = remember(uiRev) { Engine.settings.trustedCount() }
                Text("已信任设备 $trustedCount 台",
                    color = LdColors.TextDim, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Pill("清空") {
                    Engine.settings.clearTrusted()
                    Engine.restart()
                    uiRev++
                }
            }
        }

        Section("文件索引") {
            SwitchRow("全盘深度扫描", deepScan) {
                deepScan = it
                Catalog.setDeepScan(ctx, it)
                uiRev++
            }
            Text(
                "默认使用系统媒体库索引，打开分类页秒开。开启后会遍历整块存储，能发现媒体库没有收录的" +
                    "文件（手动拷进来的文档、压缩包等），结果缓存 24 小时。",
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    cat.scanning -> "正在扫描：已索引 ${cat.scanFound} 个文件"
                    cat.loading || (cat.items.isEmpty() && cat.generatedAt == 0L) -> "正在读取索引…"
                    cat.source == Catalog.Source.DEEP ->
                        "全盘扫描 · ${cat.items.size} 个文件 · " + fmtTime(cat.generatedAt)
                    else -> "媒体库索引 · ${cat.items.size} 个文件 · " + fmtTime(cat.generatedAt)
                },
                color = LdColors.TextDim,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("重新扫描", primary = true) { Catalog.rescan(ctx); uiRev++ }
            }
        }

        Section("存储") {
            Text("接收目录", color = LdColors.TextDim, fontSize = 12.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "下载/LanDrop/（系统「下载」目录下的 LanDrop 文件夹）\n" +
                    "接收过程中先写入应用私有目录，完成后才导入这里，因此中途断网可续传。",
                color = LdColors.TextFaint,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }

        Section("测速") {
            StepperRow("并发连接数", speedConns, 1, 8) {
                speedConns = it
                Engine.settings.speedConns = it
            }
            StepperRow("测试时长（秒）", speedSecs, 3, 30) {
                speedSecs = it
                Engine.settings.speedSecs = it
            }
        }

        Section("权限") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (permitted) LdColors.Green else LdColors.Red, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (permitted) "全部文件访问：已授权" else "全部文件访问：未授权",
                    color = LdColors.TextDim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                Pill(if (permitted) "已授权" else "去授权", primary = !permitted) {
                    if (!permitted) grant()
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "建议把本应用加入电池优化白名单，并允许后台运行，避免长传输被系统打断。",
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Pill("打开电池优化设置") {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        Section("关于") {
            Text("内网快传 LanDrop 1.2.0", color = LdColors.Text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Windows ⇄ Android / Android ⇄ Android 局域网文件互传与网速测试。\n" +
                    "零配置发现 · 文件夹传输 · 断点续传 · 不经过任何服务器。\n\n" +
                    "协议 v1 · 发现 UDP 47820 · 传输 TCP 47821",
                color = LdColors.TextFaint,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(LdColors.Surface, CardShape)
            .padding(14.dp)
    ) {
        Text(title, color = LdColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) Color.White else LdColors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier
            .background(
                if (primary) LdColors.Accent else LdColors.Surface3,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LdColors.TextDim, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Pill("−") { if (value > min) onChange(value - 1) }
        Text(
            "$value",
            color = LdColors.Text,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Pill("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LdColors.TextDim, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LdColors.Accent,
                uncheckedThumbColor = LdColors.TextFaint,
                uncheckedTrackColor = LdColors.Surface3,
            ),
        )
    }
}
