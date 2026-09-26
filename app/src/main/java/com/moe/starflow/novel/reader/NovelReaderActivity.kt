package com.moe.starflow.novel.reader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.databinding.ActivityNovelReaderBinding
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.mangaimport.reader.CoverTransformer
import com.moe.starflow.mangaimport.reader.CurlPageView
import com.moe.starflow.mangaimport.reader.NoneTransformer
import com.moe.starflow.mangaimport.reader.ReaderAnimationState
import com.moe.starflow.mangaimport.reader.SimulationTransformer
import com.moe.starflow.me.settings.SettingPageActivity
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.translate.NovelBatchTranslator
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelEngineConfig
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode
import com.moe.starflow.novel.translate.NovelQueuePhase
import com.moe.starflow.novel.translate.NovelQueueState
import com.moe.starflow.novel.translate.NovelTranslationEngine
import com.moe.starflow.novel.translate.NovelTranslationQueue
import com.moe.starflow.novel.translate.TranslationTextApiAdapter
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import translationapi.TranslatorFactory
import kotlin.math.roundToInt

/**
 * 小说阅读器。
 *
 * ⚠️ **与 `MangaReaderActivity` 是两套实现，但必须是同一套 UI 与交互**：chrome 的五个浮层
 * （返回/菜单圆形浮钮、顶部胶囊、底部胶囊、右下翻译浮层组）、九宫格点击分区、160ms 淡入淡出、
 * 底部四 Tab 面板、阅读背景与翻页动画（[NoneTransformer] / 滑动 / [CoverTransformer] 直接复用
 * 漫画那份 transformer 代码）全部对齐。内容不同（那边是位图页，这边是文字页）只体现在中间那层。
 *
 * 与漫画的**唯一**差别是右下翻译浮层不用 OCR —— 检测那一步直接跳过。
 *
 * ### 两条主线的状态
 * - **阅读位置**：以「章号 + 章内段落号」为准（见 [persistProgress]）
 * - **翻译**：`translations` 是本章译文，到达后重新分页（译文比原文长，必须重排）
 */
class NovelReaderActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NovelReader"
        const val EXTRA_NOVEL_ID = "novel_id"

        /** 上下 UI 显隐的淡入淡出时长（与漫画一致）。 */
        private const val CHROME_FADE_MS = 160L

        /** 翻译按钮双击判定窗口（**与漫画同一个值**，语义也一致）。 */
        private const val DOUBLE_CLICK_MS = 300L

        /** 视口尺寸变化后的重排防抖（尺寸是连续事件，见 [scheduleRepaginate]）。 */
        private const val REPAGINATE_DEBOUNCE_MS = 180L

        /** 滚动模式按比例跳转时最多补齐几次（估算值会漂、一次 scrollBy 未必吃到目标，见 [seekScrollTo]）。 */
        private const val MAX_SEEK_PASSES = 60

        private const val KEY_ROTATE = "reader_rotate_mode"
        private const val KEY_AUTO_TURN = "reader_auto_turn"
        private const val KEY_INTERVAL = "reader_auto_turn_interval"

        private const val STATE_CURRENT_CHAPTER = "novel_state_chapter"
        private const val STATE_FROM_SETTINGS = "novel_state_from_settings"

        fun intent(context: Context, novelId: Long): Intent =
            Intent(context, NovelReaderActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId)
    }

    private lateinit var binding: ActivityNovelReaderBinding
    private lateinit var repository: NovelChapterRepository
    private lateinit var pageAdapter: NovelPageAdapter
    private lateinit var scrollAdapter: NovelScrollAdapter

    /** ⚠️ 与漫画阅读器同一个 prefs 文件：背景/翻页动画/旋转/自动翻页是**共用设置**。 */
    private lateinit var prefs: SharedPreferences

    private var book: ImportedNovel? = null
    private var chapterIndex = 0
    private var chapterCount = 0
    private var content: ChapterContent? = null
    private var translations: Map<Int, String> = emptyMap()
    private var chapterStats: Map<Int, NovelChapterStat> = emptyMap()

    /** 失败明细（章行展开显示原因）；与 chapterStats 一起推给面板。 */
    private var chapterFailures: Map<Int, List<NovelFailureRow>> = emptyMap()
    private var translationJob: Job? = null

    /**
     * 滚动模式的「已翻译」格集合（进度条绿条），在 [loadChapter] 里随内容与译文一起重算。
     *
     * 单位是 [NovelScrollProgress.STEPS] 格（不是段下标，也不是页）——
     * 与 [refreshOverlay] 推进度条时用的是同一套坐标。
     */
    private var scrollTranslated: Set<Int> = emptySet()

    /** 上下 UI（顶部三个浮层 + 底部胶囊 + 右下翻译浮层组）是否隐藏。 */
    private var chromeHidden = false

    private var bgMode = 0
    private var animationMode = NovelPanelStyle.ANIM_SLIDE

    private var autoTurnEnabled = false
    private var autoTurnIntervalSec = 5
    private var autoTurnJob: Job? = null

    /** 翻译按钮双击判定用的上次单击时刻（elapsedRealtime）。 */
    private var lastTranslateClickMs = 0L
    private var lastInteractionMs = 0L
    private var rotateMode = 0

    /** 自动翻译队列（手动模式下不起）。惰性创建：它要用到翻译引擎。 */
    private var queue: NovelTranslationQueue? = null

    /** 队列状态的观察者。队列被丢弃重建时要先 cancel，否则旧收集器一直挂在 lifecycleScope 上。 */
    private var queueObserverJob: Job? = null

    /** 模型/引擎配置变化的监听（见 [onEngineConfigChanged]）。 */
    private var enginePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 队列最近一次是按哪一章 / 哪个模式起的（重复调用时用来短路，省掉一次无谓重启）。 */
    private var queueChapter = -1
    /** 队列最近一次的模式（与 [queueChapter] 一起做「重复调用短路」）。 */
    private var queueMode: NovelTranslateMode? = null
    private var queueRunning = false

    /**
     * 当前阅读锚点（段号 + **段内比例**），见 [NovelAnchor]。
     *
     * ⚠️ 只有段号是不够的：段可以长到跨好几页（整章一段的样本就是），按段号恢复会落到
     * **段首那一页/段顶**，于是每翻一批译文重排一次就把读者拽回去（用户报的「翻译之后位置跳变」）。
     * 所有"重新加载但保持位置"的路径都传它；[persistProgress] 只存段号（断点续读的既有格式）。
     */
    private var pendingAnchor = NovelAnchor(0)

    /**
     * **带位重排**期间用的逻辑锚点（见 [onPaged]）。
     *
     * ⚠️ 这是「翻译之后位置一直往回跑」的修法：`NovelAnchors.pageOf` 只能给到**页**，
     * 而落位后 [onPaged] 会把锚点重取成"这一页的页首" —— 页首永远比逻辑位置靠前，
     * 于是每重排一批就后退最多一屏，**误差逐批累积、单向走**（实测 5 批丢掉一半位置）。
     * 所以带位重排期间必须把锚点**钉在逻辑位置上**，只有用户真的自己导航才允许重取。
     *
     * 清除时机只能是**用户真导航**（滑动/点按翻页/拖进度条/切章）——
     * 不能靠"落地后清"：ViewPager2 会在同一帧末再补派发一次 `onPageSelected`，
     * 那次会把锚点又打回页首。
     */
    private var carryAnchor: NovelAnchor? = null

    /**
     * **正在翻译 / 刚翻完**的那几段（琥珀高亮底）。
     *
     * 用户要求：译文到达必须重排，位置总有轻微偏移；把这一批高亮出来，用户偏移后还能一眼
     * 找到刚翻的是哪几段。手动 / 自动 / 增量都要有。
     *
     * ⚠️ 一批翻完**不清**（不清才找得到"刚翻完的内容"），只在换章 / 清空译文 / 换阅读模式时清。
     */
    private var activeBatch: Set<Int> = emptySet()

    /** 最近一次分页用的视口尺寸（px）。尺寸变了必须重排，否则页边界对不上真实视口。 */
    private var pagedWidth = 0
    private var pagedHeight = 0

    /**
     * `loadChapter` 的序号。**并发加载按序号丢弃过期结果**（见 [loadChapter] 说明）。
     *
     * 这是本阅读器最容易踩的一个坑：加载是异步的、慢的（读文件 + 整章 StaticLayout 分页），
     * 而触发它的路径又多（进入、每次尺寸变化、译文到达、改排版），
     * 一旦让过期结果落地就会把用户已经翻好的位置拽回去 —— 表现为「翻页/切章都没反应」。
     */
    private var loadToken = 0

    /** 本次离开是去「个性化设置」页 —— 返回时重建 Activity，让设置项真正生效。 */
    private var returnedFromSettings = false

    /** 仿真/覆盖动画共享状态（与漫画同一份实现）。 */
    private val animState = ReaderAnimationState()

    /** pager 最近一次的滚动状态。跳页（无滚动）与落定要区分开，见 [normalizePageTransforms]。 */
    private var pagerScrollState = ViewPager2.SCROLL_STATE_IDLE

    /** 在途的防抖重排（尺寸变化用），见 [scheduleRepaginate]。 */
    private var repaginateJob: Job? = null

    /**
     * 上一次由**布局**补刷进度条时用的首可见段（见 [setupOverlays] 里的 layout listener）。
     *
     * 存在的唯一理由是防死循环：补刷 → `setText` → `requestLayout` → 又一次布局 → 又补刷……
     */
    private var lastLayoutScrollItem = -1

    /**
     * **选择模式**（长按正文进入）里选中的段号，按选中顺序。
     *
     * 这是文本阅读器唯一的「重翻」入口（用户明确要求：不要像漫画那样让翻译按钮凭缓存命中
     * 变成重翻，而是长按多选任意段落来翻/重翻）。退出方式见 [exitSelection]。
     */
    private val selectedPara = linkedSetOf<Int>()
    private var selecting = false

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) = handleLongPress(e.x, e.y)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // UI 同步字体（开关开启时）：挂 Factory2，inflate 即应用。须在 super.onCreate 前挂。
        com.moe.starflow.utils.FontSync.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNovelReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        prefs = getSharedPreferences(NovelPanelStyle.PREFS_NAME, MODE_PRIVATE)

        // ⚠️ **缓存的引擎/队列必须随「模型选择」失效**：模型只是在设置页里写了 prefs，
        // 没有任何东西通知阅读器 —— 早先的现场就是「切换模型必须退出阅读器才生效」。
        // 监听注册在**阅读器**（而不是面板）里：改模型要先离开面板去设置页，
        // 面板回来时重新 onStart 才注册监听，那一刻**已经错过了**这次变化。
        val appPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        enginePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (NovelEngineConfig.affectsEngine(key)) onEngineConfigChanged()
        }
        appPrefs.registerOnSharedPreferenceChangeListener(enginePrefsListener)

        bgMode = NovelPanelStyle.background(prefs)
        animationMode = NovelPanelStyle.animation(prefs)
        autoTurnEnabled = prefs.getBoolean(KEY_AUTO_TURN, false)
        autoTurnIntervalSec = prefs.getInt(KEY_INTERVAL, 5)
        rotateMode = prefs.getInt(KEY_ROTATE, 0)

        val id = intent.getLongExtra(EXTRA_NOVEL_ID, -1L)
        val loaded = NovelStore.load(this).firstOrNull { it.id == id }
        if (loaded == null) {
            LogCollector.w(TAG, "找不到书籍 id=$id，关闭阅读器")
            finish()
            return
        }
        book = loaded
        chapterCount = loaded.chapterCount

        repository = NovelChapterRepository()
        pageAdapter = NovelPageAdapter()
        scrollAdapter = NovelScrollAdapter()
        binding.novelPager.adapter = pageAdapter
        binding.novelScroll.layoutManager = LinearLayoutManager(this)
        binding.novelScroll.adapter = scrollAdapter

        chapterIndex = loaded.lastReadChapter.coerceAtLeast(0)
        // 断点续读只存了段号（既有格式），段内比例从段首起算
        pendingAnchor = NovelAnchor(loaded.lastReadParaIndex)

        if (savedInstanceState != null) {
            returnedFromSettings = savedInstanceState.getBoolean(STATE_FROM_SETTINGS, false)
            chapterIndex = savedInstanceState.getInt(STATE_CURRENT_CHAPTER, chapterIndex)
        }

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyReaderMode()

        NovelDebug.log(
            "onCreate book=${loaded.id} chapters=$chapterCount ch=$chapterIndex para=${pendingAnchor.paraIndex} " +
                "mode=${NovelPanelStyle.readerMode(prefs)} anim=$animationMode bg=$bgMode " +
                "root=${binding.root.width}x${binding.root.height}"
        )

        // ⚠️ **先渲染正文**：首次进入绝不能只有「译文读回来」那一条链才会加载内容 ——
        // 那条链在「本章一句译文都没有」时会判定「没变化」直接返回，结果第一次进阅读器
        // 一片空白，必须切一次章才显示（用户实测反馈）。
        loadChapter(chapterIndex, anchor = pendingAnchor)

        lifecycleScope.launch {
            // 清理上次异常退出留下的「翻译中」标记（进程被杀时退出清理不会执行）
            runCatching { translator().resetStale(loaded) }
                .onFailure { LogCollector.w(TAG, "清理残留翻译状态失败", it) }
            refreshChapterStats()
            refreshTranslations()
        }
    }

    override fun onStart() {
        super.onStart()
        updateAutoTurn()
        // 从设置页返回必须重建：字号/字距等是在 Activity 创建时读进缓存、并已由渲染结果固化的，
        // 就地刷新既漏项又容易只改一半（与漫画同一套理由）。
        if (returnedFromSettings) {
            returnedFromSettings = false
            recreate()
            return
        }
        // ⚠️ onStop 会把队列停掉（后台不该继续翻），回前台必须自己接回来 ——
        // 没有这一步，「自动/增量」模式切后台再回来就永久停在原地了
        restartQueueIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        autoTurnJob?.cancel()
        autoTurnJob = null        // 切后台必须暂停队列：lifecycleScope 不因 onStop 取消，否则翻译会在后台整段跑，
        // 且常驻状态芯片（系统窗口）会一直盖在别的应用上
        queue?.stop()
        queueRunning = false
        // 退出阅读器**只暂停，不改模式**：用户说的是「退出阅读器自动暂停」——
        // 回来时 `onStart → restartQueueIfNeeded()` 会按**原来那个模式**接着翻。
        // ⚠️ 早先这里顺手把模式也回退成手动了（多做的），结果是「切个后台/息屏回来，
        // 自动翻译就没了，还得重新选」—— 那才是"老是暂停"的来源。
        TranslationStatusOverlay.getInstance(this@NovelReaderActivity).dismiss()
        // 离开阅读器清掉选择集：它是「长按多选」的临时状态，回到阅读器时不该还亮着
        exitSelection()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            updateAutoTurn()
        } else {
            autoTurnJob?.cancel()
            autoTurnJob = null
        }
    }

    override fun onPause() {
        super.onPause()
        persistProgress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_CHAPTER, chapterIndex)
        outState.putBoolean(STATE_FROM_SETTINGS, returnedFromSettings)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 视口尺寸变了 → 页表必须按新尺寸重排，否则字会溢出或被裁。
        // 走防抖：旋转/分屏会连着一串布局回调，直接重排会重排很多遍
        applyBackground()
        scheduleRepaginate()
    }

    override fun onDestroy() {
        queue?.stop()
        queueObserverJob?.cancel()
        enginePrefsListener?.let {
            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        enginePrefsListener = null
        translationJob?.cancel()
        if (::repository.isInitialized) repository.evictAll()
        // ⚠️ 状态浮层是**进程级 TYPE_APPLICATION_OVERLAY 系统窗口**，不清会挂在桌面/别的应用上
        TranslationStatusOverlay.getInstance(this@NovelReaderActivity).dismiss()
        super.onDestroy()
    }

    // ===== 依赖 =====

    private fun db() = TranslationHistoryDatabase.getInstance(this)

    private fun translator() = NovelChapterTranslator(
        dao = db().novelParagraphTranslationDao(),
        engine = NovelTranslationEngine(
            TranslationTextApiAdapter(
                TranslatorFactory.createForText(this, CustomPreference.getInstance(this))
                    ?: throw IllegalStateException("no engine")
            )
        ),
        splitVersion = NovelParagraphSplitter.SPLIT_VERSION,
    )

    // ===== chrome（与漫画逐项对齐） =====

    private fun setupOverlays() {
        // 返回键：选择模式下先退出选择（与「点空白处退出」同一件事），否则才退出阅读器。
        // `isEnabled=false` → 转发一次 → 再打开，是为了把"退出阅读器"这个默认行为交回系统，
        // 不自己 finish（自绘返回会漏掉系统的一些收尾）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) {
                    exitSelection()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.btnPrev.setOnClickListener { gotoChapter(chapterIndex - 1) }
        binding.btnNext.setOnClickListener { gotoChapter(chapterIndex + 1) }
        binding.tvPageIndicator.setOnClickListener { openToc() }
        binding.btnTranslate.setOnClickListener { onTranslateButtonClick() }
        binding.btnToggleTranslate.setOnClickListener { cycleDisplayMode() }
        binding.btnFailTranslate.setOnClickListener { showFailBubble() }

        binding.novelProgress.onSeek = { page -> goToPage(page) }
        binding.novelProgress.onLongPress = {
            lastInteractionMs = SystemClock.elapsedRealtime()
            openToc()
        }

        binding.novelPager.registerOnPageChangeCallback(pageChangeCallback)
        // 视口尺寸变化后重新吸附（旋转/分屏）：见漫画里同名处理的说明
        binding.novelPager.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l == or_ - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            if (or_ - ol == 0 || ob - ot == 0) return@addOnLayoutChangeListener
            realignPagerAfterResize()
        }
        // 分页器同样关掉默认 item 动画（notifyItemChanged 会带一层淡入淡出 → 落定页闪一下）
        (binding.novelPager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        (binding.novelPager.getChildAt(0) as? RecyclerView)?.setChildDrawingOrderCallback(
            object : RecyclerView.ChildDrawingOrderCallback {
                override fun onGetChildDrawingOrder(count: Int, i: Int): Int {
                    if (count <= 1) return i
                    val rv = binding.novelPager.getChildAt(0) as? RecyclerView ?: return i
                    return (0 until count).sortedBy { rv.getChildAt(it).translationZ }[i]
                }
            }
        )
        // 滚动模式：滚动即翻段，同步当前段/进度条/lastRead
        binding.novelScroll.clipToPadding = false
        binding.novelScroll.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateFromScroll()
        })
        // ⚠️ **程序化跳转不派发 onScrolled**：换章/续读走的是 `scrollToPosition`，
        // 布局跑完才知道自己滚到了哪 —— 少了这一句，从中间续读长章节时进度条会一直停在 0
        // 直到用户手动滑一下（用户报的「进度条失效」有这一半）。
        // ⚠️ 只在「首可见段真的变了」时才补刷：`updateChapterTocLabel` 里会 setText，
        // 那会 requestLayout → 又回调到这里 —— 不设这个闸门就是每帧一次布局的死循环。
        binding.novelScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isScrollMode()) return@addOnLayoutChangeListener
            val first = firstVisibleScrollItem()
            if (first == lastLayoutScrollItem) return@addOnLayoutChangeListener
            lastLayoutScrollItem = first
            binding.novelScroll.post { updateFromScroll() }
        }
        // 手势：⚠️ 挂到 ViewPager2 **内部那个真正消费触摸的 RecyclerView** 上。
        // 挂在页 View 上收不到 UP（页 View 不消费 DOWN → 不会成为 touch target），
        // 挂在 ViewPager2 自身上也收不到（子 View 消费后父的 onTouchEvent 就不再被调用）。
        // 详见 NovelPageAdapter 的类注释。返回 false 不消费，滑动翻页照常。
        installPagerTouch()
        binding.novelScroll.setOnTouchListener { _, e -> onReaderTouch(e); false }
    }

    /** 装手势到 ViewPager2 的内部 RecyclerView（它才是触摸的实际消费者）。 */
    private fun installPagerTouch() {
        val rv = binding.novelPager.getChildAt(0) as? RecyclerView ?: return
        rv.setOnTouchListener { _, e -> onReaderTouch(e); false }
    }

    /**
     * 触摸总入口：仿真动画先记录折线触点，再交给单击探测器。
     *
     * **永远返回 false**：消费掉事件会让 ViewPager2 / 滚动列表收不到拖拽，翻页与滚动就没了。
     */
    private fun onReaderTouch(e: MotionEvent): Boolean {
        if (animationMode == NovelPanelStyle.ANIM_SIMULATION && !isScrollMode()) {
            val size = (if (isVerticalMode()) binding.novelPager.height else binding.novelPager.width).toFloat()
            if (size > 0f) {
                animState.foldStartFraction =
                    ((if (isVerticalMode()) e.x else e.y) / size).coerceIn(0f, 1f)
            }
        }
        tapDetector.onTouchEvent(e)
        return false
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            // 与漫画同一套锚点判定：相对锚点而非瞬时增量，反向时折叠/位移不会跳变
            val marker = position + positionOffset
            if (kotlin.math.abs(marker - kotlin.math.round(marker)) < 0.02f) {
                animState.anchorPage = currentPage()
            }
            animState.isBackward = marker < animState.anchorPage
            animState.navigationProgress = (marker - animState.anchorPage).coerceIn(-1f, 1f)
        }

        override fun onPageSelected(position: Int) {
            NovelDebug.log("onPageSelected pos=$position itemCount=${pageAdapter.itemCount} state=$pagerScrollState")
            if (animationMode == NovelPanelStyle.ANIM_NONE) animState.anchorPage = position
            // 跳页（setCurrentItem(_, false)，无动画模式与点击翻页都走它）不会产生滚动，
            // 于是**不会有任何一次 transformPage 去把新页从"动画中途态"复位**。
            // 必须在落定时自己归一化，否则页面停在 alpha=0/错位：进度条在走、画面不变。
            if (pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) normalizePageTransforms()
            onPaged(position)
            persistProgress()
        }

        override fun onPageScrollStateChanged(state: Int) {
            pagerScrollState = state
            // 用户开始拖动 = 真的在导航 → 松掉带位锚点（之后的位置按他停下的那一页算）
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) carryAnchor = null
            // 滚动/吸附结束：把三个 transformer 留在页面上的 alpha/位移/折叠全部复位。
            // 漫画是靠 onPageSelected → notifyItemChanged → onBindViewHolder 里的
            // resetItemTransform 达到同样效果的；这里直接复位，省一次重绑。
            if (state == ViewPager2.SCROLL_STATE_IDLE) normalizePageTransforms()
        }
    }

    /**
     * 视口尺寸变化后重新对齐。
     *
     * ⚠️ 对文字阅读器这比漫画更要紧：页表是**按视口像素尺寸算出来的**。首次 `loadChapter`
     * 跑在 `onCreate` 里，那时 View 还没测量（宽度 0）→ 只能退回 `displayMetrics`，而它可能
     * 含状态栏那几十像素，结果就是**每页最后一行被裁掉**。所以尺寸一定下来必须**按真实尺寸
     * 重新分页**，而不是只把 pager 吸附回去。
     */
    private fun realignPagerAfterResize() {
        val w = binding.root.width
        val h = binding.root.height
        NovelDebug.log("realign root=${w}x$h paged=${pagedWidth}x$pagedHeight mode=${NovelPanelStyle.readerMode(prefs)}")
        if (w <= 0 || h <= 0) return
        if (w != pagedWidth || h != pagedHeight) {
            scheduleRepaginate()
            return
        }
        if (isScrollMode()) return
        // 尺寸没变（多半是子 View 引起的无关布局回调）：只把动画重新贴一遍。
        // ⚠️ 这里**不要**再 scrollToPosition 硬吸一次 —— 那会在用户正滑到一半时把他拽回去
        applyAnimation()
    }

    private fun chromeViews(): List<View> =
        listOf(binding.btnBack, binding.btnMenu, binding.tvPageIndicator, binding.bottomProgress)

    private fun applyChromeVisibility() {
        if (!chromeHidden) refreshTranslationChrome()
        val group = binding.translateGroup
        val groupShown = group.visibility == View.VISIBLE
        for (v in chromeViews() + (if (groupShown) listOf(group) else emptyList())) {
            if (chromeHidden) fadeOutChrome(v) else fadeInChrome(v)
        }
    }

    /** 淡出并置 GONE —— 只打 alpha 不加 GONE 的话控件仍然可点（会误触到看不见的按钮）。 */
    private fun fadeOutChrome(v: View) {
        setChromeInteractive(v, false)
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(CHROME_FADE_MS)
            .withEndAction { if (chromeHidden && v.alpha == 0f) v.visibility = View.GONE }
            .start()
    }

    private fun fadeInChrome(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.visibility = View.VISIBLE
        setChromeInteractive(v, true)
        v.animate().alpha(1f).setDuration(CHROME_FADE_MS).start()
    }

    /** 递归开关整棵子树的 `isEnabled`（禁用而不是改 clickable，理由见漫画同名函数）。 */
    private fun setChromeInteractive(v: View, interactive: Boolean) {
        v.isEnabled = interactive
        if (v is ViewGroup) for (i in 0 until v.childCount) setChromeInteractive(v.getChildAt(i), interactive)
    }

    private fun toggleChrome() {
        chromeHidden = !chromeHidden
        lastInteractionMs = SystemClock.elapsedRealtime()
        applyChromeVisibility()
    }

    // ===== 点击分区（与漫画同一套九宫格） =====

    private fun handleTap(x: Float, y: Float) {
        val target: View = if (isScrollMode()) binding.novelScroll else binding.novelPager
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 选择模式：点段落 = 加/减选，点空白 = 退出。九宫格（翻页 / 显隐 chrome）**让位** ——
        // 选段过程中误翻页是最烦的一种；chrome 上的按钮是独立视图，照常可点。
        if (selecting) {
            val para = paragraphAt(x, y)
            when {
                para == null -> exitSelection()
                !isTranslatablePara(para) ->
                    showOverlayToast(getString(R.string.novel_select_not_translatable), error = false)
                else -> {
                    if (!selectedPara.add(para)) selectedPara.remove(para)
                    // 取消到一段不剩 = 已经没有选择了，直接退出选择模式（不留一个空的选择态）
                    if (selectedPara.isEmpty()) selecting = false
                    refreshSelectionUi()
                }
            }
            return
        }
        if (consumeChromeOrMenuTap(x, y, w, h)) return
        when {
            // 连续滚动：点上下半屏 = 上一段 / 下一段
            isScrollMode() -> scrollByParagraph(if (y >= h / 2f) 1 else -1)
            // 上下翻页：点下半屏 = 下一页（与漫画竖排模式同一套判定）
            isVerticalMode() -> turnPage(if (y >= h / 2f) 1 else -1)
            // 左右翻页：点右半屏 = 下一页
            else -> turnPage(if (x >= w / 2f) 1 else -1)
        }
    }

    /** 右上角 22%×28% → 菜单；正中间一格 → 显隐上下 UI。返回 true 表示已消费。 */
    private fun consumeChromeOrMenuTap(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (y < h * 0.22f && x > w * 0.72f) {
            showMenu()
            return true
        }
        if (x >= w / 3f && x < w * 2f / 3f && y >= h / 3f && y < h * 2f / 3f) {
            toggleChrome()
            return true
        }
        return false
    }

    // ===== 选择模式（长按多选 → 翻译 / 重翻） =====

    /**
     * 长按正文 = 进入选择模式并选中手指下那一段。
     *
     * ⚠️ 文本翻译的重翻**只有这一个入口**（用户明确要求）：不要学漫画用「缓存命中 → 按钮变重翻」，
     * 那样"点一下会发生什么"取决于看不见的译文状态。
     */
    private fun handleLongPress(x: Float, y: Float) {
        lastInteractionMs = SystemClock.elapsedRealtime()
        val para = paragraphAt(x, y)
        if (para == null) return
        if (!isTranslatablePara(para)) {
            showOverlayToast(getString(R.string.novel_select_not_translatable), error = false)
            return
        }
        if (!selecting) {
            selecting = true
            selectedPara.clear()
            showOverlayToast(getString(R.string.novel_select_hint), error = false)
        }
        if (!selectedPara.add(para)) selectedPara.remove(para)
        if (selectedPara.isEmpty()) selecting = false
        refreshSelectionUi()
    }

    /** 手指下的段号（翻页模式看页内行区间，滚动模式看 item）。 */
    private fun paragraphAt(x: Float, y: Float): Int? {
        if (isScrollMode()) {
            val rv = binding.novelScroll
            val child = rv.findChildViewUnder(x, y) ?: return null
            return scrollAdapter.paraIndexAt(rv.getChildAdapterPosition(child))
        }
        val rv = binding.novelPager.getChildAt(0) as? RecyclerView ?: return null
        val child = rv.findChildViewUnder(x, y) ?: return null
        val vh = rv.findContainingViewHolder(child) as? NovelPageAdapter.VH ?: return null
        // 页 View 铺满 item，所以只需要把 y 转成页内坐标
        return vh.page.paraIndexAt(y - child.top)
    }

    private fun isTranslatablePara(paraIndex: Int): Boolean =
        content?.paragraphs?.firstOrNull { it.index == paraIndex }?.isTranslatable() == true

    /**
     * 退出选择模式并清空选择。
     *
     * 会退出的场合：点空白处、返回键、切章、开面板、切阅读模式、离开阅读器。
     * ⚠️ **翻页/换页不退**：选完几段要翻到别页再看看是正常操作，而且译文到达后
     * `refreshTranslations → loadChapter` 也会走一遍翻页路径，在那里清会让选择凭空消失。
     */
    private fun exitSelection() {
        if (!selecting && selectedPara.isEmpty()) return
        selecting = false
        selectedPara.clear()
        refreshSelectionUi()
    }

    /** 选择态变化后刷新正文高亮与右下浮层。 */
    private fun refreshSelectionUi() {
        val sel = selectedPara.toSet()
        pageAdapter.setSelected(sel)
        scrollAdapter.setSelected(sel)
        refreshTranslationChrome()
    }

    /** 「正在翻译 / 刚翻完」的高亮刷新（两种阅读模式各自的视图都要落到）。 */
    private fun refreshActiveHighlight() {
        pageAdapter.setActiveBatch(activeBatch)
        scrollAdapter.setActiveBatch(activeBatch)
    }

    // ===== 翻页 / 翻段 / 切章 =====

    private fun isScrollMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_SCROLL

    private fun isVerticalMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_VERTICAL

    private fun currentPage(): Int =
        if (isScrollMode()) firstVisibleScrollItem() else binding.novelPager.currentItem

    /** 翻页；越界则切章（本章末页往后 → 下一章首页，反之上一章末页）。 */
    private fun turnPage(delta: Int) {
        // 用户点按翻页 = 真的在导航 → 松掉带位锚点
        carryAnchor = null
        val pages = content?.pages ?: return
        if (pages.isEmpty()) {
            gotoChapter(chapterIndex + if (delta > 0) 1 else -1)
            return
        }
        val target = currentPage() + delta
        when {
            target in pages.indices -> binding.novelPager.setCurrentItem(target, animationMode != NovelPanelStyle.ANIM_NONE)
            delta > 0 -> gotoChapter(chapterIndex + 1)
            else -> gotoChapter(chapterIndex - 1, atLastPage = true)
        }
    }

    private fun scrollByParagraph(delta: Int) {
        val lm = binding.novelScroll.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val total = scrollAdapter.itemCount
        val target = first + delta
        if (target in 0 until total) {
            smoothScrollTo(target)
        } else {
            gotoChapter(chapterIndex + if (delta > 0) 1 else -1, atLastPage = delta < 0)
        }
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    private fun smoothScrollTo(position: Int) {
        val rv = binding.novelScroll
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val targetView = lm.findViewByPosition(position)
        if (targetView != null && targetView.top != 0) {
            rv.smoothScrollBy(0, targetView.top)
            return
        }
        val scroller = object : LinearSmoothScroller(this) {}
        scroller.targetPosition = position
        lm.startSmoothScroll(scroller)
    }

    /**
     * 跳到章内第 [page] 页（进度条拖拽/点击）。
     *
     * ⚠️ 滚动模式也要实现：那条进度条在滚动模式下是**显示**的，
     * 拖了没反应就是「控件在、功能不在」。滚动模式的 [page] 是 [NovelScrollProgress] 的格。
     */
    private fun goToPage(page: Int) {
        val pages = content?.pages ?: return
        if (isScrollMode()) {
            seekScrollTo(NovelScrollProgress.fractionOfStep(page))
            return
        }
        // 用户拖进度条 = 真的在导航 → 松掉带位锚点
        carryAnchor = null
        val p = page.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        binding.novelPager.setCurrentItem(p, false)
        onPaged(p)
    }

    /**
     * 滚动模式按**像素比例**跳转。
     *
     * ⚠️ 不能只按段号 `scrollToPositionWithOffset`：段高差几十倍（超长章节甚至整章一段），
     * 按段号跳会落到离目标很远的地方，用户看到的就是"拖了没反应"。这里按像素目标滚。
     *
     * ⚠️ 而且要**循环补齐**：`ScrollbarHelper` 的总长是「已测量项的平均高度 × 段数」，
     * 一次 `scrollBy` 未必吃到目标（新滚过的段落才刚被测量，估算值当场就变了）
     * ——不补齐就会停在半路，用户看到的同样是"进度条失效"。循环有次数上限，不会卡死。
     */
    private fun seekScrollTo(fraction: Float) {
        val rv = binding.novelScroll
        val target = fraction.coerceIn(0f, 1f)
        var passes = 0
        fun step() {
            val span = (rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent())
                .coerceAtLeast(1)
            val before = rv.computeVerticalScrollOffset()
            val dy = (target * span).roundToInt() - before
            if (dy == 0 || passes >= MAX_SEEK_PASSES) return
            passes++
            rv.scrollBy(0, dy)
            // ⚠️ 必须 post：scrollBy 之后要等布局跑完才有新的 offset/range。
            // 位置没动（内容不足一屏 / 已到边界）就不再补，免得空转几十次。
            rv.post { if (rv.computeVerticalScrollOffset() != before) step() }
        }
        step()
    }

    private fun gotoChapter(index: Int, atLastPage: Boolean = false) {
        NovelDebug.log("gotoChapter index=$index chapterCount=$chapterCount")
        if (chapterCount <= 0) return
        if (index < 0) {
            toast(R.string.novel_first_chapter)
            return
        }
        if (index >= chapterCount) {
            toast(R.string.novel_last_chapter)
            return
        }
        // 选择集是按**段号**记的，换章后段号会撞上别的段 —— 必须先清
        exitSelection()
        // 换章：位置由目标章自己决定，不许带上上一章的带位锚点；高亮同理
        carryAnchor = null
        activeBatch = emptySet()
        refreshActiveHighlight()
        persistProgress()
        loadChapter(index, atLastPage = atLastPage)
    }

    /**
     * 尺寸变了要重排，但**必须防抖**。
     *
     * 重排 = 读文件 + 对整章跑 `StaticLayout` + 重建全部页，很重。而尺寸变化是**连续事件**：
     * 旋转、分屏、MIUI 小窗缩放都会在一秒内派发几十次布局回调 —— 每次都重排会把主线程压死，
     * 现场表现就是「整个画面卡住不刷新」。这里只在尺寸稳定下来之后排一次。
     */
    private fun scheduleRepaginate() {
        repaginateJob?.cancel()
        repaginateJob = lifecycleScope.launch {
            delay(REPAGINATE_DEBOUNCE_MS)
            loadChapter(chapterIndex, anchor = pendingAnchor)
        }
    }

    // ===== 模式 / 背景 / 动画 =====

    private fun applyReaderMode() {
        val mode = NovelPanelStyle.readerMode(prefs)
        val scroll = mode == NovelPanelStyle.READER_SCROLL
        // 阅读模式换了 → 选中的段在两套视图上表达方式也不同，直接清掉，别留个看不见的选择
        exitSelection()
        // 模式换了，页/段坐标系都不一样了：带位锚点作废
        carryAnchor = null
        // 高亮换成另一套视图（新建/复用的 item 都靠它落到正确底色）
        refreshActiveHighlight()
        binding.novelPager.visibility = if (scroll) View.GONE else View.VISIBLE
        binding.novelScroll.visibility = if (scroll) View.VISIBLE else View.GONE
        // 进度条两种模式都用：翻页模式是「章内页进度」，连续滚动是「章内段进度」
        binding.novelProgress.visibility = View.VISIBLE
        if (scroll) {
            binding.novelScroll.itemAnimator = null
        } else {
            // 上下翻页 = 竖向 ViewPager2，与漫画的竖排模式同一套（预绑定邻页、关 item 动画都在下面）
            binding.novelPager.orientation =
                if (mode == NovelPanelStyle.READER_VERTICAL) ViewPager2.ORIENTATION_VERTICAL
                else ViewPager2.ORIENTATION_HORIZONTAL
            binding.novelPager.offscreenPageLimit = 1
            applyDirection()
            applyAnimation()
        }
    }

    private fun applyDirection() {
        // 中文/西文都是 LTR。⚠️ 与漫画一样必须同时设到内部 RecyclerView 上，
        // 否则 RTL 的槽位计算与页面自身布局方向会打架
        binding.novelPager.layoutDirection = View.LAYOUT_DIRECTION_LTR
        (binding.novelPager.getChildAt(0) as? RecyclerView)?.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    /**
     * 翻页动画：0 无 / 1 滑动（ViewPager2 默认）/ 2 覆盖 / 3 仿真（折页）。
     * **四项直接复用漫画那套 transformer**（`NoneTransformer` / `CoverTransformer` /
     * `SimulationTransformer`），文字页与位图页用的是同一份动画代码。
     */
    private fun applyAnimation() {
        if (isScrollMode()) return
        val vertical = isVerticalMode()
        // 先清掉旧 transformer 残留的 alpha/缩放/位移/折叠，否则切动画时旧效果粘在页面上
        resetPageTransforms()
        animState.anchorPage = currentPage()
        animState.navigationProgress = 0f
        animState.isBackward = false
        animState.foldStartFraction = 0.85f
        binding.novelPager.setPageTransformer(
            when (animationMode) {
                NovelPanelStyle.ANIM_NONE -> NoneTransformer(isVertical = vertical, animState)
                NovelPanelStyle.ANIM_COVER -> CoverTransformer(isVertical = vertical, isReversed = false, animState)
                NovelPanelStyle.ANIM_SIMULATION -> SimulationTransformer(isVertical = vertical, isReversed = false, animState)
                else -> null
            }
        )
    }

    private fun resetPageTransforms() {
        val rv = binding.novelPager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val page = rv.getChildAt(i)
            page.alpha = 1f
            page.translationX = 0f
            page.translationY = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            page.rotationX = 0f
            page.rotationY = 0f
            page.rotation = 0f
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            page.translationZ = 0f
            (page as? CurlPageView)?.clearFold()
        }
    }

    /**
     * 把当前挂在 pager 上的所有页归一化到「落定态」。
     *
     * ⚠️ 三个 transformer（无/覆盖/仿真）都会在**转场中途**给页面写 alpha/位移/折叠，
     * 而 ViewPager2 **不保证**转场结束时再调一次 `transformPage` 把它们复位：
     * - 无动画：非锚点页被写成 `alpha = 0`，且锚点页被 `pinToViewCenter` 加了位移
     * - 跳页（`setCurrentItem(_, false)`）：压根不产生滚动，一次 `transformPage` 都没有
     *
     * 不复位的后果就是「**进度条在走 / 页码在变，画面却停在上一页**」——
     * 因为被翻译回屏幕中央的旧页 alpha 还是 1，而新页 alpha 还是 0。
     */
    private fun normalizePageTransforms() {
        if (isScrollMode()) return
        resetPageTransforms()
    }

    private fun applyBackground() {
        bgMode = NovelPanelStyle.background(prefs)
        val bg = NovelPanelStyle.backgroundColor(bgMode)
        binding.root.setBackgroundColor(bg)
        binding.novelPager.setBackgroundColor(bg)
        binding.novelScroll.setBackgroundColor(bg)
        binding.tvPageIndicator.setTextColor(
            if (NovelPanelStyle.isDarkBackground(bgMode)) Color.WHITE else Color.BLACK
        )
        binding.novelStatus.setTextColor(
            if (NovelPanelStyle.isDarkBackground(bgMode)) 0xFFECECEC.toInt() else 0xFF222222.toInt()
        )
        // 文字色与**每页的底色**一起下发：底色必须画在页自己身上，
        // 否则「覆盖」动画里页面透明 → 看起来只有文字在动、背景不动
        val textColor = NovelPanelStyle.textColor(bgMode)
        pageAdapter.setColors(textColor, bg)
        scrollAdapter.setColors(textColor, bg)
    }

    // ===== 加载 =====

    /**
     * 加载并渲染一章。
     *
     * ⚠️ **并发加载必须按序号丢弃过期结果**（[loadToken]）：这个函数会被很多条路径调到 ——
     * onCreate、每次窗口尺寸变化（[realignPagerAfterResize]）、译文到达（[refreshTranslations]）、
     * 改排版/改显示模式。每次都要读文件 + 对整章跑 `StaticLayout` 分页，**几百毫秒很正常**。
     * 不做序号保护的话，早先发出的那次加载会在用户已经翻页/换章之后再落地，把
     * `chapterIndex`、`content` 和 `setCurrentItem` 一起**拽回旧章旧页**：
     * 表现就是「翻页卡在第一页、点目录切章也没用、整个画面像卡死不会刷新」。踩过。
     *
     * @param anchor 要定位到的阅读锚点（段号 + 段内比例，见 [NovelAnchor]）；
     *   译文到达 / 改排版都会重排，重排后**必须回到同一个位置**，否则每翻一批就跳一次
     * @param atLastPage 本章末尾（切上一章时停在末页）
     */
    private fun loadChapter(index: Int, anchor: NovelAnchor = NovelAnchor(0), atLastPage: Boolean = false) {
        val b = book ?: return
        val token = ++loadToken
        lifecycleScope.launch {
            val style = NovelPanelStyle.textStyle(this@NovelReaderActivity, prefs)
            val w = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            NovelDebug.log("loadChapter#$token start ch=$index anchor=$anchor atLast=$atLastPage size=${w}x$h")

            // ⚠️ **本章译文必须在这里按章取**，不能沿用上一次的 `translations`：
            // 那个表是「paraIndex -> 译文」，换章后 paraIndex 会撞上，于是新章每一段都显示
            // **上一章同一段号的译文** —— 用户看到的就是「切了章内容却一点没变」。
            // 而且分页是在「显示文本」上做的，译文换了就必须重排。
            val map = runCatching { translator().loadTranslations(b, index) }
                .getOrDefault(translations)
            if (token != loadToken) {
                NovelDebug.log("loadChapter#$token DISCARDED after translations (latest=$loadToken)")
                return@launch
            }
            translations = map

            // ⚠️ 分页模式把锚点交给分页器：**锚点必须落在页首**。不给这一步的话，`pageOf`
            // 只能给到"含锚点的那一页"，锚点可能落在页尾 —— 译文比原文短时它会被挤到上一页，
            // 读者看到的就是「翻译完回到前一页」（用户报的）。滚动模式不用页，不传。
            val pageAnchor = if (isScrollMode() || atLastPage) null else anchor
            val loaded = repository.load(
                b, index, translations, NovelPanelStyle.displayMode(prefs), style, w, h, pageAnchor,
            )
            if (token != loadToken) {
                NovelDebug.log("loadChapter#$token DISCARDED (latest=$loadToken)")
                return@launch
            }
            pagedWidth = w
            pagedHeight = h
            chapterIndex = index
            content = loaded
            scrollTranslated = computeScrollTranslated(loaded)
            showStatus(if (loaded.isEmpty) getString(R.string.novel_empty_chapter) else null)
            NovelDebug.log(
                "loadChapter#$token done ch=$index paras=${loaded.paragraphs.size} pages=${loaded.pages.size} " +
                    "texts=${loaded.displayTexts.size} " +
                    "p0=${loaded.pages.firstOrNull()?.segments?.firstOrNull()?.let { s -> NovelDebug.brief(loaded.displayOf(s.paraIndex)) }}"
            )

            val textColor = NovelPanelStyle.textColor(bgMode)
            val bgColor = NovelPanelStyle.backgroundColor(bgMode)
            if (isScrollMode()) {
                // 上下间距落在**列表**上而不是每一段上：落在每段上会变成段间距。
                // clipToPadding=false → 正文可以滚到浮层底下再滑走，而不是被硬切一刀
                binding.novelScroll.setPadding(0, style.topPaddingPx.toInt(), 0, style.bottomPaddingPx.toInt())
                scrollAdapter.submit(loaded, style, textColor, bgColor)
                // 换章/重排后一定要让 layout listener 再补刷一次进度条（防死循环的闸门要复位）
                lastLayoutScrollItem = -1
                val pos = if (atLastPage) {
                    (scrollAdapter.itemCount - 1).coerceAtLeast(0)
                } else {
                    NovelScrollMapping.positionOf(loaded, anchor.paraIndex)
                }
                binding.novelScroll.scrollToPosition(pos)
                // ⚠️ 段内比例要等这一段**量出高度**才换得出像素，所以 post 到布局之后再补一次滚动。
                // 不做这一步：长段（整章一段的样本）每次重排都被吸回段首 —— 就是「位置跳变」。
                if (anchor.fraction > 0f && !atLastPage) {
                    binding.novelScroll.post {
                        if (token != loadToken) return@post
                        val v = (binding.novelScroll.layoutManager as? LinearLayoutManager)
                            ?.findViewByPosition(pos)
                        if (v != null && v.height > 0) {
                            binding.novelScroll.scrollBy(0, (v.height * anchor.fraction).roundToInt())
                        }
                    }
                }
            } else {
                pageAdapter.submit(loaded, style, textColor, bgColor)
                val page = if (atLastPage) {
                    loaded.pages.lastIndex.coerceAtLeast(0)
                } else {
                    // ⚠️ 用 anchor 版而不是 `pageOfParagraph`：后者给的是「含该段的**第一页**」，
                    // 段跨页时那是段首所在的页，会把读者从段中间拽回段首
                    NovelAnchors.pageOf(loaded.pages, loaded.displayTexts, anchor)
                }
                binding.novelPager.setCurrentItem(page, false)
                // 带位重排：把逻辑锚点钉住（见 [carryAnchor]），落位后 onPaged 不许重取
                carryAnchor = anchor
                onPaged(page)
                // ⚠️ 布局稳定后**再校一次**：页数缩水到当前页号以下时 ViewPager2 会把位置
                // 重置到第 0 页（实测），紧随的 setCurrentItem 通常能兜住，这里再兜一层。
                binding.novelPager.post { if (token == loadToken) snapPagerToAnchor(anchor) }
                NovelDebug.log("loadChapter submit pages=${pageAdapter.itemCount} gotoPage=$page anchor=$anchor")
            }
            refreshOverlay()
            refreshTranslationChrome()
            updateChapterTocLabel()
            persistProgress()
            restartQueueIfNeeded()
        }
    }

    /**
     * 模型 / 引擎配置变了 → **丢掉缓存的队列（连同它捕获的那份引擎）**，下次用到时按新配置重建。
     *
     * ⚠️ 这里只丢、不无条件重启：这条回调多半发生在阅读器**已经 `onStop`** 的时候
     * （用户正在设置页里改模型），就地重启会让翻译在后台跑起来 —— 与
     * 「退出阅读器自动暂停」的约定冲突。回到前台由 `onStart → restartQueueIfNeeded()` 接上。
     *
     * ⚠️ 别改成「每批现造引擎」：本地引擎（NLLB）造一次要重载模型，
     * 而模型只在用户切换时才变 —— 按变化失效比每批重建便宜得多。
     */
    private fun onEngineConfigChanged() {
        NovelDebug.log("引擎配置变化 → 丢弃缓存的翻译队列")
        queue?.stop()
        queue = null
        queueRunning = false
        queueChapter = -1
        queueMode = null
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) restartQueueIfNeeded()
    }

    /**
     * 按当前模式启动（或重启）自动翻译队列。
     *
     * ⚠️ 每次**换章**都调：模式可能刚从手动切成自动，窗口也随当前章变化。
     * 队列内部会先 cancel 旧 job，重复调用是安全的。
     */
    private fun restartQueueIfNeeded(force: Boolean = false) {
        val b = book ?: return
        val mode = NovelPanelStyle.translateMode(prefs)
        // ⚠️ 手动模式**也要把队列建出来**：手动「翻一批」走的是队列的 `translateOneBatch`，
        // 与自动/增量共用同一套锚点与成批规则（两条路各写一套迟早不一致）
        val t = runCatching { translator() }.getOrNull()
        if (t == null) {
            if (mode != NovelTranslateMode.MANUAL) toast(R.string.novel_translate_need_config)
            return
        }
        // ⚠️ **先把队列建出来，再决定跑不跑**：手动「翻一批」走的是队列的
        // `translateOneBatch` —— 早先手动分支直接 `return`，于是队列永远是 null，
        // 「翻译本章」点了**一点反应都没有**（用户报的现象）。
        val q = ensureQueue(t)
        if (mode == NovelTranslateMode.MANUAL) {
            q.stop()
            queueRunning = false
            queueMode = NovelTranslateMode.MANUAL
            return
        }
        // 同一章的重复调用（译文到达 → 重排 → 又调一次）直接跳过：白重启一次队列会把
        // 防抖计时清零，正文越翻越慢
        if (!force && queueRunning && queueChapter == chapterIndex && queueMode == mode) return

        queueChapter = chapterIndex
        queueMode = mode
        q.start(
            book = b,
            mode = mode,
            quota = NovelQuota.of(NovelPanelStyle.aheadBatches(prefs)),
            // 每轮重新求值：切章不重启队列，锚点自动跟上
            currentChapter = { chapterIndex },
            onBatchSettled = { ch, result ->
                // 无论成败都要刷新：失败也要让面板的「失败」状态与原因立刻出来
                refreshChapterStats()
                if (!result.isEmpty && ch == chapterIndex) {
                    refreshTranslations()
                }
                if (result.isEmpty) {
                    showOverlayToast(
                        getString(
                            R.string.novel_translate_failed_reason,
                            result.error ?: getString(R.string.reader_translate_failed),
                        ),
                        error = true,
                    )
                }
            },
        )
        queueRunning = true
    }

    /**
     * 当前页显示的段落号（按顺序）——翻译的**锚点**就是这一页里第一段没翻的段。
     *
     * 滚动模式没有「页」：用当前可见的首段往后一段，保持「以用户现在看到的内容为锚」。
     */
    private fun currentPageParaIndexes(): List<Int> {
        if (isScrollMode()) return visibleScrollParaIndexes()
        val page = content?.pages?.getOrNull(currentPage()) ?: return emptyList()
        return page.segments.map { it.paraIndex }.distinct()
    }

    /** 滚动模式：当前可见的那几段（锚点用）。列表拿不到时退回首段。 */
    private fun visibleScrollParaIndexes(): List<Int> {
        val c = content ?: return emptyList()
        val visible = NovelScrollMapping.visibleParagraphs(c)
        if (visible.isEmpty()) return emptyList()
        val lm = binding.novelScroll.layoutManager as? LinearLayoutManager
            ?: return listOf(visible.first().index)
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtMost(visible.size - 1)
        if (last < first) return listOf(visible[first.coerceAtMost(visible.size - 1)].index)
        return (first..last).map { visible[it].index }
    }

    /** 惰性创建队列 + 挂观察（观察必须挂在这里，挂在 onCreate 会对着 null 收流）。 */
    private fun ensureQueue(t: NovelBatchTranslator): NovelTranslationQueue =
        queue ?: NovelTranslationQueue(
            scope = lifecycleScope,
            translator = t,
            paragraphsOf = { book2, ch -> repository.paragraphsOf(book2, ch) },
            sourceLang = { sourceLang() },
            targetLang = { targetLang() },
            translatorName = { "novel" },
            batchSize = { NovelPanelStyle.batchSize(prefs) },
            debounceMs = { NovelPanelStyle.debounceMs(prefs) },
            currentPageParaIndexes = { currentPageParaIndexes() },
            chapterParaIndexes = { book2, ch -> repository.paragraphsOf(book2, ch).map { it.index } },
            translatedIndexes = { book2, ch -> translations.keys.toSet() },
        ).also {
            queue = it
            observeQueue(it)
        }

    private fun sourceLang(): String = CustomPreference.getInstance(this).getString("Source_Language", "auto")

    private fun targetLang(): String = CustomPreference.getInstance(this).getString("Target_Language", "zh")

    private fun onPaged(position: Int) {
        val c = content ?: return
        // ⚠️ 带位重排（译文到达/改排版）期间**不许**把锚点重取成页首：页首比逻辑位置靠前，
        // 每批退一点、逐批累积，用户看到的就是"位置一直往回跑"（见 [carryAnchor]）。
        // 用户自己翻开这一页时才重取（那时 carryAnchor 已经被清掉）。
        pendingAnchor = carryAnchor ?: NovelAnchors.ofPage(c.pages, c.displayTexts, position)
        refreshOverlay()
        // ⚠️ **翻页必须重算浮层**：判据是「当前页有没有译文」，翻到别的页当然会变。
        // 早先这里只刷进度条，于是翻译/三态按钮停在上一次 `loadChapter` 时的状态（用户报的
        // 「三态这块问题相当多」有这一半）。
        refreshTranslationChrome()
    }

    /** 滚动模式下把当前可见段同步到「待续读段落」。 */
    private fun updateFromScroll() {
        if (!isScrollMode()) return
        val lm = binding.novelScroll.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val c = content ?: return
        val para = NovelScrollMapping.paraIndexOf(c, first) ?: return
        // ⚠️ 锚点必须带**段内比例**（首可见项已经滚过多少）：段可以长到跨屏，
        // 只记段号的话重排后会回到段顶 —— 长段/整章一段的样本里就是"跳回章节开头"
        val v = lm.findViewByPosition(first)
        val scrolledPx = if (v != null) -v.top else 0
        val anchor = NovelAnchors.ofScroll(para, scrolledPx, v?.height ?: 0)
        val moved = anchor.paraIndex != pendingAnchor.paraIndex
        pendingAnchor = anchor
        refreshOverlay()
        // 三态按钮的判据是「当前屏幕有没有译文」—— 滚到有/没译文的区域必须跟着变。
        // ⚠️ 只在**首可见段真的变了**时算：判据要遍历可见段（滚动回调是每帧一次的）
        if (moved) refreshTranslationChrome()
    }

    /**
     * 译文到达后：重新分页（译文比原文长）+ **回到同一个阅读位置**。
     *
     * ⚠️ 取锚点必须在**读译文之后**（而不是调用时捕获）：中间隔着一次 IO，几百毫秒里
     * 用户/自动翻页都可能已经落位，用旧锚点会把读者**拽回上一处**（跳变的第二种机制）。
     * ⚠️ 位置本身是 `(段号, 段内比例)` 而不是段号：段可以跨好几页，只按段号会落回段首
     * （见 [NovelAnchor]）。
     */
    private fun refreshTranslations() {
        val b = book ?: return
        val ch = chapterIndex
        lifecycleScope.launch {
            val map = runCatching { translator().loadTranslations(b, ch) }
                .getOrDefault(emptyMap())
            // 读译文期间用户可能已经换章：那次结果作废（新章进来时 loadChapter 自己会再取一次），
            // 否则会把上一章的译文贴到新章上
            if (ch != chapterIndex) return@launch
            // ⚠️ `content == null` 时必须照样往下走：首次进入本章一句译文都没有时
            // `map == translations`（都是空表）成立，早退就会让正文永远不加载。
            if (map == translations && content != null) {
                refreshTranslationChrome()
                return@launch
            }
            translations = map
            loadChapter(chapterIndex, anchor = pendingAnchor)
        }
    }

    private fun refreshChapterStats() {
        val b = book ?: return
        lifecycleScope.launch {
            chapterStats = runCatching { translator().chapterStats(b) }.getOrDefault(emptyMap())
            chapterFailures = runCatching { translator().failuresOf(b) }.getOrDefault(emptyMap())
            // 这一句里已经把章级状态推给面板了（见 updateChapterTocLabel）
            updateChapterTocLabel()
        }
    }

    /** 每章总段数（章行分母）缓存 + 在途去重：同一个章只解析一次。 */
    private val chapterTotals = mutableMapOf<Int, Int>()
    private val pendingTotalFetches = mutableSetOf<Int>()

    /**
     * 章行要某章的段数：解析一次并回推（懒解析：章行一次只显示几行）。
     *
     * ⚠️ 未翻译的章在数据库里没有统计行，分母只能从**正文**解析 —— 这就是
     * 「只有翻过的章显示段数」的修法。
     */
    private fun ensureChapterTotal(index: Int) {
        val b = book ?: return
        if (chapterTotals.containsKey(index) || !pendingTotalFetches.add(index)) return
        lifecycleScope.launch {
            val n = runCatching { repository.paragraphCountOf(b, index) }.getOrDefault(0)
            pendingTotalFetches.remove(index)
            if (n <= 0) return@launch
            chapterTotals[index] = n
            pushPanelState()
        }
    }

    /**
     * 宿主状态的**唯一构造出口**（面板就是它的纯函数渲染，见 [NovelPanelHostState]）。
     *
     * ⚠️ 新增任何「宿主能改、面板要显示」的状态，都加到这里 —— 漏了就是那一处 UI 不同步。
     */
    private fun hostStateToPanel() = NovelPanelHostState(
        chapterIndex = chapterIndex,
        chapterCount = chapterCount,
        chapterStats = chapterStats,
        chapterTotals = chapterTotals.toMap(),
        chapterFailures = chapterFailures,
        translateMode = NovelPanelStyle.translateMode(prefs),
        translating = queueRunning,
        readerMode = NovelPanelStyle.readerMode(prefs),
        animation = animationMode,
        background = bgMode,
        autoTurn = autoTurnEnabled,
        intervalSec = autoTurnIntervalSec,
        debounceMs = NovelPanelStyle.debounceMs(prefs),
        aheadBatches = NovelPanelStyle.aheadBatches(prefs),
        batchSize = NovelPanelStyle.batchSize(prefs),
        rotateLabel = rotateLabel(),
        keepParagraphsWhole = NovelPanelStyle.keepParagraphsWhole(prefs),
        isDarkPanel = NovelPanelStyle.isDarkBackground(bgMode),
    )

    /**
     * 把**整份宿主状态**推给正在显示的面板（没开就什么都不做）。
     *
     * 挂在 [updateChapterTocLabel] 与队列状态流上：换章、翻页、译文到达、重算统计、
     * 翻译跑/停都会走到，所以面板不会停在打开那一刻。
     */
    private fun pushPanelState() {
        (supportFragmentManager.findFragmentByTag(NovelPanelSheet.TAG) as? NovelPanelSheet)
            ?.renderHostState(hostStateToPanel())
    }

    private fun chapterTitle(index: Int): String = chapterDisplayTitle(
        this, index, content?.takeIf { it.chapterIndex == index }?.title,
    )

    /** 章内页码/滚动进度相关的显示 + 推给面板。 */
    private fun updateChapterTocLabel() {
        // 顶部胶囊：章名 + 章内进度。章是小说最主要的定位单位，必须常显
        val label = chapterTitle(chapterIndex)
        val text = if (isScrollMode()) {
            val total = scrollAdapter.itemCount
            getString(R.string.novel_page_indicator, label, (firstVisibleScrollItem() + 1).coerceAtMost(total), total)
        } else {
            val pages = content?.pages
            if (pages.isNullOrEmpty()) getString(R.string.novel_page_indicator, label, 0, 0)
            else getString(R.string.novel_page_indicator, label, currentPage() + 1, pages.size)
        }
        // ⚠️ 文案没变就别 setText：TextView 会无条件 requestLayout，
        // 而滚动回调是每帧一次的（还会牵动 [setupOverlays] 里的 layout listener）
        if (binding.tvPageIndicator.text.toString() != text) binding.tvPageIndicator.text = text
        pushPanelState()
    }

    private fun refreshOverlay() {
        if (isScrollMode()) {
            // 滚动模式的位置**必须取像素进度，不能取段序号**：一个几万字的章节可能整章只有一段
            // （`NovelParagraphSplitter` 只按空行分段），"第几段/共几段"恒为 0/1 —— 进度条永远不动，
            // 这就是用户报的「章节文字太多时底部的进度条失效」。取像素后滚到一半就是一半。
            // 见 `NovelScrollProgress`。
            val rv = binding.novelScroll
            binding.novelProgress.setPage(
                NovelScrollProgress.stepOf(
                    offset = rv.computeVerticalScrollOffset(),
                    range = rv.computeVerticalScrollRange(),
                    extent = rv.computeVerticalScrollExtent(),
                ),
                NovelScrollProgress.STEPS,
            )
            binding.novelProgress.setTranslatedPages(scrollTranslated)
        } else {
            val pages = content?.pages ?: return
            binding.novelProgress.setPage(currentPage(), pages.size)
            binding.novelProgress.setTranslatedPages(translatedPagesOf(pages, translations.keys))
        }
        updateChapterTocLabel()
    }

    /** 滚动列表第一个可见 item 的下标（拿不到时给 0）。 */
    private fun firstVisibleScrollItem(): Int =
        (binding.novelScroll.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: 0

    /**
     * 本章「整页都已翻」的页集合（进度条上的绿色区间）。
     *
     * 与漫画同义：那边是「这一页渲染过译图」，这边是「这一页的每一段都有译文」。
     */
    private fun translatedPagesOf(pages: List<NovelPage>): Set<Int> {
        if (translations.isEmpty()) return emptySet()
        return pages.indices.filterTo(mutableSetOf()) { i ->
            pages[i].segments.isNotEmpty() && pages[i].segments.all { translations.containsKey(it.paraIndex) }
        }
    }

    /**
     * 滚动模式下的「已翻译」格集合（进度条上的绿色区间），值与 [loadChapter] 同步刷新。
     *
     * ⚠️ 不能每次滚动都现算：滚动回调是每帧一次的，几百段遍历会白烧 CPU。
     * 单位与 [refreshOverlay] 推进度条时一致（[NovelScrollProgress] 的格）。
     */
    private fun computeScrollTranslated(c: ChapterContent): Set<Int> {
        if (translations.isEmpty()) return emptySet()
        val visible = NovelScrollMapping.visibleParagraphs(c)
        val hits = mutableSetOf<Int>()
        for (i in visible.indices) if (translations.containsKey(visible[i].index)) hits += i
        return NovelScrollProgress.stepsOfItems(hits, visible.size)
    }

    private fun showStatus(message: String?) {
        binding.novelStatus.text = message ?: ""
        binding.novelStatus.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ===== 翻译浮层组 =====

    /**
     * 同步翻译浮层组（判据全部收敛在 [NovelTranslateChrome] 里，这个函数只负责画）。
     *
     * ### 判据（用户口径，别再改错）
     * - **翻译按钮**：当前页还有没翻的可翻译段才显示；本页都翻过了就只剩三态。
     *   滚动模式没有「页」，按**整章**还有没有没翻的段判（否则滚一屏就忽隐忽现）。
     * - **三态按钮**：当前页（滚动模式 = 当前屏幕）有译文才显示 ——
     *   没译文时点了看不出任何变化。
     * - **图标不再看「有没有译文」**：漫画那套「缓存命中 → 变重翻图标」在文本翻译里**不要**
     *   （用户明确要求）。重翻走长按多选，按钮语义由**选择集**决定。
     */
    private fun refreshTranslationChrome() {
        if (chromeHidden) {
            binding.translateGroup.visibility = View.GONE
            return
        }
        val translated = chromeTranslated()
        val selectedUntranslated = selectedPara.count { !translations.containsKey(it) }
        val selectedTranslated = selectedPara.count { translations.containsKey(it) }
        val action = if (selectedPara.isNotEmpty()) {
            NovelTranslateChrome.actionForSelection(selectedUntranslated, selectedTranslated)
        } else {
            NovelTranslateChrome.actionFor(chromeUntranslated())
        }
        val showToggle = NovelTranslateChrome.showToggle(translated)
        val showFail = hasFailedChapter(chapterIndex)

        // 三块都不可用时整组收掉：留一个空的黑胶囊在右下角更奇怪
        binding.translateGroup.visibility =
            if (action != null || showToggle || showFail) View.VISIBLE else View.GONE

        binding.btnTranslate.visibility = if (action != null) View.VISIBLE else View.GONE
        if (action != null) {
            binding.ivTranslate.setImageResource(
                when (action) {
                    // 「翻译」用翻译图标；重翻与"翻+重翻"都含重翻动作 → 刷新图标
                    NovelTranslateAction.TRANSLATE -> R.drawable.ic_reader_translate
                    NovelTranslateAction.RETRANSLATE,
                    NovelTranslateAction.TRANSLATE_AND_RETRANSLATE,
                    -> R.drawable.ic_refresh
                }
            )
            val label = actionLabel(action, selectedPara.size)
            binding.btnTranslate.contentDescription = label
            // 选择模式下按钮左边显示动作 + 段数：只有两个图标，区分不出「重翻」和「翻译+重翻」。
            // ⚠️ 判据是**选中集非空**（不是 selecting）：取消到一段不剩时按钮已经退回普通语义，
            // 显示「翻译 0 段」就自相矛盾了
            val showLabel = selectedPara.isNotEmpty()
            binding.tvTranslateAction.visibility = if (showLabel) View.VISIBLE else View.GONE
            if (showLabel) binding.tvTranslateAction.text = label
        } else {
            binding.tvTranslateAction.visibility = View.GONE
        }

        binding.btnToggleTranslate.visibility = if (showToggle) View.VISIBLE else View.GONE
        binding.btnFailTranslate.visibility = if (showFail) View.VISIBLE else View.GONE
        if (showToggle) {
            binding.ivToggleTranslate.setImageResource(displayModeIcon(NovelPanelStyle.displayMode(prefs)))
        }
    }

    /** 动作文案（也当按钮的 contentDescription 用，读屏能听出"这一下会干什么"）。 */
    private fun actionLabel(action: NovelTranslateAction, count: Int): String = getString(
        when (action) {
            NovelTranslateAction.TRANSLATE -> R.string.novel_action_translate_n
            NovelTranslateAction.RETRANSLATE -> R.string.novel_action_retranslate_n
            NovelTranslateAction.TRANSLATE_AND_RETRANSLATE -> R.string.novel_action_both_n
        },
        count,
    )

    /** 浮层判据：当前视野里**还没译文**的可翻译段数（滚动模式按整章 —— 见 [refreshTranslationChrome]）。 */
    private fun chromeUntranslated(): Int {
        if (!isScrollMode()) return translatableOnScreen().count { !translations.containsKey(it) }
        val c = content ?: return 0
        return c.paragraphs.count { it.isTranslatable() && !translations.containsKey(it.index) }
    }

    /** 浮层判据：当前视野里已有译文的段数（三态按钮的显示条件）。 */
    private fun chromeTranslated(): Int = translatableOnScreen().count { translations.containsKey(it) }

    /**
     * 当前视野里**可翻译**的段号：翻页模式 = 本页各 segment 的段号，滚动模式 = 可见段。
     *
     * ⚠️ 必须滤掉图片与短行（[isTranslatable]）：它们**按设计永远不翻译**，
     * 算进「待翻译」的话含 `……` 的一页永远判不出「整页翻完了」，翻译按钮就常驻且点了没反应。
     */
    private fun translatableOnScreen(): List<Int> {
        val c = content ?: return emptyList()
        return currentPageParaIndexes().filter { pi ->
            c.paragraphs.firstOrNull { it.index == pi }?.isTranslatable() == true
        }
    }

    /**
     * 三态图标（**自绘**，在 `res/drawable/ic_display_*.xml`）。
     *
     * ⚠️ 原来借的是 `android.R.drawable.ic_menu_*`（相机/图库/眼睛那套老系统图标），
     * 与阅读器其它图标不是一种风格，用户明确要求换掉。三个态各一个自绘图标，
     * 由调用方统一 tint 成白色（浮层组是固定深色底）。
     */
    private fun displayModeIcon(mode: NovelDisplayMode): Int = when (mode) {
        NovelDisplayMode.TRANSLATED -> R.drawable.ic_display_translated
        NovelDisplayMode.ORIGINAL -> R.drawable.ic_display_original
        NovelDisplayMode.BILINGUAL -> R.drawable.ic_display_bilingual
    }

    /**
     * 本章有没有**真的翻译失败**的段（右下角感叹号）。
     *
     * ⚠️ 判据必须是**失败明细**（`failures` 表里真的 FAILED 的行），不能是
     * 「success < total」：后者在"翻了一部分"时为真 —— 于是只要开了自动翻译，感叹号就一直挂着；
     * 而且面板「失败」筛选 tab 用的是 `chapterFailures`，两处判据不一致会让用户看到
     * "有感叹号但列表里没有失败的章"。
     */
    private fun hasFailedChapter(chapterIndex: Int): Boolean =
        chapterFailures[chapterIndex].orEmpty().isNotEmpty()

    /**
     * 右下角翻译按钮（语义**与漫画 `ReaderTranslationController.onTranslateButtonClick` 逐条对齐**）：
     * - **单击**：手动模式空闲 → 从当前页第一段没翻的段起翻**一批**；
     *   自动/增量在跑 → **只提示，绝不打断**（用户明确要求）
     * - **双击**：取消在途翻译并**回退手动**
     */
    private fun onTranslateButtonClick() {
        // 选择模式：单击就是**按选择翻**（翻译 / 重翻 / 翻译+重翻）——
        // 意图已经由选择集说清了，不走下面那套「双击=取消」的判定
        if (selecting && selectedPara.isNotEmpty()) {
            translateSelectionNow()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val isDouble = now - lastTranslateClickMs <= DOUBLE_CLICK_MS
        lastTranslateClickMs = if (isDouble) 0L else now

        val mode = NovelPanelStyle.translateMode(prefs)
        if (queueRunning && mode != NovelTranslateMode.MANUAL) {
            if (!isDouble) {
                showOverlayToast(
                    getString(R.string.novel_translate_hint_running, chapterIndex + 1), error = false,
                )
                return
            }
            pauseToManual(getString(R.string.reader_translate_cancelled))
            return
        }
        if (isDouble) return
        translateOneBatchNow()
    }

    /** 手动：从当前页锚点翻**一批**（走队列的同一套规则）。 */
    private fun translateOneBatchNow() {
        val b = book ?: return
        val q = queueOrNull()
        if (q == null) {
            toast(R.string.novel_translate_need_config)
            return
        }
        showOverlay(getString(R.string.reader_translate_in_progress), autoDismiss = false)
        lifecycleScope.launch {
            val result = runCatching { q.translateOneBatch(b, chapterIndex) }.getOrNull()
            showOverlay(null)
            if (result == null || result.isEmpty) {
                // 失败也要刷面板：不然「失败」状态和原因要等下次开面板才看得到
                refreshChapterStats()
                showOverlayToast(
                    getString(
                        R.string.novel_translate_failed_reason,
                        result?.error ?: getString(R.string.reader_translate_failed),
                    ),
                    error = true,
                )
                return@launch
            }
            refreshChapterStats()
            refreshTranslations()
        }
    }

    /**
     * 翻**选中的这些段**（长按多选 → 翻译 / 重翻 / 翻译+重翻）。
     *
     * ⚠️ 按「每批段数」（面板里的设置）**拆成多次请求**（用户要求）：一次塞几十段
     * 既容易超上下文，也看不到逐批上屏的效果。
     * ⚠️ 选中里**已有译文的段照样重发**（这正是重翻）—— 队列的 `translateExact`
     * 刻意不经过规划器，规划器会把有译文的段当已完成跳过。
     */
    private fun translateSelectionNow() {
        val b = book ?: return
        val wanted = selectedPara.sorted()
        if (wanted.isEmpty()) return
        val q = queueOrNull()
        if (q == null) {
            toast(R.string.novel_translate_need_config)
            return
        }
        val chunk = NovelPanelStyle.batchSize(prefs).coerceAtLeast(1)
        showOverlay(getString(R.string.reader_translate_in_progress), autoDismiss = false)
        lifecycleScope.launch {
            var failed: String? = null
            var done = 0
            for (group in wanted.chunked(chunk)) {
                val r = runCatching { q.translateExact(b, chapterIndex, group) }.getOrNull()
                if (r == null || r.isEmpty) {
                    failed = r?.error ?: getString(R.string.reader_translate_failed)
                    break
                }
                done += group.size
                // 每批上屏一次：选十几段时要能看到逐批出译文，而不是最后一起蹦出来
                refreshTranslations()
            }
            showOverlay(null)
            refreshChapterStats()
            if (failed != null) {
                showOverlayToast(getString(R.string.novel_translate_failed_reason, failed), error = true)
            } else {
                showOverlayToast(getString(R.string.novel_translate_selected_done, done), error = false)
            }
        }
    }

    /**
     * 把 pager 拉回锚点所在的页（重排后的二次校正）。
     *
     * ⚠️ 只在**算出来的页与当前页不同**时才动：`setCurrentItem` 会触发 `onPageSelected` →
     * `onPaged`，无条件调用等于和 ViewPager2 自己的定位互相打架。
     */
    private fun snapPagerToAnchor(anchor: NovelAnchor) {
        val c = content ?: return
        if (c.pages.isEmpty()) return
        val want = NovelAnchors.pageOf(c.pages, c.displayTexts, anchor)
        val cur = binding.novelPager.currentItem
        if (cur != want) {
            NovelDebug.log("snapPager 校正：anchor=$anchor 当前页=$cur 校正到=$want")
            binding.novelPager.setCurrentItem(want, false)
        }
    }

    /**
     * 取（必要时创建）翻译队列。
     *
     * ⚠️ 手动模式是**默认模式**，队列从没被创建过 —— 少了这句兜底，点翻译按钮会一点反应都没有
     * （曾经就是「翻译本章点了没作用」的根因）。引擎没配好返回 null，由调用方提示。
     */
    private fun queueOrNull(): NovelTranslationQueue? {
        val t = runCatching { translator() }.getOrNull() ?: return null
        return ensureQueue(t)
    }

    /**
     * 停止在途翻译并**回退手动**：面板打开 / 双击 / 退出阅读器都走这里。
     *
     * 与漫画 `pauseToManual` 同义 —— 回退之后不会自己接上，要用户重新选模式（用户明确要求）。
     */
    private fun pauseToManual(message: String? = null) {
        queue?.stop()
        queueRunning = false
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.MANUAL)
        showOverlay(null)
        message?.let { showOverlayToast(it, error = false) }
        pushPanelState()
    }

    /** 三态循环：译文 → 原文 → 双语。 */
    private fun cycleDisplayMode() {
        val next = NovelDisplayModeCodec.next(NovelPanelStyle.displayMode(prefs))
        NovelPanelStyle.setDisplayMode(prefs, next)
        loadChapter(chapterIndex, anchor = pendingAnchor)
        showOverlayToast(NovelPanelStyle.displayModeLabel(this, next), error = false)
    }

    /**
     * 失败页点感叹号：小气泡说明（不弹窗，与漫画一致）。
     *
     * ⚠️ 显示**原始失败原因**（`failCode` 里存的就是异常链/模型返回原文），不是一句进度 ——
     * 具体哪几段失败、都写什么，面板章行展开里有全量明细。
     */
    private fun showFailBubble() {
        val rows = chapterFailures[chapterIndex].orEmpty()
        val msg = rows.firstOrNull()?.failCode?.take(120)
            ?: getString(R.string.reader_translate_failed)
        TranslationStatusOverlay.getInstance(this).show(
            getString(R.string.reader_translate_failed_page_hint, chapterIndex + 1, msg)
        )
    }

    /** 观察自动队列状态 → 状态浮层（与漫画同样是「常驻芯片 + 结束即消」）。 */
    private fun observeQueue(q: NovelTranslationQueue) {
        // 队列可能被丢弃重建（模型换了）：旧收集器要先撤，否则每次换模型都往 lifecycleScope 上挂一个
        queueObserverJob?.cancel()
        queueObserverJob = lifecycleScope.launch {
            q.state.collect { st ->
                // 队列状态一变就把整份状态推给面板（否则面板不知道"正在翻/停了"）
                pushPanelState()
                // 「正在翻译」高亮：手动/自动/增量都走队列，所以在这一处统一接
                // （TRANSLATING / WAITING_LOCK 时高亮这一批；DRAINED 后**保留**，
                //  用户正是靠它找"刚翻完的是哪几段"）
                if (st.batchParaIndexes.isNotEmpty()) {
                    val next = st.batchParaIndexes.toSet()
                    if (next != activeBatch) {
                        activeBatch = next
                        refreshActiveHighlight()
                    }
                }
                when (st.phase) {
                    NovelQueuePhase.TRANSLATING -> showOverlay(
                        queuePositionText(st), autoDismiss = false,
                    )
                    NovelQueuePhase.WAITING_LOCK -> showOverlay(
                        getString(R.string.novel_translate_busy), autoDismiss = false
                    )
                    NovelQueuePhase.DRAINED -> {
                        showOverlay(null)
                        // ⚠️ **DRAINED 不等于"翻完了"，别在这里喊完成**（用户报的
                        // 「某一章没翻完却显示已经全部翻译完成」就是这里）：走到 DRAINED 有四种原因 ——
                        // 手动/自动翻完当前页、增量翻完窗口、本章真的翻完、**以及一批失败被跳过**。
                        // 只有增量值得说一句，而且要说清"翻页就能接着翻"。
                        if (st.remaining != null) {
                            showOverlayToast(
                                getString(R.string.novel_translate_window_done, st.batchesDone),
                                error = false,
                            )
                        }
                    }
                    NovelQueuePhase.IDLE -> showOverlay(null)
                }
            }
        }
    }

    /**
     * 状态芯片文案：**显示正在翻到哪一页 / 哪一段，不是第几章**（用户要求）。
     *
     * 增量模式会一直往后翻，只写"第 N 章"等于什么都没说 —— 用户在等着看它翻到哪一页了。
     * 翻页模式给「第 X/Y 页」，滚动模式没有「页」就给段号。
     */
    private fun queuePositionText(st: NovelQueueState): String {
        val para = st.batchParaIndexes.firstOrNull()
        val c = content
        if (para != null && !isScrollMode() && c != null && c.pages.isNotEmpty()) {
            val page = NovelAnchors.pageOf(c.pages, c.displayTexts, NovelAnchor(para))
            return getString(R.string.novel_queue_translating_page, page + 1, c.pages.size)
        }
        return getString(R.string.novel_queue_translating_para, (para ?: 0) + 1)
    }

    private fun statusOverlayEnabled(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("status_overlay_enabled", true)

    /** 状态浮层显示（`text == null` = 收起）。⚠️ 开关关闭时退回系统 Toast，否则用户毫无反馈。 */
    private fun showOverlay(text: String?, autoDismiss: Boolean = true) {
        if (!statusOverlayEnabled()) {
            if (text != null) UiUtils.showToast(this, text)
            return
        }
        val overlay = TranslationStatusOverlay.getInstance(this)
        if (text == null) overlay.dismiss() else overlay.showImmediate(text, autoDismiss)
    }

    private fun showOverlayToast(text: String, error: Boolean) {
        if (!statusOverlayEnabled()) {
            UiUtils.showToast(this, text)
            return
        }
        val overlay = TranslationStatusOverlay.getInstance(this)
        overlay.dismiss()
        if (error) overlay.showError(text) else overlay.show(text)
    }

    /**
     * 把指定页重画（改排版/译文到达/切显示模式后）。
     *
     * 分页模式下页表已经重建（[loadChapter] 会 submit），这里只补一次重绑；
     * 滚动模式的条数可能变了，同样走 submit。
     */
    private fun refreshCurrentVisual() {
        if (isScrollMode()) scrollAdapter.refresh() else pageAdapter.refresh()
    }

    // ===== 面板 =====

    private fun openToc() {
        val b = book ?: return
        lifecycleScope.launch {
            val stats = runCatching { translator().chapterStats(b) }.getOrDefault(emptyMap())
            chapterStats = stats
            val chapters = repository.chaptersOf(b)
            NovelTocDialog.show(
                context = this@NovelReaderActivity,
                chapters = chapters,
                stats = stats,
                currentChapter = chapterIndex,
                dark = NovelPanelStyle.isDarkBackground(bgMode),
            ) { picked -> gotoChapter(picked) }
        }
    }

    private fun showMenu() {
        val b = book ?: return
        if (supportFragmentManager.findFragmentByTag(NovelPanelSheet.TAG) != null) return
        // 章节目录要读文件（EPUB 读 OPF+NCX；TXT 跑一遍分章正则），不能在主线程取
        lifecycleScope.launch {
            val chapters = repository.chaptersOf(b)
            val stats = runCatching { translator().chapterStats(b) }.getOrDefault(chapterStats)
            chapterStats = stats
            showMenuNow(chapters, stats)
        }
    }

    private fun showMenuNow(chapters: List<com.moe.starflow.novel.model.NovelChapterMeta>, stats: Map<Int, NovelChapterStat>) {
        if (isFinishing || isDestroyed) return
        val fontSp = NovelPanelStyle.fontSizeSp(prefs)
        val sheet = NovelPanelSheet(
            NovelPanelState(
                readerMode = NovelPanelStyle.readerMode(prefs),
                animation = animationMode,
                bg = bgMode,
                        fontSizeSp = fontSp,
                lineSpacingStep = NovelPanelStyle.lineSpacingStep(prefs),
                paragraphSpacingDp = NovelPanelStyle.paragraphSpacingDp(prefs, fontSp),
                paddingDp = NovelPanelStyle.paddingDp(prefs),
                topPaddingDp = NovelPanelStyle.topPaddingDp(prefs),
                bottomPaddingDp = NovelPanelStyle.bottomPaddingDp(prefs),
                autoTurn = autoTurnEnabled,
                intervalSec = autoTurnIntervalSec,
                rotateLabel = rotateLabel(),
                isDarkPanel = NovelPanelStyle.isDarkBackground(bgMode),
                keepParagraphsWhole = NovelPanelStyle.keepParagraphsWhole(prefs),
                translateMode = NovelPanelStyle.translateMode(prefs),
                debounceMs = NovelPanelStyle.debounceMs(prefs),
                aheadBatches = NovelPanelStyle.aheadBatches(prefs),
                batchSize = NovelPanelStyle.batchSize(prefs),
                chapterCount = chapterCount,
                currentChapter = chapterIndex,
                chapters = chapters,
                chapterStats = stats,
            ),
            NovelPanelCallbacks(
                onReaderMode = { mode ->
                    NovelPanelStyle.setReaderMode(prefs, mode)
                    // ⚠️ 上下翻页 ↔ 左右翻页 用的是同一个分页表（尺寸没变），但仍要重走一遍
                    // loadChapter：滚动模式与翻页模式之间条目结构不同，必须重新提交
                    applyReaderMode()
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                    updateAutoTurn()
                },
                onAnimation = { a -> NovelPanelStyle.setAnimation(prefs, a); animationMode = a; applyAnimation() },
                onBackground = { v ->
                    NovelPanelStyle.setBackground(prefs, v)
                    applyBackground()
                    refreshCurrentVisual()
                    // ⚠️ 背景换了、面板里所有组件的配色都要跟着换 —— 不推的话得关掉再打开才对
                    pushPanelState()
                },
                onFontSize = { sp ->
                    NovelPanelStyle.setFontSizeSp(prefs, sp)
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onLineSpacing = { v ->
                    NovelPanelStyle.setLineSpacingStep(prefs, v, NovelPanelStyle.fontSizeSp(prefs))
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onParagraphSpacing = { v ->
                    NovelPanelStyle.setParagraphSpacingDp(prefs, v, NovelPanelStyle.fontSizeSp(prefs))
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onPadding = { v -> NovelPanelStyle.setPaddingDp(prefs, v); loadChapter(chapterIndex, anchor = pendingAnchor) },
                onResetTypography = {
                    NovelPanelStyle.resetTypography(prefs)
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onKeepParagraphsWhole = { v ->
                    NovelPanelStyle.setKeepParagraphsWhole(prefs, v)
                    // 改的是分页规则 → 必须重排（页表变了）
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onTopPadding = { v -> NovelPanelStyle.setTopPaddingDp(prefs, v); loadChapter(chapterIndex, anchor = pendingAnchor) },
                onBottomPadding = { v -> NovelPanelStyle.setBottomPaddingDp(prefs, v); loadChapter(chapterIndex, anchor = pendingAnchor) },
                onAutoTurn = { enabled, interval ->
                    prefs.edit().putBoolean(KEY_AUTO_TURN, enabled).putInt(KEY_INTERVAL, interval).apply()
                    autoTurnEnabled = enabled
                    autoTurnIntervalSec = interval
                    updateAutoTurn()
                },
                onRotate = { updateRotateMode((rotateMode + 1) % 3, persist = true) },
                onSettings = {
                    returnedFromSettings = true
                    startActivity(
                        Intent(this@NovelReaderActivity, SettingPageActivity::class.java)
                            .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
                    )
                },
                onTranslateMode = { m ->
                    NovelPanelStyle.setTranslateMode(prefs, m)
                    if (m == NovelTranslateMode.MANUAL) {
                        queue?.stop()
                        showOverlay(null)
                    }
                    refreshTranslationChrome()
                },
                onDebounceMs = { ms -> NovelPanelStyle.setDebounceMs(prefs, ms) },
                onAheadBatches = { n -> NovelPanelStyle.setAheadBatches(prefs, n) },
                onBatchSize = { n -> NovelPanelStyle.setBatchSize(prefs, n) },
                currentTranslateMode = { NovelPanelStyle.translateMode(prefs) },
                onTranslateNow = { onTranslateButtonClick() },
                onClearChapter = { clearChapterTranslations() },
                onDownload = { which -> exportNovel(which) },
                onChapterJump = { ch -> gotoChapter(ch) },
                onNeedChapterTotal = { ch -> ensureChapterTotal(ch) },
                onPanelOpened = {
                    // 与漫画一致：面板一打开就**暂停并回退手动** —— 用户在看面板时若按旧设置继续翻，
                    // 既浪费额度也可能翻错；要重新选模式才会继续
                    queue?.setPanelOpen(true)
                    showOverlay(null)
                    // 面板一开就退出选择模式：那套浮层被面板挡住，留着只会让状态对不上
                    exitSelection()
                    refreshChapterStats()
                    if (NovelPanelStyle.translateMode(prefs) != NovelTranslateMode.MANUAL) {
                        pauseToManual(getString(R.string.reader_translate_paused_to_manual))
                    }
                },
                onPanelClosed = {
                    queue?.setPanelOpen(false)
                    // 模式可能刚被面板改过（面板里选的是宿主真实模式），强制按新参数重启
                    restartQueueIfNeeded(force = true)
                },
                onOpenApiConfig = {
                    startActivity(
                        Intent(this@NovelReaderActivity, SettingPageActivity::class.java)
                            .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_API_CONFIG)
                    )
                }
            )
        )
        sheet.show(supportFragmentManager, NovelPanelSheet.TAG)
    }

    /**
     * 只清**本章**译文（用户明确要求：不要一点就清整本；面板里已二次确认）。
     */
    private fun clearChapterTranslations() {
        val b = book ?: return
        lifecycleScope.launch {
            runCatching { translator().clearChapter(b, chapterIndex) }
            translations = emptyMap()
            // 译文都没了，"刚翻的是哪几段"也就无从谈起
            activeBatch = emptySet()
            refreshChapterStats()
            loadChapter(chapterIndex, anchor = pendingAnchor)
            showOverlayToast(getString(R.string.novel_translate_cleared_chapter), error = false)
        }
    }

    /** 导出译文 / 原文 / 双语（0 译文 1 原文 2 双语）。 */
    private fun exportNovel(which: Int) {
        val b = book ?: return
        val kind = when (which) {
            1 -> NovelExport.Kind.ORIGINAL
            2 -> NovelExport.Kind.BILINGUAL
            else -> NovelExport.Kind.TRANSLATED
        }
        val t = runCatching { translator() }.getOrNull() ?: return
        showOverlay(getString(R.string.novel_download_writing), autoDismiss = false)
        lifecycleScope.launch {
            val r = runCatching { NovelExport.exportBook(this@NovelReaderActivity, b, repository, t, kind) }
            showOverlay(null)
            r.onSuccess { showOverlayToast(getString(R.string.novel_download_done, it.absolutePath), error = false) }
                .onFailure { showOverlayToast(getString(R.string.novel_download_failed, it.message.orEmpty()), error = true) }
        }
    }

    private fun clearBookTranslations() {
        val b = book ?: return
        lifecycleScope.launch {
            runCatching { translator().clearBook(b) }
                .onFailure { LogCollector.w(TAG, "清空译文失败", it) }
            translations = emptyMap()
            refreshChapterStats()
            loadChapter(chapterIndex, anchor = pendingAnchor)
            toast(R.string.novel_translate_cleared)
        }
    }

    // ===== 自动翻页 =====

    private fun updateAutoTurn() {
        autoTurnJob?.cancel()
        autoTurnJob = null
        if (!autoTurnEnabled || isScrollMode() || chapterCount < 1) return
        autoTurnJob = lifecycleScope.launch {
            val intervalMs = autoTurnIntervalSec.toLong() * 1000
            while (isActive) {
                delay(intervalMs)
                if (SystemClock.elapsedRealtime() - lastInteractionMs < 2000L) continue
                turnPage(1)
            }
        }
    }

    // ===== 旋转 =====

    private fun updateRotateMode(mode: Int, persist: Boolean) {
        rotateMode = mode
        if (persist) prefs.edit().putInt(KEY_ROTATE, mode).apply()
        requestedOrientation = when (mode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun rotateLabel(): String = getString(
        when (rotateMode) {
            1 -> R.string.reader_rotate_landscape
            2 -> R.string.reader_rotate_follow
            else -> R.string.reader_rotate_portrait
        }
    )

    // ===== 进度 =====

    /**
     * 保存阅读位置。
     *
     * ⚠️ 权威锚点是**章内段落号**而不是页号：页号随字号、行距、视口尺寸、甚至
     * 「译文到达后的重新分页」而变；段落号是内容本身的属性，永远指向同一处。
     */
    private fun persistProgress() {
        val b = book ?: return
        val updated = b.copy(
            lastReadChapter = chapterIndex,
            lastReadParaIndex = pendingAnchor.paraIndex,
            lastReadPage = if (isScrollMode()) 0 else currentPage(),
        )
        book = updated
        NovelStore.update(this, updated)
    }

    // ===== 沉浸 =====

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 供面板判断「当前是否在前台」用（导出/长任务避免把芯片贴到别的应用上）。 */
    fun isForeground(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun toast(resId: Int) {
        UiUtils.showToast(this, getString(resId), true)
    }
}
