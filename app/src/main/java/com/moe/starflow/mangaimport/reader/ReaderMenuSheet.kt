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
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.mangaChapterLabel
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.mangaimport.translate.ReaderTranslationInfo
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.LanguageSelectionDialog
import com.moe.starflow.translate.TranslateTools
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.MangaFontSize
import com.moe.starflow.sr.SrModelManager
import com.moe.starflow.sr.SrSettings
import com.moe.starflow.sr.SuperResolutionEngines
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.utils.OcrEngineManager
import com.moe.starflow.utils.ReaderDialogs

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
    val pageTranslations: List<ImportedPageTranslation> = emptyList(),  // 每页翻译记录快照
    /** 章节表（空 = 单章）。翻译面板顶部的章切换行与记录列表分组都用它。 */
    val chapters: List<MangaChapter> = emptyList(),
    /** 当前选中的章下标（默认 = 阅读器当前所在章）。 */
    val currentChapter: Int = 0,
    /** 各章的后台任务状态（章卡片按钮文案、「进行中」都按它显示）。 */
    val chapterJobs: Map<Int, ChapterJobState> = emptyMap(),
    /** 各章任务已完成的页数。 */
    val chapterJobDone: Map<Int, Int> = emptyMap(),
    /** 排队中（还没开始翻）的页 —— 面板把它们标成「等待」。 */
    val waitingPages: Set<Int> = emptySet(),
    /** 漫画的「同时请求数」（2-5）。 */
    val concurrency: Int = 3,
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
    /** 点调色面板的「超分模型」行：跳模型管理页的**超分 Tab**。 */
    val onOpenSrModelManagement: () -> Unit = {},
    /** 阅读器超分开关被切换（值已写入 prefs）。 */
    val onReaderSrChanged: (Boolean) -> Unit = {},
    /** Anime4K 档位被切换（值已写入 prefs）：宿主需作废渲染缓存。 */
    val onAnime4kModeChanged: () -> Unit = {},
    /** 点章卡片主按钮：没有任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续。 */
    val onChapterPrimary: (Int) -> Unit = {},
    /** 点章卡片次按钮：没有任务 = 清除本章译文（宿主负责二次确认）；有任务 = 取消该章任务。 */
    val onChapterSecondary: (Int) -> Unit = {},
    /** 删除某一页的翻译数据（面板行内「删除」）。 */
    val onDeletePage: (Int) -> Unit = {},
    /** 点章卡片空白处 = 跳到该章（切章只从记录里切，面板不再有左右切章组件）。 */
    val onChapterSelected: (Int) -> Unit = {},
    /**
     * 回读宿主**当前真实**的章节状态（章表 / 当前章 / 各章任务 / 等待页）。
     *
     * ⚠️ 与 [currentTranslateMode] 同一套理由：面板是打开那一刻的快照，宿主随时可能改
     * （翻页换章、批量任务开跑/暂停/收尾），面板必须能拿到**此刻**的真值。
     */
    val currentChapterState: () -> ChapterPanelState = { ChapterPanelState() },
    /**
     * 回读宿主**当前真实**的翻译模式。
     *
     * ⚠️ 必须：宿主在 [onPanelOpened] 里会把模式回退到手动，面板若不回读就会停在打开前的
     * 选中项（例如「自动」）—— 而用户想切回自动时点的正是那个已选中的条目，
     * RadioButton 在同组内重复选中不派发 onCheckedChanged → 模式彻底切不动。
     */
    val currentTranslateMode: () -> Int = { 0 },
    /** 漫画的「同时请求数」变化（2-5）：宿主写 prefs，下一次任务生效。 */
    val onConcurrency: (Int) -> Unit = {},
    /**
     * 面板里改了**译文字号 / 自动字号**。
     *
     * ⚠️ 字号不在渲染缓存 key 里（key 只有 `page:idx:MODE`）→ 宿主必须**作废已渲染译图**
     * 并重渲染当前页，不然滑块拖完屏幕上还是旧字号。
     */
    val onFontSizeChanged: () -> Unit = {},
)

/** 面板回读用的章节快照（宿主侧真值）。 */
class ChapterPanelState(
    val chapters: List<MangaChapter> = emptyList(),
    val currentChapter: Int = 0,
    val records: List<ImportedPageTranslation> = emptyList(),
    /** 各章任务状态 / 已完成页数 / 排队页（章卡片按钮与「等待」标签都靠它们）。 */
    val jobs: Map<Int, ChapterJobState> = emptyMap(),
    val jobDone: Map<Int, Int> = emptyMap(),
    val waitingPages: Set<Int> = emptySet(),
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

    /**
     * 每页/每章记录列表适配器。
     *
     * 章标题的本地化交给面板注入（`mangaChapterLabel` 与阅读器顶部胶囊、章节目录弹窗**同一份**），
     * 三个地方各拼一套的话「第0章 / 序章 / ch1」会同时出现。
     */
    private val pageAdapter by lazy {
        ReaderPageStateAdapter(
            chapterLabelOf = { mangaChapterLabel(requireContext(), it) },
            onJump = { cb.onTranslatePageJump(it) },
            onSelectChapter = { cb.onChapterSelected(it) },
            onChapterPrimary = { cb.onChapterPrimary(it) },
            onChapterSecondary = { cb.onChapterSecondary(it) },
            onDeletePage = { cb.onDeletePage(it) },
        )
    }
    private var currentRecords: List<ImportedPageTranslation> = emptyList()

    /** 面板内的章节快照（宿主推送 / 刚打开时回读）。 */
    private var chapters: List<MangaChapter> = emptyList()
    private var selectedChapter = 0
    private var jobs: Map<Int, ChapterJobState> = emptyMap()
    private var jobDone: Map<Int, Int> = emptyMap()
    private var waitingPages: Set<Int> = emptySet()
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
        // 章节状态同样回读：面板的 onCreateView 是异步的，这中间宿主完全可能已经翻页换章，
        // 不覆盖的话章标题行会停在上一次打开时的章号
        run {
            val st = cb.currentChapterState()
            chapters = st.chapters
            selectedChapter = st.currentChapter
            currentRecords = st.records
            jobs = st.jobs
            jobDone = st.jobDone
            waitingPages = st.waitingPages
            view?.let { pushToAdapter() }
        }
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

        // 翻译面板：模式骨架 + 同时请求数 + 过滤 + 章节卡片/页记录列表
        currentRecords = state.pageTranslations
        chapters = state.chapters
        selectedChapter = state.currentChapter
        jobs = state.chapterJobs
        jobDone = state.chapterJobDone
        waitingPages = state.waitingPages
        val rvPages = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_pages)
        rvPages.layoutManager = LinearLayoutManager(requireContext())
        rvPages.adapter = pageAdapter
        pushToAdapter()
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

        // 译文大小：与**文本阅读器同一个滑块样式**（标签在左、值贴右、进度条占满一行）。
        // ⚠️ 仍走 `MangaFontSize`（个性化设置页 / 悬浮窗菜单 / 这里三处同一份设置）；
        // 「自动」是漫画独有的，做成右侧小胶囊；滑块档位就是 `MangaFontSize.PRESET_SIZES` 的下标。
        val tvFontSizeValue = view.findViewById<TextView>(R.id.tv_font_size_value)
        val btnFontAuto = view.findViewById<TextView>(R.id.btn_font_auto)
        val sbFontSize = view.findViewById<SeekBar>(R.id.sb_font_size)
        sbFontSize.max = (MangaFontSize.PRESET_SIZES.size - 1).coerceAtLeast(1)
        fun refreshFontSizeRow() {
            val ctx = requireContext()
            val auto = MangaFontSize.isAuto(ctx)
            tvFontSizeValue.text = MangaFontSize.summary(ctx)
            sbFontSize.progress = MangaFontSize.presetIndexOf(ctx).coerceIn(0, sbFontSize.max)
            // 自动态：胶囊高亮；固定态：只留描边
            btnFontAuto.setTextColor(if (auto) 0xFF55AEEA.toInt() else if (darkPanel) 0xFF9A9A9F.toInt() else 0xFF888888.toInt())
            btnFontAuto.background = GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(if (auto) (if (darkPanel) 0x332E86C9 else 0x1A2E86C9) else 0x00000000)
                setStroke((1f * resources.displayMetrics.density).toInt(), if (darkPanel) 0x33FFFFFF else 0x22000000)
            }
        }
        refreshFontSizeRow()
        btnFontAuto.setOnClickListener {
            // 切自动：按气泡自适应（滑块位置保留上一次的固定值，切回来还在）
            MangaFontSize.setAuto(requireContext(), !MangaFontSize.isAuto(requireContext()))
            refreshFontSizeRow()
            // ⚠️ 字号不在渲染缓存 key 里，改完必须作废已渲染译图，否则屏幕上还是旧字号
            cb.onFontSizeChanged()
        }
        sbFontSize.setOnSeekBarChangeListener(slider {
            val index = sbFontSize.progress.coerceIn(0, sbFontSize.max)
            MangaFontSize.setSize(requireContext(), MangaFontSize.PRESET_SIZES[index].toFloat())
            refreshFontSizeRow()
            cb.onFontSizeChanged()
        })

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
        setupConcurrency(view)

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
            // ⚠️ 右侧「处理后」要**真的走一遍增强**：此前只套调色滤镜，
            // 于是切 Anime4K 档 / 开超分在预览里看不到任何变化（用户报的"预览没效果"）。
            // 增强结果与输入同尺寸，换 bitmap 不改变格子里的构图。
            ivProc.setImageBitmap(enhancedPreview(bmp) ?: bmp)
        }
        // ── 画质增强（调色面板，2026-10）──
        // ⚠️ 超分控件都在**调色面板**（用户口径：它调的是"画面看起来怎样"，与调色同类）。
        // ⚠️ 超分开关与 Anime4K **互不干涉、可叠加**：开关只管超分模型那一道。
        //    之前 Anime4K 被超分开关闸住（而那个开关默认还关着）→ 用户在调色面板开了它却毫无反应，
        //    这正是「切了档、预览和阅读器都没任何效果」的根因。
        /**
         * 让调色面板右侧「处理后」立刻反映当前的增强设置。
         *
         * ⚠️ 不能调下面那个 `refreshProc()`：Kotlin 的局部函数**不允许前向引用**，
         * 而它定义在更后面 —— 这里就地套一遍同一份调色滤镜（`cur()` 与 `ivProc` 此刻已可用）。
         */
        fun refreshEnhancedPreview() {
            val raw = state.previewBitmap ?: return
            ivProc.setImageBitmap(enhancedPreview(raw) ?: raw)
            ivProc.colorFilter = cur().toColorFilter()
        }

        val srPrefs = CustomPreference.getInstance(requireContext()).getSharedPreferences()
        view.findViewById<TextView>(R.id.tv_sr_model_row).text =
            getString(R.string.reader_sr_model_row, srModelLabel(srPrefs))
        view.findViewById<View>(R.id.btn_model_sr).setOnClickListener { cb.onOpenSrModelManagement() }
        view.findViewById<Switch>(R.id.sw_reader_sr).apply {
            isChecked = SrSettings.isEnabledForReader(srPrefs)
            setOnCheckedChangeListener { _, checked ->
                SrSettings.setReaderEnabled(srPrefs, checked)
                // 关掉时把已加载的超分引擎放掉（2x 模型几百 MB）；打开时不预热 —— 首翻再建
                if (!checked) SuperResolutionEngines.releaseSrModel()
                refreshEnhancedPreview()
                cb.onReaderSrChanged(checked)
            }
        }

        // Anime4K 档位：**弹窗选择**（不是点一下循环切档）—— 每档带一句说明，
        // 同时解决"切换不美观"与"看不懂各档是什么意思"。
        fun refreshAnime4kRow() {
            val p = CustomPreference.getInstance(requireContext()).getSharedPreferences()
            view.findViewById<TextView>(R.id.tv_anime4k_value).text =
                if (!Anime4kMode.isEnabled(p)) getString(R.string.reader_anime4k_off)
                else getString(Anime4kMode.labelResOf(Anime4kMode.fromPrefs(p)))
        }
        fun applyAnime4k(enabled: Boolean, mode: Anime4kMode?) {
            val p = CustomPreference.getInstance(requireContext()).getSharedPreferences()
            Anime4kMode.setEnabled(p, enabled)
            if (mode != null) Anime4kMode.setMode(p, mode)
            // 换档 = 之前那份增强结果全部作废（引擎缓存 + 设置指纹都会跟着变）
            SuperResolutionEngines.releaseAnime4k()
            refreshAnime4kRow()
            refreshEnhancedPreview()
            cb.onAnime4kModeChanged()
        }
        view.findViewById<View>(R.id.btn_anime4k).setOnClickListener {
            val p = CustomPreference.getInstance(requireContext()).getSharedPreferences()
            // 顺序由 Anime4kMode.nextAfter 定义（由弱到强），第 0 项固定是「关闭」
            val order = generateSequence(Anime4kMode.nextAfter(null)) { Anime4kMode.nextAfter(it) }.toList()
            val curMode = if (Anime4kMode.isEnabled(p)) Anime4kMode.fromPrefs(p) else null
            val labels = ArrayList<String>(order.size + 1)
            labels.add(getString(R.string.reader_anime4k_desc_off))
            order.forEach {
                labels.add(getString(Anime4kMode.labelResOf(it)) + " — " + getString(Anime4kMode.descResOf(it)))
            }
            val checked = curMode?.let { order.indexOf(it) + 1 } ?: 0
            ReaderDialogs.show(requireContext(), darkPanel) {
                setTitle(R.string.reader_anime4k_label)
                setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                    if (which == 0) applyAnime4k(false, null) else applyAnime4k(true, order[which - 1])
                    dlg.dismiss()
                }
                setNegativeButton(android.R.string.cancel, null)
            }
        }
        refreshAnime4kRow()

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
    /**
     * 「超分模型」行要显示的文案。三种状态都显式给出：未选 / 选了但没下载 / 正常。
     *
     * ⚠️「选了但没下载」必须露出来 —— 否则用户开了开关却什么都没发生，面板上看着一切正常。
     */
    private fun srModelLabel(prefs: SharedPreferences): String {
        val key = SrModelManager.getActiveKey(prefs) ?: return getString(R.string.reader_sr_none)
        val name = getString(SrModelManager.nameResOf(key))
        return if (SrModelManager.isDownloaded(requireContext(), key)) name
        else getString(R.string.reader_sr_not_downloaded, name)
    }

    /**
     * 调色面板右侧「处理后」预览格用的增强 —— 走**阅读器同一条链路**
     * （`SrPageEnhancer.enhanceForReader`）。预览里看到什么，翻页后页面上就是什么。
     * 失败/未开启 → null，调用方回退原图。
     */
    private fun enhancedPreview(src: android.graphics.Bitmap): android.graphics.Bitmap? =
        runCatching { com.moe.starflow.sr.SrPageEnhancer.enhanceForReader(src) }.getOrNull()

    /**
     * 面板主题的**标题色**控件（浅色 `#333333` / 深色 `#E2E2E4`）。
     *
     * ⚠️ **一个控件只能出现在本数组与 [panelSubIds] 之一**：两段 `setTextColor` 先后执行、
     * **后写的赢** —— 同一个 id 两处都登记，它会静默变成次要色（「翻译模型最大同时请求数」的标题
     * 就这样被反复改回淡色）。这条由 `PanelThemeGuardTest` 机械守卫（读下面的 BEGIN/END 标记）。
     */
    // PANEL_THEME_LABEL_IDS_BEGIN
    private val panelLabelIds = intArrayOf(
        R.id.tv_mode_label, R.id.tv_animation_label, R.id.tv_background_label,
        R.id.tv_translate_mode_label,
        R.id.tv_color_title, R.id.tv_color_inverted, R.id.tv_color_grayscale, R.id.tv_color_book,
        R.id.tv_brightness_label, R.id.tv_contrast_label,
        R.id.tv_rotate_label, R.id.tv_auto_turn_label, R.id.tv_download_label, R.id.tv_settings_label,
        R.id.tv_ocr_model_row, R.id.tv_translator_model_row,
        R.id.tv_sr_model_row, R.id.tv_sr_switch_label, R.id.tv_anime4k_label,
        R.id.tv_source_lang_value, R.id.tv_target_lang_value,
        R.id.tv_debounce_label, R.id.tv_ahead_label,
        // 与「OCR模型 / 翻译模型」两行同为 14sp 正文色
        R.id.tv_font_size_row,
        // 「同时请求数」的**标题**和其它标题同色（用户口径 2026-09-28：改成和其他标题一样的纯黑）
        R.id.tv_concurrency_label,
    )
    // PANEL_THEME_LABEL_IDS_END

    /** 面板主题的**次要色**控件（值 / 说明行）。⚠️ 不许与 [panelLabelIds] 重复。 */
    // PANEL_THEME_SUB_IDS_BEGIN
    private val panelSubIds = intArrayOf(
        R.id.tv_brightness_value, R.id.tv_contrast_value, R.id.tv_color_hint,
        R.id.tv_rotate_value, R.id.tv_interval_value, R.id.tv_download_value,
        R.id.tv_source_caption, R.id.tv_target_caption,
        R.id.tv_debounce_value, R.id.tv_ahead_value,
        R.id.tv_sr_hint, R.id.tv_anime4k_hint, R.id.tv_anime4k_value,
        // 「同时请求数」只剩**值/说明**走次要色
        R.id.tv_concurrency_hint,
        // 字号行的值与说明（「自动」胶囊的配色在 refreshFontSizeRow 里按状态给）
        R.id.tv_font_size_value, R.id.tv_font_size_hint,
        // 启动延迟的说明行
        R.id.tv_debounce_hint,
    )
    // PANEL_THEME_SUB_IDS_END

    private fun applyPanelTheme(view: View, root: View) {
        val dark = darkPanel
        root.setBackgroundColor(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        panelLabelIds.forEach { id ->
            view.findViewById<TextView>(id).setTextColor(labelColor)
        }
        panelSubIds.forEach { id ->
            view.findViewById<TextView>(id).setTextColor(subColor)
        }
        // 分段未选中文字 + 复合图标颜色（随深浅）
        reapplySegments(view)
        // 每页状态列表行内配色（元数据/原文译文/复制）随面板深浅
        pageAdapter.dark = darkPanel
        // 调色/更多面板 Switch 配色（避免与面板背景重叠/看不清）
        val swTrack = if (dark) 0xFF3A4046.toInt() else 0xFFCFD8DC.toInt()
        listOf(R.id.sw_invert, R.id.sw_grayscale, R.id.sw_book, R.id.sw_auto_turn, R.id.sw_reader_sr).forEach { id ->
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
        // 「同时请求数」行文字随面板深浅（标签在 subColor 组、数值固定强调色）
        view.findViewById<TextView>(R.id.tv_concurrency_value).setTextColor(0xFF55AEEA.toInt())
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

    /** 置灰语言提示弹窗（走共享的 ReaderDialogs：底与字同源）。 */
    private fun showHintDialog(msg: String) {
        // ⚠️ 这里以前是「换窗口底 + recolorLang 把字刷浅」——**白底白字**的元凶：
        // `AlertDialog` 的面板底由主题给（系统浅色时是白的），窗口底被面板盖住，而字被刷成了浅色
        // → 完全看不见（用户 2026-09-28 报的「背景白色文字你也搞成白色」）。
        // 现在用**对应 night 模式的上下文**建弹窗：面板/文字/按钮统一由主题给色。
        ReaderDialogs.show(requireContext(), darkPanel) {
            setMessage(msg)
            setPositiveButton(R.string.user_known, null)
        }
    }

    /**
     * 把宿主侧状态推给列表适配器（章节卡片 + 页记录都要这些）。
     *
     * ⚠️ 这是**唯一**的宿主 → 列表推送出口：新增宿主可改的字段必须一起加进来，
     * 否则那处 UI 永远停在打开那一刻。
     */
    private fun pushToAdapter() {
        pageAdapter.submit(
            chapters = chapters,
            records = currentRecords,
            selectedChapter = selectedChapter,
            jobs = jobs,
            jobDone = jobDone,
            waitingPages = waitingPages,
        )
    }

    /** 「同时请求数」滑块（漫画批量任务用；与文本那个设置互相独立）。 */
    private fun setupConcurrency(view: View) {
        val sb = view.findViewById<SeekBar>(R.id.sb_concurrency)
        val tv = view.findViewById<TextView>(R.id.tv_concurrency_value)
        val value = state.concurrency.coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX)
        // ⚠️ 设了 android:min 的 SeekBar，progress 就是**绝对值**
        sb.progress = value
        tv.text = "$value"
        sb.setOnSeekBarChangeListener(slider {
            val n = sb.progress
            tv.text = "$n"
            cb.onConcurrency(n)
        })
    }

    /**
     * 外部刷新入口（翻译任务开始/完成/失败、切章、批量任务进度都走这里）。
     *
     * ⚠️ 这是**唯一**的宿主 → 面板推送入口（与小说面板的 `notifyHostState` 同一约定）：
     * 新增宿主可改的字段必须一起加进来，否则那处 UI 永远停在打开那一刻。
     */
    fun notifyTranslateChanged(
        records: List<ImportedPageTranslation>,
        chapters: List<MangaChapter>,
        selectedChapter: Int,
        jobs: Map<Int, ChapterJobState>,
        jobDone: Map<Int, Int>,
        waitingPages: Set<Int>,
    ) {
        currentRecords = records
        this.chapters = chapters
        this.selectedChapter = selectedChapter
        this.jobs = jobs
        this.jobDone = jobDone
        this.waitingPages = waitingPages
        pushToAdapter()
    }

    /**
     * 筛选 chips：全部 / 完成 / 进行中 / 失败。
     *
     * ⚠️ 过滤**不在面板这一层做**，而是交给 `pageAdapter.setFilter`：记录列表是「章表头 + 页行」
     * 两段式的，过滤只能作用于页行；在面板里先筛一遍再交给适配器，会把没有记录的章表头也一起筛掉。
     *
     * ⚠️ 与改造前的区别：原来把失败再细分成「OCR空 / 翻译空 / 异常」三个 chip，
     * 而失败的**具体原因**本来就写在行内（`tv_fail_message`）——细分 chip 既占位置又没必要，
     * 现在统一收敛到「失败」（用户口径）。
     */
    private fun setupTranslateFilter(view: View) {
        val row = view.findViewById<ViewGroup>(R.id.translate_filter_row)
        row.removeAllViews()
        val options = listOf(
            ReaderPageStateAdapter.FILTER_ALL to R.string.reader_translate_filter_all,
            ReaderPageStateAdapter.FILTER_DONE to R.string.reader_translate_filter_done,
            ReaderPageStateAdapter.FILTER_ONGOING to R.string.reader_translate_filter_ongoing,
            ReaderPageStateAdapter.FILTER_FAILED to R.string.reader_translate_filter_failed,
        )
        for ((key, label) in options) {
            val chip = TextView(requireContext()).apply {
                text = getString(label)
                textSize = 15f
                setPadding(dp8 * 2, dp8, dp8 * 2, dp8)
                tag = key
                setOnClickListener {
                    currentFilterKey = key
                    refreshFilterChipStyle(row)
                    pageAdapter.setFilter(key)
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

        /** 同时请求数范围（漫画），与 sheet_reader_menu.xml 的 sb_concurrency min/max 一致。 */
        private const val CONCURRENCY_MIN = 2
        private const val CONCURRENCY_MAX = 5
    }
}