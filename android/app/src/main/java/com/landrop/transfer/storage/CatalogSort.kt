package com.landrop.transfer.storage

import com.landrop.transfer.model.FileItem
import java.text.Collator
import java.util.Locale

/**
 * 分类页排序。纯函数，便于单测（本工程 app/src/test 此前为空）。
 *
 * @param key 排序主键
 * @param asc 升序；默认按时间倒序（最新的在最前）
 */
data class CatalogSort(val key: Key = Key.TIME, val asc: Boolean = false) {

    enum class Key(val label: String) {
        NAME("名称"),
        SIZE("大小"),
        TIME("时间"),
        KIND("类型"),
    }

    fun sort(items: List<FileItem>): List<FileItem> {
        if (items.size < 2) return items
        val primary: Comparator<FileItem> = when (key) {
            Key.NAME -> Comparator { a, b -> COLLATOR.compare(a.name, b.name) }
            Key.SIZE -> compareBy { it.size }
            Key.TIME -> compareBy { it.mtime }
            Key.KIND -> compareBy { it.kind.ordinal }
        }
        val tie: Comparator<FileItem> = Comparator { a, b -> COLLATOR.compare(a.name, b.name) }
        val full = if (asc) primary.thenComparing(tie) else primary.reversed().thenComparing(tie)
        return items.sortedWith(full)
    }

    /** 点同一个键时翻转方向，否则切到新键并用该键的默认方向。 */
    fun toggle(k: Key): CatalogSort =
        if (k == key) copy(asc = !asc) else CatalogSort(k, asc = false)

    private companion object {
        /** Collator 构造很贵，必须复用，否则十万条排序会退化到秒级。 */
        val COLLATOR: Collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
    }
}
