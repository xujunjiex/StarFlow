package com.moe.starflow.mangaimport.reader

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation

/** 阅读器底部工具栏初始状态。mode:0=LTR 1=RTL 2=竖排 3=Webtoon；animation:0无 1默认 2高级 3仿真；bg:0默认 1浅 2深 3白 4黑 5自动。 */
class ReaderMenuState(
    val mode: Int = 0,
    val animation: Int = 1,
    val bg: Int = 0,
    val autoTurn: Boolean = false,
    val intervalSec: Int = 5,
    val colorFilter: ReaderColorFilter = ReaderColorFilter.EMPTY,
    val rotateLabel: String = "",
    val downloadLabel: String = "",
    val isDarkPanel: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val translateMode: Int = 0,                 // 0 手动 1 自动 2 增量（阶段一恒 0）
    val pageTranslations: List<ImportedPageTranslation> = emptyList()  // 每页翻译记录快照
)

/** 阅读器底部工具栏回调。 */
class ReaderMenuCallbacks(
    val onMode: (Int) -> Unit,
    val onAnimation: (Int) -> Unit,
    val onBackground: (Int) -> Unit,
    val onAutoTurn: (Boolean, Int) -> Unit,
    val onColorFilterChanged: (ReaderColorFilter) -> Unit,
    val onResetColor: () -> Unit,
    val onRotate: () -> Unit,
    val onDownload: () -> Unit,
    val onSettings: () -> Unit,
    val onTranslateMode: (Int) -> Unit = {},
    val onTranslatePageJump: (Int) -> Unit = {},
)

/**
 * 阅读器底部工具栏（Koto 四图标 Tab）：翻页（模式/动画/背景分段）/ 翻译 / 调色（内联）/ 更多。
 * 面板配色随阅读背景深浅联动。
 */
class ReaderMenuSheet(
    private val state: ReaderMenuState,
    private val cb: ReaderMenuCallbacks
) : BottomSheetDialogFragment() {

    /** 面板深浅（随阅读背景切换即时更新）。 */
    private var darkPanel = state.isDarkPanel

    private val pageAdapter by lazy {
        ReaderPageStateAdapter(onJump = { cb.onTranslatePageJump(it) })
    }
    private var currentRecords: List<ImportedPageTranslation> = emptyList()
    private var currentFilterKey = 0

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun isDarkBg(bg: Int): Boolean = when (bg) {
        1, 3 -> false                 // light / white
        2, 4 -> true                  // dark / black
        else -> systemDark()          // default / auto（跟随系统）
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(if (state.isDarkPanel) R.drawable.bg_bottom_sheet_dark else R.drawable.bg_bottom_sheet)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_reader_menu, container, false)
        val root = view.findViewById<View>(R.id.sheet_root)
        applyPanelTheme(view, root)

        val panelPaging = view.findViewById<View>(R.id.panel_paging)
        val panelTranslate = view.findViewById<View>(R.id.panel_translate)
        val panelAppearance = view.findViewById<View>(R.id.panel_appearance)
        val panelMore = view.findViewById<View>(R.id.panel_more)
        val tabPaging = view.findViewById<View>(R.id.tab_paging)
        val tabTranslate = view.findViewById<View>(R.id.tab_translate)
        val tabAppearance = view.findViewById<View>(R.id.tab_appearance)
        val tabMore = view.findViewById<View>(R.id.tab_more)
        val tabs = listOf(tabPaging to panelPaging, tabTranslate to panelTranslate, tabAppearance to panelAppearance, tabMore to panelMore)

        fun show(selected: View, panel: View) {
            tabs.forEach { (tv, p) ->
                setTab(tv, tv === selected)
                p.visibility = if (p === panel) View.VISIBLE else View.GONE
            }
        }
        tabPaging.setOnClickListener { show(tabPaging, panelPaging) }
        tabTranslate.setOnClickListener { show(tabTranslate, panelTranslate) }
        tabAppearance.setOnClickListener { show(tabAppearance, panelAppearance) }
        tabMore.setOnClickListener { show(tabMore, panelMore) }
        show(tabPaging, panelPaging)

        // 阅读模式
        setupSeg(view, R.id.seg_mode, listOf(
            R.id.seg_mode_ltr to (state.mode == 0),
            R.id.seg_mode_rtl to (state.mode == 1),
            R.id.seg_mode_vertical to (state.mode == 2),
            R.id.seg_mode_webtoon to (state.mode == 3)
        )) { id ->
            cb.onMode(when (id) {
                R.id.seg_mode_rtl -> 1
                R.id.seg_mode_vertical -> 2
                R.id.seg_mode_webtoon -> 3
                else -> 0
            })
        }
        // 翻页动画
        setupSeg(view, R.id.seg_animation, listOf(
            R.id.seg_anim_none to (state.animation == 0),
            R.id.seg_anim_default to (state.animation == 1),
            R.id.seg_anim_advanced to (state.animation == 2),
            R.id.seg_anim_simulation to (state.animation == 3)
        )) { id ->
            cb.onAnimation(when (id) {
                R.id.seg_anim_none -> 0
                R.id.seg_anim_advanced -> 2
                R.id.seg_anim_simulation -> 3
                else -> 1
            })
        }
        // 背景（默认=跟随系统；点击即切背景 + 面板立即跟随深浅）
        setupSeg(view, R.id.seg_background, listOf(
            R.id.seg_bg_default to (state.bg == 0),
            R.id.seg_bg_light to (state.bg == 1),
            R.id.seg_bg_dark to (state.bg == 2),
            R.id.seg_bg_white to (state.bg == 3),
            R.id.seg_bg_black to (state.bg == 4)
        )) { id ->
            val newBg = when (id) {
                R.id.seg_bg_light -> 1
                R.id.seg_bg_dark -> 2
                R.id.seg_bg_white -> 3
                R.id.seg_bg_black -> 4
                else -> 0
            }
            darkPanel = isDarkBg(newBg)
            applyPanelTheme(view, root)
            cb.onBackground(newBg)
        }

        // 更多
        view.findViewById<View>(R.id.btn_rotate).setOnClickListener { dismiss(); cb.onRotate() }
        view.findViewById<View>(R.id.btn_download).setOnClickListener { dismiss(); cb.onDownload() }
        view.findViewById<View>(R.id.btn_settings).setOnClickListener { dismiss(); cb.onSettings() }

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
        view.findViewById<TextView>(R.id.tv_rotate_value).text = state.rotateLabel
        view.findViewById<TextView>(R.id.tv_download_value).text = state.downloadLabel

        // 翻译面板：模式骨架（手动可用，自动/增量置灰）+ 汇总 + 过滤 + 每页列表 + 重试
        currentRecords = state.pageTranslations
        val rvPages = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_pages)
        rvPages.layoutManager = LinearLayoutManager(requireContext())
        rvPages.adapter = pageAdapter
        pageAdapter.rows = filteredRows()
        val rbManual = view.findViewById<RadioButton>(R.id.translate_mode_manual)
        rbManual.isChecked = state.translateMode == 0
        view.findViewById<RadioButton>(R.id.translate_mode_auto).isEnabled = false
        view.findViewById<RadioButton>(R.id.translate_mode_incremental).isEnabled = false
        rbManual.setOnCheckedChangeListener { _, checked -> if (checked) cb.onTranslateMode(0) }
        setupTranslateFilter(view)
        updateSummary(currentRecords)

        // Webtoon 滚动模式下翻页动画/自动翻页不生效：禁用并置灰（切回分页模式自动恢复）
        if (state.mode == 3) {
            setSegEnabled(view.findViewById<ViewGroup>(R.id.seg_animation), false)
            swAutoTurn.isEnabled = false
            swAutoTurn.alpha = 0.4f
            tvInterval.isEnabled = false
            tvInterval.alpha = 0.4f
        }

        // 调色
        val swInvert = view.findViewById<Switch>(R.id.sw_invert)
        val swGray = view.findViewById<Switch>(R.id.sw_grayscale)
        val swBook = view.findViewById<Switch>(R.id.sw_book)
        val sbBright = view.findViewById<SeekBar>(R.id.sb_brightness)
        val sbContrast = view.findViewById<SeekBar>(R.id.sb_contrast)
        val tvBright = view.findViewById<TextView>(R.id.tv_brightness_value)
        val tvContrast = view.findViewById<TextView>(R.id.tv_contrast_value)

        fun cur() = ReaderColorFilter(
            brightness = sbBright.progress / 100f,
            contrast = sbContrast.progress / 100f,
            isInverted = swInvert.isChecked,
            isGrayscale = swGray.isChecked,
            isBookBackground = swBook.isChecked
        )
        fun push() = cb.onColorFilterChanged(cur())
        // Koto 式对比预览：左原图 / 右处理后（随控件实时刷新）
        val ivOrig = view.findViewById<ImageView>(R.id.iv_orig_preview)
        val ivProc = view.findViewById<ImageView>(R.id.iv_proc_preview)
        state.previewBitmap?.let { bmp ->
            ivOrig.setImageBitmap(bmp)
            ivProc.setImageBitmap(bmp)
        }
        fun refreshProc() {
            ivProc.colorFilter = cur().toColorFilter()
        }
        refreshProc()
        sbBright.progress = (state.colorFilter.brightness * 100).toInt().coerceIn(-100, 100)
        sbContrast.progress = (state.colorFilter.contrast * 100).toInt().coerceIn(-100, 100)
        swInvert.isChecked = state.colorFilter.isInverted
        swGray.isChecked = state.colorFilter.isGrayscale
        swBook.isChecked = state.colorFilter.isBookBackground
        tvBright.text = "${sbBright.progress}%"
        tvContrast.text = "${sbContrast.progress}%"
        sbBright.setOnSeekBarChangeListener(slider { tvBright.text = "${sbBright.progress}%"; refreshProc(); push() })
        sbContrast.setOnSeekBarChangeListener(slider { tvContrast.text = "${sbContrast.progress}%"; refreshProc(); push() })
        swInvert.setOnCheckedChangeListener { _, _ -> refreshProc(); push() }
        swGray.setOnCheckedChangeListener { _, _ -> refreshProc(); push() }
        swBook.setOnCheckedChangeListener { _, _ -> refreshProc(); push() }
        view.findViewById<View>(R.id.btn_reset_color).setOnClickListener { cb.onResetColor() }

        return view
    }

    /** 面板配色随阅读背景深浅联动：根背景 + 标题/行文字颜色 + 分段轨道。 */
    private fun applyPanelTheme(view: View, root: View) {
        val dark = darkPanel
        root.setBackgroundColor(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        listOf(
            R.id.tv_mode_label, R.id.tv_animation_label, R.id.tv_background_label,
            R.id.tv_translate_mode_label, R.id.tv_translate_summary,
            R.id.tv_color_title, R.id.tv_color_inverted, R.id.tv_color_grayscale, R.id.tv_color_book,
            R.id.tv_brightness_label, R.id.tv_contrast_label,
            R.id.tv_rotate_label, R.id.tv_auto_turn_label, R.id.tv_download_label, R.id.tv_settings_label
        ).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(labelColor)
        }
        listOf(
            R.id.tv_brightness_value, R.id.tv_contrast_value, R.id.tv_color_hint,
            R.id.tv_rotate_value, R.id.tv_interval_value, R.id.tv_download_value
        ).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(subColor)
        }
        // 分段未选中文字 + 复合图标颜色（随深浅）
        reapplySegments(view)
        // 每页状态列表行内配色（元数据/原文译文/复制）随面板深浅
        pageAdapter.dark = darkPanel
        // 调色/更多面板 Switch 配色（避免与面板背景重叠/看不清）
        val swTrack = if (dark) 0xFF3A4046.toInt() else 0xFFCFD8DC.toInt()
        listOf(R.id.sw_invert, R.id.sw_grayscale, R.id.sw_book, R.id.sw_auto_turn).forEach { id ->
            view.findViewById<Switch>(id).let {
                it.thumbTintList = ColorStateList.valueOf(0xFF55AEEA.toInt())
                it.trackTintList = ColorStateList.valueOf(swTrack)
            }
        }
    }

    /** 启用/禁用某分段容器内的所有子单元格（置灰用）。 */
    private fun setSegEnabled(container: ViewGroup, enabled: Boolean) {
        for (i in 0 until container.childCount) {
            val cell = container.getChildAt(i)
            cell.isEnabled = enabled
            cell.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun setTab(tab: View, active: Boolean) {
        val sel = (tab as? ViewGroup)?.getChildAt(0) as? ImageView
        val icon = (tab as? ViewGroup)?.getChildAt(1) as? ImageView
        // 菜单选中：圆角方块表面（与面板的圆区分）
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

    /** 各分段容器当前选中项（容器Id → 选中segmentId），供主题切换后重画。 */
    private val selection = mutableMapOf<Int, Int>()

    /** 背景 glyph 分段（不 tint 图标，只用选中容器高亮）。 */
    private val bgSegIds = setOf(R.id.seg_bg_default, R.id.seg_bg_light, R.id.seg_bg_dark, R.id.seg_bg_white, R.id.seg_bg_black)

    private fun setupSeg(
        view: View,
        containerId: Int,
        segments: List<Pair<Int, Boolean>>,
        onPick: (Int) -> Unit
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

    /** 依据 selection 重画所有分段（面板深浅切换后调用）。 */
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
        val sel = (cell as? ViewGroup)?.getChildAt(0) as? ImageView
        val icon = (cell as? ViewGroup)?.getChildAt(1) as? ImageView
        // 面板选中：柔和圆表面（无边框）
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

    private fun slider(onRefresh: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onRefresh()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ===== 翻译面板：汇总 / 过滤 / 外部刷新 =====

    private fun updateSummary(records: List<ImportedPageTranslation>) {
        val s = records.count { it.state == ImportedPageTranslation.STATE_SUCCESS }
        val t = records.count { it.state == ImportedPageTranslation.STATE_TRANSLATING }
        val f = records.count { it.state == ImportedPageTranslation.STATE_FAILED }
        view?.findViewById<TextView>(R.id.tv_translate_summary)?.text =
            getString(R.string.reader_translate_summary, records.size, s, t, f)
    }

    /** 外部刷新入口（翻译任务开始/完成/失败后调用）。 */
    fun notifyTranslateChanged(records: List<ImportedPageTranslation>) {
        currentRecords = records
        view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_pages)?.adapter = pageAdapter
        pageAdapter.rows = filteredRows()
        updateSummary(currentRecords)
    }

    private fun filteredRows(): List<ImportedPageTranslation> = when (currentFilterKey) {
        1 -> currentRecords.filter { it.state == ImportedPageTranslation.STATE_FAILED }
        2 -> currentRecords.filter { it.state == ImportedPageTranslation.STATE_FAILED && it.failCode == "OCR_EMPTY" }
        3 -> currentRecords.filter { it.state == ImportedPageTranslation.STATE_FAILED && it.failCode == "TRANSLATE_EMPTY" }
        4 -> currentRecords.filter { it.state == ImportedPageTranslation.STATE_FAILED && it.failCode != "OCR_EMPTY" && it.failCode != "TRANSLATE_EMPTY" }
        else -> currentRecords
    }

    private fun setupTranslateFilter(view: View) {
        val row = view.findViewById<ViewGroup>(R.id.translate_filter_row)
        row.removeAllViews()
        val options = listOf(
            0 to R.string.reader_translate_filter_all,
            1 to R.string.reader_translate_filter_failed,
            2 to R.string.reader_translate_filter_ocr,
            3 to R.string.reader_translate_filter_translate,
            4 to R.string.reader_translate_filter_exception,
        )
        for ((key, label) in options) {
            val chip = TextView(requireContext()).apply {
                text = getString(label)
                textSize = 12f
                setPadding(dp8 * 2, dp8, dp8 * 2, dp8)
                tag = key
                setOnClickListener {
                    currentFilterKey = key
                    refreshFilterChipStyle(row)
                    pageAdapter.rows = filteredRows()
                }
            }
            setChipStyle(chip, key == currentFilterKey)
            row.addView(chip)
        }
    }

    private fun refreshFilterChipStyle(row: ViewGroup) {
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            setChipStyle(tv, (tv.tag as? Int) == currentFilterKey)
        }
    }

    private fun setChipStyle(tv: TextView, selected: Boolean) {
        val radius = 10f * resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            cornerRadius = radius
            setColor(
                when {
                    !selected -> 0
                    darkPanel -> 0xFF2E3A45.toInt()
                    else -> 0xFFE4E4E6.toInt()
                }
            )
        }
        tv.background = bg
        tv.setTextColor(
            when {
                !selected -> if (darkPanel) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
                darkPanel -> 0xFFB8D9FF.toInt()
                else -> 0xFF1D6FB8.toInt()
            }
        )
    }

    private val dp8: Int get() = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        const val TAG = "ReaderMenuSheet"
    }
}