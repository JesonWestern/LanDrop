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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
fun FileGlyph(isDir: Boolean, tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (isDir) {
            val p = Path()
            p.moveTo(w * 0.10f, h * 0.30f)
            p.lineTo(w * 0.44f, h * 0.30f)
            p.lineTo(w * 0.52f, h * 0.40f)
            p.lineTo(w * 0.90f, h * 0.40f)
            p.lineTo(w * 0.90f, h * 0.80f)
            p.lineTo(w * 0.10f, h * 0.80f)
            p.close()
            drawPath(p, tint)
        } else {
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(w * 0.20f, h * 0.10f),
                size = Size(w * 0.60f, h * 0.80f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = Stroke(width = w * 0.09f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.20f, h * 0.10f),
                size = Size(w * 0.60f, h * 0.80f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = Stroke(width = w * 0.09f),
            )
            for (i in 0..2) {
                drawLine(
                    color = tint,
                    start = Offset(w * 0.32f, h * (0.38f + i * 0.16f)),
                    end = Offset(w * 0.68f, h * (0.38f + i * 0.16f)),
                    strokeWidth = w * 0.07f,
                )
            }
        }
    }
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
