package com.moe.starflow.mangaimport.reader

/**
 * 顶部胶囊的宽度预算（漫画阅读器）。
 *
 * 胶囊是「章名 · 章内页/共页」：章名一长，`wrap_content` 会横向长到 `btn_back` / `btn_menu`
 * 底下（用户反馈：章节名过长遮挡左右按钮）。这里的常量就是两侧必须让出的空间：
 *
 * ```text
 * 圆钮 40dp + 外侧 10dp + 与胶囊的间隙 14dp = 64dp / 侧
 * 胶囊自身左右内边距 12dp × 2 = 24dp
 * ```
 *
 * ⚠️ 章名与页码的取舍：**只截章名、页码永远完整**（页码是这一格存在的另一半理由）。
 * 小说阅读器 `top_pill` 用同一套口径（maxWidth=150dp + 独立页码 TextView）。
 */
internal object ReaderPillLayout {

    const val BUTTON_WIDTH_DP = 40f
    const val BUTTON_MARGIN_DP = 10f
    const val BUTTON_GAP_DP = 14f
    const val PILL_PADDING_DP = 12f

    /** 极端窄屏也别把胶囊压成一条线（章名至少留 1 个省略号的宽度）。 */
    const val MIN_PILL_DP = 96f

    /** 胶囊允许的最大宽度（含内边距），必须给两侧按钮留出硬边距。 */
    fun maxPillWidthPx(screenWidthPx: Int, density: Float): Int {
        val side = (BUTTON_WIDTH_DP + BUTTON_MARGIN_DP + BUTTON_GAP_DP) * density
        val pillPadding = PILL_PADDING_DP * 2 * density
        val min = (MIN_PILL_DP * density).toInt()
        return (screenWidthPx - 2 * side - pillPadding).toInt().coerceAtLeast(min)
    }

    /** 章名可用宽度 = 胶囊最大宽 − 页码占用 − 左右内边距。 */
    fun titleBudgetPx(maxPillPx: Int, pagesWidthPx: Float, paddingStartPx: Int, paddingEndPx: Int): Float =
        (maxPillPx - pagesWidthPx - paddingStartPx - paddingEndPx).coerceAtLeast(1f)
}
