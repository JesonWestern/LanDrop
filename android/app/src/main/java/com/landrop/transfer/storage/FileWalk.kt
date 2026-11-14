package com.landrop.transfer.storage

import com.landrop.transfer.model.Entry
import java.io.File

object FileWalk {

    fun collect(paths: List<String>): List<Entry> {
        val out = ArrayList<Entry>()
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            if (f.isDirectory) {
                walk(f, f.name, out)
            } else {
                out.add(entryOf(f, f.name))
            }
        }
        return out
    }

    private fun entryOf(f: File, rel: String) = Entry(
        rel = rel,
        absPath = f.absolutePath,
        size = f.length(),
        mtime = f.lastModified(),
        isDir = false,
    )

    private fun walk(dir: File, rel: String, out: MutableList<Entry>) {
        val children = dir.listFiles()
        var count = 0
        if (children != null) {
            for (f in children) {
                if (f.name.startsWith(".")) continue
                val childRel = "$rel/${f.name}"
                count++
                if (f.isDirectory) walk(f, childRel, out) else out.add(entryOf(f, childRel))
            }
        }
        if (count == 0) {
            out.add(Entry(rel, dir.absolutePath, 0, dir.lastModified(), true))
        }
    }

    /** rel 的顶级目录名（用于接收端去重命名）。 */
    fun topPrefix(rel: String): String = rel.substringBefore('/')
}
