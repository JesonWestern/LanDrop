package com.landrop.transfer.core

import android.content.Context
import android.util.Log
import com.landrop.transfer.data.HistoryRec
import com.landrop.transfer.data.HistoryStore
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.JobDir
import com.landrop.transfer.model.JobState
import com.landrop.transfer.model.JobUi
import com.landrop.transfer.model.SpeedUi
import com.landrop.transfer.net.Discovery
import com.landrop.transfer.net.Proto
import com.landrop.transfer.net.ReceiverServer
import com.landrop.transfer.net.Sender
import com.landrop.transfer.net.Speed
import com.landrop.transfer.storage.FileWalk
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

    private val seq = AtomicInteger(2)

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

        server = ReceiverServer(
            context = ctx,
            settings = settings,
            onNewJob = { job ->
                jobs.add(job)
                pushJobs()
            },
            onUpdate = { pushJobs() },
            autoAcceptAll = { autoAcceptAll },
        ).also { it.start() }

        serverListening = true
        running = true
        Log.i(TAG, "started")
    }

    fun stop() {
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        running = false
        serverListening = false
        devMap.clear()
        _devices.value = emptyList()
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
        val title = if (entries.size == 1) entries[0].rel.substringAfterLast('/')
        else entries[0].rel.substringBefore('/')
        val job = Job(
            id = seq.getAndIncrement(),
            dir = JobDir.SEND,
            device = dev,
            title = title,
            files = entries,
        )
        job.state = JobState.HANDSHAKE
        jobs.add(job)
        pushJobs()
        Sender(settings, job) { pushJobs() }.start()
        return true
    }

    fun accept(id: Int, yes: Boolean) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (yes) j.acceptFlag = true else j.cancel = true
    }

    fun cancel(id: Int) {
        jobs.firstOrNull { it.id == id }?.cancel = true
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
        _jobList.value = jobs.map { it.toUi() }.sortedWith(
            compareByDescending<JobUi> { it.state.active }.thenByDescending { it.startAt }
        )
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
                if (running) _devices.value = pruned()
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
