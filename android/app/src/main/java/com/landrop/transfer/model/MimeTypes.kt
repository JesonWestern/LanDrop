package com.landrop.transfer.model

/**
 * 文件 MIME 判定。
 *
 * 判定顺序（越靠前越可信）：
 *  1. 自维护表：Android 的 MimeTypeMap 对 heic/mkv/7z/apk 等常见类型并不可靠；
 *  2. 调用方已知的 MIME（来自 MediaStore/分享来源，排除 octet-stream）；
 *  3. 平台 MimeTypeMap（以参数注入，单测可传假实现）；
 *  4. [FileKind] 粗分类兜底，仍未知则 application/octet-stream。
 *
 * 纯函数，不依赖 Android 运行时；平台绑定放在调用方（见 storage/FileOpener）。
 */
object MimeTypes {

    const val OCTET = "application/octet-stream"

    private val EXPLICIT: Map<String, String> = buildMap(192) {
        fun reg(mime: String, vararg exts: String) {
            for (e in exts) put(e, mime)
        }
        // 图片
        reg("image/jpeg", "jpg", "jpeg", "jpe", "jfif")
        reg("image/png", "png")
        reg("image/gif", "gif")
        reg("image/webp", "webp")
        reg("image/bmp", "bmp")
        reg("image/heic", "heic")
        reg("image/heif", "heif")
        reg("image/avif", "avif")
        reg("image/svg+xml", "svg")
        reg("image/tiff", "tif", "tiff")
        reg("image/x-adobe-dng", "dng")
        reg("image/x-icon", "ico")
        reg("image/vnd.adobe.photoshop", "psd")
        reg("image/x-canon-cr2", "cr2")
        reg("image/x-nikon-nef", "nef")
        reg("image/x-sony-arw", "arw")
        reg("image/x-olympus-orf", "orf")
        reg("image/x-panasonic-rw2", "rw2")
        // 视频
        reg("video/mp4", "mp4")
        reg("video/x-m4v", "m4v")
        reg("video/x-matroska", "mkv")
        reg("video/quicktime", "mov")
        reg("video/x-msvideo", "avi")
        reg("video/x-ms-wmv", "wmv")
        reg("video/x-flv", "flv")
        reg("video/webm", "webm")
        reg("video/3gpp", "3gp")
        reg("video/3gpp2", "3g2")
        reg("video/mp2t", "ts", "m2ts", "mts")
        reg("video/mpeg", "mpg", "mpeg")
        reg("video/vnd.rn-realvideo", "rmvb")
        reg("application/vnd.rn-realmedia", "rm")
        reg("video/dvd", "vob")
        // 音频
        reg("audio/mpeg", "mp3")
        reg("audio/flac", "flac")
        reg("audio/x-wav", "wav")
        reg("audio/aac", "aac")
        reg("audio/mp4", "m4a")
        reg("audio/ogg", "ogg")
        reg("audio/opus", "opus")
        reg("audio/x-ms-wma", "wma")
        reg("audio/amr", "amr")
        reg("audio/x-ape", "ape")
        reg("audio/midi", "mid", "midi")
        reg("audio/aiff", "aiff", "aif")
        reg("audio/x-alac", "alac")
        reg("audio/x-dsf", "dsf")
        reg("audio/x-matroska", "mka")
        // 文档
        reg("application/pdf", "pdf")
        reg("application/msword", "doc")
        reg("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
        reg("application/vnd.ms-excel", "xls", "et")
        reg("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
        reg("application/vnd.ms-powerpoint", "ppt", "dps")
        reg("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx")
        reg("text/plain", "txt", "log", "ini", "conf", "properties")
        reg("text/markdown", "md")
        reg("application/rtf", "rtf")
        reg("text/csv", "csv")
        reg("application/json", "json")
        reg("application/xml", "xml")
        reg("text/html", "html", "htm")
        reg("application/epub+zip", "epub")
        reg("application/x-mobipocket-ebook", "mobi")
        reg("application/vnd.amazon.ebook", "azw3")
        reg("application/yaml", "yml", "yaml")
        reg("application/toml", "toml")
        reg("application/x-subrip", "srt")
        reg("text/x-ssa", "ass")
        reg("text/vtt", "vtt")
        reg("application/x-tex", "tex")
        reg("application/vnd.apple.pages", "pages")
        reg("application/vnd.apple.numbers", "numbers")
        reg("application/vnd.apple.keynote", "key")
        // 压缩包
        reg("application/zip", "zip", "zipx", "xapk", "apkm")
        reg("application/vnd.rar", "rar")
        reg("application/x-7z-compressed", "7z")
        reg("application/x-tar", "tar")
        reg("application/gzip", "gz", "tgz")
        reg("application/x-bzip2", "bz2")
        reg("application/x-xz", "xz")
        reg("application/x-iso9660-image", "iso")
        reg("application/vnd.ms-cab-compressed", "cab")
        reg("application/x-lz4", "lz4")
        reg("application/zstd", "zst")
        reg("application/java-archive", "jar", "war")
        // 安装包
        reg("application/vnd.android.package-archive", "apk")
    }

    /** 取文件名的扩展名（小写，不含点）。 */
    fun extOf(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return ""
        return base.substring(dot + 1).lowercase()
    }

    /**
     * @param known 来源已知的 MIME（可空）；只在自维护表与平台查询都失败后使用
     * @param platform 平台查询（如 MimeTypeMap），测试可注入
     */
    fun of(name: String, known: String?, platform: (String) -> String?): String {
        val ext = extOf(name)
        if (ext.isNotEmpty()) {
            EXPLICIT[ext]?.let { return it }
        }
        val k = known?.trim()?.lowercase().orEmpty()
        if (k.isNotEmpty() && k != OCTET && k != "*/*") return k
        if (ext.isNotEmpty()) {
            runCatching { platform(ext) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != OCTET && it != "*/*" }
                ?.let { return it }
        }
        // 最后按粗分类给一个可用的兜底（只用于让系统选择器有机会匹配）
        return when (FileKind.of(name, known)) {
            FileKind.IMAGE -> "image/*"
            FileKind.VIDEO -> "video/*"
            FileKind.AUDIO -> "audio/*"
            else -> OCTET
        }
    }
}
