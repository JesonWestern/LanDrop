package com.landrop.transfer.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobFileUi
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 传输页的扁平行：任务头 / 任务内文件 / 历史分隔 / 历史条目。 */
private sealed interface TRow {
    val key: String

    data class Job(val job: JobUi) : TRow {
        override val key: String get() = "j${job.id}"
    }

    data class File(val jobId: Int, val index: Int, val file: JobFileUi) : TRow {
        override val key: String get() = "f$jobId:$index"
    }

    data object HistoryHeader : TRow {
        override val key: String get() = "h:header"
    }

    data class History(val index: Int, val rec: HistoryRec) : TRow {
        override val key: String get() = "h$index:${rec.time}"
    }
}

@Composable
fun TransferScreen(jobs: List<JobUi>, onOpenFiles: () -> Unit) {
    val ctx = LocalContext.current
    val expanded = rememberIntSetState()
    var history by remember { mutableStateOf<List<HistoryRec>>(emptyList()) }
    val finished = jobs.count { !it.state.active }
    LaunchedEffect(finished) {
        history = withContext(Dispatchers.IO) { Engine.history.load().take(30) }
    }

    val jobFiles = remember(jobs, expanded.value) {
        expanded.value.associateWith { id -> Engine.jobFiles(id) }
    }

    val rows = remember(jobs, expanded.value, jobFiles, history) {
        buildList {
            jobs.forEach { j ->
                add(TRow.Job(j))
                if (expanded.value.contains(j.id)) {
                    jobFiles[j.id]?.forEachIndexed { i, f -> add(TRow.File(j.id, i, f)) }
                }
            }
            if (history.isNotEmpty()) {
                add(TRow.HistoryHeader)
                history.forEachIndexed { i, h -> add(TRow.History(i, h)) }
            }
        }
    }

    fun toggleExpanded(id: Int) {
        expanded.value = if (expanded.value.contains(id)) expanded.value - id else expanded.value + id
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
                    history = emptyList()
                }) { Text("清除已结束", fontSize = 12.sp, color = LdColors.TextFaint) }
            }
        }

        if (jobs.isEmpty() && history.isEmpty()) {
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
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                when (row) {
                    is TRow.Job -> JobCard(
                        j = row.job,
                        expanded = expanded.value.contains(row.job.id),
                        onToggleExpand = { toggleExpanded(row.job.id) },
                        onOpen = {
                            openFile(ctx, row.job.openPath, null, row.job.openUri)
                        },
                    )
                    is TRow.File -> JobFileRow(row.jobId, row.file) {
                        openFile(
                            ctx,
                            row.file.openPath,
                            null,
                            row.file.openUri,
                        )
                    }
                    TRow.HistoryHeader -> Text(
                        "历史记录",
                        color = LdColors.TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                    )
                    is TRow.History -> HistoryRow(row.rec)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun JobCard(
    j: JobUi,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpen: () -> Unit,
) {
    val active = j.state.active
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(LdColors.Surface, CardShape)
            .clickable(enabled = j.totalFiles > 0) { onToggleExpand() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindThumb(
                kind = com.landrop.transfer.model.FileKind.of(j.title),
                isDir = j.totalFiles > 1,
                size = 34.dp,
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
                        if (j.totalFiles > 0) append(" · ${j.totalFiles} 个文件")
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
            when {
                active -> TextButton(onClick = { Engine.cancel(j.id) }) {
                    Text("取消", fontSize = 12.sp, color = LdColors.Red)
                }
                j.canRetry -> TextButton(onClick = { Engine.retry(j.id) }) {
                    Text("重试", fontSize = 12.sp, color = LdColors.Accent)
                }
                j.canOpenSingle -> TextButton(onClick = onOpen) {
                    Text("打开", fontSize = 12.sp, color = LdColors.Accent)
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
        if (j.totalFiles > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    expanded && j.filesTruncated -> "▴ 收起（仅显示前 ${com.landrop.transfer.core.Job.SNAPSHOT_LIMIT} 个）"
                    expanded -> "▴ 收起文件列表"
                    j.filesTruncated -> "▾ 展开文件列表（共 ${j.totalFiles} 个，仅显示前 ${com.landrop.transfer.core.Job.SNAPSHOT_LIMIT} 个）"
                    else -> "▾ 展开文件列表（${j.totalFiles} 个）"
                },
                color = LdColors.TextFaint,
                fontSize = 10.5.sp,
            )
        }
    }
}

@Composable
private fun JobFileRow(jobId: Int, f: JobFileUi, onOpen: () -> Unit) {
    val item = remember(jobId, f.rel, f.doneBytes, f.openPath, f.openUri) {
        FileItem(
            name = f.name,
            path = f.openPath,
            isDir = f.isDir,
            size = f.size,
            mtime = 0L,
            contentUri = f.openUri,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = f.openable, onClick = onOpen)
            .padding(start = 28.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(item, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.name,
                color = LdColors.Text,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                f.isDir -> "文件夹"
                f.progress >= 1f -> fmtBytes(f.size) + " · 已完成"
                else -> fmtBytes(f.doneBytes) + " / " + fmtBytes(f.size)
            }
            Text(sub, color = LdColors.TextFaint, fontSize = 10.5.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (f.openable && f.progress >= 1f) {
            Text("打开", color = LdColors.Accent, fontSize = 11.5.sp)
        } else if (!f.isDir) {
            Text(
                "${(f.progress * 100).toInt()}%",
                color = LdColors.TextDim,
                fontSize = 10.5.sp,
            )
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
