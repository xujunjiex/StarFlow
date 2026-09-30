package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ReaderTranslationController` 的**并发契约守卫**（源码级，纯 JVM）。
 *
 * 背景：控制器里原本有 5 个被"多页并发"共用的实例级可变字段
 * （`runDet`/`runOcr`/`cacheCandidates`/`cacheHits`/`cancelFlag`）。它们导致的都是
 * **不崩不报错的静默错误**（同一页有时竖排有时横排、缓存数字串页、取消 A 页连带取消 B 页、
 * 甚至有"整章一页都翻不出来"），所以这里把"它们不许回来"钉死。
 *
 * ⚠️ 源码是 CRLF，统一归一化后再匹配。
 */
class ReaderControllerConcurrencyTest {

    private fun src() = File("src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun noSharedInstanceCancelFlagAnyMore() {
        val s = src()
        // 共用一个取消标志会同时造成两个方向的错（见 ReaderCancelSignals 类注释），
        // 其中一个是"翻译本章一页都翻不出来"。
        assertFalse("不允许再声明共用的实例级取消标志", s.contains("private val cancelFlag"))
        assertFalse("不允许再读它", s.contains("cancelFlag.get()"))
        assertFalse("不允许再写它", s.contains("cancelFlag.set("))
        assertTrue("阅读器前台任务必须走按页登记", s.contains("readerCancel.newFlag()"))
        assertTrue("取消必须只作用于登记过的那批", s.contains("readerCancel.cancelAll()"))
        assertTrue("登记与注销必须配对", s.contains("readerCancel.retire(cancel)"))
    }

    @Test
    fun chapterPagesGetTheirOwnNeverCancelledSignal() {
        val s = src()
        // ⚠️ 章节页拿到的必须是**恒为 false** 的信号：它的取消走 ChapterJobRunner 的按章取消
        // （用户口径：取消只丢还没开始翻的页，在途页照旧跑完）。
        // 以前它读的是阅读器前台那一个标志 → `startChapterJob` 先停队列时把它置 true，
        // 于是整章一进管线就判"已取消"，一页都翻不出来。
        assertTrue(
            "章节页必须用恒 false 的取消信号",
            s.contains("ReaderBatchHost(prep.bitmap, translator, { _, _ -> }, page, prep.det, prep.ocr) { false }"),
        )
    }

    @Test
    fun noSharedPerPageEngineFieldsAnyMore() {
        val s = src()
        // 渲染方向取自"产出这页的引擎"，必须是参数；读实例字段 = 并发下串页
        assertFalse("不允许再有 runDet 实例字段", s.contains("private var runDet"))
        assertFalse("不允许再有 runOcr 实例字段", s.contains("private var runOcr"))
        assertTrue("renderBubbles 必须显式收 det 参数", s.contains("det: DetEngine,"))
        assertTrue(
            "竖排方向必须用参数里的 det 解析（不能用实例字段兜底路径）",
            s.contains("RtTextDirection.resolve(\n            det,"),
        )
    }

    @Test
    fun cacheStatsArePerCallNotShared() {
        val s = src()
        assertFalse("不允许再有 cacheCandidates 实例字段", s.contains("private var cacheCandidates"))
        assertFalse("不允许再有 cacheHits 实例字段", s.contains("private var cacheHits"))
        assertTrue("统计必须按「本次调用」返回", s.contains("val stats = CacheOutcome("))
        assertTrue("提示必须吃本次调用的统计", s.contains("cacheNotice(page, translated.size, outcome.cache)"))
    }

    @Test
    fun ocrLockIsReleasedExactlyOnce() {
        val s = src()
        // 放锁点有两个：`onOcrDone` 的提前放锁 + `finally` 的兜底放锁。
        // 无条件 release 会"放两把" → 别的任务以为锁空着就冲进来，与 native OCR 并发。
        // 合并：加上了 OcrLock 的**持有者令牌**（master 的修复，防「旧持有者放掉新持有者的锁」）。
        // 断言意图不变：finally 里必须**条件**释放，且恰好一次。
        assertTrue("finally 里必须条件释放", s.contains("if (lockHeld) OcrLock.release(ocrToken)"))
        assertTrue("提前放锁也要条件化", s.contains("lockHeld = false"))
        // 本地引擎的 join 必须在放锁之后（否则超分等锁、我们等超分 = 死锁）
        val hook = s.indexOf("onOcrDone = ocrDone@{ t ->")
        assertTrue("必须有 OCR 结束回调", hook > 0)
        val body = s.substring(hook, s.indexOf(") ?: return", hook))
        assertTrue(
            "放锁必须排在 join 之前",
            // 合并后放锁带 OcrLock 持有者令牌（master 的修复），断言意图不变
            body.indexOf("OcrLock.release(ocrToken)") in 1 until body.indexOf("job.join()"),
        )
    }

    @Test
    fun queuePathDoesNotReleaseTheLockEarly() {
        val s = src()
        // ⚠️ 队列（自动/增量）刻意不提前放锁：两页一旦重叠，共享的 AI 上下文历史
        // （`readerContextHistory`，LinkedList）就会并发踩踏；手动路径只有一个任务，
        // 提前放锁不引入任何跨页并发。
        assertTrue("队列路径必须显式跳过提前放锁", s.contains("if (fromQueue) return@ocrDone"))
    }
}
