package com.landrop.transfer.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.landrop.transfer.model.DeviceInfo
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * UDP 广播发现。
 *
 * 关键点（来自前期实测）：
 *  - 每个网卡一个发送 socket 并绑定该网卡地址，避免默认选路把包发错接口
 *  - 接收 socket 必须绑 0.0.0.0 才能收到广播
 *  - 必须过滤本机地址与自身 id（同一包会以 IPv4/IPv6 多份到达）
 */
class Discovery(
    private val context: Context,
    private val selfId: String,
    private val selfName: String,
    private val selfPort: Int,
    private val onDevice: (DeviceInfo) -> Unit,
) {
    @Volatile private var running = false
    private var recv: DatagramSocket? = null
    private val senders = mutableListOf<Pair<DatagramSocket, Int>>() // socket -> broadcast addr(int)
    private var thread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastProbe = 0L

    private val selfAddresses: Set<String> by lazy { localAddresses() }

    private fun localAddresses(): Set<String> {
        val out = mutableSetOf("127.0.0.1")
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) out.add(a.hostAddress ?: "")
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun ipToInt(a: InetAddress): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun packet(type: String): ByteArray {
        val o = JSONObject()
        o.put("t", type)
        o.put("v", Proto.PROTO_VERSION)
        o.put("id", selfId)
        o.put("name", selfName)
        o.put("plat", "android")
        o.put("port", selfPort)
        o.put("ver", "1.0.0")
        o.put("caps", org.json.JSONArray().put("file").put("folder").put("resume").put("speed"))
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun start() {
        if (running) return
        running = true
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("landrop").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock: ${e.message}")
        }

        val rs = DatagramSocket(null)
        rs.reuseAddress = true
        rs.broadcast = true
        rs.bind(InetSocketAddress(Proto.DISCOVER_PORT))
        rs.soTimeout = 400
        recv = rs

        // 每个网卡一个发送 socket
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    if (addr.isLinkLocalAddress) continue
                    val net = nif.interfaceAddresses.firstOrNull { it.address == addr } ?: continue
                    val bcast = net.broadcast ?: continue
                    val s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(addr, 0))
                    senders.add(s to ipToInt(bcast))
                    Log.i(TAG, "iface ${nif.name} ${addr.hostAddress} -> ${bcast.hostAddress}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "enum ifaces: ${e.message}")
        }
        if (senders.isEmpty()) {
            Log.w(TAG, "no iface, fallback to 255.255.255.255")
        }

        thread = Thread { loop() }.apply { isDaemon = true; name = "landrop-disc"; start() }
    }

    private fun loop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastProbe >= Proto.PROBE_INTERVAL_MS) {
                    lastProbe = now
                    val p = packet("probe")
                    if (senders.isEmpty()) {
                        recv?.let {
                            it.send(DatagramPacket(p, p.size, InetAddress.getByName("255.255.255.255"),
                                Proto.DISCOVER_PORT))
                        }
                    } else {
                        for ((s, bcast) in senders) {
                            try {
                                s.send(DatagramPacket(p, p.size, InetAddress.getByName(intToIp(bcast)),
                                    Proto.DISCOVER_PORT))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                val rs = recv ?: break
                val dp = DatagramPacket(buf, buf.size)
                rs.receive(dp)
                val text = String(dp.data, 0, dp.length, Charsets.UTF_8)
                val srcIp = dp.address.hostAddress ?: continue
                if (srcIp in selfAddresses) continue
                val o = JSONObject(text)
                val t = o.optString("t")
                val id = o.optString("id")
                if (id.isEmpty() || id == selfId) continue
                val dev = DeviceInfo(
                    id = id,
                    name = o.optString("name", "未知设备"),
                    plat = o.optString("plat", "win"),
                    ver = o.optString("ver", ""),
                    ip = srcIp,
                    port = o.optInt("port", Proto.TRANSFER_PORT),
                    lastSeen = System.currentTimeMillis(),
                )
                onDevice(dev)
                if (t == "probe") {
                    val p = packet("announce")
                    try {
                        val s = senders.firstOrNull()?.first ?: recv
                        s?.send(DatagramPacket(p, p.size, dp.address, Proto.DISCOVER_PORT))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "loop: ${e.message}")
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    fun forceProbe() {
        lastProbe = 0
    }

    fun stop() {
        running = false
        try {
            recv?.close()
        } catch (_: Exception) {
        }
        recv = null
        for ((s, _) in senders) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        senders.clear()
        thread?.interrupt()
        thread = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    companion object {
        const val TAG = "LanDropDisc"
    }
}
