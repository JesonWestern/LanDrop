package com.landrop.transfer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindTest {

    @Test
    fun extensionWins() {
        assertEquals(FileKind.IMAGE, FileKind.of("IMG_0001.JPG"))
        assertEquals(FileKind.VIDEO, FileKind.of("旅行.mkv"))
        assertEquals(FileKind.AUDIO, FileKind.of("a.flac"))
        assertEquals(FileKind.DOC, FileKind.of("报告.docx"))
        assertEquals(FileKind.ARCHIVE, FileKind.of("备份.7z"))
        assertEquals(FileKind.APK, FileKind.of("app.apk"))
        assertEquals(FileKind.OTHER, FileKind.of("data.bin"))
    }

    @Test
    fun extensionBeatsWrongMime() {
        // 部分 provider 会给 octet-stream，扩展名优先
        assertEquals(FileKind.IMAGE, FileKind.of("photo.png", "application/octet-stream"))
    }

    @Test
    fun mimeFallbackWhenNoExtension() {
        assertEquals(FileKind.IMAGE, FileKind.of("noext", "image/jpeg"))
        assertEquals(FileKind.VIDEO, FileKind.of("noext", "video/mp4"))
        assertEquals(FileKind.AUDIO, FileKind.of("noext", "audio/mpeg"))
        assertEquals(FileKind.DOC, FileKind.of("noext", "text/plain"))
        assertEquals(FileKind.DOC, FileKind.of("noext", "application/pdf"))
        assertEquals(FileKind.ARCHIVE, FileKind.of("noext", "application/zip"))
        assertEquals(
            FileKind.APK,
            FileKind.of("noext", "application/vnd.android.package-archive")
        )
        assertEquals(FileKind.OTHER, FileKind.of("noext", ""))
    }

    @Test
    fun pathIsTrimmed() {
        assertEquals(FileKind.IMAGE, FileKind.of("/sdcard/DCIM/a.png"))
        assertEquals(FileKind.DOC, FileKind.of("C:\\Users\\a\\b.txt"))
    }

    @Test
    fun hiddenNameWithoutExt() {
        assertEquals(FileKind.OTHER, FileKind.of(".nomedia"))
    }
}
