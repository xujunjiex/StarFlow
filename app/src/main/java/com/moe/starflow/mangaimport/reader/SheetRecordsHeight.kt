package com.moe.starflow.mangaimport.reader

import android.content.res.Resources

/**
 * 阅读器面板里「记录列表」（章节卡片 / 每页状态）的高度 = **半个屏幕**。
 *
 * 用户口径 2026-10-01：
 * > 「翻译和超分的记录功能底部的空间太小了，面板上滑占满屏幕后，应该可以继续上滑屏幕，
 * >  让记录组件至少可以占据半个屏幕为止」
 *
 * 原来 XML 里写死 `200dp`（≈1/4 屏），于是**面板整体内容比视口还矮** —— 外层滚动容器
 * 根本没得滚，用户说的"继续上滑没反应"就是这个，不是手势问题。
 *
 * ⚠️ 但**仍然必须定高**：`wrap_content` 会让 RecyclerView 量完全部条目，
 * 几百章的书一打开面板就卡死（这是当初写死 200dp 的原因，不是随便定的）。
 * 半屏两边都占：量得起（定高），展开后真的能占到半屏。
 *
 * ⚠️ 两个面板（漫画 `sheet_reader_menu.xml` / 小说 `sheet_novel_menu.xml`）都要走这里 ——
 * 只在 XML 里改数字的话两边会漂开，而"列表太矮"的症状两边一模一样。
 */
internal fun sheetRecordsHeightPx(resources: Resources): Int {
    val metrics = resources.displayMetrics
    return maxOf(metrics.heightPixels / 2, (MIN_RECORDS_HEIGHT_DP * metrics.density).toInt())
}

/** 记录列表高度的下限（dp）——屏幕特别矮时兜底，取值与 XML 里的初始值一致。 */
private const val MIN_RECORDS_HEIGHT_DP = 200
