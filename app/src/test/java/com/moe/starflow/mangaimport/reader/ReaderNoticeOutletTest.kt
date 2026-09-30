package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器**提示出口守卫**（源码级）。
 *
 * 用户口径（2026-10）：「阅读器所有的提示报错什么的信息，全部使用顶部系统弹窗系统，
 * 用截屏翻译那个同款弹窗系统（支持调节显示位置、时间的那个）」。
 *
 * 也就是说：阅读器里所有的用户提示都必须经过 [MangaReaderActivity.notifyUser] ——
 * 它优先走 `TranslationStatusOverlay`（`TYPE_APPLICATION_OVERLAY` 系统窗口，位置/时长读
 * `Status_Position` / `Status_Duration`），只在**两条兜底**成立时才退回系统 Toast：
 * ① `status_overlay_enabled` 开关被关掉；② 没有「显示在其他应用上层」权限（浮层静默失败）。
 *
 * ⚠️ 为什么用源码断言：这两个类的构造需要完整的 Activity/WindowManager 环境，
 * 而"某处直接 `UiUtils.showToast`"正是历史上反复出现的回归（用户报「点了没反应、什么提示都没有」），
 * 它不崩不报错、单测也照过 —— 只能盯源码钉死（与 `PanelThemeGuardTest` /
 * `ChapterTranslationCancelGuardTest` 同一手法）。
 */
class ReaderNoticeOutletTest {

    private fun readerSource(): String {
        val src = java.io.File("src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt")
        assertTrue("找不到阅读器源码：${src.absolutePath}", src.exists())
        return src.readText()
    }

    /** `notifyUser` 的函数体（到下一个同缩进的成员定义为止，不用魔法窗口长度）。 */
    private fun notifyUserBody(text: String): String {
        val start = text.indexOf("private fun notifyUser(")
        assertTrue("找不到 notifyUser —— 阅读器提示必须收敛到这一个出口", start >= 0)
        val end = text.indexOf("\n    private fun ", start + 1).let { if (it > start) it else text.length }
        return text.substring(start, end)
    }

    /** Toast 只允许出现在 `notifyUser` 的兜底分支里，**任何提示都不许直接弹 Toast**。 */
    @Test
    fun toastOnlyAsFallbackInsideNotifyUser() {
        val text = readerSource()
        val body = notifyUserBody(text)
        assertEquals(
            "阅读器里 `UiUtils.showToast` 只允许出现在 notifyUser 的兜底分支（开关关掉 / 没有悬浮窗权限）",
            1,
            Regex("UiUtils\\.showToast\\(").findAll(text).count(),
        )
        assertTrue("那唯一一处必须在 notifyUser 里", body.contains("UiUtils.showToast("))
        // 逐行检查：不许出现 `UiUtils.showToast(this, getString(R.string.…))` 这种「提示直接走 Toast」
        text.lines().forEach { line ->
            if (line.contains("UiUtils.showToast(")) {
                assertFalse(
                    "提示不许绕开 notifyUser 直接弹 Toast：${line.trim()}",
                    line.contains("R.string."),
                )
            }
        }
    }

    /** 兜底判据必须**两个都判**：开关关掉、没有悬浮窗权限（浮层画不出来且不报错）。 */
    @Test
    fun fallbackChecksToggleAndPermission() {
        val body = notifyUserBody(readerSource())
        assertTrue(
            "notifyUser 必须判 `status_overlay_enabled`（关掉后 overlay 的 show* 第一句就 return）",
            body.contains("statusOverlayEnabled()"),
        )
        assertTrue(
            "notifyUser 必须判 `TranslationStatusOverlay.canDraw`（没给悬浮窗权限时浮层静默失败，" +
                "用户看到的是「点了毫无反馈」）",
            body.contains("TranslationStatusOverlay.canDraw("),
        )
    }

    /**
     * 包含 [index] 的**成员函数**名（往上找最近一个缩进正好 4 空格的 `fun NAME(`）。
     *
     * ⚠️ 必须限定缩进：`onTranslatePhase` 里有个**局部**函数 `progress()`，它比自己所属的
     * 成员函数更靠近调用点 —— 不限定就会被识别成 `progress`，白名单判断随之失效。
     */
    private fun enclosingFun(text: String, index: Int): String {
        val head = text.substring(0, index)
        val m = Regex("\\n {4}(?:private |internal |public )?fun ([A-Za-z0-9_]+)\\(").findAll(head).lastOrNull()
        return m?.groupValues?.get(1) ?: ""
    }

    /** 直接往浮层塞"给人看的提示"的调用，只允许出现在白名单函数里。 */
    @Test
    fun overlayShowsOnlyFromAllowedFunctions() {
        val text = readerSource()
        // show / showError / showSticky：只有 notifyUser 能发（它负责兜底成 Toast）
        val strict = mapOf(
            "\\boverlay\\.show\\(" to setOf("notifyUser"),
            "\\boverlay\\.showError\\(" to setOf("notifyUser"),
            "\\boverlay\\.showSticky\\(" to setOf("notifyUser"),
            "TranslationStatusOverlay\\.getInstance\\([^)]*\\)\\.showError\\(" to setOf("notifyUser"),
        )
        for ((pattern, allowed) in strict) {
            for (m in Regex(pattern).findAll(text)) {
                val fn = enclosingFun(text, m.range.first)
                assertTrue(
                    "`${m.value}` 出现在 $fn() 里 —— 阅读器的提示必须从 notifyUser 发（否则浮层" +
                        "画不出来时用户什么都看不到）。白名单：$allowed",
                    fn in allowed,
                )
            }
        }
        // showImmediate 是**连续进度**芯片：故意不做 Toast 兜底（浮层关掉时保持静默是那个设置
        // 本身的语义，退化成 Toast 会变成每页/每秒刷屏）。四处白名单各有理由，新增时想清楚：
        //   · notifyUser          —— PROGRESS 样式的实现
        //   · onTranslatePhase    —— 「检测中…／翻译中…」常驻芯片（翻页即变）
        //   · setupChapterBatchUi —— 「正在翻译本章 3/21 · 识别中 P8…」（进度回调高频）
        //   · exportTranslated    —— 「正在导出 x/y…」，且必须只在**前台**显示（跨 onStop 的任务，
        //                            后台再贴会把芯片挂到别的应用上），所以它自己判 progressVisible()
        val progressAllowed = setOf("notifyUser", "onTranslatePhase", "setupChapterBatchUi", "exportTranslated")
        for (m in Regex("\\boverlay\\.showImmediate\\(|TranslationStatusOverlay\\.getInstance\\([^)]*\\)\\.showImmediate\\(").findAll(text)) {
            val fn = enclosingFun(text, m.range.first)
            assertTrue(
                "`showImmediate` 出现在 $fn() 里 —— 只允许用于连续进度芯片，白名单：$progressAllowed" +
                    "（要发「给人看的提示」请用 notifyUser，否则浮层不可用时用户什么都看不到）",
                fn in progressAllowed,
            )
        }
    }

    /** 这些曾经直接弹 Toast 的提示，现在必须逐条走 `notifyUser`。 */
    @Test
    fun knownPromptsGoThroughNotifyUser() {
        val text = readerSource()
        val keys = listOf(
            "reader_file_lost",                  // 本地文件丢失（说完就关闭页面 → ERROR_BRIEF）
            "reader_translate_disabled_mode",    // Webtoon 不支持翻译
            "reader_translate_busy",             // 队列忙
            "reader_translate_chapter_nothing",  // 本章没有可翻的页 / 没有译文可清
            "reader_translate_chapter_cleared",  // 已清除本章
            "reader_translate_page_deleted",     // 已删除本页译文
            "reader_download_busy",              // 导出进行中
            "reader_download_started",           // 开始导出
            "reader_download_nothing",           // 没有可导出的译文
            "reader_download_failed",            // 导出失败（红底芯片）
        )
        for (key in keys) {
            assertTrue(
                "`$key` 必须通过 notifyUser 提示（顶部系统浮层优先，浮层不可用时才退 Toast）：" +
                    "应写成 notifyUser(getString(R.string.$key)…)",
                text.contains("notifyUser(getString(R.string.$key)"),
            )
        }
        // 导出结果：文案由 exportMessage 产出，也必须经 notifyUser（并且失败要带 ERROR 样式）
        assertTrue(
            "导出结束的提示必须走 notifyUser（而不是直接 Toast）",
            text.contains("notifyUser(notice.text,") && text.contains("ReaderNotice.ERROR else ReaderNotice.INFO"),
        )
        assertTrue(
            "「翻译进程已强制退出」也走 notifyUser（showForceStoppedNotice 内部）",
            text.contains("private fun showForceStoppedNotice(toManual: Boolean)") &&
                text.contains("notifyUser(\n            getString("),
        )
    }

    /** 超分提示同样必须走统一出口（用户口径：超分也要用 app 的「系统提示」）。 */
    @Test
    fun srNoticesGoThroughNotifyUser() {
        val text = readerSource()
        assertTrue("showSrNotice 必须委托 notifyUser", notifyUserBody(text).isNotEmpty())
        for (fn in listOf("showSrNotice", "showSrProgress")) {
            val start = text.indexOf("private fun $fn(")
            assertTrue("找不到 $fn", start >= 0)
            val end = text.indexOf("\n    private fun ", start + 1).let { if (it > start) it else text.length }
            assertTrue("$fn 必须通过 notifyUser 提示", text.substring(start, end).contains("notifyUser("))
        }
    }
}
