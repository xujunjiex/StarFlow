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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
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
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.NovelQueuePhase
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

        /** 视口尺寸变化后的重排防抖（尺寸是连续事件，见 [scheduleRepaginate]）。 */
        private const val REPAGINATE_DEBOUNCE_MS = 180L

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
    private var translationJob: Job? = null

    /** 滚动模式的「已翻译段」（进度条绿条），在 [loadChapter] 里随内容与译文一起重算。 */
    private var scrollTranslated: Set<Int> = emptySet()

    /** 上下 UI（顶部三个浮层 + 底部胶囊 + 右下翻译浮层组）是否隐藏。 */
    private var chromeHidden = false

    private var bgMode = 0
    private var animationMode = NovelPanelStyle.ANIM_SLIDE

    private var autoTurnEnabled = false
    private var autoTurnIntervalSec = 5
    private var autoTurnJob: Job? = null
    private var lastInteractionMs = 0L
    private var rotateMode = 0

    /** 自动翻译队列（手动模式下不起）。惰性创建：它要用到翻译引擎。 */
    private var queue: NovelTranslationQueue? = null

    /** 队列最近一次是按哪一章 / 哪个模式起的（重复调用时用来短路，省掉一次无谓重启）。 */
    private var queueChapter = -1
    private var queueMode = -1
    private var queueRunning = false

    /** 段落号 → 页码的请求：改排版/译文到达后重新分页时用它把位置找回来。 */
    private var pendingParaIndex = 0

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

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                lastInteractionMs = SystemClock.elapsedRealtime()
            }
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
        pendingParaIndex = loaded.lastReadParaIndex

        if (savedInstanceState != null) {
            returnedFromSettings = savedInstanceState.getBoolean(STATE_FROM_SETTINGS, false)
            chapterIndex = savedInstanceState.getInt(STATE_CURRENT_CHAPTER, chapterIndex)
        }

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyReaderMode()

        NovelDebug.log(
            "onCreate book=${loaded.id} chapters=$chapterCount ch=$chapterIndex para=$pendingParaIndex " +
                "mode=${NovelPanelStyle.readerMode(prefs)} anim=$animationMode bg=$bgMode " +
                "root=${binding.root.width}x${binding.root.height}"
        )

        // ⚠️ **先渲染正文**：首次进入绝不能只有「译文读回来」那一条链才会加载内容 ——
        // 那条链在「本章一句译文都没有」时会判定「没变化」直接返回，结果第一次进阅读器
        // 一片空白，必须切一次章才显示（用户实测反馈）。
        loadChapter(chapterIndex, keepPara = pendingParaIndex)

        lifecycleScope.launch {
            // 清理上次异常退出留下的「翻译中」标记（进程被杀时退出清理不会执行）
            runCatching { translator().resetStale(loaded) }
                .onFailure { LogCollector.w(TAG, "清理残留翻译状态失败", it) }
            refreshChapterStats()
            refreshTranslations(keepPara = pendingParaIndex)
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
        autoTurnJob = null
        // 切后台必须暂停队列：lifecycleScope 不因 onStop 取消，否则翻译会在后台整段跑，
        // 且常驻状态芯片（系统窗口）会一直盖在别的应用上
        queue?.stop()
        queueRunning = false
        TranslationStatusOverlay.getInstance(this@NovelReaderActivity).dismiss()
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

    // ===== 翻页 / 翻段 / 切章 =====

    private fun isScrollMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_SCROLL

    private fun isVerticalMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_VERTICAL

    private fun currentPage(): Int =
        if (isScrollMode()) firstVisibleScrollItem() else binding.novelPager.currentItem

    /** 翻页；越界则切章（本章末页往后 → 下一章首页，反之上一章末页）。 */
    private fun turnPage(delta: Int) {
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
     * 拖了没反应就是「控件在、功能不在」。滚动模式按**段**定位。
     */
    private fun goToPage(page: Int) {
        val pages = content?.pages ?: return
        if (isScrollMode()) {
            // ⚠️ 滚动模式要**瞬间**跳，不能平滑滚动：进度条上跨的是几十页的跨度，
            // 平滑滚过去要好几秒、中途整屏文字飞速掠过（用户明确要求瞬间切换）
            val total = scrollAdapter.itemCount
            if (total <= 0) return
            val target = page.coerceIn(0, total - 1)
            (binding.novelScroll.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target, 0)
            return
        }
        val p = page.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        binding.novelPager.setCurrentItem(p, false)
        onPaged(p)
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
        persistProgress()
        loadChapter(index, keepPara = if (atLastPage) Int.MAX_VALUE else 0, atLastPage = atLastPage)
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
            loadChapter(chapterIndex, keepPara = pendingParaIndex)
        }
    }

    // ===== 模式 / 背景 / 动画 =====

    private fun applyReaderMode() {
        val mode = NovelPanelStyle.readerMode(prefs)
        val scroll = mode == NovelPanelStyle.READER_SCROLL
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
     * @param keepPara 要定位到的段落号（[Int.MAX_VALUE] = 本章末尾）
     */
    private fun loadChapter(index: Int, keepPara: Int = 0, atLastPage: Boolean = false) {
        val b = book ?: return
        val token = ++loadToken
        lifecycleScope.launch {
            val style = NovelPanelStyle.textStyle(this@NovelReaderActivity, prefs)
            val w = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            NovelDebug.log("loadChapter#$token start ch=$index keepPara=$keepPara atLast=$atLastPage size=${w}x$h")

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

            val loaded = repository.load(b, index, translations, NovelPanelStyle.displayMode(prefs), style, w, h)
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
                val pos = if (keepPara == Int.MAX_VALUE) {
                    (scrollAdapter.itemCount - 1).coerceAtLeast(0)
                } else {
                    NovelScrollMapping.positionOf(loaded, keepPara)
                }
                binding.novelScroll.scrollToPosition(pos)
            } else {
                pageAdapter.submit(loaded, style, textColor, bgColor)
                val page = if (keepPara == Int.MAX_VALUE) {
                    loaded.pages.lastIndex.coerceAtLeast(0)
                } else if (atLastPage) {
                    loaded.pages.lastIndex.coerceAtLeast(0)
                } else {
                    repository.pageOfParagraph(loaded.pages, keepPara)
                }
                binding.novelPager.setCurrentItem(page, false)
                onPaged(page)
                NovelDebug.log("loadChapter submit pages=${pageAdapter.itemCount} gotoPage=$page")
            }
            refreshOverlay()
            refreshTranslationChrome()
            updateChapterTocLabel()
            persistProgress()
            restartQueueIfNeeded()
        }
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
        if (mode == NovelPanelStyle.MODE_MANUAL) {
            queue?.stop()
            queueRunning = false
            return
        }
        // 同一章的重复调用（译文到达 → 重排 → 又调一次）直接跳过：白重启一次队列会把
        // 防抖计时清零，正文越翻越慢
        if (!force && queueRunning && queueChapter == chapterIndex && queueMode == mode) return
        val t = runCatching { translator() }.getOrNull()
        if (t == null) {
            toast(R.string.novel_translate_need_config)
            return
        }
        val q = queue ?: NovelTranslationQueue(
            scope = lifecycleScope,
            translator = t,
            paragraphsOf = { book2, ch -> repository.paragraphsOf(book2, ch) },
            sourceLang = { sourceLang() },
            targetLang = { targetLang() },
            translatorName = { "novel" },
            batchSize = { NovelPanelStyle.batchSize(prefs) },
        ).also { it2 ->
            queue = it2
            // ⚠️ 队列是惰性创建的：观察必须挂在这里，挂在 onCreate 会对着 null 收流，
            // 之后新建的队列永远没人听（状态浮层再也不更新）
            observeQueue(it2)
        }

        queueChapter = chapterIndex
        queueMode = mode
        q.start(
            book = b,
            mode = mode,
            aheadCount = NovelPanelStyle.aheadChapters(prefs),
            debounceMs = NovelPanelStyle.debounceMs(prefs),
            // 每轮重新求值：切章不重启队列，窗口自动跟上
            currentChapter = { chapterIndex },
            onChapterTranslated = { ch ->
                refreshChapterStats()
                if (ch == chapterIndex) refreshTranslations(keepPara = pendingParaIndex)
            },
        )
        queueRunning = true
    }

    private fun sourceLang(): String = CustomPreference.getInstance(this).getString("Source_Language", "auto")

    private fun targetLang(): String = CustomPreference.getInstance(this).getString("Target_Language", "zh")

    private fun onPaged(position: Int) {
        val pages = content?.pages ?: return
        pendingParaIndex = pages.getOrNull(position)?.segments?.firstOrNull()?.paraIndex ?: 0
        refreshOverlay()
    }

    /** 滚动模式下把当前可见段同步到「待续读段落」。 */
    private fun updateFromScroll() {
        if (!isScrollMode()) return
        val lm = binding.novelScroll.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val c = content ?: return
        pendingParaIndex = NovelScrollMapping.paraIndexOf(c, first) ?: return
        refreshOverlay()
    }

    /** 译文到达后：重新分页（译文比原文长）+ 保持位置。 */
    private fun refreshTranslations(keepPara: Int = pendingParaIndex) {
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
            loadChapter(chapterIndex, keepPara = keepPara)
        }
    }

    private fun refreshChapterStats() {
        val b = book ?: return
        lifecycleScope.launch {
            chapterStats = runCatching { translator().chapterStats(b) }.getOrDefault(emptyMap())
            updateChapterTocLabel()
            // 面板开着就必须把它一起刷新：面板是打开那一刻的快照，宿主不推它就永远停在旧状态
            pushStatsToOpenPanel()
        }
    }

    /** 把最新的章翻译状态推给正在显示的面板（没开就什么都不做）。 */
    private fun pushStatsToOpenPanel() {
        (supportFragmentManager.findFragmentByTag(NovelPanelSheet.TAG) as? NovelPanelSheet)
            ?.notifyTranslateChanged(chapterStats)
    }

    private fun chapterTitle(index: Int): String {
        val t = content?.takeIf { it.chapterIndex == index }?.title
        if (!t.isNullOrBlank()) return t
        return getString(R.string.novel_chapter_label, index + 1)
    }

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
        binding.tvPageIndicator.text = text
    }

    private fun refreshOverlay() {
        if (isScrollMode()) {
            // 滚动模式的进度按**段**算（那边没有「页」）。不更新的话底部那条永远是死的 ——
            // 用户反馈「滚动模式下底部进度条不动」。
            binding.novelProgress.setPage(firstVisibleScrollItem(), scrollAdapter.itemCount)
            binding.novelProgress.setTranslatedPages(scrollTranslated)
        } else {
            val pages = content?.pages ?: return
            binding.novelProgress.setPage(currentPage(), pages.size)
            binding.novelProgress.setTranslatedPages(translatedPagesOf(pages))
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
     * 滚动模式下的「已翻译段」集合（进度条上的绿色区间），值与 [loadChapter] 同步刷新。
     *
     * ⚠️ 不能每次滚动都现算：滚动回调是每帧一次的，几百段遍历会白烧 CPU。
     */
    private fun computeScrollTranslated(c: ChapterContent): Set<Int> {
        if (translations.isEmpty()) return emptySet()
        val visible = NovelScrollMapping.visibleParagraphs(c)
        val out = mutableSetOf<Int>()
        for (i in visible.indices) if (translations.containsKey(visible[i].index)) out += i
        return out
    }

    private fun showStatus(message: String?) {
        binding.novelStatus.text = message ?: ""
        binding.novelStatus.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ===== 翻译浮层组 =====

    /** 同步翻译浮层组：本章有任何译文 → 出现三态按钮；翻译失败 → 出现感叹号。 */
    private fun refreshTranslationChrome() {
        if (chromeHidden) {
            binding.translateGroup.visibility = View.GONE
            return
        }
        binding.translateGroup.visibility = View.VISIBLE
        val translated = translations.isNotEmpty()
        val failed = hasFailedChapter(chapterIndex)
        binding.btnToggleTranslate.visibility = if (translated) View.VISIBLE else View.GONE
        binding.btnFailTranslate.visibility = if (failed) View.VISIBLE else View.GONE
        binding.ivTranslate.setImageResource(
            if (translated) R.drawable.ic_refresh else R.drawable.ic_reader_translate
        )
        if (translated) binding.ivToggleTranslate.setImageResource(displayModeIcon(NovelPanelStyle.displayMode(prefs)))
    }

    /** 三态图标与漫画一致（相机=译文 / 相册=双语 / 眼睛=原文）。 */
    private fun displayModeIcon(mode: NovelDisplayMode): Int = when (mode) {
        NovelDisplayMode.TRANSLATED -> android.R.drawable.ic_menu_camera
        NovelDisplayMode.ORIGINAL -> android.R.drawable.ic_menu_view
        else -> android.R.drawable.ic_menu_gallery
    }

    private fun hasFailedChapter(chapterIndex: Int): Boolean {
        val st = chapterStats[chapterIndex] ?: return false
        return st.total > 0 && st.success < st.total
    }

    /**
     * 右下角翻译按钮：
     * - 本章还没译文 → 翻这一章
     * - 已有译文 → 重翻这一章
     */
    private fun onTranslateButtonClick() {
        val b = book ?: return
        val loaded = content ?: return
        // 常驻芯片：翻完/失败时才收起（与漫画一致，中途不给"闪一下"的反馈）
        showOverlay(getString(R.string.reader_translate_in_progress), autoDismiss = false)
        translationJob?.cancel()
        translationJob = lifecycleScope.launch {
            if (!OcrLock.tryAcquire()) {
                showOverlay(null)
                toast(R.string.novel_translate_busy)
                return@launch
            }
            try {
                translator().translateChapter(
                    book = b,
                    chapterIndex = chapterIndex,
                    paragraphs = loaded.paragraphs,
                    sourceLang = sourceLang(),
                    targetLang = targetLang(),
                    translatorName = "novel",
                    batchSize = NovelPanelStyle.batchSize(prefs),
                ).collect { progress ->
                    refreshTranslations(keepPara = pendingParaIndex)
                    if (progress.isComplete) {
                        showOverlay(null)
                        showOverlayToast(getString(R.string.reader_translate_done), error = false)
                    }
                }
                refreshChapterStats()
                pushStatsToOpenPanel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "本章翻译失败", e)
                showOverlay(null)
                showOverlayToast(e.message ?: getString(R.string.reader_translate_failed), error = true)
            } finally {
                OcrLock.release()
            }
        }
    }

    /** 三态循环：译文 → 原文 → 双语。 */
    private fun cycleDisplayMode() {
        val next = NovelDisplayModeCodec.next(NovelPanelStyle.displayMode(prefs))
        NovelPanelStyle.setDisplayMode(prefs, next)
        loadChapter(chapterIndex, keepPara = pendingParaIndex)
        showOverlayToast(NovelPanelStyle.displayModeLabel(this, next), error = false)
    }

    /** 失败页点感叹号：小气泡说明（不弹窗，与漫画一致）。 */
    private fun showFailBubble() {
        val st = chapterStats[chapterIndex]
        val msg = if (st != null) {
            getString(R.string.novel_chapter_partial, st.success, st.total)
        } else {
            getString(R.string.reader_translate_failed)
        }
        TranslationStatusOverlay.getInstance(this).show(
            getString(R.string.reader_translate_failed_page_hint, chapterIndex + 1, msg)
        )
    }

    /** 观察自动队列状态 → 状态浮层（与漫画同样是「常驻芯片 + 结束即消」）。 */
    private fun observeQueue(q: NovelTranslationQueue) {
        lifecycleScope.launch {
            q.state.collect { st ->
                when (st.phase) {
                    NovelQueuePhase.TRANSLATING -> showOverlay(
                        getString(R.string.novel_queue_translating, st.chapterIndex + 1),
                        autoDismiss = false,
                    )
                    NovelQueuePhase.WAITING_LOCK -> showOverlay(
                        getString(R.string.novel_translate_busy), autoDismiss = false
                    )
                    NovelQueuePhase.DRAINED -> {
                        showOverlay(null)
                        showOverlayToast(getString(R.string.reader_translate_queue_drained), error = false)
                    }
                    NovelQueuePhase.IDLE -> showOverlay(null)
                }
            }
        }
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
                displayMode = NovelPanelStyle.displayMode(prefs),
                fontSizeSp = fontSp,
                lineSpacingStep = NovelPanelStyle.lineSpacingStep(prefs),
                paragraphSpacingDp = NovelPanelStyle.paragraphSpacingDp(prefs, fontSp),
                paddingDp = NovelPanelStyle.paddingDp(prefs),
                autoLayout = NovelPanelStyle.isAutoLayout(prefs),
                topPaddingDp = NovelPanelStyle.topPaddingDp(prefs),
                bottomPaddingDp = NovelPanelStyle.bottomPaddingDp(prefs),
                autoTurn = autoTurnEnabled,
                intervalSec = autoTurnIntervalSec,
                rotateLabel = rotateLabel(),
                tocLabel = getString(R.string.novel_chapter_label, chapterIndex + 1) + " / " + chapterCount,
                isDarkPanel = NovelPanelStyle.isDarkBackground(bgMode),
                translateMode = NovelPanelStyle.translateMode(prefs),
                debounceMs = NovelPanelStyle.debounceMs(prefs),
                aheadChapters = NovelPanelStyle.aheadChapters(prefs),
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
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                    updateAutoTurn()
                },
                onAnimation = { a -> NovelPanelStyle.setAnimation(prefs, a); animationMode = a; applyAnimation() },
                onBackground = { v -> NovelPanelStyle.setBackground(prefs, v); applyBackground(); refreshCurrentVisual() },
                onDisplayMode = { m -> NovelPanelStyle.setDisplayMode(prefs, m); loadChapter(chapterIndex, keepPara = pendingParaIndex) },
                onFontSize = { sp ->
                    NovelPanelStyle.setFontSizeSp(prefs, sp)
                    // 自动排版是「跟着字号走」的：字号一变必须重算一组间距，否则排版与字号脱节
                    if (NovelPanelStyle.isAutoLayout(prefs)) {
                        NovelPanelStyle.applyAutoLayout(this@NovelReaderActivity, prefs)
                    }
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                },
                onLineSpacing = { v ->
                    NovelPanelStyle.setLineSpacingStep(prefs, v, NovelPanelStyle.fontSizeSp(prefs))
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                },
                onParagraphSpacing = { v ->
                    NovelPanelStyle.setParagraphSpacingDp(prefs, v, NovelPanelStyle.fontSizeSp(prefs))
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                },
                onPadding = { v -> NovelPanelStyle.setPaddingDp(prefs, v); loadChapter(chapterIndex, keepPara = pendingParaIndex) },
                onAutoLayout = { auto ->
                    NovelPanelStyle.setAutoLayout(this@NovelReaderActivity, prefs, auto)
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                },
                onResetTypography = {
                    NovelPanelStyle.resetTypography(this@NovelReaderActivity, prefs)
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                },
                onTopPadding = { v -> NovelPanelStyle.setTopPaddingDp(prefs, v); loadChapter(chapterIndex, keepPara = pendingParaIndex) },
                onBottomPadding = { v -> NovelPanelStyle.setBottomPaddingDp(prefs, v); loadChapter(chapterIndex, keepPara = pendingParaIndex) },
                onAutoTurn = { enabled, interval ->
                    prefs.edit().putBoolean(KEY_AUTO_TURN, enabled).putInt(KEY_INTERVAL, interval).apply()
                    autoTurnEnabled = enabled
                    autoTurnIntervalSec = interval
                    updateAutoTurn()
                },
                onRotate = { updateRotateMode((rotateMode + 1) % 3, persist = true) },
                onOpenToc = { openToc() },
                onSettings = {
                    returnedFromSettings = true
                    startActivity(
                        Intent(this@NovelReaderActivity, SettingPageActivity::class.java)
                            .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
                    )
                },
                onTranslateMode = { m ->
                    NovelPanelStyle.setTranslateMode(prefs, m)
                    if (m == NovelPanelStyle.MODE_MANUAL) {
                        queue?.stop()
                        showOverlay(null)
                    }
                    refreshTranslationChrome()
                },
                onDebounceMs = { ms -> NovelPanelStyle.setDebounceMs(prefs, ms) },
                onAheadChapters = { n -> NovelPanelStyle.setAheadChapters(prefs, n) },
                onBatchSize = { n -> NovelPanelStyle.setBatchSize(prefs, n) },
                onTranslateNow = { onTranslateButtonClick() },
                onClearBook = { clearBookTranslations() },
                onChapterJump = { ch -> gotoChapter(ch) },
                onPanelOpened = {
                    // 打开面板即暂停队列（用户在调设置），关闭后才恢复
                    queue?.setPanelOpen(true)
                    showOverlay(null)
                    refreshChapterStats()
                },
                onPanelClosed = {
                    queue?.setPanelOpen(false)
                    restartQueueIfNeeded()
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

    private fun clearBookTranslations() {
        val b = book ?: return
        lifecycleScope.launch {
            runCatching { translator().clearBook(b) }
                .onFailure { LogCollector.w(TAG, "清空译文失败", it) }
            translations = emptyMap()
            refreshChapterStats()
            loadChapter(chapterIndex, keepPara = pendingParaIndex)
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
            lastReadParaIndex = pendingParaIndex,
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
