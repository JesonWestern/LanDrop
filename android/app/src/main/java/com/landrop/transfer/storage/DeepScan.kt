package com.landrop.transfer.storage

import android.content.Context
import com.landrop.transfer.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 全盘深度扫描（分类页的可选数据源）。
 *
 * 设计要点：
 * - 迭代式 DFS（显式栈），不用递归，深层目录不会爆栈且可随时中断；
 * - 整棵剪掉根目录下的 `Android/`（无权限且巨大），跳过隐藏目录、含 .nomedia 的目录；
 * - 结果写二进制缓存，十万条约 7 MB / 200 ms，比 JSON 快一个量级；
 * - 单线程 + 每 500 个文件让出一次，避免与传输抢 I/O。
 */
object DeepScan {

    data class Result(
        val items: List<FileItem>,
        val generatedAt: Long,
        val truncated: Boolean,
    )

    private const val MAGIC = 0x4C444331 // "LDC1"

    /** 2：可见性口径改为与 CatalogPolicy 共用（点目录/Android-data·obb 统一），旧缓存自动失效。 */
    private const val VERSION = 2
    private const val MAX_DEPTH = 12
    private const val MAX_ITEMS = 200_000
    private const val YIELD_EVERY = 500

    val TTL_MS = 24L * 60 * 60 * 1000

    suspend fun scan(
        root: File,
        onProgress: (found: Int, dir: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val out = ArrayList<FileItem>(8192)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        var truncated = false
        var checked = 0

        while (stack.isNotEmpty() && !truncated) {
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            if (children.isEmpty()) continue

            // 有 .nomedia 的目录整体跳过（与系统相册语义一致）
            var noMedia = false
            for (c in children) {
                if (c.name == ".nomedia") {
                    noMedia = true
                    break
                }
            }
            if (noMedia && depth > 0) continue

            for (f in children) {
                val n = f.name
                // 与分类页/最近文件共用同一套可见性口径（点开头路径段、Android/data·obb）
                if (!CatalogPolicy.isVisiblePath(f.absolutePath)) continue
                if (f.isDirectory) {
                    // 根下的 Android/ 整棵剪掉：data/obb 无权限，media 体积巨大且不进分类
                    if (depth == 0 && n.equals("Android", ignoreCase = true)) continue
                    if (!f.canRead()) continue
                    if (isSymlink(f)) continue
                    stack.addLast(f to depth + 1)
                } else {
                    val size = runCatching { f.length() }.getOrDefault(0L)
                    if (size <= 0L) continue
                    out.add(
                        FileItem(
                            name = n,
                            path = f.absolutePath,
                            isDir = false,
                            size = size,
                            mtime = runCatching { f.lastModified() }.getOrDefault(0L),
                        )
                    )
                    checked++
                    if (out.size >= MAX_ITEMS) {
                        truncated = true
                        break
                    }
                    if (checked % YIELD_EVERY == 0) {
                        onProgress(out.size, dir.absolutePath)
                        yield()
                        ensureActive()
                    }
                }
            }
        }
        onProgress(out.size, "")
        Result(out, System.currentTimeMillis(), truncated)
    }

    private fun isSymlink(f: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    }.getOrDefault(false)

    fun cacheFile(ctx: Context): File = File(ctx.filesDir, "catalog/scan.bin")

    fun load(ctx: Context): Result? {
        val f = cacheFile(ctx)
        if (!f.exists() || f.length() < 16) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(f), 64 * 1024)).use { i ->
                if (i.readInt() != MAGIC) return null
                if (i.readInt() != VERSION) return null
                val at = i.readLong()
                val n = i.readInt()
                if (n < 0 || n > MAX_ITEMS) return null
                val items = ArrayList<FileItem>(n)
                for (k in 0 until n) {
                    val path = i.readUTF()
                    val size = i.readLong()
                    val mtime = i.readLong()
                    items.add(
                        FileItem(
                            name = path.substringAfterLast('/'),
                            path = path,
                            isDir = false,
                            size = size,
                            mtime = mtime,
                        )
                    )
                }
                Result(items, at, i.readBoolean())
            }
        }.getOrNull()
    }

    fun save(ctx: Context, r: Result) {
        runCatching {
            val target = cacheFile(ctx)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "scan.bin.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VERSION)
                o.writeLong(r.generatedAt)
                o.writeInt(r.items.size)
                for (it in r.items) {
                    o.writeUTF(it.path)
                    o.writeLong(it.size)
                    o.writeLong(it.mtime)
                }
                o.writeBoolean(r.truncated)
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun clear(ctx: Context) {
        runCatching { cacheFile(ctx).delete() }
    }

    fun sizeBytes(ctx: Context): Long =
        runCatching { cacheFile(ctx).length() }.getOrDefault(0L)
}
