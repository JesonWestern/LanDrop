package com.landrop.transfer.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.JobState
import com.landrop.transfer.service.TransferService
import com.landrop.transfer.share.ShareIntake

object Tab {
    const val FILES = 0
    const val CATEGORY = 1
    const val TRANSFER = 2
    const val SPEED = 3
    const val SETTINGS = 4
}

/** tab 切换动画时长（毫秒）。关掉动画时归零，视觉上等于瞬时切换。 */
private const val TAB_ANIM_MS = 180

private data class TabItem(val label: String, val hint: String)

@Composable
fun LanDropApp() {
    val devices by Engine.devices.collectAsState()
    val jobs by Engine.jobList.collectAsState()
    val speed by Engine.speed.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(Tab.FILES) }
    // 切换 tab 时保留各页的 rememberSaveable 状态（目录位置、选中、展开、排序）
    val tabStates = rememberSaveableStateHolder()
    val ctx = LocalContext.current
    // 「用户设置 + 系统动画开关」已在外壳统一合并，这里只读结果
    val animations = LocalLdAnimations.current
    val tabAnimMs = if (animations) TAB_ANIM_MS else 0

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

    // 系统「分享 / 打开方式」进来时：勾选在线设备后发送
    val share by ShareIntake.pending.collectAsState()
    share?.let { s ->
        ShareSendDialog(
            share = s,
            devices = devices,
            onDismiss = { ShareIntake.clear() },
            onRetryProbe = { Engine.refreshProbe() },
            onSent = { tab = Tab.TRANSFER },
        )
    }

    // 「打开方式」选择框（只列其他应用，避免本应用自环）
    OpenChoiceDialog()

    // 其他 tab 按返回先回「文件」页，再按一次才退出。
    // 注意：必须注册在 Column 之前，Compose 里后注册的 BackHandler 优先级更高，
    // 这样分类详情/子目录等内层页面的返回才会先于 tab 级返回生效。
    BackHandler(enabled = tab != Tab.FILES) { tab = Tab.FILES }

    // 根节点铺主题壁纸 + scrim（替代原来的纯色 .background(LdColors.Bg)）
    ThemedBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // 新 tab 序号更大 → 从右滑入；否则从左滑入
                        val dir = if (targetState > initialState) {
                            AnimatedContentTransitionScope.SlideDirection.Left
                        } else {
                            AnimatedContentTransitionScope.SlideDirection.Right
                        }
                        val spec = tween<IntOffset>(
                            tabAnimMs, easing = FastOutSlowInEasing
                        )
                        val fadeMs = tween<Float>(tabAnimMs, easing = FastOutSlowInEasing)
                        (slideIntoContainer(dir, animationSpec = spec) + fadeIn(fadeMs))
                            .togetherWith(fadeOut(fadeMs))
                            .using(SizeTransform(clip = false))
                    },
                    label = "tabContent",
                ) { t ->
                    // rememberSaveableStateHolder 用 key=t，切回旧页面时状态仍在
                    tabStates.SaveableStateProvider(t) {
                        when (t) {
                            Tab.FILES -> FileScreen(devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                            Tab.CATEGORY -> CategoryScreen(devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                            Tab.TRANSFER -> TransferScreen(jobs = jobs, onOpenFiles = { tab = Tab.FILES })
                            Tab.SPEED -> SpeedScreen(speed = speed, devices = devices, onNeedDevice = { tab = Tab.SETTINGS })
                            else -> SettingsScreen(devices = devices)
                        }
                    }
                }
            }
            BottomBar(
                tab = tab,
                activeJobs = activeJobs,
                onSelect = { tab = it },
            )
        }
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
    val dur = if (LocalLdAnimations.current) TAB_ANIM_MS else 0
    val widthSpec = remember(dur) { tween<Dp>(dur, easing = FastOutSlowInEasing) }
    val alphaSpec = remember(dur) { tween<Float>(dur, easing = FastOutSlowInEasing) }
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
            val indicatorWidth by animateDpAsState(
                targetValue = if (sel) 18.dp else 0.dp,
                animationSpec = widthSpec,
                label = "tabIndicatorWidth",
            )
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (sel) 1f else 0f,
                animationSpec = alphaSpec,
                label = "tabIndicatorAlpha",
            )
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
                            Text(item.hint, color = LdColors.OnAccent, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = indicatorWidth, height = 2.dp)
                        .background(
                            LdColors.Accent.copy(alpha = indicatorAlpha),
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}
