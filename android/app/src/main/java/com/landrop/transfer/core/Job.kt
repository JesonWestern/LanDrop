package com.landrop.transfer.core

import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi
import java.io.File
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

    val doneBytes = AtomicLong(0)
    val doneChunkCount = AtomicInteger(0)

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

    fun toUi(): JobUi = JobUi(
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
    )

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
}
