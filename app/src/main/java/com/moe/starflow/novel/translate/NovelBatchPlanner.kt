package com.moe.starflow.novel.translate

/** 翻译模式。与面板上的三个单选钮一一对应；也决定队列的推进方式。 */
enum class NovelTranslateMode { MANUAL, AUTO, AHEAD }

/**
 * 「批」的规划（纯函数，无 Android 依赖）。
 *
 * ### 批是本阅读器翻译的唯一进度单位
 * 批 = **连续的 N 段**（N = 设置里的「每批段数」，默认 3，范围 1–10）。**不跨章**。
 *
 * ### 三种模式的差别只在这一个函数里
 * - **手动 / 自动**：锚点只看**当前页**（自动模式页翻完就停下、等翻到下一页）
 * - **增量**：页内翻完后**继续在本章往后**，直到配额用尽或章末
 *
 * ⚠️ 曾经把「增量」做成了「向后 N **章**」—— 那是完全不同的东西：用户要的是**批**。
 * 这条语义由 `NovelBatchPlannerTest` 钉死，改之前先看那条测试。
 */
object NovelBatchPlanner {

    /** 本页第一段未翻译的段号；本页全翻过 → null。 */
    fun anchorOnPage(pageParaIndexes: List<Int>, translated: Set<Int>): Int? =
        pageParaIndexes.firstOrNull { it !in translated }

    /**
     * 按模式取锚点。
     *
     * @param pageParaIndexes 当前页显示的段（按顺序）
     * @param chapterParaIndexes 整章的段（按顺序）—— 增量模式在页内翻完后从这里继续**向后**找
     * @param aheadLimitPara 增量窗口的右边界（**不含**）：只在这个段号之前往后找。
     *   窗口 = 当前页第一段往后「向后批数 × 每批段数」段（见 [NovelTranslationQueue]）——
     *   翻页时窗口自己前移，所以"额度用完"不是终点，翻页就能接着翻
     */
    fun anchorForMode(
        mode: NovelTranslateMode,
        pageParaIndexes: List<Int>,
        chapterParaIndexes: List<Int>,
        translated: Set<Int>,
        aheadLimitPara: Int = Int.MAX_VALUE,
    ): Int? {
        anchorOnPage(pageParaIndexes, translated)?.let { return it }
        if (mode != NovelTranslateMode.AHEAD) return null
        // ⚠️ 增量只**向后**：页内翻完后取「本页之后第一段没翻的」，
        // **不回头**去补本页之前的段（那与"向后翻译"相反），也不越到下一章（章末即停 → null）。
        val lastOnPage = pageParaIndexes.maxOrNull() ?: return null
        return chapterParaIndexes.firstOrNull {
            it > lastOnPage && it < aheadLimitPara && it !in translated
        }
    }

    /**
     * 从 [anchor] 向后取一批（最多 [batchSize] 段）。
     *
     * 已翻译的段**跳过但补足数量**；到章末不足一批就给剩下的；锚点不在本章 → 空。
     */
    fun nextBatch(
        chapterParaIndexes: List<Int>,
        anchor: Int,
        translated: Set<Int>,
        batchSize: Int,
    ): List<Int> {
        val start = chapterParaIndexes.indexOf(anchor)
        if (start < 0) return emptyList()
        return chapterParaIndexes.drop(start)
            .filter { it !in translated }
            .take(batchSize.coerceAtLeast(1))
    }
}

/**
 * 增量模式的**窗口宽度**：从当前页第一段往后「这么多批」。
 *
 * ⚠️ 它不是"一次性额度"：窗口跟着**当前页**走，翻页窗口就前移、接着往后翻
 * （用户口径：「配额用完要根据用户翻页来刷新」）。窗口右边界 = 当前页第一段 + 批数 × 每批段数。
 *
 * ⚠️ 那个数字是**批**的个数（默认 5，范围 2–10），不是章数。
 */
data class NovelQuota(val remaining: Int) {

    /** 窗口宽度（批）。 */
    val batches: Int get() = remaining

    val exhausted: Boolean get() = remaining <= 0

    fun consume(): NovelQuota = copy(remaining = (remaining - 1).coerceAtLeast(0))

    companion object {
        const val MIN = 2
        const val MAX = 10
        const val DEFAULT = 5

        fun of(batches: Int): NovelQuota = NovelQuota(batches.coerceIn(MIN, MAX))
    }
}
