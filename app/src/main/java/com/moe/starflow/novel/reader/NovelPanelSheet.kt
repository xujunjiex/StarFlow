package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.manga.config.OcrEngineGroup
import com.moe.starflow.mangaimport.translate.ReaderTranslationInfo
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.LanguageSelectionDialog
import com.moe.starflow.translate.TranslateTools
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.MangaFontSize
import com.moe.starflow.utils.OcrEngineManager
import translationapi.hymt2translation.HyMt2Languages

/**
 * 小说阅读器底部工具栏的初始状态。
 *
 * 字段与 `ReaderMenuState` 一一对应（同名同义），只是把「页」换成「章」、
 * 把「颜色矫正」换成「排版」。
 */
class NovelPanelState(
    /** 阅读模式：0 左右翻页 / 1 上下翻页 / 2 连续滚动。 */
    val readerMode: Int = NovelPanelStyle.READER_PAGED,
    val animation: Int = NovelPanelStyle.ANIM_SLIDE,
    val bg: Int = 0,
    val displayMode: NovelDisplayMode = NovelDisplayMode.TRANSLATED,
    val fontSizeSp: Float = NovelPanelStyle.FONT_SIZE_DEFAULT,
    val lineSpacingStep: Int = NovelPanelStyle.LINE_SPACING_DEFAULT,
    val paragraphSpacingDp: Int = NovelPanelStyle.PARA_SPACING_DEFAULT,
    val paddingDp: Int = NovelPanelStyle.SIDE_PADDING_DEFAULT,
    val topPaddingDp: Int = NovelPanelStyle.VERTICAL_PADDING_MIN,
    val bottomPaddingDp: Int = NovelPanelStyle.VERTICAL_PADDING_MIN,
    val autoTurn: Boolean = false,
    val intervalSec: Int = 5,
    val rotateLabel: String = "",
    val isDarkPanel: Boolean = false,
    val translateMode: NovelTranslateMode = NovelTranslateMode.MANUAL,
    val debounceMs: Int = 500,
    val aheadBatches: Int = NovelQuota.DEFAULT,
    val batchSize: Int = NovelPanelStyle.BATCH_DEFAULT,
    val chapterCount: Int = 0,
    val currentChapter: Int = 0,
    val chapters: List<NovelChapterMeta> = emptyList(),
    val chapterStats: Map<Int, NovelChapterStat> = emptyMap(),
)

/** 小说阅读器底部工具栏回调。 */
class NovelPanelCallbacks(
    val onReaderMode: (Int) -> Unit,
    val onAnimation: (Int) -> Unit,
    val onBackground: (Int) -> Unit,
    val onDisplayMode: (NovelDisplayMode) -> Unit,
    val onFontSize: (Float) -> Unit = {},
    val onLineSpacing: (Int) -> Unit,
    val onParagraphSpacing: (Int) -> Unit,
    val onPadding: (Int) -> Unit,
    val onResetTypography: () -> Unit = {},
    val onTopPadding: (Int) -> Unit = {},
    val onBottomPadding: (Int) -> Unit = {},
    val onAutoTurn: (Boolean, Int) -> Unit,
    val onRotate: () -> Unit,
    val onSettings: () -> Unit,
    val onTranslateMode: (NovelTranslateMode) -> Unit = {},

    /**
     * 宿主**此刻**的真实模式。
     *
     * ⚠️ 不能用 `state.translateMode`：那是构造面板那一刻的快照，而宿主在打开面板时会
     * `pauseToManual()` 把模式回退成手动 —— 不回读的话面板会一直显示「自动」选中，
     * 用户以为自动还在跑（漫画那份同样处理，见 `cb.currentTranslateMode()`）。
     */
    val currentTranslateMode: () -> NovelTranslateMode = { NovelTranslateMode.MANUAL },

    /** 某章有多少段（章行的分母）：宿主懒解析后回推。 */
    val onNeedChapterTotal: (Int) -> Unit = {},
    val onDebounceMs: (Int) -> Unit = {},
    val onAheadBatches: (Int) -> Unit = {},
    val onBatchSize: (Int) -> Unit = {},
    val onTranslateNow: () -> Unit = {},
    val onClearBook: () -> Unit = {},
    val onChapterJump: (Int) -> Unit = {},
    val onPanelOpened: () -> Unit = {},
    val onPanelClosed: () -> Unit = {},
    val onOpenApiConfig: () -> Unit = {},
)

/**
 * 小说阅读器底部工具栏（四 Tab）：翻页 / 翻译 / 样式 / 更多。
 *
 * ⚠️ **面板骨架与 `ReaderMenuSheet` 是同一套**：同一批 drawable（`ic_sel_circle` /
 * `ic_sel_active` / `ic_tab_sel` / `bg_bottom_sheet_*`）、同样的 52dp Tab 格、同样的
 * 分段控件实现与选中态、同样的行/滑块/RadioButton 配色逻辑。差异只在内容项。
 *
 * 面板配色随阅读背景深浅联动（[darkPanel]），**不随全局主题** —— 与漫画阅读器一致。
 */
class NovelPanelSheet(
    private val state: NovelPanelState,
    private val cb: NovelPanelCallbacks,
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "NovelPanelSheet"
    }

    /** 面板深浅（随阅读背景切换即时更新）。 */
    private var darkPanel = state.isDarkPanel

    private var appPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ===== 面板内的「工作状态」=====
    //
    // ⚠️ **派生 UI 必须只有一个重算出口**（[refreshDerivedUi]）。这类 bug 反复出现过：
    //   · 从「连续滚动」切到「左右/上下翻页」后，翻页动画该立刻解除置灰，但面板没刷
    // 根因都一样 —— 派生关系（A 决定 B 能不能用）只在 `onCreateView` 里算了一次，
    // 之后再改 A 就没人重算了。所以：**任何控件改完都更新这里的字段，然后统一调
    // [refreshDerivedUi]**，不给「顺手在某处再补一句」留口子。

    /** 当前阅读模式（0 左右 / 1 上下 / 2 滚动）。 */
    private var curReaderMode = NovelPanelStyle.READER_PAGED

    private var curFontSp = NovelPanelStyle.FONT_SIZE_DEFAULT
    private var curLineStep = NovelPanelStyle.LINE_SPACING_DEFAULT

    /** 正在由 [refreshDerivedUi] 程序化改控件（避免回调回环）。 */
    private var syncing = false

    /** 各分段容器当前选中项（容器Id → 选中segmentId），供主题切换后重画。 */
    private val selection = mutableMapOf<Int, Int>()

    /** 背景 glyph 分段（不 tint 图标，只用选中容器高亮）——行为与漫画面板一致。 */
    private val bgSegIds = setOf(
        R.id.seg_bg_default, R.id.seg_bg_light, R.id.seg_bg_dark, R.id.seg_bg_white, R.id.seg_bg_black,
    )

    private var translateMode = NovelTranslateMode.MANUAL
    private var reapplyingMode = false
    private var currentFilterKey = 0

    private val chapterAdapter by lazy {
        NovelChapterStateAdapter(onJump = { cb.onChapterJump(it) })
    }

    private val dp8: Int get() = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onStart() {
        super.onStart()
        cb.onPanelOpened()
        reapplySheetContainerBg()
        refreshModelRows()
        refreshLangRowIfReady()
        view?.let { applyFilter(currentFilterKey, it) }
        val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
        appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "Text_API", "Text_AI", "OpenAI_Selected_Provider" -> refreshModelRows()
                "Source_Language", "Target_Language" -> refreshLangRowIfReady()
            }
        }
        sp.registerOnSharedPreferenceChangeListener(appPrefsListener)
    }

    override fun onStop() {
        appPrefsListener?.let {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        appPrefsListener = null
        // 面板关闭 → 恢复队列：这就是「选了自动/增量也要等退出面板才开始翻」
        cb.onPanelClosed()
        super.onStop()
    }

    private fun refreshLangRowIfReady() {
        view?.let { refreshLangRow(it) }
    }

    /** 外层 BottomSheet 容器背景随当前深浅。 */
    private fun reapplySheetContainerBg() {
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(if (darkPanel) R.drawable.bg_bottom_sheet_dark else R.drawable.bg_bottom_sheet_light)
    }

    private fun refreshModelRows() {
        val v = view ?: return
        // 小说没有 OCR，只有翻译模型一行
        v.findViewById<TextView>(R.id.tv_translator_model_row).text =
            getString(R.string.reader_translate_translator_model, ReaderTranslationInfo.translatorModelLabel(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_novel_menu, container, false)
        val root = view.findViewById<View>(R.id.sheet_root)
        applyPanelTheme(view, root)

        val panels = listOf(
            view.findViewById<View>(R.id.tab_paging) to view.findViewById<View>(R.id.panel_paging),
            view.findViewById<View>(R.id.tab_translate) to view.findViewById<View>(R.id.panel_translate),
            view.findViewById<View>(R.id.tab_style) to view.findViewById<View>(R.id.panel_style),
            view.findViewById<View>(R.id.tab_more) to view.findViewById<View>(R.id.panel_more),
        )
        fun show(selected: View, panel: View) {
            panels.forEach { (tv, p) ->
                setTab(tv, tv === selected)
                p.visibility = if (p === panel) View.VISIBLE else View.GONE
            }
        }
        panels.forEach { (tv, p) -> tv.setOnClickListener { show(tv, p) } }
        show(panels[0].first, panels[0].second)

        // ---- 翻页：阅读模式 / 翻页动画 / 背景 ----
        setupSeg(view, R.id.seg_mode, listOf(
            R.id.seg_mode_paged to (state.readerMode == NovelPanelStyle.READER_PAGED),
            R.id.seg_mode_vertical to (state.readerMode == NovelPanelStyle.READER_VERTICAL),
            R.id.seg_mode_scroll to (state.readerMode == NovelPanelStyle.READER_SCROLL),
        )) { id ->
            curReaderMode = when (id) {
                R.id.seg_mode_vertical -> NovelPanelStyle.READER_VERTICAL
                R.id.seg_mode_scroll -> NovelPanelStyle.READER_SCROLL
                else -> NovelPanelStyle.READER_PAGED
            }
            // 切到「连续滚动」要立刻置灰翻页动画、切回来立刻解除 —— 见 refreshDerivedUi
            refreshDerivedUi(view)
            cb.onReaderMode(curReaderMode)
        }

        setupSeg(view, R.id.seg_animation, listOf(
            R.id.seg_anim_none to (state.animation == NovelPanelStyle.ANIM_NONE),
            R.id.seg_anim_default to (state.animation == NovelPanelStyle.ANIM_SLIDE),
            R.id.seg_anim_advanced to (state.animation == NovelPanelStyle.ANIM_COVER),
            R.id.seg_anim_simulation to (state.animation == NovelPanelStyle.ANIM_SIMULATION),
        )) { id ->
            cb.onAnimation(
                when (id) {
                    R.id.seg_anim_none -> NovelPanelStyle.ANIM_NONE
                    R.id.seg_anim_advanced -> NovelPanelStyle.ANIM_COVER
                    R.id.seg_anim_simulation -> NovelPanelStyle.ANIM_SIMULATION
                    else -> NovelPanelStyle.ANIM_SLIDE
                }
            )
        }

        setupSeg(view, R.id.seg_background, listOf(
            R.id.seg_bg_default to (state.bg == 0),
            R.id.seg_bg_light to (state.bg == 1),
            R.id.seg_bg_dark to (state.bg == 2),
            R.id.seg_bg_white to (state.bg == 3),
            R.id.seg_bg_black to (state.bg == 4),
        )) { id ->
            val newBg = when (id) {
                R.id.seg_bg_light -> 1
                R.id.seg_bg_dark -> 2
                R.id.seg_bg_white -> 3
                R.id.seg_bg_black -> 4
                else -> 0
            }
            // 点背景即切，面板深浅立刻跟随
            darkPanel = NovelPanelStyle.isDarkBackground(newBg)
            applyPanelTheme(view, root)
            cb.onBackground(newBg)
        }

        // ---- 样式：显示三态 + 行距/段距/边距 ----
        val tvDisplay = view.findViewById<TextView>(R.id.tv_display_value)
        tvDisplay.text = NovelPanelStyle.displayModeLabel(requireContext(), state.displayMode)
        view.findViewById<View>(R.id.btn_display_mode).setOnClickListener {
            val next = NovelDisplayModeCodec.next(
                readDisplayFromLabel(tvDisplay.text.toString())
            )
            tvDisplay.text = NovelPanelStyle.displayModeLabel(requireContext(), next)
            cb.onDisplayMode(next)
        }

        // ---- 排版：恢复默认 / 字号 / 行距 / 段距 / 左右边距 / 上下间距 ----
        //
        // ⚠️ 每个滑块松手就回调一次，阅读器立刻重排并重画 —— 参数是**直接生效**的，
        // 没有「自动排版」在中间改写用户设的值（那个功能已删除）。
        val sbFont = view.findViewById<SeekBar>(R.id.sb_font_size)

        val tvFontValue = view.findViewById<TextView>(R.id.tv_font_size_value)
        val sbLine = view.findViewById<SeekBar>(R.id.sb_line_spacing)
        val tvLine = view.findViewById<TextView>(R.id.tv_line_spacing_value)
        val sbPara = view.findViewById<SeekBar>(R.id.sb_para_spacing)
        val tvPara = view.findViewById<TextView>(R.id.tv_para_spacing_value)
        val sbPad = view.findViewById<SeekBar>(R.id.sb_padding)
        val tvPad = view.findViewById<TextView>(R.id.tv_padding_value)
        val sbTop = view.findViewById<SeekBar>(R.id.sb_top_padding)
        val tvTopValue = view.findViewById<TextView>(R.id.tv_top_padding_value)
        val sbBottom = view.findViewById<SeekBar>(R.id.sb_bottom_padding)
        val tvBottomValue = view.findViewById<TextView>(R.id.tv_bottom_padding_value)

        view.findViewById<View>(R.id.btn_reset_typography).setOnClickListener {
            cb.onResetTypography()
            // ⚠️ 恢复默认是**宿主**在 prefs 上改的值，而面板持有的是打开面板那一刻的快照 ——
            // 不回读的话滑块还停在旧位置，用户看到的就是「点了恢复默认面板没反应」。
            reloadTypography(view)
        }

        // 初始值统一由 [bindTypography] 填：与「恢复默认」后的回读是同一段代码
        curReaderMode = state.readerMode
        bindTypography(
            view,
            fontSp = state.fontSizeSp,
            lineStep = state.lineSpacingStep,
            paraDp = state.paragraphSpacingDp,
            padDp = state.paddingDp,
            topDp = state.topPaddingDp,
            bottomDp = state.bottomPaddingDp,
        )

        sbFont.setOnSeekBarChangeListener(sliderLabel({ tvFontValue.text = "$it sp" }) {
            curFontSp = it.toFloat()
            cb.onFontSize(curFontSp)
            // 字号变了 → 段距的区间跟着变
            refreshDerivedUi(view)
        })

        sbLine.setOnSeekBarChangeListener(sliderLabel(
            onLabel = { tvLine.text = NovelPanelStyle.lineSpacingLabel(it) },
            onSettle = { v ->
                curLineStep = v
                cb.onLineSpacing(v)
                // 行距一变，段距的区间跟着变 → 统一在 refreshDerivedUi 里重算
                refreshDerivedUi(view)
            },
        ))

        sbPara.setOnSeekBarChangeListener(sliderLabel({ tvPara.text = "$it dp" }) { cb.onParagraphSpacing(it) })

        sbPad.setOnSeekBarChangeListener(sliderLabel({ tvPad.text = "$it dp" }) { cb.onPadding(it) })

        sbTop.setOnSeekBarChangeListener(sliderLabel({ tvTopValue.text = "$it dp" }) { cb.onTopPadding(it) })
        sbBottom.setOnSeekBarChangeListener(sliderLabel({ tvBottomValue.text = "$it dp" }) { cb.onBottomPadding(it) })

        refreshDerivedUi(view)

        // ---- 翻译：模式 / 防抖 / 向后章数 / 每批段数 / 模型 / 字号 / 语言 / 章节列表 ----
        val rbManual = view.findViewById<RadioButton>(R.id.translate_mode_manual)
        val rbAuto = view.findViewById<RadioButton>(R.id.translate_mode_auto)
        val rbAhead = view.findViewById<RadioButton>(R.id.translate_mode_incremental)
        translateMode = cb.currentTranslateMode()
        when (translateMode) {
            NovelTranslateMode.AUTO -> rbAuto.isChecked = true
            NovelTranslateMode.AHEAD -> rbAhead.isChecked = true
            NovelTranslateMode.MANUAL -> rbManual.isChecked = true
        }
        rbManual.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = NovelTranslateMode.MANUAL; cb.onTranslateMode(translateMode) }
            applyAheadRowVisibility(view, translateMode)
        }
        rbAuto.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = NovelTranslateMode.AUTO; cb.onTranslateMode(translateMode) }
            applyAheadRowVisibility(view, translateMode)
        }
        rbAhead.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = NovelTranslateMode.AHEAD; cb.onTranslateMode(translateMode) }
            applyAheadRowVisibility(view, translateMode)
        }

        // ⚠️ 设了 android:min 的 SeekBar，progress 是**绝对值**（不是相对 0 的偏移）
        val sbDebounce = view.findViewById<SeekBar>(R.id.sb_debounce)
        val tvDebounce = view.findViewById<TextView>(R.id.tv_debounce_value)
        sbDebounce.progress = state.debounceMs.coerceIn(NovelPanelStyle.DEBOUNCE_MIN, NovelPanelStyle.DEBOUNCE_MAX)
        tvDebounce.text = "${state.debounceMs} ms"
        sbDebounce.setOnSeekBarChangeListener(slider {
            tvDebounce.text = "${sbDebounce.progress} ms"
            cb.onDebounceMs(sbDebounce.progress)
        })

        val sbAhead = view.findViewById<SeekBar>(R.id.sb_ahead)
        val tvAhead = view.findViewById<TextView>(R.id.tv_ahead_value)
        sbAhead.progress = state.aheadBatches.coerceIn(NovelQuota.MIN, NovelQuota.MAX)
        tvAhead.text = "${state.aheadBatches}"
        sbAhead.setOnSeekBarChangeListener(slider {
            tvAhead.text = "${sbAhead.progress}"
            cb.onAheadBatches(sbAhead.progress)
        })

        val sbBatch = view.findViewById<SeekBar>(R.id.sb_batch)
        val tvBatch = view.findViewById<TextView>(R.id.tv_batch_value)
        sbBatch.progress = state.batchSize
        tvBatch.text = "${state.batchSize}"
        sbBatch.setOnSeekBarChangeListener(slider {
            tvBatch.text = "${sbBatch.progress}"
            cb.onBatchSize(sbBatch.progress)
        })
        applyAheadRowVisibility(view, translateMode)

        view.findViewById<TextView>(R.id.tv_translator_model_row).text =
            getString(R.string.reader_translate_translator_model, ReaderTranslationInfo.translatorModelLabel(requireContext()))
        view.findViewById<View>(R.id.btn_model_translate).setOnClickListener { cb.onOpenApiConfig() }

        // 语言区
        refreshLangRow(view)
        view.findViewById<View>(R.id.btn_source_lang).setOnClickListener { showLangDialog(1) }
        view.findViewById<View>(R.id.btn_target_lang).setOnClickListener { showLangDialog(2) }
        view.findViewById<View>(R.id.btn_swap_lang).setOnClickListener {
            val prefs = CustomPreference.getInstance(requireContext())
            val s = prefs.getString("Source_Language", "auto")
            val t = prefs.getString("Target_Language", "zh")
            prefs.setString("Source_Language", t)
            prefs.setString("Target_Language", s)
            refreshLangRow(view)
        }

        view.findViewById<View>(R.id.btn_translate_action).setOnClickListener { cb.onTranslateNow() }
        view.findViewById<View>(R.id.btn_translate_clear).setOnClickListener { cb.onClearBook() }

        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = chapterAdapter
        chapterAdapter.chapters = state.chapters
        chapterAdapter.stats = state.chapterStats
        chapterAdapter.onNeedTotal = { cb.onNeedChapterTotal(it) }
        chapterAdapter.currentChapter = state.currentChapter
        liveStats = state.chapterStats
        updateSummary()
        setupTranslateFilter(view)

        // ---- 更多：旋转 / 自动翻页 / 目录 / 设置 ----
        view.findViewById<TextView>(R.id.tv_rotate_value).text = state.rotateLabel
        view.findViewById<View>(R.id.btn_rotate).setOnClickListener { dismiss(); cb.onRotate() }

        val swAutoTurn = view.findViewById<Switch>(R.id.sw_auto_turn)
        val tvInterval = view.findViewById<TextView>(R.id.tv_interval_value)
        var interval = state.intervalSec
        swAutoTurn.isChecked = state.autoTurn
        tvInterval.text = "$interval s"
        swAutoTurn.setOnCheckedChangeListener { _, checked -> cb.onAutoTurn(checked, interval) }
        tvInterval.setOnClickListener {
            interval = when (interval) { 3 -> 5; 5 -> 8; 8 -> 12; else -> 3 }
            tvInterval.text = "$interval s"
            cb.onAutoTurn(swAutoTurn.isChecked, interval)
        }

        view.findViewById<View>(R.id.btn_settings).setOnClickListener { dismiss(); cb.onSettings() }

        // 连续滚动下没有「翻页动画」这个概念（滚动即翻页）：置灰
        applyModeDependence(view, state.readerMode)

        return view
    }

    /** 显示三态的值文本 → 枚举（面板只是个不含状态的循环按钮，从显示值反推）。 */
    private fun readDisplayFromLabel(label: String): NovelDisplayMode = when (label) {
        getString(R.string.novel_display_original) -> NovelDisplayMode.ORIGINAL
        getString(R.string.novel_display_bilingual) -> NovelDisplayMode.BILINGUAL
        else -> NovelDisplayMode.TRANSLATED
    }

    /** 连续滚动下翻页动画不生效：即时置灰。 */
    private fun applyModeDependence(view: View, readerMode: Int) {
        setSegEnabled(view.findViewById<ViewGroup>(R.id.seg_animation), readerMode != NovelPanelStyle.READER_SCROLL)
    }

    /** 向后翻译章数滑块只在「增量」模式显示（与漫画面板的 row_ahead_pages 同义）。 */
    private fun applyAheadRowVisibility(view: View, mode: NovelTranslateMode) {
        view.findViewById<View>(R.id.row_ahead_chapters).visibility =
            if (mode == NovelTranslateMode.AHEAD) View.VISIBLE else View.GONE
    }

    /**
     * 把一组排版参数写进控件（滑块位置 + 数值文本），并刷新派生 UI。
     *
     * ⚠️ **只有一个出口**：`onCreateView` 的初始填充与「恢复默认」后的回读都走这里 ——
     * 两边各写一套的话，迟早出现「打开时显示对了、复位后漏刷一个」。
     * 程序化设 `progress` 不会触发回调（[sliderLabel] 只在 `fromUser` 时回调节点），所以不必额外加锁。
     */
    private fun bindTypography(
        view: View,
        fontSp: Float,
        lineStep: Int,
        paraDp: Int,
        padDp: Int,
        topDp: Int,
        bottomDp: Int,
    ) {
        val sbFont = view.findViewById<SeekBar>(R.id.sb_font_size)
        sbFont.progress = fontSp.toInt()
            .coerceIn(NovelPanelStyle.FONT_SIZE_MIN.toInt(), NovelPanelStyle.FONT_SIZE_MAX.toInt())
        view.findViewById<TextView>(R.id.tv_font_size_value).text = "${sbFont.progress} sp"

        val sbLine = view.findViewById<SeekBar>(R.id.sb_line_spacing)
        sbLine.progress = lineStep
        view.findViewById<TextView>(R.id.tv_line_spacing_value).text =
            NovelPanelStyle.lineSpacingLabel(lineStep)

        val sbPara = view.findViewById<SeekBar>(R.id.sb_para_spacing)
        sbPara.progress = paraDp
        view.findViewById<TextView>(R.id.tv_para_spacing_value).text = "$paraDp dp"

        val sbPad = view.findViewById<SeekBar>(R.id.sb_padding)
        sbPad.progress = padDp
        view.findViewById<TextView>(R.id.tv_padding_value).text = "$padDp dp"

        val sbTop = view.findViewById<SeekBar>(R.id.sb_top_padding)
        sbTop.progress = topDp
        view.findViewById<TextView>(R.id.tv_top_padding_value).text = "$topDp dp"

        val sbBottom = view.findViewById<SeekBar>(R.id.sb_bottom_padding)
        sbBottom.progress = bottomDp
        view.findViewById<TextView>(R.id.tv_bottom_padding_value).text = "$bottomDp dp"

        // 工作状态同步：段距的合法区间是按「当前字号 + 当前行距」算的
        curFontSp = fontSp
        curLineStep = lineStep
        refreshDerivedUi(view)
    }

    /** 从 prefs 回读排版参数并刷新控件（见「恢复默认」按钮处的说明）。 */
    private fun reloadTypography(view: View) {
        val p = requireContext().getSharedPreferences(NovelPanelStyle.PREFS_NAME, Context.MODE_PRIVATE)
        val font = NovelPanelStyle.fontSizeSp(p)
        bindTypography(
            view,
            fontSp = font,
            lineStep = NovelPanelStyle.lineSpacingStep(p),
            paraDp = NovelPanelStyle.paragraphSpacingDp(p, font),
            padDp = NovelPanelStyle.paddingDp(p),
            topDp = NovelPanelStyle.topPaddingDp(p),
            bottomDp = NovelPanelStyle.bottomPaddingDp(p),
        )
    }

    /**
     * **重算全部派生 UI**（唯一出口，理由见 [curReaderMode] 上面的说明）。
     *
     * 只读 [curReaderMode] / [curFontSp] / [curLineStep] 这几个工作状态，
     * 所以任何控件改完调它一次就能收敛 —— 不需要（也不允许）在别处再补一句。
     */
    private fun refreshDerivedUi(view: View) {
        syncing = true
        try {
            // ① 阅读模式 → 连续滚动下没有「翻页动画」这个概念：立刻置灰；切回翻页立刻解除
            setSegEnabled(
                view.findViewById(R.id.seg_animation),
                curReaderMode != NovelPanelStyle.READER_SCROLL,
            )

            // ② 段距的区间跟着**字号 + 行距**走：段距必须始终大于行距
            val sbPara = view.findViewById<SeekBar>(R.id.sb_para_spacing)
            val min = NovelPanelStyle.minParagraphSpacingDp(curFontSp, curLineStep)
            val max = NovelPanelStyle.maxParagraphSpacingDp(curFontSp, curLineStep)
            sbPara.min = min
            // 上限也要跟着抬：字号/行距很大时 min 会顶到 48 以上，SeekBar 的 max 必须 >= min
            sbPara.max = max.coerceAtLeast(min + 1)
            if (sbPara.progress < min) sbPara.progress = min
            view.findViewById<TextView>(R.id.tv_para_spacing_value).text = "${sbPara.progress} dp"
        } finally {
            syncing = false
        }
    }

    // ===== 主题（与 ReaderMenuSheet.applyPanelTheme 同一套取值） =====

    private fun applyPanelTheme(view: View, root: View) {
        val dark = darkPanel
        root.setBackgroundColor(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        listOf(
            R.id.tv_mode_label, R.id.tv_animation_label, R.id.tv_background_label,
            R.id.tv_translate_mode_label,
            R.id.tv_rotate_label, R.id.tv_auto_turn_label, R.id.tv_settings_label,
            R.id.tv_translator_model_row,
            R.id.tv_source_lang_value, R.id.tv_target_lang_value,
            R.id.tv_debounce_label, R.id.tv_ahead_label, R.id.tv_batch_label,
            R.id.tv_display_label, R.id.tv_font_size_label,
            R.id.tv_line_spacing_label, R.id.tv_para_spacing_label,
            R.id.tv_padding_label,
            R.id.tv_top_padding_label, R.id.tv_bottom_padding_label,
        ).forEach { view.findViewById<TextView>(it).setTextColor(labelColor) }
        listOf(
            R.id.tv_rotate_value, R.id.tv_interval_value,
            R.id.tv_source_caption, R.id.tv_target_caption,
            R.id.tv_debounce_value, R.id.tv_ahead_value, R.id.tv_batch_value,
            R.id.tv_display_value, R.id.tv_font_size_value,
            R.id.tv_line_spacing_value, R.id.tv_para_spacing_value,
            R.id.tv_padding_value,
            R.id.tv_top_padding_value, R.id.tv_bottom_padding_value,
            R.id.tv_style_hint,
        ).forEach { view.findViewById<TextView>(it).setTextColor(subColor) }
        reapplySegments(view)
        chapterAdapter.dark = darkPanel
        // Switch 配色（避免与面板背景重叠/看不清）
        val swTrack = if (dark) 0xFF3A4046.toInt() else 0xFFCFD8DC.toInt()
        listOf(R.id.sw_auto_turn).forEach { id ->
            view.findViewById<Switch>(id).let {
                it.thumbTintList = ColorStateList.valueOf(0xFF55AEEA.toInt())
                it.trackTintList = ColorStateList.valueOf(swTrack)
            }
        }
        val spinnerColor = if (dark) 0xFFB8BCC2.toInt() else 0xFF777777.toInt()
        listOf(R.id.iv_source_spinner, R.id.iv_target_spinner).forEach {
            view.findViewById<ImageView>(it).setColorFilter(spinnerColor)
        }
        applyTranslateModeTheme(view, dark)
        reapplySheetContainerBg()
        val langCellBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(if (dark) 0xFF2A2A2C.toInt() else 0xFFF2F4F7.toInt())
        }
        listOf(R.id.btn_source_lang, R.id.btn_target_lang).forEach {
            view.findViewById<View>(it).background = langCellBg
        }
    }

    /** 翻译模式三选项（RadioButton）：文字与按钮 tint 必须显式给，否则浅色下白字压白底。 */
    private fun applyTranslateModeTheme(view: View, dark: Boolean) {
        val text = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val onColor = 0xFF55AEEA.toInt()
        val offColor = if (dark) 0xFFB8BCC2.toInt() else 0xFF777777.toInt()
        for (id in listOf(R.id.translate_mode_manual, R.id.translate_mode_auto, R.id.translate_mode_incremental)) {
            val rb = view.findViewById<RadioButton>(id) ?: continue
            rb.setTextColor(text)
            rb.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onColor, offColor)
            )
        }
    }

    private fun setTab(tab: View, active: Boolean) {
        val sel = (tab as? ViewGroup)?.getChildAt(0) as? ImageView
        val icon = (tab as? ViewGroup)?.getChildAt(1) as? ImageView
        if (active) {
            sel?.visibility = View.VISIBLE
            sel?.setImageResource(if (darkPanel) R.drawable.ic_tab_sel_dark else R.drawable.ic_tab_sel)
        } else {
            sel?.visibility = View.GONE
        }
        icon?.setColorFilter(
            if (active) 0xFF55AEEA.toInt() else if (darkPanel) 0xFFB8BCC2.toInt() else 0xFF777777.toInt()
        )
    }

    private fun setSegEnabled(container: ViewGroup, enabled: Boolean) {
        for (i in 0 until container.childCount) {
            val cell = container.getChildAt(i)
            cell.isEnabled = enabled
            cell.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun setupSeg(
        view: View,
        containerId: Int,
        segments: List<Pair<Int, Boolean>>,
        onPick: (Int) -> Unit,
    ) {
        val container = view.findViewById<ViewGroup>(containerId)
        val selectedId = segments.firstOrNull { it.second }?.first ?: segments[0].first
        selection[containerId] = selectedId
        segments.forEach { (id, selected) ->
            val cell = container.findViewById<View>(id)
            setSegStyle(cell, id, selected, darkPanel)
            cell.setOnClickListener {
                selection[containerId] = id
                segments.forEach { (oid, _) ->
                    container.findViewById<View>(oid).let { setSegStyle(it, oid, oid == id, darkPanel) }
                }
                onPick(id)
            }
        }
    }

    private fun reapplySegments(view: View) {
        selection.forEach { (containerId, selectedId) ->
            val container = view.findViewById<ViewGroup>(containerId)
            for (i in 0 until container.childCount) {
                val cell = container.getChildAt(i)
                setSegStyle(cell, cell.id, cell.id == selectedId, darkPanel)
            }
        }
    }

    private fun setSegStyle(cell: View, id: Int, selected: Boolean, dark: Boolean) {
        val group = cell as? ViewGroup
        val sel = group?.getChildAt(0) as? ImageView
        val icon = (group?.getChildAt(1) as? ImageView) ?: findDescendantIcon(group, skip = sel)
        if (selected) {
            sel?.visibility = View.VISIBLE
            sel?.setImageResource(if (dark) R.drawable.ic_sel_active_dark else R.drawable.ic_sel_active)
        } else {
            sel?.visibility = View.GONE
        }
        // 背景 glyph 不 tint；模式/动画图标随选中/深浅着色
        if (icon != null && id !in bgSegIds) {
            icon.setColorFilter(
                if (selected) 0xFF55AEEA.toInt() else if (dark) 0xFFB8BCC2.toInt() else 0xFF777777.toInt()
            )
        }
    }

    private fun findDescendantIcon(v: View?, skip: View?): ImageView? {
        if (v == null || v === skip) return null
        if (v is ImageView) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            findDescendantIcon(v.getChildAt(i), skip)?.let { return it }
        }
        return null
    }

    private fun slider(onRefresh: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onRefresh()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    /**
     * 滑块：数值标签**实时**跟手，但只在**松手**时才回调宿主。
     *
     * 字号/行距/段距/边距/上下间距每改一次都要**重排整章**（几百个 `StaticLayout`）。
     * 逐格回调 = 拖动时每秒重排几十次整章，主线程直接卡死。松手才落地，拖动过程只动标签。
     */
    private fun sliderLabel(onLabel: (Int) -> Unit, onSettle: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { onSettle(it.progress) }
            }
        }

    // ===== 翻译汇总 / 过滤 =====

    /**
     * 宿主推来的**最新**章状态。
     *
     * ⚠️ 不能读 `state.chapterStats`：那是**打开面板那一刻的快照**。汇总曾经读的是它，
     * 于是「面板开着时译文在涨，汇总数字却一动不动」—— 同一份数据在面板里存了两份
     * （快照一份、adapter 一份），读错一份就白推了。
     */
    private var liveStats: Map<Int, NovelChapterStat> = emptyMap()

    /** 每章总段数（章行的分母），见 `NovelChapterStateAdapter.totals`。 */
    private var chapterTotals: Map<Int, Int> = emptyMap()

    private fun updateSummary() {
        val total = state.chapterCount
        val done = liveStats.count { (_, s) -> s.total > 0 && s.success >= s.total }
        val partial = liveStats.count { (_, s) -> s.success > 0 && s.success < s.total }
        val failed = liveStats.count { (_, s) -> s.total > 0 && s.success < s.total }
        view?.findViewById<TextView>(R.id.tv_translate_summary)?.text =
            getString(R.string.novel_translate_summary, total, done, partial, failed)
    }

    /**
     * 外部刷新入口：**宿主状态一变就推全量**（当前章 + 章状态）。
     *
     * ⚠️ 面板是**打开那一刻的快照**，宿主不推就没有第二条路能刷新它。
     * ⚠️ 刻意做成**一个函数推全部**：早先是「每个字段一个推送方法」（只有 stats），
     * 于是「在面板里点某一章跳过去」之后行高亮一直停在旧值 ——
     * 加字段时忘了加推送是默认结果。**新增任何依赖宿主的面板字段，都必须加到这篇里。**
     */
    fun notifyHostState(
        currentChapter: Int,
        stats: Map<Int, NovelChapterStat>,
        totals: Map<Int, Int> = emptyMap(),
    ) {
        chapterTotals = totals
        liveStats = stats
        chapterAdapter.stats = stats
        chapterAdapter.totals = chapterTotals
        chapterAdapter.currentChapter = currentChapter
        updateSummary()
    }

    private fun setupTranslateFilter(view: View) {
        val row = view.findViewById<ViewGroup>(R.id.translate_filter_row)
        row.removeAllViews()
        val options = listOf(
            0 to R.string.reader_translate_filter_all,
            1 to R.string.novel_translate_filter_pending,
        )
        for ((key, label) in options) {
            val chip = TextView(requireContext()).apply {
                text = getString(label)
                textSize = 12f
                setPadding(dp8 * 2, dp8, dp8 * 2, dp8)
                tag = key
                setOnClickListener { applyFilter(key, view) }
            }
            setChipStyle(chip, key == currentFilterKey)
            row.addView(chip)
        }
    }

    private fun applyFilter(key: Int, view: View) {
        currentFilterKey = key
        refreshFilterChipStyle(view.findViewById(R.id.translate_filter_row))
        chapterAdapter.failuresOnly = key == 1
    }

    private fun refreshFilterChipStyle(row: ViewGroup) {
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            setChipStyle(tv, (tv.tag as? Int) == currentFilterKey)
        }
    }

    private fun setChipStyle(tv: TextView, selected: Boolean) {
        val radius = 10f * resources.displayMetrics.density
        tv.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(
                when {
                    !selected -> 0
                    darkPanel -> 0xFF2E3A45.toInt()
                    else -> 0xFFE4E4E6.toInt()
                }
            )
        }
        tv.setTextColor(
            when {
                !selected -> if (darkPanel) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
                darkPanel -> 0xFFB8D9FF.toInt()
                else -> 0xFF1D6FB8.toInt()
            }
        )
    }

    // ===== 语言 =====

    private fun refreshLangRow(view: View) {
        val prefs = CustomPreference.getInstance(requireContext())
        view.findViewById<TextView>(R.id.tv_source_lang_value).text =
            CustomLocale.getInstance(prefs.getString("Source_Language", "auto")).getDisplayName()
        view.findViewById<TextView>(R.id.tv_target_lang_value).text =
            CustomLocale.getInstance(prefs.getString("Target_Language", "zh")).getDisplayName()
    }

    /** 测试缝：语言下拉数据源（见 `ReaderMenuSheet` 同名属性）。 */
    @androidx.annotation.VisibleForTesting
    var languagesList: (Int, OcrEngineGroup?) -> List<CustomLocale> = { type, group ->
        TranslateTools.getLanguagesList(requireContext(), type, group) ?: emptyList()
    }

    private fun showLangDialog(type: Int) {
        val ctx = requireContext()
        val appPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val customPrefs = CustomPreference.getInstance(ctx)
        val ocrGroup = if (type == 1) OcrEngineManager.getOcrEngineGroup(appPrefs) else null
        val locales = languagesList(type, ocrGroup)
        if (locales.isEmpty()) return
        val isHyMt2 = appPrefs.getInt("Text_API", Constants.TextApi.BING.id) == Constants.TextApi.AI.id &&
            appPrefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.HYMT2.id
        val disabledTargets = if (type == 2) TranslateTools.getDisabledTargetLangs(customPrefs) else emptySet()
        val enabled = when (type) {
            1 -> locales.map { ReaderTranslationInfo.isSourceSupported(it.getOriCode(), ocrGroup!!.sourceLangs) }
            2 -> locales.map {
                ReaderTranslationInfo.isTargetSupported(it.getOriCode(), isHyMt2, HyMt2Languages.supportedCodes, disabledTargets)
            }
            else -> null
        }
        LanguageSelectionDialog(
            ctx, type, locales,
            enabled = enabled,
            dark = darkPanel,
            lightBg = R.drawable.bg_dialog_white,
            fixedLightText = !darkPanel,
            onDisabledClick = when (type) {
                1 -> { loc ->
                    val supportedNames = OcrEngineGroup.entries
                        .filter { it.sourceLangs.contains(loc.getOriCode()) }
                        .joinToString(" / ") { getString(it.labelRes) }
                    val msg = if (supportedNames.isEmpty()) getString(R.string.reader_lang_ocr_unsupported_none)
                    else getString(R.string.reader_lang_ocr_unsupported, supportedNames)
                    showHintDialog(msg)
                }
                2 -> { _ -> showHintDialog(getString(R.string.reader_lang_translate_unsupported)) }
                else -> null
            },
            onLanguageSelected = { locale ->
                if (type == 1) customPrefs.setString("Source_Language", locale.getOriCode())
                else customPrefs.setString("Target_Language", locale.getOriCode())
                refreshLangRow(requireView())
            }
        ).show()
    }

    private fun showHintDialog(msg: String) {
        val dlg = AlertDialog.Builder(requireContext())
            .setMessage(msg)
            .setPositiveButton(R.string.user_known, null)
            .create()
        dlg.show()
        dlg.window?.setBackgroundDrawableResource(if (darkPanel) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white)
        if (darkPanel) recolorDark(dlg.window?.decorView)
    }

    private fun recolorDark(v: View?) {
        if (v == null) return
        if (v is TextView) v.setTextColor(0xFFE2E2E4.toInt())
        if (v is ViewGroup) for (i in 0 until v.childCount) recolorDark(v.getChildAt(i))
    }
}
