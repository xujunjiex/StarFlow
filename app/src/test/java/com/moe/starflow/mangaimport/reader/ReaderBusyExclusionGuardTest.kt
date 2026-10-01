package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **「自动进程」互斥**（用户口径 2026-10-01）—— 源码级守卫，与 [com.moe.starflow.sr.SrRecordSystemWiringTest]
 * 同一手法。
 *
 * > 「自动翻译 / 增量翻译 / 翻译本章 / 超分本章 开启状态，**不管有没有开翻译自动超分**，
 * >  都不能点超分和重新超分按钮进行工作；第一次提示，第二次关闭自动进程后才能正常使用，
 * >  关闭也要有对应提示」+「超分本章执行过程中点击翻译按钮，也是第一次提示、第二次关闭」
 *
 * 为什么必须断言源码：这条链路的失效方式是**静默**的 —— 判据漏了一种状态，
 * 按钮照常可点，点下去只是去 `OcrLock` 上排队等（单页可等几分钟），
 * 不崩不报错、单测全绿，用户看到的是「点了没反应」。修之前就是这样：
 * `btn_sr_page` 只判"本页自己是不是正在超分"，`onTranslateButtonClick` 的 busy 里没有超分本章。
 *
 * ⚠️ 用源码断言而不是 Robolectric：这些行为都挂在 `ReaderTranslationController` 的
 * 活实例状态（`_srChapterJob` / `chapterRunner` / `translateMode` / `manualJob`）上，
 * 造出那个组合比断言本身贵得多；而**真正会回归的是"哪个入口判了、判的是谁"，
 * 那正是源码层面的东西**。
 */
class ReaderBusyExclusionGuardTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private val controller by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt")
    }
    private val activity by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt")
    }

    /** 取一段源码（从 [from] 到下一个 `\n    fun ` 或文件尾），用来把断言限定在某个函数里。 */
    private fun block(src: String, from: String): String {
        val i = src.indexOf(from)
        assertTrue("源码里找不到 `$from`（函数被改名了？）", i >= 0)
        val end = src.indexOf("\n    fun ", i + from.length).let { if (it > i) it else src.length }
        return src.substring(i, end)
    }

    // ========== 判据本身 ==========

    /**
     * [BusySource] 的**优先级**：同一时刻可能同时成立（超分本章不会自动停掉页面模式），
     * 提示必须报最"重"的那个 —— 报轻的，用户照着关完会发现按钮还是不能用。
     */
    @Test
    fun busySourceReportsTheHeaviestThingFirst() {
        val body = block(controller, "fun busySource(): BusySource")
        val sr = body.indexOf("BusySource.CHAPTER_SR")
        val chapter = body.indexOf("isChapterBatchRunning()")
        val page = body.indexOf("translateMode.value != MODE_MANUAL")
        assertTrue("busySource 必须判超分本章", sr >= 0)
        assertTrue("busySource 必须判翻译本章", chapter >= 0)
        assertTrue("busySource 必须判页面模式（自动/增量/手动在途）", page >= 0)
        assertTrue("超分本章必须排在翻译本章之前（它最重）", sr < chapter)
        assertTrue("翻译本章必须排在页面模式之前", chapter < page)
    }

    /**
     * 第二次点击**只关掉挡路的那一个**，不做"顺手全清"。
     *
     * ⚠️ 这条是本次改动里最容易被改坏的地方：图省事写成"先 `cancelEverything()` 再顺手
     * 把 `cancelSrChapterJob()` 也调一遍"，就会让用户「双击超分按钮」时把另一个章正在跑的
     * 翻译也停掉 —— 而他根本不知道。
     */
    @Test
    fun cancelBusyCancelsExactlyOneThing() {
        val body = block(controller, "fun cancelBusy(")
        val srBranch = body.substringAfter("BusySource.CHAPTER_SR ->").substringBefore("BusySource.NONE")
        assertTrue("超分本章那支必须调 cancelSrChapterJob()", srBranch.contains("cancelSrChapterJob()"))
        assertTrue(
            "超分本章那支**不能**顺手把页面模式也清了（cancelEverything）",
            !srBranch.contains("cancelEverything()"),
        )
        assertTrue(
            "超分本章那支**不能**顺手把翻译本章也停了（cancelAllChapterJobs）",
            !srBranch.contains("cancelAllChapterJobs()"),
        )
        val chapterBranch = body.substringAfter("BusySource.CHAPTER_TRANSLATE ->")
            .substringBefore("BusySource.CHAPTER_SR")
        assertTrue("翻译本章那支必须调 cancelAllChapterJobs()", chapterBranch.contains("cancelAllChapterJobs()"))
        assertTrue(
            "翻译本章那支不能顺手停超分本章",
            !chapterBranch.contains("cancelSrChapterJob()"),
        )
    }

    /** 「超分本章」入口也要被**页面模式**挡住：自动/增量是持续模式，两条链路会一直抢 `OcrLock`。 */
    @Test
    fun srChapterIsAlsoBlockedByPageModes() {
        val body = block(controller, "fun srBatchBlockedByTranslate()")
        assertTrue(
            "srBatchBlockedByTranslate 必须把页面模式（自动/增量）也算进去 —— " +
                "它们不会自己停，放进来就是两条链路互相抢锁",
            body.contains("BusySource.PAGE_MODE"),
        )
        assertTrue("翻译本章当然也要挡", body.contains("BusySource.CHAPTER_TRANSLATE"))
    }

    // ========== 入口接线 ==========

    /**
     * 超分按钮必须先问 [ReaderTranslationController.busySource]，且**必须保持可点**。
     *
     * ⚠️ `btnSrPage.isEnabled = false` 只能出现在"真的开始超分"之后 —— 放到 busy 分支里
     * 就没有"第一次提示"了，用户只会觉得按钮坏了。
     */
    @Test
    fun enhanceButtonAsksBusySourceAndStaysClickable() {
        val body = block(activity, "private fun onSrEnhanceClicked()")
        assertTrue("必须问 busySource()（只判本页 BUSY 是旧 bug）", body.contains("controller.busySource()"))
        assertTrue("单击要给提示", body.contains("controller.busyHint(busy)"))
        assertTrue("第二次点击要关掉挡路的进程", body.contains("controller.cancelBusy(busy)"))
        assertTrue("被挡时两次都不能真的去超分，必须提前 return", body.contains("return"))
        val busyAt = body.indexOf("controller.busySource()")
        val disableAt = body.indexOf("binding.btnSrPage.isEnabled = false")
        assertTrue("源码里找不到禁用按钮那一行", disableAt >= 0)
        assertTrue(
            "`isEnabled = false` 必须排在 busy 分支**之后** —— 放前面（或放分支里）按钮就点不动了，" +
                "用户既得不到提示也点不了，只会以为坏了",
            disableAt > busyAt,
        )
    }

    /**
     * ⚠️ 「**不管有没有开翻译自动超分**」：互斥判据里**不许**出现 `SrSettings` / `sr_reader_auto`。
     * 那个开关只管"翻译时要不要顺带超分"，与"现在能不能按超分按钮"无关。
     */
    @Test
    fun theExclusionDoesNotDependOnTheAutoSrSwitch() {
        for ((name, body) in listOf(
            "busySource" to block(controller, "fun busySource(): BusySource"),
            "onSrEnhanceClicked" to block(activity, "private fun onSrEnhanceClicked()"),
        )) {
            assertTrue(
                "$name 里出现了 SrSettings / isAutoEnabledForReader —— 互斥判据与「翻译时自动超分」开关无关",
                !body.contains("SrSettings") && !body.contains("isAutoEnabledForReader"),
            )
        }
    }

    /** 翻译按钮的 busy 也要认超分本章（以前漏了 → 点下去卡在 OcrLock 上等）。 */
    @Test
    fun translateButtonAlsoSeesChapterSr() {
        val body = block(controller, "fun onTranslateButtonClick(")
        assertTrue(
            "翻译按钮必须走 busySource()（只判页面模式+翻译本章是旧 bug：超分本章在跑时这一击会直接开翻，然后卡锁）",
            body.contains("busySource()"),
        )
        assertTrue("二次点击关掉批量要走 CancelledBatch（宿主按文案提示）", body.contains("CancelledBatch"))
        assertTrue(
            "页面模式仍走老语义 CancelledToManual（宿主还要做收尾动画/清屏）",
            body.contains("CancelledToManual"),
        )
    }

    // ========== 「这个开关只影响手动/自动/增量」 ==========

    /**
     * ⚠️ **「翻译本章」不得顺带自动超分**（用户口径 2026-10-01）：
     * 「这个开关只影响手动/自动/增量这三个模式」。
     * `translatePhase` 被章节批量与增量**共用**，靠 `allowAutoSr` 参数区分 ——
     * 章节不传（默认 false），增量显式传 true。改反了就是"翻译本章偷偷把整章超了"。
     */
    @Test
    fun onlyTranslateAheadAutoUpscales() {
        val def = controller.substringAfter("private suspend fun translatePhase(")
        assertTrue("translatePhase 必须有 allowAutoSr 开关", def.contains("allowAutoSr: Boolean = false"))
        assertTrue(
            "自动超分必须被 allowAutoSr 包住（不能无条件调 maybeStartAutoSr）",
            def.contains("if (allowAutoSr) {") && def.contains("maybeStartAutoSr(page, prep)"),
        )
        assertTrue(
            "增量那条路必须显式传 allowAutoSr = true",
            controller.contains("""translatePhase(page, prep, label = "增量", allowAutoSr = true)"""),
        )
        assertTrue(
            "章节那条路不能传 true（翻译本章是纯翻译批量）",
            controller.contains("""translate = { page, prep -> translatePhase(page, prep) }"""),
        )
        assertTrue(
            "translatePhase 只该有章节/增量两个调用点",
            controller.split("translatePhase(page, prep").size == 3,
        )
    }
}
