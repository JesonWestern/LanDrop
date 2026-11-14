package com.landrop.transfer.storage

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.landrop.transfer.data.Settings
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.FileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「分类」页的数据层。
 *
 * 默认数据源是系统媒体库索引（MediaStore.Files，秒开），设置里可切到全盘深度扫描
 * （[DeepScan]）。结果常驻内存，避免每次切 tab 都重扫 —— 这是本页最大的性能陷阱。
 */
object Catalog {

    enum class Source { MEDIA, DEEP }

    data class KindBucket(val kind: FileKind, val count: Int, val bytes: Long)

    data class State(
        val items: List<FileItem> = emptyList(),
        val buckets: List<KindBucket> = emptyList(),
        val loading: Boolean = false,
        val scanning: Boolean = false,
        val scanFound: Int = 0,
        val scanDir: String = "",
        val generatedAt: Long = 0L,
        val source: Source = Source.MEDIA,
        val truncated: Boolean = false,
        val error: String = "",
    )

    /** 媒体库查询上限：不设上限时，三万张照片的机器会拉全表并多次扩容 CursorWindow。 */
    private const val LIMIT = 20_000
    private const val MEDIA_TTL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appCtx: Context? = null
    private var prefs: Settings? = null
    @Volatile private var lastLoad = 0L
    private var scanJob: Job? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val invalidateTask = Runnable {
        val c = appCtx ?: return@Runnable
        if (_state.value.source == Source.MEDIA && !_state.value.scanning) refresh(c, force = true)
    }

    private val observer by lazy {
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                lastLoad = 0L
                main.removeCallbacks(invalidateTask)
                main.postDelayed(invalidateTask, 1200)
            }
        }
    }

    /** 应用启动后调用一次即可，重复调用无副作用。 */
    fun warm(ctx: Context) {
        if (appCtx != null) return
        val app = ctx.applicationContext
        appCtx = app
        val s = Settings(app)
        prefs = s
        runCatching {
            app.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, observer
            )
        }
        if (s.deepScan) {
            val cached = DeepScan.load(app)
            if (cached != null) {
                emit(cached.items, Source.DEEP, cached.generatedAt, cached.truncated)
                if (System.currentTimeMillis() - cached.generatedAt > DeepScan.TTL_MS) startDeepScan(app)
            } else {
                startDeepScan(app)
            }
        } else {
            refresh(app, force = true)
        }
    }

    fun isDeepEnabled(): Boolean = prefs?.deepScan ?: false

    /** 设置里的开关与分类页顶部开关共用这一个入口。 */
    fun setDeepScan(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        if (appCtx == null) warm(app)
        prefs?.deepScan = enabled
        if (enabled) startDeepScan(app) else {
            scanJob?.cancel()
            scanJob = null
            _state.update { it.copy(scanning = false, scanFound = 0, scanDir = "") }
            refresh(app, force = true)
        }
    }

    /** @param force 忽略 60 秒缓存 */
    fun refresh(ctx: Context, force: Boolean = false) {
        if (_state.value.scanning) return
        if (!force && System.currentTimeMillis() - lastLoad < MEDIA_TTL_MS && _state.value.items.isNotEmpty()) {
            return
        }
        val app = ctx.applicationContext
        _state.update { it.copy(loading = true, error = "") }
        scope.launch {
            val list = runCatching { queryMedia(app) }.getOrElse { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "读取媒体库失败") }
                return@launch
            }
            lastLoad = System.currentTimeMillis()
            emit(list, Source.MEDIA, System.currentTimeMillis(), false)
            _state.update { it.copy(loading = false) }
        }
    }

    fun startDeepScan(ctx: Context) {
        if (_state.value.scanning) return
        val app = ctx.applicationContext
        if (appCtx == null) {
            appCtx = app
            prefs = Settings(app)
        }
        scanJob?.cancel()
        _state.update {
            it.copy(scanning = true, scanFound = 0, scanDir = "", error = "", truncated = false)
        }
        scanJob = scope.launch {
            val root = FsBrowser.root()
            val result = runCatching {
                DeepScan.scan(root) { found, dir ->
                    _state.update { it.copy(scanFound = found, scanDir = dir) }
                }
            }.getOrElse { e ->
                _state.update { it.copy(scanning = false, error = e.message ?: "扫描失败") }
                return@launch
            }
            DeepScan.save(app, result)
            lastLoad = System.currentTimeMillis()
            emit(result.items, Source.DEEP, result.generatedAt, result.truncated)
            _state.update { it.copy(scanning = false, scanFound = 0, scanDir = "") }
        }
    }

    /** 设置页「重新扫描」。 */
    fun rescan(ctx: Context) {
        if (isDeepEnabled()) startDeepScan(ctx) else {
            lastLoad = 0L
            refresh(ctx, force = true)
        }
    }

    private fun emit(items: List<FileItem>, source: Source, at: Long, truncated: Boolean) {
        val n = FileKind.entries.size
        val counts = IntArray(n)
        val bytes = LongArray(n)
        for (f in items) {
            val i = f.kind.ordinal
            counts[i]++
            bytes[i] += f.size
        }
        val buckets = FileKind.entries.mapIndexed { i, k -> KindBucket(k, counts[i], bytes[i]) }
        _state.update {
            it.copy(
                items = items,
                buckets = buckets,
                generatedAt = at,
                source = source,
                truncated = truncated,
            )
        }
    }

    private fun queryMedia(ctx: Context): List<FileItem> {
        val out = ArrayList<FileItem>(4096)
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val sel = StringBuilder("${MediaStore.MediaColumns.SIZE} > 0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sel.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sel.append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel.toString())
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                arrayOf("${MediaStore.MediaColumns.DATE_MODIFIED} DESC"),
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, LIMIT)
        }
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"), proj, args, null
        )?.use { c ->
            val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val name = c.getString(iName) ?: path.substringAfterLast('/')
                if (name.startsWith(".")) continue
                val lower = path.lowercase()
                // 应用私有目录：既无权限也不该出现在分类里
                if (lower.contains("/android/data/") || lower.contains("/android/obb/")) continue
                val size = c.getLong(iSize)
                if (size <= 0L) continue
                out.add(
                    FileItem(
                        name = name,
                        path = path,
                        isDir = false,
                        size = size,
                        // MediaStore 的 DATE_MODIFIED 单位是秒
                        mtime = c.getLong(iMod) * 1000L,
                        mime = c.getString(iMime) ?: "",
                    )
                )
            }
        }
        return out
    }
}
