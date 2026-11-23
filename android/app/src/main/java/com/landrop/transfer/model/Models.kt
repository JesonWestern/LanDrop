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

/** 传输任务里的单个文件（按需快照，不参与每 400ms 的 JobUi 推送）。 */
data class JobFileUi(
    val rel: String,
    val name: String,
    val size: Long,
    val doneBytes: Long,
    val isDir: Boolean,
    /** 可打开的本地绝对路径（收到的文件为落盘后的真实路径）。 */
    val openPath: String = "",
    /** MediaStore content uri（仅接收文件可能有）。 */
    val openUri: String = "",
) {
    val progress: Float
        get() = when {
            isDir -> 1f
            size <= 0L -> 1f
            else -> (doneBytes.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
        }

    val openable: Boolean
        get() = !isDir && (openUri.isNotEmpty() || openPath.isNotEmpty())
}

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
    /** 单文件任务完成后的打开目标（多文件任务用 Engine.jobFiles 逐文件打开）。 */
    val openPath: String = "",
    val openUri: String = "",
    /** 文件数超过快照上限，UI 提示「仅显示前 N 个」。 */
    val filesTruncated: Boolean = false,
) {
    val progress: Float
        get() = when {
            totalBytes <= 0L -> if (state == JobState.DONE) 1f else 0f
            else -> (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        }

    /** 发送任务失败/暂停时可以重试；接收任务只能等对方重试。 */
    val canRetry: Boolean
        get() = dir == JobDir.SEND && (state == JobState.FAILED || state == JobState.PAUSED)

    /** 单文件完成的任务可在任务卡上直接「打开」。 */
    val canOpenSingle: Boolean
        get() = state == JobState.DONE && totalFiles == 1 && (openUri.isNotEmpty() || openPath.isNotEmpty())
}

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val childInfo: String = "",
    val mime: String = "",
    /** MediaStore content uri（媒体库来源才有）；缩略图可走系统 loadThumbnail。 */
    val contentUri: String = "",
) {
    /**
     * 文件类型。**惰性缓存**——原来是 `get() = FileKind.of(...)` 的每访问即重算：
     * `FileKind.of` 内含 `substringAfterLast('/')`/`substringAfterLast('.')`/`lowercase()`
     * 三次字符串操作 + HashMap 查找。而同一行里它至少被访问 2 次
     * （`FileListWidgets` 取 tint 一次、传 `kind` 一次），`ThumbCache` 判类型再 1 次；
     * 分类页 `CatalogPolicy.buckets` 对全部 5900 条再各调一次。
     *
     * 用 `lazy` 而不是构造参数，是为了**不改变构造签名/`equals`/`copy` 语义**：
     * 惰性字段不进构造参数，`copy()` 产生的新实例会重新懒算，不会带上旧缓存。
     * `lazy` 默认 SYNCHRONIZED，可安全跨线程（列表在 UI 读、分类在 IO 算）。
     */
    private val kindLazy: Lazy<FileKind> =
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            if (isDir) FileKind.OTHER else FileKind.of(name, mime.ifEmpty { null })
        }

    /** 目录没有文件类型；文件按扩展名（mime 兜底）判定。 */
    val kind: FileKind
        get() = kindLazy.value

    /**
     * 缩略图缓存键的公共前缀（`path|mtime|size`），**构造时算一次**。
     *
     * 原实现 `ThumbCache.keyOf` 每次调用都拼 `"${path}|${mtime}|${size}|$px"`，
     * 而一行里 `cached()` / `shouldLoad()` / `load()` 各拼一次 → 200 行约 600 次字符串构建/帧。
     * 这里预拼前缀，`keyOf` 只需拼上像素档位。
     */
    val thumbKeyBase: String = "$path|$mtime|$size"
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
