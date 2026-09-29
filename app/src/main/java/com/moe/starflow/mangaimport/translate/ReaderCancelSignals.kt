package com.moe.starflow.mangaimport.translate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **阅读器自身在途任务**（手动 / 自动 / 增量三条前台路径）的取消标志集合。
 *
 * ## ⚠️ 为什么必须是"每页一个标志 + 只登记阅读器自己的任务"
 *
 * 原来整个控制器只有一个 `private val cancelFlag`，读它的地方有两类：
 * - 阅读器前台路径：`runTranslate` 开头 `cancelFlag.set(false)`，跑的时候读
 * - **章节批量任务**：`translatePhase` 构造的 `ReaderBatchHost.isCancelled()` 读的是**同一个**标志
 *
 * 两者语义完全不同，共用一份就必然出错，而且两个方向都错：
 *
 * 1. **章节任务一页都翻不出来（静默）**：`startChapterJob` 会先调 `cancelEverything()` 去停
 *    自动/增量队列，而那一步把这唯一的标志置成 `true`；章节页从不置回 `false`
 *    （只有 `runTranslate` 才置 false）→ 章节的每一页一进 `IncrementalBatchPipeline.translateWithCache`
 *    就 `if (host.isCancelled()) throw TranslationCancelledException()` → 全部按"已取消"结算。
 *    表现是任务正常跑完、进度条走完，**译文一条都没有**。
 * 2. **反向污染**：章节任务跑着时切换翻译模式 / 打开面板也会把标志置 true，
 *    把**别的章**在途的页一起打断 —— 而用户口径明确"多章互不影响"。
 *
 * 所以规则是：
 * - **阅读器前台路径**：进 `runTranslate` 时[新登记][newFlag]一个标志，`finally` 里[注销][retire]；
 *   [cancelAll] 只影响这些。
 * - **章节批量任务**：**每个页面自己的、永远不被 [cancelAll] 触碰的**标志；它的取消走
 *   `ChapterJobRunner` 的按章取消（协程取消），与阅读器前台互不干扰。
 *   这也与既有的取消语义一致：「取消」只丢**还没开始翻**的页，**在途页照旧跑完**。
 */
internal class ReaderCancelSignals {

    private val live = java.util.Collections.newSetFromMap(ConcurrentHashMap<AtomicBoolean, Boolean>())

    /** 为一个新的在途任务登记标志（初值恒为 false —— **新任务不会被上一次的取消波及**）。 */
    fun newFlag(): AtomicBoolean = AtomicBoolean(false).also { live += it }

    /** 任务收尾注销（`finally` 必调，否则集合会一直涨）。 */
    fun retire(flag: AtomicBoolean) {
        live -= flag
    }

    /** 取消全部**在途的阅读器前台任务**（换模式 / 打开面板 / 退出阅读器 / 启动章节任务前）。 */
    fun cancelAll() {
        live.forEach { it.set(true) }
    }

    /** 当前在途任务数（诊断用） */
    fun liveCount(): Int = live.size
}
