package com.landrop.transfer.storage

import android.content.Context
import android.util.Log
import com.landrop.transfer.model.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文件页的数据层：带 TTL 的内存缓存 + 后台预热。
 *
 * 解决的核心问题：**从其他 tab 切回文件页会卡顿**。原实现每次进入都 `LaunchedEffect`
 * 同步等 `listDir` + `recentFiles`（MediaStore 查询在低端机上要几十到几百毫秒），
 * 且这些结果用完即弃。
 *
 * 现在的策略：
 *  - `warm()` 在 Activity `onCreate` 就发起预热（根目录 + 最近文件）；
 *  - 首帧用 [cachedDir] / [cachedRecent] **同步**取已有结果立即出 UI；
 *  - 数据过期才在后台刷新，刷新完成后 UI 再自然更新。
 *
 * 缓存的失效：接收落盘（`DownloadSink`）与外部变更会调 [invalidate]；
 * 目录缓存按路径粒度失效，最近文件缓存整体失效。
 */
object FileRepo {

    private const val TAG = "LanDropRepo"

    /** 目录列表 TTL：足够掩盖来回切 tab，又不至于让新建文件迟迟不出现。 */
    private const val DIR_TTL_MS = 8_000L

    /** 最近文件 TTL：MediaStore 变更本身有延迟，30 秒够用。 */
    private const val RECENT_TTL_MS = 30_000L

    /** 最近文件上限。1.2 只有 12 条，太少；200 条约 10 屏，够翻。 */
    const val RECENT_LIMIT = 200

    data class Entry(val items: List<FileItem>, val at: Long)

    /** 性能统计，供 `LanDropPerf` 日志与验收断言使用。 */
    data class Stats(
        val dirHits: Int = 0,
        val dirMisses: Int = 0,
        val recentHits: Int = 0,
        val recentMisses: Int = 0,
        val lastLoadMs: Long = 0,
        val lastLoadKind: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirCache = ConcurrentHashMap<String, Entry>()
    private val recentCount = AtomicInteger(0)
    private val recentMiss = AtomicInteger(0)
    private val dirHit = AtomicInteger(0)
    private val dirMiss = AtomicInteger(0)

    @Volatile private var recentCache: Entry? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var warmed = false
    @Volatile private var lastLoadMs = 0L

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** 预热：Activity 启动时调一次即可，重复调用无副作用。 */
    fun warm(ctx: Context) {
        if (warmed) return
        warmed = true
        val app = ctx.applicationContext
        appCtx = app
        scope.launch {
            runCatching {
                val root = FsBrowser.root()
                // 根目录与最近文件一起预热，这样首次进文件页就能立刻出内容
                loadDir(app, root)
                loadRecent(app)
                Log.i(TAG, "warm done dir=${dirCache.size} recent=${recentCache?.items?.size}")
            }.onFailure { Log.w(TAG, "warm: ${it.message}") }
        }
    }

    /** 同步取缓存；过期或不存在返回 null（调用方据此决定是否显示"加载中"）。 */
    fun cachedDir(path: String, nowMs: Long = System.currentTimeMillis()): List<FileItem>? {
        val e = dirCache[path] ?: return null
        return if (nowMs - e.at <= DIR_TTL_MS) e.items else null
    }

    fun cachedRecent(nowMs: Long = System.currentTimeMillis()): List<FileItem>? {
        val e = recentCache ?: return null
        return if (nowMs - e.at <= RECENT_TTL_MS) e.items else null
    }

    /** 读取目录（会更新缓存）。无缓存时耗时即真实 IO 时间。 */
    fun dir(ctx: Context, path: String, force: Boolean = false): List<FileItem> {
        val now = System.currentTimeMillis()
        if (!force) {
            cachedDir(path, now)?.let {
                dirHit.incrementAndGet()
                pushStats("dir-hit")
                return it
            }
        }
        dirMiss.incrementAndGet()
        val t0 = System.currentTimeMillis()
        val items = FsBrowser.listDir(File(path))
        dirCache[path] = Entry(items, System.currentTimeMillis())
        lastLoadMs = System.currentTimeMillis() - t0
        pushStats("dir")
        return items
    }

    /** 读取最近文件（走 MediaStore，可能较慢）。 */
    fun recent(ctx: Context, limit: Int = RECENT_LIMIT, force: Boolean = false): List<FileItem> {
        val now = System.currentTimeMillis()
        if (!force) {
            cachedRecent(now)?.let {
                recentCount.incrementAndGet()
                pushStats("recent-hit")
                return it
            }
        }
        recentMiss.incrementAndGet()
        val t0 = System.currentTimeMillis()
        val items = FsBrowser.recentFiles(ctx, limit)
        recentCache = Entry(items, System.currentTimeMillis())
        lastLoadMs = System.currentTimeMillis() - t0
        pushStats("recent")
        return items
    }

    /** 目录级失效：接收落盘/删除后只失效受影响的目录。 */
    fun invalidateDir(path: String) {
        dirCache.remove(path)
        // 父目录的子项计数也变了
        File(path).parent?.let { dirCache.remove(it) }
    }

    /** 全局失效：媒体库变更、接收完成后调用。 */
    fun invalidate() {
        dirCache.clear()
        recentCache = null
    }

    private fun loadDir(app: Context, dir: File) {
        runCatching { dir(app, dir.absolutePath, force = true) }
    }

    private fun loadRecent(app: Context) {
        runCatching { recent(app, RECENT_LIMIT, force = true) }
    }

    private fun pushStats(kind: String) {
        _stats.value = Stats(
            dirHits = dirHit.get(),
            dirMisses = dirMiss.get(),
            recentHits = recentCount.get(),
            recentMisses = recentMiss.get(),
            lastLoadMs = lastLoadMs,
            lastLoadKind = kind,
        )
    }

    /** 清空统计（验收脚本每次进页面前调用，便于断言单次行为）。 */
    fun resetStats() {
        dirHit.set(0); dirMiss.set(0); recentCount.set(0); recentMiss.set(0)
        lastLoadMs = 0L
        _stats.value = Stats()
    }
}
