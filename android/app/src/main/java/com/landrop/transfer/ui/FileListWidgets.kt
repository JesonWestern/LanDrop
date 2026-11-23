package com.landrop.transfer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.landrop.transfer.model.FileItem
import com.landrop.transfer.model.FileKind
import com.landrop.transfer.storage.FileOpener
import com.landrop.transfer.storage.OpenResult

/** 文件页与分类页共用的列表行 / 标题 / 发送条 / 授权卡片。 */

internal const val ROW_HEIGHT_DP = 60

/** 紧凑行：44dp，比常规行矮 16dp，同屏能多放约 1/3 的行。 */
internal const val COMPACT_ROW_HEIGHT_DP = 44

/** 网格单元缩略图边长（96dp ≈ 3 列满宽 + 间距）。 */
internal const val GRID_TILE_DP = 96

internal const val GUTTER_WIDTH_DP = 56

internal fun toggle(s: Set<String>, path: String): Set<String> =
    if (s.contains(path)) s - path else s + path

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        color = LdColors.TextFaint,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 列表左侧缩略图槽位：图片/APK 用缩略图，其余保持手绘图标。
 *
 * @param targetPx 解码目标像素（列表 128，网格 256）。必须传进 LangEffect/remember 的
 *   key，否则切视图时旧尺寸的缩略图会先画出来再被替换，出现一次闪动。
 */
@Composable
internal fun FileThumb(item: FileItem, size: Dp = 40.dp, targetPx: Int = ThumbCache.TARGET_PX) {
    val ctx = LocalContext.current
    var bmp by remember(item.path, item.mtime, item.size, targetPx) {
        mutableStateOf(ThumbCache.cached(item, targetPx))
    }
    LaunchedEffect(item.path, item.mtime, item.size, targetPx) {
        if (bmp == null && ThumbCache.shouldLoad(item, targetPx)) {
            bmp = ThumbCache.load(ctx, item, targetPx)
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(LdColors.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FileGlyph(
                isDir = item.isDir,
                tint = if (item.isDir) LdColors.Accent else kindColor(item.kind),
                modifier = Modifier.size(22.dp),
                kind = item.kind,
            )
        }
    }
}

/**
 * 文件/目录行。
 *
 * 点击语义（1.2 起）：正文区 = 打开文件 / 进入目录；右侧 56dp 选择区 = 切换选中。
 * 选择区整块可点（远大于 22dp 圆圈），左缘有 1dp 分割线标明边界，便于后续拖选。
 */
@Composable
internal fun FileRow(
    item: FileItem,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    compact: Boolean = false,
) {
    // 颜色要在组合里取好：背景色表达式含 @Composable getter，不能在 lambda 里延迟求值
    val rowBg = if (checked) LdColors.Accent.copy(alpha = 0.06f) else Color.Transparent
    val rowHeight = if (compact) COMPACT_ROW_HEIGHT_DP else ROW_HEIGHT_DP
    val thumbSize = if (compact) 30.dp else 40.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight.dp)
            .background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onOpen)
                .padding(
                    start = 16.dp,
                    end = 8.dp,
                    top = if (compact) 2.dp else 6.dp,
                    bottom = if (compact) 2.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumb(item, size = thumbSize)
            Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = LdColors.Text,
                    fontSize = if (compact) 13.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 紧凑视图刻意不画副标题：一行只留文件名，同屏能多放约 1/3
                if (!compact) {
                    val sub = when {
                        item.isDir -> item.childInfo
                        else -> fmtBytes(item.size) + " · " + fmtTime(item.mtime)
                    }
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            color = LdColors.TextFaint,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        SelectionGutter(item.name, checked, onToggle)
    }
}

/** 右侧选择区：56dp 宽、撑满行高，是圆圈命中范围的 2.5 倍。 */
@Composable
internal fun SelectionGutter(name: String, checked: Boolean, onToggle: () -> Unit) {
    // 绘制 lambda 不是组合作用域，颜色必须先在组合里取出来
    val dividerColor = LdColors.Border
    val fill = LdColors.Accent
    val ring = LdColors.Border2
    // 浅色主题下 Accent 可能偏浅，勾必须用 onAccent 才看得见（白底白勾是旧的 bug）
    val checkTint = LdColors.OnAccent
    Box(
        Modifier
            .width(GUTTER_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(LdColors.Surface2.copy(alpha = 0.30f))
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, 12.dp.toPx()),
                    end = Offset(0f, size.height - 12.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .toggleable(value = checked, role = Role.Checkbox) { onToggle() }
            .semantics {
                stateDescription = if (checked) "$name 已选择" else "$name 未选择"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (checked) fill else Color.Transparent, CircleShape)
                .border(1.75.dp, if (checked) fill else ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = checkTint, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun SendBar(
    count: Int,
    totalBytes: Long,
    deviceName: String,
    busy: Boolean = false,
    onSend: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onInvert: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LdColors.Surface2)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 $count 项",
                    color = LdColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (totalBytes > 0) {
                    Text(
                        " · " + fmtBytes(totalBytes),
                        color = LdColors.TextFaint,
                        fontSize = 11.sp,
                    )
                }
            }
            // 全选 / 反选 / 清空 / 退出：作用域 = 当前页签的全部可见行
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSelectAll != null) SendBarAction("全选", onSelectAll)
                if (onInvert != null) SendBarAction("反选", onInvert)
                if (onClear != null) SendBarAction("清空", onClear)
                if (onDone != null) SendBarAction("退出多选", onDone)
            }
        }
        Button(
            onClick = onSend,
            enabled = !busy && count > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = LdColors.Accent,
                // 浅色主题下强调色偏浅，onAccent 才是可读前景
                contentColor = LdColors.OnAccent,
                disabledContainerColor = LdColors.AccentDim,
                disabledContentColor = LdColors.OnAccent,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = LdColors.OnAccent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text("准备中…", fontSize = 13.sp)
            } else {
                Text("发送到 $deviceName", fontSize = 13.sp)
            }
        }
    }
}

/** 发送条里的文字操作按钮（无底色，靠间距分组）。 */
@Composable
private fun SendBarAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = LdColors.Accent,
        fontSize = 11.5.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 14.dp, top = 4.dp, bottom = 2.dp),
    )
}

/**
 * 网格视图的一格：96dp 圆角缩略图 + 下方一行文件名。
 *
 * 多选态下的**右上角圆圈**是唯一选择入口（不像列表有 56dp gutter）：
 * 网格格间没有足够空隙放 gutter，圆圈因此做成 18dp 的圆形浮层，点它切换选中。
 */
@Composable
internal fun FileGridTile(
    item: FileItem,
    checked: Boolean,
    selectMode: Boolean,
    onToggle: () -> Unit,
    onEnterSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val selBg = LdColors.Accent.copy(alpha = 0.10f)
    val unselBg = Color.Transparent
    val ringColor = LdColors.Border
    val checkedRing = LdColors.Accent
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) selBg else unselBg)
            .pointerInput(selectMode) {
                // 长按 = 进入多选态并选中当前格；短按 = 多选态下切换、否则打开。
                // 不用 combinedClickable：它在旧版 foundation 里是实验 API。
                detectTapGestures(
                    onTap = { if (selectMode) onToggle() else onOpen() },
                    onLongPress = {
                        if (!selectMode) onEnterSelect()
                        onToggle()
                    },
                )
            }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(GRID_TILE_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (checked) checkedRing else ringColor, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            FileThumb(item, size = GRID_TILE_DP.dp, targetPx = ThumbCache.GRID_TARGET_PX)
            if (selectMode) {
                val checkBg = if (checked) checkedRing else Color.Transparent
                val checkEdge = if (checked) checkedRing else LdColors.Border2
                val tint = LdColors.OnAccent
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .background(checkBg, CircleShape)
                        .border(1.2.dp, checkEdge, CircleShape)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) Text("✓", color = tint, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            item.name,
            color = LdColors.Text,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
internal fun PermissionCard(legacy: Boolean, onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(LdColors.Surface, CardShape)
            .padding(18.dp)
    ) {
        Text(
            if (legacy) "需要「存储读写」权限" else "需要「全部文件访问」权限",
            color = LdColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (legacy) {
                "内网快传需要像文件管理器一样浏览手机里的文件，才能让你选择任意文件或整个文件夹发送。\n\n" +
                    "请在接下来的系统弹窗里允许访问存储。"
            } else {
                "内网快传需要像文件管理器一样浏览手机里的文件，才能让你选择任意文件或整个文件夹发送。\n\n" +
                    "在接下来的设置页里找到「内网快传」，打开「允许管理所有文件」。"
            },
            color = LdColors.TextDim,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = LdColors.Accent),
            shape = RoundedCornerShape(10.dp),
        ) { Text(if (legacy) "去授权" else "去设置") }
    }
}

/** 「去授权」：R+ 跳全部文件访问设置页；旧系统直接弹 READ/WRITE 运行时权限。 */
@Composable
internal fun rememberGrantStorageAccess(onResult: () -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    return remember(ctx, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:${ctx.packageName}")
                runCatching { ctx.startActivity(i) }
            } else {
                val perms = buildList {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }.filter {
                    ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
                }
                if (perms.isNotEmpty()) runCatching { launcher.launch(perms.toTypedArray()) }
            }
        }
    }
}

internal fun isLegacyStorage(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

/** 用系统默认应用打开；所有失败都变成一句用户能看懂的提示。 */
internal fun openFile(ctx: Context, path: String, mime: String? = null, contentUri: String = "") {
    when (val r = FileOpener.open(ctx, path, mime, contentUri.ifEmpty { null })) {
        OpenResult.Started -> Unit
        OpenResult.NoApp -> userToast(ctx, "没有可以打开该文件的应用")
        OpenResult.NotExist -> userToast(ctx, "文件已不存在，可能已被移动或删除")
        OpenResult.NeedInstallPermission -> {
            userToast(ctx, "请先允许「安装未知应用」，返回后再点一次")
            FileOpener.installSettingsIntent(ctx)?.let { i ->
                if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(i) }
            }
        }
        is OpenResult.Failed -> userToast(ctx, r.message)
        is OpenResult.NeedsChoice -> OpenChooser.offer(r.intent, r.choices)
    }
}

internal fun userToast(ctx: Context, msg: String) {
    runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}

/** 列表为空时的提示行。 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = LdColors.TextFaint, fontSize = 13.sp)
    }
}

/** 缩略图/图标 + 文件类型色（供传输页等非标准行使用）。 */
@Composable
internal fun KindThumb(kind: FileKind, isDir: Boolean = false, size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(LdColors.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        FileGlyph(
            isDir = isDir,
            tint = if (isDir) LdColors.Accent else kindColor(kind),
            modifier = Modifier.size(18.dp),
            kind = kind,
        )
    }
}
