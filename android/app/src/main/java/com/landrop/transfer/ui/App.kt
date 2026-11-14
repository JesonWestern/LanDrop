package com.landrop.transfer.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.JobState
import com.landrop.transfer.service.TransferService

object Tab {
    const val FILES = 0
    const val CATEGORY = 1
    const val TRANSFER = 2
    const val SPEED = 3
    const val SETTINGS = 4
}

private data class TabItem(val label: String, val hint: String)

@Composable
fun LanDropApp() {
    val devices by Engine.devices.collectAsState()
    val jobs by Engine.jobList.collectAsState()
    val speed by Engine.speed.collectAsState()
    var tab by remember { mutableIntStateOf(Tab.FILES) }
    val ctx = LocalContext.current

    val activeJobs = jobs.count { it.state.active }

    // 传输期间保持屏幕常亮（熄屏会导致 WiFi 降速/断流）
    val view = LocalView.current
    DisposableEffect(activeJobs > 0) {
        val w = (view.context as? Activity)?.window
        if (activeJobs > 0) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            (view.context as? Activity)?.window
                ?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 回到前台立即探测一次，避免最多干等 3 秒才出现设备
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) Engine.refreshProbe()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // 接收确认弹窗
    val pending = jobs.firstOrNull { it.state == JobState.WAITING_ACCEPT }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("接收文件？") },
            text = {
                Text(
                    "「${pending.device}」想发送 ${pending.totalFiles} 个项目" +
                        "（${fmtBytes(pending.totalBytes)}）。\n\n接收后会保存到 下载/LanDrop。"
                )
            },
            confirmButton = {
                TextButton(onClick = { Engine.accept(pending.id, true) }) { Text("接收") }
            },
            dismissButton = {
                TextButton(onClick = { Engine.accept(pending.id, false) }) { Text("拒绝") }
            },
            containerColor = LdColors.Surface2,
            titleContentColor = LdColors.Text,
            textContentColor = LdColors.TextDim,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LdColors.Bg)
            .statusBarsPadding()
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.FILES -> FileScreen(devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                Tab.CATEGORY -> CategoryScreen(devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                Tab.TRANSFER -> TransferScreen(jobs = jobs, onOpenFiles = { tab = Tab.FILES })
                Tab.SPEED -> SpeedScreen(speed = speed, devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                else -> SettingsScreen(devices = devices)
            }
        }
        BottomBar(
            tab = tab,
            activeJobs = activeJobs,
            onSelect = { tab = it },
        )
    }

    // 首次进入启动前台服务
    DisposableEffect(Unit) {
        runCatching { TransferService.start(ctx) }
        onDispose { }
    }
}

@Composable
private fun BottomBar(tab: Int, activeJobs: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        TabItem("文件", ""),
        TabItem("分类", ""),
        TabItem("传输", if (activeJobs > 0) "$activeJobs" else ""),
        TabItem("测速", ""),
        TabItem("设置", ""),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(LdColors.Surface)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val sel = tab == i
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        item.label,
                        color = if (sel) LdColors.Text else LdColors.TextFaint,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (item.hint.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Box(
                            Modifier
                                .size(15.dp)
                                .background(LdColors.Accent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.hint, color = Color.White, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = if (sel) 18.dp else 0.dp, height = 2.dp)
                        .background(
                            if (sel) LdColors.Accent else Color.Transparent,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}
