package com.landrop.transfer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypesTest {

    private fun mime(name: String, known: String? = null, platform: (String) -> String? = { null }) =
        MimeTypes.of(name, known, platform)

    @Test
    fun explicitTableWins() {
        assertEquals("application/vnd.android.package-archive", mime("a.apk"))
        assertEquals("image/heic", mime("a.HEIC"))
        assertEquals("video/x-matroska", mime("a.mkv"))
        assertEquals("application/x-7z-compressed", mime("a.7z"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            mime("a.docx"),
        )
    }

    @Test
    fun extensionParsing() {
        assertEquals("jpg", MimeTypes.extOf("a.b.JPG"))
        assertEquals("", MimeTypes.extOf("noext"))
        assertEquals("", MimeTypes.extOf("trailing."))
        assertEquals("gz", MimeTypes.extOf("/data/x/y.tar.gz"))
    }

    @Test
    fun knownMimeUsedWhenExtensionUnknown() {
        assertEquals("image/png", mime("weird.zzz", known = "image/png"))
        // octet-stream 不能覆盖平台查询
        assertEquals("text/plain", mime("weird.zzz", known = MimeTypes.OCTET) { "text/plain" })
    }

    @Test
    fun knownMimeIsTrustedOverPlatformLookup() {
        assertEquals("image/png", mime("a.zzz", known = "image/png") { "application/x-foo" })
        // 来源没有可信 MIME 时才查平台表
        assertEquals("application/x-foo", mime("a.zzz", known = MimeTypes.OCTET) { "application/x-foo" })
    }

    @Test
    fun octetStreamIsFinalFallbackForUnknownKind() {
        assertEquals(MimeTypes.OCTET, mime("a.zzz"))
    }

    @Test
    fun imageWildcardFallbackForKnownImageMime() {
        // 已知 MIME 是 image/* 时按类型兜底
        assertEquals("image/*", mime("a.zzz", known = "image/*"))
    }
}
