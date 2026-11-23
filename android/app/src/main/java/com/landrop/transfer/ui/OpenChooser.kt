package com.landrop.transfer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landrop.transfer.storage.FileOpener
import com.landrop.transfer.storage.OpenChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「打开方式」选择框（自绘，不用系统 chooser）。
 *
 * 原因：本应用自己注册了 ACTION_VIEW 的全类型 intent-filter（用于「用内网快传发送」），
 * 系统 chooser 在部分 OEM（实测 MIUI）上会忽略 EXTRA_EXCLUDE_COMPONENTS，把本应用也列进去，
 * 形成「点文件打开 → 又回到本应用的分享弹窗」自环。这里只列其他应用，且只有一个时直接启动。
 */
object OpenChooser {

    data class Pending(val intent: Intent, val choices: List<OpenChoice>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun offer(intent: Intent, choices: List<OpenChoice>) {
        _pending.value = Pending(intent, choices)
    }

    fun clear() {
        _pending.value = null
    }
}

/** 由 App 层挂一次即可。 */
@Composable
fun OpenChoiceDialog() {
    val ctx = LocalContext.current
    val pending by OpenChooser.pending.collectAsState()
    val p = pending ?: return
    AlertDialog(
        onDismissRequest = { OpenChooser.clear() },
        containerColor = LdColors.Surface2,
        titleContentColor = LdColors.Text,
        textContentColor = LdColors.TextDim,
        title = { Text("打开方式", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                p.choices.forEach { c ->
                    Text(
                        c.label,
                        color = LdColors.Text,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val ok = startOpenChoice(ctx, p, c)
                                OpenChooser.clear()
                                if (ok is StartResult.Failed) userToast(ctx, ok.msg)
                            }
                            .padding(vertical = 13.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { OpenChooser.clear() }) {
                Text("取消", color = LdColors.TextDim)
            }
        },
        shape = RoundedCornerShape(16.dp),
    )
}

sealed interface StartResult {
    data object Ok : StartResult
    data class Failed(val msg: String) : StartResult
}

private fun startOpenChoice(ctx: Context, pending: OpenChooser.Pending, choice: OpenChoice): StartResult {
    val i = FileOpener.explicitIntent(pending.intent, choice)
    if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        ctx.startActivity(i)
        StartResult.Ok
    }.getOrElse { StartResult.Failed(it.message ?: "打开失败") }
}
