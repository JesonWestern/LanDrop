package com.landrop.transfer.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landrop.transfer.model.FileItem

/** 文件页与分类页共用的列表行 / 标题 / 发送条 / 授权卡片。 */

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
            .clickable {
                if (item.isDir) onOpen() else onToggle()
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileGlyph(
            isDir = item.isDir,
            tint = if (item.isDir) LdColors.Accent else kindColor(item.kind),
            modifier = Modifier.size(22.dp),
            kind = item.kind,
        )
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
                Text(sub, color = LdColors.TextFaint, fontSize = 11.sp, maxLines = 1)
            }
        }
        Box(
            Modifier
                .size(22.dp)
                .background(
                    if (checked) LdColors.Accent else Color.Transparent,
                    CircleShape
                )
                .border(
                    1.5.dp,
                    if (checked) LdColors.Accent else LdColors.Border2,
                    CircleShape
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun SendBar(
    count: Int,
    totalBytes: Long,
    deviceName: String,
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
            colors = ButtonDefaults.buttonColors(containerColor = LdColors.Accent),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("发送到 $deviceName", fontSize = 13.sp)
        }
    }
}

@Composable
internal fun PermissionCard(onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(LdColors.Surface, CardShape)
            .padding(18.dp)
    ) {
        Text(
            "需要「全部文件访问」权限", color = LdColors.Text, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "内网快传需要像文件管理器一样浏览手机里的文件，才能让你选择任意文件或整个文件夹发送。\n\n" +
                "在接下来的设置页里找到「内网快传」，打开「允许管理所有文件」。",
            color = LdColors.TextDim,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = LdColors.Accent),
            shape = RoundedCornerShape(10.dp),
        ) { Text("去授权") }
        Spacer(Modifier.height(8.dp))
        Text(
            "当前目录：${Environment.getExternalStorageDirectory().absolutePath}",
            color = LdColors.TextFaint,
            fontSize = 11.sp,
        )
    }
}
