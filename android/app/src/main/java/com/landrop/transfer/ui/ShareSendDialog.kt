package com.landrop.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.share.ShareIntake
import com.landrop.transfer.share.ShareMaterializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从系统「分享 / 打开方式」跳到本应用后的确认弹窗：勾选在线设备（可多选）后发送。
 */
@Composable
fun ShareSendDialog(
    share: ShareIntake.Pending,
    devices: List<DeviceInfo>,
    onDismiss: () -> Unit,
    onRetryProbe: () -> Unit,
    onSent: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = 默认全选（跟随设备列表变化），非 null = 用户手动勾选过
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf("") }

    val chosen: Set<String> = picked ?: devices.map { it.id }.toSet()
    val items = share.items
    val knownSize = items.all { it.size >= 0 }
    val totalBytes = if (knownSize) items.sumOf { it.size } else 0L
    val title = when {
        items.isEmpty() -> "无法发送"
        items.size == 1 -> items[0].name
        else -> "${items.size} 个文件"
    }

    fun toggle(id: String) {
        picked = if (chosen.contains(id)) chosen - id else chosen + id
    }

    fun doSend() {
        val targets = devices.filter { chosen.contains(it.id) }
        if (targets.isEmpty() || items.isEmpty()) return
        busy = true
        err = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ShareMaterializer.materialize(ctx, items) { d, t ->
                    done = d
                    total = t
                }
            }
            busy = false
            if (r.entries.isEmpty()) {
                err = "来源应用没有授予读取权限，请重新分享这" +
                    (if (items.size == 1) "个文件" else "些文件")
                return@launch
            }
            val jobTitle = if (r.entries.size == 1) r.entries[0].rel else "${r.entries.size} 个文件"
            val n = Engine.sendToDevices(targets, r.entries, jobTitle)
            if (n > 0) {
                ShareIntake.clear()
                onSent()
            } else {
                err = "没有可用的接收设备"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = LdColors.Surface2,
        titleContentColor = LdColors.Text,
        textContentColor = LdColors.TextDim,
        title = {
            Text(
                if (items.isEmpty()) "无法发送" else "发送 $title",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                if (items.isNotEmpty()) {
                    Text(
                        if (knownSize) "共 ${fmtBytes(totalBytes)}" else "大小未知",
                        color = LdColors.TextFaint,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (share.note.isNotEmpty()) {
                    Text(share.note, color = LdColors.TextDim, fontSize = 12.5.sp)
                }

                if (items.isNotEmpty()) {
                    if (devices.isEmpty()) {
                        Text(
                            "还没有发现设备。请确认对方已打开内网快传，并连接同一个 WiFi。",
                            color = LdColors.Amber,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                    } else {
                        Text(
                            "选择接收设备（可多选）",
                            color = LdColors.TextFaint,
                            fontSize = 11.5.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            devices.forEach { d ->
                                val on = chosen.contains(d.id)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (on) LdColors.AccentDim.copy(alpha = 0.35f)
                                            else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { toggle(d.id) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = on,
                                        onCheckedChange = { toggle(d.id) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = LdColors.Accent,
                                            uncheckedColor = LdColors.Border2,
                                            checkmarkColor = LdColors.OnAccent,
                                        ),
                                    )
                                    Dot(
                                        if (d.plat == "android") LdColors.Green else LdColors.Accent,
                                        6.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        d.name,
                                        color = LdColors.Text,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (d.plat == "android") "手机" else "电脑",
                                        color = LdColors.TextFaint,
                                        fontSize = 10.5.sp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        }
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = LdColors.Accent,
                        trackColor = LdColors.Surface3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在准备文件 $done/$total",
                        color = LdColors.TextFaint,
                        fontSize = 11.sp,
                    )
                }

                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = LdColors.Red, fontSize = 11.5.sp)
                }

                if (items.size == 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "内网快传不是文件查看器，这里只是把文件发到另一台设备。",
                        color = LdColors.TextFaint,
                        fontSize = 10.5.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (items.isNotEmpty()) {
                TextButton(
                    onClick = { doSend() },
                    enabled = !busy && chosen.isNotEmpty() && devices.isNotEmpty(),
                ) {
                    Text(if (busy) "准备中…" else "发送", color = if (busy) LdColors.TextFaint else LdColors.Accent)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (items.isNotEmpty() && devices.isEmpty()) {
                    TextButton(onClick = onRetryProbe, enabled = !busy) {
                        Text("重新搜索", color = LdColors.Accent)
                    }
                }
                TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                    Text(if (items.isEmpty()) "关闭" else "取消", color = LdColors.TextDim)
                }
            }
        },
    )
}
