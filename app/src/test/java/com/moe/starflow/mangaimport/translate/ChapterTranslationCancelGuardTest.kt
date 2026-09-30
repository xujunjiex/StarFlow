package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节批量翻译的**取消判据守卫**（源码级）。
 *
 * 背景（2026-09-28 定位到的致命 bug，用户报「章节翻译完全没用：进度在涨、阅读器毫无变化、
 * 没有译文、进行中的卡片翻完就消失、取消后什么都没有」）：
 *
 * ```
 * startChapterJob() → cancelEverything() → cancelFlag.set(true)   // 手动/队列的取消标志
 *        ↓ 而章节路径从来没人把它复位（只有 runTranslate 开头会）
 * translatePhase() → IncrementalBatchPipeline.translateWithCache()
 *        ↓ 第一句就是 `if (host.isCancelled()) throw TranslationCancelledException()`
 *        ↓ catch 分支静默（连日志都没有）→ 本页退回 IDLE、ok=false
 * ```
 *
 * ⇒ **整章每一页都在管线第一句被判「已取消」，一章都翻不出来**，而进度照涨（done+1）。
 *
 * 修法是把取消判据**由调用方注入**：手动/队列路径传 `{ cancelFlag.get() }`，
 * 章节任务传 `{ false }`（它由 `ChapterJobRunner` 用协程取消来停）。
 *
 * ⚠️ 为什么用源码断言：`ReaderBatchHost` 是控制器的 private inner class，而控制器要
 * Room + OCR 引擎才能构造（真机才能跑起来）。这类"接线错了就整章报废、且零日志"的问题
 * 只能盯源码钉死（与 `ReaderMenuSheetTranslateTest` 的源码守卫同一手法）。
 */
class ChapterTranslationCancelGuardTest {

    private fun controllerSource(): String {
        val src = java.io.File(
            "src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt"
        )
        assertTrue("找不到控制器源码：${src.absolutePath}", src.exists())
        return src.readText()
    }

    /** 章节批量任务**绝不能用**手动/队列的 `cancelFlag` 当取消判据。 */
    @Test
    fun chapterPath_doesNotUseQueueCancelFlag() {
        val text = controllerSource()
        assertTrue(
            "章节翻译路径必须把取消判据固定为 false（章节任务靠 ChapterJobRunner 的协程取消来停）；" +
                "一旦让它读阅读器前台那套取消标志，startChapterJob 会把整章全部判成「已取消」" +
                "—— 进度照涨、却一页都翻不出来、连日志都没有（2026-09-28 事故）",
            // 合并后沿用**分支**的取消架构（ReaderCancelSignals）：章节路径传恒定 false，
            // 阅读器前台路径传**按页登记**的那个 flag（`cancel.get()`），不再有共用实例字段。
            text.contains("cancelled = { false }") || text.contains(") { false }")
        )
        assertTrue(
            "手动/自动/增量路径才该用手动取消标志（分支：按页登记的 flag）",
            text.contains("cancelled = { cancel.get() }") || text.contains("cancelled = cancelled")
        )
        assertFalse(
            "宿主不许再写死读 cancelFlag（那正是事故写法）",
            text.contains("override fun isCancelled(): Boolean = cancelFlag.get()")
        )
    }

    /** `startChapterJob` 必须在提交前把 `cancelFlag` 复位（双保险，防别的入口又把它置 true）。 */
    @Test
    fun startChapterJob_doesNotTouchQueueCancelFlag() {
        val text = controllerSource()
        // ⚠️ 按**函数边界**截取，不要用魔法窗口（start + 2000）：函数一变长/被重排会假红。
        val start = text.indexOf("fun startChapterJob(")
        assertTrue("找不到 startChapterJob", start >= 0)
        val end = text.indexOf("fun pauseChapterJob(", start).let { if (it > start) it else text.length }
        val body = text.substring(start, end)
        assertTrue(
            "startChapterJob 里不许再读队列取消标志（那正是事故写法）",
            !body.contains("cancelFlag.get()")
        )
        // ⚠️ 合并说明：分支 `ReaderCancelSignals` 架构把共用实例字段整体删除，改成
        // 「每页一个新 flag（`readerCancel.newFlag()`，配对注销）+ 章节路径传恒定 false」。
        // 所以不再断言"先复位再提交"（字段不存在了），改断言**提交前不得写任何队列取消标志** ——
        // 与 `ReaderControllerConcurrencyTest.noSharedInstanceCancelFlagAnyMore` 同一契约。
        assertTrue("startChapterJob 里不许写队列取消标志", !body.contains("cancelFlag.set("))
        assertTrue("必须仍然提交章节任务", body.contains("chapterRunner.submit("))
    }
}
