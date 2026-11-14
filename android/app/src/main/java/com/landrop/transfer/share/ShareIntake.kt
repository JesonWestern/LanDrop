package com.landrop.transfer.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 系统「分享 / 打开方式」进来的待发送文件队列。
 *
 * 只在这里读元数据，**不打开 fd** —— `FLAG_GRANT_READ_URI_PERMISSION` 的授权
 * 只在当前 task 存活期内有效，长时间持有 ParcelFileDescriptor 容易被系统回收。
 * 真正的打开动作在用户点「发送」之后由 [ShareMaterializer] 完成。
 */
object ShareIntake {

    private const val TAG = "LanDropShare"

    data class Item(
        val uri: Uri,
        val name: String,
        /** 小于 0 表示来源未告知大小 */
        val size: Long,
        /** 0 表示未知；必须稳定，否则 jobKey 每次都变、续传永远命中不了 */
        val mtime: Long,
        val mime: String,
    )

    data class Pending(val items: List<Item>, val note: String = "")

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastSignature = ""

    fun offer(intent: Intent?, ctx: Context) {
        if (intent == null) return
        val uris = extract(intent)
        if (uris.isEmpty()) {
            if (intent.action == Intent.ACTION_SEND &&
                !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
            ) {
                _pending.value = Pending(emptyList(), "暂不支持直接分享纯文本，请分享文件")
            }
            return
        }
        val sig = (intent.action ?: "") + "|" + uris.joinToString("|") { it.toString() }
        if (sig == lastSignature) return
        lastSignature = sig

        val app = ctx.applicationContext
        scope.launch {
            val items = uris.mapNotNull { readMeta(app, it) }
            _pending.value = if (items.isEmpty()) {
                Pending(emptyList(), "来源应用没有授予读取权限，无法获取文件")
            } else {
                Pending(items)
            }
            Log.i(TAG, "offer ${items.size} item(s) from ${intent.action}")
        }
    }

    fun clear() {
        _pending.value = null
        lastSignature = ""
    }

    /** EXTRA_STREAM 可能是 Uri / String / ArrayList，SEND_MULTIPLE 还可能只放在 ClipData 里。 */
    private fun extract(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()

        fun addAny(v: Any?) {
            when (v) {
                null -> Unit
                is Uri -> out.add(v)
                is String -> if (v.isNotBlank() && v.contains("://")) {
                    runCatching { out.add(Uri.parse(v)) }
                }
                is Iterable<*> -> v.forEach { addAny(it) }
                is Array<*> -> v.forEach { addAny(it) }
                is ClipData -> for (i in 0 until v.itemCount) {
                    v.getItemAt(i)?.uri?.let { out.add(it) }
                }
                else -> Unit
            }
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    addAny(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                } else {
                    @Suppress("DEPRECATION")
                    addAny(intent.getParcelableExtra(Intent.EXTRA_STREAM))
                }
                addAny(intent.clipData)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    addAny(intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java))
                } else {
                    @Suppress("DEPRECATION")
                    addAny(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
                }
                addAny(intent.clipData)
            }

            Intent.ACTION_VIEW -> addAny(intent.data)
            else -> return emptyList()
        }
        return out.toList()
    }

    private fun readMeta(ctx: Context, uri: Uri): Item? {
        var name: String? = null
        var size = -1L
        var mtime = 0L
        var mime = ""

        val scheme = uri.scheme
        if (scheme == null || scheme == "file") {
            val p = uri.path ?: return null
            val f = File(p)
            if (!f.exists() || f.isDirectory) return null
            name = f.name
            size = f.length()
            mtime = f.lastModified()
        } else {
            runCatching {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (iName >= 0 && !c.isNull(iName)) name = c.getString(iName)
                        val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                        if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                    }
                }
            }
            mtime = runCatching {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
            }.getOrDefault(0L)
            mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        }

        val finalName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
        return Item(uri = uri, name = finalName, size = size, mtime = mtime, mime = mime)
    }
}
