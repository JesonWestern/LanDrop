package com.landrop.transfer.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.storage.FileRepo
import com.landrop.transfer.storage.FsBrowser
import com.landrop.transfer.storage.groupRecentByDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * 文件页的两个页签。
 *
 * 「最近」和「内部存储」分开，是因为两者是两种心智模型：
 * 前者回答"我最近动的文件在哪"，后者回答"目录树里有什么"。
 * 1.2 把最近文件塞进目录列表顶部，导致最近只剩 12 条、且和目录项混在一起没法扫读。
 */
private enum class FileTab(val id: String, val label: String) {
    RECENT("recent", "最近"),
    STORAGE("storage", "内部存储");

    companion object {
        fun of(id: String?): FileTab = if (id == STORAGE.id) STORAGE else RECENT
    }
}

/**
 * 「文件」页：最近 / 内部存储 两个页签 + 列表 / 紧凑 / 网格 三种视图。
 *
 * 1.3 的关键变化：
 *  - 页签分离：最近文件独享一页，条数提到 200，并按「今天 / 昨天 / M月d日」分段；
 *  - 三视图：列表（60dp）、紧凑（44dp）、网格（3 列 96dp），切换后写 Settings 持久化；
 *  - 拖选可取消：按在已选中的锚点上拖动 = 本次拖动为取消选中（[dragModeFor]）；
 *  - 发送条操作：全选 / 反选 / 清空，作用域 = 当前页签的全部可见行；
 *  - 首帧不阻塞：`FileRepo.cachedDir/cachedRecent` 在组合里同步取，取不到才显示
 *    "加载中…"，真实 IO 放 LaunchedEffect 后台跑；配合 FileRepo.warm() 预热，
 *    从别的页签切回来第一帧就有内容。
 *
 * 交互语义（内部存储页签与 1.2 一致）：
 *  - 点文件 → 系统默认应用打开；点目录 → 进入；
 *  - 右侧 56dp 选择区切换选中，按住上下拖动 = 区间选中/取消（按锚点当前状态决定方向）；
 *  - 系统返回手势：先清选择/退出多选，再回上一级目录，根目录时交给系统退出。
 */
@Composable
fun FileScreen(devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember { FsBrowser.root() }
    var permitted by remember { mutableStateOf(FsBrowser.hasAllFilesAccess(ctx)) }
    var curDirPath by rememberSaveable { mutableStateOf(root.absolutePath) }

    // 页签与视图都持久化：上次停在哪、用哪种视图，下次进来直接还原
    var tabName by rememberSaveable { mutableStateOf(FileTab.of(Engine.settings.fileTab).name) }
    val tab = remember(tabName) {
        FileTab.entries.firstOrNull { it.name == tabName } ?: FileTab.RECENT
    }
    var viewModeName by rememberSaveable {
        mutableStateOf(viewModeOf(Engine.settings.fileViewMode).name)
    }
    val viewMode = remember(viewModeName) { viewModeOf(viewModeName) }

    // ---- 首帧：只读内存缓存，绝不做 IO ----
    // 这两个 remember 在组合阶段同步执行，命中的是 FileRepo.warm()/上次访问留下的结果，
    // 因此"从别的页签切回来"的第一帧就能直接渲染出数据行。
    // 目录用恢复后的 curDirPath 取缓存：上次停在 /DCIM 时不能先闪一下根目录。
    val initialDirPath = remember { curDirPath }
    val cachedRecent0 = remember { FileRepo.cachedRecent() }
    val cachedDir0 = remember { FileRepo.cachedDir(initialDirPath) }
    val firstFrameAtNs = remember { System.nanoTime() }

    var recent by remember { mutableStateOf(cachedRecent0 ?: emptyList()) }
    var recentReady by remember { mutableStateOf(cachedRecent0 != null) }
    var items by remember { mutableStateOf(cachedDir0 ?: emptyList()) }
    var itemsReady by remember { mutableStateOf(cachedDir0 != null) }

    val selected = rememberStringSetState()
    var tick by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var showHint by rememberSaveable { mutableStateOf(!Engine.settings.selectionHintSeen) }

    /**
     * 显式多选态：一开始选择就常驻，直到返回/清空后退出。
     *
     * 不能只用"选中集合非空"来驱动发送条 —— 否则用户「清空」后条子整条消失，
     * 「全选」「反选」就再也点不到了（正是反馈里"缺少全选/取消选中"的成因）。
     *
     * 用 rememberSaveable 且初值看 selected：进程被杀恢复时若选中集合还在，
     * 多选态必须一起回来，否则会出现"行上有勾、发送条不见了"的割裂状态。
     */
    var selectMode by rememberSaveable { mutableStateOf(selected.value.isNotEmpty()) }

    val curDir = remember(curDirPath, root) {
        val f = File(curDirPath)
        if (f.isDirectory && isUnderRoot(f, root)) f else root
    }

    val grant = rememberGrantStorageAccess { tick++ }

    // 首帧耗时埋点：从进入组合到"已经拿到可渲染数据"（缓存命中即为 0 附近）
    LaunchedEffect(Unit) {
        val cached = cachedRecent0 != null || cachedDir0 != null
        val ms = (System.nanoTime() - firstFrameAtNs) / 1_000_000L
        Log.i("LanDropPerf", "fileTab firstDraw cached=$cached ms=$ms")
    }

    val owner = LocalLifecycleOwner.current
    // 首次进入的 ON_RESUME 紧跟在本页组合之后，此时缓存刚被首帧读过，不能立刻丢掉
    var sawResume by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permitted = FsBrowser.hasAllFilesAccess(ctx)
                // 首次 ON_RESUME 不清缓存：那会把 FileRepo.warm() 的预热结果白扔，
                // 反而让首帧退化成"加载中"。从别处真正"回来"时才失效重刷。
                if (sawResume) {
                    FileRepo.invalidate()
                    tick++
                } else {
                    sawResume = true
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // 后台刷新：首帧之后才跑，UI 已有旧数据（或"加载中"），不会卡住组合。
    // 只刷当前页签需要的那份数据：切到「最近」不读目录，切到「内部存储」不查 MediaStore。
    LaunchedEffect(tab, curDirPath, tick, permitted) {
        if (!permitted) return@LaunchedEffect
        val dirPath = curDirPath
        when (tab) {
            FileTab.RECENT -> {
                val rec = withContext(Dispatchers.IO) {
                    runCatching { FileRepo.recent(ctx, FileRepo.RECENT_LIMIT) }.getOrNull()
                }
                if (rec != null) {
                    recent = rec
                    recentReady = true
                }
            }
            FileTab.STORAGE -> {
                val dir = withContext(Dispatchers.IO) {
                    runCatching { FileRepo.dir(ctx, dirPath) }.getOrNull()
                }
                if (dir != null) {
                    items = dir
                    itemsReady = true
                }
            }
        }
    }

    fun leaveSelectMode() {
        selectMode = false
        selected.value = emptySet()
    }

    fun goDir(path: String) {
        leaveSelectMode()
        curDirPath = path
        // 目录切换也走同步缓存：进过的目录回退时瞬间出现内容
        val cached = FileRepo.cachedDir(path)
        items = cached ?: emptyList()
        itemsReady = cached != null
    }

    fun toggleRow(path: String) {
        selectMode = true
        selected.value = toggle(selected.value, path)
    }

    BackHandler(
        enabled = selectMode ||
            (tab == FileTab.STORAGE && curDir.absolutePath != root.absolutePath)
    ) {
        if (selectMode) {
            leaveSelectMode()
        } else {
            val parent = curDir.parentFile
            goDir(
                if (parent != null && isUnderRoot(parent, root)) parent.absolutePath
                else root.absolutePath
            )
        }
    }

    LaunchedEffect(selected.value) {
        if (selected.value.isNotEmpty() && showHint) {
            showHint = false
            Engine.settings.selectionHintSeen = true
        }
    }

    val selDevice = devices.firstOrNull { it.id == (Engine.selectedId ?: "") }
        ?: devices.firstOrNull()

    // 最近文件按日期分段（今天/昨天/M月d日），标题复用 SectionHeader
    val recentSections = remember(recent, recentReady) {
        if (!recentReady || recent.isEmpty()) emptyList()
        else groupRecentByDate(recent, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    // 平铺行模型：分区标题也占一个 index，拖选命中时跳过（path = null）。
    // 两个页签各算各的：AnimatedContent 过渡期间会把"旧页签"多画几帧，
    // 若共用一个 rows 就会看到旧面板里冒出另一页签的内容。
    val rowsRecent = remember(recent, recentReady, recentSections) {
        buildRowsForRecent(recentReady, recentSections)
    }
    val rowsStorage = remember(items, itemsReady) {
        buildRowsForStorage(itemsReady, items)
    }

    val byPath = remember(items, recent) { (items + recent).associateBy { it.path } }
    val selectedBytes = remember(selected.value, byPath) {
        selectionBytes(selected.value, byPath)
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // 拖选：锚点 index + 基线选中集合 + 模式。区间每次都从基线重算，
    // 这样"拖出去再拖回来"会恢复原状，不会留下拖过一半的残留。
    var dragAnchor by remember { mutableIntStateOf(-1) }
    var dragBase by remember { mutableStateOf(emptySet<String>()) }
    var dragMode by remember { mutableStateOf(DragMode.SELECT) }

    // 两个页签各算一份行路径表：拖选的 index 必须与"该面板自己的 rows"对齐
    val pathsRecent = remember(rowsRecent) { rowsRecent.map { rowPath(it) } }
    val pathsStorage = remember(rowsStorage) { rowsStorage.map { rowPath(it) } }
    // 发送条的 全选/反选 作用域 = 当前页签的全部可见行
    val rowPaths = if (tab == FileTab.RECENT) pathsRecent else pathsStorage

    fun openItem(item: FileItem) {
        if (item.isDir) {
            goDir(item.path)
        } else {
            openFile(ctx, item.path, item.mime, item.contentUri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        val animMs = if (LocalLdAnimations.current) 180 else 0

        // 页签在最顶上一行（左上角）：回答"页签放在页面左上角"的诉求
        FileTabHeader(
            tab = tab,
            viewMode = viewMode,
            onTab = {
                if (it != tab) {
                    tabName = it.name
                    Engine.settings.fileTab = it.id
                    // 页签署不同，避免把上一页的勾带到另一页造成误解
                    leaveSelectMode()
                    // 切到另一页签时也先吃同步缓存，别让用户看到一次"加载中…"
                    when (it) {
                        FileTab.RECENT -> if (!recentReady) {
                            FileRepo.cachedRecent()?.let { c ->
                                recent = c
                                recentReady = true
                            }
                        }
                        FileTab.STORAGE -> if (!itemsReady) {
                            FileRepo.cachedDir(curDirPath)?.let { c ->
                                items = c
                                itemsReady = true
                            }
                        }
                    }
                }
            },
            onCycleView = {
                val next = viewMode.next()
                viewModeName = next.name
                Engine.settings.fileViewMode = next.id
            },
        )

        DevicePickerRow(devices) { Engine.select(it) }

        if (!permitted) {
            PermissionCard(legacy = isLegacyStorage(), onGrant = grant)
            return@Column
        }

        if (tab == FileTab.STORAGE) {
            Breadcrumb(root = root, cur = curDir) { goDir(it.absolutePath) }
        }

        // 拖选提示只在能拖选的两种列表视图里出现（网格没有拖选）
        if (showHint && !selectMode && viewMode != FileViewMode.GRID) {
            Text(
                "点右侧圆圈多选；按住圆圈上下拖动可连续选中，从已选中的行开始拖则取消选中",
                color = LdColors.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab to viewMode,
                transitionSpec = {
                    fadeIn(tween(animMs)) togetherWith fadeOut(tween(animMs))
                },
                label = "filePane",
            ) { (paneTab, mode) ->
                // 每块面板用自己页签的行：过渡期间旧面板不会被新页签内容顶掉
                val paneRows = if (paneTab == FileTab.RECENT) rowsRecent else rowsStorage
                val panePaths = if (paneTab == FileTab.RECENT) pathsRecent else pathsStorage
                if (mode == FileViewMode.GRID) {
                    // 网格视图不做拖选（明确取舍）：
                    //  1. 网格是二维布局，LazyGridState.layoutInfo 的命中模型与 ListRow 的
                    //     一维行模型不同，硬做要维护两套口径；
                    //  2. 网格格间只有 8dp 间距，没有 56dp gutter 那种"不误触正文"的缓冲区；
                    //  3. 手机单手拖动跨格选中的命中精度差，误选率高。
                    // 因此网格多选 = 长按进入多选态 + 点每格右上角圆圈（或整格）。
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            count = paneRows.size,
                            key = { i -> paneRows[i].key },
                            span = { i ->
                                when (paneRows[i]) {
                                    is ListRow.Item -> GridItemSpan(1)
                                    else -> GridItemSpan(maxLineSpan)
                                }
                            },
                        ) { i ->
                            when (val row = paneRows[i]) {
                                is ListRow.Header -> SectionHeader(row.text)
                                is ListRow.Empty -> EmptyHint(row.text)
                                is ListRow.Item -> FileGridTile(
                                    item = row.item,
                                    checked = selected.value.contains(row.item.path),
                                    selectMode = selectMode,
                                    onEnterSelect = { selectMode = true },
                                    onToggle = { toggleRow(row.item.path) },
                                    onOpen = { openItem(row.item) },
                                )
                                ListRow.Bottom -> Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .gutterDragSelect(
                                listState = listState,
                                gutterWidth = GUTTER_WIDTH_DP.dp,
                                rows = { paneRows },
                                onDragStart = { idx, item ->
                                    // 锚点已选中 → 本次拖动是"取消选中"
                                    val mode = dragModeFor(
                                        selected.value.contains(item.item.path)
                                    )
                                    val base = selected.value
                                    dragMode = mode
                                    dragAnchor = idx
                                    dragBase = base
                                    selectMode = true
                                    selected.value =
                                        applyDragRange(base, panePaths, idx, idx, mode)
                                },
                                onDragOver = { idx, _ ->
                                    if (dragAnchor >= 0) {
                                        selected.value = applyDragRange(
                                            dragBase, panePaths, dragAnchor, idx, dragMode
                                        )
                                    }
                                },
                            ),
                    ) {
                        itemsIndexed(paneRows, key = { _, row -> row.key }) { _, row ->
                            when (row) {
                                is ListRow.Header -> SectionHeader(row.text)
                                is ListRow.Empty -> EmptyHint(row.text)
                                is ListRow.Item -> FileRow(
                                    item = row.item,
                                    checked = selected.value.contains(row.item.path),
                                    onToggle = { toggleRow(row.item.path) },
                                    onOpen = { openItem(row.item) },
                                    compact = mode == FileViewMode.COMPACT,
                                )
                                ListRow.Bottom -> Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // 多选态常驻：清空后仍可点「全选」「反选」，符合"缺少全选/取消选中"的反馈
        if (selectMode) {
            SendBar(
                count = selected.value.size,
                totalBytes = selectedBytes,
                deviceName = selDevice?.name ?: "…",
                busy = sending,
                onSelectAll = { selected.value = selectAll(rowPaths) },
                onInvert = { selected.value = invertSelection(selected.value, rowPaths) },
                onClear = { selected.value = emptySet() },
                onDone = { leaveSelectMode() },
                onSend = {
                    if (devices.isEmpty()) {
                        onNeedDevice()
                    } else {
                        sending = true
                        val paths = selected.value.toList()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Engine.send(paths) }
                            sending = false
                            if (ok) leaveSelectMode()
                            else userToast(ctx, "没有可发送的文件，或设备已离线")
                        }
                    }
                },
            )
        }
    }
}

/**
 * 顶部：左侧两个页签，右侧视图切换按钮（显示"下一个"视图名，点一下循环）。
 * 页签选中态用 Accent 下划线，风格与分类页的排序 chip 同一套配色。
 */
@Composable
private fun FileTabHeader(
    tab: FileTab,
    viewMode: FileViewMode,
    onTab: (FileTab) -> Unit,
    onCycleView: () -> Unit,
) {
    val nextLabel = viewMode.next().label
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            FileTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier
                        .clickable { onTab(t) }
                        .padding(end = 18.dp, top = 6.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        t.label,
                        color = if (on) LdColors.Accent else LdColors.TextFaint,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .size(width = if (on) 22.dp else 0.dp, height = 2.dp)
                            .background(
                                if (on) LdColors.Accent else Color.Transparent,
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
        // 视图切换：按钮上直接写"下一个视图"，点一次就切过去，不需要先弹菜单
        Text(
            nextLabel,
            color = LdColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier
                .background(LdColors.Surface2, RoundedCornerShape(16.dp))
                .border(1.dp, LdColors.Border, RoundedCornerShape(16.dp))
                .clickable { onCycleView() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DevicePickerRow(devices: List<DeviceInfo>, onSelect: (String) -> Unit) {
    val selId = (Engine.selectedId ?: devices.firstOrNull()?.id)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发送到", color = LdColors.TextFaint, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            if (devices.isEmpty()) {
                Text(
                    "未发现设备，请确认对方已打开内网快传",
                    color = LdColors.Amber,
                    fontSize = 12.sp,
                )
            } else {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    devices.forEach { d ->
                        val sel = d.id == selId
                        // 选中态底是 AccentDim，前景必须用 OnAccent，否则浅色主题下白底白字
                        val fg = if (sel) LdColors.OnAccent else LdColors.TextDim
                        Row(
                            Modifier
                                .background(
                                    if (sel) LdColors.AccentDim else LdColors.Surface2,
                                    RoundedCornerShape(20.dp)
                                )
                                .border(
                                    1.dp,
                                    if (sel) LdColors.Accent else LdColors.Border,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { onSelect(d.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(
                                if (d.plat == "android") LdColors.Green else LdColors.Accent,
                                6.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                d.name,
                                fontSize = 12.sp,
                                color = fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(root: File, cur: File, onGo: (File) -> Unit) {
    val rel = cur.absolutePath.removePrefix(root.absolutePath).trim('/')
    val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "内部存储",
            color = if (parts.isEmpty()) LdColors.Text else LdColors.Accent,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable { onGo(root) }
                .padding(vertical = 6.dp),
        )
        var acc = root
        for (p in parts) {
            Text("  ›  ", color = LdColors.TextFaint, fontSize = 12.sp)
            acc = File(acc, p)
            val target = acc
            val last = p == parts.last()
            Text(
                p,
                color = if (last) LdColors.Text else LdColors.Accent,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onGo(target) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * 从扁平行取文件路径；标题/空态/占位行返回 null（拖选与全选据此跳过）。
 * 抽成函数是为了让两份 rows 表用同一口径，避免退化成两套实现。
 */
private fun rowPath(row: ListRow): String? = (row as? ListRow.Item)?.item?.path

/** 父目录必须在根目录之内，防止返回/向上时退到 /storage/emulated 这种不可浏览的层级。 */
internal fun isUnderRoot(f: File, root: File): Boolean {
    val fp = f.absolutePath
    val rp = root.absolutePath
    return fp == rp || fp.startsWith(rp.trimEnd('/') + "/")
}

/** 「最近」页签的扁平行：日期分段标题 + 文件行。 */
private fun buildRowsForRecent(
    ready: Boolean,
    sections: List<com.landrop.transfer.storage.RecentSection>,
): List<ListRow> = buildList {
    when {
        !ready -> add(ListRow.Empty("加载中…"))
        sections.isEmpty() -> add(ListRow.Empty("还没有最近文件"))
        else -> sections.forEachIndexed { si, sec ->
            add(ListRow.Header(sec.title, "recent-$si"))
            // key 前缀带段号：同一文件理论上可能出现在两个段（不该有，但别让 key 冲突崩列表）
            sec.items.forEach { add(ListRow.Item(it, "rc$si:")) }
        }
    }
    add(ListRow.Bottom)
}

/** 「内部存储」页签的扁平行：文件夹在前、文件在后（与 FsBrowser.listDir 的排序一致）。 */
private fun buildRowsForStorage(ready: Boolean, items: List<FileItem>): List<ListRow> = buildList {
    when {
        !ready -> add(ListRow.Empty("加载中…"))
        items.isEmpty() -> add(ListRow.Empty("这个文件夹是空的"))
        else -> {
            val dirs = items.filter { it.isDir }
            val files = items.filter { !it.isDir }
            if (dirs.isNotEmpty()) {
                add(ListRow.Header("文件夹", "dirs"))
                dirs.forEach { add(ListRow.Item(it)) }
            }
            if (files.isNotEmpty()) {
                add(ListRow.Header("文件", "files"))
                files.forEach { add(ListRow.Item(it)) }
            }
        }
    }
    add(ListRow.Bottom)
}
