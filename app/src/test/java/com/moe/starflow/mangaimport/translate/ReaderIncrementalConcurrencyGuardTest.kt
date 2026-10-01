package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **增量翻译的并发守卫**（源码级）。
 *
 * 用户口径（2026-09-28）：「那个最大请求数对增量翻译应该也生效」——
 * 增量窗口必须跑成 **OCR 逐页串行 + 翻译请求并发 N**（与整章任务同一套语义），
 * 而不是像以前那样逐页串行地「OCR → 翻译 → 下一句」。
 *
 * 为什么用源码断言：`runWindow` 里那两条（`ocrPhase` / `translatePhase`）都要真引擎 +
 * Room 才能跑（真机链路），纯单测起不来；而这条接线一旦被改回串行，
 * 表现只是"设置拖了没效果"——**没有任何报错**，最容易悄悄回退。
 */
class ReaderIncrementalConcurrencyGuardTest {

    private fun controllerSource(): String {
        val src = java.io.File(
            "src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt"
        )
        assertTrue("找不到控制器源码：${src.absolutePath}", src.exists())
        return src.readText()
    }

    @Test
    fun incrementalWindow_usesConcurrencySemaphore() {
        val text = controllerSource()
        assertTrue(
            "增量窗口必须用信号量把翻译请求限制在「同时请求数」内" +
                "（`Semaphore(TranslationConcurrency.mangaConcurrency(...))`）",
            text.contains("kotlinx.coroutines.sync.Semaphore(") &&
                text.contains("TranslationConcurrency.mangaConcurrency(context, appPrefs).coerceAtLeast(1)")
        )
        assertTrue(
            "增量分支必须走 runWindow（OCR 串行 + 翻译并发），不能退回逐页 runTranslate",
            text.contains("val dispatched = runWindow(targets)")
        )
        // ⚠️ 只断言"出现过 Semaphore(" 是不够的：声明留着、acquire/release 被删依旧全绿。
        // 这里按函数边界取 runWindow 的函数体，断言**结构顺序**真的成立。
        val body = text.substringAfter("private suspend fun runWindow(")
            .substringBefore("private fun reportQueuePhase(")
        val acquire = body.indexOf("slots.acquire()")
        val launch = body.indexOf("launch {")
        // ⚠️ 只匹配到 `label = "增量"` 为止 —— 增量那条路现在还带 `allowAutoSr = true`
        //    （用户口径 2026-10-01：「翻译时自动超分」只管手动/自动/增量三个页面模式），
        //    把后面的参数一起写进来这里就会误报。
        val call = body.indexOf("translatePhase(page, prep, label = \"增量\"")
        // 注意：runWindow 里在 launch **之前**也有 slots.release()（早退分支），
        // 所以"翻译那一次的释放"要从 launch 之后再找。
        val release = body.indexOf("slots.release()", launch.coerceAtLeast(0))
        assertTrue("必须先占槽位再 OCR/发请求（acquire 在 launch 之前）", acquire in 0 until launch)
        assertTrue("翻译调用必须包在 launch 里", call > launch)
        assertTrue("翻译那一路必须有释放（finally 内）", release > call)
        assertTrue(
            "自动模式要保留单页路径（页内分批 + 流式：边翻边出），不能被并发化",
            text.contains("runTranslate(target, fromQueue = true)")
        )
    }
}
