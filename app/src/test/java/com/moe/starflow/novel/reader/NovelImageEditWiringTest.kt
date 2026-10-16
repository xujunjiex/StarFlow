package com.moe.starflow.novel.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插图**编辑模式**的接线守卫（源码级，纯 JVM）。
 *
 * ⚠️ 为什么用源码断言：这几条的失效方式全是**静默**的 —— 长按又会自己开跑、退出编辑模式
 * 顺手把在途翻译取消掉、按钮没收干净。不崩不报错，单测也照过，只有人肉点一遍才发现。
 * 与 `SrRecordSystemWiringTest` / `NovelReaderTranslateRulesTest` 同一手法。
 *
 * 用户口径（2026-10-04）：
 * 1. 「长按出现翻译按钮，**点击翻译才会 OCR 翻译**」
 * 2. 「翻译过程中也可以快速返回退出图片编辑模式，**翻译不会因为退出编辑模式而暂停**，
 *    退出阅读器会自动暂停」
 */
class NovelImageEditWiringTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private val activity by lazy { read("src/main/java/com/moe/starflow/novel/reader/NovelReaderActivity.kt") }

    /** 截出某个函数体（从签名到下一个 `\n    private fun` / `\n    fun` / `\n    internal fun`）。 */
    private fun bodyOf(signature: String): String {
        val start = activity.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val rest = activity.substring(start + signature.length)
        val next = listOf("\n    private fun ", "\n    internal fun ", "\n    fun ", "\n    override fun ")
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        return rest.substring(0, next)
    }

    /** 长按插图**只进编辑模式**：不许直接把翻译开出去（用户口径 1）。 */
    @Test
    fun `长按插图不开翻`() {
        val body = bodyOf("private fun onLongPressImage(")
        assertFalse("长按不许直接开翻（要等用户点那颗「翻译」按钮）", body.contains("translateImageNow("))
        assertTrue("长按要把这张图设为编辑目标", body.contains("focusedImagePara = paraIndex"))
    }

    /** 编辑模式里点「翻译」按钮才真的开翻。 */
    @Test
    fun `编辑模式里点翻译按钮开翻`() {
        val body = bodyOf("private fun handleTap(")
        assertTrue(
            "按钮路由里必须有 TRANSLATE → translateImageNow",
            body.contains("NovelImageButton.TRANSLATE -> translateImageNow("),
        )
    }

    /**
     * **退出编辑模式不许打断在途翻译**（用户口径 2）：
     * 点别处 / 翻页这两条只清聚焦，绝不 `imageJob?.cancel()`。
     */
    @Test
    fun `退出编辑模式不取消在途翻译`() {
        val tap = bodyOf("private fun handleTap(")
        val turn = bodyOf("private fun turnPage(")
        for ((name, body) in listOf("handleTap" to tap, "turnPage" to turn)) {
            assertTrue("$name 里要清聚焦（按钮跟着收掉）", body.contains("focusedImagePara = null"))
            assertFalse("$name 不许取消在途翻译（那样退出编辑模式就把翻译停了）", body.contains("imageJob?.cancel()"))
        }
    }

    /** **退出阅读器自动停**：`onStop` 必须取消在途的图片翻译。 */
    @Test
    fun `退出阅读器会取消在途图片翻译`() {
        val body = bodyOf("override fun onStop(")
        assertTrue("onStop 要取消在途图片翻译", body.contains("imageJob?.cancel()"))
    }

    /** 按钮条只在**有聚焦图**时才画/才命中（"退出编辑模式隐藏相关按钮"的兜底判据）。 */
    @Test
    fun `没聚焦时不画也不命中按钮`() {
        val pageView = read("src/main/java/com/moe/starflow/novel/reader/NovelPageView.kt")
        val controls = pageView.substring(pageView.indexOf("internal fun controlsOf()"))
            .take(400)
        assertTrue("拿不到聚焦图就直接返回 null", controls.contains("val focus = focusedImage ?: return null"))
        assertTrue("画之前也要判聚焦", pageView.contains("if (focused) {"))
    }

    // ===== 点图看全图 + 实时上屏（用户口径 2026-10-04）=====

    /**
     * **点一下插图 = 打开全屏查看器**（不再是"翻页"）。
     *
     * 用户口径：「**点击图片应该要可以放大查看全图**（这个查看全图就复用漫画阅读器那边的逻辑，
     * 底部右下角也要有翻译，切换和删除按钮这些基础按钮）」。
     */
    @Test
    fun `点插图打开全屏查看器`() {
        val body = bodyOf("private fun handleTap(")
        assertTrue("点插图要开查看器", body.contains("openImageViewer("))
        // ⚠️ 顺序关键：必须在九宫格 `when { isScrollMode() -> ... turnPage ... }` 之前 return，
        // 否则整页插图那一下会被当成翻页（用户点了半天图没反应）
        val openAt = body.indexOf("openImageViewer(")
        val turnAt = body.indexOf("turnPage(")
        assertTrue("开查看器要在翻页判定之前", openAt in 0 until turnAt)
    }

    /**
     * **插图的触发范围 = 整张图**（真机反馈 2026-10-04：「滚动模式下图片点击打开的位置有问题，
     * 触发范围应该都是整张图片才对；有的图片打不开，必须在很刁钻的位置才能打开」）。
     *
     * 根因是九宫格**中格**（显隐 UI，占屏幕正中 1/9）抢在插图前面：整张图正好落在中格时
     * 一个点都点不到。所以插图判定必须在 `inChromeToggleZone` 之前。
     */
    @Test
    fun `插图优先于九宫格中格`() {
        val body = bodyOf("private fun handleTap(")
        val imgAt = body.indexOf("val tapped = paragraphAt(")
        val chromeAt = body.indexOf("inChromeToggleZone(")
        assertTrue("两项都要在", imgAt >= 0 && chromeAt > 0)
        assertTrue("点插图必须排在「中格显隐 UI」之前，否则中格会把图吃掉", imgAt < chromeAt)
    }

    /**
     * **菜单角不许再用屏幕百分比**（真机反馈 2026-10-04：「点击屏幕右上角（菜单按钮的下方很大一片
     * 区域）都可以打开底部菜单」）：改成贴着菜单按钮算（[inMenuZone]）。
     */
    @Test
    fun `菜单角贴着菜单按钮算`() {
        assertTrue("菜单补充区必须看真实按钮", activity.contains("private fun inMenuZone("))
        assertTrue("要用 btnMenu 的屏幕矩形", activity.contains("v.getLocationOnScreen(vLoc)"))
        assertFalse("不许再出现 22% 的右上角大区", activity.contains("h * 0.22f"))
        assertFalse("不许再出现 72% 的右边界", activity.contains("w * 0.72f"))
    }

    /**
     * **chrome 隐藏时任何一次点击先把 UI 唤回来**：整页都是插图的章里，如果点图一律开查看器，
     * 用户把 UI 藏起来之后就再也调不出面板/返回键了。
     */
    @Test
    fun `UI 隐藏时先唤回 UI`() {
        val body = bodyOf("private fun handleTap(")
        val hiddenAt = body.indexOf("if (chromeHidden) {")
        val imgAt = body.indexOf("val tapped = paragraphAt(")
        assertTrue("要有 chrome 隐藏的兜底分支", hiddenAt >= 0)
        assertTrue("兜底分支要排在插图之前（否则调不回 UI）", hiddenAt < imgAt)
    }

    /** 查看器的三颗按钮**复用**图上那排的实现（在途去重/落库/上屏只有一份）。 */
    @Test
    fun `查看器按钮走同一条实现`() {
        val body = bodyOf("private fun openImageViewer(")
        for (f in listOf(
            "override fun onTranslateImage(paraIndex: Int) = translateImageNow(paraIndex)",
            "override fun onToggleImageVisual(paraIndex: Int) = cycleImageVisual(paraIndex)",
            "override fun onDeleteImageTranslation(paraIndex: Int) = deleteImageTranslation(paraIndex)",
        )) {
            assertTrue("查看器里必须直接复用 $f", body.contains(f))
        }
        // 查看器开着的期间，图上/查看器里的状态一变就要重画（翻译完成、切三态、删译文）
        val chrome = bodyOf("private fun refreshImageChrome(")
        assertTrue("状态变化要推给查看器", chrome.contains("imageViewer?.refresh()"))
    }

    /** 查看器是 Dialog，**必须**在 onStop/onDestroy 收掉（否则窗口泄漏）。 */
    @Test
    fun `退出阅读器收掉查看器`() {
        assertTrue("onStop 要收", bodyOf("override fun onStop(").contains("imageViewer?.dismiss()"))
        assertTrue("onDestroy 要兜底", bodyOf("override fun onDestroy(").contains("imageViewer?.dismiss()"))
    }

    /**
     * **一个请求单位落库就上屏**（"先完成先显示"）—— 四条翻译路都汇到翻译器这一个回调上。
     */
    @Test
    fun `落库即上报接线齐全`() {
        assertTrue(
            "阅读器自己的翻译器要接 onUnitSettled",
            activity.contains("onUnitSettled = { ch, paras -> onUnitSettled(ch, paras) }"),
        )
        assertTrue(
            "onStart 要经统一启动入口把回调挂到整章宿主上",
            bodyOf("override fun onStart(").contains("onReaderStarted()") &&
                bodyOf("private fun onReaderStarted(").contains("chapterHost?.onUnitSettled = { ch, paras -> onUnitSettled(ch, paras) }"),
        )
        assertTrue(
            "onStop 要摘掉（宿主是应用级的，挂着就是泄漏）",
            bodyOf("override fun onStop(").contains("chapterHost?.onUnitSettled = null"),
        )
        val host = read("src/main/java/com/moe/starflow/novel/translate/NovelChapterJobHost.kt")
        assertTrue(
            "宿主自己的翻译器也要接（整章任务那条路）",
            host.contains("onUnitSettled = { ch, paras -> onUnitSettled?.invoke(ch, paras) }"),
        )
    }

    /**
     * **插图走轻量上屏**（只重读载荷 + 重画，不重排整章）。
     *
     * 插图段的高度由图片自身尺寸定，与"有没有译文"无关 —— 走 `refreshTranslations → loadChapter`
     * 会白白重排整章，还会把阅读位置掀一下（三张图就是三次）。
     */
    @Test
    fun `插图落库只重画不重排`() {
        val body = bodyOf("private suspend fun applyUnitResult(")
        assertTrue("插图走载荷轻量路径", body.contains("loadImageTranslations("))
        assertTrue("文本才需要重排", body.contains("refreshTranslations()"))
    }

    /**
     * **「刚翻完」的高亮**：4 种翻译模式共用 `onUnitSettled` 这一条接线，
     * 两种阅读模式的视图都要收到（与选中/失败/在飞同一套刷新方式）。
     */
    @Test
    fun `刚翻完的高亮推给两种阅读模式`() {
        val body = bodyOf("private fun refreshRecentHighlight(")
        assertTrue(body.contains("pageAdapter.setRecentBatch("))
        assertTrue(body.contains("scrollAdapter.setRecentBatch("))
        // ⚠️ 同一章的重排（译文到达自己触发的那次 loadChapter）**不能**把高亮清掉，
        // 只有真的换章才清 —— 判据是入口处的 chapterIndex（那时它还是旧章）
        val load = bodyOf("private fun loadChapter(")
        assertTrue("换章才清高亮", load.contains("if (index != chapterIndex && recentPara.isNotEmpty())"))
    }
}
