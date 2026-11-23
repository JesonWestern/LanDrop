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
 * 目标尺寸：列表/紧凑行用 [TARGET_PX]（128），网格用 [GRID_TARGET_PX]（256）。
 * 缓存 key 含 targetPx，因此两种尺寸各自成条目、互不顶掉。
 * 缓存按字节计（默认 1/8 堆，上限 24 MB），并发解码 4 路，同一 key 只解一次。
 */
object ThumbCache {

    /** 40dp 行高在 3x 屏约 120px，解码到 128px 足够清晰又省内存。 */
    const val TARGET_PX = 128

    /** 网格单元缩略图 96dp，3x 屏约 288px；256 是清晰度与内存的折中点。 */
    const val GRID_TARGET_PX = 256

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

    fun keyOf(item: FileItem, targetPx: Int = TARGET_PX): String =
        "${item.path}|${item.mtime}|${item.size}|$targetPx"

    fun cached(item: FileItem, targetPx: Int = TARGET_PX): ImageBitmap? =
        runCatching { cache.get(keyOf(item, targetPx)) }.getOrNull()

    fun isSupported(item: FileItem): Boolean {
        val k = item.kind
        return !item.isDir && (k == FileKind.IMAGE || k == FileKind.APK)
    }

    /** 是否值得为这一行启动加载（已在缓存/负缓存/不支持的类型直接跳过）。 */
    fun shouldLoad(item: FileItem, targetPx: Int = TARGET_PX): Boolean {
        if (!isSupported(item)) return false
        val k = keyOf(item, targetPx)
        return cache.get(k) == null && !negative.contains(k)
    }

    suspend fun load(ctx: Context, item: FileItem): ImageBitmap? = load(ctx, item, TARGET_PX)

    /**
     * 按指定目标像素加载（网格视图传 [GRID_TARGET_PX]）。
     *
     * 同一 (item, targetPx) 的并发请求共享同一个 Deferred，只有第一个真正解码；
     * 4 路信号量仍作用于**解码**本身，并发上限没变。
     */
    suspend fun load(ctx: Context, item: FileItem, targetPx: Int): ImageBitmap? {
        if (!isSupported(item)) return null
        val px = targetPx.coerceIn(32, 1024)
        val key = keyOf(item, px)
        cache.get(key)?.let { return it }
        if (negative.contains(key)) return null
        val app = ctx.applicationContext
        val deferred = inFlight.getOrPut(key) {
            scope.async {
                try {
                    val bmp = semaphore.withPermit { decode(app, item, px) }
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

    private fun decode(ctx: Context, item: FileItem, targetPx: Int): ImageBitmap? = when (item.kind) {
        FileKind.IMAGE -> decodeImage(ctx, item, targetPx)
        FileKind.APK -> decodeApkIcon(ctx, item.path, targetPx)
        else -> null
    }

    private fun decodeImage(ctx: Context, item: FileItem, targetPx: Int): ImageBitmap? {
        if (item.contentUri.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ctx.contentResolver.loadThumbnail(
                    Uri.parse(item.contentUri),
                    Size(targetPx, targetPx),
                    null,
                )
            }.getOrNull()?.let { return it.asImageBitmap() }
        }
        val f = File(item.path)
        if (!f.exists() || f.length() <= 0) return null
        return decodeSampled(f, targetPx)?.asImageBitmap()
    }

    private fun decodeSampled(f: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > targetPx * 2 || h / sample > targetPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull() ?: return null
        val oriented = runCatching { applyExifRotation(raw, f) }.getOrDefault(raw)
        val maxSide = maxOf(oriented.width, oriented.height)
        if (maxSide <= targetPx * 2) return oriented
        val scale = (targetPx * 2).toFloat() / maxSide
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

    private fun decodeApkIcon(ctx: Context, path: String, targetPx: Int): ImageBitmap? {
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
        return runCatching { drawable.toBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888) }
            .getOrNull()?.asImageBitmap()
    }
}
