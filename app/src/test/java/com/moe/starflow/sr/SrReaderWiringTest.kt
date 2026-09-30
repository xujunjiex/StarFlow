package com.moe.starflow.sr

import org.junit.Assert.assertEquals
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
            // 合并后放锁带 OcrLock 持有者令牌（master 的修复），意图不变：条件释放、恰好一次
            src.contains("if (lockHeld) OcrLock.release(ocrToken)") &&
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
    fun switchingModelOnlyProgrammaticallyNeverFiresUserCallbacks() {
        val sheet = menuSheet()
        // ⚠️ 真机反馈：「每次进入阅读器的面板都会提示翻译完成后自动超分」。
        //    根因：`isChecked = true` 会**派发** OnCheckedChangeListener（与用户手点同一条路），
        //    而 `refreshSrGroup()` 每次开面板都会回填这两个开关 → 每次都弹提示；
        //    超分开着时还会顺带触发 `onReaderSrChanged` → 作废全部渲染缓存 + 重渲染。
        assertTrue("必须有「程序化回填期间抑制回调」的开关", sheet.contains("var suppressSrSwitch = false"))
        assertTrue("回填前要置位", sheet.contains("suppressSrSwitch = true"))
        assertTrue("回填后要复位（finally，异常路径也一样）", sheet.contains("suppressSrSwitch = false"))
        // 两个监听都必须早退（少写一个就等于没修）
        val guarded = Regex("if \\(suppressSrSwitch\\) return@setOnCheckedChangeListener").findAll(sheet).count()
        assertEquals("两个超分 Switch 的监听都要被抑制", 2, guarded)
    }

    @Test
    fun srModelRowRootCoversBothTitleAndDownloadRow() {
        // ⚠️ 真机反馈：「已下载的模型无法切换（只能一级切换，无法二级切换）」。
        //    根因：行根 id 挂在 <include> 上，而「模型名 + 选中圈」在它**外面** ——
        //    用户点模型名（最自然的目标，旁边还画了个单选圈）什么都不会发生。
        //    行根必须在同时包住「标题/选中圈」与「下载行」的那一层上。
        val layout = read("src/main/res/layout/fragment_model_management.xml")
        for (base in listOf(
            "sr_w2x_anime_m1", "sr_w2x_anime_n2", "sr_cunet_m1",
            "sr_cunet_n1", "sr_cunet_n2", "sr_srmd_x2", "sr_srmd_nf_x2", "sr_cugan_dn",
            "sr_cugan_cons", "sr_cugan_d3", "sr_rsrgan_a6b"
        )) {
            val wrapper = layout.indexOf("android:id=\"@+id/${base}_row\"")
            val radio = layout.indexOf("android:id=\"@+id/${base}_radio\"")
            val include = layout.indexOf("layout=\"@layout/item_model_row_browser\"", wrapper)
            assertTrue("$base: 找不到行根", wrapper > 0)
            assertTrue("$base: 找不到选中圈", radio > 0)
            // 选中圈在行根**之后**（即被它包住），下载行也在行根之后
            assertTrue("$base: 选中圈必须在行根容器里（否则点模型名没反应）", radio > wrapper)
            assertTrue("$base: 下载行必须在行根容器里", include > wrapper)
            // 行根的闭合标签必须在两者之后 —— 即它确实"包住"了这两块
            val close = matchingLinearLayoutEnd(layout, layout.lastIndexOf("<LinearLayout", wrapper))
            assertTrue("$base: 行根必须包住选中圈", close > radio)
            assertTrue("$base: 行根必须包住下载行", close > include)
            // <include> 自己不该再抢这个 id（否则行根又变成"只包住下载行"）
            assertFalse("$base: include 不该再带行根 id", layout.contains("android:id=\"@+id/${base}_row\"/>"))
        }
    }

    @Test
    fun srNoticesGoThroughTheInAppOverlayNotToast() {
        val act = activity()
        // 用户口径：「超分的提示信息应该也用 app 系统提示，不要用手机底部 Toast」
        // 2026-10 起提示出口收敛到 AppNotice（阅读器/书架共用，唯一允许兜底 Toast 的地方）
        assertTrue("必须有一个统一的超分提示出口", act.contains("private fun showSrNotice(text: String, isError: Boolean)"))
        assertTrue("进行中的提示要常驻（autoDismiss=false），直到被结果替换", act.contains("private fun showSrProgress(text: String)"))
        assertFalse("阅读器里不许再直接弹 Toast（必须走 AppNotice）", act.contains("UiUtils.showToast("))
        val notice = read("src/main/java/com/moe/starflow/utils/AppNotice.kt")
        assertTrue("失败要用红色可复制的 chip（AppNotice.Style.ERROR）", notice.contains("overlay.showError(text)"))
        assertTrue(
            "拿不到悬浮窗权限 / 浮层被关掉时由 AppNotice 退回 Toast（否则点了毫无反馈）",
            notice.contains("UiUtils.showToast(app, text)"),
        )
        // 控制器侧：失败原因必须经宿主回调送出去（控制器不直接碰 UI）
        val ctl = controller()
        assertTrue("控制器要有 onSrNotice 回调", ctl.contains("var onSrNotice: (text: String, isError: Boolean) -> Unit"))
        assertTrue("自动超分失败要报具体原因", ctl.contains("withContext(Dispatchers.Main) { onSrNotice(msg, true) }"))
        assertTrue("断开 UI 绑定时要清掉回调", ctl.contains("onSrNotice = { _, _ -> }"))
        // 旧的模糊文案不许再被引用
        assertFalse("不许再用那句糊在一起的 reader_sr_failed", act.contains("R.string.reader_sr_failed"))
        assertFalse("不许再用旧的 reader_sr_enhancing", act.contains("R.string.reader_sr_enhancing"))
    }

    @Test
    fun srControlsMirrorTheTranslateTrioAndSitOnTheLeft() {
        val act = activity()
        val layout = read("src/main/res/layout/activity_manga_reader.xml")
        // 用户口径（2026-10）：「超分之后变成重新超分，然后显示两态切换按钮，和翻译的逻辑差不多，
        // 同样可以删除超分结果」+「把阅读器的超分的相关面板移到左边」。
        for (id in listOf("btn_sr_page", "btn_sr_toggle", "btn_sr_clear")) {
            assertTrue("布局缺 " + id, layout.contains("android:id=\"@+id/" + id + "\""))
        }
        // ⚠️ 顺序：超分三件套必须在**翻译三件套左边**（同一 LinearLayout 里按文档序排）
        val order = listOf(
            "btn_sr_toggle", "btn_sr_clear", "btn_sr_page",
            "btn_toggle_translate", "btn_fail_translate", "btn_translate", "btn_clear_translate",
        ).map { layout.indexOf("android:id=\"@+id/" + it + "\"") }
        assertTrue("超分按钮必须全部在翻译按钮左边，且顺序固定", order.all { it > 0 } && order == order.sorted())
        // 动作按钮：有结果时变「重新超分」图标（与"重翻"同款）
        assertTrue(
            "有结果要把动作按钮换成重超图标",
            act.contains("if (hasResult) R.drawable.ic_refresh else R.drawable.ic_reader_sr"),
        )
        // 二态切换与删除：**有结果才出现**
        assertTrue(
            "切换/删除按钮要有结果才显示",
            act.contains("binding.btnSrToggle.visibility = if (hasResult) View.VISIBLE else View.GONE") &&
                act.contains("binding.btnSrClear.visibility = if (hasResult) View.VISIBLE else View.GONE"),
        )
        assertTrue("删除超分结果必须二次确认", act.contains("R.string.reader_sr_clear_confirm_title"))
        assertTrue("删除走控制器的 deleteSrResult", act.contains("controller.deleteSrResult(currentPage)"))
        assertTrue("二态切换走控制器的 toggleSrBase", act.contains("controller.toggleSrBase(currentPage)"))
    }

    @Test
    fun pureToggleDoesNotShowTheEnhancingChip() {
        // 真机日志实证：点一下**切换**，先冒「正在超分第 12 页…」再冒「已切回原图显示」——
        // 用户因此以为"没法切换，它又重超了一遍"。
        val act = activity()
        val i = act.indexOf("private fun onSrToggleClicked()")
        assertTrue("必须把二态切换拆成独立函数", i > 0)
        val body = act.substring(i, act.indexOf("private fun onSrClearClicked()", i))
        assertFalse("纯切换绝不能挂「正在超分…」", body.contains("showSrProgress("))
        val e = act.indexOf("private fun onSrEnhanceClicked()")
        val eBody = act.substring(e, act.indexOf("private fun onSrToggleClicked()", e))
        assertTrue("超分那一路要挂进行中提示", eBody.contains("showSrProgress("))
    }

    @Test
    fun everySrNoticeReplacesThePersistentChip() {
        // 真机反馈「然后一直卡在那」：`show()` 是**追加**一条芯片、不替换顶部 ——
        // 不先 dismiss 的话那条常驻的「正在超分…」会永远挂在屏幕上。
        val act = activity()
        val i = act.indexOf("private fun showSrNotice(")
        assertTrue("找不到 showSrNotice", i > 0)
        val body = act.substring(i, act.indexOf("private fun showSrProgress(", i))
        assertTrue("showSrNotice 必须先 dismiss 掉常驻芯片", body.contains("overlay.dismiss()"))
    }
    @Test
    fun togglingToOriginalNeverClearsTheSrResult() {
        // 真机反馈：「点击切换回原图，整个超分的组件都没有了」。
        // 根因：`srBaseFor` 以前把「用户切回原图」与「结果不存在」都当成 `sig == "o"`，
        // 一并 `srModelByPage.remove(...)` → `hasSrResult` 变 false → 整个超分组消失 → 再也切不回去。
        val ctl = controller()
        val i = ctl.indexOf("private suspend fun srBaseFor(")
        assertTrue("找不到 srBaseFor", i > 0)
        val body = ctl.substring(i, i + 3500)   // 只看 srBaseFor 这一段的固定窗口（~50 行）
        assertTrue(
            "结果的存在性必须只由**文件**决定（storedModelOrNull）",
            body.contains("SrStore.storedModelOrNull(context, manga.id, pageIndex)"),
        )
        // 清除只允许发生在「文件确实不存在」那一支里，且必须排在「切回原图」早退**之前**
        val nullBranch = body.indexOf("if (storedModel == null) {")
        val removeAt = body.indexOf("srModelByPage.remove(pageIndex)")
        val toggleAt = body.indexOf("if (!isSrVisualOn(pageIndex)) return null")
        assertTrue("找不到那一支", nullBranch > 0 && removeAt > 0 && toggleAt > 0)
        assertTrue("清除结果只能发生在「文件不存在」那一支里（不能因为切回原图就清）", removeAt in nullBranch until toggleAt)
        assertEquals("srBaseFor 里只允许一处清除", 1, Regex("srModelByPage\\.remove\\(pageIndex\\)").findAll(body).count())
        // 组显隐：只有 HIDDEN / chromeHidden / Webtoon 才 GONE，不允许因为"切回原图"而 GONE
        val act = activity()
        val j = act.indexOf("private fun refreshSrButtons(")
        val aBody = act.substring(j, j + 1200)   // 只看这个函数开头那一段就够
        assertTrue(
            "超分组整组显隐只由 HIDDEN / chromeHidden / Webtoon 决定",
            aBody.contains("action == ReaderTranslationController.SrAction.HIDDEN || chromeHidden || mode == 3"),
        )
        assertFalse(
            "不许用 srBaseOn / isSrVisualOn 去决定整组显隐（那就是这个 bug）",
            aBody.substringBefore("binding.srGroup.visibility = View.VISIBLE").contains("srBaseOn"),
        )
    }

    @Test
    fun srHasItsOwnFloatingGroupNotNestedInTheTranslateOne() {
        // 用户口径：「单独设计一个组件组放到右边！专门显示超分相关的东西」。
        val layout = read("src/main/res/layout/activity_manga_reader.xml")
        val srGroup = layout.indexOf("android:id=\"@+id/sr_group\"")
        val translateGroup = layout.indexOf("android:id=\"@+id/translate_group\"")
        assertTrue("要有独立的超分组", srGroup > 0)
        // 用户口径：「超分按钮放到左边，不是右边的上面」→ 左下角、与翻译组同一水平线
        assertTrue("超分组的 id 要出现（顺序无关）", srGroup > 0)
        val srBlock = layout.substring(srGroup, layout.indexOf(">", layout.indexOf("android:background", srGroup)))
        assertTrue("超分组必须靠左（bottom|start）", srBlock.contains("android:layout_gravity=\"bottom|start\""))
        assertTrue("左边的组用 marginStart", srBlock.contains("android:layout_marginStart="))
        assertFalse("左边不许再用 marginEnd", srBlock.contains("android:layout_marginEnd="))
        // 三枚按钮必须在 sr_group 里，而不是 translate_group 里
        val srEnd = layout.indexOf("</LinearLayout>", srGroup)
        for (id in listOf("btn_sr_page", "btn_sr_toggle", "btn_sr_clear")) {
            val p = layout.indexOf("android:id=\"@+id/" + id + "\"")
            assertTrue("$id 必须在 sr_group 内", p in srGroup until srEnd)
        }
        // 整组显隐在 refreshTranslationChrome（每页每次刷新都走它）
        assertTrue("refreshSrButtons 要能整组 GONE", activity().contains("binding.srGroup.visibility = View.GONE"))
    }
    @Test
    fun plainModeBaseWarmupIsAwaitedBeforeNotify() {
        // 真机反馈：「当前显示原图（纯原图态）切换底图没作用」。
        // 根因：`warmSrBase` 以前是 `scope.launch`（发射后不管）→ 宿主 `applyPageVisual` 预热还没落地
        // 就 `notifyItemChanged` → 适配器 `cachedDisplayBitmap` 取不到底图 → 回落源图，
        // 而预热完成后**没有任何东西再触发重绑** → 屏幕一直停在源图。
        // 译文/原文态走 `visualBitmap`（同步 await）所以正常 —— 这也解释了"只有纯原图态没作用"。
        val ctl = controller()
        val i = ctl.indexOf("suspend fun warmSrBase(")
        assertTrue("warmSrBase 必须是 suspend（调用方才能 await）", i > 0)
        val body = ctl.substring(i, i + 900)
        assertFalse("不许再在里面 scope.launch（那就是发射后不管）", body.contains("scope.launch"))
        assertTrue("要就地 withContext(IO)", body.contains("withContext(Dispatchers.IO)"))
        // 宿主侧：预热与 notify 必须在**同一个** IO 协程里顺序执行
        val act = activity()
        val j = act.indexOf("controller.warmSrBase(pageIndex)")
        val around = act.substring(j - 400, j + 300)
        assertTrue("宿主必须在 notify 之前 await 它", around.contains("controller.warmSrBase(pageIndex)"))
        assertTrue("notify 必须排在预热之后", act.indexOf("pageAdapter?.notifyItemChanged(pageIndex)", j) > j)
    }
    @Test
    fun panelRefreshesSrRowsWhenComingBackFromModelPage() {
        // 真机反馈：「切换超分模型返回后颜色面板没有刷新更新」。
        // 面板是打开那一刻的快照（模型行文案在 onCreateView 里读一次），从模型管理页回来必须重读。
        val sheet = menuSheet()
        assertTrue("面板要有 notifySrChanged 入口", sheet.contains("fun notifySrChanged()"))
        assertTrue("它要调 onCreateView 里赋的刷新闭包", sheet.contains("refreshSrRows?.invoke()"))
        assertTrue("闭包里要重读模型行文案", sheet.contains("getString(R.string.reader_sr_model_row, srModelLabel(p2))"))
        assertTrue("闭包里要重算整组显隐/开关/Anime4K", sheet.contains("refreshSrGroup()"))
        val act = activity()
        assertTrue(
            "宿主回到前台必须推一次",
            act.contains("?.notifySrChanged()"),
        )
    }

    @Test
    fun colorPanelPreviewShowsTheUpscaledResult() {
        // 用户口径：「在调色面板启用超分或者切换超分模型，面板的预览应该要实时更新超分的结果」。
        val sheet = menuSheet()
        val act = activity()
        val ctl = controller()
        assertTrue("预览格要优先用超分预览", sheet.contains("= state.srPreviewBitmap"))
        assertTrue("state 要有这一格", sheet.contains("val srPreviewBitmap: Bitmap? = null"))
        assertTrue("宿主必须准备它", act.contains("translationController?.srPreviewFor(currentPage, bmp.width)"))
        assertTrue("要传给面板", act.contains("srPreviewBitmap = srPreview,"))
        assertTrue("控制器提供按目标宽度缩放的入口", ctl.contains("suspend fun srPreviewFor(pageIndex: Int, targetWidth: Int): Bitmap?"))
        // ⚠️ 必须在 IO 侧做（预览格是主线程渲染的）
        assertTrue("缩放必须在 IO 线程", ctl.contains("= withContext(Dispatchers.IO) {"))
        assertFalse("不许在面板里读盘/缩放", sheet.contains("SrStore"))
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
            "超分关闭（HIDDEN）时**整组** GONE（超分组现在是独立的一组）",
            act.contains("ReaderTranslationController.SrAction.HIDDEN") &&
                act.contains("binding.srGroup.visibility = View.GONE"),
        )
    }
}
