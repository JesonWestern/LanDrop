package com.landrop.transfer.ui

import android.content.Context
import android.provider.Settings

/**
 * 系统级"动画是否被关闭"的查询。
 *
 * Compose 的 `animate*` 系列本来就会跟随 `Settings.Global.ANIMATOR_DURATION_SCALE`
 * （开发者选项"动画时长缩放"设为 0 时自动瞬时完成）。这里额外读一次是为了：
 *  - 让"关闭动画"不仅能时长归零，还能直接跳过 `AnimatedContent` 的过渡开销；
 *  - 与 Windows 端语义对齐（那边读 `SPI_GETCLIENTAREAANIMATION`）。
 */
internal object Motion {

    private const val KEY = "animator_duration_scale"

    /** 缓存：每次组合都走 contentResolver 太贵，且该值极少变化。 */
    @Volatile private var cached: Boolean? = null

    fun animationsEnabled(ctx: Context): Boolean {
        cached?.let { return it }
        val enabled = runCatching {
            Settings.Global.getFloat(ctx.applicationContext.contentResolver, KEY, 1f) > 0f
        }.getOrDefault(true)
        cached = enabled
        return enabled
    }

    /** 从系统设置返回时调用，丢弃缓存。 */
    fun invalidate() {
        cached = null
    }
}
