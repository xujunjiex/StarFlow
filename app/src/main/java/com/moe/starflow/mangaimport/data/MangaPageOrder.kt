package com.moe.starflow.mangaimport.data

/**
 * **页序迁移**：章节系统上线（2026-10）改变了「权威页序」。
 *
 * 旧版（`MangaChapterSplitter` 之前）：`ArchivedMangaReader.sortNaturally(完整路径)`。
 * 新版：按第一层子目录分章 + **最外层散图单独成第 0 章、排在最前**。
 * 对「根散图 + 子目录混放」的书，两套顺序**不一致** —— 而译文/气泡是**按 `pageIndex` 存**的
 * （`imported_page_translation` 主键 `(mangaId, pageIndex)` + 身份指纹 `mangaKey`），
 * 于是升级后同一个下标指向**另一张图**：译文挂错页、气泡坐标错位。
 *
 * 这里给出**重排映射**（旧下标 → 新下标），由迁移器把行重新编号（而不是删掉重翻）：
 * 位移是纯置换，信息可完整保留。
 *
 * 纯函数（不依赖 Android），单测覆盖。
 */
object MangaPageOrder {

    /**
     * 当前页序版本。`ImportedManga.pageOrderVersion` 小于它就说明这本书的行还可能是旧序。
     * 加一 = 又换过一次权威页序（换的时候必须同步改这里，否则老书不会再迁移）。
     */
    const val CURRENT_VERSION: Int = 1

    /**
     * 算「旧下标 → 新下标」的映射。
     *
     * @param rawKeys 原始页 key（**未排序**，与 [MangaChapterSplitter] 的输入同一集合）
     * @param newKeys 当前权威页序（`MangaChapterSplitter.split(rawKeys).keys`）
     * @return 长度 = 页数的映射；两套顺序一致、或页集合对不上（数据异常）时返回 **null**（无需/不能迁移）
     */
    fun legacyToNewPlan(rawKeys: List<String>, newKeys: List<String>): IntArray? {
        if (rawKeys.isEmpty() || rawKeys.size != newKeys.size) return null
        // 集合必须完全一致：不一致说明文件被换过（增删页），映射没有意义 —— 宁可不动
        if (rawKeys.toHashSet() != newKeys.toHashSet()) return null
        val legacy = ArchivedMangaReader.sortNaturally(rawKeys)
        if (legacy == newKeys) return null
        val newIndexOf = HashMap<String, Int>(newKeys.size * 2)
        newKeys.forEachIndexed { index, key -> newIndexOf[key] = index }
        val plan = IntArray(legacy.size) { -1 }
        legacy.forEachIndexed { oldIndex, key ->
            plan[oldIndex] = newIndexOf[key] ?: return null
        }
        // 必须是**双射**（置换）：有重复目标就说明两份 key 列表并非集合相等
        if (plan.toHashSet().size != plan.size) return null
        return plan
    }

    /** 旧下标 → 新下标（越界/无映射时原样返回，调用方不必再判）。 */
    fun mapPage(plan: IntArray?, oldIndex: Int): Int {
        if (plan == null) return oldIndex
        if (oldIndex < 0 || oldIndex >= plan.size) return oldIndex
        val mapped = plan[oldIndex]
        return if (mapped >= 0) mapped else oldIndex
    }
}
