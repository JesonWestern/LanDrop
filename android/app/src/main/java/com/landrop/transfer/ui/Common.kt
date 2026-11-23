package com.landrop.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.landrop.transfer.model.FileKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtBytes(n: Long): String {
    if (n < 1024) return "$n B"
    var v = n.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (v >= 1024 && i < 4) {
        v /= 1024.0
        i++
    }
    return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
    else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtSpeed(bps: Double): String = when {
    bps <= 0 -> "--"
    bps >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bps / 1024 / 1024)
    bps >= 1024 -> String.format(Locale.US, "%.0f KB/s", bps / 1024)
    else -> String.format(Locale.US, "%.0f B/s", bps)
}

fun fmtEta(remainBytes: Long, bps: Double): String {
    if (bps <= 1 || remainBytes <= 0) return ""
    val sec = remainBytes / bps
    return when {
        sec < 1 -> "即将完成"
        sec < 60 -> "剩 ${sec.toInt()} 秒"
        sec < 3600 -> "剩 ${(sec / 60).toInt()} 分"
        sec > 36000 -> ""
        else -> String.format(Locale.US, "剩 %.1f 小时", sec / 3600)
    }
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return ""
    val f = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return f.format(Date(ms))
}

/**
 * 文件类型 → 图标配色。颜色只作辅助，灰度下靠剪影区分。
 *
 * 注意：必须是 `@Composable`（主题色改为运行期取色后，这里要读当前调色板）。
 * 所有调用点原本就在组合作用域内，因此不需要改动调用方式。
 */
@Composable
fun kindColor(k: FileKind): Color = when (k) {
    FileKind.IMAGE -> LdColors.Green
    FileKind.VIDEO -> LdColors.Purple
    FileKind.AUDIO -> LdColors.Orange
    FileKind.DOC -> LdColors.Accent
    FileKind.ARCHIVE -> LdColors.Amber
    FileKind.APK -> LdColors.Cyan
    FileKind.OTHER -> LdColors.TextDim
}

/**
 * 手绘文件图标。不依赖 material-icons（本机离线缓存里没有该依赖）。
 *
 * 兼容性：`kind` 是**追加在末尾的带默认值参数**，现有 `FileGlyph(isDir = .., tint = ..)` 调用点无需改动。
 */
@Composable
fun FileGlyph(
    isDir: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp),
    kind: FileKind = FileKind.OTHER,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (isDir) drawFolder(tint, w, h) else drawFileGlyph(kind, tint, w, h)
    }
}

/** 便捷重载：只用类型，颜色自动取。 */
@Composable
fun FileGlyph(
    kind: FileKind,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = kindColor(kind),
) {
    Canvas(modifier) {
        drawFileGlyph(kind, tint, size.width, size.height)
    }
}

private fun DrawScope.drawFolder(tint: Color, w: Float, h: Float) {
    val p = Path()
    p.moveTo(w * 0.10f, h * 0.30f)
    p.lineTo(w * 0.44f, h * 0.30f)
    p.lineTo(w * 0.52f, h * 0.40f)
    p.lineTo(w * 0.90f, h * 0.40f)
    p.lineTo(w * 0.90f, h * 0.80f)
    p.lineTo(w * 0.10f, h * 0.80f)
    p.close()
    drawPath(p, tint)
}

private fun DrawScope.drawFileGlyph(kind: FileKind, tint: Color, w: Float, h: Float) {
    when (kind) {
        FileKind.IMAGE -> drawImageGlyph(tint, w, h)
        FileKind.VIDEO -> drawVideoGlyph(tint, w, h)
        FileKind.AUDIO -> drawAudioGlyph(tint, w, h)
        FileKind.DOC -> drawDocGlyph(tint, w, h)
        FileKind.ARCHIVE -> drawArchiveGlyph(tint, w, h)
        FileKind.APK -> drawApkGlyph(tint, w, h)
        FileKind.OTHER -> drawPlainGlyph(tint, w, h)
    }
}

/** 相框：太阳 + 山峦 */
private fun DrawScope.drawImageGlyph(tint: Color, w: Float, h: Float) {
    drawFrame(tint, w, h)
    drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.34f, h * 0.34f))
    val m = Path()
    m.moveTo(w * 0.20f, h * 0.74f)
    m.lineTo(w * 0.43f, h * 0.47f)
    m.lineTo(w * 0.59f, h * 0.64f)
    m.lineTo(w * 0.69f, h * 0.55f)
    m.lineTo(w * 0.82f, h * 0.74f)
    m.close()
    drawPath(m, tint)
}

/** 播放三角 */
private fun DrawScope.drawVideoGlyph(tint: Color, w: Float, h: Float) {
    drawFrame(tint, w, h)
    val p = Path()
    p.moveTo(w * 0.43f, h * 0.32f)
    p.lineTo(w * 0.69f, h * 0.50f)
    p.lineTo(w * 0.43f, h * 0.68f)
    p.close()
    drawPath(p, tint)
}

/** 音符 */
private fun DrawScope.drawAudioGlyph(tint: Color, w: Float, h: Float) {
    drawOval(
        color = tint,
        topLeft = Offset(w * 0.22f, h * 0.60f),
        size = Size(w * 0.22f, h * 0.18f),
    )
    drawLine(tint, Offset(w * 0.44f, h * 0.69f), Offset(w * 0.44f, h * 0.24f),
        strokeWidth = w * 0.075f)
    drawLine(tint, Offset(w * 0.44f, h * 0.24f), Offset(w * 0.70f, h * 0.33f),
        strokeWidth = w * 0.075f)
}

/** 折角纸：折角是它与「其它」的辨识点 */
private fun DrawScope.drawDocGlyph(tint: Color, w: Float, h: Float) {
    val page = Path()
    page.moveTo(w * 0.24f, h * 0.10f)
    page.lineTo(w * 0.60f, h * 0.10f)
    page.lineTo(w * 0.80f, h * 0.30f)
    page.lineTo(w * 0.80f, h * 0.90f)
    page.lineTo(w * 0.24f, h * 0.90f)
    page.close()
    drawPath(page, tint, style = Stroke(width = w * 0.085f))

    val fold = Path()
    fold.moveTo(w * 0.60f, h * 0.10f)
    fold.lineTo(w * 0.60f, h * 0.30f)
    fold.lineTo(w * 0.80f, h * 0.30f)
    fold.close()
    drawPath(fold, tint)

    drawLine(tint, Offset(w * 0.36f, h * 0.50f), Offset(w * 0.68f, h * 0.50f),
        strokeWidth = w * 0.07f)
    drawLine(tint, Offset(w * 0.36f, h * 0.66f), Offset(w * 0.60f, h * 0.66f),
        strokeWidth = w * 0.07f)
}

/** 压缩包：箱体 + 盖线 + 拉链齿 */
private fun DrawScope.drawArchiveGlyph(tint: Color, w: Float, h: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.18f, h * 0.26f),
        size = Size(w * 0.64f, h * 0.62f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = Stroke(width = w * 0.085f),
    )
    drawLine(tint, Offset(w * 0.18f, h * 0.41f), Offset(w * 0.82f, h * 0.41f),
        strokeWidth = w * 0.085f)
    for (i in 0..2) {
        val y = h * (0.52f + i * 0.12f)
        drawLine(tint, Offset(w * 0.44f, y), Offset(w * 0.56f, y), strokeWidth = w * 0.08f)
    }
}

/** 安装包：方框 + 下箭头 */
private fun DrawScope.drawApkGlyph(tint: Color, w: Float, h: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.20f, h * 0.18f),
        size = Size(w * 0.60f, h * 0.64f),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        style = Stroke(width = w * 0.085f),
    )
    drawLine(tint, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.54f),
        strokeWidth = w * 0.085f)
    val head = Path()
    head.moveTo(w * 0.40f, h * 0.51f)
    head.lineTo(w * 0.50f, h * 0.64f)
    head.lineTo(w * 0.60f, h * 0.51f)
    head.close()
    drawPath(head, tint)
    drawLine(tint, Offset(w * 0.34f, h * 0.72f), Offset(w * 0.66f, h * 0.72f),
        strokeWidth = w * 0.07f)
}

/** 兜底：单页 + 三横线 */
private fun DrawScope.drawPlainGlyph(tint: Color, w: Float, h: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.20f, h * 0.10f),
        size = Size(w * 0.60f, h * 0.80f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = Stroke(width = w * 0.09f),
    )
    for (i in 0..2) {
        val y = h * (0.38f + i * 0.16f)
        drawLine(tint, Offset(w * 0.32f, y), Offset(w * 0.68f, y), strokeWidth = w * 0.07f)
    }
}

private fun DrawScope.drawFrame(tint: Color, w: Float, h: Float) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.12f, h * 0.16f),
        size = Size(w * 0.76f, h * 0.68f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = Stroke(width = w * 0.085f),
    )
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(color, RoundedCornerShape(size / 2))
    )
}

val CardShape = RoundedCornerShape(14.dp)
