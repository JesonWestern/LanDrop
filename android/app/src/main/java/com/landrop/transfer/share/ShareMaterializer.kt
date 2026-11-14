package com.landrop.transfer.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.landrop.transfer.model.Entry
import com.landrop.transfer.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 把分享进来的 Uri 变成可发送的 [Entry]。
 *
 * 三级降级链（越靠前越省时省空间）：
 *  0. 能解析出真实路径（file:// 或 MediaStore 的 _data）→ 直接按路径读；
 *  1. 能拿到可定位的 ParcelFileDescriptor → 零拷贝随机读（正好匹配按 1 MiB 分块）；
 *  2. fd 不可定位（管道型 provider）或大小未知 → 复制到 cacheDir 再发；
 *  3. 都不行 → 跳过，并在弹窗里提示。
 */
object ShareMaterializer {

    private const val TAG = "LanDropShare"

    data class Result(val entries: List<Entry>, val skipped: List<String>)

    suspend fun materialize(
        ctx: Context,
        items: List<ShareIntake.Item>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val out = ArrayList<Entry>(items.size)
        val skipped = ArrayList<String>()
        val used = HashSet<String>()
        items.forEachIndexed { i, item ->
            onProgress(i, items.size)
            val e = runCatching { toEntry(ctx, item, used) }
                .onFailure { Log.w(TAG, "materialize ${item.name}: ${it.message}") }
                .getOrNull()
            if (e != null) out.add(e) else skipped.add(item.name)
        }
        onProgress(items.size, items.size)
        Result(out, skipped)
    }

    private fun toEntry(ctx: Context, item: ShareIntake.Item, used: MutableSet<String>): Entry? {
        val name = uniqueName(sanitize(item.name), used)
        val resolver = ctx.contentResolver

        // 0) 真实路径
        directPath(resolver, item.uri)?.let { p ->
            val f = File(p)
            if (f.exists() && !f.isDirectory) {
                return Entry(name, p, f.length(), f.lastModified(), false, Source.Path(f))
            }
        }

        // 1) 可定位 fd
        val knownSize = probeSize(resolver, item.uri)
        val size = if (knownSize > 0) knownSize else item.size
        val src = Source.UriSource(resolver, item.uri)
        if (size == 0L) {
            return Entry(name, "", 0, item.mtime, false, src)
        }
        if (size > 0 && isSeekable(src)) {
            return Entry(name, "", size, item.mtime, false, src)
        }

        // 2) 复制到缓存
        return copyToCache(ctx, item, name)
    }

    private fun directPath(resolver: ContentResolver, uri: Uri): String? {
        val scheme = uri.scheme
        if (scheme == "file") return uri.path
        if (scheme != "content") return null
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun probeSize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    /** 管道型 provider 的 fd 不可 lseek，只能用复制兜底。 */
    private fun isSeekable(src: Source.UriSource): Boolean = runCatching {
        src.open().use { it.readAt(0, ByteArray(1), 0, 1) }
        true
    }.getOrDefault(false)

    private fun copyToCache(ctx: Context, item: ShareIntake.Item, name: String): Entry? {
        val dir = File(ctx.cacheDir, "share")
        if (!dir.exists() && !dir.mkdirs()) return null

        if (item.size > 0) {
            val avail = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
            val need = item.size + item.size / 8 + 64L * 1024 * 1024
            if (avail in 1 until need) {
                Log.w(TAG, "cache 空间不足：need=$need avail=$avail")
                return null
            }
        }

        val target = File(dir, System.nanoTime().toString(36) + "_" + name)
        val ok = runCatching {
            ctx.contentResolver.openInputStream(item.uri)?.use { ins ->
                FileOutputStream(target).use { outs ->
                    // 256 KiB 缓冲，避免大文件复制时来回 syscall
                    ins.copyTo(outs, 256 * 1024)
                }
            } ?: return null
        }.isSuccess
        if (!ok) {
            target.delete()
            return null
        }
        val size = target.length()
        if (size <= 0L) {
            target.delete()
            return null
        }
        if (item.mtime > 0) target.setLastModified(item.mtime)
        Log.i(TAG, "copied ${item.name} -> ${target.name} ($size B)")
        return Entry(name, target.absolutePath, size, target.lastModified(), false, Source.Path(target))
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        return cleaned.ifEmpty { "file" }
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}
