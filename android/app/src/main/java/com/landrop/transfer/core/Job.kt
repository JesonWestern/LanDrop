package com.landrop.transfer.core

import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobFileUi
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi
import com.landrop.transfer.storage.DownloadSink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Job(
    val id: Int,
    val dir: JobDir,
    var device: DeviceInfo,
    var title: String,
    val files: List<Entry>,
) {
    val totalBytes: Long = files.sumOf { if (it.isDir) 0L else it.size }
    val totalChunks: Int = files.sumOf { it.chunkCount }
    val totalFiles: Int = files.size

    /** 逐文件快照上限：上万条任务不把 UI 拖死。 */
    val filesTruncated: Boolean = files.size > SNAPSHOT_LIMIT

    val doneBytes = AtomicLong(0)
    val doneChunkCount = AtomicInteger(0)

    /** 接收文件落盘结果（rel → Published），由 ReceiverServer 提交阶段写入。 */
    val published = ConcurrentHashMap<String, DownloadSink.Published>()

    @Volatile var state: JobState = JobState.QUEUED
    @Volatile var bps: Double = 0.0
    @Volatile var errMsg: String = ""
    @Volatile var savePath: String = ""
    @Volatile var cancel: Boolean = false
    @Volatile var acceptFlag: Boolean = false
    @Volatile var promptShown: Boolean = false
    @Volatile var jobKey: String = ""
    @Volatile var trustedPeer: Boolean = false
    @Volatile var historyLoggedDone: Boolean = false

    val startedAt: Long = System.currentTimeMillis()
    @Volatile var endedAt: Long = 0

    var tempRoot: File? = null
    var prevBytes: Long = 0
    var prevTick: Long = 0

    fun progress(): Float = when {
        totalBytes <= 0L -> if (state == JobState.DONE) 1f else 0f
        else -> (doneBytes.get().toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    }

    /** UI 展开任务时按需生成（不要在 400ms tick 里调用）。 */
    fun fileSnapshot(limit: Int = SNAPSHOT_LIMIT): List<JobFileUi> {
        val list = if (files.size > limit) files.subList(0, limit) else files
        return list.map { e ->
            val p = published[e.rel]
            val openPath = when {
                dir == JobDir.RECV -> p?.absPath.orEmpty()
                else -> e.absPath
            }
            JobFileUi(
                rel = e.rel,
                name = e.rel.substringAfterLast('/'),
                size = e.size,
                doneBytes = e.doneBytes(),
                isDir = e.isDir,
                openPath = openPath,
                openUri = p?.contentUri.orEmpty(),
            )
        }
    }

    /** 单文件任务的打开目标（path, uri）；不可打开时返回 null。 */
    private fun singleOpenTarget(): Pair<String, String>? {
        if (totalFiles != 1) return null
        val e = files[0]
        if (e.isDir) return null
        if (dir == JobDir.RECV) {
            val p = published[e.rel] ?: return null
            if (p.absPath.isEmpty() && p.contentUri.isEmpty()) return null
            return p.absPath to p.contentUri
        }
        if (e.absPath.isEmpty()) return null
        return e.absPath to ""
    }

    fun toUi(): JobUi {
        val open = singleOpenTarget()
        return JobUi(
            id = id,
            dir = dir,
            state = state,
            title = title,
            device = device.name,
            totalBytes = totalBytes,
            doneBytes = doneBytes.get(),
            totalFiles = totalFiles,
            bps = bps,
            errMsg = errMsg,
            savePath = savePath,
            startAt = startedAt,
            openPath = open?.first.orEmpty(),
            openUri = open?.second.orEmpty(),
            filesTruncated = filesTruncated,
        )
    }

    fun noteTick() {
        val now = System.currentTimeMillis()
        if (prevTick == 0L) {
            prevTick = now
            prevBytes = doneBytes.get()
            return
        }
        val dt = now - prevTick
        if (dt < 400) return
        val cur = doneBytes.get()
        val inst = (cur - prevBytes).toDouble() * 1000.0 / dt
        prevBytes = cur
        prevTick = now
        bps = if (bps <= 0) inst else bps * 0.7 + inst * 0.3
    }

    companion object {
        const val SNAPSHOT_LIMIT = 500
    }
}
