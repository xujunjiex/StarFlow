package com.moe.starflow.mangaimport.reader

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 阅读器翻译面板守卫（模式三选项的**配色**与**状态回灌**）。
 *
 * 这两条都曾在真机上出问题，且都卡在框架边界上，纯函数单测盖不住：
 *
 * 1. **浅色面板下模式文字是白的**：三个选项是 `RadioButton`（本面板唯一的系统按钮类控件），
 *    按钮/文字颜色来自主题 `Theme.MaterialComponents.DayNight` —— Manifest 给
 *    MangaReaderActivity 指定的正是它，浅色分支解析出来的文字颜色很浅；而面板底色由
 *    [ReaderMenuSheet.applyPanelTheme] 写死成 `0xFFFFFFFF` → 白字压白底，既看不清也点不准。
 *    所以必须和同面板的 `TextView` 一样显式着色，并显式 tint 按钮。
 *
 * 2. **面板打开自动回退手动后，UI 停在旧选中项**：`setPanelOpen(true)` 会把模式回退到手动，
 *    但面板的 `isChecked` 只在创建时读一次。面板显示着「自动」而实际是手动，用户想切回自动时
 *    点的正是那个已勾选的条目 → `RadioButton` 在同组内重复选中**不派发** `onCheckedChanged`
 *    → 模式彻底切不动。宿主必须经 [ReaderMenuSheet.setTranslateMode] 单向回灌。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderMenuSheetTranslateTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun sheet(
        mode: Int = 0,
        dark: Boolean = false,
        onMode: (Int) -> Unit = {}
    ): ReaderMenuSheet = ReaderMenuSheet(
        ReaderMenuState(translateMode = mode, pageTranslations = emptyList(), isDarkPanel = dark),
        ReaderMenuCallbacks(
            onMode = {}, onAnimation = {}, onBackground = {}, onAutoTurn = { _, _ -> },
            onColorFilterChanged = {}, onResetColor = {}, onRotate = {}, onDownload = {},
            onSettings = {},
            // 默认「宿主」跟随面板初始模式，复刻真实接线（两者本就同源）；
            // 需要验证回退场景的测试自己再单独造一个常量回调
            onTranslateMode = onMode,
            currentTranslateMode = { mode }
        )
    ).apply {
        // 语言下拉要读真实 prefs（OcrEngineManager / CustomPreference），Robolectric 下噪音大且与
        // 本测试无关 —— 面板构造时会调它，这里直接换掉
        languagesList = { _, _ -> emptyList() }
    }

    /**
     * 只构建翻译面板里被本测试关心的那部分，塞进真实的 `sheet_reader_menu.xml` 里再走一次
     * 真正的 `onCreateView`。
     *
     * ⚠️ 锚点必须存在：`onCreateView` 返回的是 `<merge>`（没有自己的根 id），直接挂上去正是
     * 生产里的用法；`onViewReady` 注入的子树补上 `applyPanelTheme` 会去找的那些控件。
     */
    private fun buildPanel(s: ReaderMenuSheet): View {
        s.onViewReady = { v ->
            val host = v as ViewGroup
            val content = LinearLayout(v.context)
            content.id = R.id.panel_translate
            content.orientation = LinearLayout.VERTICAL

            val group = RadioGroup(v.context)
            group.id = R.id.seg_translate_mode
            listOf(
                R.id.translate_mode_manual to R.string.reader_translate_mode_manual,
                R.id.translate_mode_auto to R.string.reader_translate_mode_auto,
                R.id.translate_mode_incremental to R.string.reader_translate_mode_incremental
            ).forEach { (id, textRes) ->
                val rb = RadioButton(v.context)
                rb.id = id
                rb.setText(textRes)
                group.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(group)

            // 「字体大小」行：与 OCR模型/翻译模型 同款的单行文本。漏登记进 applyPanelTheme 的话，
            // 它在深色面板下会保持布局里的默认色（深色字压在深色背景上，看不见）。
            val sizeRow = LinearLayout(v.context)
            sizeRow.orientation = LinearLayout.HORIZONTAL
            val sizeText = TextView(v.context)
            sizeText.id = R.id.tv_font_size_row
            sizeText.setText(R.string.manga_font_size_title)
            sizeRow.addView(sizeText)
            content.addView(sizeRow)

            host.addView(content, 0)
        }
        // ⚠️ 必须让 fragment 真正 attached：ReaderMenuSheet 的 onCreateView 里会读
        // `resources`（applyPanelTheme 要按 density 算圆角），而裸 fragment 调
        // requireContext() 会抛 "Fragment ... not attached to a context"。
        // host 类与 R.layout 无关，用最便宜的 Activity 即可；create() 才会把 FragmentManager 接到宿主上
        val controller = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).create().start().resume()
        val host = controller.get()
        val fm = host.supportFragmentManager
        fm.beginTransaction().add(s, "under-test").commitNow()
        return s.view!!
    }

    // ===== 问题 1：模式选项必须与面板底色分得开 =====

    @Test
    fun lightPanel_modeRowsDoNotFightThePanelBackground() {
        assertReadable(buildPanel(sheet(dark = false)), dark = false)
    }

    @Test
    fun darkPanel_modeRowsDoNotFightThePanelBackground() {
        assertReadable(buildPanel(sheet(dark = true)), dark = true)
    }

    /** 模式选项的文字色与面板底色亮度必须拉开差距，否则就是"白字白底"。 */
    private fun assertReadable(view: View, dark: Boolean) {
        val panelColor = (view.findViewById<View>(R.id.sheet_root).background as? ColorDrawable)?.color
        assertNotNull("面板底色应由 applyPanelTheme 设置", panelColor)
        modeIds.forEach { id ->
            val rb = view.findViewById<RadioButton>(id)
            assertNotNull("模式选项 $id 缺失", rb)
            assertReadableColor("模式选项 $id", rb.currentTextColor, panelColor!!, dark)
        }
    }

    /**
     * 面板里**任何新增的文字控件**都必须与面板底色分得开。
     *
     * 这条守卫是被真实返工逼出来的：给翻译面板加「译文大小」行时，值控件漏登记进
     * [ReaderMenuSheet.applyPanelTheme]，深色面板下它保持布局默认的深色文字，
     * 与自绘的深色背景同色 → 完全看不见。
     */
    @Test
    fun fontSizeRow_followsPanelTheme() {
        val light = buildPanel(sheet(dark = false))
        val dark = buildPanel(sheet(dark = true))
        val lightColor = light.findViewById<TextView>(R.id.tv_font_size_row).currentTextColor
        val darkColor = dark.findViewById<TextView>(R.id.tv_font_size_row).currentTextColor
        assertTrue(
            "字体大小行必须随面板深浅换色（浅=#${hex(lightColor)} 深=#${hex(darkColor)}）——" +
                "相同说明没接 applyPanelTheme",
            lightColor != darkColor
        )
        assertReadableColor("字体大小行(浅)", lightColor, panelBg(light), false)
        assertReadableColor("字体大小行(深)", darkColor, panelBg(dark), true)
        // 必须与「OCR模型 / 翻译模型」同字号：面板正文行统一 14sp，不能自成一档
        val modelRow = light.findViewById<TextView>(R.id.tv_ocr_model_row)
        assertEquals(
            "字体大小行的字号必须与 OCR模型/翻译模型 两行一致",
            modelRow.textSize, light.findViewById<TextView>(R.id.tv_font_size_row).textSize, 0.01f
        )
    }

    private fun panelBg(view: View): Int =
        (view.findViewById<View>(R.id.sheet_root).background as? ColorDrawable)?.color ?: 0

    private fun assertReadableColor(what: String, color: Int, bg: Int, dark: Boolean) {
        val gap = kotlin.math.abs(luminance(color) - luminance(bg))
        assertTrue(
            "$what 与面板底色亮度差过小（dark=$dark text=#${hex(color)}，bg=#${hex(bg)}）—— 就是字压在底色上",
            gap > 0.25
        )
    }

    /** 按钮圆圈也必须显式 tint —— 默认 tint 同样来自 DayNight 主题。 */
    @Test
    fun modeRows_haveExplicitButtonTint() {
        val view = buildPanel(sheet())
        modeIds.forEach { id ->
            assertNotNull(
                "RadioButton $id 必须显式设 buttonTintList，否则按钮颜色由主题决定",
                view.findViewById<RadioButton>(id).buttonTintList
            )
        }
    }

    private fun luminance(color: Int): Double {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun hex(c: Int) = String.format("%08X", c)

    // ===== 问题 2：宿主回灌模式 =====

    @Test
    fun setTranslateMode_updatesCheckedState() {
        val s = sheet(mode = 0)
        val view = buildPanel(s)
        s.setTranslateMode(1)
        assertFalse(view.findViewById<RadioButton>(R.id.translate_mode_manual).isChecked)
        assertTrue(view.findViewById<RadioButton>(R.id.translate_mode_auto).isChecked)
        assertEquals(1, checkCount(view))
    }

    @Test
    fun setTranslateMode_doesNotEchoBackToHost() {
        // 回灌是**宿主 → 面板**的单向同步：不能反过来再调 onTranslateMode，
        // 否则形成「宿主改 → 面板回调 → 宿主再改」的回环
        var calls = 0
        val s = sheet(mode = 0, onMode = { calls++ })
        val view = buildPanel(s)
        s.setTranslateMode(1)
        assertEquals("回灌不得回调宿主", 0, calls)
        assertTrue(view.findViewById<RadioButton>(R.id.translate_mode_auto).isChecked)
    }

    /** 回灌到同一模式必须幂等：不能把用户的选中态抖掉。 */
    @Test
    fun setTranslateMode_sameModeIsIdempotent() {
        val s = sheet(mode = 1)
        val view = buildPanel(s)
        s.setTranslateMode(1)
        s.setTranslateMode(1)
        assertTrue(view.findViewById<RadioButton>(R.id.translate_mode_auto).isChecked)
        assertEquals(1, checkCount(view))
    }

    /** 三个下标各自都要能回灌到位（0/1/2 一一对应）。 */
    @Test
    fun setTranslateMode_mapsAllThreeIndices() {
        for (mode in 0..2) {
            val s = sheet(mode = (mode + 1) % 3)
            val view = buildPanel(s)
            s.setTranslateMode(mode)
            modeIds.forEachIndexed { i, id ->
                assertEquals(
                    "mode=$mode 时 $id 的选中态不对",
                    i == mode, view.findViewById<RadioButton>(id).isChecked
                )
            }
            assertEquals(1, checkCount(view))
        }
    }

    /**
     * 复刻用户复现路径：面板打开时宿主把模式回退到手动（面板还停在打开前的「自动」），
     * 用户想切回自动 → 必须切得动。
     */
    @Test
    fun afterHostFallsBackToManual_userCanSwitchBackToAuto() {
        var lastMode = -1
        val s = sheet(mode = 1, onMode = { lastMode = it })
        val view = buildPanel(s)
        val rbAuto = view.findViewById<RadioButton>(R.id.translate_mode_auto)
        assertTrue("初始为自动", rbAuto.isChecked)

        // 宿主侧回退手动（等价于 setPanelOpen(true) 里 pauseToManual 的效果）→ 回灌面板
        s.setTranslateMode(0)
        assertTrue("回灌后手动选中", view.findViewById<RadioButton>(R.id.translate_mode_manual).isChecked)
        assertFalse(rbAuto.isChecked)

        // 用户点「自动」—— 现在必须真的触发回调
        rbAuto.performClick()
        assertEquals("用户切回自动必须生效", 1, lastMode)
    }

    /** 问题本体：不回灌时点已选中的条目毫无反应，正是"模式切不动"的来源。 */
    @Test
    fun withoutReapply_clickingAlreadyCheckedItemDoesNothing() {
        var lastMode = -1
        val s = sheet(mode = 1, onMode = { lastMode = it })
        val view = buildPanel(s)
        view.findViewById<RadioButton>(R.id.translate_mode_auto).performClick()
        assertEquals("同组内重复选中不派发回调 —— 这正是需要回灌的原因", -1, lastMode)
    }

    @Test
    fun getTranslateMode_reflectsCurrentSelection() {
        val s = sheet(mode = 2)
        buildPanel(s)
        assertEquals(2, s.getTranslateMode())
        s.setTranslateMode(0)
        assertEquals(0, s.getTranslateMode())
    }

    /** sheet 未创建视图时回灌不得崩溃（宿主可能在 sheet 已 dismiss 后仍同步一次）。 */
    @Test
    fun setTranslateMode_beforeViewCreatedIsSafe() {
        val s = sheet(mode = 0)
        s.setTranslateMode(2)   // 无 view
        assertEquals(2, s.getTranslateMode())
    }

    private val modeIds = listOf(
        R.id.translate_mode_manual, R.id.translate_mode_auto, R.id.translate_mode_incremental
    )

    private fun checkCount(view: View) = modeIds.count { view.findViewById<RadioButton>(it).isChecked }
    /**
     * **接线守卫（源码级）**：宿主必须真的把 `currentTranslateMode` 接上。
     *
     * 这条接线不做的话，问题 2 会静默复发：控制器回退了，面板却不知道自己在显示旧模式。
     * 之所以用源码断言而不是行为断言 —— `ReaderMenuCallbacks` 的回调是构造参数（val），
     * 要在测试里替换就得把它们全改成 var，为一个断言污染 20 个字段的可变性不划算。
     */
    @Test
    fun hostWiresCurrentTranslateModeIntoCallbacks() {
        val src = java.io.File("src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt")
        assertTrue("找不到 MangaReaderActivity 源码：${src.absolutePath}", src.exists())
        val text = src.readText()
        assertTrue(
            "MangaReaderActivity 必须给 ReaderMenuCallbacks 传 currentTranslateMode（回读宿主真实模式），" +
                "否则面板打开被回退成手动后，UI 会停在旧选中项、模式再也切不动",
            text.contains("currentTranslateMode = ")
        )
        assertTrue(
            "面板打开后回退手动必须提示用户（modeBeforeOpen 判定）",
            text.contains("modeBeforeOpen")
        )
    }

    /**
     * **回归守卫**：曾因 `restoredTranslateMode != MODE_MANUAL` 漏掉初值 -1，
     * 导致**一进阅读器就自动开始翻译**。
     *
     * 判据必须是 `>= 0`（-1 = 本次不是从设置页返回触发的重建）。
     * 这类"少写一个边界判断"编译不报错、行为测试又跑不到（要真机 + Room），只能盯源码。
     */
    @Test
    fun restoredTranslateModeGuard_keepsMinusOneOutOfTheController() {
        val src = java.io.File(
            "src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt"
        )
        assertTrue("找不到阅读器源码：${src.absolutePath}", src.exists())
        val text = src.readText()
        assertTrue(
            "恢复翻译模式必须先判 `>= 0`：-1 表示「不是从设置页返回」，直接塞给控制器会一进阅读器就开翻",
            text.contains("if (restoredTranslateMode >= 0)")
        )
        assertTrue(
            "不能写成 `!= ReaderTranslationController.MODE_MANUAL`（对 -1 为真，正是事故写法）",
            !text.contains("restoredTranslateMode != ReaderTranslationController.MODE_MANUAL")
        )
    }
}
