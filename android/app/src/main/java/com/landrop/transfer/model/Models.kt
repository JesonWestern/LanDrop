package com.landrop.transfer.model

enum class JobDir { SEND, RECV }

enum class JobState {
    QUEUED, WAITING_ACCEPT, HANDSHAKE, TRANSFERRING, VERIFYING, DONE, FAILED, CANCELED, PAUSED;

    val active: Boolean
        get() = this == QUEUED || this == WAITING_ACCEPT || this == HANDSHAKE ||
            this == TRANSFERRING || this == VERIFYING

    val label: String
        get() = when (this) {
            QUEUED -> "排队中"
            WAITING_ACCEPT -> "等待确认"
            HANDSHAKE -> "连接中"
            TRANSFERRING -> "传输中"
            VERIFYING -> "校验中"
            DONE -> "已完成"
            FAILED -> "失败"
            CANCELED -> "已取消"
            PAUSED -> "已暂停"
        }
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val plat: String,
    val ver: String,
    val ip: String,
    val port: Int,
    val lastSeen: Long,
    val trusted: Boolean = false,
)

data class JobUi(
    val id: Int,
    val dir: JobDir,
    val state: JobState,
    val title: String,
    val device: String,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalFiles: Int = 0,
    val bps: Double = 0.0,
    val errMsg: String = "",
    val savePath: String = "",
    val startAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = when {
            totalBytes <= 0L -> if (state == JobState.DONE) 1f else 0f
            else -> (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        }

    /** 发送任务失败/暂停时可以重试；接收任务只能等对方重试。 */
    val canRetry: Boolean
        get() = dir == JobDir.SEND && (state == JobState.FAILED || state == JobState.PAUSED)
}

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val childInfo: String = "",
    val mime: String = "",
) {
    /** 目录没有文件类型；文件按扩展名（mime 兜底）判定。 */
    val kind: FileKind
        get() = if (isDir) FileKind.OTHER else FileKind.of(name, mime.ifEmpty { null })
}

data class SpeedUi(
    val running: Boolean = false,
    val dir: Int = 2, // 0 up 1 down 2 both
    val currentMbps: Double = 0.0,
    val avgMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val p50Mbps: Double = 0.0,
    val samples: List<Double> = emptyList(),
    val err: String = "",
    val elapsedMs: Long = 0,
)
