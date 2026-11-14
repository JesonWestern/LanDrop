package com.landrop.transfer.model

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 按偏移精确定长读取一段数据。传输层按 chunkIndex 随机读，所以这里要的是
 * 「可定位读」而不是顺序流。
 */
interface ChunkReader : Closeable {
    fun readAt(offset: Long, dst: ByteArray, dstOff: Int, len: Int)
}

/**
 * 文件数据来源。绝大多数场景是本地路径；从系统分享进来的是 content://，
 * 只能用 ParcelFileDescriptor 定位读（零拷贝），不能落到 RandomAccessFile。
 */
sealed interface Source {

    fun open(): ChunkReader

    class Path(val file: File) : Source {
        override fun open(): ChunkReader = PathReader(file)
    }

    class UriSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
    ) : Source {
        override fun open(): ChunkReader = FdReader(resolver, uri)
    }
}

private class PathReader(file: File) : ChunkReader {
    private val raf = RandomAccessFile(file, "r")

    override fun readAt(offset: Long, dst: ByteArray, dstOff: Int, len: Int) {
        raf.seek(offset)
        raf.readFully(dst, dstOff, len)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

private class FdReader(
    resolver: ContentResolver,
    uri: Uri,
) : ChunkReader {

    private val pfd: ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException("无法打开 $uri")

    private val channel = FileInputStream(pfd.fileDescriptor).channel

    override fun readAt(offset: Long, dst: ByteArray, dstOff: Int, len: Int) {
        val buf = ByteBuffer.wrap(dst, dstOff, len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw java.io.EOFException("数据源提前结束（可能已被来源应用回收）")
            pos += n
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}
