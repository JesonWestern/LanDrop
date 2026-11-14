package com.landrop.transfer.storage

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.landrop.transfer.model.FileItem
import java.io.File

/**
 * 像系统文件管理器一样浏览 /storage/emulated/0。
 * 依赖 MANAGE_EXTERNAL_STORAGE（全部文件访问）。
 */
object FsBrowser {

    fun root(): File = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun listDir(dir: File): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        val out = ArrayList<FileItem>(children.size)
        for (f in children) {
            val name = f.name
            if (name.startsWith(".")) continue // 默认隐藏 dot 文件，保持列表清爽
            val isDir = f.isDirectory
            out.add(
                FileItem(
                    name = name,
                    path = f.absolutePath,
                    isDir = isDir,
                    size = if (isDir) 0 else f.length(),
                    mtime = f.lastModified(),
                    childInfo = if (isDir) childCountLabel(f) else "",
                )
            )
        }
        out.sortWith(compareByDescending<FileItem> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    private fun childCountLabel(dir: File): String {
        val n = dir.list()?.size ?: 0
        return "$n 项"
    }

    /** 最近修改的文件（走 MediaStore，需要全部文件权限才能覆盖非媒体文件）。 */
    fun recentFiles(context: Context, limit: Int = 60): List<FileItem> {
        val out = ArrayList<FileItem>()
        val proj = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                proj,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val path = c.getString(iData) ?: continue
                    val f = File(path)
                    if (f.name.startsWith(".")) continue
                    val lower = path.lowercase()
                    if (lower.contains("/android/data/") || lower.contains("/android/obb/")) continue
                    if (!f.exists() || f.isDirectory) continue
                    if (f.length() <= 0) continue
                    out.add(
                        FileItem(
                            name = c.getString(iName) ?: f.name,
                            path = path,
                            isDir = false,
                            size = c.getLong(iSize),
                            mtime = c.getLong(iMod) * 1000L,
                        )
                    )
                    n++
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun dirsOfInterest(): List<FileItem> {
        val r = root()
        val names = listOf("DCIM", "Download", "Pictures", "Documents", "Movies", "Music", "Android")
        val out = ArrayList<FileItem>()
        for (n in names) {
            val f = File(r, n)
            if (f.exists()) {
                out.add(FileItem(n, f.absolutePath, true, 0, f.lastModified(), childCountLabel(f)))
            }
        }
        return out
    }
}
