package com.landrop.transfer.model

import com.landrop.transfer.net.Proto
import java.io.File

/** 传输清单中的一条：文件或空目录。 */
class Entry(
    val rel: String,
    val absPath: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    /** 数据来源。默认等价于「按 absPath 读本地文件」，从系统分享进来的 content:// 会传 UriSource。 */
    val source: Source = Source.Path(File(absPath)),
) {
    val chunkCount: Int = if (isDir || size <= 0) 0
    else (((size + Proto.CHUNK - 1) / Proto.CHUNK).toInt())

    val bits: IntArray = IntArray((chunkCount + 31) / 32)

    /** 每个文件一把锁：多个数据连接可能同时写同一个文件的不同块。 */
    val lock = Any()

    /** @Volatile：UI 线程只读快照，写侧在 [lock] 内自增。 */
    @Volatile
    var doneChunks: Int = 0
        private set

    /** 已传字节（发送/接收两侧共用；UI 线程可直接读做进度展示）。 */
    fun doneBytes(): Long = if (isDir || size <= 0L) 0L
    else (doneChunks.toLong() * Proto.CHUNK).coerceAtMost(size)

    /** 单文件进度；0 字节文件与目录视为已完成。 */
    fun progress(): Float = when {
        isDir -> 1f
        chunkCount == 0 -> 1f
        else -> (doneBytes().toDouble() / size).coerceIn(0.0, 1.0).toFloat()
    }

    fun has(i: Int): Boolean {
        val w = i ushr 5
        val b = 1 shl (i and 31)
        return w < bits.size && (bits[w] and b) != 0
    }

    fun mark(i: Int): Boolean {
        val w = i ushr 5
        val b = 1 shl (i and 31)
        if (w >= bits.size) return false
        if (bits[w] and b != 0) return false
        bits[w] = bits[w] or b
        doneChunks++
        return true
    }

    fun reset() {
        bits.fill(0)
        doneChunks = 0
    }

    fun recount() {
        var c = 0
        for (i in 0 until chunkCount) if (has(i)) c++
        doneChunks = c
    }

    fun localFile(): File = File(absPath)
}
