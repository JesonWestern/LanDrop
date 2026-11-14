package com.landrop.transfer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryRec(
    val title: String,
    val bytes: Long,
    val dir: Int,       // 0 recv 1 send
    val time: Long,
    val path: String,
    val ok: Boolean,
)

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    @Synchronized
    fun add(rec: HistoryRec) {
        val list = load().toMutableList()
        list.add(0, rec)
        while (list.size > 200) list.removeAt(list.size - 1)
        save(list)
    }

    @Synchronized
    fun load(): List<HistoryRec> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = ArrayList<HistoryRec>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HistoryRec(
                        title = o.optString("title"),
                        bytes = o.optLong("bytes"),
                        dir = o.optInt("dir"),
                        time = o.optLong("time"),
                        path = o.optString("path"),
                        ok = o.optBoolean("ok", true),
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun save(list: List<HistoryRec>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("title", r.title)
            o.put("bytes", r.bytes)
            o.put("dir", r.dir)
            o.put("time", r.time)
            o.put("path", r.path)
            o.put("ok", r.ok)
            arr.put(o)
        }
        try {
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
