package com.landrop.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.landrop.transfer.core.Engine
import com.landrop.transfer.data.HistoryRec
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi

@Composable
fun TransferScreen(jobs: List<JobUi>, onOpenFiles: () -> Unit) {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf<List<HistoryRec>>(emptyList()) }
    LaunchedEffect(jobs.size, jobs.count { !it.state.active }) {
        history = Engine.history.load().take(30)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("传输", color = LdColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (jobs.any { !it.state.active }) {
                TextButton(onClick = {
                    Engine.clearAllFinished()
                    history = Engine.history.load().take(30)
                }) { Text("清除已结束", fontSize = 12.sp, color = LdColors.TextFaint) }
            }
        }

        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有传输任务", color = LdColors.TextDim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在「文件」页选中项目后即可发送",
                        color = LdColors.TextFaint,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenFiles,
                        colors = ButtonDefaults.buttonColors(containerColor = LdColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("去选文件") }
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(jobs, key = { "j${it.id}" }) { j ->
                JobRow(j)
            }
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "历史记录",
                        color = LdColors.TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(history, key = { "h${it.time}${it.title}" }) { h ->
                    HistoryRow(h)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun JobRow(j: JobUi) {
    val active = j.state.active
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(LdColors.Surface, CardShape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileGlyph(
                isDir = false,
                tint = if (j.state == JobState.FAILED) LdColors.Red
                else if (j.dir == JobDir.SEND) LdColors.Green else LdColors.Accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    j.title.ifEmpty { "文件" },
                    color = LdColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(if (j.dir == JobDir.SEND) "发送到 " else "来自 ")
                        append(j.device)
                        append(" · ")
                        append(j.state.label)
                        if (j.state == JobState.TRANSFERRING) {
                            append(" · ")
                            append(fmtSpeed(j.bps))
                            val eta = fmtEta(j.totalBytes - j.doneBytes, j.bps)
                            if (eta.isNotEmpty()) {
                                append(" · ")
                                append(eta)
                            }
                        }
                    },
                    color = if (j.state == JobState.FAILED) LdColors.Red else LdColors.TextFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                TextButton(onClick = { Engine.cancel(j.id) }) {
                    Text("取消", fontSize = 12.sp, color = LdColors.Red)
                }
            } else if (j.canRetry) {
                TextButton(onClick = { Engine.retry(j.id) }) {
                    Text("重试", fontSize = 12.sp, color = LdColors.Accent)
                }
            }
        }
        if (active) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { j.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (j.dir == JobDir.SEND) LdColors.Green else LdColors.Accent,
                trackColor = LdColors.Surface3,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${fmtBytes(j.doneBytes)} / ${fmtBytes(j.totalBytes)}",
                    color = LdColors.TextFaint,
                    fontSize = 10.5.sp,
                )
                Text(
                    "${(j.progress * 100).toInt()}%",
                    color = LdColors.TextDim,
                    fontSize = 10.5.sp,
                )
            }
        } else if (j.state == JobState.DONE && j.savePath.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                j.savePath,
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (j.errMsg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(j.errMsg, color = LdColors.Red, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryRec) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (h.ok) LdColors.Green else LdColors.Red,
                    RoundedCornerShape(3.dp)
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            h.title,
            color = LdColors.TextDim,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (h.dir == 0) "接收 " else "发送 ") + fmtBytes(h.bytes) + " · " + fmtTime(h.time),
            color = LdColors.TextFaint,
            fontSize = 10.5.sp,
        )
    }
}
