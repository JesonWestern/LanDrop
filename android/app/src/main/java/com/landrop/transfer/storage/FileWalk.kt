package com.landrop.transfer.storage

import com.landrop.transfer.model.Entry
import java.io.File
import java.nio.file.Files

/**
 * 把用户选中的路径（文件/文件夹混合）展开成传输清单。
 *
 * 1.2 修正：
 *  - 父目录与子文件同时被选中时不再重复入清单（同一文件两个 rel 会在接收端写两份）；
 *  - 按绝对路径去重；
 *  - 跳过符号链接，避免目录环导致无限递归。
 */
object FileWalk {

    fun collect(paths: List<String>): List<Entry> {
        val selected = paths.map { File(it) }.filter { it.exists() }
        if (selected.isEmpty()) return emptyList()

        // 已被某个选中目录包含的路径直接丢弃，避免「选父目录又选子文件」
        val dirs = selected.filter { it.isDirectory }.sortedBy { it.absolutePath.length }
        val roots = selected.filter { f -> dirs.none { d -> isInside(f, d) } }

        val raw = LinkedHashMap<String, Entry>(selected.size * 4)
        for (f in roots) {
            if (f.isDirectory) walk(f, f.name, raw) else add(raw, f, f.name)
        }
        return uniqueRels(raw.values)
    }

    /**
     * 不同目录下的同名文件会得到相同 rel（顶层文件直接用文件名、不同选中目录也可能同名）。
     * 接收端按 rel 定位 .part，重复 rel 会互相覆盖 → 这里做自动消歧（name (1).ext）。
     */
    private fun uniqueRels(entries: Collection<Entry>): List<Entry> {
        val used = HashSet<String>(entries.size * 2)
        val out = ArrayList<Entry>(entries.size)
        for (e in entries) {
            var rel = e.rel
            if (!used.add(rel)) {
                val slash = rel.lastIndexOf('/')
                val dir = if (slash >= 0) rel.substring(0, slash + 1) else ""
                val name = if (slash >= 0) rel.substring(slash + 1) else rel
                var i = 1
                while (true) {
                    val candidate = dir + PublishPaths.numberedName(name, i)
                    if (used.add(candidate)) {
                        rel = candidate
                        break
                    }
                    i++
                }
            }
            out.add(if (rel == e.rel) e else Entry(rel, e.absPath, e.size, e.mtime, e.isDir, e.source))
        }
        return out
    }

    private fun isInside(file: File, dir: File): Boolean {
        if (file.absolutePath == dir.absolutePath) return false
        return file.absolutePath.startsWith(dir.absolutePath.trimEnd(File.separatorChar) + File.separator)
    }

    private fun add(out: MutableMap<String, Entry>, f: File, rel: String) {
        out.putIfAbsent(f.absolutePath, entryOf(f, rel))
    }

    private fun entryOf(f: File, rel: String) = Entry(
        rel = rel,
        absPath = f.absolutePath,
        size = f.length(),
        mtime = f.lastModified(),
        isDir = false,
    )

    private fun walk(dir: File, rel: String, out: MutableMap<String, Entry>) {
        val children = runCatching { dir.listFiles() }.getOrNull()
        var count = 0
        if (children != null) {
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (isSymlink(f)) continue
                val childRel = "$rel/${f.name}"
                count++
                if (f.isDirectory) walk(f, childRel, out) else add(out, f, childRel)
            }
        }
        if (count == 0) {
            out.putIfAbsent(dir.absolutePath, Entry(rel, dir.absolutePath, 0, dir.lastModified(), true))
        }
    }

    private fun isSymlink(f: File): Boolean = runCatching {
        Files.isSymbolicLink(f.toPath())
    }.getOrDefault(false)

    /** rel 的顶级目录名（用于接收端去重命名）。 */
    fun topPrefix(rel: String): String = rel.substringBefore('/')
}
