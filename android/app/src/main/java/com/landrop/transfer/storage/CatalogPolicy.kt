package com.landrop.transfer.storage

import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.FileKind

/**
 * 分类页的数据口径（纯函数，便于 JVM 单测）。
 *
 * 这里刻意不依赖 android.*：目录判定/存在性/URI 生成都用回调注入。
 * 「文件页、分类页、全盘扫描」三处必须共用 [isVisiblePath]，否则同一台机器会出现
 * 105 / 100 / 97 / 92 这种四套图片数量。
 */

data class KindBucket(val kind: FileKind, val count: Int, val bytes: Long)

/** MediaStore.Files 的一行（projection：_id/_data/_display_name/_size/date_modified/mime_type）。 */
data class MediaRow(
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val mtimeMs: Long,
    val mime: String,
)

object CatalogPolicy {

    /** 媒体库查询上限：够覆盖绝大多数手机，又不至于把 Cursor 拉成全表。 */
    const val LIMIT = 20_000

    private val PRIVATE_SEGMENTS = arrayOf("/android/data/", "/android/obb/")

    /**
     * 与系统文件管理器/相册一致的「可见」判定：
     * - 路径任一段以 '.' 开头（隐藏目录/隐藏文件）→ 不可见；
     * - 位于 Android/data、Android/obb（无权限且不该出现在分类里）→ 不可见。
     */
    fun isVisiblePath(path: String): Boolean {
        if (path.isEmpty()) return false
        val lower = path.lowercase()
        for (p in PRIVATE_SEGMENTS) if (lower.contains(p)) return false
        var start = 0
        val n = path.length
        while (start < n) {
            val slash = path.indexOf('/', start)
            val end = if (slash < 0) n else slash
            if (end > start && path[start] == '.') return false
            if (slash < 0) break
            start = slash + 1
        }
        return true
    }

    /**
     * 把 MediaStore 行变成分类条目；剔除：
     *  - 目录行（MediaStore.Files 会返回目录，_size 常为 4096、mime 为空）
     *  - 已失效/已删除的陈旧行
     *  - 隐藏路径、点文件名、重复路径
     *
     * @param isDir 仅对 mime 为空的行调用（避免对全部行 stat）
     * @param exists 文件是否仍存在
     * @param uriOf 由 _id 生成 content uri（可为空实现）
     */
    fun toItems(
        rows: List<MediaRow>,
        isDir: (String) -> Boolean,
        exists: (String) -> Boolean,
        uriOf: (Long) -> String,
    ): List<FileItem> {
        val out = ArrayList<FileItem>(rows.size)
        val seen = HashSet<String>(rows.size * 2)
        for (r in rows) {
            if (r.size <= 0L) continue
            val name = r.name.ifEmpty { r.path.substringAfterLast('/') }
            if (name.isEmpty() || name.startsWith(".")) continue
            if (!isVisiblePath(r.path)) continue
            if (!seen.add(r.path)) continue
            // 目录行：MediaStore 的 mime 为空，直接用文件系统判定，顺手省掉大量 stat
            if (r.mime.isBlank() && runCatching { isDir(r.path) }.getOrDefault(false)) continue
            if (!runCatching { exists(r.path) }.getOrDefault(false)) continue
            out.add(
                FileItem(
                    name = name,
                    path = r.path,
                    isDir = false,
                    size = r.size,
                    mtime = r.mtimeMs,
                    mime = r.mime,
                    contentUri = if (r.id > 0) uriOf(r.id) else "",
                )
            )
        }
        return out
    }

    fun buckets(items: List<FileItem>): List<KindBucket> {
        val n = FileKind.entries.size
        val counts = IntArray(n)
        val bytes = LongArray(n)
        for (f in items) {
            val i = f.kind.ordinal
            counts[i]++
            bytes[i] += f.size
        }
        return FileKind.entries.mapIndexed { i, k -> KindBucket(k, counts[i], bytes[i]) }
    }

    /**
     * 请求时取 LIMIT+1 行：返回行数超过 LIMIT 才说明真的还有更多，
     * 恰好等于 LIMIT 时不会误报“已截断”。
     */
    fun truncated(rawRows: Int): Boolean = rawRows > LIMIT
}
