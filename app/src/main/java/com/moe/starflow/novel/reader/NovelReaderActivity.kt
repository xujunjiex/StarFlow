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
import android.widget.PopupWindow
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
import com.moe.starflow.mangaimport.reader.CoverTransformer
import com.moe.starflow.mangaimport.reader.CurlPageView
import com.moe.starflow.mangaimport.reader.NoneTransformer
import com.moe.starflow.mangaimport.reader.ReaderAnimationState
import com.moe.starflow.mangaimport.reader.SimulationTransformer
import com.moe.starflow.me.settings.SettingPageActivity
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelStorageDir
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.translate.NovelBatchTranslator
import com.moe.starflow.novel.translate.NovelBatchWarnGate
import com.moe.starflow.novel.translate.NovelBatchWarning
import com.moe.starflow.novel.translate.NovelChapterJobHost
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelEngineConfig
import com.moe.starflow.novel.translate.NovelImageHost
import com.moe.starflow.novel.translate.NovelImagePipeline
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual
import com.moe.starflow.novel.translate.NovelOversizeConfirmer
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.NovelParagraphType
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode
import com.moe.starflow.novel.translate.NovelQueuePhase
import com.moe.starflow.novel.translate.NovelQueueState
import com.moe.starflow.novel.translate.NovelTranslationEngine
import com.moe.starflow.novel.translate.NovelTranslationHub
import com.moe.starflow.novel.translate.NovelTranslationQueue
import com.moe.starflow.novel.translate.TranslationTextApiAdapter
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.ui.reader.ReaderTranslateModePopup
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.ReaderTranslationNotify
import com.moe.starflow.translate.batch.TranslationJobService
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.TranslationConcurrency
import com.moe.starflow.utils.ReaderDialogs
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import translationapi.TranslatorFactory
import kotlin.coroutines.resume
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

        /**
         * 通知栏点进来时带的目标章（章节后台翻译的通知用）。
         * ⚠️ 由 `TranslationJobService` 传入；阅读器若还没接这个 extra，就退化成"按断点续读"。
         */
        const val EXTRA_CHAPTER_INDEX = "chapter_index"

        /** 上下 UI 显隐的淡入淡出时长（与漫画一致）。 */
        private const val CHROME_FADE_MS = 160L

        /** 翻译按钮双击判定窗口（**与漫画同一个值**，语义也一致）。 */
        private const val DOUBLE_CLICK_MS = 300L

        /** 视口尺寸变化后的重排防抖（尺寸是连续事件，见 [scheduleRepaginate]）。 */
        private const val REPAGINATE_DEBOUNCE_MS = 180L

        /** 快速章节不闪等待态；长章节超过此时间后展示进度动画。 */
        private const val CHAPTER_LOADING_INDICATOR_DELAY_MS = 180L

        /** 滚动模式按比例跳转时最多补齐几次（估算值会漂、一次 scrollBy 未必吃到目标，见 [seekScrollTo]）。 */
        private const val MAX_SEEK_PASSES = 60

        /**
         * 菜单按钮补充点击区向外扩多少 dp（见 [inMenuZone]）。
         *
         * ⚠️ **这是替代「右上角 28%×22%」那套屏幕百分比的**：那块区域十倍于按钮本身，
         * 用户在按钮下方老远点一下也会弹出面板（用户口径 2026-10-04）。
         */
        private const val MENU_ZONE_PAD_DP = 6f

        private const val KEY_ROTATE = "reader_rotate_mode"
        private const val KEY_AUTO_TURN = "reader_auto_turn"
        private const val KEY_INTERVAL = "reader_auto_turn_interval"

        private const val STATE_CURRENT_CHAPTER = "novel_state_chapter"
        private const val STATE_ANCHOR_PARA = "novel_state_anchor_para"
        private const val STATE_ANCHOR_FRACTION = "novel_state_anchor_fraction"
        private const val STATE_FROM_SETTINGS = "novel_state_from_settings"

        fun intent(context: Context, novelId: Long): Intent =
            Intent(context, NovelReaderActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId).apply {
                val session = com.moe.starflow.library.LibrarySessions.current(context)
                putExtra("library_location", session.locationId)
                putExtra("library_book_uuid", session.database.catalog().book("novel", novelId)?.uuid)
            }
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

    /**
     * 手动「翻一批」/ 长按多选翻译的在途任务。
     *
     * ⚠️ **必须真的赋值**（这里曾经是个只被 cancel、从不被赋值的死字段）：这两条路
     * 都会一直持有 `OcrLock`（别的翻译因此被挡住）并且跑完后调 `showOverlay`——
     * 那是**进程级系统窗口**，用户已经退出阅读器去别的应用了，芯片还会盖上去。
     * 退出/切后台要能取消它们。
     *
     * ⚠️ 章批量任务（原来那个 `chapterSweepJob`）**已经挪到应用级宿主**
     * （`novel/translate/NovelChapterJobHost.kt`）：它不受 Activity 生命周期影响，
     * 退出阅读器也继续翻，进度在前台服务通知栏里。
     */
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

    /** 手动空闲时暂缓第一次单击，以便区分单击翻一批与双击开启自动模式。 */
    private var pendingManualTranslateClick: Runnable? = null
    private var pendingManualTranslateClickMs = 0L
    private var translateModePopup: PopupWindow? = null
    /** 防止旧的取消清理结束后覆盖用户刚选的新模式。 */
    private var translateModeChangeId = 0L
    private var lastInteractionMs = 0L
    private var rotateMode = 0

    /** 自动翻译队列（手动模式下不起）。惰性创建：它要用到翻译引擎。 */
    private var queue: NovelTranslationQueue? = null

    /** 队列状态的观察者。队列被丢弃重建时要先 cancel，否则旧收集器一直挂在 lifecycleScope 上。 */
    private var queueObserverJob: Job? = null

    /**
     * **章批量翻译的应用级宿主**（`NovelChapterJobHost`，按 novelId 缓存）。
     *
     * ⚠️ 「翻译本章」不再跑在 `lifecycleScope` 上：整章任务由宿主在**应用级 scope** 里跑，
     * 退出阅读器也继续（进度走前台服务通知栏），这里只负责取任务状态 + 把结果刷上屏。
     */
    private var chapterHost: NovelChapterJobHost? = null

    /** 宿主任务收尾的监听（显示「本章翻完」提示）；退出阅读器要 remove。 */
    private var jobFinishedListener: ((Int, Int, Int, Boolean) -> Unit)? = null

    /**
     * 单批超长预警的「同一轮只打扰一次」记账（用户拒过一遍 → 本轮后续超长批静默跳过）。
     * 与宿主里那份是**两份**：它们分别对应自动/增量队列与章任务两条路。
     */
    private val oversizeConfirmer = NovelOversizeConfirmer { estimate, threshold ->
        confirmOversizeOnUi(estimate, threshold)
    }

    /** 模型/引擎配置变化的监听（见 [onEngineConfigChanged]）。 */
    private var enginePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 队列最近一次是按哪一章 / 哪个模式起的（重复调用时用来短路，省掉一次无谓重启）。 */
    private var queueChapter = -1
    /** 队列最近一次的模式（与 [queueChapter] 一起做「重复调用短路」）。 */
    private var queueMode: NovelTranslateMode? = null
    private var queueRunning = false

    /** 「已暂停」提示发过没有（`onStop` 与 `onDestroy` 的兜底二选一，别弹两次）。 */
    private var pausedToManualNotified = false

    /** `onDestroy` 兜底用：`onStop` 那一刻队列是否真的在跑。 */
    private var wasQueueRunning = false

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

    /** 本批**翻失败**的段（淡红高亮底）—— 由 [refreshFailedHighlight] 从失败明细同步。 */
    private var failedBatch: Set<Int> = emptySet()

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

    /** Loading a newer chapter/reflow supersedes the old full-chapter parse and pagination job. */
    private var chapterLoadJob: Job? = null
    private var chapterLoadingRunnable: Runnable? = null

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
    private var lastLayoutScrollLastItem = -1

    /** Visible adapter range last used to calculate the scroll-mode translate controls. */
    private var lastScrollChromeRange: IntRange? = null

    /**
     * **选择模式**（长按正文进入）里选中的段号，按选中顺序。
     *
     * 这是文本阅读器唯一的「重翻」入口（用户明确要求：不要像漫画那样让翻译按钮凭缓存命中
     * 变成重翻，而是长按多选任意段落来翻/重翻）。退出方式见 [exitSelection]。
     */
    private val selectedPara = linkedSetOf<Int>()
    private var selecting = false

    // ===== 图片翻译（epub 插图）=====

    /** 本章图片段的译文载荷（`段号 -> 载荷`）：长按翻译 / 队列翻完都会刷新它。 */
    private var imageTranslations: Map<Int, NovelImageTranslation> = emptyMap()

    /**
     * 每张图的显示态（`段号 -> 三态`）。**会话级内存态**，默认译文态。
     *
     * ⚠️ 刻意不落库（与漫画一致：翻页重置为译文）：它是"我这一刻想看什么"，
     * 不是译文本身的数据。落库会让下次打开阅读器仍停在上次随手切的"原图"上，
     * 用户以为译文丢了。
     */
    private val imageVisuals = HashMap<Int, NovelImageVisual>()

    /**
     * **上一次翻译完成的那几段**（用户口径 2026-10-04：「上一次翻译完成的段落，不管是对比还是
     * 原文模式，都要换一个背景色用来提示刚刚翻译的是哪一段，4 种翻译模式都要有这个机制」）。
     *
     * ⚠️ 只留**最后一次**（不是累积的集合）：语义是"刚刚翻的是哪几段"，用来在译文到达引起的
     * 重排之后重新对上阅读位置。它在原文/双语/译文三种显示模式下都画（高亮按**段号**算，
     * 与显示文本无关），4 种翻译模式（手动/自动/增量/整章）共用 [onUnitSettled] 这一条接线。
     * 换章时清空（下一条见 [loadChapter]），别的章翻完不影响当前章的高亮。
     */
    private var recentPara: Set<Int> = emptySet()

    /**
     * 当前**聚焦的那张插图**（长按选中）：只有它显示右下角那排按钮
     * （三态 / 重翻 / 删除，见 [NovelImageControls]）。点别处、翻页、切章都清掉。
     */
    private var focusedImagePara: Int? = null

    /** 在途的图片翻译（长按触发那次）；切章/退出要取消。⚠️ `@Volatile`：队列线程会读它判"这张图在翻"。 */
    @Volatile
    private var imageJob: Job? = null

    /**
     * **插图全屏查看器**（点一下插图打开，见 [NovelImageViewer]）。
     *
     * 同一时刻最多一个；`onStop`/`onDestroy` 必须收掉（Dialog 持有 window token，留着即泄漏）。
     */
    private var imageViewer: NovelImageViewer? = null

    /**
     * 「正在识别插图 第 i/N 张」的浮层文案；null = 没有插图在跑（用队列的默认文案）。
     *
     * 由 [NovelChapterTranslator] 的 `onImageProgress` 上报（批内插图串行，OCR 一张要几秒），
     * 是用户口径「提示系统要显示当前正在翻译的图片状态」的落点。
     */
    @Volatile
    private var imageStageText: String? = null

    /** 队列最近一次状态（插图进度上报时要据此判断"现在确实在翻"，用默认文案兜底）。 */
    @Volatile
    private var queueState: NovelQueueState = NovelQueueState()

    /** [imageStageText] 属于哪一章（`-1` = 没有；用来决定"整章芯片"要不要显示它）。 */
    @Volatile
    private var imageStageChapter: Int = -1

    /** 图片流水线（懒建：它自己要持有翻译引擎）。 */
    private var imagePipeline: NovelImagePipeline? = null

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
        libraryReadLease

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
        val startupLoadToken = loadToken
        val startupJob = lifecycleScope.launch {
        try {
            com.moe.starflow.library.LibrarySessions.prepare(this@NovelReaderActivity)
            com.moe.starflow.library.LibrarySessions.checkReaderIntent(this@NovelReaderActivity, intent, "novel", id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.moe.starflow.utils.AppNotice.show(this@NovelReaderActivity, e.message ?: "入口已失效"); finish(); return@launch
        }
        val foundBook = NovelStore.load(this@NovelReaderActivity).firstOrNull { it.id == id }
        if (foundBook == null) {
            LogCollector.w(TAG, "找不到书籍 id=$id，关闭阅读器")
            finish()
            return@launch
        }
        var loaded = foundBook
        book = loaded
        librarySourceLease = com.moe.starflow.library.LibrarySources.acquire(java.io.File(loaded.localRoot))

        // ⚠️ 传 `novel_import/` 作为插图落盘根目录：epub 里的插图在这一层解出来，
        // 落到独立的隐藏图片缓存目录（按 id + addedAt 隔离，删书时一起清掉）。
        // 不传的话图片段就永远是占位文本 `📷 [图片]`（阅读器是唯一需要真图的地方）
        repository = NovelChapterRepository(imagesRoot = NovelStorageDir.root(this@NovelReaderActivity))
        beginChapterLoading(startupLoadToken)
        val parsedChapters = repository.chaptersOf(loaded)
        if (parsedChapters.isNotEmpty()) {
            val actualChapterCount = parsedChapters.size
            val firstContentChapter = parsedChapters.indexOfFirst { !it.isAuxiliary }
                .takeIf { it >= 0 } ?: 0
            val savedChapterIsAuxiliary = parsedChapters
                .getOrNull(loaded.lastReadChapter)
                ?.isAuxiliary == true
            val needsPositionReset = loaded.lastReadChapter !in parsedChapters.indices || savedChapterIsAuxiliary
            if (actualChapterCount != loaded.chapterCount || needsPositionReset) {
                loaded = loaded.copy(
                    chapterCount = actualChapterCount,
                    lastReadChapter = if (needsPositionReset) firstContentChapter else loaded.lastReadChapter,
                    lastReadParaIndex = if (needsPositionReset) 0 else loaded.lastReadParaIndex,
                    lastReadPage = if (needsPositionReset) 0 else loaded.lastReadPage,
                )
                NovelStore.update(this@NovelReaderActivity, loaded)
                book = loaded
            }
            chapterCount = actualChapterCount
        } else {
            chapterCount = loaded.chapterCount
        }
        pageAdapter = NovelPageAdapter()
        scrollAdapter = NovelScrollAdapter()
        binding.novelPager.adapter = pageAdapter
        binding.novelScroll.layoutManager = LinearLayoutManager(this@NovelReaderActivity)
        binding.novelScroll.adapter = scrollAdapter

        chapterIndex = loaded.lastReadChapter.coerceAtLeast(0)
        // 断点续读只存了段号（既有格式），段内比例从段首起算
        pendingAnchor = NovelAnchor(loaded.lastReadParaIndex)

        // **通知栏点进来的目标章**：章节后台任务的通知带着 chapterIndex，直接定位到那一章
        // （没有这个 extra 就走断点续读。⚠️ `TranslationJobService` 已经在传它了。）
        intent.getIntExtra(EXTRA_CHAPTER_INDEX, -1)
            .takeIf { it in 0 until chapterCount }
            ?.let {
                chapterIndex = it
                // 目标章要从**章首**看：段内比例从第一段起算（断点续读的位置与这一章无关）
                pendingAnchor = NovelAnchor(0)
            }

        if (savedInstanceState != null) {
            returnedFromSettings = savedInstanceState.getBoolean(STATE_FROM_SETTINGS, false)
            chapterIndex = savedInstanceState.getInt(STATE_CURRENT_CHAPTER, chapterIndex)
            pendingAnchor = NovelAnchor(savedInstanceState.getInt(STATE_ANCHOR_PARA, pendingAnchor.paraIndex),
                savedInstanceState.getFloat(STATE_ANCHOR_FRACTION, pendingAnchor.fraction))
        }
        if (chapterIndex !in 0 until chapterCount ||
            parsedChapters.getOrNull(chapterIndex)?.isAuxiliary == true
        ) {
            chapterIndex = parsedChapters.indexOfFirst { !it.isAuxiliary }
                .takeIf { it >= 0 } ?: 0
            pendingAnchor = NovelAnchor(0)
        }

        // 章批量翻译的**应用级宿主**：同一本书永远只有一份（任务/进度不会分裂）
        chapterHost = NovelTranslationHub.hostFor(this@NovelReaderActivity, loaded).also { host ->
            host.bindUi(
                confirmOversize = { estimate, threshold -> confirmOversizeOnUi(estimate, threshold) },
                onChanged = { onChapterJobProgress() },
            )
        }
        // 任务收尾提示（失败/取消不喊完成；用户口径：取消时不报"本章完成 x/y"）
        val finished: (Int, Int, Int, Boolean) -> Unit = { ch, ok, total, cancelled ->
            runOnUiThread {
                if (!cancelled && ch == chapterIndex && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    showOverlayToast(getString(R.string.reader_translate_chapter_finished, ok, total), error = false)
                }
            }
        }
        jobFinishedListener = finished
        chapterHost?.addJobFinishedListener(finished)
        // 任务状态一变就把整份宿主状态推给面板（暂停/继续/取消也要立刻反映到卡片按钮上）
        lifecycleScope.launch { chapterHost?.chapterJobs?.collect { pushPanelState(); refreshChapterJobChips() } }

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyReaderMode()

        // ⚠️ **先渲染正文**：首次进入绝不能只有「译文读回来」那一条链才会加载内容 ——
        // 那条链在「本章一句译文都没有」时会判定「没变化」直接返回，结果第一次进阅读器
        // 一片空白，必须切一次章才显示（用户实测反馈）。
        loadChapter(chapterIndex, anchor = pendingAnchor)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onReaderStarted()

        lifecycleScope.launch {
            // 清理上次异常退出留下的「翻译中」标记（进程被杀时退出清理不会执行）
            runCatching { translator().resetStale(loaded) }
                .onFailure { LogCollector.w(TAG, "清理残留翻译状态失败", it) }
            refreshChapterStats()
            refreshTranslations()
        }
        }
        startupJob.invokeOnCompletion {
            if (!isDestroyed && ::binding.isInitialized) {
                binding.novelLoading.post { finishChapterLoading(startupLoadToken) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::repository.isInitialized) return
        onReaderStarted()
    }

    private fun onReaderStarted() {
        // 提示条紧贴顶部胶囊下沿（与漫画同一个口径：不重叠、也不留大缝）。
        // ⚠️ 只覆盖顶距、不动用户的 `Status_Position` 设置；离开阅读器恢复（onStop）。
        pushNoticeTopBelowPill()
        updateAutoTurn()
        // ⚠️ **整章任务在阅读器关掉时也能跑**，所以它的插图进度回调必须**进出阅读器时挂/摘**：
        // 不摘就是「宿主（应用级）持着已销毁的 Activity」—— 泄漏 + 往死掉的界面刷 UI。
        chapterHost?.onImageStage = { ch, i, n -> onImageStage(ch, i, n) }
        // 同一个理由：整章任务每落库一个请求单位也要**实时上屏**（否则插图要等整批跑完才出现）
        chapterHost?.onUnitSettled = { ch, paras -> onUnitSettled(ch, paras) }
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
        // 回到前台时**整章任务可能还在跑**（那是应用级宿主）→ 把它的进度芯片补回来
        // （`onStop` 的 `dismiss()` 会收走所有非 sticky 芯片，StateFlow 不会再发一次）
        refreshChapterJobChips()
    }

    /**
     * 把提示条推到**顶部胶囊正下方**（见 `TranslationStatusOverlay.setTopScreenY`）。
     * ⚠️ 按**实测**的胶囊下沿推，不猜固定 dp —— 章名换行会把胶囊撑高。
     * ⚠️ 单位是**屏幕坐标**（`getLocationOnScreen` + `height`）：浮层窗口被系统栏内缩过，
     * 用视图的 `pill.bottom` 会整体低一条系统栏（详见 `TranslationStatusOverlay.getViewParams`）。
     */
    private fun pushNoticeTopBelowPill() {
        val pill = binding.topPill
        val density = resources.displayMetrics.density
        val overlay = TranslationStatusOverlay.getInstance(this)
        // 胶囊还没布局 → 先用**全局固定值**；布局完成后「高度变化」回调会推一次实测值，此后一直用它。
        // ⚠️ 不做"每条提示都重新量"（用户口径 2026-10-03：顶距固定下来，别把通知系统搞复杂）。
        if (pill.height <= 0) {
            overlay.setTopScreenY((TranslationStatusOverlay.READER_TOP_OFFSET_DP * density).toInt())
            return
        }
        val loc = IntArray(2)
        pill.getLocationOnScreen(loc)
        // ⚠️ 单位是**屏幕坐标**（与浮层的 [TranslationStatusOverlay.setTopScreenY] 同口径）
        overlay.setTopScreenY(loc[1] + pill.height + (2 * density).toInt())
    }

    override fun onStop() {
        super.onStop()
        translateModeChangeId += 1
        dismissTranslateModePopup()
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        autoTurnJob?.cancel()
        autoTurnJob = null
        // 摘掉整章任务的插图进度回调（宿主是应用级的，挂着就是泄漏 + 往死界面刷 UI）
        chapterHost?.onImageStage = null
        chapterHost?.onUnitSettled = null
        imageStageText = null
        imageStageChapter = -1
        // ⚠️ 用户口径：「打开菜单或者**退出阅读器**自动暂停回退手动，但我发现这个暂停没有
        // app 的提示信息」—— 提示要在 `onStop` 里发（`isFinishing` = 真的在关掉），
        // 那一刻窗口还在、看得见；`onDestroy` 里弹的 Toast 常被系统直接吞掉。
        // ⚠️ 提示必须**最后**发：下面的 `TranslationStatusOverlay.dismiss()` 会清掉浮层上
        // 所有堆叠消息，先发就被它自己抹掉了。
        wasQueueRunning = queueRunning
        // ⚠️ **离开阅读器（返回键/关掉）与切后台（home/息屏）现在是**同一套**（用户口径 2026-10-04）**：
        // 「切后台只暂停什么意思？？且后台就直接关闭当前翻译进程，回退到手动模式！（本章翻译除外）」
        // —— 之前切后台只暂停 + 保留模式（2026-09 的旧口径），现在一律：
        // 停队列 + 中止在途请求 + **回退手动** + 给提示。回来想继续就自己再点一次。
        // ⚠️ **整章任务（应用级宿主）例外**：它继续跑（用户明确"本章翻译除外"）。
        val wasTranslating = NovelPanelStyle.translateMode(prefs) != NovelTranslateMode.MANUAL ||
            translationJob?.isActive == true || imageJob?.isActive == true
        val notifyPause = wasTranslating
        if (notifyPause) pausedToManualNotified = true
        // 手动翻一批 / 按选择翻也要停：它们持有 OcrLock（挡住别的翻译），跑完还会往
        // **进程级系统浮层**上写字 —— 用户已经在别的应用里了也会看到芯片
        translationJob?.cancel()
        translationJob = null
        // 长按翻译一张插图也在途（它持 OcrLock、几秒钟）：退出/切后台同样掐掉
        imageJob?.cancel()
        imageJob = null
        // 插图全屏查看器也要收（Dialog 持 window token，切后台还开着就是窗口泄漏）
        imageViewer?.dismiss()
        imageViewer = null
        focusedImagePara = null
        queue?.stop()
        queueRunning = false
        // 队列停了 → 通知栏那条"正在翻译"立刻收掉（后台也一样：队列已停，别留假进度）
        ReaderTranslationNotify.clear(this)
        // 把模式回退成手动：chip 显示与实际一致，要再翻就显式点一次。
        // ⚠️ 只回退**模式**，不动 `pendingAnchor`/阅读位置。
        if (wasTranslating) {
            NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.MANUAL)
        }
        // ⚠️ **章批量任务（应用级宿主）不受影响**：用户要求它「除非清后台否则继续翻」，
        // 进度走前台服务通知栏，回到阅读器时译文已经写库、`refreshTranslations` 直接读到。
        // 它的引擎是**另一个实例**，上面的 `queue.stop() → abortAll()` 掐不到它。
        TranslationStatusOverlay.getInstance(this@NovelReaderActivity).dismiss()
        // ⚠️ **不复位顶距**（用户口径 2026-10-03）：提示系统全应用固定在同一个位置，
        //    复位就等于回到"两条链路两个位置"。
        // `dismiss()` 已经把整章任务的进度芯片一起收走（`showRunning` 的不进 sticky）→ 句柄作废
        chapterJobChips.clear()
        // ⚠️ 提示放在 `dismiss()` **之后**：浮层是共享单例，dismiss 会清掉所有堆叠消息
        if (notifyPause) showPausedNotice(R.string.reader_translate_force_stopped_to_manual)
        // 离开阅读器清掉选择集：它是「长按多选」的临时状态，回到阅读器时不该还亮着
        exitSelection()
    }

    /**
     * 通知栏点进来的目标章（阅读器**已经开着**时走这里：服务的 Intent 带 SINGLE_TOP）。
     *
     * 少了它，点第二条通知会停在上一条通知那一章 —— 用户看到的是"点了没反应"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val current = book ?: return
        try { com.moe.starflow.library.LibrarySessions.checkReaderIntent(this, intent, "novel", current.id) }
        catch (e: Exception) { com.moe.starflow.utils.AppNotice.show(this, e.message ?: "入口已失效"); return }
        if (intent.getLongExtra(EXTRA_NOVEL_ID, current.id) != current.id) return
        setIntent(intent)
        val target = intent.getIntExtra(EXTRA_CHAPTER_INDEX, -1)
        if (target < 0 || target >= chapterCount) return
        if (target == chapterIndex) return
        gotoChapter(target)
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
        outState.putInt(STATE_ANCHOR_PARA, pendingAnchor.paraIndex)
        outState.putFloat(STATE_ANCHOR_FRACTION, pendingAnchor.fraction)
        outState.putBoolean(STATE_FROM_SETTINGS, returnedFromSettings)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 视口尺寸变了 → 页表必须按新尺寸重排，否则字会溢出或被裁。
        // 走防抖：旋转/分屏会连着一串布局回调，直接重排会重排很多遍
        applyBackground()
        // ⚠️ 旋转/分屏会改浮层窗口的系统栏内缩量，而胶囊高度可能没变 → 必须主动重推，
        //    否则浮层没有重新校准的机会，提示条会带旧内缩量偏上去压住胶囊（与漫画侧同一条）
        pushNoticeTopBelowPill()
        scheduleRepaginate()
    }

    override fun onDestroy() {
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        val closingLease = librarySourceLease
        librarySourceLease = null
        val finishingLifecycle = lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
        finishChapterLoading(loadToken)
        chapterLoadJob?.cancel()
        chapterLoadJob = null
        com.moe.starflow.library.LibrarySessions.release {
            finishingLifecycle?.join()
            try { closingLease?.close() } finally { libraryReadLease.close() }
        }
        queue?.stop()
        queueObserverJob?.cancel()
        enginePrefsListener?.let {
            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        enginePrefsListener = null
        translationJob?.cancel()
        // ⚠️ 章任务**不取消**（应用级宿主继续跑）：只解绑 UI + 撤监听，让宿主能在"没有 UI
        // 且没有任务"时被回收。译文已经写库，回到阅读器直接读得到。
        jobFinishedListener?.let { chapterHost?.removeJobFinishedListener(it) }
        jobFinishedListener = null
        chapterHost?.unbindUi()
        chapterHost = null
        // 兜底：`onStop` 没走到时（进程被杀之类）也别把查看器窗口留在系统里
        imageViewer?.dismiss()
        imageViewer = null
        book?.let { NovelTranslationHub.releaseIfIdle(it.id, it.translationKey) }
        if (::repository.isInitialized) repository.evictAll()
        // ⚠️ 状态浮层是**进程级 TYPE_APPLICATION_OVERLAY 系统窗口**，不清会挂在桌面/别的应用上
        TranslationStatusOverlay.getInstance(this@NovelReaderActivity).dismiss()
        // 通知栏那条阅读器进度同理：随队列一起收（`onStop` 已收过一次，这里兜底）
        ReaderTranslationNotify.clear(this)
        // 兜底：`onStop` 没发过（进程被杀 / 没走 onStop 的路径）而当时确实在自动翻 → 补一次。
        // ⚠️ 文案是「强制退出，回退手动」—— 切后台现在也是**终止**（用户口径 2026-10-04），
        // 不再是"暂停、回来继续"。`onDestroy` 里的 Toast 常被系统吞掉，正常路径靠 onStop 那条。
        if (!pausedToManualNotified &&
            NovelPanelStyle.translateMode(prefs) != NovelTranslateMode.MANUAL &&
            wasQueueRunning
        ) {
            NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.MANUAL)
            UiUtils.showToast(this, getString(R.string.reader_translate_force_stopped_to_manual))
        }
        super.onDestroy()
    }

    // ===== 依赖 =====

    private fun db() = com.moe.starflow.library.LibrarySessions.forLocation(this, book?.libraryLocationId.orEmpty())

    private fun translator() = NovelChapterTranslator(
        dao = db().novelParagraphTranslationDao(),
        engine = NovelTranslationEngine(
            TranslationTextApiAdapter(
                TranslatorFactory.createForText(this, CustomPreference.getInstance(this))
                    ?: throw IllegalStateException("no engine")
            )
        ),
        splitVersion = NovelParagraphSplitter.SPLIT_VERSION,
        // 单批超长预警：阈值现读设置，确认弹窗走宿主（**没 UI 时放行**，见 NovelBatchWarnGate）
        warnGate = NovelBatchWarnGate(
            threshold = { NovelPanelStyle.batchWarnThreshold(prefs) },
            confirm = { estimate, threshold -> oversizeConfirmer.confirm(estimate, threshold) },
        ),
        // 图片段：**开关关着时返回 null**（那时图片段按设计不翻译）。
        // ⚠️ 走 lambda：这份 translator 是按值缓存的，开关随时会变。
        imageHost = { imageHostOrNull() },
        // 批内插图串行 OCR，一张几秒：浮层要说得出"现在是第几张"（用户口径 2026-10-04）
        onImageProgress = { ch, index, total -> onImageStage(ch, index, total) },
        // 一个请求单位落库就上报：**先完成先显示**（不等整批跑完），同时点亮"刚翻完的是哪几段"
        onUnitSettled = { ch, paras -> onUnitSettled(ch, paras) },
    )

    /**
     * 插图进度上报（在翻译协程里被调，**可能在 IO 线程**）→ 只写一个 `@Volatile` 文案 +
     * 在主线程刷新浮层。`index = 0` = 这一批的插图跑完了（清文案）。
     *
     * ⚠️ 参数名**不能叫 `chapterIndex`**（会遮蔽 Activity 自己的那个同名属性，
     * `chapter != chapterIndex` 就永远是 false —— 这种自己跟自己比的写法编译器不报错）。
     */
    private fun onImageStage(chapter: Int, index: Int, total: Int) {
        if (chapter != chapterIndex) return
        imageStageText = if (index <= 0 || total <= 0) {
            null
        } else {
            getString(R.string.novel_image_translating_progress, index, total)
        }
        imageStageChapter = if (imageStageText == null) -1 else chapter
        runOnUiThread {
            val st = queueState
            if (st.phase == NovelQueuePhase.TRANSLATING) {
                showOverlay(imageStageText ?: queuePositionText(st), autoDismiss = false)
            }
            // 整章任务那条路的浮层芯片也要跟上（否则用户在整章翻译时只看到"x/y 批"，
            // 不知道卡在哪张图上）
            refreshChapterJobChips()
            // 通知栏同一条状态（前台队列在跑时才会真的刷）
            updateReaderTranslationNotify(queueState)
        }
    }

    /**
     * **一个请求单位翻完并落库**（`(chapter, 那几段)`）—— 先完成先显示 + 点亮"刚翻的是哪几段"。
     *
     * 用户口径 2026-10-04：「当前多张图片的结果**一起出现**，应该是**先 ocr 完成返回的就先显示结果**，
     * 而不是非要等到整个批次都完成再一起显示」+「上一次翻译完成的段落（不管是对比还是原文模式）
     * 都要**换一个背景色**用来提示刚刚翻译的是哪一段，4 种翻译模式都要有」。
     *
     * 这个回调被翻译器在**每落库一个请求单位**时调用（见 `NovelChapterTranslator.onUnitSettled`），
     * 而翻译器是**四条路共用**的：手动一批 / 自动 / 增量（阅读器队列那份实例）、
     * 整章任务（`NovelChapterJobHost` 那份实例，经它转一手）—— 所以一处接线四种模式全生效。
     *
     * ⚠️ 可能在**任意线程**被调（队列的 `Dispatchers.IO`、或章任务的应用级 scope）→ 只写内存态，
     * UI 与库读取一律切主线程。
     * ⚠️ 章节号不匹配就整个丢掉（整章任务可能在翻**别的章**；那章的译文由 `loadChapter` 负责）。
     */
    private fun onUnitSettled(chapter: Int, paras: List<Int>) {
        if (chapter != chapterIndex || paras.isEmpty()) return
        // 高亮只留**最后一次**翻完的那几个段（"刚刚翻译的是哪一段"），随下一次落库自然替换
        recentPara = paras.toSet()
        runOnUiThread {
            refreshRecentHighlight()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runOnUiThread
            lifecycleScope.launch { applyUnitResult(chapter, paras) }
        }
    }

    /**
     * 把刚落库的那几段**读回来上屏**。
     *
     * ⚠️ **插图走轻量路径**（只重读载荷 + 重画）：插图段的高度由图片自身尺寸定，与"有没有译文"
     * 无关 —— 走 `refreshTranslations → loadChapter` 会白白重排整章，还会把阅读位置掀一下。
     * 文本段则相反：译文比原文长，**必须重排**并回到同一锚点（那是既有的 `refreshTranslations`）。
     */
    private suspend fun applyUnitResult(chapter: Int, paras: List<Int>) {
        val b = book ?: return
        if (paras.all { onImageParagraph(it) }) {
            val map = runCatching { translator().loadImageTranslations(b, chapter) }
                .getOrDefault(emptyMap())
            // 读库期间用户可能已经换章：那次结果作废（新章进来时 loadChapter 自己会再取一次）
            if (chapter != chapterIndex) return
            imageTranslations = map
            imageVisuals.keys.retainAll(map.keys)
            refreshImageChrome()
            // ⚠️ 浮层组也要跟着重算：插图算「可翻单位」，一张图翻完可能就让这一页"没有待翻段了"
            // （文本那条路是靠 `loadChapter` 末尾那次刷新，这条轻量路径得自己补）
            refreshTranslationChrome()
        } else {
            refreshTranslations()
        }
    }

    /** 「刚翻完」的高亮刷新（两种阅读模式各自的视图都要落到）。 */
    private fun refreshRecentHighlight() {
        pageAdapter.setRecentBatch(recentPara)
        scrollAdapter.setRecentBatch(recentPara)
    }

    /**
     * 图片翻译宿主（阅读器侧）：把"图在哪"与"怎么 OCR + 翻译"接给 [NovelChapterTranslator]。
     *
     * 返回 null = 面板上的「同时翻译图片」关着 → 图片段一个都不碰（老行为）。
     */
    private fun imageHostOrNull(): NovelImageHost? {
        if (!NovelPanelStyle.translateImages(prefs)) return null
        val pipeline = imagePipeline ?: NovelImagePipeline(this) {
            runCatching { TranslatorFactory.createForText(this, CustomPreference.getInstance(this)) }.getOrNull()
        }.also { imagePipeline = it }
        return NovelImageHost(
            fileOf = { b, ch, para -> repository.imagesOf(b, ch)[para]?.file },
            translate = { file, src, tgt -> pipeline.translate(file, src, tgt) },
        )
    }

    // ===== chrome（与漫画逐项对齐） =====

    private fun setupOverlays() {
        // 返回键：选择模式 / **插图编辑模式**下先退出它们（与「点空白处退出」同一件事），
        // 否则才退出阅读器。`isEnabled=false` → 转发一次 → 再打开，是为了把"退出阅读器"
        // 这个默认行为交回系统，不自己 finish（自绘返回会漏掉系统的一些收尾）。
        //
        // ⚠️ 插图编辑模式这条是用户报的（2026-10-04）：「选中文本可以通过返回退出编辑，
        // 为什么选中图片返回直接就退出阅读器？」—— 两处理必须是同一套心智。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) {
                    exitSelection()
                    return
                }
                if (focusedImagePara != null) {
                    // ⚠️ 只退出编辑模式，**不打断在途翻译**（见 onLongPressImage 的说明）
                    focusedImagePara = null
                    refreshImageChrome()
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
        // ⚠️ 点整颗胶囊（不是只点章名那半）：页码那半也在这颗胶囊里，点上去同样该开目录
        binding.topPill.setOnClickListener { openToc() }
        // 提示条紧贴胶囊下沿 —— 章名换行会把胶囊撑高，高度一变就重推（与漫画同一接线）。
        binding.topPill.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, ob ->
            if (b != ob) pushNoticeTopBelowPill()
        }
        binding.btnTranslate.setOnClickListener { onTranslateButtonClick() }
        binding.btnTranslate.setOnLongClickListener {
            showTranslateModePopup()
            true
        }
        binding.btnToggleTranslate.setOnClickListener { cycleDisplayMode() }
        binding.btnFailTranslate.setOnClickListener { showFailBubble() }
        binding.btnClearTranslate.setOnClickListener { confirmClearSelectedTranslations() }

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
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                cancelPendingManualTranslateClick()
                lastTranslateClickMs = 0L
                updateFromScroll()
            }
        })
        // ⚠️ **程序化跳转不派发 onScrolled**：换章/续读走的是 `scrollToPosition`，
        // 布局跑完才知道自己滚到了哪 —— 少了这一句，从中间续读长章节时进度条会一直停在 0
        // 直到用户手动滑一下（用户报的「进度条失效」有这一半）。
        // ⚠️ 只在「首可见段真的变了」时才补刷：`updateChapterTocLabel` 里会 setText，
        // 那会 requestLayout → 又回调到这里 —— 不设这个闸门就是每帧一次布局的死循环。
        binding.novelScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isScrollMode()) return@addOnLayoutChangeListener
            val first = firstVisibleScrollItem()
            val last = lastVisibleScrollItem()
            if (first == lastLayoutScrollItem && last == lastLayoutScrollLastItem) return@addOnLayoutChangeListener
            lastLayoutScrollItem = first
            lastLayoutScrollLastItem = last
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
        trackChapterEdgeSwipe(e)
        return false
    }

    // ── 「滑到章节边界」的提示（用户口径：点击与滑动都要提示）──

    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private var edgeNotified = false
    private val edgeSwipeMinPx by lazy { 24 * resources.displayMetrics.density }

    /**
     * 观察拖动：方向指向下一章/上一章、而当前**已经在边界上**（或已经滚到底）→ 提示一次。
     *
     * ⚠️ 只观察、**绝不消费**事件（[onReaderTouch] 恒返回 false）：消费掉会破坏翻页/滚动手势。
     * ⚠️ 每次手势最多提示一条（`edgeNotified`），不然拖动过程中会刷一屏。
     */
    private fun trackChapterEdgeSwipe(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeStartX = e.x
                edgeStartY = e.y
                edgeNotified = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeNotified) return
                val dx = e.x - edgeStartX
                val dy = e.y - edgeStartY
                val forward: Boolean
                val atEdge: Boolean
                if (isScrollMode()) {
                    if (kotlin.math.abs(dy) < edgeSwipeMinPx) return
                    forward = dy < 0
                    atEdge = if (forward) !binding.novelScroll.canScrollVertically(1)
                    else !binding.novelScroll.canScrollVertically(-1)
                } else {
                    val d = if (isVerticalMode()) dy else dx
                    if (kotlin.math.abs(d) < edgeSwipeMinPx) return
                    forward = d < 0
                    val last = content?.pages?.lastIndex ?: return
                    atEdge = if (forward) currentPage() >= last else currentPage() <= 0
                }
                if (!atEdge) return
                edgeNotified = true
                showChapterEdge(if (forward) 1 else -1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeNotified = false
        }
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
            cancelPendingManualTranslateClick()
            lastTranslateClickMs = 0L
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
        listOf(binding.btnBack, binding.btnMenu, binding.topPill, binding.bottomProgress)

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
        dismissTranslateModePopup()
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        chromeHidden = !chromeHidden
        lastInteractionMs = SystemClock.elapsedRealtime()
        applyChromeVisibility()
    }

    // ===== 点击分区 =====
    //
    // ⚠️ **与漫画那套"右上角 28%×22% = 菜单 + 正中一格 = 显隐 UI"已经刻意分叉**（2026-10-04 真机反馈）：
    // ① 菜单角改成**贴着菜单按钮**算（[inMenuZone]）—— 屏幕百分比那块是按钮的十倍以上，
    //    在按钮下方老远点一下也弹面板，还吃掉本该翻页/点插图的点击；
    // ② **插图优先于中格** —— 中格会把落在里面的插图整个吃掉（整张图正好在中格时永远打不开，
    //    边缘的图也只能点到露出来的两条），用户口径是"整张图片都该能点开"。
    // 漫画那边没被反馈过、且是另一套内容（位图页没有"插图段落"），暂不动；要统一时两边一起改。

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
        // ── 分区判定的**顺序就是优先级**（2026-10-04 真机反馈后重排，别再改回去）──
        // ① 编辑模式按钮（用户刚长按唤出来的东西，命中判据最精确，先给它）
        // ② chrome 隐藏时：**任何一次点击先把 UI 唤回来**（否则整页都是插图的章里，
        //    用户把 UI 藏起来后就再也调不出来 —— 这是"点图开查看器"必然带来的新口子）
        // ③ 菜单按钮那一小块（见 [inMenuZone]，**不再是右上角一大片**）
        // ④ 点插图 = 看全图（**整张图都是触发范围**）
        // ⑤ 九宫格：中格显隐 UI、上下/左右半屏翻页
        imageControlAt(x, y)?.let { button ->
            val para = focusedImagePara
            if (para != null) {
                when (button) {
                    NovelImageButton.TRANSLATE -> translateImageNow(para)
                    NovelImageButton.TOGGLE -> cycleImageVisual(para)
                    NovelImageButton.DELETE -> deleteImageTranslation(para)
                }
                return
            }
        }
        if (chromeHidden) {
            toggleChrome()
            return
        }
        if (inMenuZone(x, y)) {
            showMenu()
            return
        }
        // **点插图本身 = 放大查看全图**（用户口径 2026-10-04：「点击图片应该要可以放大查看全图」）。
        // ⚠️ 必须排在**中格（显隐 UI）之前**（用户口径 2026-10-04 真机反馈：「滚动模式下图片点击打开的
        // 位置有问题，触发范围应该都是整张图片才对；有的图片打不开，必须在很刁钻的位置才能打开」）：
        // 中格占屏幕正中 1/9，落在中格里的插图**一个点都点不到**（整张图正好在中格时永远打不开），
        // 边缘的图也只能点到露在中格外的上下两条 —— 这就是"很刁钻的位置"。
        val tapped = paragraphAt(x, y)
        if (tapped != null && onImageParagraph(tapped)) {
            openImageViewer(tapped)
            return
        }
        // 点了别的段（不是正在编辑的那张图）→ **退出编辑模式**（按钮跟着收掉），再按九宫格处理。
        // ⚠️ 点正在编辑的那张图本身已经在上面被"打开查看器"接管了（查看器自带那排按钮）。
        if (focusedImagePara != null) {
            focusedImagePara = null
            refreshImageChrome()
        }
        if (inChromeToggleZone(x, y, w, h)) {
            toggleChrome()
            return
        }
        when {
            // 连续滚动：点上下半屏 = 上一段 / 下一段
            isScrollMode() -> scrollByParagraph(if (y >= h / 2f) 1 else -1)
            // 上下翻页：点下半屏 = 下一页（与漫画竖排模式同一套判定）
            isVerticalMode() -> turnPage(if (y >= h / 2f) 1 else -1)
            // 左右翻页：点右半屏 = 下一页
            else -> turnPage(if (x >= w / 2f) 1 else -1)
        }
    }

    /**
     * **菜单按钮的补充点击区** = 按钮矩形向外扩 [MENU_ZONE_PAD_DP]。
     *
     * ⚠️ **不许再用屏幕百分比**（老实现是"右上角 28%×22%"）：那块区域是按钮本身的十倍以上，
     * 在按钮**下方老远**点一下也会弹出面板（用户口径 2026-10-04：「点击屏幕右上角（菜单按钮的
     * 下方很大一片区域）都可以打开底部菜单」），还顺手吃掉本该翻页/点插图的点击。
     * 现在贴着按钮算，尺寸跟着按钮走（`btn_menu` 是 40dp 见方 → 有效区约 52dp）。
     *
     * ⚠️ 按钮本身是**可点视图**（`btnMenu.setOnClickListener`），点在按钮上根本到不了这里 ——
     * 这一块纯粹是给"按在按钮边缘/旁边一点点"兜底的。chrome 隐藏时按钮是 `GONE`，直接不认。
     */
    private fun inMenuZone(x: Float, y: Float): Boolean {
        val v = binding.btnMenu
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return false
        val target: View = if (isScrollMode()) binding.novelScroll else binding.novelPager
        val vLoc = IntArray(2)
        v.getLocationOnScreen(vLoc)
        val tLoc = IntArray(2)
        target.getLocationOnScreen(tLoc)
        val pad = MENU_ZONE_PAD_DP * resources.displayMetrics.density
        val left = vLoc[0] - tLoc[0] - pad
        val top = vLoc[1] - tLoc[1] - pad
        return x >= left && x <= left + v.width + 2 * pad &&
            y >= top && y <= top + v.height + 2 * pad
    }

    /**
     * 九宫格**中格** = 显隐上下 UI（Koto 式）。
     *
     * ⚠️ 它的判定必须排在**插图之后**（见 [handleTap] 的顺序说明）：中格会把落在里面的插图
     * 整个吃掉，用户点图"没反应"。正文（文字段）照旧走它。
     */
    private fun inChromeToggleZone(x: Float, y: Float, w: Float, h: Float): Boolean =
        x >= w / 3f && x < w * 2f / 3f && y >= h / 3f && y < h * 2f / 3f

    // ===== 选择模式（长按多选 → 翻译 / 重翻） =====

    /**
     * 长按正文 = 进入选择模式并选中手指下那一段。
     *
     * ⚠️ 文本翻译的重翻**只有这一个入口**（用户明确要求）：不要学漫画用「缓存命中 → 按钮变重翻」，
     * 那样"点一下会发生什么"取决于看不见的译文状态。
     */
    private fun handleLongPress(x: Float, y: Float) {
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        lastInteractionMs = SystemClock.elapsedRealtime()
        val para = paragraphAt(x, y)
        if (para == null) return
        // ── 插图：长按 = 翻这张图（用户口径 2026-10）──
        // ⚠️ 先判图再判"可翻译段"：插图段按设计 `isTranslatable() == false`，
        // 顺序反了就会对着插图弹「这段不能翻译」。
        if (onImageParagraph(para)) {
            onLongPressImage(para)
            return
        }
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

    /** 这一段是不是"有图可操作的插图段"（图片翻译功能是否可用与开关无关：长按是显式指令）。 */
    private fun onImageParagraph(paraIndex: Int): Boolean =
        content?.images?.containsKey(paraIndex) == true

    /**
     * 长按插图：**只进编辑模式**（把按钮亮出来），**不自动开翻**。
     *
     * 用户口径（2026-10-04 明确纠正）：「长按图片 → 出现翻译按钮，**点击翻译才会 OCR 翻译**」
     * —— 之前那版长按即开跑，用户还没看清按钮在哪，OCR 与翻译请求就已经发出去了
     * （几秒 + 一次请求，还可能点错图）。
     *
     * ⚠️ 与文本段不同，插图**不进选择模式**：一张图就是一个独立单位，多选没有意义。
     */
    private fun onLongPressImage(paraIndex: Int) {
        exitSelection()
        focusedImagePara = paraIndex
        refreshImageChrome()
        showOverlayToast(getString(R.string.novel_image_edit_hint), error = false)
    }

    /**
     * 用户长按手动翻译的那张插图（在途）→ 让队列/其它入口躲开它。
     *
     * ⚠️ 库里那张图这时还是 IDLE，不排除的话自动/增量会**再翻一遍同一张图**
     * （`OcrLock` 只保证不并发，不保证不重复；白花一次 OCR + 一次请求）。
     */
    private fun manualImageInFlight(chapter: Int): Set<Int> =
        if (imageJob?.isActive == true && chapter == manualImageChapter) {
            manualImagePara?.let { setOf(it) } ?: emptySet()
        } else {
            emptySet()
        }

    /** 正在手动翻的那张图在**哪一章哪一段**（[manualImageInFlight] 用）。 */
    @Volatile
    private var manualImagePara: Int? = null

    @Volatile
    private var manualImageChapter: Int = -1

    /**
     * 冲突守卫：这张图**正被别处翻**（整章任务 / 前台队列 / 手动那张）时，不要再发一次。
     *
     * 用户口径里的"冲突操作"就在这类地方：同一张图被两路同时翻 → OCR 两遍、请求两遍、
     * 后落库的那次覆盖前一次（用户看到"翻了两次、结果还闪一下"）。
     */
    private fun imageInFlightElsewhere(paraIndex: Int): Boolean {
        if (chapterHost?.inFlightAndWaitingParas(chapterIndex)?.contains(paraIndex) == true) return true
        val st = queueState
        if ((st.phase == NovelQueuePhase.TRANSLATING || st.phase == NovelQueuePhase.WAITING_LOCK) &&
            paraIndex in st.batchParaIndexes
        ) {
            return true
        }
        return manualImageInFlight(chapterIndex).contains(paraIndex)
    }

    /**
     * 翻（或重翻）**一张插图**：`OCR → 翻译 → 落库 → 上屏`。
     *
     * ⚠️ 这是**用户对这张图的显式指令**（编辑模式里点「翻译」按钮），不受面板
     * 「同时翻译图片」开关限制（那个开关管的是自动/增量/整章批量翻译要不要带上插图）。
     */
    private fun translateImageNow(paraIndex: Int) {
        val b = book ?: return
        val chapter = chapterIndex
        val file = content?.images?.get(paraIndex)?.file ?: return
        // ⚠️ 同一张图已经在别处翻（整章/队列/手动上一张）→ 不重复发，告诉用户等一下
        if (imageInFlightElsewhere(paraIndex)) {
            showOverlayToast(getString(R.string.novel_image_already_translating), error = false)
            return
        }
        if (runCatching { TranslatorFactory.createForText(this, CustomPreference.getInstance(this)) }.getOrNull() == null) {
            showOverlayToast(getString(R.string.novel_image_need_config), error = true)
            return
        }
        val pipeline = imagePipeline ?: NovelImagePipeline(this) {
            runCatching { TranslatorFactory.createForText(this, CustomPreference.getInstance(this)) }.getOrNull()
        }.also { imagePipeline = it }

        imageJob?.cancel()
        manualImagePara = paraIndex
        manualImageChapter = chapter
        // ⚠️ 用**可堆叠**的 `showRunning` 而不是 `showOverlay`：后者是"替换顶部那一条"，
        // 而这时队列很可能正在显示「正在翻译 · 第 X/Y 页」，替换会把它抹掉
        // （用户口径：通知系统本来就支持同时显示多条）
        val chip = if (canUseOverlay()) {
            TranslationStatusOverlay.getInstance(this).showRunning(getString(R.string.novel_image_translating))
        } else {
            0L
        }
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome = try {
                    pipeline.translate(file, ocrLanguageOf(sourceLang()), targetLang())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogCollector.e(TAG, "插图翻译失败", e)
                    showOverlayToast(getString(R.string.novel_image_failed, e.message.orEmpty()), error = true)
                    return@launch
                }
                if (!isActive) return@launch
                if (outcome.payload == null) {
                    // 取消了（error 为 null）就不报错；真失败带原始原因。
                    outcome.error?.let {
                        showOverlayToast(getString(R.string.novel_image_failed, it), error = true)
                    }
                    return@launch
                }
                val payload = outcome.payload
                // 落库（走翻译器那一份，保证与批量路径写的是同一套字段/状态）
                try {
                    translator().saveImageTranslation(
                        book = b,
                        chapterIndex = chapter,
                        paraIndex = paraIndex,
                        payload = payload,
                        sourceLang = sourceLang(),
                        targetLang = targetLang(),
                        translatorName = "novel",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    showOverlayToast(getString(R.string.novel_image_failed, e.message.orEmpty()), error = true)
                    return@launch
                }
                if (!isActive) return@launch
                if (chapter == chapterIndex) {
                    imageTranslations = imageTranslations + (paraIndex to payload)
                    imageVisuals[paraIndex] = NovelImageVisual.TRANSLATED
                    focusedImagePara = paraIndex
                    // 不 submit 整章内容，避免阅读位置跳动。
                    refreshImageChrome()
                }
                refreshChapterStats()
                showOverlayToast(getString(R.string.novel_image_translated, payload.blocks.size), error = false)
            } finally {
                if (imageJob === coroutineContext[Job]) {
                    imageJob = null
                    manualImagePara = null
                    manualImageChapter = -1
                    refreshTranslationChrome()
                }
                if (chip != 0L) TranslationStatusOverlay.getInstance(this@NovelReaderActivity).removeRunning(chip)
            }
        }
        imageJob = job
        job.start()
    }

    /** 三态循环（译文 → 识别原文 → 原图 → 译文）。 */
    private fun cycleImageVisual(paraIndex: Int) {
        val next = imageVisualOf(paraIndex).next()
        imageVisuals[paraIndex] = next
        refreshImageChrome()
        showOverlayToast(getString(visualLabelRes(next)), error = false)
    }

    /**
     * 打开**插图全屏查看器**（点一下正文里的插图）。
     *
     * ⚠️ 查看器里的三颗按钮**不另写一份逻辑**：它们回调到这里，走的还是图上那排按钮同一条路
     * （[translateImageNow] / [cycleImageVisual] / [deleteImageTranslation]）—— 在途去重、
     * 落库、上屏、提示都只有一份实现。
     * ⚠️ 与「同时翻译图片」开关**无关**：那开关管的是批量翻译要不要带上插图，
     * 而"点开看大图 / 对这张图下指令"是用户的显式动作（与长按同一口径）。
     */
    private fun openImageViewer(paraIndex: Int) {
        if (content?.images?.get(paraIndex) == null) return
        // 已经开着就先收掉（收尾会把自己那份引用清空），避免叠出第二个窗口
        imageViewer?.dismiss()
        imageViewer = NovelImageViewer(
            activity = this,
            paraIndex = paraIndex,
            host = object : NovelImageViewer.Host {
                override fun imageOf(paraIndex: Int): NovelImage? = content?.images?.get(paraIndex)
                override fun visualOf(paraIndex: Int): NovelImageVisual = imageVisualOf(paraIndex)
                override fun payloadOf(paraIndex: Int): NovelImageTranslation? = imageTranslations[paraIndex]
                override fun onTranslateImage(paraIndex: Int) = translateImageNow(paraIndex)
                override fun onToggleImageVisual(paraIndex: Int) = cycleImageVisual(paraIndex)
                override fun onDeleteImageTranslation(paraIndex: Int) = deleteImageTranslation(paraIndex)
                override fun onViewerClosed() {
                    imageViewer = null
                }
            },
        )
        imageViewer?.show()
    }

    /** 删除这一张图的译文（回落原图；与文本段的"清除译文"同一语义）。 */
    private fun deleteImageTranslation(paraIndex: Int) {
        val b = book ?: return
        val chapter = chapterIndex
        if (!imageTranslations.containsKey(paraIndex)) return
        // ⚠️ **这张图正在翻**（整章/队列/手动在途）→ 先别删：删了之后那一批落库会把它**写回来**
        // （用户看到的是"删了又出现"）。让用户等这批结束再删。
        if (imageInFlightElsewhere(paraIndex)) {
            showOverlayToast(getString(R.string.novel_image_already_translating), error = false)
            return
        }
        // 与清文本译文同一口径：显式编辑 → 当场停掉前台队列并回退手动（不会"清了个寂寞"）
        pauseToManual()
        lifecycleScope.launch {
            runCatching { translator().clearParagraphs(b, chapter, listOf(paraIndex)) }
                .onFailure {
                    showOverlayToast(
                        getString(R.string.novel_translate_failed_reason, it.message.orEmpty()),
                        error = true,
                    )
                    return@launch
                }
            if (chapter == chapterIndex) {
                imageTranslations = imageTranslations - paraIndex
                imageVisuals.remove(paraIndex)
                // 这张图已经不是"刚翻完"了（用户刚把译文删了）
                if (recentPara.contains(paraIndex)) {
                    recentPara = recentPara - paraIndex
                    refreshRecentHighlight()
                }
                refreshImageChrome()
            }
            refreshChapterStats()
            showOverlayToast(getString(R.string.novel_image_deleted), error = false)
        }
    }

    private fun imageVisualOf(paraIndex: Int): NovelImageVisual =
        imageVisuals[paraIndex] ?: NovelImageVisual.TRANSLATED

    private fun visualLabelRes(v: NovelImageVisual): Int = when (v) {
        NovelImageVisual.TRANSLATED -> R.string.novel_image_visual_translated
        NovelImageVisual.ORIGINAL -> R.string.novel_image_visual_original
        NovelImageVisual.PLAIN -> R.string.novel_image_visual_plain
    }

    /**
     * OCR 用的语言：把"自动"落到一个**具体**语言。
     *
     * OCR 引擎没有"自动检测"这一说（引擎组按语言选模型）。用户把源语言设成 auto 时，
     * 按目标语言猜一个（译成中文 → 图上的字多半是日中英；中文模型对拉丁字母也能认）。
     *
     * ⚠️ 猜错只影响识别质量，不会崩：认不出字就报「这张图里没识别到文字」。
     */
    private fun ocrLanguageOf(source: String): String {
        if (source.isNotBlank() && source != "auto") return source
        val target = targetLang()
        return when {
            target.startsWith("zh") || target.startsWith("ja") -> "zh"
            else -> "en"
        }
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
     * 聚焦插图（[focusedImagePara]）右下角那排按钮的命中测试。
     *
     * ⚠️ 坐标要减掉**页/条目**的 left/top：按钮是画在页内坐标里的（与绘制同一份几何）。
     * 这也是它必须分模式判的原因 —— 分页看页内坐标，滚动看条目内坐标。
     */
    private fun imageControlAt(x: Float, y: Float): NovelImageButton? {
        val focus = focusedImagePara ?: return null
        if (isScrollMode()) {
            val rv = binding.novelScroll
            val child = rv.findChildViewUnder(x, y) ?: return null
            val pos = rv.getChildAdapterPosition(child)
            if (scrollAdapter.paraIndexAt(pos) != focus) return null
            val v = child as? NovelScrollAdapter.ParagraphView ?: return null
            return v.imageControlAt(x - child.left, y - child.top)
        }
        val rv = binding.novelPager.getChildAt(0) as? RecyclerView ?: return null
        val child = rv.findChildViewUnder(x, y) ?: return null
        val vh = rv.findContainingViewHolder(child) as? NovelPageAdapter.VH ?: return null
        return vh.page.imageControlAt(x - child.left, y - child.top)
    }

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
        refreshFailedHighlight()
    }

    /**
     * 「翻失败了」的高亮刷新：段号取自 [chapterFailures]（面板章行展开里那份明细）。
     *
     * ⚠️ 失败原因在面板里是**原文**，正文里只标底色 —— 用户要能一眼分清
     * 「还没轮到」和「翻失败了」（用户口径 2026-10-03）。
     */
    private fun refreshFailedHighlight() {
        val failed = chapterFailures[chapterIndex].orEmpty().map { it.paraIndex }.toSet()
        if (failed == failedBatch) return
        failedBatch = failed
        pageAdapter.setFailedBatch(failed)
        scrollAdapter.setFailedBatch(failed)
    }

    // ===== 翻页 / 翻段 / 切章 =====

    private fun isScrollMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_SCROLL

    private fun isVerticalMode(): Boolean =
        NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_VERTICAL

    private fun currentPage(): Int =
        if (isScrollMode()) firstVisibleScrollItem() else binding.novelPager.currentItem

    /**
     * 翻页；**到章边界即停并提示**，绝不自动跨章。
     *
     * 用户口径（2026-10）：「文本阅读器不支持左右翻页或点击跨章阅读」—— 原先 `target` 越界时
     * 直接 `gotoChapter(±1)`，于是在**本章最后一页点一下右半屏就跳到下一章**（还没有任何提示）。
     * 切章只保留**显式入口**：目录、底部胶囊左右按钮、面板章卡片、通知跳章。
     */
    private fun turnPage(delta: Int) {
        // 用户点按翻页 = 真的在导航 → 松掉带位锚点，也**退出插图编辑模式**
        // （用户口径：「退出编辑模式隐藏相关按钮」；翻页就是离开那张图）
        carryAnchor = null
        if (focusedImagePara != null) {
            focusedImagePara = null
            refreshImageChrome()
        }
        val pages = content?.pages ?: return
        if (pages.isEmpty()) {
            // ⚠️ 空页表也不许切章（原来这里也是直接 gotoChapter）
            showChapterEdge(delta)
            return
        }
        // ⚠️ 夹一下 currentItem：页表刷新瞬间 currentItem 可能瞬时大于 lastIndex（见 NovelPageAdapter 注释），
        // 不夹的话一次右半屏点击会算出越界 target → 变成"并非末页却跳章"
        val target = currentPage().coerceIn(0, pages.lastIndex) + delta
        when {
            target in pages.indices ->
                binding.novelPager.setCurrentItem(target, animationMode != NovelPanelStyle.ANIM_NONE)
            else -> showChapterEdge(delta)
        }
    }

    /**
     * 章边界提示：「已是本章最后一页」/「已是本章第一页」。
     *
     * 走 [showOverlayToast]（有悬浮窗权限用浮层、否则自动降级系统 Toast）—— 与翻译状态提示同一套，
     * 权限没给也不会静默失败。**不做频控**：连点就连提示，行为可预期。
     */
    private fun showChapterEdge(delta: Int) {
        showOverlayToast(
            getString(
                if (delta > 0) R.string.novel_chapter_last_page else R.string.novel_chapter_first_page
            ),
            error = false,
        )
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
            // ⚠️ 滚动模式同样**不跨章**：到章首/章末就停并提示（原来直接 gotoChapter）
            showChapterEdge(delta)
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
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
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
        if (chapterCount <= 0) return
        if (index < 0) {
            toast(R.string.novel_first_chapter)
            return
        }
        if (index >= chapterCount) {
            toast(R.string.novel_last_chapter)
            return
        }
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        // 选择集是按**段号**记的，换章后段号会撞上别的段 —— 必须先清
        exitSelection()
        // 换章：位置由目标章自己决定，不许带上上一章的带位锚点；高亮同理
        carryAnchor = null
        activeBatch = emptySet()
        failedBatch = emptySet()
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
        val bg = NovelPanelStyle.backgroundColor(bgMode, prefs)
        val statusColors = NovelAppearance.statusColors(prefs)
        pageAdapter.setStatusColors(statusColors)
        scrollAdapter.setStatusColors(statusColors)
        binding.root.setBackgroundColor(bg)
        binding.novelPager.setBackgroundColor(bg)
        binding.novelScroll.setBackgroundColor(bg)
        val pillTextColor = if (NovelPanelStyle.isDarkBackground(bgMode, prefs)) Color.WHITE else Color.BLACK
        // 两半都要设：textColor 不会从父 LinearLayout 继承下来
        binding.tvPageIndicator.setTextColor(pillTextColor)
        binding.tvPageCounter.setTextColor(pillTextColor)
        binding.novelStatus.setTextColor(
            if (NovelPanelStyle.isDarkBackground(bgMode, prefs)) 0xFFECECEC.toInt() else 0xFF222222.toInt()
        )
        // 文字色与**每页的底色**一起下发：底色必须画在页自己身上，
        // 否则「覆盖」动画里页面透明 → 看起来只有文字在动、背景不动
        val textColor = NovelPanelStyle.textColor(bgMode, prefs)
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
        // 换章 → 「刚翻的是哪几段」作废（段号在另一章里指向别的内容）。
        // ⚠️ 判据是**入口处**的 `chapterIndex`（真正换章时它还是旧章）：同一章的译文到达重排
        // 也会走这里，那时**不能**清 —— 否则刚点亮的高亮立刻被自己抹掉。
        if (index != chapterIndex && recentPara.isNotEmpty()) {
            recentPara = emptySet()
            refreshRecentHighlight()
        }
        val token = ++loadToken
        chapterLoadJob?.cancel()
        beginChapterLoading(token)
        chapterLoadJob = lifecycleScope.launch {
            val style = NovelPanelStyle.textStyle(this@NovelReaderActivity, prefs)
            val w = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

            // ⚠️ **本章译文必须在这里按章取**，不能沿用上一次的 `translations`：
            // 那个表是「paraIndex -> 译文」，换章后 paraIndex 会撞上，于是新章每一段都显示
            // **上一章同一段号的译文** —— 用户看到的就是「切了章内容却一点没变」。
            // 而且分页是在「显示文本」上做的，译文换了就必须重排。
            val map = try {
                translator().loadTranslations(b, index)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                translations
            }
            // 图片段的译文载荷（矩形 + OCR 原文 + 译文）：**与文本译文分开读**，缺了它插图就画不出覆盖层
            val imageMap = try {
                translator().loadImageTranslations(b, index)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            if (token != loadToken) {
                return@launch
            }
            translations = map
            imageTranslations = imageMap
            // 换章后段号对不上：把本章不存在的显示态清掉（否则下章的同一段号会继承上一章的三态）
            imageVisuals.keys.retainAll(imageMap.keys)
            focusedImagePara = null

            // ⚠️ 分页模式把锚点交给分页器：**锚点必须落在页首**。不给这一步的话，`pageOf`
            // 只能给到"含锚点的那一页"，锚点可能落在页尾 —— 译文比原文短时它会被挤到上一页，
            // 读者看到的就是「翻译完回到前一页」（用户报的）。滚动模式不用页，不传。
            val pageAnchor = if (isScrollMode() || atLastPage) null else anchor
            val loaded = repository.load(
                b, index, translations, NovelPanelStyle.displayMode(prefs), style, w, h, pageAnchor,
                imageTranslations = imageMap,
                // 开关开着时插图也算"可翻译段"：否则含插图的页在图还没翻时就变绿（进度条说谎）
                includeImages = imagesEnabled(),
            )
            if (token != loadToken) {
                return@launch
            }
            pagedWidth = w
            pagedHeight = h
            chapterIndex = index
            content = loaded
            scrollTranslated = computeScrollTranslated(loaded)
            showStatus(if (loaded.isEmpty) getString(R.string.novel_empty_chapter) else null)

            val textColor = NovelPanelStyle.textColor(bgMode, prefs)
            val bgColor = NovelPanelStyle.backgroundColor(bgMode, prefs)
            if (isScrollMode()) {
                // 上下间距落在**列表**上而不是每一段上：落在每段上会变成段间距。
                // clipToPadding=false → 正文可以滚到浮层底下再滑走，而不是被硬切一刀
                binding.novelScroll.setPadding(0, style.topPaddingPx.toInt(), 0, style.bottomPaddingPx.toInt())
                scrollAdapter.submit(loaded, style, textColor, bgColor)
                // 换章/重排后一定要让 layout listener 再补刷一次进度条（防死循环的闸门要复位）
                lastLayoutScrollItem = -1
                lastLayoutScrollLastItem = -1
                lastScrollChromeRange = null
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
                // 带位重排：把逻辑锚点钉住（见 [carryAnchor]），落位后 onPaged 不许重取。
                //
                // ⚠️ `atLastPage`（在章首页点「上一页」翻到上一章末页）**不能钉传进来的
                // `anchor`**：那条路传的是签名默认值 `NovelAnchor(0)`，钉住它等于宣布
                // 「读者在这一章的第一段」。于是紧接着的第 1089 行 `snapPagerToAnchor` 会
                // 算出 want = pageOf(NovelAnchor(0)) = 0 而 `cur` 是末页 → 立刻
                // `setCurrentItem(0)`：**「上一页」翻到的是上一章的首页而不是末页**，
                // 而且 `persistProgress` 会把 lastReadParaIndex 落成 0，下次打开从章首开始。
                // 钉的必须是**实际落位那一页**的锚点。
                val pinned =
                    if (atLastPage) NovelAnchors.ofPage(loaded.pages, loaded.displayTexts, page)
                    else anchor
                carryAnchor = pinned
                onPaged(page)
                // ⚠️ 布局稳定后**再校一次**：页数缩水到当前页号以下时 ViewPager2 会把位置
                // 重置到第 0 页（实测），紧随的 setCurrentItem 通常能兜住，这里再兜一层。
                binding.novelPager.post { if (token == loadToken) snapPagerToAnchor(pinned) }
            }
            refreshOverlay()
            refreshTranslationChrome()
            refreshImageChrome()
            updateChapterTocLabel()
            persistProgress()
            restartQueueIfNeeded()
        }
        chapterLoadJob?.invokeOnCompletion {
            if (!isDestroyed && ::binding.isInitialized) {
                binding.novelLoading.post { finishChapterLoading(token) }
            }
        }
    }

    /** 仅较慢的章节才显示等待层，加载请求以 [loadToken] 保证与最新任务对应。 */
    private fun beginChapterLoading(token: Int) {
        val alreadyVisible = binding.novelLoading.visibility == View.VISIBLE
        chapterLoadingRunnable?.let(binding.novelLoading::removeCallbacks)
        chapterLoadingRunnable = null
        binding.novelLoading.animate().cancel()
        if (!alreadyVisible) {
            binding.novelLoading.visibility = View.GONE
            binding.novelLoading.alpha = 1f
        }
        showStatus(null)

        // 启动流程切换到正式章节加载时，保持已显示的动画连续，不闪回空白。
        if (alreadyVisible) return

        val showLoading = Runnable {
            if (token != loadToken || isFinishing || isDestroyed) return@Runnable
            binding.novelLoading.alpha = 0f
            binding.novelLoading.visibility = View.VISIBLE
            binding.novelLoading.animate().alpha(1f).setDuration(140L).start()
        }
        chapterLoadingRunnable = showLoading
        binding.novelLoading.postDelayed(showLoading, CHAPTER_LOADING_INDICATOR_DELAY_MS)
    }

    private fun finishChapterLoading(token: Int) {
        if (token != loadToken || !::binding.isInitialized) return
        chapterLoadingRunnable?.let(binding.novelLoading::removeCallbacks)
        chapterLoadingRunnable = null
        binding.novelLoading.animate().cancel()
        binding.novelLoading.visibility = View.GONE
        binding.novelLoading.alpha = 1f
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
        queue?.stop()
        queue = null
        queueRunning = false
        queueChapter = -1
        queueMode = null
        // 章任务的翻译器也按值缓存了，同样要丢（下次用到时按新配置重建）
        chapterHost?.invalidateTranslator()
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
                // ⚠️ **空批 + 没带原因 = 这一批里没有可翻译段**（例如整批都是图片段），
                // 那是"没得翻"，不是失败 —— 弹红条只会让用户以为引擎坏了
                if (result.isEmpty && result.error != null) {
                    showOverlayToast(
                        getString(R.string.novel_translate_failed_reason, result.error),
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
            // ⚠️ 两条都只喂**可翻译段**（`isTranslatable`）：图片与 <4 字的短行按设计永不翻译，
            // 混进来会让锚点落在它们身上 → 引擎把整批滤空 → 队列当"一批失败"弹红条（假失败）
            // ⚠️ 例外：**「同时翻译图片」开着时**插图段也算可翻单位（一张图 = 一个段 = 一次 OCR），
            // 成批规则由 `NovelBatchPlanner.splitImageUnits` 保证"图片独占一批"（见 isImagePara）。
            currentPageParaIndexes = { selectableOnScreen() },
            // 当前视野**全部**段（含不可翻译的图片/短行）—— 只用来给增量窗口定位起点
            // ⚠️ 这里**不能**用 translatableOnScreen()：本页一个可翻译段都没有时窗口会退化成章首
            currentPageSpanIndexes = { currentPageParaIndexes() },
            chapterParaIndexes = { book2, ch -> selectableInChapter(book2, ch) },
            translatedIndexes = { book2, ch -> translations.keys.toSet() },
            // 图片段判据：队列靠它把插图段拆成"独占一批"并串行化（OCR 单例）
            isImagePara = { _, para -> isImagePara(para) },
            // ⚠️ 排除「翻译本章」任务已占住的段：那些段在翻完前库里还是 IDLE，
            // 不排除会让自动/增量队列与章任务**同时**对同一段发请求（白烧额度）。
            // ⚠️ 也要排除**用户长按手动翻的那张插图**：那张图同样在库里还是 IDLE，
            // 队列撞上去就是同一张图被 OCR 两遍（`OcrLock` 只保证不并发，不保证不重复）。
            excludedParas = { ch ->
                (chapterHost?.inFlightAndWaitingParas(ch) ?: emptySet()) + manualImageInFlight(ch)
            },
            // **并发**：远端 API 由「同时 API 请求数」决定（默认 3），**本地引擎恒 1**（判据在
            // TranslationConcurrency 里）→ 本地引擎天然串行，不需要额外分支。
            // ⚠️ 队列内部还会把**自动模式**夹成 1（用户口径 2026-10-03：自动恒串行，并发只给增量/整章）。
            // 每轮现读 prefs：滑块改完立刻对下一批生效（在飞的那几批不受影响）。
            concurrency = {
                TranslationConcurrency.novelConcurrency(
                    this,
                    PreferenceManager.getDefaultSharedPreferences(this),
                )
            },
        ).also {
            queue = it
            observeQueue(it)
        }

    private fun sourceLang(): String = CustomPreference.getInstance(this).getString("Source_Language", "auto")

    private fun targetLang(): String = CustomPreference.getInstance(this).getString("Target_Language", "zh")

    // ===== 图片段（epub 插图）=====

    /**
     * 「同时翻译图片」开着时，插图段也算**可翻单位**（用户口径：「一张图片算一个文本段落」）。
     */
    private fun imagesEnabled(): Boolean = NovelPanelStyle.translateImages(prefs)

    /** 某段是不是**可用的插图段**（段落类型 + 真的解出了图）。 */
    private fun isImagePara(paraIndex: Int): Boolean =
        imagesEnabled() && content?.images?.containsKey(paraIndex) == true

    /** 当前视野里要参与翻译的段（文本 + 开关开启时的插图）。 */
    private fun selectableOnScreen(): List<Int> {
        val c = content ?: return emptyList()
        return currentPageParaIndexes().filter { pi ->
            val p = c.paragraphs.firstOrNull { it.index == pi } ?: return@filter false
            p.isTranslatable() || (imagesEnabled() && p.type == NovelParagraphType.IMAGE && c.images.containsKey(pi))
        }
    }

    /** 整章要参与翻译的段（文本 + 开关开启时的插图）。 */
    private suspend fun selectableInChapter(book: ImportedNovel, chapterIndex: Int): List<Int> {
        val include = imagesEnabled()
        return repository.paragraphsOf(book, chapterIndex)
            .filter { it.isTranslatable() || (include && it.type == NovelParagraphType.IMAGE) }
            .map { it.index }
    }

    /**
     * 把图片显示态 / 聚焦状态推给两种阅读模式的视图。
     *
     * ⚠️ 与 `setSelected` 一样是**只重画不重排**的轻量刷新：三态切换只换覆盖层位图，
     * 分页几何一点没变（插图高度与显示态无关）。
     */
    private fun refreshImageChrome() {
        pageAdapter.setImageState(imageVisuals.toMap(), focusedImagePara, imageTranslations)
        scrollAdapter.setImageState(imageVisuals.toMap(), focusedImagePara, imageTranslations)
        // 查看器开着时也要跟上（在里面点翻译/切换三态/删除，画面上必须立刻变）
        imageViewer?.refresh()
    }

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
        val last = lm.findLastVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceAtLeast(first) ?: first
        val c = content ?: return
        val para = NovelScrollMapping.paraIndexOf(c, first) ?: return
        // ⚠️ 锚点必须带**段内比例**（首可见项已经滚过多少）：段可以长到跨屏，
        // 只记段号的话重排后会回到段顶 —— 长段/整章一段的样本里就是"跳回章节开头"
        val v = lm.findViewByPosition(first)
        val scrolledPx = if (v != null) -v.top else 0
        val anchor = NovelAnchors.ofScroll(para, scrolledPx, v?.height ?: 0)
        pendingAnchor = anchor
        refreshOverlay()
        // 可见范围的末尾也会变化：长段一直占着首位时，新的段仍会从屏幕底部进入/离开。
        // 三态与翻译按钮都按整段可见范围判断，因此只盯首段会让按钮状态过期。
        val visibleRange = first..last
        if (visibleRange != lastScrollChromeRange) {
            lastScrollChromeRange = visibleRange
            refreshTranslationChrome()
        }
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
            // 失败明细变了 → 正文里那几段的「失败」红底跟着变
            refreshFailedHighlight()
            // 目录开着时也推一份：它和面板用的是同一套判据，不能只更新面板
            tocHandle?.update(chapterStats, chapterTotals.toMap())
            // 这一句里已经把章级状态推给面板了（见 updateChapterTocLabel）
            updateChapterTocLabel()
        }
    }

    /** 每章总段数（章行分母）缓存 + 在途去重：同一个章只解析一次。 */
    private val chapterTotals = mutableMapOf<Int, Int>()

    /** 每章可翻译正文字数（面板章行显示，用户拿它估翻译费用）。与 [chapterTotals] 一起懒解析。 */
    private val chapterChars = mutableMapOf<Int, Int>()
    private val pendingTotalFetches = mutableSetOf<Int>()

    /**
     * 当前打开的目录弹窗。宿主解析出分母 / 章状态变化时回推给它 ——
     * 否则目录里的「已完成」会在打开那一刻被冻结，与面板对不上。
     */
    private var tocHandle: NovelTocDialog.Handle? = null

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
            // ⚠️ 分母要跟着「同时翻译图片」走：开着时插图段也算一个可翻单位（一张图 = 一段），
            // 否则会出现「已翻 6/5」——库里那几行图片译文算了成功数，分母里却没有它们
            val includeImages = imagesEnabled()
            val n = runCatching { repository.paragraphCountOf(b, index, includeImages) }.getOrDefault(0)
            // 字数与分母是同一份解析（段落缓存命中），顺手一起取 —— 章行要显示「本章多少字」
            // ⚠️ 字数**不含**图片（图上多少字要 OCR 才知道）：分母含图片、字数不含，两者口径不同是刻意的
            val chars = runCatching { repository.charCountOf(b, index) }.getOrDefault(0)
            pendingTotalFetches.remove(index)
            // ⚠️ **0 也要落账**：不落的话章行的「还不知道」判据永远为真，于是每次绑定都再问一次
            // （空章 / 整章都是短行时最明显 —— 同一章被反复解析）
            chapterTotals[index] = n
            chapterChars[index] = chars
            // 目录与面板共用同一份分母：目录开着时也要跟着刷新，否则两处判据会分叉
            tocHandle?.update(chapterStats, chapterTotals.toMap())
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
        chapterStats = chapterStats,
        chapterTotals = chapterTotals.toMap(),
        chapterChars = chapterChars.toMap(),
        chapterFailures = chapterFailures,
        translateMode = NovelPanelStyle.translateMode(prefs),
        readerMode = NovelPanelStyle.readerMode(prefs),
        animation = animationMode,
        background = bgMode,
        statusColors = NovelAppearance.statusColors(prefs),
        customBackground = NovelAppearance.customBackground(prefs),
        customText = NovelAppearance.customText(prefs),
        fontName = NovelReaderFont.label(this),
        autoTurn = autoTurnEnabled,
        intervalSec = autoTurnIntervalSec,
        debounceMs = NovelPanelStyle.debounceMs(prefs),
        aheadBatches = NovelPanelStyle.aheadBatches(prefs),
        batchSize = NovelPanelStyle.batchSize(prefs),
        rotateLabel = rotateLabel(),
        keepParagraphsWhole = NovelPanelStyle.keepParagraphsWhole(prefs),
        isDarkPanel = NovelPanelStyle.isDarkBackground(bgMode, prefs),
        // 章任务状态（卡片按钮文案 + 徽章 + 「等待」行）：应用级宿主是唯一真值
        chapterJobs = chapterHost?.chapterJobs?.value.orEmpty().associateBy { it.chapterIndex },
        waitingBatches = chapterHost?.waitingByChapter().orEmpty(),
                    activeBatches = chapterHost?.activeByChapter().orEmpty(),
        concurrency = NovelPanelStyle.storedConcurrency(
            PreferenceManager.getDefaultSharedPreferences(this),
        ),
        batchWarnIndex = NovelBatchWarning.indexOf(NovelPanelStyle.batchWarnThreshold(prefs)),
        translateImages = NovelPanelStyle.translateImages(prefs),
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
        // 顶部胶囊 = 章名 + 章内进度。章是小说最主要的定位单位，必须常显。
        //
        // ⚠️ **两个 TextView 分开写**（见布局注释）：章名可以很长（`第一回　宴桃园豪杰三结义
        // 斩黄巾英雄首立功` 这种回目、网文那种带前缀的长标题），合成一个字符串再省略的话
        // 页码会被一起吃掉 —— 而页码是这格存在的另一半理由。
        val label = chapterTitle(chapterIndex)
        val counter = if (isScrollMode()) {
            val total = scrollAdapter.itemCount
            getString(
                R.string.novel_page_counter,
                (firstVisibleScrollItem() + 1).coerceAtMost(total), total,
            )
        } else {
            val pages = content?.pages
            if (pages.isNullOrEmpty()) getString(R.string.novel_page_counter, 0, 0)
            else getString(R.string.novel_page_counter, currentPage() + 1, pages.size)
        }
        // ⚠️ 文案没变就别 setText：TextView 会无条件 requestLayout，
        // 而滚动回调是每帧一次的（还会牵动 [setupOverlays] 里的 layout listener）
        if (binding.tvPageIndicator.text.toString() != label) binding.tvPageIndicator.text = label
        if (binding.tvPageCounter.text.toString() != counter) binding.tvPageCounter.text = counter
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
            binding.novelProgress.setTranslatedPages(
                translatedPagesOf(
                    pages,
                    content?.let(::translatableIndexesOf).orEmpty(),
                    translations.keys + imageTranslations.keys,
                ),
            )
        }
        updateChapterTocLabel()
    }

    /** 滚动列表第一个可见 item 的下标（拿不到时给 0）。 */
    private fun firstVisibleScrollItem(): Int =
        (binding.novelScroll.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: 0

    private fun lastVisibleScrollItem(): Int =
        (binding.novelScroll.layoutManager as? LinearLayoutManager)
            ?.findLastVisibleItemPosition()
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: firstVisibleScrollItem()

    /**
     * 滚动模式下的「已翻译」格集合（进度条上的绿色区间），值与 [loadChapter] 同步刷新。
     *
     * ⚠️ 不能每次滚动都现算：滚动回调是每帧一次的，几百段遍历会白烧 CPU。
     * 单位与 [refreshOverlay] 推进度条时一致（[NovelScrollProgress] 的格）。
     */
    private fun translatableIndexesOf(c: ChapterContent): Set<Int> =
        if (imagesEnabled()) c.translatableIndexes + c.images.keys
        else c.translatableIndexes - c.images.keys

    private fun computeScrollTranslated(c: ChapterContent): Set<Int> {
        val translatable = translatableIndexesOf(c)
        val translated = translations.keys + imageTranslations.keys
        val visible = c.visibleParas
        val hits = mutableSetOf<Int>()
        for (i in visible.indices) {
            val pi = visible[i].index
            if (pi !in translatable || pi in translated) hits += i
        }
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
     * - **翻译按钮**：当前视野还有没翻的可翻译段才显示（翻页 = 本页；滚动 = 当前屏幕可见段）。
     *   都翻过了就只剩三态 —— 滚动模式也一样，别"始终显示"。
     * - **三态按钮**：当前视野里有译文才显示 —— 没译文时点了看不出任何变化。
     * - **图标不再看「有没有译文」**：漫画那套「缓存命中 → 变重翻图标」在文本翻译里**不要**
     *   （用户明确要求）。重翻走长按多选，按钮语义由**选择集**决定。
     */
    private fun refreshTranslationChrome() {
        if (chromeHidden) {
            dismissTranslateModePopup()
            binding.translateGroup.visibility = View.GONE
            return
        }
        val translated = chromeTranslated()
        // ⚠️ 「已翻」判据要把**插图的译文载荷**也算上（`translations` 只装文本段）：
        // 开关开着时插图能被多选，只认文本的话会出现"选中了一张已翻的插图，却没有清除按钮"。
        val selectedUntranslated = selectedPara.count { !isUnitTranslated(it) }
        val selectedTranslated = selectedPara.count { isUnitTranslated(it) }
        val action = if (selectedPara.isNotEmpty()) {
            NovelTranslateChrome.actionForSelection(selectedUntranslated, selectedTranslated)
        } else {
            NovelTranslateChrome.actionFor(chromeUntranslated())
        }
        val showToggle = NovelTranslateChrome.showToggle(translated)
        val showFail = hasFailedChapter(chapterIndex)
        val showRunningTranslate = NovelPanelStyle.translateMode(prefs) != NovelTranslateMode.MANUAL ||
            queueRunning || translationJob?.isActive == true || imageJob?.isActive == true
        val showTranslate = action != null || showRunningTranslate
        // 清除按钮只看**选中集里有没有译过的段**（与三态那套判据不同，见 showClear）
        val showClear = NovelTranslateChrome.showClear(selectedTranslated)

        // 四块都不可用时整组收掉：留一个空的黑胶囊在右下角更奇怪
        binding.translateGroup.visibility =
            if (showTranslate || showToggle || showFail || showClear) View.VISIBLE else View.GONE

        binding.btnClearTranslate.visibility = if (showClear) View.VISIBLE else View.GONE

        binding.btnTranslate.visibility = if (showTranslate) View.VISIBLE else View.GONE
        if (!showTranslate) dismissTranslateModePopup()
        if (showTranslate) {
            binding.ivTranslate.setImageResource(
                when (action) {
                    // 「翻译」用翻译图标；重翻与"翻+重翻"都含重翻动作 → 刷新图标
                    null, NovelTranslateAction.TRANSLATE -> R.drawable.ic_reader_translate
                    NovelTranslateAction.RETRANSLATE,
                    NovelTranslateAction.TRANSLATE_AND_RETRANSLATE,
                    -> R.drawable.ic_refresh
                }
            )
            val label = if (action != null) actionLabel(action, selectedPara.size)
                else getString(R.string.reader_tab_translate)
            // ⚠️ 没选中任何段时不要报「翻译 0 段」（读屏会念出来）：退回不带计数的普通文案
            binding.btnTranslate.contentDescription =
                if (selectedPara.isNotEmpty()) label else getString(R.string.reader_tab_translate)
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
            binding.btnToggleTranslate.contentDescription = getString(R.string.novel_display_toggle_desc)
            binding.ivToggleTranslate.contentDescription = getString(R.string.novel_display_toggle_desc)
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

    /**
     * 浮层判据：当前视野里**还没译文**的可翻译段数。
     *
     * ⚠️ 两种阅读模式同口径（翻页 = 本页，滚动 = 当前屏幕可见段）：用户明确要求滚动模式
     * 也别"始终显示翻译按钮"，当前屏翻完了就该收起来。代价是滚动时它会随内容出现/消失 ——
     * 那是他要的行为。
     */
        /**
     * 浮层判据：当前视野里**还没翻**的可翻单位数。
     *
     * ⚠️ 插图也要按**它自己的载荷**判"翻没翻"（`translations` 只装文本段）——
     * 只认文本的话，一张已经翻好的插图会被永远算成"没翻"，翻译按钮常驻且点了没反应。
     */
    private fun chromeUntranslated(): Int =
        translatableOnScreen().count { !isUnitTranslated(it) }

    /** 浮层判据：当前视野里已有译文的段数（三态按钮的显示条件）。 */
    private fun chromeTranslated(): Int =
        translatableOnScreen().count { isUnitTranslated(it) }

    /** 这一段有没有译文 —— 文本看 `translations`，插图看**载荷**（另一张表）。 */
    private fun isUnitTranslated(paraIndex: Int): Boolean =
        translations.containsKey(paraIndex) || imageTranslations.containsKey(paraIndex)

    /**
     * 当前视野里**可翻**的段号：翻页模式 = 本页各 segment 的段号，滚动模式 = 可见段。
     *
     * ⚠️ 必须滤掉图片与短行（[isTranslatable]）：它们**按设计永远不翻译**，
     * 算进「待翻译」的话含 `……` 的一页永远判不出「整页翻完了」，翻译按钮就常驻且点了没反应。
     * ⚠️ 例外：**「同时翻译图片」开着**时插图段算可翻单位（一张图 = 一个段）——
     * 与队列的 `currentPageParaIndexes` 同一份判据（[selectableOnScreen]），
     * 两边不一致会出现"按钮不亮但队列会翻"或反之。
     */
    private fun translatableOnScreen(): List<Int> = selectableOnScreen()

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
     * 右下角翻译按钮（使用相同的 300ms 双击窗口；文本侧保留自己的批次与选择规则）：
     * - **手动空闲双击**：快捷开启自动模式；单击先等 300ms 窗口，超时后从当前页未翻段起翻一批
     * - **自动/增量在跑**：单击只提示，双击停止队列并回退手动
     * - **手动批次在途**：单击提示，双击取消在途请求并回退手动
     * - **选择模式**：空闲时点击直接翻所选段；选段翻译在途时也按单击提示、双击取消
     */
    private fun onTranslateButtonClick() {
        translateModeChangeId += 1
        // 选择模式：单击就是**按选择翻**（翻译 / 重翻 / 翻译+重翻）——
        // 意图已经由选择集说清了，不走下面那套「双击=取消」的判定
        if (selecting && selectedPara.isNotEmpty() &&
            NovelPanelStyle.translateMode(prefs) == NovelTranslateMode.MANUAL && !queueRunning &&
            translationJob?.isActive != true && imageJob?.isActive != true
        ) {
            cancelPendingManualTranslateClick()
            lastTranslateClickMs = 0L
            translateSelectionNow()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val pending = pendingManualTranslateClick
        if (pending != null) {
            val isDouble = now - pendingManualTranslateClickMs <= DOUBLE_CLICK_MS
            cancelPendingManualTranslateClick()
            if (isDouble) {
                lastTranslateClickMs = 0L
                if (canStartAutoFromTranslateButton()) {
                    startAutoFromTranslateButton()
                } else {
                    handleTranslateButtonClick(isDouble = true)
                }
                return
            }
            // 主线程延迟回调若被阻塞，先补交超时的第一次单击，再处理这一次。
            lastTranslateClickMs = now - DOUBLE_CLICK_MS - 1L
            handleTranslateButtonClick(isDouble = false)
        }

        if (canStartAutoFromTranslateButton()) {
            scheduleManualTranslateClick(now)
            return
        }

        val isDouble = now - lastTranslateClickMs <= DOUBLE_CLICK_MS
        lastTranslateClickMs = if (isDouble) 0L else now
        handleTranslateButtonClick(isDouble)
    }

    /** 手动空闲时才延迟单击；自动队列运行时仍立即提示并保留原有双击退出手势。 */
    private fun canStartAutoFromTranslateButton(): Boolean =
        NovelPanelStyle.translateMode(prefs) == NovelTranslateMode.MANUAL &&
            !queueRunning && translationJob?.isActive != true && imageJob?.isActive != true &&
            !selecting && focusedImagePara == null

    private fun scheduleManualTranslateClick(now: Long) {
        lastTranslateClickMs = 0L
        val action = object : Runnable {
            override fun run() {
                if (pendingManualTranslateClick !== this) return
                pendingManualTranslateClick = null
                pendingManualTranslateClickMs = 0L
                if (isFinishing || isDestroyed) return
                handleTranslateButtonClick(isDouble = false)
            }
        }
        pendingManualTranslateClick = action
        pendingManualTranslateClickMs = now
        binding.btnTranslate.postDelayed(action, DOUBLE_CLICK_MS)
    }

    private fun cancelPendingManualTranslateClick() {
        pendingManualTranslateClick?.let { action ->
            if (::binding.isInitialized) binding.btnTranslate.removeCallbacks(action)
        }
        pendingManualTranslateClick = null
        pendingManualTranslateClickMs = 0L
    }

    private fun showTranslateModePopup() {
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        dismissTranslateModePopup()
        translateModePopup = ReaderTranslateModePopup.show(
            anchor = binding.btnTranslate,
            selectedMode = NovelPanelStyle.translateMode(prefs).ordinal,
            ocrOnly = false,
        ) { mode ->
            translateModePopup = null
            applyQuickTranslateMode(NovelTranslateMode.entries[mode])
        }
    }

    private fun dismissTranslateModePopup() {
        translateModePopup?.dismiss()
        translateModePopup = null
    }

    private fun applyQuickTranslateMode(mode: NovelTranslateMode) {
        translateModeChangeId += 1
        val current = NovelPanelStyle.translateMode(prefs)
        val hasForegroundTask = queueRunning || translationJob?.isActive == true || imageJob?.isActive == true
        if (mode == current && !hasForegroundTask) return
        if (mode == NovelTranslateMode.MANUAL) {
            pauseToManual(
                getString(R.string.reader_translate_force_stopped_to_manual).takeIf {
                    current != NovelTranslateMode.MANUAL || hasForegroundTask
                }
            )
        } else {
            // 旧批次的 TRANSLATING 复位必须先落库，再让新队列标记在途段。
            // 只异步等待数据库清理，不在主线程等 native OCR 或持有 OcrLock。
            val resetJob = pauseToManual()
            val changeId = translateModeChangeId
            exitSelection()
            if (queueOrNull() == null) {
                toast(R.string.novel_translate_need_config)
                refreshTranslationChrome()
                return
            }
            lifecycleScope.launch {
                resetJob?.join()
                if (!isActive || changeId != translateModeChangeId) return@launch
                NovelPanelStyle.setTranslateMode(prefs, mode)
                restartQueueIfNeeded(force = true)
                refreshTranslationChrome()
                pushPanelState()
            }
        }
        refreshTranslationChrome()
        pushPanelState()
    }

    private fun startAutoFromTranslateButton() {
        // 缺少引擎配置时不留下「自动已开启」但实际无队列的假状态。
        if (queueOrNull() == null) {
            toast(R.string.novel_translate_need_config)
            return
        }
        lastTranslateClickMs = 0L
        translateModeChangeId += 1
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AUTO)
        restartQueueIfNeeded(force = true)
        if (queueRunning) {
            showOverlayToast(getString(R.string.reader_auto_mode_started), error = false)
        }
        refreshTranslationChrome()
        pushPanelState()
    }

    private fun handleTranslateButtonClick(isDouble: Boolean) {
        val mode = NovelPanelStyle.translateMode(prefs)
        if (mode != NovelTranslateMode.MANUAL) {
            if (!isDouble) {
                showOverlayToast(
                    if (queueRunning) getString(R.string.novel_translate_hint_running, chapterIndex + 1)
                    else getString(
                        if (mode == NovelTranslateMode.AUTO) R.string.reader_translate_hint_auto_idle
                        else R.string.reader_translate_hint_ahead_idle
                    ),
                    error = false,
                )
                return
            }
            pauseToManual(getString(R.string.reader_translate_force_stopped_to_manual))
            return
        }
        if (translationJob?.isActive == true || imageJob?.isActive == true) {
            if (isDouble) {
                pauseToManual(getString(R.string.reader_translate_force_stopped_to_manual))
            } else {
                showOverlayToast(getString(R.string.novel_translate_hint_manual), error = false)
            }
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
        // 显式操作 = 新一轮：超长预警重新允许弹窗
        oversizeConfirmer.reset()
        translationJob?.cancel()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    q.translateOneBatch(b, chapterIndex)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogCollector.e(TAG, "手动翻译批次失败", e)
                    null
                }
                if (!isActive) return@launch
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
            } finally {
                // 旧任务取消后可能晚于新任务收尾，不能清掉新任务的句柄和进度提示。
                if (translationJob === coroutineContext[Job]) {
                    translationJob = null
                    showOverlay(null)
                    refreshTranslationChrome()
                }
            }
        }
        translationJob = job
        showOverlay(getString(R.string.reader_translate_in_progress), autoDismiss = false)
        job.start()
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
        // 显式操作 = 新一轮：超长预警重新允许弹窗
        oversizeConfirmer.reset()
        translationJob?.cancel()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                var failed: String? = null
                var done = 0
                for (group in wanted.chunked(chunk)) {
                    val r = try {
                        q.translateExact(b, chapterIndex, group)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "选段翻译失败", e)
                        null
                    }
                    if (!isActive) return@launch
                    if (r == null || r.isEmpty) {
                        failed = r?.error ?: getString(R.string.reader_translate_failed)
                        break
                    }
                    done += group.size
                    // 每批上屏一次：选十几段时要能看到逐批出译文，而不是最后一起蹦出来
                    refreshTranslations()
                }
                refreshChapterStats()
                if (failed != null) {
                    showOverlayToast(getString(R.string.novel_translate_failed_reason, failed), error = true)
                } else {
                    showOverlayToast(getString(R.string.novel_translate_selected_done, done), error = false)
                }
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    translationJob = null
                    showOverlay(null)
                    refreshTranslationChrome()
                }
            }
        }
        translationJob = job
        showOverlay(getString(R.string.reader_translate_in_progress), autoDismiss = false)
        job.start()
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
            binding.novelPager.setCurrentItem(want, false)
        }
    }

    /**
     * **章卡片的主按钮**（用户口径：没任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续）。
     *
     * 任务本体在**应用级宿主**（`NovelChapterJobHost`）里跑：退出阅读器也继续，
     * 进度走前台服务通知栏。所以这里只做三件事：按状态切暂停/继续、把任务提交上去、
     * 把前台服务拉起来。
     */
    private fun onChapterPrimaryClicked(index: Int) {
        val host = chapterHost ?: return
        when (host.jobOf(index)?.state) {
            ChapterJobState.RUNNING, ChapterJobState.QUEUED -> {
                host.pause(index)
                pushPanelState()
                return
            }
            ChapterJobState.PAUSED -> {
                host.resume(index)
                TranslationJobService.start(applicationContext)
                pushPanelState()
                return
            }
            else -> Unit
        }
        // 没任务 → 「翻译本章」：只翻**没翻成**的段（重翻走长按多选，语义不混）
        oversizeConfirmer.reset()
        // 反向去重：章任务排批时避开**前台队列正在翻**的段（见 NovelChapterJobHost.queueInFlightParas）
        host.queueInFlightParas = { queue?.inflightParaIndexes() ?: emptySet() }
        host.startChapter(
            chapterIndex = index,
            label = chapterDisplayTitle(this, index, chaptersCache[index]),
            onEmpty = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        UiUtils.showToast(this, getString(R.string.reader_translate_chapter_nothing), true)
                    }
                }
            },
        )
        // 任务在应用级后台跑：拉起前台服务（通知栏进度 + 暂停/继续/取消按钮）
        TranslationJobService.start(applicationContext)
        pushPanelState()
    }

    /**
     * **章卡片的次按钮**（有任务时）：取消该章任务 ——
     * 丢掉**还没开始翻**的批，**已翻好的译文保留**（语义在 `ChapterJobRunner` 里，与漫画一致）。
     */
    private fun onChapterSecondaryClicked(index: Int) {
        val host = chapterHost ?: return
        host.cancel(index)
        showOverlayToast(getString(R.string.reader_translate_force_stopped), error = false)
        book?.let { NovelTranslationHub.releaseIfIdle(it.id, it.translationKey) }
        pushPanelState()
    }

    /** 章目录缓存（章任务要标题当通知栏文案）。 */
    private val chaptersCache = mutableMapOf<Int, String?>()

    private fun cacheChapterTitles(list: List<com.moe.starflow.novel.model.NovelChapterMeta>) {
        list.forEachIndexed { i, meta -> chaptersCache[i] = meta.title }
    }

    /** 章任务有进展（一批落定 / 收尾）→ 把结果**实时刷上屏**。 */
    private fun onChapterJobProgress() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // 后台任务也可能在别的章上跑：只刷统计（章行/徽章），当前章的译文才重排
            refreshChapterStats()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshTranslations()
            pushPanelState()
            // ⚠️ **整章任务也要有 app 内的进度提示**（用户口径 2026-10-04：「整章翻译我还是没看到
            // 通知系统有任何提示……我说的是 app 的通知系统要有提示！！」）—— 这里说的"app 通知系统"
            // 就是状态浮层（`TranslationStatusOverlay` 的常驻芯片），不是系统的通知栏。
            refreshChapterJobChips()
        }
    }

    /**
     * **整章任务**在浮层上的常驻进度芯片（每个在跑的章一条）。
     *
     * 用户口径 2026-10-04：整章翻译「不仅要显示在卡片徽章上」，**app 的通知系统（浮层）里也要有**；
     * 自动/增量/手动同样要（那三条走队列状态，见 [updateReaderTranslationNotify] 旁边的浮层观察）。
     *
     * ⚠️ 用 [TranslationStatusOverlay.showRunning] 而不是 `showImmediate`：整章任务常常与
     * 自动/增量的进度同时存在，`showImmediate` 会把对方**替换**掉；`showRunning` 是**追加**、
     * 不自动消失、可按句柄精确移除（`removeRunning`），正是为这种并存场景做的。
     * 浮层总开关关掉时 `showRunning` 返回 0（此时 `removeRunning` 是空操作，无需特判）。
     */
    private fun refreshChapterJobChips() {
        val overlay = TranslationStatusOverlay.getInstance(this)
        val jobs = chapterHost?.chapterJobs?.value.orEmpty().filter { it.isActive }
        val alive = mutableSetOf<Int>()
        jobs.forEach { job ->
            alive += job.chapterIndex
            // 这一章正在逐张 OCR 插图 → 芯片报"第 i/N 张"（用户口径：提示系统要显示当前
            // 正在翻译的图片状态）；否则报批进度。
            // ⚠️ 优先用**宿主快照里的** stageText：整章任务可以在别的章上跑（甚至阅读器已关），
            // 那时 Activity 自己那份 `imageStageText` 因为"不是当前章"根本没被写入。
            val stage = chapterHost?.imageStageTextOf(job.chapterIndex)
                ?: imageStageText?.takeIf { imageStageChapter == job.chapterIndex }
            val text = when {
                job.state == ChapterJobState.QUEUED -> getString(R.string.reader_translate_state_waiting)
                stage != null -> stage
                job.state == ChapterJobState.PAUSED ->
                    getString(R.string.novel_chapter_overlay_paused, job.chapterIndex + 1, job.done, job.total)
                else ->
                    getString(R.string.novel_chapter_overlay_running, job.chapterIndex + 1, job.done, job.total)
            }
            chapterJobChips.remove(job.chapterIndex)?.let { overlay.removeRunning(it) }
            val id = overlay.showRunning(text)
            if (id != 0L) chapterJobChips[job.chapterIndex] = id
        }
        // 已经收尾/取消的章：把它的芯片摘掉
        chapterJobChips.keys.filter { it !in alive }.forEach { ch ->
            chapterJobChips.remove(ch)?.let { overlay.removeRunning(it) }
        }
    }

    /** 整章任务的浮层芯片句柄（章 → 句柄），见 [refreshChapterJobChips]。 */
    private val chapterJobChips = mutableMapOf<Int, Long>()

    /**
     * 「已暂停」的用户可见提示（用户报过"没有提示"）。
     *
     * 状态浮层开着就用浮层（**系统窗口，阅读器正在关闭也看得见**），否则退回 Toast ——
     * 与漫画 `showPausedToManualNotice()` 同一套判断。
     */
    private fun showPausedNotice(textRes: Int) {
        val text = getString(textRes)
        if (canUseOverlay()) {
            // ⚠️ 必须走 **showSticky**（用户口径 2026-10-03：「退出阅读器没看到回退提示」）：
            // 提示在 `onStop` 发，紧跟其后的 `onDestroy` 会调 `dismiss()` 清屏 —— 普通 `show`
            // 会被当场抹掉（只剩几十毫秒的一闪），而 sticky 的芯片 `dismiss()` 时**保留**，
            // 它自己按 `Status_Duration` 计时消失。
            TranslationStatusOverlay.getInstance(this).showSticky(text)
        } else {
            UiUtils.showToast(this, text)
        }
    }

    /**
     * **「取消本次翻译」**（单批超长预警的否定按钮）：关掉当前翻译进程并回退手动。
     *
     * 用户口径 2026-10-03：「可以取消本次翻译（**关闭当前翻译进程，回退手动**），也可以忽略继续翻译」。
     * ⚠️ 与旧行为的区别：原来这个按钮只**跳过这一批**，后面的批照翻（用户看到的是"点了取消还在翻"）。
     * 现在停前台队列（含中止在途请求）+ 模式回退手动 + **当前章**的整章任务一并取消。
     * （整章任务属于应用级宿主，只有当前章的能这样点到；别的章的任务请从通知/面板取消。）
     */
    private fun cancelCurrentTranslation() {
        pauseToManual()
        val ch = chapterIndex
        if (chapterHost?.jobOf(ch)?.isActive == true) {
            chapterHost?.cancel(ch)
            book?.let { NovelTranslationHub.releaseIfIdle(it.id, it.translationKey) }
        }
        showOverlayToast(getString(R.string.novel_translate_oversize_cancelled), error = false)
        pushPanelState()
    }

    /**
     * **单批超长预警**的确认框（继续 / 取消这次翻译）。
     *
     * ⚠️ 会被**后台任务**调用（宿主在 IO 线程上问 UI），所以整段切主线程；
     * Activity 正在销毁时直接**放行**（预警是提示，不是硬闸门 —— 没 UI 不能把任务卡死）。
     */
    private suspend fun confirmOversizeOnUi(estimate: Int, threshold: Int): Boolean {
        if (isFinishing || isDestroyed) return true
        return withContext(Dispatchers.Main) {
            // ⚠️ **弹窗必须带超时**（用户口径 2026-10-03）：预警是"提示"不是硬闸门 ——
            // 用户一直不点（或弹窗被系统挡在后面）时，原来那一批会**无限期挂着**：
            // 状态一直显示「翻译中」、并发槽位不放、后面的批也排不上（仅次于引擎根因的卡点）。
            // 超时 = 当作"继续翻"放行。
            val timeoutMs = 60_000L
            kotlinx.coroutines.coroutineScope {
                var answered = false
                var dlg: androidx.appcompat.app.AlertDialog? = null
                var waiting: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
                val answer: (Boolean) -> Unit = { go ->
                    if (!answered) {
                        answered = true
                        runCatching { dlg?.dismiss() }
                        waiting?.takeIf { it.isActive }?.resume(go)
                    }
                }
                val timer = launch {
                    delay(timeoutMs)
                    if (!answered) {
                        UiUtils.showToast(
                            this@NovelReaderActivity,
                            getString(R.string.novel_translate_oversize_timeout),
                            false,
                        )
                        answer(true)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        waiting = cont
                        if (isFinishing || isDestroyed) {
                            cont.resume(true)
                            return@suspendCancellableCoroutine
                        }
                        // ⚠️ 走共享的 ReaderDialogs（用对应 night 模式的上下文建弹窗）：
                        // 面板/标题/正文/按钮全由主题给色，不再"换底 + 一律刷浅色"（那是白底白字的来源）
                        dlg = ReaderDialogs.show(
                            this@NovelReaderActivity,
                            NovelPanelStyle.isDarkBackground(bgMode, prefs),
                        ) {
                            setTitle(R.string.novel_translate_oversize_title)
                            setMessage(getString(R.string.novel_translate_oversize_msg, estimate, threshold))
                            setPositiveButton(R.string.novel_translate_oversize_continue) { _, _ ->
                                answer(true)
                            }
                            setNegativeButton(R.string.novel_translate_oversize_cancel) { _, _ ->
                                // ⚠️ 先 resume 再收尾：resume 之后这个协程可能当场被取消（stop 会掐在途请求），
                                // 所以收尾动作必须在 resume **之后**同步做完，不能等协程回来再补。
                                answer(false)
                                cancelCurrentTranslation()
                            }
                            setOnCancelListener { answer(false) }
                        }
                        cont.invokeOnCancellation { if (!answered) runCatching { dlg?.dismiss() } }
                    }
                } finally {
                    timer.cancel()
                }
            }
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
     * 停止在途翻译并**回退手动**：面板打开 / 双击走这里（用户明确要求）。
     *
     * ⚠️ **不动章批量任务**：那是应用级的后台任务（面板上有暂停/取消按钮），
     * 打开面板就把它停掉与「后台继续翻」直接冲突。
     */
    private fun pauseToManual(message: String? = null): Job? {
        translateModeChangeId += 1
        val hadPageTask = queueRunning || translationJob?.isActive == true
        // 取消 Job 让挂起请求立刻退栈；stop() 同时 abort 该队列引擎的 HTTP 请求。
        // 不等待 native OCR 退出，也不提前释放 OcrLock：原调用的 finally 负责释放。
        translationJob?.cancel()
        translationJob = null
        imageJob?.cancel()
        imageJob = null
        manualImagePara = null
        manualImageChapter = -1
        queue?.stop()
        queueRunning = false
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.MANUAL)
        showOverlay(null)
        // ⚠️ **插图进度文案要一起清**：批内插图被取消时它不会自己收（那张图的下一次上报不会来了），
        // 留着的话浮层会永远显示「正在识别插图 2/3…」而其实什么都没在跑。
        imageStageText = null
        imageStageChapter = -1
        // ⚠️ 停队列 = 在途批被 cancel：**那一批留在库里的「翻译中」没人清**（写成 SUCCESS/FAILED
        // 的代码在 cancel 之后不会执行）→ 面板会把那几张图永远显示成"翻译中"。
        // 这里顺手把它们复位成 IDLE（与进入阅读器时的 `resetStale` 同一件事，只是更及时）。
        val b = book
        val resetJob = if (hadPageTask && b != null) {
            lifecycleScope.launch {
                runCatching { translator().resetTranslating(b) }
            }
        } else null
        message?.let { showOverlayToast(it, error = false) }
        refreshTranslationChrome()
        pushPanelState()
        return resetJob
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
                queueState = st
                // 队列状态一变就把整份状态推给面板（否则面板不知道"正在翻/停了"）
                pushPanelState()
                // 同一份状态也镜像到**通知栏**（用户口径 2026-10-03：翻译过程的提示必须进通知系统，
                // 不只是面板上的章卡片徽章）
                updateReaderTranslationNotify(st)
                // 「正在翻译」高亮 = **真的在飞的那几批**（用户口径 2026-10-03）。
                //
                // ⚠️ 原来写的是「认领了就高亮、翻完也不擦」，于是一旦某一批卡住/失败，那几段就
                // **永远黄着** —— 用户看到的「首批一直黄着却不翻译」「黄底和实际在翻译的段对不上」
                // 就是它。现在只在 TRANSLATING / WAITING_LOCK 期间高亮在飞的批，其余阶段立刻擦掉；
                // 「哪几段失败了」由面板的失败明细 + 红条说明，不再靠底色记。
                val inflightParas =
                    if (st.phase == NovelQueuePhase.TRANSLATING || st.phase == NovelQueuePhase.WAITING_LOCK) {
                        st.batchParaIndexes.toSet()
                    } else {
                        emptySet()
                    }
                // 队列不在翻 → 插图进度文案**必须清掉**：批内插图只在自己开跑前上报一次，
                // 批失败/被取消时那次"清零上报"不会执行，留着会让浮层永远显示「正在识别插图 2/3…」。
                if (st.phase != NovelQueuePhase.TRANSLATING && imageStageText != null) {
                    imageStageText = null
                    imageStageChapter = -1
                }
                if (inflightParas != activeBatch) {
                    activeBatch = inflightParas
                    refreshActiveHighlight()
                }
                when (st.phase) {
                    NovelQueuePhase.TRANSLATING -> showOverlay(
                        // 批内有插图正在 OCR 时优先报"第 i/N 张图"（用户口径 2026-10-04：
                        // 「由于 ocr 串行，提示系统要显示当前正在翻译的图片状态」）——
                        // 三张图要一张张来，只报"正在翻译 · 第 X/Y 页"用户不知道在等什么
                        imageStageText ?: queuePositionText(st), autoDismiss = false,
                    )
                    // ⚠️ **等页面稳定要有明文提示**（用户口径 2026-10-04：「我没看到任何等待页面稳定…
                    // 的相关提示信息」）—— 这一态**还没有发请求**，所以黄底是空的，
                    // 只靠"没有黄底"用户不知道是在等自己停下来。
                    NovelQueuePhase.WAITING_STABLE -> showOverlay(
                        getString(R.string.novel_queue_waiting_stable), autoDismiss = false,
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
        // 在飞批全都不在当前屏（用户已经翻走）时不再瞎报"第 1 段"，只说"翻译中…"
        if (para == null) return getString(R.string.reader_translate_in_progress)
        val c = content
        if (!isScrollMode() && c != null && c.pages.isNotEmpty()) {
            val page = NovelAnchors.pageOf(c.pages, c.displayTexts, NovelAnchor(para))
            return getString(R.string.novel_queue_translating_page, page + 1, c.pages.size)
        }
        return getString(R.string.novel_queue_translating_para, para + 1)
    }

    /**
     * 阅读器**前台队列**（手动翻一批 / 自动 / 增量）在通知栏的进度。
     *
     * 用户口径 2026-10-03：「整章翻译的通知要有，**其他模式的翻译过程相关提示也必须显示在通知系统中**」。
     * 整章任务本来就有通知（`TranslationJobService`）；自动/增量/手动只有浮层与面板徽章，
     * 这里补上通知栏那一条（[ReaderTranslationNotify]，固定 id，同一时刻只有一条）。
     *
     * ⚠️ **只在真的在翻时挂着**：面板打开 / 翻完本页 / 停队列时立刻收掉 ——
     * 一条写着"正在翻译"却什么都没在跑的通知比没有通知更让人困惑。
     */
    private fun updateReaderTranslationNotify(st: NovelQueueState) {
        val b = book
        if (b == null ||
            (st.phase != NovelQueuePhase.TRANSLATING && st.phase != NovelQueuePhase.WAITING_LOCK)
        ) {
            ReaderTranslationNotify.clear(this)
            return
        }
        val para = st.batchParaIndexes.firstOrNull() ?: 0
        // 队列可能在**别的章**上跑（切章不重启队列）→ 通知要报它自己那一章
        val ch = if (st.chapterIndex >= 0) st.chapterIndex else chapterIndex
        val c = content
        val paged = !isScrollMode() && c != null && c.pages.isNotEmpty()
        val posText: String
        var done = 0
        var total = 0
        if (paged && c != null) {
            val page = NovelAnchors.pageOf(c.pages, c.displayTexts, NovelAnchor(para))
            done = page + 1
            total = c.pages.size
            posText = getString(R.string.novel_queue_translating_page, done, total)
        } else {
            posText = getString(R.string.novel_queue_translating_para, para + 1)
        }
        // ⚠️ 批内有插图正在 OCR 时，**通知栏也要报那一句**（用户口径：提示系统要显示当前正在
        // 翻译的图片状态）—— 只报"第 X/Y 页"的话，锁屏/下拉看到的是同一句，图片那几秒像卡住。
        val stage = imageStageText
        ReaderTranslationNotify.show(
            context = this,
            bookTitle = b.title,
            chapterLabel = chapterDisplayTitle(this, ch, chaptersCache[ch]),
            line = getString(
                R.string.novel_translate_notify_line,
                stage ?: posText,
                st.batchesDone,
            ),
            done = done,
            total = total,
            novelId = b.id,
            chapterIndex = ch,
        )
    }

    /**
     * 现在能不能真的把状态浮层画出来：**开关打开 + 有悬浮窗权限**。
     *
     * ⚠️ 只判开关是不够的：没有「显示在其他应用上层」权限时浮层**静默失败**
     * （既不显示也不报错），用户看到的就是"点了翻译/取消，什么提示都没有"。
     * 与漫画 `MangaReaderActivity.showForceStoppedNotice` 同一套判断。
     */
    private fun canUseOverlay(): Boolean =
        statusOverlayEnabled() && TranslationStatusOverlay.canDraw(this)

    private fun statusOverlayEnabled(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("status_overlay_enabled", true)

    /** 状态浮层显示（`text == null` = 收起）。⚠️ 开关关闭时退回系统 Toast，否则用户毫无反馈。 */
    private fun showOverlay(text: String?, autoDismiss: Boolean = true) {
        if (!canUseOverlay()) {
            if (text != null) UiUtils.showToast(this, text)
            return
        }
        val overlay = TranslationStatusOverlay.getInstance(this)
        if (text == null) overlay.dismiss() else overlay.showImmediate(text, autoDismiss)
    }

    private fun showOverlayToast(text: String, error: Boolean) {
        if (!canUseOverlay()) {
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
            // 章标题缓存：章任务的通知栏文案要用（阅读器关掉后拿不到目录）
            cacheChapterTitles(chapters)
            tocHandle = NovelTocDialog.show(
                context = this@NovelReaderActivity,
                chapters = chapters,
                stats = stats,
                // ⚠️ 目录的「已完成」必须与面板**同一个判据**（见 isChapterDone）：
                // 早先它拿 st.total（库里的行数）当分母，于是同一章在目录里是「已完成」、
                // 在面板里是「进行中」—— 用户报的「没翻完却显示全部完成」的另一半在这。
                totals = chapterTotals.toMap(),
                currentChapter = chapterIndex,
                dark = NovelPanelStyle.isDarkBackground(bgMode, prefs),
                onPick = { picked ->
                    tocHandle = null
                    gotoChapter(picked)
                },
                onNeedTotal = { ensureChapterTotal(it) },
            )
        }
    }

    private fun showMenu() {
        dismissTranslateModePopup()
        cancelPendingManualTranslateClick()
        lastTranslateClickMs = 0L
        val b = book ?: return
        if (supportFragmentManager.findFragmentByTag(NovelPanelSheet.TAG) != null) return
        // 章节目录要读文件（EPUB 读 OPF+NCX；TXT 跑一遍分章正则），不能在主线程取
        lifecycleScope.launch {
            val chapters = repository.chaptersOf(b)
            // 章标题缓存：章任务的通知栏文案要用（阅读器关掉后拿不到目录）
            cacheChapterTitles(chapters)
            val stats = runCatching { translator().chapterStats(b) }.getOrDefault(chapterStats)
            chapterStats = stats
            showMenuNow(chapters, stats)
        }
    }

    private fun openPersonalization(preferenceKey: String? = null) {
        persistProgress()
        returnedFromSettings = true
        startActivity(Intent(this, SettingPageActivity::class.java)
            .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
            .putExtra(SettingPageActivity.EXTRA_PREFERENCE_KEY, preferenceKey))
    }

    private fun showMenuNow(chapters: List<com.moe.starflow.novel.model.NovelChapterMeta>, stats: Map<Int, NovelChapterStat>) {
        if (isFinishing || isDestroyed) return
        val fontSp = NovelPanelStyle.fontSizeSp(prefs)
        val sheet = NovelPanelSheet(
            NovelPanelState(
                readerMode = NovelPanelStyle.readerMode(prefs),
                animation = animationMode,
                bg = bgMode,
                statusColors = NovelAppearance.statusColors(prefs),
                customBackground = NovelAppearance.customBackground(prefs),
                customText = NovelAppearance.customText(prefs),
                fontName = NovelReaderFont.label(this),
                fontSizeSp = fontSp,
                fontSizeAuto = NovelPanelStyle.fontSizeAuto(prefs),
                lineSpacingStep = NovelPanelStyle.lineSpacingStep(prefs),
                paragraphSpacingDp = NovelPanelStyle.paragraphSpacingDp(prefs),
                paddingDp = NovelPanelStyle.paddingDp(prefs),
                topPaddingDp = NovelPanelStyle.topPaddingDp(prefs),
                bottomPaddingDp = NovelPanelStyle.bottomPaddingDp(prefs),
                autoTurn = autoTurnEnabled,
                intervalSec = autoTurnIntervalSec,
                rotateLabel = rotateLabel(),
                isDarkPanel = NovelPanelStyle.isDarkBackground(bgMode, prefs),
                keepParagraphsWhole = NovelPanelStyle.keepParagraphsWhole(prefs),
                translateMode = NovelPanelStyle.translateMode(prefs),
                debounceMs = NovelPanelStyle.debounceMs(prefs),
                aheadBatches = NovelPanelStyle.aheadBatches(prefs),
                batchSize = NovelPanelStyle.batchSize(prefs),
                currentChapter = chapterIndex,
                chapters = chapters,
                chapterStats = stats,
                // 章任务状态一并给面板：卡片按钮的「暂停/继续/取消」与「等待」行要靠它
                chapterJobs = chapterHost?.chapterJobs?.value.orEmpty().associateBy { it.chapterIndex },
                waitingBatches = chapterHost?.waitingByChapter().orEmpty(),
                    activeBatches = chapterHost?.activeByChapter().orEmpty(),
                concurrency = NovelPanelStyle.storedConcurrency(
                    PreferenceManager.getDefaultSharedPreferences(this),
                ),
                batchWarnIndex = NovelBatchWarning.indexOf(NovelPanelStyle.batchWarnThreshold(prefs)),
                translateImages = NovelPanelStyle.translateImages(prefs),
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
                onOpenFontSettings = { openPersonalization("result_font") },
                onStatusColor = { state, color ->
                    NovelAppearance.setStatusColor(prefs, state, color)
                    applyBackground()
                    pushPanelState()
                },
                onResetStatusColors = {
                    NovelAppearance.resetStatusColors(prefs)
                    applyBackground()
                    pushPanelState()
                },
                onCustomColors = { background, text ->
                    NovelAppearance.saveCustom(prefs, background, text)
                    applyBackground()
                    pushPanelState()
                },
                onBackground = { v ->
                    NovelPanelStyle.setBackground(prefs, v)
                    applyBackground()
                    refreshCurrentVisual()
                    // ⚠️ 背景换了、面板里所有组件的配色都要跟着换 —— 不推的话得关掉再打开才对
                    pushPanelState()
                },
                onFontSize = { sp ->
                    NovelPanelStyle.setFontSizeSp(prefs, sp)
                    NovelPanelStyle.setFontSizeAuto(prefs, false)
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onFontSizeAuto = { auto ->
                    NovelPanelStyle.setFontSizeAuto(prefs, auto)
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onLineSpacing = { v ->
                    NovelPanelStyle.setLineSpacingStep(prefs, v)
                    loadChapter(chapterIndex, anchor = pendingAnchor)
                },
                onParagraphSpacing = { v ->
                    NovelPanelStyle.setParagraphSpacingDp(prefs, v)
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
                onBookData = { com.moe.starflow.library.BookDataActivity.open(this,
                    com.moe.starflow.library.BookRef(book!!.libraryLocationId, "novel", book!!.bookUuid), runtimePages = content?.pages?.size) },
                onSettings = { openPersonalization() },
                onTranslateMode = { m ->
                    cancelPendingManualTranslateClick()
                    lastTranslateClickMs = 0L
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
                // 「同时 API 请求数」：写的是**用户设的值**（本地引擎实际仍恒 1，判据在
                // TranslationConcurrency）。队列每轮现读 → 改完立刻对下一批生效。
                onConcurrency = { v ->
                    NovelPanelStyle.setConcurrency(PreferenceManager.getDefaultSharedPreferences(this), v)
                },
                // 「单批预警阈值」：面板给的是**档位下标**，落地成 token 值（判据用这个值）
                onBatchWarn = { index ->
                    NovelPanelStyle.setBatchWarnThreshold(prefs, NovelBatchWarning.thresholdAt(index))
                },
                // 「同时翻译图片」：写 prefs 就够了 —— 队列每轮的段号表与图片判据都现读它
                // （见 ensureQueue 的 chapterParaIndexes / isImagePara），下一批立刻生效。
                // ⚠️ 不用重启队列：在飞的那批照常跑完，改的是"后面还挑不挑图片"。
                onTranslateImages = { on ->
                    NovelPanelStyle.setTranslateImages(prefs, on)
                    if (!on) {
                        // 关掉时把"这张图还等着翻"的焦点与浮层收掉，免得停在一张永远不动的图上
                        focusedImagePara = null
                        refreshImageChrome()
                    }
                    content?.let { scrollTranslated = computeScrollTranslated(it) }
                    refreshOverlay()
                    refreshTranslationChrome()
                },
                currentTranslateMode = { NovelPanelStyle.translateMode(prefs) },
                // ⚠️ 章卡片的主按钮（用户口径）：没任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续。
                // 任务本体在**应用级宿主**里跑，退出阅读器也继续（进度走通知栏）。
                onChapterPrimary = { index -> onChapterPrimaryClicked(index) },
                onChapterSecondary = { index -> onChapterSecondaryClicked(index) },
                onChapterClear = { index -> clearChapterTranslations(index) },
                onDownload = { which -> exportNovel(which) },
                onChapterJump = { ch -> gotoChapter(ch) },
                onNeedChapterTotal = { ch -> ensureChapterTotal(ch) },
                onPanelOpened = {
                    translateModeChangeId += 1
                    dismissTranslateModePopup()
                    cancelPendingManualTranslateClick()
                    lastTranslateClickMs = 0L
                    // 与漫画一致：面板一打开就**暂停并回退手动** —— 用户在看面板时若按旧设置继续翻，
                    // 既浪费额度也可能翻错；要重新选模式才会继续
                    val hadForegroundTask = NovelPanelStyle.translateMode(prefs) != NovelTranslateMode.MANUAL ||
                        translationJob?.isActive == true || imageJob?.isActive == true
                    queue?.setPanelOpen(true)
                    showOverlay(null)
                    // 面板一开就退出选择模式：那套浮层被面板挡住，留着只会让状态对不上
                    exitSelection()
                    refreshChapterStats()
                    if (hadForegroundTask) {
                        pauseToManual(getString(R.string.reader_translate_force_stopped_to_manual))
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
     * 只清**某一章**译文（用户明确要求：不要一点就清整本；面板里已二次确认）。
     *
     * @param index 要清的章；默认当前章
     */
    private fun clearChapterTranslations(index: Int = chapterIndex) {
        val b = book ?: return
        lifecycleScope.launch {
            runCatching { translator().clearChapter(b, index) }
            // 只清当前章时才动屏幕上的这份译文（清别的章不该把读者这一章的内容抽掉）
            if (index == chapterIndex) {
                translations = emptyMap()
                // 译文都没了，"刚翻的是哪几段"也就无从谈起
                activeBatch = emptySet()
                failedBatch = emptySet()
                recentPara = emptySet()
                loadChapter(index, anchor = pendingAnchor)
            }
            refreshChapterStats()
            showOverlayToast(getString(R.string.novel_translate_cleared_chapter), error = false)
        }
    }

    /**
     * 右下角「清除译文」（长按多选后用）：**先弹窗报清多少段**，确认才动手（用户明确要求）。
     */
    private fun confirmClearSelectedTranslations() {
        val n = selectedPara.count { translations.containsKey(it) }
        if (n <= 0) return
        // 共享实现：弹窗用对应 night 模式的上下文建，底与字同源（见 ReaderDialogs）
        ReaderDialogs.show(this, NovelPanelStyle.isDarkBackground(bgMode, prefs)) {
            setTitle(R.string.novel_clear_translation_title)
            setMessage(getString(R.string.novel_clear_translation_confirm, n))
            setNegativeButton(R.string.user_cancel, null)
            setPositiveButton(R.string.novel_clear_translation_ok) { _, _ -> clearSelectedTranslations() }
        }
    }

    /**
     * 清除**选中段落**的译文。
     *
     * - 只清选中集里**真的有译文**的那几段：没翻过的段没什么可清，也不该算进弹窗那个数字
     * - 清除 = 把那些行**重置为未翻译**，不是删行（见 DAO `resetToIdle`：那几行是本章分母的来源）
     * - 清完**重载本章**：显示文本由译文变回原文、长度变了必须重排；顺带刷新翻译浮层
     *   （翻译/三态按钮按当前页重算）、底部进度条绿块、面板统计
     */
    private fun clearSelectedTranslations() {
        val b = book ?: return
        val chapter = chapterIndex
        val target = selectedPara.filter { translations.containsKey(it) }.sorted()
        if (target.isEmpty()) return
        // ⚠️ **清译文 = 用户的一次显式编辑 → 当场停掉前台的自动/增量队列并回退手动**（用户口径 2026-10-04）。
        //
        // 老写法是"把清掉的段记进账、本会话别再自动翻回来"。那份账带来两个更糟的后果：
        // ① 用户清完再开启自动/增量 → 那几段被静默跳过，**看着像"不会翻译"**（用户报的
        //    「清译文后重翻只有手动生效，自动和增量开启也没有任何翻译」）；
        // ② 判断"本屏/跨屏"还得跟着翻页维护，越写越绕。
        // 现在：清完就停队列 + 回退手动（**此刻不会**立刻翻回来 = 不会"清了个寂寞"），
        // 用户之后**再开启**自动/增量时，那几段就是普通"没译文的段"，照常被翻。
        // ⚠️ 只停**前台队列**，不动整章任务（那是应用级后台任务，见 [pauseToManual] 的说明）。
        pauseToManual()
        lifecycleScope.launch {
            runCatching { translator().clearParagraphs(b, chapter, target) }
                .onFailure {
                    showOverlayToast(
                        getString(R.string.novel_translate_failed_reason, it.message.orEmpty()),
                        error = true,
                    )
                    return@launch
                }
            refreshChapterStats()
            // 期间用户可能已经翻到别的章：那就别把他拽回来
            if (chapter == chapterIndex) {
                // 清掉的段不该再亮"刚翻完"（那已经不是"刚翻完"了）
                recentPara = recentPara - target.toSet()
                refreshRecentHighlight()
                loadChapter(chapter, anchor = pendingAnchor)
            }
            showOverlayToast(getString(R.string.novel_clear_translation_done, target.size), error = false)
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
            // 导出落点 = 手机的 Download（MediaStore），与漫画同一条路同一个提示语
            val r = runCatching { NovelExport.exportBook(this@NovelReaderActivity, b, repository, t, kind) }
                .getOrElse { Result.failure(it) }
            showOverlay(null)
            r.onSuccess { showOverlayToast(getString(R.string.toast_saved_to_download, it), error = false) }
                .onFailure { showOverlayToast(getString(R.string.novel_download_failed, it.message.orEmpty()), error = true) }
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
    private var librarySourceLease: java.io.Closeable? = null
    private val libraryReadLease by lazy { com.moe.starflow.library.LibrarySessions.use() }

    private fun persistProgress() {
        val b = book ?: return
        val updated = b.copy(
            lastReadChapter = chapterIndex,
            lastReadParaIndex = pendingAnchor.paraIndex,
            lastReadPage = if (isScrollMode()) 0 else currentPage(),
            lastReadAt = System.currentTimeMillis(),
        )
        book = updated
        NovelStore.patch(this, updated) { it.copy(lastReadChapter = updated.lastReadChapter,
            lastReadParaIndex = updated.lastReadParaIndex, lastReadPage = updated.lastReadPage,
            lastReadAt = updated.lastReadAt) }
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

    private fun toast(resId: Int) {
        UiUtils.showToast(this, getString(resId), true)
    }
}
