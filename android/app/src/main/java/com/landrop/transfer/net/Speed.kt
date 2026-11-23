package com.landrop.transfer.net

import android.util.Log
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class SpeedResult(
    val ok: Boolean = false,
    val avgMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val p50Mbps: Double = 0.0,
    val bytes: Long = 0,
    val conns: Int = 0,
    val err: String = "",
)

/**
 * 网速测试：客户端打流 + 服务端陪跑。
 * 约定：数据连接 stream < conns 为“客户端上传”，>= conns 为“客户端下载”。
 */
object Speed {
    private const val TAG = "LanDropSpeed"
    private const val BUF = 1024 * 1024

    private class SSess(val token: String, val conns: Int, val dir: Int) {
        @Volatile var stop = false
    }

    private val sessions = ConcurrentHashMap<String, SSess>()

    // ---- 客户端 ------------------------------------------------------------
    fun runClient(
        settings: Settings,
        device: DeviceInfo,
        dirMode: Int,
        conns: Int,
        secs: Int,
        onSample: (Double) -> Unit,
        onDone: (SpeedResult) -> Unit,
    ) {
        Thread({
            val total = AtomicLong(0)
            val stop = AtomicBoolean(false)
            val workers = ArrayList<Thread>()
            var ctrl: Socket? = null
            var token = ""
            try {
                ctrl = connect(device.ip, device.port, 2000)
                val out = ctrl.getOutputStream()
                val input = BufferedInputStream(ctrl.getInputStream())
                sendLine(out, hello(settings, "ctrl"))
                val h = LineReader(input).readLine() ?: throw java.io.IOException("no hello")
                if (JSONObject(h).optString("t") != "hello_ok") throw java.io.IOException("bad hello")

                token = java.lang.String.format("%08x", (Math.random() * 0x7fffffff).toInt())
                sendLine(
                    out,
                    JSONObject()
                        .put("t", "speed_req")
                        .put("token", token)
                        .put("dir", dirMode)
                        .put("conns", conns)
                        .put("secs", secs)
                )
                val resp = LineReader(input).readLine() ?: throw java.io.IOException("no speed_ok")
                val ro = JSONObject(resp)
                if (ro.optString("t") != "speed_ok") {
                    onDone(SpeedResult(err = if (ro.optString("t") == "busy") "对方正在传文件，稍后再试" else "对方拒绝了测速"))
                    return@Thread
                }

                val plan = ArrayList<Pair<Int, Boolean>>()
                if (dirMode == 0 || dirMode == 2) for (i in 0 until conns) plan.add(i to true)
                if (dirMode == 1 || dirMode == 2) for (i in 0 until conns) plan.add((conns + i) to false)

                for ((stream, up) in plan) {
                    val ds = connect(device.ip, device.port, 2000)
                    val dOut = ds.getOutputStream()
                    val dIn = BufferedInputStream(ds.getInputStream())
                    sendLine(dOut, hello(settings, "data", token, stream))
                    val ok = LineReader(dIn).readLine() ?: throw java.io.IOException("no data_ok")
                    if (JSONObject(ok).optString("t") != "data_ok") throw java.io.IOException("bad data_ok")
                    val t = if (up) Thread({ upWorker(ds, dOut, total, stop) }, "spd-up-$stream")
                    else Thread({ downWorker(ds, dIn, total, stop) }, "spd-down-$stream")
                    t.isDaemon = true
                    t.start()
                    workers.add(t)
                }

                val t0 = System.currentTimeMillis()
                var last = 0L
                var lastT = t0
                val samples = ArrayList<Double>()
                while (System.currentTimeMillis() - t0 < secs * 1000L) {
                    Thread.sleep(200)
                    val cur = total.get()
                    val now = System.currentTimeMillis()
                    val dt = (now - lastT) / 1000.0
                    if (dt > 0.05) {
                        val mbps = (cur - last) * 8.0 / dt / 1e6
                        samples.add(mbps)
                        onSample(mbps)
                    }
                    last = cur
                    lastT = now
                }
                stop.set(true)
                for (t in workers) t.join(3000)
                workers.clear()

                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                val bytes = total.get()
                val trimmed = if (samples.size > 6) samples.subList(1, samples.size - 2) else samples
                val avg = if (elapsed > 0.05) bytes * 8.0 / elapsed / 1e6 else 0.0
                val peak = trimmed.maxOrNull() ?: 0.0
                val p50 = if (trimmed.isEmpty()) 0.0 else trimmed.sorted()[trimmed.size / 2]
                onDone(
                    SpeedResult(
                        ok = bytes > 0,
                        avgMbps = avg,
                        peakMbps = peak,
                        p50Mbps = p50,
                        bytes = bytes,
                        conns = conns,
                        err = if (bytes <= 0) "没有收到数据，检查网络" else "",
                    )
                )
            } catch (e: Exception) {
                onDone(SpeedResult(err = e.message ?: "测速失败"))
            } finally {
                stop.set(true)
                for (t in workers) runCatching { t.join(1500) }
                runCatching { ctrl?.close() }
            }
        }, "landrop-speed").apply { isDaemon = true }.start()
    }

    private fun upWorker(sock: Socket, out: OutputStream, total: AtomicLong, stop: AtomicBoolean) {
        val buf = ByteArray(BUF) { 0x5A }
        try {
            while (!stop.get()) {
                out.write(buf)
                out.flush()
                total.addAndGet(buf.size.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun downWorker(sock: Socket, input: InputStream, total: AtomicLong, stop: AtomicBoolean) {
        val buf = ByteArray(256 * 1024)
        try {
            while (!stop.get()) {
                val n = input.read(buf)
                if (n <= 0) break
                total.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---- 服务端 ------------------------------------------------------------
    fun serverServe(ctrl: Socket, req: JSONObject, dev: DeviceInfo) {
        val token = req.optString("token", "anon")
        val conns = req.optInt("conns", 4).coerceIn(1, Proto.MAX_DATA_CONNS)
        val dir = req.optInt("dir", 2)
        val secs = req.optInt("secs", 10).coerceIn(1, 60)
        val sess = SSess(token, conns, dir)
        sessions[token] = sess
        Log.i(TAG, "serve token=$token dir=$dir conns=$conns secs=$secs")
        try {
            val out = ctrl.getOutputStream()
            sendLine(out, JSONObject().put("t", "speed_ok").put("token", token))
            val deadline = System.currentTimeMillis() + secs * 1000L
            while (System.currentTimeMillis() < deadline) Thread.sleep(100)
            sess.stop = true
            Thread.sleep(300)
            sendLine(out, JSONObject().put("t", "speed_done"))
        } catch (e: Exception) {
            Log.w(TAG, "serve: ${e.message}")
        } finally {
            sessions.remove(token)
            runCatching { ctrl.close() }
        }
    }

    fun attachData(sock: Socket, token: String, stream: Int) {
        val sess = sessions[token]
        if (sess == null) {
            runCatching { sock.close() }
            return
        }
        val t = if (stream < sess.conns) {
            Thread({ drain(sock, sess) }, "spd-srv-in-$stream")
        } else {
            Thread({ blast(sock, sess) }, "spd-srv-out-$stream")
        }
        t.isDaemon = true
        t.start()
    }

    private fun drain(sock: Socket, sess: SSess) {
        val buf = ByteArray(256 * 1024)
        try {
            val input = sock.getInputStream()
            while (!sess.stop) {
                val n = input.read(buf)
                if (n <= 0) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun blast(sock: Socket, sess: SSess) {
        val buf = ByteArray(BUF) { 0xA5.toByte() }
        try {
            val out = sock.getOutputStream()
            while (!sess.stop) {
                out.write(buf)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---- 工具 --------------------------------------------------------------
    private fun hello(
        settings: Settings,
        kind: String,
        token: String = "",
        stream: Int = 0,
    ): JSONObject {
        val o = JSONObject()
        o.put("t", "hello")
        o.put("kind", kind)
        o.put("v", Proto.PROTO_VERSION)
        o.put("id", settings.deviceId)
        o.put("name", settings.deviceName)
        o.put("plat", "android")
        o.put("ver", com.landrop.transfer.BuildConfig.VERSION_NAME)
        o.put("caps", JSONArray().put("speed"))
        if (kind == "data") {
            o.put("job", "speed:$token")
            o.put("stream", stream)
        }
        return o
    }

    private fun connect(ip: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.sendBufferSize = 4 * 1024 * 1024
        s.receiveBufferSize = 4 * 1024 * 1024
        s.soTimeout = 0
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        return s
    }

    private fun sendLine(out: OutputStream, o: JSONObject) {
        out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
