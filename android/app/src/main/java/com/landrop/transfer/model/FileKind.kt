package com.landrop.transfer.model

/**
 * 文件类型分类。用于「最近文件 / 分类页」的图标与分组。
 *
 * 判定以**扩展名优先**（比 MIME 稳定：很多 provider 给的是 octet-stream），
 * 扩展名未命中时再按 MIME 前缀兜底。
 */
enum class FileKind(val label: String) {
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    DOC("文档"),
    ARCHIVE("压缩包"),
    APK("安装包"),
    OTHER("其它"),
    ;

    companion object {

        private val EXT_MAP: Map<String, FileKind> = run {
            val m = HashMap<String, FileKind>(192)
            fun reg(kind: FileKind, vararg exts: String) {
                for (e in exts) m[e] = kind
            }
            reg(
                IMAGE,
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
                "svg", "tif", "tiff", "dng", "raw", "cr2", "nef", "arw", "orf", "rw2",
                "psd", "jfif", "ico",
            )
            reg(
                VIDEO,
                "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "m4v", "3gp", "3g2",
                "ts", "mpg", "mpeg", "rmvb", "rm", "vob", "m2ts", "mts", "f4v",
            )
            reg(
                AUDIO,
                "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "amr",
                "ape", "mid", "midi", "aiff", "aif", "alac", "dsf", "mka",
            )
            reg(
                DOC,
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
                "rtf", "csv", "json", "xml", "html", "htm", "epub", "mobi", "azw3",
                "log", "ini", "yml", "yaml", "toml", "conf", "properties",
                "srt", "ass", "vtt", "tex", "pages", "numbers", "key", "wps", "et", "dps",
            )
            reg(
                ARCHIVE,
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "cab",
                "lz4", "zst", "jar", "war", "zipx",
            )
            reg(APK, "apk", "apks", "xapk", "aab", "apkm")
            m
        }

        /**
         * @param name 文件名（可含路径，内部只取末段扩展名）
         * @param mime 可为空；仅在扩展名无法判定时使用
         */
        fun of(name: String, mime: String? = null): FileKind {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val ext = base.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) EXT_MAP[ext]?.let { return it }
            return ofMime(mime)
        }

        /** 只按 MIME 判定，供分享入口等只有 MIME 的场景使用。 */
        fun ofMime(mime: String?): FileKind {
            val m = mime?.lowercase().orEmpty()
            if (m.isEmpty()) return OTHER
            return when {
                m.startsWith("image/") -> IMAGE
                m.startsWith("video/") -> VIDEO
                m.startsWith("audio/") -> AUDIO
                m.startsWith("text/") -> DOC
                m == "application/pdf" -> DOC
                m.contains("word") || m.contains("excel") || m.contains("spreadsheet") ||
                    m.contains("presentation") || m.contains("powerpoint") ||
                    m.contains("opendocument") -> DOC
                m.contains("zip") || m.contains("rar") || m.contains("7z") ||
                    m.contains("tar") || m.contains("compress") || m.contains("archive") -> ARCHIVE
                m.startsWith("application/vnd.android.package") ||
                    m.contains("xapk") || m.contains("apks") -> APK
                else -> OTHER
            }
        }
    }
}
