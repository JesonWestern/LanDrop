package com.landrop.transfer.net

/**
 * 协议里的 mtime 统一用 Windows FILETIME（自 1601-01-01 起的 100ns）。
 * 与 Windows 端 SetFileTime/GetFileTime 语义一致。
 */
object TimeUtil {
    private const val EPOCH_DIFF = 116_444_736_000_000_000L

    fun msToFileTime(ms: Long): Long = if (ms <= 0) 0 else ms * 10_000L + EPOCH_DIFF

    fun fileTimeToMs(ft: Long): Long =
        if (ft <= EPOCH_DIFF) System.currentTimeMillis() else (ft - EPOCH_DIFF) / 10_000L
}
