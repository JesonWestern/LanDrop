package com.landrop.transfer.storage

/**
 * 接收落盘路径的纯计算（便于 JVM 单测，不依赖 android.*）。
 */
object PublishPaths {

    /** rel 拆成 (完整 rel, 子目录, 文件名)；rel 使用 '/' 分隔。 */
    fun split(relPath: String): Triple<String, String, String> {
        val rel = relPath.replace('\\', '/').trimStart('/')
        val idx = rel.lastIndexOf('/')
        val sub = if (idx > 0) rel.substring(0, idx) else ""
        val name = if (idx >= 0) rel.substring(idx + 1) else rel
        return Triple(rel, sub, name)
    }

    /** 公共下载目录下的相对目录，例如 "Download/LanDrop/子目录"。 */
    fun displayDir(sub: String): String =
        "Download/LanDrop" + if (sub.isNotEmpty()) "/$sub" else ""

    /** 同名冲突时的候选名："a.txt" -> "a (1).txt"。 */
    fun numberedName(name: String, index: Int): String {
        if (index <= 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($index)${name.substring(dot)}"
        } else {
            "$name ($index)"
        }
    }
}
