package com.landrop.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landrop.transfer.core.Engine
import com.landrop.transfer.model.DeviceInfo
import com.landrop.transfer.model.SpeedUi
import java.util.Locale

@Composable
fun SpeedScreen(speed: SpeedUi, devices: List<DeviceInfo>, onNeedDevice: () -> Unit) {
    val sel = devices.firstOrNull { it.id == (Engine.selectedId ?: "") } ?: devices.firstOrNull()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "网速测试",
            color = LdColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        )
        Text(
            "测量本机与对端之间真实可用的 TCP 吞吐。测试会建立多条并发连接持续打流，" +
                "仅走局域网，不消耗移动数据。",
            color = LdColors.TextFaint,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))

        // 目标设备
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("对端", color = LdColors.TextFaint, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            if (devices.isEmpty()) {
                Text("未发现设备", color = LdColors.Amber, fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.take(3).forEach { d ->
                        val s = d.id == sel?.id
                        Text(
                            d.name,
                            color = if (s) Color.White else LdColors.TextDim,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    if (s) LdColors.AccentDim else LdColors.Surface2,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { Engine.select(d.id) }
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 方向
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "上传", 1 to "下载", 2 to "双向").forEach { (mode, label) ->
                val s = speed.dir == mode
                Text(
                    label,
                    color = if (s) Color.White else LdColors.TextDim,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .background(
                            if (s) LdColors.Accent else LdColors.Surface2,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { Engine.setSpeedDir(mode) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .background(LdColors.Surface, CardShape)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val show = if (speed.running) speed.currentMbps else speed.avgMbps
            Text(
                String.format(Locale.US, "%.1f", show),
                color = LdColors.Text,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("Mbps", color = LdColors.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            SpeedChart(speed.samples, Modifier.fillMaxWidth().height(96.dp))
            Spacer(Modifier.height(10.dp))
            if (speed.peakMbps > 0) {
                Text(
                    String.format(
                        Locale.US,
                        "平均 %.1f · 峰值 %.1f · 中位 %.1f",
                        speed.avgMbps, speed.peakMbps, speed.p50Mbps
                    ),
                    color = LdColors.TextDim,
                    fontSize = 11.5.sp,
                )
            } else if (speed.err.isNotEmpty()) {
                Text(speed.err, color = LdColors.Red, fontSize = 11.5.sp)
            } else {
                Text("点击开始测速", color = LdColors.TextFaint, fontSize = 11.5.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (speed.running) {
                    Engine.stopSpeed()
                } else if (devices.isEmpty()) {
                    onNeedDevice()
                } else {
                    Engine.startSpeed(speed.dir)
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (speed.running) LdColors.Surface3 else LdColors.Accent
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (speed.running) "测试中…（点击停止）" else "开始测速", fontSize = 14.sp)
        }
    }
}

@Composable
private fun SpeedChart(samples: List<Double>, modifier: Modifier = Modifier) {
    Box(
        modifier.background(LdColors.Surface2, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (samples.size < 2) {
            Text(
                if (samples.isEmpty()) "—" else "采样中…",
                color = LdColors.TextFaint,
                fontSize = 11.sp,
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val maxV = (samples.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
            val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
            val path = Path()
            samples.forEachIndexed { i, v ->
                val x = stepX * i
                val y = size.height - (v / maxV).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = LdColors.Accent,
                style = Stroke(width = 2.0f),
            )
            // 填充
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, color = LdColors.Accent.copy(alpha = 0.15f))
            drawLine(
                color = LdColors.Border,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
        Text(
            String.format(Locale.US, "峰值 %.0f", samples.maxOrNull() ?: 0.0),
            color = LdColors.TextFaint,
            fontSize = 9.5.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}
