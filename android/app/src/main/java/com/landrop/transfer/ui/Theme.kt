package com.landrop.transfer.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

object LdColors {
    val Bg = Color(0xFF101215)
    val Surface = Color(0xFF171A1F)
    val Surface2 = Color(0xFF1E2228)
    val Surface3 = Color(0xFF262B33)
    val Border = Color(0xFF2E343D)
    val Border2 = Color(0xFF3A424D)
    val Text = Color(0xFFE6E9EF)
    val TextDim = Color(0xFF9AA1AC)
    val TextFaint = Color(0xFF6B7280)
    val Accent = Color(0xFF4C8DF6)
    val AccentDim = Color(0xFF2F5FA8)
    val Green = Color(0xFF57C98A)
    val Red = Color(0xFFE5645F)
    val Amber = Color(0xFFE2B34D)
    // 文件类型附加色（图标用）
    val Purple = Color(0xFF9B7BF0)
    val Orange = Color(0xFFE0904F)
    val Cyan = Color(0xFF4FC1D9)
}

private val LanDropScheme = darkColorScheme(
    primary = LdColors.Accent,
    onPrimary = Color.White,
    primaryContainer = LdColors.AccentDim,
    onPrimaryContainer = Color.White,
    secondary = LdColors.Green,
    onSecondary = Color(0xFF06240F),
    background = LdColors.Bg,
    onBackground = LdColors.Text,
    surface = LdColors.Surface,
    onSurface = LdColors.Text,
    surfaceVariant = LdColors.Surface2,
    onSurfaceVariant = LdColors.TextDim,
    outline = LdColors.Border,
    error = LdColors.Red,
    onError = Color.White,
)

private val LanDropTypography = Typography().run {
    copy(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = LdColors.Text),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = LdColors.Text),
        bodyLarge = TextStyle(fontSize = 15.sp, color = LdColors.Text),
        bodyMedium = TextStyle(fontSize = 13.5.sp, color = LdColors.TextDim),
        bodySmall = TextStyle(fontSize = 12.sp, color = LdColors.TextFaint),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun LanDropTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = LanDropScheme,
        typography = LanDropTypography,
        content = content,
    )
}
