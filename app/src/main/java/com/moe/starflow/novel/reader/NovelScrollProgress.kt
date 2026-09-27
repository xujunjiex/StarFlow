package com.moe.starflow.novel.reader

import kotlin.math.roundToInt

/**
 * 滚动模式的进度**坐标系**（纯函数，可单测）。
 *
 * ### 为什么位置不能按「段序号」算（用户报的「章节文字太多时进度条失效」）
 * 一段的长度没有上限：`NovelParagraphSplitter` 只按空行分段，所以一个几万字的章节可能
 * **整章就一段**（样例 `英文-单段超长-无空行.txt` 就是这样）。此时「第几段 / 共几段」
 * 恒等于 0/1 —— 进度条从头到尾一动不动，看着就是"坏了"。
 * 段落高度又差几十倍，按段号走本来也和眼睛看到的滚动位置对不上。
 *
 * 所以位置一律取 **RecyclerView 的像素进度**：滚到一半就是一半，与段落怎么切无关。
 *
 * ### 绿条（已翻译）为什么仍按段算
 * 段落高度只有**测量过**才知道，而滚动模式是懒加载的 —— 全量量一遍等于把整章排版一次，
 * 正是滚动模式要避免的事。因此绿条用**段序号的等分切片**（第 i 段 → 第 i/n 片，整段算一片）：
 * 两端（0% / 100%）与像素进度一致，中间是近似。这是取舍，不是遗漏。
 *
 * ⚠️ 切片而不是取点：一章只有一段且已翻时，全章都翻完了，绿条应该铺满整条；
 * 取点的话只会画出一格。
 */
object NovelScrollProgress {

    /**
     * 进度条的坐标格数（像素进度映射到 `0..STEPS-1`）。
     *
     * 比屏幕像素还细（一条进度条约 900px），拖拽不会感到"跳格"；
     * 又足够小，让 `ReaderProgressBar` 的绿条合并（[IntRange] 归并）不至于每次都重排上千个数。
     */
    const val STEPS = 1000

    /**
     * 像素位置 → 格。
     *
     * @param offset `computeVerticalScrollOffset()`
     * @param range `computeVerticalScrollRange()`（RecyclerView 按已测量项估的全长）
     * @param extent `computeVerticalScrollExtent()`（一屏）
     *
     * ⚠️ 内容是**估算**出来的（`ScrollbarHelper` 用已测量项的平均高度外推），
     * 所以这个比例会随着滚动缓慢自我修正 —— 对一条进度条足够了。
     * 内容不足一屏（`range <= extent`）时恒为 0（没得滚）。
     */
    fun stepOf(offset: Int, range: Int, extent: Int, steps: Int = STEPS): Int {
        if (steps <= 1) return 0
        val span = range - extent
        if (span <= 0) return 0
        val f = (offset.toFloat() / span).coerceIn(0f, 1f)
        return (f * (steps - 1)).roundToInt().coerceIn(0, steps - 1)
    }

    /** 格 → 比例（进度条拖拽回调 → 滚动目标）。 */
    fun fractionOfStep(step: Int, steps: Int = STEPS): Float {
        if (steps <= 1) return 0f
        return step.coerceIn(0, steps - 1).toFloat() / (steps - 1)
    }

    /**
     * 第 [itemIndex] 段（共 [itemCount] 段）占的格区间。
     *
     * 段数多于格数时切片会塌成负宽度，这里退化成「至少一格」——否则几十万段的章节
     * 尾部会出现"翻了却什么都不亮"的空洞。
     */
    fun sliceOf(itemIndex: Int, itemCount: Int, steps: Int = STEPS): IntRange {
        if (itemCount <= 0 || steps <= 0) return IntRange.EMPTY
        val i = itemIndex.coerceIn(0, itemCount - 1)
        val lo = (i.toLong() * steps / itemCount).toInt().coerceIn(0, steps - 1)
        val hi = (((i + 1).toLong() * steps / itemCount) - 1).toInt().coerceIn(0, steps - 1)
        return lo..(if (hi < lo) lo else hi)
    }

    /** 已翻译的**段下标**集合 → 绿条要画的格集合。 */
    fun stepsOfItems(itemIndexes: Set<Int>, itemCount: Int, steps: Int = STEPS): Set<Int> {
        if (itemIndexes.isEmpty() || itemCount <= 0 || steps <= 0) return emptySet()
        val out = HashSet<Int>(minOf(steps, itemIndexes.size * 2))
        for (i in itemIndexes) {
            if (i < 0 || i >= itemCount) continue
            for (s in sliceOf(i, itemCount, steps)) out += s
        }
        return out
    }
}
