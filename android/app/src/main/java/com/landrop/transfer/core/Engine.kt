package com.landrop.transfer.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.landrop.transfer.data.HistoryRec
import com.landrop.transfer.data.HistoryStore
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi
import com.landrop.transfer.model.SpeedUi
import com.landrop.transfer.net.Discovery
import com.landrop.transfer.net.NetLocks
import com.landrop.transfer.net.Proto
import com.landrop.transfer.net.ReceiverServer
import com.landrop.transfer.net.Sender
import com.landrop.transfer.net.Speed
import com.landrop.transfer.storage.DownloadSink
import com.landrop.transfer.storage.FileWalk
import com.landrop.transfer.util.JobIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object Engine {
    private const val TAG = "LanDropEngine"

    /** 跨设备并发上限：再多只会把上行带宽切碎，让每台都变慢。 */
    private const val MAX_PARALLEL_SENDS = 2

    private lateinit var ctx: Context
    lateinit var settings: Settings
        private set
    lateinit var history: HistoryStore
        private set

    private var discovery: Discovery? = null
    private var server: ReceiverServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val devMap = ConcurrentHashMap<String, DeviceInfo>()
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val jobs = CopyOnWriteArrayList<Job>()
    private val _jobList = MutableStateFlow<List<JobUi>>(emptyList())
    val jobList: StateFlow<List<JobUi>> = _jobList.asStateFlow()

    private val _speed = MutableStateFlow(SpeedUi())
    val speed: StateFlow<SpeedUi> = _speed.asStateFlow()

    private val sendLock = Any()

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var firstNetEvent = true
    @Volatile private var netLocked = false

    /** 等待开工的发送任务（单设备串行、跨设备并发上限见 [MAX_PARALLEL_SENDS]）。 */
    private val sendQueue = ArrayDeque<Job>()

    @Volatile var selectedId: String? = null
        private set
    @Volatile var running = false
        private set
    @Volatile var serverListening = false
        private set
    @Volatile var lastError: String = ""
    /** 仅调试/自动化用：无条件自动接收 */
    @Volatile var autoAcceptAll: Boolean = false

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        settings = Settings(ctx)
        history = HistoryStore(ctx)
        scope.launch { ticker() }
        Log.i(TAG, "init id=${settings.deviceId}")
    }

    fun start(context: Context? = null) {
        if (running) return
        if (!::ctx.isInitialized) {
            val c = context ?: return
            init(c)
        }
        discovery = Discovery(
            context = ctx,
            selfId = settings.deviceId,
            selfName = settings.deviceName,
            selfPort = Proto.TRANSFER_PORT,
        ) { dev ->
            devMap[dev.id] = dev.copy(trusted = settings.isTrusted(dev.id))
            if (selectedId == null) selectedId = dev.id
        }.also { it.start() }

        val srv = ReceiverServer(
            context = ctx,
            settings = settings,
            onNewJob = { job ->
                jobs.add(job)
                pushJobs()
            },
            onUpdate = { pushJobs() },
            autoAcceptAll = { autoAcceptAll },
        )
        server = srv

        // 端口被占用时不能再假装已监听（否则设置页永远显示绿色）
        serverListening = srv.start()
        if (!serverListening) Log.w(TAG, "接收端口 ${Proto.TRANSFER_PORT} 监听失败")

        running = true
        Log.i(TAG, "started listening=$serverListening")

        // WiFi 换网/切回后必须重建广播 socket，否则会出现「别人能发现我，我发现不了别人」
        registerNetworkWatch()

        // 清理上次遗留的接收临时目录（失败/放弃的任务不会自动清）
        scope.launch(Dispatchers.IO) { runCatching { DownloadSink.gcIncoming(ctx) } }
    }

    private fun registerNetworkWatch() {
        if (netCallback != null) return
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (firstNetEvent) {
                        firstNetEvent = false
                        return
                    }
                    Log.i(TAG, "network available -> rebuild discovery")
                    discovery?.rebuild()
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "network lost -> rebuild discovery")
                    discovery?.rebuild()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }.onFailure { Log.w(TAG, "network callback: ${it.message}") }
    }

    private fun unregisterNetworkWatch() {
        val cb = netCallback ?: return
        netCallback = null
        firstNetEvent = true
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        }
    }

    fun stop() {
        unregisterNetworkWatch()
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        running = false
        serverListening = false
        devMap.clear()
        _devices.value = emptyList()
        NetLocks.releaseAll()
    }

    fun select(id: String) {
        selectedId = id
    }

    fun selectedDevice(): DeviceInfo? {
        val id = selectedId ?: return _devices.value.firstOrNull()
        return _devices.value.firstOrNull { it.id == id } ?: _devices.value.firstOrNull()
    }

    fun refreshProbe() {
        discovery?.forceProbe()
    }

    fun send(paths: List<String>): Boolean {
        val dev = selectedDevice() ?: return false
        return sendInternal(dev, paths)
    }

    /** 按 IP 直连发送（供 CLI / 调试钩子使用：发现失败时也能测通链路）。 */
    fun sendTo(ip: String, paths: List<String>): Boolean {
        val dev = _devices.value.firstOrNull { it.ip == ip }
            ?: DeviceInfo(
                id = "direct-$ip",
                name = ip,
                plat = "win",
                ver = "",
                ip = ip,
                port = Proto.TRANSFER_PORT,
                lastSeen = System.currentTimeMillis(),
            )
        return sendInternal(dev, paths)
    }

    private fun sendInternal(dev: DeviceInfo, paths: List<String>): Boolean {
        val entries = FileWalk.collect(paths)
        if (entries.isEmpty()) return false
        return sendEntries(dev, entries, null)
    }

    /**
     * 把一份清单发给一台设备。任务先进队列，由 [pumpSends] 按并发上限启动。
     * @param title 为空时按清单自动取（单文件用文件名，多文件用首层目录名）
     */
    fun sendEntries(dev: DeviceInfo, entries: List<Entry>, title: String? = null): Boolean {
        if (entries.isEmpty()) return false
        val job = Job(
            id = JobIds.next(),
            dir = JobDir.SEND,
            device = dev,
            title = title ?: defaultTitle(entries),
            files = entries,
        )
        job.state = JobState.QUEUED
        jobs.add(job)
        pushJobs()
        synchronized(sendLock) { sendQueue.addLast(job) }
        pumpSends()
        return true
    }

    /**
     * 同一份内容发给多台设备（系统分享后的多选）。每台设备一个独立任务，
     * 返回真正入队的设备数。
     */
    fun sendToDevices(devs: List<DeviceInfo>, entries: List<Entry>, title: String? = null): Int {
        if (entries.isEmpty() || devs.isEmpty()) return 0
        var n = 0
        for (d in devs) {
            // 每台设备一份独立清单：Entry 携带分块位图与已发字节，共享会互相污染
            if (sendEntries(d, cloneEntries(entries), title)) n++
        }
        return n
    }

    /** 失败/暂停的发送任务重新入队。接收端会回传位图，天然续传。 */
    fun retry(id: Int): Boolean {
        val j = jobs.firstOrNull { it.id == id } ?: return false
        if (j.dir != JobDir.SEND || j.state.active) return false
        j.cancel = false
        j.errMsg = ""
        j.bps = 0.0
        j.state = JobState.QUEUED
        synchronized(sendLock) { sendQueue.addLast(j) }
        pushJobs()
        pumpSends()
        return true
    }

    private fun cloneEntries(list: List<Entry>): List<Entry> =
        list.map { Entry(it.rel, it.absPath, it.size, it.mtime, it.isDir, it.source) }

    private fun defaultTitle(entries: List<Entry>): String =
        if (entries.size == 1) entries[0].rel.substringAfterLast('/')
        else entries[0].rel.substringBefore('/')

    private fun runningSends(): Int = jobs.count {
        it.dir == JobDir.SEND && (
            it.state == JobState.HANDSHAKE ||
                it.state == JobState.TRANSFERRING ||
                it.state == JobState.VERIFYING
            )
    }

    private fun pumpSends() {
        synchronized(sendLock) {
            while (runningSends() < MAX_PARALLEL_SENDS && sendQueue.isNotEmpty()) {
                val j = sendQueue.removeFirst()
                if (j.cancel) continue
                j.state = JobState.HANDSHAKE
                pushJobs()
                Sender(settings, j) { pushJobs() }.start()
            }
        }
    }

    fun accept(id: Int, yes: Boolean) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (yes) j.acceptFlag = true else j.cancel = true
    }

    fun cancel(id: Int) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        j.cancel = true
        if (j.state == JobState.QUEUED) {
            synchronized(sendLock) { sendQueue.remove(j) }
            j.state = JobState.CANCELED
            j.endedAt = System.currentTimeMillis()
            pushJobs()
        }
    }

    fun removeFinished() {
        jobs.removeAll { !it.state.active }
        pushJobs()
    }

    fun clearAllFinished() {
        removeFinished()
    }

    fun startSpeed(dir: Int) {
        if (_speed.value.running) return
        val dev = selectedDevice() ?: run {
            _speed.value = _speed.value.copy(err = "还没有发现设备")
            return
        }
        _speed.value = SpeedUi(running = true, dir = dir)
        Speed.runClient(
            settings = settings,
            device = dev,
            dirMode = dir,
            conns = settings.speedConns,
            secs = settings.speedSecs,
            onSample = { mbps ->
                val cur = _speed.value
                val s = (cur.samples + mbps).takeLast(120)
                _speed.value = cur.copy(currentMbps = mbps, samples = s)
            },
        ) { r ->
            val cur = _speed.value
            _speed.value = cur.copy(
                running = false,
                currentMbps = r.avgMbps,
                avgMbps = r.avgMbps,
                peakMbps = r.peakMbps,
                p50Mbps = r.p50Mbps,
                err = r.err,
            )
        }
    }

    fun stopSpeed() {
        // 客户端按 secs 自然结束；这里只把 UI 状态标记为结束
        _speed.value = _speed.value.copy(running = false)
    }

    fun setSpeedDir(dir: Int) {
        _speed.value = _speed.value.copy(dir = dir)
    }

    fun setDeviceName(name: String) {
        settings.deviceName = name
        restart()
    }

    fun restart() {
        stop()
        start()
    }

    private fun pushJobs() {
        val next = jobs.map { it.toUi() }.sortedWith(
            compareByDescending<JobUi> { it.state.active }.thenByDescending { it.startAt }
        )
        // 没有变化就不推：否则每 400 ms 都会让所有页面重组一次
        if (next != _jobList.value) _jobList.value = next
    }

    /** lastSeen 每次都变，比较时必须排除，否则永远判为「变了」。 */
    private fun sameDevices(a: List<DeviceInfo>, b: List<DeviceInfo>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.id != y.id || x.name != y.name || x.plat != y.plat ||
                x.ip != y.ip || x.port != y.port || x.trusted != y.trusted
            ) return false
        }
        return true
    }

    private fun pruned(): List<DeviceInfo> {
        val now = System.currentTimeMillis()
        val it = devMap.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > Proto.DEVICE_TTL_MS) it.remove()
        }
        val list = devMap.values.map { d -> d.copy(trusted = settings.isTrusted(d.id)) }
        return list.sortedBy { it.name }
    }

    private suspend fun ticker() {
        while (true) {
            delay(400)
            try {
                if (running) {
                    val next = pruned()
                    if (!sameDevices(next, _devices.value)) _devices.value = next
                }
                pumpSends()
                var changed = false
                for (j in jobs) {
                    if (j.state.active) {
                        j.noteTick()
                        changed = true
                    }
                    if (!j.state.active && !j.historyLoggedDone) {
                        j.historyLoggedDone = true
                        history.add(
                            HistoryRec(
                                title = j.title,
                                bytes = j.totalBytes,
                                dir = if (j.dir == JobDir.RECV) 0 else 1,
                                time = System.currentTimeMillis(),
                                path = j.savePath,
                                ok = j.state == JobState.DONE,
                            )
                        )
                    }
                }
                if (changed || jobs.isNotEmpty()) pushJobs()

                // 传输期间持有 WifiLock：省电策略把 WiFi 降到 PSM 会让 RTT 抖动、掉速
                val activeNow = jobs.any { it.state.active }
                if (activeNow != netLocked) {
                    netLocked = activeNow
                    if (activeNow) NetLocks.acquire(ctx) else NetLocks.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick: ${e.message}")
            }
        }
    }

    fun localIpHint(): String {
        val d = _devices.value.firstOrNull()
        return if (serverListening) "端口 ${Proto.TRANSFER_PORT} 已监听" else "未监听"
    }

    fun deviceCount(): Int = _devices.value.size
}
