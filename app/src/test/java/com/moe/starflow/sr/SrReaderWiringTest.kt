package com.moe.starflow.sr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码级接线守卫**（纯 JVM，不需要设备）。
 *
 * 为什么需要它：超分这条链路的失败方式几乎全是**静默**的 —— 引擎跑得好好的、落盘也成功，
 * 但"显示那一层"没人接线，用户在界面上看不到任何变化（这正是 2026-10 R5 之后的状态：
 * `OverlayRenderer.baseScale` 与 `SrStore.load` **零调用方**，管线全通却毫无效果）。
 *
 * 所以这里不测行为（行为要真机），只钉死**接线存在**：改坏了就是红，而不是等下一个人肉眼看。
 *
 * ⚠️ 源码是 CRLF，统一归一化后再做子串匹配。
 */
class SrReaderWiringTest {

    private val reader = "src/main/java/com/moe/starflow/mangaimport"

    private fun read(path: String) = File(path).readText().replace("\r\n", "\n")

    private fun controller() = read("$reader/translate/ReaderTranslationController.kt")

    private fun activity() = read("$reader/reader/MangaReaderActivity.kt")

    private fun menuSheet() = read("$reader/reader/ReaderMenuSheet.kt")

    private fun menuLayout() = read("src/main/res/layout/sheet_reader_menu.xml")

    @Test
    fun readerRenderPathActuallyConsumesUpscaledBase() {
        val src = controller()
        // ① 底图从磁盘读回来（只落盘不读 = `SrStore.load` 零调用方那次的原始症状）
        assertTrue("渲染路径必须真的去读超分底图（SrStore.load）", src.contains("SrStore.load("))
        // ② 倍率必须传给 OverlayRenderer（不传 = 超分图永远不会被显示）
        assertTrue(
            "必须把底图倍率交给 OverlayRenderer 的 baseScale",
            src.contains("baseScale = base?.scale ?: 1f"),
        )
        // ③ 底图决定走纯函数（可单测），不在渲染路径里散写 if
        assertTrue(
            "底图选择必须走 SrDisplayBase（纯函数，有真值表守卫）",
            src.contains("SrDisplayBase.resolveBaseKind("),
        )
    }

    @Test
    fun everyOverlayRenderGoesThroughTheBaseResolvingEntry() {
        val src = controller()
        assertTrue("分批首屏结果要走 renderPage", src.contains("renderPage(page, pageBitmap, bubbles"))
        assertTrue("最终整页结果要走 renderPage", src.contains("renderPage(pageIndex, original, bubbles, mode, cfg"))
        assertTrue("三态取图要走 renderPage", src.contains("renderPage(pageIndex, orig, bubbles, mode, config)"))
        // ⚠️ 导出/Webtoon **刻意不用**超分底图：导出不该因为"这台机器开过超分"而翻体积；
        //    Webtoon 页是按屏宽采样解码的，底图倍率对不上（见各自调用点注释）
        assertTrue("导出/Webtoon 必须显式声明 base = null", src.contains("base = null"))
    }

    @Test
    fun bothTranslatePathsStartUpscaling() {
        val src = controller()
        // 整章批量翻译：OCR 之后（translatePhase 收到 prep）启动，本地引擎则串行
        assertTrue("批量路径必须调 maybeStartAutoSr", src.contains("srJob = maybeStartAutoSr(page, prep)"))
        assertTrue(
            "本地引擎（LlamaCpp/NLLB）必须串行等待超分",
            src.contains("if (translator.isLocalHeavyEngine()) srJob?.join()"),
        )
        // 手动/自动/增量（runTranslate）：在 OcrLock **释放之后**启动
        assertTrue(
            "runTranslate 的 finally 必须「条件放锁 → 兜底启动超分」（放锁只能放一次，" +
                "而兜底只在没能提前启动时才跑）",
            src.contains("if (lockHeld) OcrLock.release()") &&
                src.contains("if (!srStarted && !cancel.get()) maybeStartSrAfterTranslate(page)"),
        )
    }

    // ---------- 面板 / 按钮显隐 ----------

    @Test
    fun srRowsHiddenWhenFeatureOffButAnime4kStaysVisible() {
        val sheet = menuSheet()
        val layout = menuLayout()
        assertTrue("布局里必须有 sr_panel_group", layout.contains("android:id=\"@+id/sr_panel_group\""))
        assertTrue(
            "面板必须显式切换 sr_panel_group 的可见性",
            sheet.contains("R.id.sr_panel_group") && sheet.contains("View.GONE"),
        )
        // Anime4K 行**不在**超分组里（关掉超分后还要能用，否则这个功能永远不可用）
        val groupStart = layout.indexOf("android:id=\"@+id/sr_panel_group\"")
        assertTrue("sr_panel_group 必须真的存在", groupStart > 0)
        // ⚠️ 必须从 `<LinearLayout` **标签本身**开始配对计数：`groupStart` 指的是
        //    `android:id="…sr_panel_group"` 这个属性，它在标签内部 —— 从那里开始数会漏掉
        //    外层那一层，结果停在第一个子控件的右括号上，后面几条断言就全是假的。
        val groupEnd = matchingLinearLayoutEnd(layout, layout.lastIndexOf("<LinearLayout", groupStart))
        val anime4k = layout.indexOf("android:id=\"@+id/btn_anime4k\"")
        assertTrue("Anime4K 行必须在 sr_panel_group 之外（关掉超分后仍可用）", anime4k > groupEnd)

        // ⚠️ **总开关也必须在组外** —— 这是真机踩过的单向门：
        //    超分**默认是关的**，开关一旦落在"关着就隐藏"的那一组里，用户永远看不到它、
        //    也就永远打不开这个功能（表现为"阅读器面板里一点超分的东西都没有"）。
        val switchPos = layout.indexOf("android:id=\"@+id/sw_reader_sr\"")
        val switchAutoPos = layout.indexOf("android:id=\"@+id/sw_reader_sr_auto\"")
        val modelRowPos = layout.indexOf("android:id=\"@+id/btn_model_sr\"")
        assertTrue("总开关必须存在", switchPos > 0)
        assertTrue(
            "超分总开关 sw_reader_sr 绝不能在 sr_panel_group 里面（否则是单向门）",
            switchPos < groupStart,
        )
        assertTrue("超分模型行要在组**内**（超分关着时该一起消失）", modelRowPos in groupStart until groupEnd)
        assertTrue(
            "「翻译时自动超分」开关要在组**内**（超分关着时该一起消失）",
            switchAutoPos in groupStart until groupEnd,
        )
    }

    /** 从某个 `<LinearLayout` 的起点找到与它配对的 `</LinearLayout>` 起点（按嵌套计数）。 */
    private fun matchingLinearLayoutEnd(text: String, openIdx: Int): Int {
        var i = openIdx
        var depth = 0
        while (i < text.length) {
            val nextOpen = text.indexOf("<LinearLayout", i)
            val nextClose = text.indexOf("</LinearLayout>", i)
            if (nextClose < 0) return text.length
            if (nextOpen in 0 until nextClose) {
                depth++
                i = nextOpen + 13
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + 15
            }
        }
        return text.length
    }

    @Test
    fun anime4kHintRowIsGoneFromLayoutAndThemeList() {
        // 说明行已按用户口径删除；面板主题清单若还登记着它，深色面板下 findViewById 会 NPE
        assertFalse("布局里不该再有 tv_anime4k_hint", menuLayout().contains("tv_anime4k_hint"))
        assertFalse("主题清单里不该再登记 tv_anime4k_hint", menuSheet().contains("R.id.tv_anime4k_hint"))
        // 新增的「翻译时自动超分」标签必须登记进 label 组（漏登记 = 深色面板下看不见）
        assertTrue("tv_sr_auto_label 必须进 label 配色组", menuSheet().contains("R.id.tv_sr_auto_label"))
    }

    @Test
    fun perPageSrButtonHasThreeSemanticsAndHidesWhenOff() {
        val act = activity()
        val layout = read("src/main/res/layout/activity_manga_reader.xml")
        assertTrue("布局里必须有本页超分按钮", layout.contains("android:id=\"@+id/btn_sr_page\""))
        // 三重语义全部接到控制器
        assertTrue("点击要走控制器的三重语义", act.contains("controller.onSrButtonClicked("))
        assertTrue("显隐/高亮要读控制器给出的语义", act.contains("controller.srActionOf("))
        // 关闭时不显示
        assertTrue(
            "超分关闭（HIDDEN）时按钮必须 GONE",
            act.contains("ReaderTranslationController.SrAction.HIDDEN") &&
                act.contains("binding.btnSrPage.visibility = View.GONE"),
        )
    }
}
