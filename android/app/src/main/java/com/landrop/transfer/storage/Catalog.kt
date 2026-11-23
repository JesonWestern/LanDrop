package com.landrop.transfer.storage

import android.content.ContentResolver
import android.content.ContentUris
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「分类」页的数据层。
 *
 * 默认数据源是系统媒体库索引（MediaStore.Files，秒开），设置里可切到全盘深度扫描
 * （[DeepScan]）。结果常驻内存，避免每次切 tab 都重扫。
 *
 * 计数口径修正（1.2）：
 *  - QUERY_ARG_SQL_SORT_ORDER 必须是 String（原来是 String[]，被 provider 静默忽略，
 *    超过 2 万行时取到的是最旧的 2 万行）；
 *  - 目录行/失效行/隐藏父目录/重复路径一律不进分类（见 [CatalogPolicy]）；
 *  - 截断（达到 LIMIT）会如实写进 [State.truncated]，UI 明确提示。
 */
object Catalog {

    enum class Source { MEDIA, DEEP }

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
        /** 本次查询 provider 返回的原始行数（诊断用）。 */
        val rawRows: Int = 0,
        /** 被过滤掉的行数（目录/失效/隐藏/重复，诊断用）。 */
        val dropped: Int = 0,
    )

    private const val MEDIA_TTL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appCtx: Context? = null
    private var prefs: Settings? = null
    @Volatile private var lastLoad = 0L
    @Volatile private var scanJob: Job? = null
    @Volatile private var mediaJob: Job? = null

    /** 刷新代号：只有最后一次发起的查询允许写状态，避免旧结果覆盖新结果（网络线程也会调 invalidate）。 */
    private val generation = AtomicInteger(0)

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
        registerObservers(app)
        // 深扫缓存可能十万条（约 7 MB），解析放到 IO 线程，避免分类/设置页首帧卡顿
        scope.launch {
            if (s.deepScan) {
                val cached = DeepScan.load(app)
                if (cached != null) {
                    emit(cached.items, Source.DEEP, cached.generatedAt, cached.truncated, 0)
                    if (System.currentTimeMillis() - cached.generatedAt > DeepScan.TTL_MS) startDeepScan(app)
                } else {
                    startDeepScan(app)
                }
            } else {
                refresh(app, force = true)
            }
        }
    }

    private fun registerObservers(app: Context) {
        val uris = ArrayList<android.net.Uri>(5)
        uris.add(MediaStore.Files.getContentUri("external"))
        uris.add(MediaStore.Images.Media.getContentUri("external"))
        uris.add(MediaStore.Video.Media.getContentUri("external"))
        uris.add(MediaStore.Audio.Media.getContentUri("external"))
        // MediaStore.Downloads 是 API 29 才有的类：API 26-28 上不能触碰，否则 NoClassDefFoundError
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uris.add(MediaStore.Downloads.getContentUri("external"))
        }
        for (u in uris) {
            runCatching { app.contentResolver.registerContentObserver(u, true, observer) }
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

    /** 接收落盘/外部变更后主动让缓存失效（Engine、ReceiverServer 调用）。 */
    fun invalidate(ctx: Context? = null) {
        lastLoad = 0L
        val app = appCtx ?: ctx?.applicationContext ?: return
        if (appCtx == null) warm(app)
        if (_state.value.source == Source.MEDIA && !_state.value.scanning) refresh(app, force = true)
    }

    /** @param force 忽略 15 秒缓存 */
    fun refresh(ctx: Context, force: Boolean = false) {
        if (_state.value.scanning) return
        if (!force && System.currentTimeMillis() - lastLoad < MEDIA_TTL_MS && _state.value.items.isNotEmpty()) {
            return
        }
        val app = ctx.applicationContext
        if (appCtx == null) warm(app)
        val gen = generation.incrementAndGet()
        mediaJob?.cancel()
        _state.update { it.copy(loading = true, error = "") }
        mediaJob = scope.launch {
            val result = runCatching { queryMedia(app) }
            if (gen != generation.get()) return@launch
            val rows = result.getOrElse { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "读取媒体库失败") }
                return@launch
            }
            val rawRows = rows.size
            val truncated = CatalogPolicy.truncated(rawRows)
            val capped = if (truncated) rows.subList(0, CatalogPolicy.LIMIT) else rows
            val items = CatalogPolicy.toItems(
                rows = capped,
                isDir = { File(it).isDirectory },
                exists = { File(it).exists() },
                uriOf = { id -> mediaUri(id) },
            )
            if (gen != generation.get()) return@launch
            lastLoad = System.currentTimeMillis()
            emit(
                items = items,
                source = Source.MEDIA,
                at = System.currentTimeMillis(),
                truncated = truncated,
                rawRows = rawRows,
            )
            _state.update { it.copy(loading = false) }
        }
    }

    fun startDeepScan(ctx: Context) {
        if (_state.value.scanning) return
        val app = ctx.applicationContext
        if (appCtx == null) warm(app)
        scanJob?.cancel()
        mediaJob?.cancel()
        _state.update {
            it.copy(scanning = true, scanFound = 0, scanDir = "", error = "", truncated = false)
        }
        scanJob = scope.launch {
            val root = FsBrowser.root()
            val result = try {
                DeepScan.scan(root) { found, dir ->
                    _state.update { it.copy(scanFound = found, scanDir = dir) }
                }
            } catch (e: CancellationException) {
                // 用户关闭开关：只清运行状态，不算失败
                _state.update { it.copy(scanning = false, scanFound = 0, scanDir = "") }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, error = e.message ?: "扫描失败") }
                return@launch
            }
            DeepScan.save(app, result)
            lastLoad = System.currentTimeMillis()
            emit(result.items, Source.DEEP, result.generatedAt, result.truncated, 0)
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

    private fun emit(items: List<FileItem>, source: Source, at: Long, truncated: Boolean, rawRows: Int) {
        val buckets = CatalogPolicy.buckets(items)
        _state.update {
            it.copy(
                items = items,
                buckets = buckets,
                generatedAt = at,
                source = source,
                truncated = truncated,
                rawRows = rawRows,
                dropped = if (rawRows > 0) rawRows - items.size else it.dropped,
            )
        }
    }

    private fun mediaUri(id: Long): String = runCatching {
        ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id).toString()
    }.getOrDefault("")

    private fun queryMedia(ctx: Context): List<MediaRow> {
        val out = ArrayList<MediaRow>(4096)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
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
            // 注意：QUERY_ARG_SQL_SORT_ORDER 的契约类型是 String，不是 String[]。
            // 传错类型时 provider 会静默忽略排序，配合 LIMIT 会取到最旧的行。
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, CatalogPolicy.LIMIT + 1)
        }
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"), proj, args, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val name = c.getString(iName) ?: path.substringAfterLast('/')
                out.add(
                    MediaRow(
                        id = c.getLong(iId),
                        path = path,
                        name = name,
                        size = c.getLong(iSize),
                        // MediaStore 的 DATE_MODIFIED 单位是秒
                        mtimeMs = c.getLong(iMod) * 1000L,
                        mime = c.getString(iMime) ?: "",
                    )
                )
                // 多取一行用于判定“是否还有更多”；API 26-28 的旧 MediaProvider 不认
                // QUERY_ARG_LIMIT，这里同样只读到 LIMIT+1 行就停，避免拉全表。
                if (out.size > CatalogPolicy.LIMIT) break
            }
        }
        return out
    }
}
