package com.landrop.transfer.data

import android.content.Context
import android.os.Build
import java.util.UUID

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("landrop", Context.MODE_PRIVATE)

    var deviceId: String
        get() {
            val v = sp.getString("deviceId", null)
            if (v != null) return v
            val nv = UUID.randomUUID().toString()
            sp.edit().putString("deviceId", nv).apply()
            return nv
        }
        set(v) = sp.edit().putString("deviceId", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: Build.MODEL
        set(v) = sp.edit().putString("deviceName", v).apply()

    var dataConns: Int
        get() = sp.getInt("dataConns", 3).coerceIn(1, 8)
        set(v) = sp.edit().putInt("dataConns", v.coerceIn(1, 8)).apply()

    var speedConns: Int
        get() = sp.getInt("speedConns", 4).coerceIn(1, 8)
        set(v) = sp.edit().putInt("speedConns", v.coerceIn(1, 8)).apply()

    var speedSecs: Int
        get() = sp.getInt("speedSecs", 10).coerceIn(3, 60)
        set(v) = sp.edit().putInt("speedSecs", v.coerceIn(3, 60)).apply()

    var autoAcceptTrusted: Boolean
        get() = sp.getBoolean("autoAcceptTrusted", true)
        set(v) = sp.edit().putBoolean("autoAcceptTrusted", v).apply()

    /** 分类页数据源：false=媒体库索引（秒开），true=全盘深度扫描。默认关闭，升级用户行为不变。 */
    var deepScan: Boolean
        get() = sp.getBoolean("deepScan", false)
        set(v) = sp.edit().putBoolean("deepScan", v).apply()

    /** 首次使用提示是否已经展示过（选中区/拖选）。 */
    var selectionHintSeen: Boolean
        get() = sp.getBoolean("selectionHintSeen", false)
        set(v) = sp.edit().putBoolean("selectionHintSeen", v).apply()

    /**
     * 文件页视图：`"list"` / `"compact"` / `"grid"`（默认列表）。
     * 存稳定小写 id；读取端必须用 `viewModeOf(id)` 回落，脏值不崩。
     */
    var fileViewMode: String
        get() = sp.getString("fileViewMode", "list") ?: "list"
        set(v) = sp.edit().putString("fileViewMode", v).apply()

    /**
     * 文件页页签：`"recent"` / `"storage"`（默认最近）。
     * 上次停在哪一页就回到哪一页，切换回来不用重逢重新点。
     */
    var fileTab: String
        get() = sp.getString("fileTab", "recent") ?: "recent"
        set(v) = sp.edit().putString("fileTab", v).apply()

    private fun trustedSet(): MutableSet<String> =
        (sp.getStringSet("trusted", emptySet()) ?: emptySet()).toMutableSet()

    fun isTrusted(id: String): Boolean = trustedSet().contains(id)

    fun trust(id: String) {
        val s = trustedSet()
        if (s.size >= 64) s.clear()
        s.add(id)
        sp.edit().putStringSet("trusted", s).apply()
    }

    fun forget(id: String) {
        val s = trustedSet()
        s.remove(id)
        sp.edit().putStringSet("trusted", s).apply()
    }

    fun trustedCount(): Int = trustedSet().size

    fun clearTrusted() {
        sp.edit().putStringSet("trusted", emptySet()).apply()
    }
}
