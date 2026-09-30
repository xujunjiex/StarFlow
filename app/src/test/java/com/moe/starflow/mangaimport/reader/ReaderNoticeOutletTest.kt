package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **阅读器 / 书架提示出口守卫**（源码级）。
 *
 * 用户口径（2026-10）：「阅读器、书架所有的提示报错信息全部使用顶部系统弹窗系统，
 * 用截屏翻译那个同款（支持调节显示位置、时间的那个）」。
 *
 * 也就是说：这两处的用户提示都必须经过 `com.moe.starflow.utils.AppNotice` —— 它优先走
 * `TranslationStatusOverlay`（`TYPE_APPLICATION_OVERLAY` 系统窗口，位置/时长读
 * `Status_Position` / `Status_Duration`），只在**两条兜底**成立时才退回系统 Toast：
 * ① `status_overlay_enabled` 开关被关掉；② 没有「显示在其他应用上层」权限（浮层静默失败）。
 * 2026-10 起兜底判据从阅读器收敛进 [AppNotice]，它也是全应用**唯一**允许调
 * `UiUtils.showToast` 的地方。
 *
 * ⚠️ 为什么用源码断言：这两个类的构造需要完整的 Activity/WindowManager 环境，
 * 而"某处直接 `UiUtils.showToast`"正是历史上反复出现的回归（用户报「点了没反应、什么提示都没有」），
 * 它不崩不报错、单测也照过 —— 只能盯源码钉死（与 `PanelThemeGuardTest` /
 * `ChapterTranslationCancelGuardTest` 同一手法）。
 */
class ReaderNoticeOutletTest {

    private fun src(rel: String): String {
        val f = java.io.File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private fun readerSource() =
        src("src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt")

    /** 「书架」= 导入书架这一页（用户口径里的书架提示就是这 4 处）。 */
    private fun bookshelfSource() =
        src("src/main/java/com/moe/starflow/mangaimport/ImportMangaFragment.kt")

    private fun appNoticeSource() =
        src("src/main/java/com/moe/starflow/utils/AppNotice.kt")

    /** `notifyUser` 的函数体（到下一个同缩进的成员定义为止，不用魔法窗口长度）。 */
    private fun notifyUserBody(text: String): String {
        val start = text.indexOf("private fun notifyUser(")
        assertTrue("找不到 notifyUser —— 阅读器提示必须收敛到这一个出口", start >= 0)
        val end = text.indexOf("\n    private fun ", start + 1).let { if (it > start) it else text.length }
        return text.substring(start, end)
    }

    /** 阅读器里**不许**再出现"直接弹 Toast"的出口 —— 全部经 notifyUser → AppNotice。 */
    @Test
    fun readerHasNoDirectToastOutlet() {
        val text = readerSource()
        assertEquals(
            "阅读器里不允许直接 `UiUtils.showToast`（发提示请走 notifyUser → AppNotice）",
            0,
            Regex("UiUtils\\.showToast\\(").findAll(text).count(),
        )
        assertFalse(
            "阅读器不该再 import UiUtils（兜底只剩 AppNotice 一处）",
            text.contains("import com.moe.starflow.utils.UiUtils"),
        )
        assertTrue(
            "notifyUser 必须整体委托 AppNotice.show（两条兜底判据都在那边）",
            notifyUserBody(text).contains("AppNotice.show(this, text, style, autoDismiss = autoDismiss)"),
        )
    }

    /** **书架**（导入页）的 4 处提示同样必须走 AppNotice —— 不许留直接 Toast。 */
    @Test
    fun bookshelfNoticesGoThroughAppNotice() {
        val text = bookshelfSource()
        assertEquals(
            "书架里不允许直接 `UiUtils.showToast`（全部走 AppNotice）",
            0,
            Regex("UiUtils\\.showToast\\(").findAll(text).count(),
        )
        assertFalse(
            "书架不该再 import UiUtils",
            text.contains("import com.moe.starflow.utils.UiUtils"),
        )
        for (key in listOf(
            "reader_file_lost",           // 本地文件丢失（打不开 → 红底短时报错）
            "import_rename_empty",        // 名称不能为空
            "import_manga_deleted",       // 已删除
            "import_cancel_too_late",     // 取消得太晚，已经导入完成
        )) {
            assertTrue(
                "`$key` 必须走 AppNotice.show(requireContext(), getString(R.string.$key)…)",
                text.contains("AppNotice.show(requireContext(), getString(R.string.$key)"),
            )
        }
        // 死路报错（用户说完多半马上离开这一页）要用 BRIEF_ERROR：系统窗口不会赖在别的应用上
        assertTrue(
            "文件丢失要用 BRIEF_ERROR 样式",
            text.contains("getString(R.string.reader_file_lost), AppNotice.Style.BRIEF_ERROR)"),
        )
    }

    /** **唯一**允许 `UiUtils.showToast` 的 AppNotice：两条兜底判据必须都在。 */
    @Test
    fun appNoticeIsTheSingleToastFallback() {
        val text = appNoticeSource()
        assertEquals(
            "兜底 Toast 只允许出现在 AppNotice 里，且只有一处",
            1,
            Regex("UiUtils\\.showToast\\(").findAll(text).count(),
        )
        assertTrue(
            "必须判总开关 `status_overlay_enabled`（关掉后 overlay 的 show* 第一句就 return）",
            text.contains("KEY_OVERLAY_ENABLED") && text.contains("status_overlay_enabled"),
        )
        assertTrue(
            "必须判 `TranslationStatusOverlay.canDraw`（没给悬浮窗权限时浮层静默失败，" +
                "用户看到的是「点了毫无反馈」）",
            text.contains("TranslationStatusOverlay.canDraw("),
        )
        assertTrue(
            "两条判据收在一个 canUseOverlay() 里，阅读器/书架共用",
            text.contains("fun canUseOverlay(context: Context): Boolean"),
        )
        assertTrue(
            "画不出来时必须退回 Toast，不能静默",
            text.contains("UiUtils.showToast(app, text)"),
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
        // show / showError / showSticky：阅读器里一处都不该有 —— 结果/报错全部走 notifyUser →
        // AppNotice（那边才带兜底）；这里出现就是绕开了兜底，浮层画不出来时用户看不到。
        val strict = mapOf(
            "\\boverlay\\.show\\(" to emptySet<String>(),
            "\\boverlay\\.showError\\(" to emptySet<String>(),
            "\\boverlay\\.showSticky\\(" to emptySet<String>(),
            "TranslationStatusOverlay\\.getInstance\\([^)]*\\)\\.showError\\(" to emptySet<String>(),
        )
        for ((pattern, allowed) in strict) {
            for (m in Regex(pattern).findAll(text)) {
                val fn = enclosingFun(text, m.range.first)
                assertTrue(
                    "`${m.value}` 出现在 $fn() 里 —— 阅读器的结果/报错提示必须走 notifyUser → AppNotice" +
                        "（否则浮层画不出来时用户什么都看不到）",
                    fn in allowed,
                )
            }
        }
        // showImmediate 是**连续进度**芯片：故意不做 Toast 兜底（浮层关掉时保持静默是那个设置
        // 本身的语义，退化成 Toast 会变成每页/每秒刷屏）。三处白名单各有理由，新增时想清楚：
        //   · onTranslatePhase    —— 「检测中…／翻译中…」常驻芯片（翻页即变）
        //   · setupChapterBatchUi —— 「正在翻译本章 3/21 · 识别中 P8…」（进度回调高频）
        //   · exportTranslated    —— 「正在导出 x/y…」，且必须只在**前台**显示（跨 onStop 的任务，
        //                            后台再贴会把芯片挂到别的应用上），所以它自己判 progressVisible()
        val progressAllowed = setOf("onTranslatePhase", "setupChapterBatchUi", "exportTranslated")
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
            "reader_file_lost",                  // 本地文件丢失（说完就关闭页面 → BRIEF_ERROR）
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
            text.contains("notifyUser(notice.text,") && text.contains("AppNotice.Style.ERROR else AppNotice.Style.INFO"),
        )
        assertTrue(
            "「翻译进程已强制退出」也走 notifyUser（showForceStoppedNotice 内部）",
            text.contains("private fun showForceStoppedNotice(toManual: Boolean)") &&
                text.contains("notifyUser(\n            getString("),
        )
    }

    /** 成员函数 [name] 的函数体（到下一个同缩进的 `private fun` 为止）。 */
    private fun bodyOf(text: String, name: String): String {
        val start = text.indexOf("private fun $name(")
        assertTrue("找不到 $name", start >= 0)
        val end = text.indexOf("\n    private fun ", start + 1).let { if (it > start) it else text.length }
        return text.substring(start, end)
    }

    /**
     * 超分提示同样必须走统一出口（用户口径：超分也要用 app 的「系统提示」）。
     *
     * ⚠️ 两类提示**刻意走不同出口**，别合并：
     * - **结果 / 报错**（`showSrNotice`）→ `notifyUser`：追加一条、带 Toast 兜底；
     * - **进行中**（`showSrProgress` → `showSrRunning`）→ `AppNotice.showRunning` ——
     *   它必须与并排的「翻译中…」**共存**，而 `notifyUser(..., PROGRESS)` 走的是
     *   `showImmediate` = **替换**顶部那一条，一发就把「翻译中…」顶掉（2026-10 用户口径：
     *   「超分执行的时候也要有提示信息，而且要和翻译中一起出现」）。
     */
    @Test
    fun srNoticesGoThroughTheAppNoticeOutlets() {
        val text = readerSource()
        assertTrue("showSrNotice 必须委托 notifyUser", notifyUserBody(text).isNotEmpty())
        assertTrue(
            "超分结果/报错必须走 notifyUser",
            bodyOf(text, "showSrNotice").contains("notifyUser("),
        )
        val running = bodyOf(text, "showSrRunning")
        assertTrue(
            "进行中必须走 AppNotice.showRunning（与「翻译中…」并存），不能走 notifyUser 的 PROGRESS",
            running.contains("AppNotice.showRunning("),
        )
        assertFalse(
            "进行中不许改回 PROGRESS —— 那是替换顶部一条，会把「翻译中…」顶掉",
            running.contains("AppNotice.Style.PROGRESS"),
        )
        assertTrue(
            "进行中要用句柄精确收尾（只摘自己那条）",
            text.contains("AppNotice.clearRunning("),
        )
    }
}