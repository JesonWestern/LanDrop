package com.landrop.transfer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.landrop.transfer.BuildConfig
import com.landrop.transfer.core.Engine
import com.landrop.transfer.share.ShareIntake
import com.landrop.transfer.storage.Catalog

/**
 * Debug 变体专用的命令行钩子，供 tools/e2e_test.py 通过 adb 驱动。
 *
 * adb shell am broadcast -a com.landrop.DEBUG_CMD --es cmd send \
 *     --es peer 192.168.5.4 --es path /sdcard/DCIM/a.jpg
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        Engine.init(app)
        Engine.start(app)
        val cmd = intent.getStringExtra("cmd") ?: return
        when (cmd) {
            "send" -> {
                val path = intent.getStringExtra("path")
                if (path.isNullOrEmpty()) {
                    Log.w(TAG, "send without path")
                    return
                }
                val peer = intent.getStringExtra("peer")
                val ok = if (!peer.isNullOrEmpty()) {
                    Engine.sendTo(peer, listOf(path))
                } else {
                    Engine.send(listOf(path))
                }
                Log.i(TAG, "cmd=send path=$path peer=$peer -> $ok")
            }
            "speed" -> {
                val dir = intent.getIntExtra("dir", 2)
                Engine.startSpeed(dir)
                Log.i(TAG, "cmd=speed dir=$dir")
            }
            "auto" -> {
                Engine.autoAcceptAll = intent.getBooleanExtra("on", true)
                Log.i(TAG, "cmd=auto ${Engine.autoAcceptAll}")
            }
            "state" -> {
                val devs = Engine.devices.value.joinToString(",") { "${it.name}@${it.ip}" }
                val jobs = Engine.jobList.value.joinToString(";") { "${it.id}:${it.state}:${it.progress}" }
                Log.i(TAG, "STATE devices=[$devs] jobs=[$jobs]")
            }
            "catalog" -> {
                val st = Catalog.state.value
                val buckets = st.buckets.joinToString(",") { "${it.kind}=${it.count}/${it.bytes}" }
                Log.i(
                    TAG,
                    "CATALOG source=${st.source} total=${st.items.size} raw=${st.rawRows} " +
                        "dropped=${st.dropped} truncated=${st.truncated} [$buckets]"
                )
            }
            // 模拟从别的应用分享进来，便于无设备/自动化验证
            "share" -> {
                val uri = intent.getStringExtra("uri")
                if (uri.isNullOrEmpty()) {
                    Log.w(TAG, "share without uri")
                    return
                }
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = intent.getStringExtra("mime") ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ShareIntake.offer(i, app)
                Log.i(TAG, "cmd=share uri=$uri")
            }
            else -> Log.i(TAG, "unknown cmd=$cmd")
        }
    }

    companion object {
        const val TAG = "LanDropDbg"
    }
}
