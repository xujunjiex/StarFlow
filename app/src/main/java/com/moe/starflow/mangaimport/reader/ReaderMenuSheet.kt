package com.moe.starflow.mangaimport.reader

import android.content.SharedPreferences
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
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.manga.config.OcrEngineGroup
import com.moe.starflow.mangaimport.translate.ReaderTranslationInfo
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.LanguageSelectionDialog
import com.moe.starflow.translate.TranslateTools
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.MangaFontSize
import com.moe.starflow.utils.MangaFontSizeDialog
import com.moe.starflow.utils.OcrEngineManager

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
    /** Webtoon（连续滑动）的显示态：false=原图，true=译文（默认）。决定模式图标上是否带「译」角标。 */
    val webtoonTranslated: Boolean = true,
    val translateMode: Int = 0,                 // 0 手动 1 自动 2 增量
    val debounceMs: Int = 500,                  // 自动/增量的启动延迟
    val aheadPages: Int = 5,                    // 增量向后翻多少页（1..10）
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
    /** 「连续滑动」按钮再次点击：在 原图 ↔ 译文 之间切换（不进模式，只换显示态）。 */
    val onWebtoonTranslated: (Boolean) -> Unit = {},
    val onDebounceMs: (Int) -> Unit = {},
    val onAheadPages: (Int) -> Unit = {},
    val onTranslatePageJump: (Int) -> Unit = {},
    /** 面板打开：宿主应暂停翻译队列。 */
    val onPanelOpened: () -> Unit = {},
    /** 面板关闭：宿主可恢复队列。 */
    val onPanelClosed: () -> Unit = {},
    val onOpenModelManagement: () -> Unit = {},
    val onOpenApiConfig: () -> Unit = {},
    /**
     * 回读宿主**当前真实**的翻译模式。
     *
     * ⚠️ 必须：宿主在 [onPanelOpened] 里会把模式回退到手动，面板若不回读就会停在打开前的
     * 选中项（例如「自动」）—— 而用户想切回自动时点的正是那个已选中的条目，
     * RadioButton 在同组内重复选中不派发 onCheckedChanged → 模式彻底切不动。
     */
    val currentTranslateMode: () -> Int = { 0 },
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

    /** 默认 SharedPreferences 监听（模型/语言 prefs 变化 → 刷新面板；跳设置页返回后也能生效）。 */
    private var appPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 当前翻译模式（0 手动 / 1 自动 / 2 增量）。宿主可经 [setTranslateMode] 单向回灌。 */
    private var translateMode = 0

    /** 当前翻译模式（0 手动 / 1 自动 / 2 增量）。 */
    @androidx.annotation.VisibleForTesting
    fun getTranslateMode(): Int = translateMode

    /**
     * 宿主 → 面板的**单向**回灌：把面板选中态对齐到宿主真实模式。
     *
     * ⚠️ 不会回调 [ReaderMenuCallbacks.onTranslateMode]（那是「面板 → 宿主」方向）。
     * 两边互相回写就会形成「宿主改 → 面板回调 → 宿主再改」的回环。
     *
     * 视图尚未创建时只记状态，`onCreateView` / `onStart` 会各自应用一次（幂等）。
     */
    fun setTranslateMode(mode: Int) {
        translateMode = mode
        val radios = modeRadios
        if (radios.isEmpty()) return
        val target = radios.getOrNull(mode)
        // 已经是目标态就不用动：省掉一次组内互斥的重绘，也让回灌真正幂等
        if (target?.isChecked != true) {
            reapplyingMode = true
            try {
                target?.isChecked = true
            } finally {
                reapplyingMode = false
            }
        }
        view?.let { applyAheadRowVisibility(it, mode) }
    }

    /** 三个模式单选钮（创建视图后填充；供 [setTranslateMode] 回灌选中态）。 */
    private var modeRadios: List<RadioButton> = emptyList()

    /**
     * 正在由 [setTranslateMode] 程序化改动选中态。
     *
     * ⚠️ `isChecked = true` **同样会触发 `OnCheckedChangeListener`**（它不区分来源）。
     * 不挡一下的话，「宿主 → 面板」的回灌会立刻反向调一次
     * [ReaderMenuCallbacks.onTranslateMode]，方向契约就破了。生产里因为 [setMode] 有
     * 同值早退而不显症状，但那是别人给的巧合，不该靠它。
     */
    private var reapplyingMode = false

    private val pageAdapter by lazy {
        ReaderPageStateAdapter(onJump = { cb.onTranslatePageJump(it) })
    }
    private var currentRecords: List<ImportedPageTranslation> = emptyList()
    private var currentFilterKey = 0

    /** 系统是否深色（独立于 app 强制主题）：读 Resources.getSystem()，避免全局主题切换影响面板默认深浅。 */
    private fun systemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun isDarkBg(bg: Int): Boolean = when (bg) {
        1, 3 -> false                 // light / white
        2, 4 -> true                  // dark / black
        else -> systemDark()          // default / auto（跟随系统）
    }

    override fun onStart() {
        super.onStart()
        // 面板打开 → 暂停翻译队列（用户在调设置，不该后台继续翻）。
        cb.onPanelOpened()
        // ⚠️ 顺序不能反：onPanelOpened 里宿主会把模式回退到手动，随后必须回读覆盖面板选中态，
        // 否则面板显示「自动」而实际是手动，用户点那个已选中的条目不会有任何反应。
        setTranslateMode(cb.currentTranslateMode())
        // 面板容器背景初始跟随当前深浅（此后由 applyPanelTheme 实时维护）
        reapplySheetContainerBg()
        // 模型/语言 prefs 变化（跳设置页返回等）→ 即时刷新模型名；无需关面板
        refreshModelRows()
        // 插件初始化期间可能没走到下面 onStart 的赋值，这里再补一次（幂等）
        setTranslateMode(cb.currentTranslateMode())
        view?.let { refreshLangRow(it) }
        val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
        appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "Text_API" || key == "Text_AI" || key == "OpenAI_Selected_Provider" || key == OcrEngineManager.PREF_KEY) {
                refreshModelRows()
            } else if (key == "Source_Language" || key == "Target_Language") {
                view?.let { refreshLangRow(it) }
            }
        }
        sp.registerOnSharedPreferenceChangeListener(appPrefsListener)
    }

    override fun onStop() {
        appPrefsListener?.let {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(it)
        }
        appPrefsListener = null
        // 面板关闭 → 恢复队列：这就是"选了自动/增量也要等退出面板才开始翻"
        cb.onPanelClosed()
        super.onStop()
    }

    /** 外层 BottomSheet 容器背景随当前深浅（onStart 初始化 + applyPanelTheme 实时维护）。 */
    private fun reapplySheetContainerBg() {
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(if (darkPanel) R.drawable.bg_bottom_sheet_dark else R.drawable.bg_bottom_sheet_light)
    }

    /** 重新读模型名到两行（onStart / prefs 变化时调用）。 */
    private fun refreshModelRows() {
        val v = view ?: return
        v.findViewById<TextView>(R.id.tv_ocr_model_row).text =
            getString(R.string.reader_translate_ocr_model, ReaderTranslationInfo.ocrModelLabel(requireContext()))
        v.findViewById<TextView>(R.id.tv_translator_model_row).text =
            getString(R.string.reader_translate_translator_model, ReaderTranslationInfo.translatorModelLabel(requireContext()))
    }

    /** Webtoon(3) 下翻页动画/自动翻页不生效：即时禁用置灰，切回分页模式自动恢复。 */
    private fun applyModeDependence(view: View, mode: Int) {
        val isWebtoon = mode == 3
        setSegEnabled(view.findViewById<ViewGroup>(R.id.seg_animation), !isWebtoon)
        val sw = view.findViewById<Switch>(R.id.sw_auto_turn)
        sw.isEnabled = !isWebtoon
        sw.alpha = if (isWebtoon) 0.4f else 1f
        val tv = view.findViewById<TextView>(R.id.tv_interval_value)
        tv.isEnabled = !isWebtoon
        tv.alpha = if (isWebtoon) 0.4f else 1f
    }

    /**
     * 测试缝：`onCreateView` 完成主题应用后回调一次。
     *
     * ⚠️ 这个缝是被 Robolectric 逼出来的：[`show()`][BottomSheetDialogFragment.show] 在测试里会走
     * FragmentManager 注册、并让 `BottomSheetDialog` 去操作一个没有真实窗口的 Dialog，噪音覆盖收益。
     * 而这里需要断言的恰恰是「inflate 出来的面板长什么样」—— 所以让测试用真实的
     * `sheet_reader_menu.xml` 走完整的 `onCreateView`，只把布局里那部分被观察的控件换掉。
     * 生产路径一条语句都不会被跳过。
     */
    @androidx.annotation.VisibleForTesting
    var onViewReady: ((View) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_reader_menu, container, false)
        val root = view.findViewById<View>(R.id.sheet_root)
        // 先给测试缝机会补全布局，再统一上色 —— 反过来注入的控件就赶不上这次主题应用
        onViewReady?.invoke(view)
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

        // 阅读模式。
        // ⚠️ Webtoon 这一格是**两态开关**：未选中时点它 = 进入连续滑动（看原图）；
        // 已选中时再点 = 同一模式内切换 原图 ↔ 译文（图标右上角出现「译」角标），
        // 不重走 onMode（模式没变，重走会重建适配器、丢失滚动位置）。
        var curMode = state.mode
        var webtoonTranslated = state.webtoonTranslated
        fun applyWebtoonBadge() {
            view.findViewById<View>(R.id.tv_webtoon_translated_badge).visibility =
                if (webtoonTranslated) View.VISIBLE else View.GONE
        }
        applyWebtoonBadge()
        setupSeg(view, R.id.seg_mode, listOf(
            R.id.seg_mode_ltr to (state.mode == 0),
            R.id.seg_mode_rtl to (state.mode == 1),
            R.id.seg_mode_vertical to (state.mode == 2),
            R.id.seg_mode_webtoon to (state.mode == 3)
        )) { id ->
            if (id == R.id.seg_mode_webtoon && curMode == 3) {
                webtoonTranslated = !webtoonTranslated
                applyWebtoonBadge()
                cb.onWebtoonTranslated(webtoonTranslated)
                return@setupSeg
            }
            val m = when (id) {
                R.id.seg_mode_rtl -> 1
                R.id.seg_mode_vertical -> 2
                R.id.seg_mode_webtoon -> 3
                else -> 0
            }
            curMode = m
            applyModeDependence(view, m)   // 切 Webtoon 即时置灰翻页动画/自动翻页（无需关面板）
            cb.onMode(m)
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

        // 翻译面板：模式骨架（手动/自动/增量三选一）+ 汇总 + 过滤 + 每页列表
        currentRecords = state.pageTranslations
        val rvPages = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_pages)
        rvPages.layoutManager = LinearLayoutManager(requireContext())
        rvPages.adapter = pageAdapter
        pageAdapter.rows = filteredRows()
        val rbManual = view.findViewById<RadioButton>(R.id.translate_mode_manual)
        val rbAuto = view.findViewById<RadioButton>(R.id.translate_mode_auto)
        val rbAhead = view.findViewById<RadioButton>(R.id.translate_mode_incremental)
        modeRadios = listOf(rbManual, rbAuto, rbAhead)
        setTranslateMode(cb.currentTranslateMode())
        // ⚠️ 初值必须取自 [ReaderMenuCallbacks.currentTranslateMode]（宿主**此刻**的真实模式），
        // 不能用 `state.translateMode` —— 那是构造面板那一刻的快照，创建视图这一段是异步的，
        // 中间宿主完全可能已经改过模式（例如面板打开就回退手动）。
        rbManual.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = 0; cb.onTranslateMode(0) }
            applyAheadRowVisibility(view, translateMode)
        }
        rbAuto.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = 1; cb.onTranslateMode(1) }
            applyAheadRowVisibility(view, translateMode)
        }
        rbAhead.setOnCheckedChangeListener { _, c ->
            if (c && !reapplyingMode) { translateMode = 2; cb.onTranslateMode(2) }
            applyAheadRowVisibility(view, translateMode)
        }

        // 译文大小：**同一份设置**（个性化 → 漫画翻译结果字体大小）。这里改完，
        // 悬浮窗与设置页显示同步跟着变；改动经 prefs 落盘，重翻时按新字号渲染。
        val tvFontSize = view.findViewById<TextView>(R.id.tv_font_size_row)
        fun refreshFontSizeRow() {
            tvFontSize.text = getString(R.string.reader_translate_font_size, MangaFontSize.summary(requireContext()))
        }
        refreshFontSizeRow()
        view.findViewById<View>(R.id.btn_font_size).setOnClickListener {
            showFontSizeDialog { refreshFontSizeRow() }
        }

        // 启动延迟（防抖）：翻页停留多久才开翻。自动/增量共用。
        val sbDebounce = view.findViewById<SeekBar>(R.id.sb_debounce)
        val tvDebounce = view.findViewById<TextView>(R.id.tv_debounce_value)
        // ⚠️ 设了 android:min 的 SeekBar，progress 是**绝对值**（不是相对 0 的偏移）。
        // 写成 `progress = value - MIN` 会让滑块初始位置与右侧数值不符，且可取范围整体偏移。
        sbDebounce.progress = state.debounceMs.coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)
        tvDebounce.text = "${state.debounceMs} ms"
        sbDebounce.setOnSeekBarChangeListener(slider {
            val ms = sbDebounce.progress
            tvDebounce.text = "$ms ms"
            cb.onDebounceMs(ms)
        })

        // 向后翻译页数：仅增量模式显示
        val sbAhead = view.findViewById<SeekBar>(R.id.sb_ahead)
        val tvAhead = view.findViewById<TextView>(R.id.tv_ahead_value)
        sbAhead.progress = state.aheadPages.coerceIn(AHEAD_MIN, AHEAD_MAX)
        tvAhead.text = "${state.aheadPages}"
        sbAhead.setOnSeekBarChangeListener(slider {
            val n = sbAhead.progress
            tvAhead.text = "$n"
            cb.onAheadPages(n)
        })
        applyAheadRowVisibility(view, translateMode)

        setupTranslateFilter(view)
        updateSummary(currentRecords)

        // 模型区：OCR/翻译模型 快速跳转（保持面板打开，返回后 onStart/prefs 监听刷新模型名）
        view.findViewById<TextView>(R.id.tv_ocr_model_row).text =
            getString(R.string.reader_translate_ocr_model, ReaderTranslationInfo.ocrModelLabel(requireContext()))
        view.findViewById<TextView>(R.id.tv_translator_model_row).text =
            getString(R.string.reader_translate_translator_model, ReaderTranslationInfo.translatorModelLabel(requireContext()))
        view.findViewById<View>(R.id.btn_model_ocr).setOnClickListener { cb.onOpenModelManagement() }
        view.findViewById<View>(R.id.btn_model_translate).setOnClickListener { cb.onOpenApiConfig() }

        // 语言区：源/目标语言选择（两格下拉弹窗 + 互换，随面板深浅主题）
        refreshLangRow(view)
        view.findViewById<View>(R.id.btn_source_lang).setOnClickListener { showLangDialog(1) }
        view.findViewById<View>(R.id.btn_target_lang).setOnClickListener { showLangDialog(2) }
        view.findViewById<View>(R.id.btn_swap_lang).setOnClickListener {
            val prefs = CustomPreference.getInstance(requireContext())
            val s = prefs.getString("Source_Language", "ja")
            val t = prefs.getString("Target_Language", "zh")
            prefs.setString("Source_Language", t)
            prefs.setString("Target_Language", s)
            refreshLangRow(view)
        }

        // Webtoon 滚动模式下翻页动画/自动翻页不生效：禁用并置灰（切回分页模式自动恢复；此处初始化，切模式时 applyModeDependence 即时同步）
        applyModeDependence(view, state.mode)

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

        // 程序化改控件期间抑制 push：否则会把「改了一半」的中间态写给宿主（先改亮度、对比度还没改）
        var suppressPush = false
        fun pushIfUser() {
            if (!suppressPush) push()
        }

        /**
         * 把一份滤镜状态**整块写回控件**（滑块 / 开关 / 百分比 / 右侧预览）。
         *
         * ⚠️ 别只写一部分：SeekBar 的监听只在 `fromUser=true` 时触发（见 [slider]），程序化改
         * `progress` 既不会刷标签、也不会刷预览 —— 「点重置后两个滑块不归位、要重进面板才对」
         * 和「带着滤镜打开面板时右侧预览是没处理的图」都是同一个原因。必须走同一个函数灌值。
         */
        fun syncControls(f: ReaderColorFilter) {
            sbBright.progress = (f.brightness * 100).toInt().coerceIn(-100, 100)
            sbContrast.progress = (f.contrast * 100).toInt().coerceIn(-100, 100)
            swInvert.isChecked = f.isInverted
            swGray.isChecked = f.isGrayscale
            swBook.isChecked = f.isBookBackground
            tvBright.text = "${sbBright.progress}%"
            tvContrast.text = "${sbContrast.progress}%"
            refreshProc()
        }

        sbBright.setOnSeekBarChangeListener(slider { tvBright.text = "${sbBright.progress}%"; refreshProc(); pushIfUser() })
        sbContrast.setOnSeekBarChangeListener(slider { tvContrast.text = "${sbContrast.progress}%"; refreshProc(); pushIfUser() })
        swInvert.setOnCheckedChangeListener { _, _ -> refreshProc(); pushIfUser() }
        swGray.setOnCheckedChangeListener { _, _ -> refreshProc(); pushIfUser() }
        swBook.setOnCheckedChangeListener { _, _ -> refreshProc(); pushIfUser() }

        // 初始态：把宿主的当前滤镜灌进控件（含右侧预览）。宿主已有这份值，不必回推
        suppressPush = true
        syncControls(state.colorFilter)
        suppressPush = false

        view.findViewById<View>(R.id.btn_reset_color).setOnClickListener {
            // 面板自己归位（控件 + 预览），宿主侧由 onResetColor 负责（写 prefs + 重载当前页）。
            // 归位期间禁止 push：中间态（亮度已归零、开关还没）不该落盘
            suppressPush = true
            syncControls(ReaderColorFilter.EMPTY)
            suppressPush = false
            cb.onResetColor()
        }

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
            R.id.tv_rotate_label, R.id.tv_auto_turn_label, R.id.tv_download_label, R.id.tv_settings_label,
            R.id.tv_ocr_model_row, R.id.tv_translator_model_row,
            R.id.tv_source_lang_value, R.id.tv_target_lang_value,
            R.id.tv_debounce_label, R.id.tv_ahead_label,
            // 与「OCR模型 / 翻译模型」两行同为 14sp 正文色
            R.id.tv_font_size_row
        ).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(labelColor)
        }
        listOf(
            R.id.tv_brightness_value, R.id.tv_contrast_value, R.id.tv_color_hint,
            R.id.tv_rotate_value, R.id.tv_interval_value, R.id.tv_download_value,
            R.id.tv_source_caption, R.id.tv_target_caption,
            R.id.tv_debounce_value, R.id.tv_ahead_value
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
        // 语言行下拉图标随深浅
        val spinnerColor = if (dark) 0xFFB8BCC2.toInt() else 0xFF777777.toInt()
        listOf(R.id.iv_source_spinner, R.id.iv_target_spinner).forEach { id ->
            view.findViewById<ImageView>(id).setColorFilter(spinnerColor)
        }
        // ⚠️ 翻译模式三选项是 RadioButton：文字/按钮颜色来自**主题**（DayNight 的浅色分支给的是
        // 接近白的浅色文字），而本面板底色是写死的 0xFFFFFFFF → 浅色下白字压白底，看不见也点不着。
        // 必须与同类 TextView 一样显式着色，并显式给按钮 tint（默认 tint 同样来自主题）。
        applyTranslateModeTheme(view, dark)
        // 外层面板容器背景实时跟随深浅（不再只用 onStart 初始值）
        reapplySheetContainerBg()
        // 语言两格圆角背景随深浅
        val langCellBg = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(if (dark) 0xFF2A2A2C.toInt() else 0xFFF2F4F7.toInt())
        }
        listOf(R.id.btn_source_lang, R.id.btn_target_lang).forEach { id ->
            view.findViewById<View>(id).background = langCellBg
        }
    }

    /**
     * 翻译模式三选项（`RadioButton`）配色。
     *
     * 它们是本面板唯一的系统按钮类控件（其余都是自绘/ImageView/Switch）。不加这段时，文字与
     * 圆形按钮的 tint 全部来自主题 `Theme.MaterialComponents.DayNight`（见 Manifest 里
     * MangaReaderActivity 的 `android:theme`）：浅色分支给的是浅色文字，压在面板写死的
     * `0xFFFFFFFF` 底色上 → 「白字白底」，用户既看不清也点不准。深色面板下则反过来被主题的
     * 浅色 tint 兜住、看起来正常 —— 所以这个 bug 只在浅色背景时暴露。
     */
    private fun applyTranslateModeTheme(view: View, dark: Boolean) {
        val text = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        // 按钮圆圈沿用分段选中色的蓝，未选中用与其它图标一致的灰
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
        val group = cell as? ViewGroup
        val sel = group?.getChildAt(0) as? ImageView
        // 图标通常是 cell 的第 2 个子 View；Webtoon 图标外面包了一层容器（要挂「译」角标），
        // 那时 getChildAt(1) 拿到的是容器而非 ImageView。
        // ⚠️ 不要省掉兜底：取不到 icon 时选中态**不染色也不报错**，静默变成"点了没反应"。
        val icon = (group?.getChildAt(1) as? ImageView) ?: findDescendantIcon(group, skip = sel)
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

    /**
     * 在分段 cell 里递归找图标 ImageView（跳过选中圆所在的子树）。
     * 只有图标被额外包了一层容器时才走到这里，常规 cell 直接 `getChildAt(1)` 命中。
     */
    private fun findDescendantIcon(v: View?, skip: View?): ImageView? {
        if (v == null || v === skip) return null
        if (v is ImageView) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            findDescendantIcon(v.getChildAt(i), skip)?.let { return it }
        }
        return null
    }

    /** 向后翻译页数滑块只在「增量」模式显示。 */
    private fun applyAheadRowVisibility(view: View, mode: Int) {
        view.findViewById<View>(R.id.row_ahead_pages).visibility =
            if (mode == 2) View.VISIBLE else View.GONE
    }

    private fun slider(onRefresh: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onRefresh()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ===== 翻译面板：汇总 / 过滤 / 外部刷新 =====

    // ===== 翻译面板：模型/语言区 =====

    private fun refreshLangRow(view: View) {
        val prefs = CustomPreference.getInstance(requireContext())
        view.findViewById<TextView>(R.id.tv_source_lang_value).text =
            CustomLocale.getInstance(prefs.getString("Source_Language", "ja")).getDisplayName()
        view.findViewById<TextView>(R.id.tv_target_lang_value).text =
            CustomLocale.getInstance(prefs.getString("Target_Language", "zh")).getDisplayName()
    }

    /**
     * 测试缝：语言下拉数据源。
     *
     * `TranslateTools.getLanguagesList` 会读真实 prefs（`OcrEngineManager` 的引擎组、
     * `CustomPreference` 的当前语言）。它在 `onCreateView` 里就会被调用，任何只想断言面板
     * 结构的测试都会被这些外部状态拖住 —— 换掉它即可。
     */
    @androidx.annotation.VisibleForTesting
    var languagesList: (Int, OcrEngineGroup?) -> List<CustomLocale> = { type, group ->
        TranslateTools.getLanguagesList(requireContext(), type, group) ?: emptyList()
    }

    /**
     * 译文大小选择。走三处共用的 [MangaFontSizeDialog]（设置页/悬浮窗/阅读器面板同一份实现），
     * 配色只需告诉它当前面板深浅 —— 阅读器面板不随全局主题，必须显式传。
     */
    private fun showFontSizeDialog(onChanged: () -> Unit) {
        MangaFontSizeDialog.create(requireContext(), dark = darkPanel) { onChanged() }.show()
    }

    /** 语言选择弹窗（复刻主页 showLanguageListDialog，主题随 darkPanel）。 */
    private fun showLangDialog(type: Int) {
        val ctx = requireContext()
        val appPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val customPrefs = CustomPreference.getInstance(ctx)
        val ocrGroup = if (type == 1) OcrEngineManager.getOcrEngineGroup(appPrefs) else null
        val locales = languagesList(type, ocrGroup)
        if (locales.isEmpty()) return
        // 只有「预制 Hy-MT2」套官方 38 种白名单；导入的任意 GGUF 不限制
        val isHyMt2 = com.moe.starflow.llamacpp.LlamaCppModelStore.isHyMt2ActiveFromPrefs(customPrefs)
        val disabledTargets = if (type == 2) TranslateTools.getDisabledTargetLangs(customPrefs) else emptySet()
        val enabled = when (type) {
            1 -> locales.map { ReaderTranslationInfo.isSourceSupported(it.getOriCode(), ocrGroup!!.sourceLangs) }
            2 -> locales.map {
                ReaderTranslationInfo.isTargetSupported(
                    it.getOriCode(), isHyMt2,
                    com.moe.starflow.llamacpp.LlamaCppLanguages.hyMt2SupportedCodes, disabledTargets
                )
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

    /** 置灰语言提示弹窗（主题随 darkPanel；深浅文字重着色）。 */
    private fun showHintDialog(msg: String) {
        val dlg = AlertDialog.Builder(requireContext())
            .setMessage(msg)
            .setPositiveButton(R.string.user_known, null)
            .create()
        dlg.show()
        dlg.window?.setBackgroundDrawableResource(if (darkPanel) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white)
        if (darkPanel) recolorLang(dlg.window?.decorView)
    }

    private fun recolorLang(v: View?) {
        if (v == null) return
        if (v is TextView) v.setTextColor(0xFFE2E2E4.toInt())
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) recolorLang(v.getChildAt(i))
    }

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

        /** 启动延迟范围（ms），与 sheet_reader_menu.xml 的 sb_debounce min/max 一致。 */
        private const val DEBOUNCE_MIN = 200
        private const val DEBOUNCE_MAX = 2000

        /** 向后翻译页数范围，与 sheet_reader_menu.xml 的 sb_ahead min/max 一致。 */
        private const val AHEAD_MIN = 1
        private const val AHEAD_MAX = 10
    }
}