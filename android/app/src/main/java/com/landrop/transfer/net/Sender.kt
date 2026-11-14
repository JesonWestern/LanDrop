package com.landrop.transfer.net

import android.util.Log
import com.landrop.transfer.core.Job
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.JobState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Android → Windows 发送端。与 C++ 端 tcp_client.cpp 行为一致。 */
class Sender(
    private val settings: Settings,
    private val job: Job,
    private val onUpdate: () -> Unit,
) {
    private val pending = ArrayList<Pair<Int, Int>>() // entryIdx, chunkIdx
    private val cursor = AtomicInteger(0)
    @Volatile private var aborted = false
    private var token = ""

    fun start() {
        Thread({ run() }, "landrop-send-${job.id}").apply { isDaemon = true }.start()
    }

    private fun fail(msg: String) {
        job.state = JobState.FAILED
        job.errMsg = msg
        job.bps = 0.0
        job.endedAt = System.currentTimeMillis()
        Log.w(TAG, "send fail: $msg")
        onUpdate()
    }

    private fun run() {
        var ctrl: Socket? = null
        try {
            ctrl = connect()
            val out = ctrl.getOutputStream()
            val input = BufferedInputStream(ctrl.getInputStream())

            sendLine(out, hello("ctrl"))
            val helloOk = LineReader(input).readLine() ?: throw java.io.IOException("no hello_ok")
            if (JSONObject(helloOk).optString("t") != "hello_ok") throw java.io.IOException("bad hello")

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

            val resp = LineReader(input).readLine() ?: throw java.io.IOException("no accept")
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
                    return
                }
                "accept" -> Unit
                else -> throw java.io.IOException("bad resp ${ro.optString("t")}")
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
                    val ok = LineReader(dIn).readLine() ?: throw java.io.IOException("no data_ok")
                    if (JSONObject(ok).optString("t") != "data_ok") throw java.io.IOException("bad data_ok")
                    val t = Thread({ dataWorker(ds, dOut, dIn, i) }, "landrop-data-$i")
                    t.isDaemon = true
                    t.start()
                    threads.add(t)
                }
                for (t in threads) t.join()
            }

            if (job.cancel || aborted) {
                job.state = if (job.cancel) JobState.CANCELED else JobState.FAILED
                if (!job.cancel) job.errMsg = "传输中断"
                job.endedAt = System.currentTimeMillis()
                onUpdate()
                return
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
        } catch (e: Exception) {
            if (job.cancel) {
                job.state = JobState.CANCELED
            } else {
                fail(e.message ?: "网络错误")
            }
        } finally {
            try {
                ctrl?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun dataWorker(sock: Socket, out: OutputStream, input: InputStream, seq: Int) {
        val buf = ByteArray(Proto.CHUNK)
        var inflight = 0
        var errors = 0
        val ackBuf = ByteArray(28)
        var ran = 0L

        fun readAck(): Boolean {
            if (!readFully(input, ackBuf, 0, 28)) return false
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
                ran++
                java.io.RandomAccessFile(e.localFile(), "r").use { raf ->
                    raf.seek(off)
                    raf.readFully(buf, 0, len)
                }
                val h = Proto.Header(
                    magic = Proto.MAGIC_DATA,
                    taskId = Proto.taskIdFromKey(token),
                    streamSeq = seq,
                    flags = if (ci + 1 == e.chunkCount) Proto.F_LAST else 0,
                    fileIndex = fi,
                    chunkIndex = ci,
                    payloadLen = len,
                    payloadCrc = Proto.crc32(buf, 0, len),
                )
                out.write(Proto.encode(h))
                out.write(buf, 0, len)
                out.flush()
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
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun chunkLen(e: com.landrop.transfer.model.Entry, ci: Int): Int {
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
        o.put("ver", "1.0.0")
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
        s.sendBufferSize = 1024 * 1024
        s.receiveBufferSize = 1024 * 1024
        s.soTimeout = 30000
        s.connect(InetSocketAddress(job.device.ip, job.device.port), 5000)
        return s
    }

    private fun sendLine(out: OutputStream, o: JSONObject) {
        out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    companion object {
        const val TAG = "LanDropSend"
    }
}
