package com.landrop.transfer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 主题持久化与壁纸管理。
 *
 * 存储布局（都在应用私有目录，不污染用户存储）：
 *   SharedPreferences "landrop"：themePreset / themeAccent / themeScrim / themeAnimations / themeWallpaper
 *   filesDir/theme/wallpaper.jpg：降采样后的壁纸（≤1440px 长边，JPEG 88）
 *
 * 为什么壁纸要复制并降采样：直接持用户选的 `content://` uri 会在进程重启、权限回收、
 * 原图被移动后失效；原图动辄几十 MB，每次启动解码既慢又占内存。
 */
object ThemeStore {

    private const val TAG = "LanDropTheme"
    private const val SP = "landrop"
    private const val K_PRESET = "themePreset"
    private const val K_ACCENT = "themeAccent"
    private const val K_SCRIM = "themeScrim"
    private const val K_ANIM = "themeAnimations"
    private const val K_WALLPAPER = "themeWallpaper"

    /** 壁纸降采样上限：1440px 长边。2K/4K 屏上略软，但内存与解码时间可控。 */
    private const val WALLPAPER_MAX_PX = 1440

    const val SCRIM_MIN = 0
    const val SCRIM_MAX = 80
    const val SCRIM_DEFAULT = 55

    data class State(
        val preset: String = DEFAULT_PRESET_ID,
        val accent: Color? = null,
        val scrim: Int = SCRIM_DEFAULT,
        val animations: Boolean = true,
        val wallpaper: Bitmap? = null,
        /** 壁纸解码完成（用于区分"没有壁纸"和"还在加载"）。 */
        val wallpaperLoaded: Boolean = false,
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appCtx: Context? = null

    /**
     * 同步读取主题标识（不含壁纸）。`MainActivity` 在 `setContent` 之前调用它，
     * 把 window 背景色先设成主题底色，避免冷启动的第一帧白闪。
     */
    fun peekBg(ctx: Context): Color {
        val sp = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return withAccent(
            presetOf(sp.getString(K_PRESET, null)),
            accentOf(sp.getInt(K_ACCENT, 0)),
        ).bg
    }

    private fun accentOf(argb: Int): Color? =
        if (argb == 0) null else Color(argb)

    /** 首次调用时从 SharedPreferences 载入全部状态并异步解码壁纸。 */
    fun init(ctx: Context) {
        if (appCtx != null) return
        val app = ctx.applicationContext
        appCtx = app
        val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
        _state.value = State(
            preset = sp.getString(K_PRESET, null)?.let { if (presetOf(it).id == it) it else null }
                ?: DEFAULT_PRESET_ID,
            accent = accentOf(sp.getInt(K_ACCENT, 0)),
            scrim = sp.getInt(K_SCRIM, SCRIM_DEFAULT).coerceIn(SCRIM_MIN, SCRIM_MAX),
            animations = sp.getBoolean(K_ANIM, true),
            wallpaper = null,
            wallpaperLoaded = false,
        )
        if (wallpaperFile(app).exists()) {
            scope.launch {
                val bmp = decodeWallpaper(app)
                _state.value = _state.value.copy(wallpaper = bmp, wallpaperLoaded = true)
                Log.i(TAG, "wallpaper decoded ${bmp?.width}x${bmp?.height}")
            }
        } else {
            _state.value = _state.value.copy(wallpaperLoaded = true)
        }
    }

    fun setPreset(id: String) {
        val safe = presetOf(id).id
        if (_state.value.preset == safe) return
        _state.value = _state.value.copy(preset = safe)
        persist { putString(K_PRESET, safe) }
        Log.i(TAG, "preset -> $safe")
    }

    fun setAccent(c: Color?) {
        val v = c?.let { Color(it.red, it.green, it.blue, 1f) }
        _state.value = _state.value.copy(accent = v)
        persist { if (v == null) remove(K_ACCENT) else putInt(K_ACCENT, v.toArgb()) }
        Log.i(TAG, "accent -> ${v?.toArgb()?.let { Integer.toHexString(it) } ?: "default"}")
    }

    fun setScrim(v: Int) {
        val s = v.coerceIn(SCRIM_MIN, SCRIM_MAX)
        if (_state.value.scrim == s) return
        _state.value = _state.value.copy(scrim = s)
        persist { putInt(K_SCRIM, s) }
    }

    fun setAnimations(on: Boolean) {
        if (_state.value.animations == on) return
        _state.value = _state.value.copy(animations = on)
        persist { putBoolean(K_ANIM, on) }
    }

    /** 选一张新壁纸：解码 → 降采样 → 落盘 → 更新内存。 */
    fun setWallpaper(uri: Uri) {
        val app = appCtx ?: return
        scope.launch {
            val bmp = runCatching { loadScaled(app, uri) }.getOrNull()
            if (bmp == null) {
                Log.w(TAG, "wallpaper decode failed: $uri")
                return@launch
            }
            runCatching {
                wallpaperFile(app).parentFile?.mkdirs()
                FileOutputStream(wallpaperFile(app)).use { o ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, o)
                }
                true
            }.getOrElse {
                Log.w(TAG, "wallpaper save failed: ${it.message}")
                false
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(wallpaper = bmp, wallpaperLoaded = true)
            }
            persist { putString(K_WALLPAPER, "custom") }
            Log.i(TAG, "wallpaper set ${bmp.width}x${bmp.height}")
        }
    }

    fun clearWallpaper() {
        val app = appCtx ?: return
        runCatching { wallpaperFile(app).delete() }
        _state.value = _state.value.copy(wallpaper = null, wallpaperLoaded = true)
        persist { remove(K_WALLPAPER) }
    }

    private fun decodeWallpaper(app: Context): Bitmap? =
        runCatching { BitmapFactory.decodeFile(wallpaperFile(app).absolutePath) }.getOrNull()

    private fun loadScaled(app: Context, uri: Uri): Bitmap? {
        // 第一遍只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w, h) / sample > WALLPAPER_MAX_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val maxSide = maxOf(raw.width, raw.height)
        if (maxSide <= WALLPAPER_MAX_PX) return raw
        val scale = WALLPAPER_MAX_PX.toFloat() / maxSide
        return runCatching {
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== raw) raw.recycle() }
        }.getOrDefault(raw)
    }

    private fun wallpaperFile(app: Context): File = File(File(app.filesDir, "theme"), "wallpaper.jpg")

    private fun persist(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val app = appCtx ?: return
        val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
        sp.edit().apply(block).apply()
    }
}
