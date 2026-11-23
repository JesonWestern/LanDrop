package com.landrop.transfer.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.FileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件列表缩略图（零新增依赖）。
 *
 * 数据源三级回退：
 *  1. 媒体库行带 content uri 且 API ≥ 29 → `ContentResolver.loadThumbnail`（系统负责 EXIF/HEIC/尺寸）；
 *  2. 普通路径 → BitmapFactory 两遍采样解码 + 平台 ExifInterface 纠正方向；
 *  3. APK → PackageManager.getPackageArchiveInfo + getApplicationIcon；
 *  失败（PSD/RAW/损坏文件/旧系统 HEIC）写负缓存并回退手绘图标。
 *
 * 缓存按字节计（默认 1/8 堆，上限 24 MB），并发解码 4 路，同一 key 只解一次。
 */
object ThumbCache {

    /** 40dp 行高在 3x 屏约 120px，解码到 128px 足够清晰又省内存。 */
    const val TARGET_PX = 128

    private val negative = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val semaphore = Semaphore(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache: LruCache<String, ImageBitmap> by lazy {
        val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024)
        object : LruCache<String, ImageBitmap>(budget.toInt()) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.width * value.height * 4
        }
    }

    fun keyOf(item: FileItem): String = "${item.path}|${item.mtime}|${item.size}|$TARGET_PX"

    fun cached(item: FileItem): ImageBitmap? = runCatching { cache.get(keyOf(item)) }.getOrNull()

    fun isSupported(item: FileItem): Boolean {
        val k = item.kind
        return !item.isDir && (k == FileKind.IMAGE || k == FileKind.APK)
    }

    /** 是否值得为这一行启动加载（已在缓存/负缓存/不支持的类型直接跳过）。 */
    fun shouldLoad(item: FileItem): Boolean {
        if (!isSupported(item)) return false
        val k = keyOf(item)
        return cache.get(k) == null && !negative.contains(k)
    }

    suspend fun load(ctx: Context, item: FileItem): ImageBitmap? {
        if (!isSupported(item)) return null
        val key = keyOf(item)
        cache.get(key)?.let { return it }
        if (negative.contains(key)) return null
        val app = ctx.applicationContext
        val deferred = inFlight.getOrPut(key) {
            scope.async {
                try {
                    val bmp = semaphore.withPermit { decode(app, item) }
                    if (bmp == null) {
                        negative.add(key)
                        null
                    } else {
                        cache.put(key, bmp)
                        bmp
                    }
                } catch (_: Throwable) {
                    negative.add(key)
                    null
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return runCatching { deferred.await() }.getOrNull()
    }

    private fun decode(ctx: Context, item: FileItem): ImageBitmap? = when (item.kind) {
        FileKind.IMAGE -> decodeImage(ctx, item)
        FileKind.APK -> decodeApkIcon(ctx, item.path)
        else -> null
    }

    private fun decodeImage(ctx: Context, item: FileItem): ImageBitmap? {
        if (item.contentUri.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ctx.contentResolver.loadThumbnail(
                    Uri.parse(item.contentUri),
                    Size(TARGET_PX, TARGET_PX),
                    null,
                )
            }.getOrNull()?.let { return it.asImageBitmap() }
        }
        val f = File(item.path)
        if (!f.exists() || f.length() <= 0) return null
        return decodeSampled(f)?.asImageBitmap()
    }

    private fun decodeSampled(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > TARGET_PX * 2 || h / sample > TARGET_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull() ?: return null
        val oriented = runCatching { applyExifRotation(raw, f) }.getOrDefault(raw)
        val maxSide = maxOf(oriented.width, oriented.height)
        if (maxSide <= TARGET_PX * 2) return oriented
        val scale = (TARGET_PX * 2).toFloat() / maxSide
        return runCatching {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== oriented) oriented.recycle() }
        }.getOrDefault(oriented)
    }

    private fun applyExifRotation(src: Bitmap, f: File): Bitmap {
        val exif = ExifInterface(f.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f
            else -> return src
        }
        if (degrees == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun decodeApkIcon(ctx: Context, path: String): ImageBitmap? {
        val pm = ctx.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, 0)
            }
        }.getOrNull() ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        val drawable = runCatching { pm.getApplicationIcon(ai) }.getOrNull() ?: return null
        return runCatching { drawable.toBitmap(TARGET_PX, TARGET_PX, Bitmap.Config.ARGB_8888) }
            .getOrNull()?.asImageBitmap()
    }
}
