package com.landrop.transfer.net

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.landrop.transfer.core.Job
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobState
import com.landrop.transfer.storage.DownloadSink
import com.landrop.transfer.util.JobIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ReceiverServer(
    private val context: Context,
    private val settings: Settings,
    private val onNewJob: (Job) -> Unit,
    private val onUpdate: () -> Unit,
    private val autoAcceptAll: () -> Boolean = { false },
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = ConcurrentHashMap<String, Session>()

    private inner class Session(val job: Job) {
        var tempRoot: File? = null
        val rafs = ConcurrentHashMap<Int, RandomAccessFile>()
        val received = AtomicInteger(0)
        @Volatile var complete = false
        @Volatile var failed = false
        @Volatile var ctrl: Socket? = null
    }

    fun start(): Boolean {
        if (running) return true
        running = true
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Proto.TRANSFER_PORT))
            server = ss
            acceptThread = Thread({ acceptLoop(ss) }, "landrop-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening ${Proto.TRANSFER_PORT}")
            return true
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "listen failed: ${e.message}")
            return false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        for (s in sessions.values) closeSession(s)
        sessions.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val s = ss.accept()
                s.tcpNoDelay = true
                s.receiveBufferSize = 1024 * 1024
                s.sendBufferSize = 1024 * 1024
                s.soTimeout = 30000
                Thread({ dispatch(s) }, "landrop-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "accept: ${e.message}")
                    try {
                        Thread.sleep(80)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    private fun dispatch(sock: Socket) {
        try {
            val input = BufferedInputStream(sock.getInputStream())
            val line = LineReader(input).readLine() ?: run { sock.close(); return }
            val hello = JSONObject(line)
            if (hello.optString("t") != "hello") {
                sock.close()
                return
            }
            val kind = hello.optString("kind", "ctrl")
            if (kind == "data") {
                val out = sock.getOutputStream()
                out.write("{\"t\":\"data_ok\"}\n".toByteArray())
                out.flush()
                val token = hello.optString("job")
                val stream = hello.optInt("stream", 0)
                if (token.startsWith("speed:")) {
                    Speed.attachData(sock, token.removePrefix("speed:"), stream)
                } else {
                    val sess = sessions[token]
                    if (sess == null) {
                        sock.close()
                    } else {
                        Thread({ dataLoop(sock, sess, stream) }, "landrop-in").apply {
                            isDaemon = true
                        }.start()
                    }
                }
            } else {
                handleControl(sock, input, hello)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatch: ${e.message}")
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---- 控制连接 -----------------------------------------------------------
    private fun handleControl(sock: Socket, input: BufferedInputStream, hello: JSONObject) {
        val out = sock.getOutputStream()
        fun send(o: JSONObject) {
            out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val dev = DeviceInfo(
            id = hello.optString("id"),
            name = hello.optString("name", "电脑"),
            plat = hello.optString("plat", "win"),
            ver = hello.optString("ver", ""),
            ip = sock.inetAddress.hostAddress ?: "",
            port = Proto.TRANSFER_PORT,
            lastSeen = System.currentTimeMillis(),
        )
        send(
            JSONObject()
                .put("t", "hello_ok")
                .put("v", Proto.PROTO_VERSION)
                .put("name", settings.deviceName)
                .put("chunkBytes", Proto.CHUNK)
                .put("caps", JSONArray().put("file").put("folder").put("resume").put("speed"))
        )

        val reader = LineReader(input)
        val next = reader.readLine() ?: run { sock.close(); return }
        val req = JSONObject(next)
        when (req.optString("t")) {
            "speed_req" -> {
                Speed.serverServe(sock, req, dev)
                return
            }
            "offer" -> Unit
            else -> {
                sock.close()
                return
            }
        }

        val filesArr = req.optJSONArray("files")
        if (filesArr == null || filesArr.length() == 0) {
            send(JSONObject().put("t", "reject").put("reason", "empty"))
            sock.close()
            return
        }

        val entries = ArrayList<Entry>(filesArr.length())
        var totalBytes = 0L
        var totalChunks = 0
        var bad = false
        for (i in 0 until filesArr.length()) {
            val o = filesArr.getJSONObject(i)
            val relRaw = o.optString("rel").replace('/', '\\')
            val isDir = o.optBoolean("isDir", false)
            val size = o.optLong("size", 0)
            val mtime = o.optLong("mtime", 0)
            if (!isSafeRel(relRaw)) {
                bad = true
                break
            }
            val clean = relRaw.split('\\').joinToString("/") { sanitize(it) }
            val e = Entry(clean, "", size, TimeUtil.fileTimeToMs(mtime), isDir)
            entries.add(e)
            if (!isDir) {
                totalBytes += size
                totalChunks += e.chunkCount
            }
        }
        if (bad || entries.isEmpty()) {
            send(JSONObject().put("t", "reject").put("reason", "unsafe_path"))
            sock.close()
            return
        }

        val title = if (entries.size == 1) entries[0].rel.substringAfterLast('/')
        else entries[0].rel.substringBefore('/')
        val job = Job(
            id = JobIds.next(),
            dir = JobDir.RECV,
            device = dev,
            title = title,
            files = entries,
        )
        job.trustedPeer = settings.isTrusted(dev.id)

        // 磁盘空间预检
        val need = totalBytes + totalBytes / 20 + 200L * 1024 * 1024
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val free = stat.availableBytes
        if (free > 0 && free < need) {
            send(JSONObject().put("t", "reject").put("reason", "no_space"))
            sock.close()
            return
        }

        job.state = JobState.WAITING_ACCEPT
        onNewJob(job)
        onUpdate()

        val auto = autoAcceptAll() || (settings.autoAcceptTrusted && job.trustedPeer)
        val t0 = System.currentTimeMillis()
        while (!auto) {
            if (job.cancel) break
            if (job.acceptFlag) break
            if (System.currentTimeMillis() - t0 > 60_000) break
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
            }
        }
        if (job.cancel || (!auto && !job.acceptFlag)) {
            send(JSONObject().put("t", "reject").put("reason", "declined"))
            job.state = JobState.CANCELED
            job.errMsg = "已拒绝"
            onUpdate()
            sock.close()
            return
        }
        if (job.acceptFlag) settings.trust(dev.id)

        val sess = Session(job)
        sess.ctrl = sock
        val key = jobKey(dev.id, entries)
        job.jobKey = key
        val root = DownloadSink.incomingRoot(context, key)
        sess.tempRoot = root
        job.tempRoot = root

        // 0 字节文件：先建空文件，提交阶段才有东西可改名
        for (e in entries) {
            if (e.isDir || e.size != 0L) continue
            val f = File(root, e.rel + ".part")
            f.parentFile?.mkdirs()
            runCatching { f.createNewFile() }
            e.mark(0)
        }

        // 续传
        val meta = File(root, "meta.json")
        if (meta.exists()) {
            try {
                val mo = JSONObject(meta.readText())
                val fa = mo.optJSONArray("files")
                if (fa != null) {
                    for (i in 0 until fa.length()) {
                        val o = fa.getJSONObject(i)
                        val rel = o.optString("rel")
                        val e = entries.firstOrNull { it.rel == rel } ?: continue
                        val bits = o.optJSONArray("bits") ?: continue
                        for (k in 0 until minOf(bits.length(), e.bits.size)) e.bits[k] = bits.optInt(k, 0)
                        var counted = 0
                        for (c in 0 until e.chunkCount) {
                            if ((e.bits[c ushr 5] and (1 shl (c and 31))) != 0) {
                                counted++
                                job.doneBytes.addAndGet(chunkLen(e, c).toLong())
                            }
                        }
                        sess.received.addAndGet(counted)
                    }
                    Log.i(TAG, "resume ${sess.received.get()}/$totalChunks")
                }
            } catch (_: Exception) {
            }
        }

        sessions[key] = sess

        val accept = JSONObject()
            .put("t", "accept")
            .put("job", key)
            .put("chunkBytes", Proto.CHUNK)
        val haveArr = JSONArray()
        for (i in entries.indices) {
            val e = entries[i]
            e.recount()
            if (e.isDir || e.doneChunks == 0) continue
            val bitsArr = JSONArray()
            for (w in e.bits) bitsArr.put(w)
            haveArr.put(JSONObject().put("i", i).put("bits", bitsArr))
        }
        accept.put("have", haveArr)
        send(accept)

        job.state = JobState.TRANSFERRING
        onUpdate()

        var lastBytes = job.doneBytes.get()
        var lastProgress = System.currentTimeMillis()
        var lastSaveAt = lastProgress
        while (running) {
            if (job.cancel) break
            if (sess.complete) break
            if (sess.failed) break
            val cur = job.doneBytes.get()
            if (cur != lastBytes) {
                lastBytes = cur
                lastProgress = System.currentTimeMillis()
                // 周期落盘：进程被系统杀掉时，位图最多只丢最近 3 秒的进度
                if (lastProgress - lastSaveAt > 3000) {
                    lastSaveAt = lastProgress
                    saveMeta(root, entries)
                }
            }
            if (System.currentTimeMillis() - lastProgress > 25_000) {
                saveMeta(root, entries)
                job.state = JobState.PAUSED
                job.errMsg = "连接中断，可续传"
                onUpdate()
                closeSession(sess)
                sessions.remove(key)
                return
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
        }

        if (job.cancel) {
            saveMeta(root, entries)
            job.state = JobState.CANCELED
            job.errMsg = "已取消"
            send(JSONObject().put("t", "error").put("reason", "canceled"))
            onUpdate()
            closeSession(sess)
            sessions.remove(key)
            return
        }
        if (!sess.complete || sess.failed) {
            saveMeta(root, entries)
            job.state = JobState.FAILED
            job.errMsg = if (sess.failed) "数据校验失败" else "未收全"
            send(JSONObject().put("t", "error").put("reason", "incomplete"))
            onUpdate()
            closeSession(sess)
            sessions.remove(key)
            return
        }

        job.state = JobState.VERIFYING
        onUpdate()
        closeData(sess)

        try {
            val top = entries[0].rel.substringBefore('/')
            val structured = entries.any { it.rel.contains('/') }
            var shownRoot = "Download/LanDrop"
            val finalDir = File(root, "final")
            val publicRoot = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LanDrop"
            )
            for (e in entries) {
                if (e.isDir) {
                    // MediaStore 无法表达空目录，直接用文件 API 建（已有全部文件权限）
                    File(publicRoot, e.rel).mkdirs()
                    continue
                }
                val part = File(root, e.rel + ".part")
                val dst = File(finalDir, e.rel)
                dst.parentFile?.mkdirs()
                if (part.exists()) {
                    if (!part.renameTo(dst)) part.copyTo(dst, overwrite = true)
                }
                if (e.mtime > 0) dst.setLastModified(e.mtime)
                DownloadSink.publish(context, dst, e.rel)
            }
            shownRoot = if (structured) "Download/LanDrop/$top" else "Download/LanDrop"
            job.savePath = shownRoot
            job.state = JobState.DONE
            job.bps = 0.0
            job.endedAt = System.currentTimeMillis()
            send(
                JSONObject().put("t", "done_ok").put("job", key).put("path", shownRoot)
            )
            root.deleteRecursively()
            sessions.remove(key)
            onUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "commit: ${e.message}")
            job.state = JobState.FAILED
            job.errMsg = "保存失败：${e.message}"
            send(JSONObject().put("t", "error").put("reason", "commit"))
            onUpdate()
            sessions.remove(key)
        }
    }

    private fun dataLoop(sock: Socket, sess: Session, seq: Int) {
        val input = BufferedInputStream(sock.getInputStream())
        val out: OutputStream = sock.getOutputStream()
        val hdr = ByteArray(28)
        val payload = ByteArray(Proto.CHUNK)
        try {
            while (running && !sess.failed) {
                if (sess.job.cancel) break
                if (!readFully(input, hdr, 0, 28)) break
                val h = Proto.decode(hdr) ?: break
                if (h.magic != Proto.MAGIC_DATA) break
                if (h.payloadLen < 0 || h.payloadLen > Proto.CHUNK) break
                if (h.payloadLen > 0 && !readFully(input, payload, 0, h.payloadLen)) break

                val crcOk = h.payloadLen == 0 || Proto.crc32(payload, 0, h.payloadLen) == h.payloadCrc
                var ok = false
                if (crcOk && h.fileIndex in sess.job.files.indices) {
                    val e = sess.job.files[h.fileIndex]
                    synchronized(e.lock) {
                        if (!e.has(h.chunkIndex)) {
                            ok = writeChunk(sess, h.fileIndex, e, h.chunkIndex, payload, h.payloadLen)
                            if (ok) {
                                e.mark(h.chunkIndex)
                                sess.job.doneBytes.addAndGet(h.payloadLen.toLong())
                                sess.received.incrementAndGet()
                            }
                        } else {
                            ok = true
                        }
                    }
                }
                val ack = Proto.Header(
                    magic = Proto.MAGIC_ACK,
                    taskId = h.taskId,
                    streamSeq = seq,
                    flags = if (ok) 0 else Proto.F_NAK,
                    fileIndex = h.fileIndex,
                    chunkIndex = h.chunkIndex,
                    payloadLen = 0,
                    payloadCrc = 0,
                )
                out.write(Proto.encode(ack))
                out.flush()
                if (sess.received.get() >= sess.job.totalChunks) {
                    sess.complete = true
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "data $seq: ${e.message}")
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeChunk(
        sess: Session,
        idx: Int,
        e: Entry,
        chunk: Int,
        data: ByteArray,
        len: Int,
    ): Boolean {
        return try {
            val raf = sess.rafs.computeIfAbsent(idx) {
                val f = File(sess.tempRoot, e.rel + ".part")
                f.parentFile?.mkdirs()
                RandomAccessFile(f, "rw").also { r ->
                    // 预分配：避免每写 1 MiB 都触发一次 FUSE 的文件长度扩展
                    if (e.size > 0 && r.length() < e.size) {
                        runCatching { r.setLength(e.size) }
                    }
                }
            }
            raf.seek(chunk.toLong() * Proto.CHUNK)
            raf.write(data, 0, len)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "write ${e.rel}@$chunk: ${ex.message}")
            sess.failed = true
            false
        }
    }

    private fun closeSession(s: Session) {
        closeData(s)
        try {
            s.ctrl?.close()
        } catch (_: Exception) {
        }
    }

    /** 只关数据句柄，保留控制连接用于回 done_ok */
    private fun closeData(s: Session) {
        for (r in s.rafs.values) {
            try {
                r.close()
            } catch (_: Exception) {
            }
        }
        s.rafs.clear()
    }

    private fun saveMeta(root: File, entries: List<Entry>) {
        try {
            val o = JSONObject()
            val arr = JSONArray()
            for (e in entries) {
                // 数据线程正在 mark()，必须拿锁拷一份快照，否则会读到写了一半的字
                val snapshot = synchronized(e.lock) { e.bits.copyOf() }
                val bits = JSONArray()
                for (w in snapshot) bits.put(w)
                arr.put(JSONObject().put("rel", e.rel).put("bits", bits))
            }
            o.put("files", arr)
            val target = File(root, "meta.json")
            val tmp = File(root, "meta.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    private fun chunkLen(e: Entry, ci: Int): Int {
        val left = e.size - ci.toLong() * Proto.CHUNK
        return if (left > Proto.CHUNK) Proto.CHUNK else left.toInt().coerceAtLeast(0)
    }

    private fun isSafeRel(rel: String): Boolean {
        if (rel.isEmpty() || rel.length > 1024) return false
        if (rel.startsWith("\\") || rel.startsWith("/")) return false
        if (rel.contains(':')) return false
        return rel.split('\\', '/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun sanitize(seg: String): String {
        val sb = StringBuilder()
        for (ch in seg) {
            sb.append(if (ch.code < 32 || ch in "<>:\"/\\|?*") '_' else ch)
        }
        var s = sb.toString().trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        val reserved = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
        if (reserved.contains(s.uppercase())) s += "_"
        if (s.length > 200) s = s.substring(0, 200)
        return s
    }

    private fun jobKey(peerId: String, entries: List<Entry>): String {
        val keys = entries.map { e ->
            val material = "$peerId\u0001${e.rel}\u0001${e.size}\u0001${e.mtime}\u0001${Proto.CHUNK}"
            fnv1a64(material.toByteArray(Charsets.UTF_8))
        }.sorted()
        var h = -0x340d631b7bdddcdbL
        for (k in keys) {
            var v = k
            repeat(8) {
                h = h xor (v and 0xFF)
                h *= 0x100000001b3L
                v = v ushr 8
            }
        }
        return java.lang.String.format("%016x", h)
    }

    private fun fnv1a64(b: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (x in b) {
            h = h xor (x.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    companion object {
        const val TAG = "LanDropRecv"
    }
}
