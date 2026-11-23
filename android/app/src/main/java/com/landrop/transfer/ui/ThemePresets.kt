package com.landrop.transfer.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 主题预设的**纯数据**定义（不依赖 Compose 组合，便于 JVM 单测）。
 *
 * 设计约束：
 *  - id 全小写、与 Windows 端 `win/src/theme.h` 的 palette id 一一对应（同一张对照表出截图）；
 *  - 每个预设自带 `isLight`，用来切换状态栏图标深浅与半透明叠加策略；
 *  - 强调色由用户在设置里单独挑选（[withAccent]），因此预设里的 accent 只是"推荐色"。
 */
data class LdPalette(
    val id: String,
    val label: String,
    val isLight: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val border2: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val green: Color,
    val red: Color,
    val amber: Color,
    val purple: Color,
    val orange: Color,
    val cyan: Color,
    /** 列表行/页签的 hover 底色（桌面端用得多，移动端用于按压态）。 */
    val hover: Color,
    val sel: Color,
)

// ---- 颜色小工具（纯函数，可单测） -------------------------------------------

/** 相对亮度（WCAG 定义），用于自动挑选前景色。 */
internal fun luminance(c: Color): Float {
    fun ch(v: Float): Float =
        if (v <= 0.03928f) v / 12.92f
        else ((v + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
}

/** WCAG 对比度，≥3:1 才够"大面积文字可读"的及格线。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** 在给定底色上选黑或白作为前景，取对比度更高的一侧。 */
internal fun onColorFor(bg: Color): Color =
    if (contrastRatio(bg, Color.Black) >= contrastRatio(bg, Color.White)) Color.Black else Color.White

/** 对比度及格线：3:1 是 WCAG 对大面积文字的最低要求。 */
internal const val LIGHT_MIN_CONTRAST = 3.0f

/** 把颜色按比例向黑（f<1）或向白（f>1）推。 */
internal fun Color.shade(f: Float): Color = when {
    f < 1f -> Color(
        red = red * f, green = green * f, blue = blue * f, alpha = alpha
    )
    f > 1f -> {
        val t = f - 1f
        Color(
            red = red + (1f - red) * t,
            green = green + (1f - green) * t,
            blue = blue + (1f - blue) * t,
            alpha = alpha,
        )
    }
    else -> this
}

/**
 * 用户选定强调色后重算派生色。
 *
 * @param accent 用户选的纯色；null 表示沿用预设自带强调色
 */
internal fun withAccent(p: LdPalette, accent: Color?): LdPalette {
    if (accent == null) return p
    val a = Color(accent.red, accent.green, accent.blue, 1f)
    val onAccent = onColorFor(a)
    return p.copy(
        accent = a,
        // 深色主题下 accentDim 用作选中底：按 isLight 选"压暗"或"提亮"
        accentDim = if (p.isLight) a.shade(0.82f) else a.shade(0.62f),
        onAccent = onAccent,
    )
}

// ---- 预设 -------------------------------------------------------------------

private fun palette(
    id: String,
    label: String,
    isLight: Boolean,
    bg: Long,
    surface: Long,
    surface2: Long,
    surface3: Long,
    border: Long,
    border2: Long,
    text: Long,
    textDim: Long,
    textFaint: Long,
    accent: Long,
    green: Long = 0xFF57C98A,
    red: Long = 0xFFE5645F,
    amber: Long = 0xFFE2B34D,
    purple: Long = 0xFF9B7BF0,
    orange: Long = 0xFFE0904F,
    cyan: Long = 0xFF4FC1D9,
): LdPalette {
    val acc = Color(accent)
    val light = isLight
    return LdPalette(
        id = id,
        label = label,
        isLight = light,
        bg = Color(bg),
        surface = Color(surface),
        surface2 = Color(surface2),
        surface3 = Color(surface3),
        border = Color(border),
        border2 = Color(border2),
        text = Color(text),
        textDim = Color(textDim),
        textFaint = Color(textFaint),
        accent = acc,
        accentDim = acc.shade(if (light) 0.82f else 0.62f),
        onAccent = onColorFor(acc),
        green = Color(green),
        red = Color(red),
        amber = Color(amber),
        purple = Color(purple),
        orange = Color(orange),
        cyan = Color(cyan),
        hover = if (light) Color(0x14000000) else Color(0x18FFFFFF),
        sel = if (light) Color(0x1F4C8DF6) else Color(0x2B4C8DF6),
    )
}

/**
 * 10 个预设。**顺序即设置页展示顺序**，`graphite` 是默认值（= 1.2 的观感，升级用户无感）。
 */
val LdPalettes: List<LdPalette> = listOf(
    // 深色系 —— 现有观感
    palette(
        id = "graphite", label = "石墨灰",
        isLight = false,
        bg = 0xFF101215, surface = 0xFF171A1F, surface2 = 0xFF1E2228, surface3 = 0xFF262B33,
        border = 0xFF2E343D, border2 = 0xFF5A6472,
        text = 0xFFE6E9EF, textDim = 0xFF9AA1AC, textFaint = 0xFF8A93A0,
        accent = 0xFF4C8DF6,
    ),
    // 纯黑 —— OLED 省电
    palette(
        id = "black", label = "纯黑",
        isLight = false,
        bg = 0xFF000000, surface = 0xFF0A0A0B, surface2 = 0xFF141416, surface3 = 0xFF1E1E21,
        border = 0xFF2A2A2E, border2 = 0xFF4E4E56,
        text = 0xFFF2F3F5, textDim = 0xFFA8ACB4, textFaint = 0xFF82868E,
        accent = 0xFF4C8DF6,
    ),
    // 纯白 —— 白天户外强光
    palette(
        id = "white", label = "纯白",
        isLight = true,
        bg = 0xFFFFFFFF, surface = 0xFFFFFFFF, surface2 = 0xFFF2F4F7, surface3 = 0xFFE7EBF0,
        border = 0xFFDCE1E8, border2 = 0xFFAAB2BD,
        text = 0xFF14171C, textDim = 0xFF5A6270, textFaint = 0xFF7C8592,
        accent = 0xFF2264D8,
        green = 0xFF1E8E4E, red = 0xFFC93A34, amber = 0xFFB47A15,
        purple = 0xFF6D46C4, orange = 0xFFC4692A, cyan = 0xFF1E7F94,
    ),
    // 银灰 —— 浅色但不刺眼
    palette(
        id = "silver", label = "银灰",
        isLight = true,
        bg = 0xFFEDEFF3, surface = 0xFFF8F9FB, surface2 = 0xFFE4E7EC, surface3 = 0xFFD9DDE4,
        border = 0xFFCFD4DC, border2 = 0xFF9BA3AF,
        text = 0xFF1B1F25, textDim = 0xFF565E6B, textFaint = 0xFF78818F,
        accent = 0xFF2D6DD6,
        green = 0xFF1F8A4C, red = 0xFFC43D37, amber = 0xFFAE7717,
        purple = 0xFF6A48BE, orange = 0xFFBE6628, cyan = 0xFF1B7C90,
    ),
    // 彩色系 —— 深色底 + 高饱和强调色
    palette(
        id = "ocean", label = "深海蓝",
        isLight = false,
        bg = 0xFF071A2B, surface = 0xFF0C2337, surface2 = 0xFF123049, surface3 = 0xFF193D5C,
        border = 0xFF1F4A6B, border2 = 0xFF3C7799,
        text = 0xFFE4EFF7, textDim = 0xFF95B2C6, textFaint = 0xFF7E9DB4,
        accent = 0xFF39B6E8,
        green = 0xFF3FD29B,
    ),
    palette(
        id = "forest", label = "松林绿",
        isLight = false,
        bg = 0xFF0B1710, surface = 0xFF112116, surface2 = 0xFF16291C, surface3 = 0xFF1E3624,
        border = 0xFF27422E, border2 = 0xFF4A6E52,
        text = 0xFFE6F0E8, textDim = 0xFF9CB3A2, textFaint = 0xFF84A08B,
        accent = 0xFF5FD08A,
        green = 0xFF7BE0A6,
    ),
    palette(
        id = "sunset", label = "落日橙",
        isLight = false,
        bg = 0xFF1A0F0A, surface = 0xFF241510, surface2 = 0xFF302016, surface3 = 0xFF3D2A1D,
        border = 0xFF4B3424, border2 = 0xFF86563A,
        text = 0xFFF7ECE4, textDim = 0xFFC0A493, textFaint = 0xFFA88E7E,
        accent = 0xFFFF9142,
        amber = 0xFFFFC24D,
        orange = 0xFFFF9142,
    ),
    palette(
        id = "violet", label = "夜紫",
        isLight = false,
        bg = 0xFF120C1C, surface = 0xFF1A1227, surface2 = 0xFF241933, surface3 = 0xFF2F2242,
        border = 0xFF3D2C54, border2 = 0xFF6C549B,
        text = 0xFFF0EAF6, textDim = 0xFFB2A3C4, textFaint = 0xFF9A8CAD,
        accent = 0xFFB07BF5,
        purple = 0xFFC9A0FF,
    ),
    palette(
        id = "rose", label = "玫瑰紫",
        isLight = false,
        bg = 0xFF1A0E14, surface = 0xFF24141C, surface2 = 0xFF321C26, surface3 = 0xFF412532,
        border = 0xFF52303F, border2 = 0xFF8F5468,
        text = 0xFFF9EBF0, textDim = 0xFFC6A2AF, textFaint = 0xFFAE8B98,
        accent = 0xFFF2649A,
        red = 0xFFF06A6A,
    ),
    palette(
        id = "mint", label = "薄荷青",
        isLight = true,
        bg = 0xFFEEF6F4, surface = 0xFFFAFDFC, surface2 = 0xFFE2EFEB, surface3 = 0xFFD5E6E1,
        border = 0xFFC7DBD5, border2 = 0xFF94AAA4,
        text = 0xFF12211D, textDim = 0xFF4F6762, textFaint = 0xFF71887F,
        accent = 0xFF0E8C7A,
        green = 0xFF16824F, red = 0xFFC33F3A, amber = 0xFFA97612,
        purple = 0xFF6446BC, orange = 0xFFBB6527, cyan = 0xFF12798C,
    ),
)

/** 未知/空 id 一律回落默认预设，保证升级用户不会因为脏配置崩掉。 */
internal fun presetOf(id: String?): LdPalette =
    LdPalettes.firstOrNull { it.id == id } ?: LdPalettes.first { it.id == DEFAULT_PRESET_ID }

internal const val DEFAULT_PRESET_ID = "graphite"

/** 设置页色板用的 12 个强调色（首尾都覆盖蓝/绿/红/黄/紫/青系，够用又不过载）。 */
val LdAccentSwatches: List<Color> = listOf(
    Color(0xFF4C8DF6), // 蓝（默认）
    Color(0xFF39B6E8), // 天青
    Color(0xFF0E8C7A), // 青绿
    Color(0xFF5FD08A), // 绿
    Color(0xFF7BE0A6), // 浅绿
    Color(0xFFE2B34D), // 琥珀
    Color(0xFFFF9142), // 橙
    Color(0xFFF2649A), // 品红
    Color(0xFFE5645F), // 红
    Color(0xFFB07BF5), // 紫
    Color(0xFF9B7BF0), // 靛紫
    Color(0xFF9AA1AC), // 中性灰
)
