package com.landrop.transfer.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 接收落盘：先写 app 私有目录（可随机写、可续传），全部完成后再导入
 * MediaStore.Downloads（Download/LanDrop/...）。
 */
object DownloadSink {

    fun incomingRoot(context: Context, jobKey: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "incoming/$jobKey")
        dir.mkdirs()
        return dir
    }

    private fun incomingBase(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "incoming")

    /**
     * 清理放弃/失败后遗留的临时目录：超过 7 天，或总量超过 2 GB 时按最旧优先删。
     * 成功接收的目录在提交时会被删掉，因此这里只会碰到残留。
     */
    fun gcIncoming(context: Context, maxAgeMs: Long = 7L * 24 * 3600 * 1000, maxBytes: Long = 2L * 1024 * 1024 * 1024) {
        runCatching {
            val base = incomingBase(context)
            val dirs = base.listFiles()?.filter { it.isDirectory } ?: return
            val now = System.currentTimeMillis()
            var total = dirs.sumOf { dirSize(it) }
            for (d in dirs.sortedBy { it.lastModified() }) {
                if (dirSize(d) <= 0 || now - d.lastModified() > maxAgeMs || total > maxBytes) {
                    total -= dirSize(d)
                    d.deleteRecursively()
                }
            }
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * 把已完成的本地文件复制进系统下载目录。
     * @param relPath 相对路径（'/' 分隔），用于保留目录结构
     * @return 展示用路径
     */
    fun publish(context: Context, src: File, relPath: String): String {
        val rel = relPath.replace('\\', '/')
        val idx = rel.lastIndexOf('/')
        val sub = if (idx > 0) rel.substring(0, idx) else ""
        val name = if (idx >= 0) rel.substring(idx + 1) else rel
        val relativePath = "Download/LanDrop" + if (sub.isNotEmpty()) "/$sub" else ""

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri: Uri = resolver.insert(collection, values)
            ?: return fallbackCopy(src, relativePath, name)

        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val shownDir = "Download/LanDrop" + if (sub.isNotEmpty()) "/$sub" else ""
            return "$shownDir/$name"
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return fallbackCopy(src, relativePath, name)
        }
    }

    private fun fallbackCopy(src: File, relativePath: String, name: String): String {
        return try {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(base, relativePath.removePrefix("Download/"))
            dir.mkdirs()
            var target = File(dir, name)
            var i = 1
            val baseName = target.nameWithoutExtension
            val ext = target.extension
            while (target.exists()) {
                target = File(dir, if (ext.isEmpty()) "$baseName ($i)" else "$baseName ($i).$ext")
                i++
            }
            src.copyTo(target, overwrite = false, bufferSize = 256 * 1024)
            target.absolutePath
        } catch (e: Exception) {
            src.absolutePath
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "txt", "md", "log" -> "text/plain"
        else -> "application/octet-stream"
    }
}
