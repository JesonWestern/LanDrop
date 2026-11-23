package com.landrop.transfer.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 运行期主题：把 [LdColors] 从编译期常量改成**组合感知的 getter**。
 *
 * 这样做的关键原因：全工程有 180+ 处 `LdColors.Xxx` 调用点，改签名会波及每个文件；
 * 改成 `@Composable get()` 之后，调用点一行都不用动，且只在组合作用域内被读取。
 *
 * ⚠️ 因此**不能在非组合作用域读 LdColors**（例如 `Canvas`/`drawBehind` 的绘制 lambda、
 * 普通工具函数）。这类地方必须在组合里先取到局部变量再进 lambda，
 * 或改用带参数的颜色函数（如 `kindColor(kind)` 已声明为 `@Composable`）。
 */
object LdColors {
    val Bg: Color @Composable get() = LocalLdPalette.current.bg
    val Surface: Color @Composable get() = LocalLdPalette.current.surface
    val Surface2: Color @Composable get() = LocalLdPalette.current.surface2
    val Surface3: Color @Composable get() = LocalLdPalette.current.surface3
    val Border: Color @Composable get() = LocalLdPalette.current.border
    val Border2: Color @Composable get() = LocalLdPalette.current.border2
    val Text: Color @Composable get() = LocalLdPalette.current.text
    val TextDim: Color @Composable get() = LocalLdPalette.current.textDim
    val TextFaint: Color @Composable get() = LocalLdPalette.current.textFaint
    val Accent: Color @Composable get() = LocalLdPalette.current.accent
    val AccentDim: Color @Composable get() = LocalLdPalette.current.accentDim
    val OnAccent: Color @Composable get() = LocalLdPalette.current.onAccent
    val Green: Color @Composable get() = LocalLdPalette.current.green
    val Red: Color @Composable get() = LocalLdPalette.current.red
    val Amber: Color @Composable get() = LocalLdPalette.current.amber
    val Purple: Color @Composable get() = LocalLdPalette.current.purple
    val Orange: Color @Composable get() = LocalLdPalette.current.orange
    val Cyan: Color @Composable get() = LocalLdPalette.current.cyan
    val Hover: Color @Composable get() = LocalLdPalette.current.hover
    val Sel: Color @Composable get() = LocalLdPalette.current.sel

    /** 主题是否为浅色（状态栏图标、半透明叠加策略都按它分支）。 */
    val isLight: Boolean @Composable get() = LocalLdPalette.current.isLight
}

/** 当前生效的调色板。默认 graphite = 1.2 的观感。 */
internal val LocalLdPalette = staticCompositionLocalOf { presetOf(DEFAULT_PRESET_ID) }

/** 当前是否启用页面切换动画（设置项 + 系统"移除动画"双重生效）。 */
internal val LocalLdAnimations = staticCompositionLocalOf { true }

private val LanDropTypography = Typography().run {
    copy(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp),
        bodyMedium = TextStyle(fontSize = 13.5.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun LanDropTheme(content: @Composable () -> Unit) {
    val st by ThemeStore.state.collectAsState()
    val palette = withAccent(presetOf(st.preset), st.accent)
    val view = LocalView.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 浅色主题下状态栏/导航栏要换成深色图标，否则白底白字看不见
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
        }
    }

    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentDim,
            onPrimaryContainer = palette.onAccent,
            secondary = palette.green,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.textDim,
            outline = palette.border,
            error = palette.red,
            onError = onColorFor(palette.red),
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentDim,
            onPrimaryContainer = palette.onAccent,
            secondary = palette.green,
            onSecondary = Color(0xFF06240F),
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.textDim,
            outline = palette.border,
            error = palette.red,
            onError = onColorFor(palette.red),
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLdPalette provides palette,
        LocalLdAnimations provides (st.animations && Motion.animationsEnabled(ctx)),
    ) {
        MaterialTheme(colorScheme = scheme, typography = LanDropTypography, content = content)
    }
}

/**
 * 主题壁纸层：铺在内容最底下，再叠一层可调强度的暗化层（scrim），
 * 保证任何壁纸下前景文字仍然可读。壁纸为空时就是纯色背景。
 *
 * 结构：Box[ bg → 壁纸 → scrim → 内容 ]
 */
@Composable
fun ThemedBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val st by ThemeStore.state.collectAsState()
    val palette = LocalLdPalette.current
    val bmp: Bitmap? = st.wallpaper
    Box(modifier.fillMaxSize().background(palette.bg)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val scrim = st.scrim / 100f
            if (scrim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.bg.copy(alpha = scrim)),
                )
            }
        }
        content()
    }
}
