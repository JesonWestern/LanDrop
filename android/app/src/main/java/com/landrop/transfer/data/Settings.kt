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
        get() = sp.getInt("dataConns", 2).coerceIn(1, 8)
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
