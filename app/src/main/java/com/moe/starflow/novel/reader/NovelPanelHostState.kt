package com.moe.starflow.novel.reader

import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode

/**
 * 宿主推给面板的**全部**状态 —— 面板就是它的纯函数渲染。
 *
 * ### 为什么必须收成一个类型
 * 以前是「每个字段一次推送」（先只有 stats，后来补 totals，再补 failures…），
 * 于是**凡是忘了补的那一项，面板就永远停在打开那一刻** —— 用户反复报
 * 「某个地方切了 UI 不同步」，根因都是这个，不是注意力问题而是结构问题。
 *
 * 现在：宿主只有一条出口 [NovelReaderActivity.pushPanelState] 构造本对象，
 * 面板只有一个入口 [NovelPanelSheet.renderHostState]。
 *
 * ⚠️ **新增任何「宿主能改、面板要显示」的状态，都必须加进这个 data class 并在
 * `renderHostState` 里渲染** —— 编译器不会提醒你，但漏了就是那一项不同步。
 */
data class NovelPanelHostState(
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterStats: Map<Int, NovelChapterStat> = emptyMap(),
    val chapterTotals: Map<Int, Int> = emptyMap(),
    val chapterFailures: Map<Int, List<NovelFailureRow>> = emptyMap(),
    /** 宿主**此刻**的模式（打开面板会回退手动，所以必须以宿主为准）。 */
    val translateMode: NovelTranslateMode = NovelTranslateMode.MANUAL,
    /** 翻译是否在跑（在跑时单击按钮只提示）。 */
    val translating: Boolean = false,
    val displayMode: NovelDisplayMode = NovelDisplayMode.TRANSLATED,
    val readerMode: Int = NovelPanelStyle.READER_PAGED,
    val animation: Int = NovelPanelStyle.ANIM_SLIDE,
    val background: Int = 0,
    val autoTurn: Boolean = false,
    val intervalSec: Int = 5,
    val debounceMs: Int = 500,
    val aheadBatches: Int = NovelQuota.DEFAULT,
    val batchSize: Int = NovelPanelStyle.BATCH_DEFAULT,
    val rotateLabel: String = "",
    val keepParagraphsWhole: Boolean = false,
    val isDarkPanel: Boolean = false,
)
