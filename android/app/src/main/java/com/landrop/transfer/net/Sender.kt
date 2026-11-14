package com.landrop.transfer.net

import android.util.Log
import com.landrop.transfer.core.Job
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.ChunkReader
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.JobState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Android → Windows / Android → Android 发送端。与 C++ 端 tcp_client.cpp 行为一致。 */
class Sender(
    private val settings: Settings,
    private val job: Job,
    private val onUpdate: () -> Unit,
) {
    private val pending = ArrayList<Pair<Int, Int>>() // entryIdx, chunkIdx
    private val cursor = AtomicInteger(0)
    @Volatile private var aborted = false

    /** 读源文件失败（非网络问题）：重试没意义，直接判失败。 */
    @Volatile private var sourceError = false
    private var token = ""

    fun start() {
        Thread({ run() }, "landrop-send-${job.id}").apply { isDaemon = true }.start()
    }

    private fun run() {
        var attempt = 1
        while (true) {
            val retryable = runOnce()
            if (!retryable || job.cancel) return
            if (attempt >= MAX_ATTEMPTS) {
                job.state = JobState.FAILED
                job.endedAt = System.currentTimeMillis()
                job.bps = 0.0
                job.errMsg = job.errMsg.ifEmpty { "传输失败" } + "（已重试 ${MAX_ATTEMPTS - 1} 次）"
                onUpdate()
                return
            }
            job.state = JobState.HANDSHAKE
            job.bps = 0.0
            onUpdate()
            try {
                Thread.sleep(800L * attempt)
            } catch (_: InterruptedException) {
                return
            }
            attempt++
        }
    }

    /** @return true 表示这次失败可以重试（接收端有 meta.json 位图，重试天然续传）。 */
    private fun runOnce(): Boolean {
        var ctrl: Socket? = null
        try {
            // 每次尝试都从干净状态开始：重试时本地位图与已发字节必须归零，
            // 否则 reconcile 服务端 have 位图时会重复累加 doneBytes。
            for (e in job.files) e.reset()
            job.doneBytes.set(0)
            pending.clear()
            cursor.set(0)
            aborted = false
            sourceError = false
            token = ""

            ctrl = connect()
            val out = ctrl.getOutputStream()
            val input = BufferedInputStream(ctrl.getInputStream())

            sendLine(out, hello("ctrl"))
            val helloOk = LineReader(input).readLine() ?: throw EOFException("no hello_ok")
            if (JSONObject(helloOk).optString("t") != "hello_ok") throw EOFException("bad hello")

            job.state = JobState.HANDSHAKE
            onUpdate()

            val offer = JSONObject()
            offer.put("t", "offer")
            offer.put("total", job.totalBytes)
            val arr = JSONArray()
            for (e in job.files) {
                val o = JSONObject()
                o.put("rel", e.rel.replace('/', '\\'))
                o.put("size", e.size)
                o.put("mtime", TimeUtil.msToFileTime(e.mtime))
                o.put("isDir", e.isDir)
                arr.put(o)
            }
            offer.put("files", arr)
            sendLine(out, offer)

            // 对方要人工点「接收」，最长等 60 秒（ReceiverServer 侧上限），
            // 这里必须比它长，否则会出现「我才点接收，对方已经超时失败」
            ctrl.soTimeout = 65_000
            val resp = LineReader(input).readLine() ?: throw EOFException("no accept")
            ctrl.soTimeout = SOCKET_TIMEOUT_MS
            val ro = JSONObject(resp)
            when (ro.optString("t")) {
                "reject" -> {
                    val reason = ro.optString("reason")
                    fail(
                        when (reason) {
                            "no_space" -> "对方磁盘空间不足"
                            "unsafe_path" -> "文件名不合法"
                            "declined" -> "对方取消了本次接收"
                            else -> "对方拒绝接收"
                        }
                    )
                    return false
                }
                "accept" -> Unit
                else -> throw EOFException("bad resp ${ro.optString("t")}")
            }
            token = ro.optString("job")

            // 续传位图
            val have = ro.optJSONArray("have")
            if (have != null) {
                for (i in 0 until have.length()) {
                    val o = have.getJSONObject(i)
                    val idx = o.optInt("i", -1)
                    if (idx < 0 || idx >= job.files.size) continue
                    val bits = o.optJSONArray("bits") ?: continue
                    val e = job.files[idx]
                    for (k in 0 until minOf(bits.length(), e.bits.size)) {
                        e.bits[k] = bits.optInt(k, 0)
                    }
                    var counted = 0
                    for (c in 0 until e.chunkCount) {
                        if ((e.bits[c ushr 5] and (1 shl (c and 31))) != 0) counted++
                    }
                    job.doneBytes.addAndGet((counted.toLong() * Proto.CHUNK).coerceAtMost(e.size))
                }
                if (job.doneBytes.get() > job.totalBytes) job.doneBytes.set(job.totalBytes)
            }

            for (fi in job.files.indices) {
                val e = job.files[fi]
                if (e.isDir) continue
                for (c in 0 until e.chunkCount) {
                    if (e.has(c)) continue
                    pending.add(fi to c)
                }
            }

            job.state = JobState.TRANSFERRING
            onUpdate()

            if (pending.isNotEmpty()) {
                val conns = settings.dataConns.coerceIn(1, Proto.MAX_DATA_CONNS)
                val threads = ArrayList<Thread>(conns)
                for (i in 0 until conns) {
                    val ds = connect()
                    val dOut = ds.getOutputStream()
                    val dIn = BufferedInputStream(ds.getInputStream())
                    sendLine(dOut, hello("data", i))
                    val ok = LineReader(dIn).readLine() ?: throw EOFException("no data_ok")
                    if (JSONObject(ok).optString("t") != "data_ok") throw EOFException("bad data_ok")
                    val t = Thread({ dataWorker(ds, dOut, dIn, i) }, "landrop-data-$i")
                    t.isDaemon = true
                    t.start()
                    threads.add(t)
                }
                for (t in threads) t.join()
            }

            if (job.cancel || aborted) {
                job.state = if (job.cancel) JobState.CANCELED else JobState.HANDSHAKE
                if (job.errMsg.isEmpty()) job.errMsg = if (job.cancel) "已取消" else "传输中断"
                job.endedAt = System.currentTimeMillis()
                onUpdate()
                return !job.cancel && !sourceError
            }

            val done = JSONObject().put("t", "done").put("job", token).put("bytes", job.doneBytes.get())
            sendLine(out, done)
            ctrl.soTimeout = 60000
            val finalLine = LineReader(input).readLine()
            val ok = finalLine != null && JSONObject(finalLine).optString("t") == "done_ok"
            job.state = if (ok) JobState.DONE else JobState.FAILED
            if (!ok) job.errMsg = "对方校验失败"
            job.bps = 0.0
            job.endedAt = System.currentTimeMillis()
            onUpdate()
            return false
        } catch (e: Exception) {
            if (job.cancel) {
                job.state = JobState.CANCELED
                job.endedAt = System.currentTimeMillis()
                onUpdate()
                return false
            }
            val retryable = isRetryable(e)
            job.errMsg = describe(e)
            job.bps = 0.0
            if (retryable) {
                Log.w(TAG, "send attempt failed: ${e.message}")
                onUpdate()
            } else {
                job.state = JobState.FAILED
                job.endedAt = System.currentTimeMillis()
                Log.w(TAG, "send fail: ${e.message}")
                onUpdate()
            }
            return retryable
        } finally {
            try {
                ctrl?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun fail(msg: String) {
        job.state = JobState.FAILED
        job.errMsg = msg
        job.bps = 0.0
        job.endedAt = System.currentTimeMillis()
        Log.w(TAG, "send fail: $msg")
        onUpdate()
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is SocketTimeoutException, is ConnectException, is SocketException, is EOFException -> true
        is java.io.IOException -> true
        else -> false
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "对方未响应（可能已锁屏或应用被切到后台）"
        is ConnectException -> "对方未监听，请确认应用在前台"
        is EOFException -> "对方中断了连接"
        else -> e.message ?: "网络错误"
    }

    private fun dataWorker(sock: Socket, out: OutputStream, input: InputStream, seq: Int) {
        // 帧头 + 载荷放在同一个缓冲区，一次 write 发出：
        // TCP_NODELAY 下分开写会让 28 字节的帧头单独成包。
        val frame = ByteArray(Proto.CHUNK + HDR)
        var inflight = 0
        var errors = 0
        val ackBuf = ByteArray(HDR)

        // 每个 worker 缓存最近用过的文件句柄：Android 的 /sdcard 是 FUSE，
        // 每块 open/close 一次的代价远比普通文件系统高。
        val readers = LinkedHashMap<Int, ChunkReader>()

        fun readerFor(fi: Int): ChunkReader {
            readers[fi]?.let { return it }
            if (readers.size >= MAX_OPEN_FILES) {
                val it = readers.entries.iterator()
                if (it.hasNext()) {
                    val oldest = it.next()
                    runCatching { oldest.value.close() }
                    it.remove()
                }
            }
            val r = job.files[fi].source.open()
            readers[fi] = r
            return r
        }

        fun readAck(): Boolean {
            if (!readFully(input, ackBuf, 0, HDR)) return false
            val h = Proto.decode(ackBuf) ?: return false
            if (h.magic != Proto.MAGIC_ACK) return false
            if (h.flags and Proto.F_NAK != 0) {
                errors++
                return true
            }
            val fi = h.fileIndex
            if (fi in job.files.indices) {
                val e = job.files[fi]
                synchronized(e.lock) {
                    if (e.mark(h.chunkIndex)) {
                        val len = chunkLen(e, h.chunkIndex)
                        job.doneBytes.addAndGet(len.toLong())
                    }
                }
            }
            return true
        }

        try {
            while (!aborted && !job.cancel) {
                val i = cursor.getAndIncrement()
                if (i >= pending.size) break
                val (fi, ci) = pending[i]
                val e = job.files[fi]
                val off = ci.toLong() * Proto.CHUNK
                val len = chunkLen(e, ci)

                // 数据来源可能是本地文件，也可能是分享进来的 content://（UriSource）
                try {
                    readerFor(fi).readAt(off, frame, HDR, len)
                } catch (ex: Exception) {
                    sourceError = true
                    job.errMsg = "读取源文件失败（可能已被来源应用回收）"
                    throw ex
                }

                val h = Proto.Header(
                    magic = Proto.MAGIC_DATA,
                    taskId = Proto.taskIdFromKey(token),
                    streamSeq = seq,
                    flags = if (ci + 1 == e.chunkCount) Proto.F_LAST else 0,
                    fileIndex = fi,
                    chunkIndex = ci,
                    payloadLen = len,
                    payloadCrc = Proto.crc32(frame, HDR, len),
                )
                System.arraycopy(Proto.encode(h), 0, frame, 0, HDR)
                out.write(frame, 0, HDR + len)
                inflight++
                while (inflight >= Proto.WINDOW && !aborted) {
                    if (!readAck()) {
                        aborted = true
                        break
                    }
                    inflight--
                    if (errors > 64) {
                        aborted = true
                        break
                    }
                }
                if (errors > 64) {
                    aborted = true
                    break
                }
            }
            var guard = 0
            while (inflight > 0 && !aborted && guard++ < 4000) {
                if (!readAck()) break
                inflight--
            }
        } catch (e: Exception) {
            Log.w(TAG, "worker $seq: ${e.message}")
            aborted = true
        } finally {
            for (r in readers.values) runCatching { r.close() }
            readers.clear()
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun chunkLen(e: Entry, ci: Int): Int {
        val off = ci.toLong() * Proto.CHUNK
        val left = e.size - off
        return if (left > Proto.CHUNK) Proto.CHUNK else left.toInt().coerceAtLeast(0)
    }

    private fun hello(kind: String, stream: Int = 0): JSONObject {
        val o = JSONObject()
        o.put("t", "hello")
        o.put("kind", kind)
        o.put("v", Proto.PROTO_VERSION)
        o.put("id", settings.deviceId)
        o.put("name", settings.deviceName)
        o.put("plat", "android")
        o.put("ver", "1.1.0")
        o.put("caps", JSONArray().put("file").put("folder").put("resume").put("speed"))
        if (kind == "data") {
            o.put("job", token)
            o.put("stream", stream)
        }
        return o
    }

    private fun connect(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.sendBufferSize = SOCKET_BUF
        s.receiveBufferSize = SOCKET_BUF
        s.soTimeout = SOCKET_TIMEOUT_MS
        s.connect(InetSocketAddress(job.device.ip, job.device.port), 5000)
        Log.d(TAG, "connected ${job.device.ip}:${job.device.port} snd=${s.sendBufferSize} rcv=${s.receiveBufferSize}")
        return s
    }

    private fun sendLine(out: OutputStream, o: JSONObject) {
        out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    companion object {
        const val TAG = "LanDropSend"

        /** 28 字节帧头，必须与 proto.h 的 sizeof(DataHdr) 一致 */
        private const val HDR = 28
        private const val SOCKET_BUF = 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_ATTEMPTS = 3
        private const val MAX_OPEN_FILES = 8
    }
}
