package com.landrop.transfer.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 传输期间持有 WifiLock。
 *
 * 已有 KEEP_SCREEN_ON 保屏幕，但省电策略仍可能在传输间隙把 WiFi 降到 PSM，
 * 表现为 RTT 抖动、吞吐忽高忽低。用引用计数避免多个任务互相顶掉。
 * 注意：WIFI_MODE_FULL_HIGH_PERF 在 API 29 起被标记弃用，但仍生效。
 */
object NetLocks {

    private const val TAG = "LanDropLock"

    private var lock: WifiManager.WifiLock? = null
    private var refs = 0

    @Synchronized
    fun acquire(ctx: Context) {
        refs++
        if (lock != null) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "landrop:xfer")
            wl.setReferenceCounted(false)
            wl.acquire()
            lock = wl
            Log.i(TAG, "wifi lock acquired")
        }.onFailure { Log.w(TAG, "wifi lock: ${it.message}") }
    }

    @Synchronized
    fun release() {
        if (refs > 0) refs--
        if (refs > 0) return
        runCatching { lock?.release() }
        lock = null
    }

    @Synchronized
    fun releaseAll() {
        refs = 0
        runCatching { lock?.release() }
        lock = null
    }
}
