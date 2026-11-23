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

/** 列表左侧 40dp 缩略图槽位：图片/APK 用缩略图，其余保持手绘图标。 */
@Composable
internal fun FileThumb(item: FileItem, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    var bmp by remember(item.path, item.mtime, item.size) { mutableStateOf(ThumbCache.cached(item)) }
    LaunchedEffect(item.path, item.mtime, item.size) {
        if (bmp == null && ThumbCache.shouldLoad(item)) bmp = ThumbCache.load(ctx, item)
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .background(if (checked) LdColors.Accent.copy(alpha = 0.06f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumb(item)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = LdColors.Text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
        SelectionGutter(item.name, checked, onToggle)
    }
}

/** 右侧选择区：56dp 宽、撑满行高，是圆圈命中范围的 2.5 倍。 */
@Composable
internal fun SelectionGutter(name: String, checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .width(GUTTER_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(LdColors.Surface2.copy(alpha = 0.30f))
            .drawBehind {
                drawLine(
                    color = LdColors.Border,
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
                .background(if (checked) LdColors.Accent else Color.Transparent, CircleShape)
                .border(1.75.dp, if (checked) LdColors.Accent else LdColors.Border2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 12.sp)
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LdColors.Surface2)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "已选 $count 项",
                color = LdColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (totalBytes > 0) {
                Text(fmtBytes(totalBytes), color = LdColors.TextFaint, fontSize = 11.sp)
            }
        }
        Button(
            onClick = onSend,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = LdColors.Accent,
                disabledContainerColor = LdColors.AccentDim,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
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
