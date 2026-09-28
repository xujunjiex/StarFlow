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
        assertTrue(
            "自动模式要保留单页路径（页内分批 + 流式：边翻边出），不能被并发化",
            text.contains("runTranslate(target, fromQueue = true)")
        )
    }
}
