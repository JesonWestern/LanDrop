package com.landrop.transfer.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 接收落盘：先写 app 私有目录（可随机写、可续传），全部完成后再导入下载目录。
 *
 * 1.2 起 [publish] 返回结构化结果 [Published]：
 *  - 落盘后 MediaProvider 可能因同名自动改名，必须回查真实 DISPLAY_NAME / DATA；
 *  - 任何一步失败都返回 ok=false（旧实现失败时返回展示路径，调用方仍判 DONE，
 *    随后删除临时目录 → 静默丢文件）；
 *  - API 29+ 走 MediaStore.Downloads，API 26-28 走文件 API + MediaScanner。
 */
object DownloadSink {

    data class Published(
        val rel: String,
        val displayPath: String,
        val absPath: String,
        val contentUri: String,
        val displayName: String,
        val ok: Boolean,
        val err: String = "",
    )

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
     * 1 小时内刚创建/改动的目录视为「可能正在进行」，绝不删除。
     */
    fun gcIncoming(
        context: Context,
        maxAgeMs: Long = 7L * 24 * 3600 * 1000,
        maxBytes: Long = 2L * 1024 * 1024 * 1024,
    ) {
        runCatching {
            val base = incomingBase(context)
            val dirs = base.listFiles()?.filter { it.isDirectory } ?: return
            val now = System.currentTimeMillis()
            val sizes = LinkedHashMap<File, Long>(dirs.size)
            for (d in dirs) sizes[d] = runCatching { dirSize(d) }.getOrDefault(0L)
            var total = sizes.values.sum()
            for (d in dirs.sortedBy { it.lastModified() }) {
                if (now - d.lastModified() < 3600_000L) continue // 可能正在进行，跳过
                val size = sizes[d] ?: 0L
                if (now - d.lastModified() > maxAgeMs || total > maxBytes) {
                    total -= size
                    runCatching { d.deleteRecursively() }
                }
            }
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * 把已完成的本地文件复制进系统下载目录。
     * @param relPath 相对路径（'/' 分隔），用于保留目录结构
     */
    fun publish(context: Context, src: File, relPath: String): Published {
        val (rel, sub, name) = PublishPaths.split(relPath)
        if (name.isEmpty()) return failed(rel, sub, "未知文件", "文件名为空")
        if (!src.exists() || src.isDirectory) return failed(rel, sub, name, "源文件不存在")
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishQ(context, src, rel, sub, name)
            } else {
                publishLegacy(context, src, rel, sub, name)
            }
        }.getOrElse { e -> failed(rel, sub, name, e.message ?: "保存失败") }
    }

    // ---- Android 10+：MediaStore.Downloads -----------------------------------

    private fun publishQ(context: Context, src: File, rel: String, sub: String, name: String): Published {
        val relativePath = PublishPaths.displayDir(sub)
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, FileOpener.mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // 保留源修改时间（部分 provider 会忽略，尽力而为）；否则“最近文件”按接收时间排序
            val mtimeSec = src.lastModified() / 1000L
            if (mtimeSec > 0) {
                put(MediaStore.MediaColumns.DATE_MODIFIED, mtimeSec)
                put(MediaStore.MediaColumns.DATE_ADDED, mtimeSec)
            }
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: return failed(rel, sub, name, "无法创建下载记录")
        try {
            val out = resolver.openOutputStream(uri)
                ?: run {
                    runCatching { resolver.delete(uri, null, null) }
                    return failed(rel, sub, name, "无法写入下载目录")
                }
            val copied = out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
            if (src.length() > 0 && copied != src.length()) {
                runCatching { resolver.delete(uri, null, null) }
                return failed(rel, sub, name, "写入不完整")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            val updated = resolver.update(uri, values, null, null)
            if (updated <= 0) {
                // provider 拒绝提交：文件仍处于 pending，对用户不可见，不能判成功
                runCatching { resolver.delete(uri, null, null) }
                return failed(rel, sub, name, "提交下载记录失败")
            }

            // 同名文件会被 MediaProvider 自动改名，必须回查真实名字与真实路径
            var finalName = name
            var dataPath = ""
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        finalName = c.getString(0) ?: name
                        dataPath = if (c.isNull(1)) "" else c.getString(1) ?: ""
                    }
                }
            }
            val abs = dataPath.ifEmpty {
                File(Environment.getExternalStorageDirectory(), "$relativePath/$finalName").absolutePath
            }
            return Published(
                rel = rel,
                displayPath = "$relativePath/$finalName",
                absPath = abs,
                contentUri = uri.toString(),
                displayName = finalName,
                ok = true,
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return failed(rel, sub, name, e.message ?: "写入下载目录失败")
        }
    }

    // ---- Android 8/9：直接写公共下载目录 --------------------------------------

    private fun publishLegacy(context: Context, src: File, rel: String, sub: String, name: String): Published {
        if (!FsBrowser.canWritePublic(context)) {
            return failed(rel, sub, name, "缺少存储写入权限")
        }
        val relativePath = PublishPaths.displayDir(sub)
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        if (!dir.exists() && !dir.mkdirs()) return failed(rel, sub, name, "无法创建下载目录")
        val target = uniqueTarget(dir, name)
        val copied = try {
            src.inputStream().use { ins ->
                target.outputStream().use { outs -> ins.copyTo(outs, 256 * 1024) }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            return failed(rel, sub, name, e.message ?: "写入下载目录失败")
        }
        if (src.length() > 0 && copied != src.length()) {
            runCatching { target.delete() }
            return failed(rel, sub, name, "写入不完整")
        }
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        }
        return Published(
            rel = rel,
            displayPath = "$relativePath/${target.name}",
            absPath = target.absolutePath,
            contentUri = "",
            displayName = target.name,
            ok = true,
        )
    }

    private fun failed(rel: String, sub: String, name: String, err: String): Published =
        Published(
            rel = rel,
            displayPath = "${PublishPaths.displayDir(sub)}/$name",
            absPath = "",
            contentUri = "",
            displayName = name,
            ok = false,
            err = err,
        )

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        var i = 1
        while (target.exists()) {
            target = File(dir, PublishPaths.numberedName(name, i))
            i++
        }
        return target
    }
}
