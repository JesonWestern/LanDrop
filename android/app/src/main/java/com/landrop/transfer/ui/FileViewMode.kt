package com.landrop.transfer.ui

/**
 * 文件页的三种视图（纯数据，可 JVM 单测）。
 *
 * 为什么 id 与 label 分开：label 是给人看的文案（以后可能改），id 是落
 * [com.landrop.transfer.data.Settings] 的稳定键。升级用户读到脏值/空值时
 * 由 [viewModeOf] 一律回落 [LIST]，保证不因配置异常崩页面。
 */
enum class FileViewMode(val label: String) {
    /** 常规行：60dp 行高，带「大小 · 时间」副标题。 */
    LIST("列表"),

    /** 紧凑行：44dp 行高，去掉副标题，同屏能多放约 1/3 的行。 */
    COMPACT("紧凑"),

    /** 网格：3 列、96dp 缩略图。 */
    GRID("网格");

    /** 持久化用的小写稳定 id（不随 label 文案变化）。 */
    val id: String get() = name.lowercase()

    /** 循环切到下一个视图（列表 → 紧凑 → 网格 → 列表）。 */
    fun next(): FileViewMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 把持久化的 viewMode id 解析回枚举；未知/空值一律回落 [FileViewMode.LIST]。
 *
 * 大小写不敏感：`"grid"`、`"GRID"`、`"Grid"` 都认。这样升级用户手改配置、
 * 或别处（Windows 端）写入大写都不至于失效。
 */
fun viewModeOf(id: String?): FileViewMode {
    val raw = id?.trim().orEmpty()
    if (raw.isEmpty()) return FileViewMode.LIST
    return FileViewMode.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
        ?: FileViewMode.LIST
}
