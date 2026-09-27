package com.moe.starflow.novel.reader

import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.novel.translate.NovelBatchWarning
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode
import com.moe.starflow.novel.translate.NovelWaitingBatch
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.utils.TranslationConcurrency

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
    val chapterStats: Map<Int, NovelChapterStat> = emptyMap(),
    val chapterTotals: Map<Int, Int> = emptyMap(),

    /** 每章**可翻译正文字数**（章行显示，用户拿它估翻译费用）。与 [chapterTotals] 同一次懒解析。 */
    val chapterChars: Map<Int, Int> = emptyMap(),
    val chapterFailures: Map<Int, List<NovelFailureRow>> = emptyMap(),
    /** 宿主**此刻**的模式（打开面板会回退手动，所以必须以宿主为准）。 */
    val translateMode: NovelTranslateMode = NovelTranslateMode.MANUAL,
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

    /**
     * 每章的**后台章节任务**（缺省 = 没任务）。卡片上的按钮文案（翻译本章 ⇄ 暂停 ⇄ 继续）与
     * 进度徽章都按它渲染 —— 任务是应用级的（`NovelChapterJobHost`），面板必须能拿到此刻的状态。
     */
    val chapterJobs: Map<Int, ChapterJob> = emptyMap(),

    /**
     * 每章**还没开始翻**的批（面板记录列表里标「等待」的行）。
     *
     * ⚠️ **纯内存态**：来自 `ChapterJobRunner.waitingPages`，任务结束/取消就消失，**不写库**。
     */
    val waitingBatches: Map<Int, List<NovelWaitingBatch>> = emptyMap(),

    /**
     * **正在提交/等待返回**的批（琥珀高亮行）。
     *
     * 用户口径（2026-09-27）：「正在提交等待返回的批次片段背景要高亮处理」——
     * 与 [waitingBatches] 一样是纯内存态，区别是这些批已经离开队列、正在等服务端返回。
     */
    val activeBatches: Map<Int, List<NovelWaitingBatch>> = emptyMap(),

    /** 「同时 API 请求数」**用户设的值**（1–10，默认 5）；本地引擎实际恒 1。 */
    val concurrency: Int = TranslationConcurrency.NOVEL_DEFAULT,

    /** 单批预警阈值在 [NovelBatchWarning.tiers] 里的**档位下标**。 */
    val batchWarnIndex: Int = NovelBatchWarning.defaultIndex,
)
