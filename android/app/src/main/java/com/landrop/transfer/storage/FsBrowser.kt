package com.landrop.transfer.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.landrop.transfer.model.FileItem
import java.io.File

/**
 * 像系统文件管理器一样浏览 /storage/emulated/0。
 *
 * 权限：
 *  - Android 11+（API 30）：MANAGE_EXTERNAL_STORAGE + `Environment.isExternalStorageManager()`；
 *  - Android 8-10（API 26-29）：READ/WRITE 运行时权限（本方法只判读，写由 [canWritePublic] 判）。
 */
object FsBrowser {

    fun root(): File = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false)

    /** 接收落盘到公共下载目录所需能力（API 29+ 走 MediaStore，不需要写权限）。 */
    fun canWritePublic(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun listDir(dir: File): List<FileItem> {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val out = ArrayList<FileItem>(children.size)
        for (f in children) {
            val name = f.name
            if (name.startsWith(".")) continue // 默认隐藏 dot 目录/文件，保持列表清爽
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
        val n = runCatching { dir.list()?.size }.getOrNull() ?: 0
        return "$n 项"
    }

    /** 最近修改的文件（走 MediaStore，需要全部文件权限才能覆盖非媒体文件）。 */
    fun recentFiles(context: Context, limit: Int = 60): List<FileItem> {
        val out = ArrayList<FileItem>()
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val sel = buildString {
            append("${MediaStore.Files.FileColumns.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append(" AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0")
            }
        }
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                proj,
                sel,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val path = c.getString(iData) ?: continue
                    if (!CatalogPolicy.isVisiblePath(path)) continue
                    val f = File(path)
                    if (f.name.startsWith(".")) continue
                    if (!f.exists() || f.isDirectory) continue
                    if (f.length() <= 0) continue
                    val id = c.getLong(iId)
                    out.add(
                        FileItem(
                            name = c.getString(iName) ?: f.name,
                            path = path,
                            isDir = false,
                            size = c.getLong(iSize),
                            mtime = c.getLong(iMod) * 1000L,
                            mime = c.getString(iMime) ?: "",
                            contentUri = if (id > 0) runCatching {
                                ContentUris.withAppendedId(
                                    MediaStore.Files.getContentUri("external"), id
                                ).toString()
                            }.getOrDefault("") else "",
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
