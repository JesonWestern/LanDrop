package com.landrop.transfer.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 线协议常量与编解码。必须与 Windows 端 src/proto.h 逐字段一致。
 */
object Proto {
    const val DISCOVER_PORT = 47820
    const val TRANSFER_PORT = 47821
    const val PROTO_VERSION = 1

    const val CHUNK = 1024 * 1024
    const val WINDOW = 8
    const val MAX_DATA_CONNS = 8
    const val DEVICE_TTL_MS = 12_000L
    const val PROBE_INTERVAL_MS = 3_000L
    const val MAX_LINE = 1024 * 1024

    // 'LDAT' / 'LDAK' 小端
    const val MAGIC_DATA = 0x5441444C
    const val MAGIC_ACK = 0x4B41444C

    const val F_LAST = 0x0001
    const val F_SPEED = 0x0002
    const val F_HELLO = 0x0004
    const val F_NAK = 0x0008

    private val CRC_TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[i] = c
        }
    }

    fun crc32(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
        var c = -1
        for (i in off until off + len) {
            c = CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }

    data class Header(
        val magic: Int,
        val taskId: Int,
        val streamSeq: Int,
        val flags: Int,
        val fileIndex: Int,
        val chunkIndex: Int,
        val payloadLen: Int,
        val payloadCrc: Int,
    )

    fun encode(h: Header): ByteArray {
        val b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(h.magic)
        b.putInt(h.taskId)
        b.putShort(h.streamSeq.toShort())
        b.putShort(h.flags.toShort())
        b.putInt(h.fileIndex)
        b.putInt(h.chunkIndex)
        b.putInt(h.payloadLen)
        b.putInt(h.payloadCrc)
        return b.array()
    }

    fun decode(buf: ByteArray, off: Int = 0): Header? {
        if (buf.size - off < 28) return null
        val b = ByteBuffer.wrap(buf, off, 28).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.int
        if (magic != MAGIC_DATA && magic != MAGIC_ACK) return null
        return Header(
            magic = magic,
            taskId = b.int,
            streamSeq = b.short.toInt() and 0xFFFF,
            flags = b.short.toInt() and 0xFFFF,
            fileIndex = b.int,
            chunkIndex = b.int,
            payloadLen = b.int,
            payloadCrc = b.int,
        )
    }

    fun taskIdFromKey(key: String): Int {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (ch in key.toByteArray(Charsets.UTF_8)) {
            h = h xor (ch.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        var v = (h xor (h ushr 32)).toInt()
        if (v == 0) v = 1
        return v
    }
}

/** 逐字节读一行（只用于握手阶段，避免缓冲越界吃掉后续二进制帧）。 */
class LineReader(private val input: InputStream) {
    fun readLine(): String? {
        val out = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val n = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.size - 1
                } else bytes.size
                return String(bytes, 0, n, Charsets.UTF_8)
            }
            out.write(b)
            if (out.size() > Proto.MAX_LINE) throw IOException("line too long")
        }
    }
}

fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
    var got = 0
    while (got < len) {
        val n = input.read(buf, off + got, len - got)
        if (n <= 0) return false
        got += n
    }
    return true
}
