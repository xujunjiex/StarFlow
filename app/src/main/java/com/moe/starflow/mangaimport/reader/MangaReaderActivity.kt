package com.moe.starflow.mangaimport.reader

import android.content.ContentValues
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
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
import com.moe.starflow.databinding.ActivityMangaReaderBinding
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaPageOrderMigrator
import com.moe.starflow.sr.SuperResolutionEngines
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.chapterIndexOf
import com.moe.starflow.mangaimport.data.mangaChapterLabel
import com.moe.starflow.mangaimport.translate.ReaderTranslatePhase
import com.moe.starflow.mangaimport.translate.ReaderTranslationHub
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.ChapterTaskStage
import com.moe.starflow.translate.batch.InFlightTask
import com.moe.starflow.translate.batch.TranslationJobService
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.mangaimport.translate.TranslateClick
import com.moe.starflow.me.settings.SettingPageActivity
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.utils.AppNotice
import com.moe.starflow.utils.LogCollector
import androidx.preference.PreferenceManager
import com.moe.starflow.utils.TranslationConcurrency
import com.moe.starflow.utils.ReaderDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/**
 * 漫画阅读器（复刻 Kototoro）：4 阅读模式(LTR/RTL/竖排/Webtoon) +
 * 4 翻页动画 + 6 阅读背景 + 颜色矫正 + 自动翻页 + 底部四图标工具栏 + 薄进度条(拖拽/长按预览) +
 * 右下角单个翻页按钮 + 右上角菜单键。横屏与竖屏同为单页（双页显示已移除）。
 */
class MangaReaderActivity : AppCompatActivity() {

    companion object {
        /** 面板推送最小间隔：状态每页要变 4 次（QUEUED/OCR/TRANSLATE/结算），不节流会持续重排整表。 */
        private const val PANEL_PUSH_MIN_MS = 300L
        const val EXTRA_MANGA_ID = "manga_id"

        /** 通知栏点进来时带的目标页（该章第一页）；不传 = 按断点续读。 */
        const val EXTRA_START_PAGE = "start_page"

        /** ReaderAnim 日志最小间隔（逐帧打日志会把 300 行日志窗口冲光，见 `onPageScrolled`）。 */
        private const val ANIM_LOG_MIN_MS = 250L

        /** 章节目录弹窗的实时刷新间隔（只在弹窗打开时跑。见 `openChapterDialog`）。 */
        private const val CHAPTER_TOC_REFRESH_MS = 400L

        private const val PREFS = "manga_reader"
        private const val KEY_MODE = "reader_mode"          // 0 LTR 1 RTL 2 竖排 3 Webtoon
        /** Webtoon（连续滑动）显示态：false=原图 true=译文（默认译文）。由模式分段器上「连续滑动」按钮两态控制。 */
        private const val KEY_WEBTOON_TRANSLATED = "reader_webtoon_translated"
        private const val KEY_ANIM = "reader_animation"     // 0 无 1 默认 2 高级 3 仿真
        private const val KEY_BG = "reader_background"      // 0 默认 1 浅 2 深 3 白 4 黑 5 自动
        private const val KEY_AUTO_TURN = "reader_auto_turn"
        private const val KEY_INTERVAL = "reader_auto_turn_interval"
        private const val KEY_BRIGHTNESS = "reader_color_brightness"
        private const val KEY_CONTRAST = "reader_color_contrast"
        private const val KEY_INVERT = "reader_color_invert"
        private const val KEY_GRAY = "reader_color_grayscale"
        private const val KEY_BOOK = "reader_color_book"
        private const val KEY_ROTATE = "reader_rotate_mode"

        /** 翻译按钮双击判定窗口（与悬浮球 LONG/DOUBLE 语义一致）。 */
        private const val DOUBLE_CLICK_MS = 300L

        /** 上下 UI 显隐的淡入淡出时长（短促，跟随系统「动画时长」缩放）。 */
        private const val CHROME_FADE_MS = 160L


        /** 重建时恢复现场用（见 [onSaveInstanceState]）。 */
        private const val STATE_CURRENT_PAGE = "reader_state_page"
        private const val STATE_FROM_SETTINGS = "reader_state_from_settings"
        private const val STATE_TRANSLATE_MODE = "reader_state_translate_mode"
    }

    private lateinit var binding: ActivityMangaReaderBinding
    private lateinit var manga: ImportedManga
    private lateinit var source: ReaderPageSource
    private lateinit var prefs: android.content.SharedPreferences

    /**
     * 章节表（由 [ReaderPageSource] **按实际文件**推导，不是清单里那份）。
     *
     * 「当前章」不做独立状态：它恒等于**当前页所在章**（[currentChapterIndex]）——
     * 面板选中、顶部胶囊、批量翻译都取同一个值，翻页即跟随，不存在两套状态对不上的可能。
     */
    private var chapters: List<MangaChapter> = emptyList()

    private var currentPage = 0
    private var colorFilter = ReaderColorFilter.EMPTY
    private var autoTurnEnabled = false
    private var autoTurnIntervalSec = 5
    private var autoTurnJob: Job? = null

    /** 在途的打包下载任务（原文/译文/双语共用一个槽位，防并发导出）。 */
    private var exportJob: Job? = null
    private var lastInteractionMs: Long = 0L
    private var rotateMode = 0

    private var mode = 0

    /** Webtoon 显示态：false=原图 true=译文（见 KEY_WEBTOON_TRANSLATED）。 */
    private var webtoonTranslated = false
    private var animationMode = 1
    private var bgMode = 0

    private var pageAdapter: ReaderPageAdapter? = null

    /** 翻译按钮双击判定用的上次单击时刻（elapsedRealtime）。 */
    private var lastTranslateClickMs = 0L

    /**
     * 上下 UI（顶部返回/菜单/页码 + 底部进度条/翻译浮层组）是否隐藏。
     * 点屏幕正中间切换，**状态一直保持**（没有自动恢复计时）—— 隐藏后一直隐藏，再点中间才恢复。
     * 只在本次阅读会话内有效，不持久化（重进阅读器恢复显示）。
     */
    private var chromeHidden = false

    /** 尺寸变化（旋转/分屏）之前所在的页；-1 = 无待对齐。见 [realignPagerAfterResize]。 */
    private var pendingRealignPage = -1

    /** 本次离开是去「个性化设置」页 —— 返回时重建 Activity，让设置项真正生效（见 onStart）。 */
    private var returnedFromSettings = false

    /**
     * 重建前记下的翻译模式；**-1 表示本次不是"从设置页返回"触发的重建**（全新进入阅读器时就是这个值）。
     *
     * ⚠️ 恢复时必须判 `>= 0`，不能判 `!= MODE_MANUAL` —— 后者对 -1 为真，
     * 会把 -1 当成模式塞进控制器（历史 bug：一进阅读器就自动开翻）。
     */
    private var restoredTranslateMode = -1

    /** 章节任务的收尾监听器（onDestroy 要摘掉，否则后台任务回调到已销毁的 Activity）。 */
    private var jobFinishedListener: ((Int, Int, Int, Boolean) -> Unit)? = null

    /** 「已暂停回退手动」这次退出是否已经提示过（避免 onStop + onDestroy 重复弹）。 */
    private var pausedToManualNotified = false

    /**
     * 每页那条「正在超分…」芯片的句柄（页号 → `AppNotice.showRunning` 返回的 id）。
     *
     * ⚠️ 必须**按页**存，不能只存一个：整章批量时几页会同时在等 `OcrLock`，
     * 单变量会让后一页覆盖前一页的句柄 → 前一页收尾时摘掉后一页的芯片。
     * 见 [showSrRunning] / [clearAllSrRunningChips]。
     */
    private val srRunningChipByPage = HashMap<Int, Long>()

    /** 仿真/高级动画共享状态（折线触点 + 翻页方向）。 */
    private val animState = ReaderAnimationState()
    private var webtoonTapDetector: GestureDetector? = null
    private var translationController: ReaderTranslationController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // UI 同步字体（开关开启时）：挂 Factory2，inflate 即应用。须在 super.onCreate 前挂。
        com.moe.starflow.utils.FontSync.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = prefs.getInt(KEY_MODE, 0)
        webtoonTranslated = prefs.getBoolean(KEY_WEBTOON_TRANSLATED, true)
        animationMode = prefs.getInt(KEY_ANIM, 1)
        bgMode = prefs.getInt(KEY_BG, 0)
        autoTurnEnabled = prefs.getBoolean(KEY_AUTO_TURN, false)
        autoTurnIntervalSec = prefs.getInt(KEY_INTERVAL, 5)
        rotateMode = prefs.getInt(KEY_ROTATE, 0)
        colorFilter = loadColor()

        val id = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        manga = ImportedMangaStore.load(this).firstOrNull { it.id == id }
            ?: run { finish(); return }
        source = ReaderPageSource(manga.isArchive, manga.localRoot)
        if (source.size == 0) {
            // 本地文件已丢失/损坏（可能被手动删除）：不再展示空白阅读器。
            // ⚠️ 走「短时报错」：这里紧跟着就 finish()，`onDestroy` 那次浮层清屏会吃掉普通提示，
            //    而底部 Toast 在 Activity 收尾时也常被系统吞掉 —— 那就是用户报的「点了没反应」。
            notifyUser(getString(R.string.reader_file_lost), AppNotice.Style.BRIEF_ERROR)
            finish()
            return
        }
        chapters = source.chapters
        // 自愈：清单里的章节表与实际文件不一致（老条目没回填、或文件被换过）→ 以实际为准回写，
        // 这样书架的「共N章 / 第x章」不会长期显示旧数据。写清单是「读-改-写」，
        // 放在 IO 线程做，别卡住开阅读器
        if (chapters.isNotEmpty() && chapters != manga.chapters) {
            val latest = chapters
            manga = manga.copy(chapters = latest)
            lifecycleScope.launch(Dispatchers.IO) {
                // ⚠️ 用 setChapters（锁内只改这一个字段），**不要** update(manga.copy(...))：
                //    那会拿"进入阅读器那一刻的整条快照"整条替换，把这期间写进去的
                //    lastReadPage（翻页即写）回滚掉 —— 表现是「翻了几页后进度自己退回去」。
                runCatching { ImportedMangaStore.setChapters(applicationContext, manga.id, latest) }
            }
        }

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyPager()

        // 重建（旋转 / 从设置页返回）时恢复现场：当前页、翻译模式、以及"这是从设置页返回"
        if (savedInstanceState != null) {
            returnedFromSettings = savedInstanceState.getBoolean(STATE_FROM_SETTINGS, false)
            restoredTranslateMode = savedInstanceState.getInt(STATE_TRANSLATE_MODE, -1)
            pendingRealignPage = savedInstanceState.getInt(STATE_CURRENT_PAGE, -1)
        }
        // 从设置返回触发的重建：回到用户当时那一页（lastReadPage 只在翻页时写库，可能滞后）
        // 通知栏点进来时带 EXTRA_START_PAGE：优先落在那张（该章第一页）
        val intentPage = intent.getIntExtra(EXTRA_START_PAGE, -1)
        val startPage = when {
            intentPage >= 0 -> intentPage
            pendingRealignPage >= 0 -> pendingRealignPage
            else -> manga.lastReadPage
        }
        goToPage(startPage.coerceIn(0, (source.size - 1).coerceAtLeast(0)))

        // 阅读器内嵌翻译：载入每页记录（含失败/成功态），接右下角翻译按钮与进度条。
        // ⚠️ 控制器从 **ReaderTranslationHub** 取（应用级 scope + 按书缓存）：章节批量任务是
        // 后台任务，控制器不能在 Activity 销毁时一起消失；重进阅读器要拿回同一个实例
        // （任务、页记录、渲染缓存都还在）。
        // 把绑定抽成局部函数：bind 之后要自检一次「是不是刚被 hub 回收了」并重绑（见下）
        fun bindController(c: ReaderTranslationController) {
            c.bind(
                loadFull = { source.loadFull(it) },
                currentPage = { currentPage },
                pageCount = { source.size },
                // 上屏超采样倍率 = 显示宽 / 页图宽（见 ReaderTranslationController.renderScaleFor）。
                // 取实际阅读区宽度；未布局时退回屏幕宽（两者在这套全屏布局里一致）。
                displayWidth = {
                    binding.viewPager.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                },
            )
            // Webtoon 译图走采样解码（超长页直接全解析会 OOM），气泡坐标按采样比例缩放
            c.bindWebtoonSource(
                loadWebtoon = { p ->
                    val w = binding.webtoonList.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    source.loadWebtoon(p, w)
                },
                originalWidth = { p -> source.originalWidth(p) },
            )
            c.onVisual = { runOnUiThread { applyPageVisual(currentPage) } }
            c.onPhase = ::onTranslatePhase
            // 超分提示（失败原因 / 其它）统一走 app 状态浮层。⚠️ 必须在主线程调：
            // 控制器可能从 IO 协程发过来（它内部只在自动超分那条路径 `withContext(Main)`）。
            c.onSrNotice = { text, isError ->
                runOnUiThread { if (!isFinishing) showSrNotice(text, isError) }
            }
            // 超分「进行中」的开合（控制器保证 true/false 成对，含失败与取消）。同一约定：
            // 回调已经在主线程，`runOnUiThread` 只是防御性兜底。
            c.onSrProgress = { page, running ->
                runOnUiThread { if (!isFinishing) showSrRunning(page, running) }
            }
            // Webtoon 显示态（原图/译文）落在控制器上：applyPager()/预热都按它决定渲不渲染
            c.setWebtoonTranslated(webtoonTranslated)
        }
        var controller = ReaderTranslationHub.controllerFor(applicationContext, manga)
        bindController(controller)
        // ⚠️ `controllerFor()` 与 `bind()` 之间，hub 的收集协程可能已判定「无 UI 且无任务」把它回收
        //（那一刻 `uiAttached` 还是 false）→ 手上这个实例已 shut down，之后「翻译本章」全都不动。
        // 发现已关闭就换一个新实例重绑一次（hub 里那份也已被移除，controllerFor 会新建）。
        if (controller.closed) {
            controller = ReaderTranslationHub.controllerFor(applicationContext, manga)
            bindController(controller)
        }
        translationController = controller
        // ⚠️ 只有**确实是从设置页返回触发的重建**才恢复翻译模式（`>= 0` 就是那个判据）。
        // 绝不能只看"模式 != 手动"：控制器是刚新建的，translateMode 恒为手动，
        // 而 `restoredTranslateMode` 在全新进入阅读器时是 **-1**，
        // `-1 != MODE_MANUAL` 为真 → 把 -1 当模式塞进控制器 → **一进阅读器就自动开翻**（踩过）。
        if (restoredTranslateMode >= 0) {
            translationController?.setMode(restoredTranslateMode)
        }
        lifecycleScope.launch {
            // ⚠️ **页序迁移必须在 load() 之前**：load() 一读就是按 `pageIndex` 取行，而章节系统
            // 改变了权威页序（最外层散图排到第 0 章最前）→ 对「根散图 + 子目录混放」的老书，
            // 同一个下标指向的是另一张图，不迁移就会把译文/气泡挂到错页上（详见 `MangaPageOrder`）。
            runCatching { MangaPageOrderMigrator.migrateIfNeeded(applicationContext, manga, source) }
                .onFailure { LogCollector.w("MangaReader", "页序迁移失败（不影响阅读）", it) }
            translationController?.load()
            refreshTranslationChrome()
            refreshProgressTranslation()
            // ⚠️ 启动就处于 Webtoon 时，onCreate 里的 applyPager() 早于控制器创建 → 那次预热是空跑。
            // 等 load() 拿到记录后再预热一次（此时才知道哪些页是已翻译的）。
            if (mode == 3) {
                translationController?.prewarmWebtoon(currentPage)
                refreshWebtoonRange(currentPage)
            }
        }
        setupTranslationUi()
        setupChapterBatchUi()
    }

    override fun onStart() {
        super.onStart()
        updateAutoTurn()
        // ⚠️ 从设置页返回必须**重建阅读器**：个性化里那一批参数（字号/颜色/字距行距/竖排方向/
        // 文字合并…）是在 Activity 创建时被读进缓存、并由已经渲染好的译图固化的，
        // 就地刷新既漏项又容易只改一半 —— 直接重建是最可靠、也最容易解释的做法。
        // 首次 onStart 不算「返回」，不会触发。
        if (returnedFromSettings) {
            // 先置回再 recreate：onSaveInstanceState 会把当时的字段值再存一次，
            // 不在这儿清掉的话，重建出来的那个实例仍带着 true → 无限重建。
            returnedFromSettings = false
            recreate()
            return
        }
        // 「译文替换表」可能在设置页被改过（从**主设置页**进来时不会走上面的 recreate）：
        // 替换表是在**渲染时**套用的（overlay 后期才画），所以作废已渲染译图后重渲染当前页即可 ——
        // 译文从数据库行重建，**不调翻译 API、不需要重翻**。
        translationController?.let { controller ->
            if (controller.refreshIfRulesChanged()) {
                if (mode == 3) {
                    // Webtoon：译图由 prewarmWebtoon 逐页重渲染，每渲染完一页自己会经 onVisual 重绑。
                    // ⚠️ 这里若先 applyPageVisual（rebind），缓存已清空 → 适配器回落到 source.loadWebtoon
                    // 原图 → 可见页先闪回"未翻译"再变译文。
                    controller.prewarmWebtoon(currentPage)
                } else {
                    applyPageVisual(currentPage)
                }
            }
        }
        // ⚠️ 从「超分模型管理」等外部页面回来时，面板与右下角都是**打开那一刻的快照** ——
        // 用户在那边换了模型，这里必须重读（真机反馈「切换超分模型返回后颜色面板没有刷新更新」）。
        refreshTranslationChrome()
        (supportFragmentManager.findFragmentByTag(ReaderMenuSheet.TAG) as? ReaderMenuSheet)?.notifySrChanged()
        translationController?.resumeFromBackground()

    }

    override fun onStop() {
        super.onStop()
        autoTurnJob?.cancel()
        autoTurnJob = null
        // ⚠️ 用户口径：「退出阅读器自动暂停回退手动，但**没有 app 的提示信息**」——
        // 原来这条提示写在 `onDestroy` 里，而 Activity 正在销毁时弹的 Toast 经常被系统直接吞掉
        // （Activity 一销毁 Toast 就跟着没了）。所以**在 onStop 里先判 isFinishing**（真的要关掉了），
        // 那一刻窗口还在，提示看得见；onDestroy 里保留一个兜底（正常路径不会再弹第二次）。
        if (isFinishing && translationController?.translateMode?.value != ReaderTranslationController.MODE_MANUAL) {
            pausedToManualNotified = true
            showPausedToManualNotice()
        }
        // 切后台必须暂停队列：lifecycleScope 不会因 onStop 取消，否则 OCR + 翻译 + HyMT2 推理
        // 会在后台整段跑，且常驻状态芯片（系统窗口）会一直盖在别的应用上
        translationController?.pauseForBackground()
        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
    }

    /**
     * 「翻译进程已强制退出（，回退到手动模式）」的提示。
     *
     * 用户口径（2026-09-28）：**所有**打断翻译的入口（打开面板 / 退出阅读器 / 取消整章任务）都要
     * 说清"进程已被强制结束"；只有**确实发生了"回退到手动"**时才接后半句
     * （整章任务本来就跑在手动模式下 → 只报前半句）。
     *
     * 状态浮层开着时优先用它（**系统窗口，阅读器正在关闭也看得见**），
     * 画不出来时由 [notifyUser] 统一退回 Toast。
     */
    private fun showForceStoppedNotice(toManual: Boolean) {
        notifyUser(
            getString(
                if (toManual) R.string.reader_translate_force_stopped_to_manual
                else R.string.reader_translate_force_stopped
            )
        )
    }

    /** 打开面板 / 退出阅读器：把在途翻译**强制退出**并回退手动 → 提示带"回退到手动模式"。 */
    private fun showPausedToManualNotice() = showForceStoppedNotice(toManual = true)

    /**
     * ⚠️ **少了它，点第二条章节通知「点了没反应」**：`TranslationJobService.openReaderIntent`
     * 用 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP` 发起，阅读器已经在前台时
     * 走的是本回调，**不再走 `onCreate` 里那段读 `EXTRA_START_PAGE` 的逻辑** → 页面不跳转。
     * 小说阅读器为同一个功能实现了它（`NovelReaderActivity.onNewIntent`），漫画侧此前漏了。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getIntExtra(EXTRA_START_PAGE, -1)
        if (target < 0 || target >= source.size) return
        if (target == currentPage) return
        goToPage(target)
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

    // ===== 分页 / Webtoon 切换 =====

    private fun applyPager() {
        // 重建分页器（含 Webtoon ↔ 翻页互切）后，尺寸变化前记下的那一页已无意义：
        // ⚠️ Webtoon 下 viewPager 是 GONE → 它的布局回调**永远不触发** → realignPagerAfterResize
        // 拿不到机会清空，pendingRealignPage 会一直武装着；等用户切回左右/竖排时 viewPager
        // 首次布局就命中「有 old 尺寸」的分支 → 把阅读位置拽回旋转时那一页，还会写回 lastReadPage
        pendingRealignPage = -1
        if (mode == 3) {
            // Webtoon：连续竖滚列表（无翻页动画/自动翻页，滚动即翻页）
            binding.webtoonList.adapter = WebtoonAdapter(source) { colorFilter }.also { a ->
                // 已翻译页显示渲染好的译图（未翻译页回落原图）。
                // 渲染由 prewarmWebtoon 按当前位置上下几页限范围预热，适配器只读缓存。
                a.setPageImageProvider { p -> translationController?.webtoonCachedBitmap(p) }
            }
            binding.webtoonList.layoutManager = LinearLayoutManager(this)
            // ⚠️ 必须关掉默认 item 动画。Webtoon 靠 notifyItemChanged（译图渲染完成）刷新单页，
            // 默认的 DefaultItemAnimator 会给每次刷新叠一层**淡出淡入** → 页面持续闪、像"一直在变"。
            // 同理 notifyDataSetChanged（切原图/译文）也会整体过一遍淡入淡出。
            binding.webtoonList.itemAnimator = null
            binding.webtoonList.visibility = View.VISIBLE
            binding.viewPager.visibility = View.GONE
            translationController?.prewarmWebtoon(currentPage)
            return
        }
        // 离开 Webtoon：作废 Webtoon 渲染缓存（换回翻页模式后不再需要）
        translationController?.clearWebtoonCache()
        binding.webtoonList.adapter = null
        binding.webtoonList.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE

        // 竖排模式（Koto VERTICAL）= 竖向整页 Pager，逐页上/下切换；其余横向
        binding.viewPager.orientation =
            if (mode == 2) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
        // 预绑定邻页：当前页停住后空闲期即后台解码下一页 → 滑动翻页时邻页图已就位，
        // 消除「翻页瞬间旧页颜色/空白闪烁」（配 loadTo 绑定即清残留，快速连翻不显旧图）
        binding.viewPager.offscreenPageLimit = 1
        // 禁用 RecyclerView 默认 item 动画：notifyItemChanged（翻页落定/三态重绑）会触发 change alpha
        // 淡入淡出 → 刚落定的页「透明渐变、轻微变白/变黑」。分页器无 item 动画需求，直接关掉。
        (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.itemAnimator = null

        // 横竖屏都是单页（双页显示已移除）：旋转不需要重建 adapter
        pageAdapter = ReaderPageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap) { currentPage }
        binding.viewPager.adapter = pageAdapter
        applyDirection()
        applyAnimation()
        applyPageImageSource()
    }

    /**
     * 翻页动画：0无(直接跳) / 1默认(滑动) / 2高级(封面叠放) / 3仿真(页脚卷曲翻页)。
     * 高级/仿真按当前横/竖模式 + 正/反向选择 transformer（Koto 语义）。
     */
    private fun applyAnimation() {
        if (mode == 3) return // Webtoon 无翻页动画
        // 关键：先清除所有已附加页上旧 transformer 残留的 alpha/缩放/旋转/折叠，
        // 否则切动画（尤其切到 0/1 = null transformer）时旧效果粘在页面上 → 堆叠/黑屏
        resetPageTransforms()
        // 清空上一动画的共享锚点/导航状态，避免跨动画切换残留（切到无/默认/高级/仿真互相干扰）
        animState.anchorPage = currentPage
        animState.navigationProgress = 0f
        animState.isBackward = false
        animState.foldStartFraction = 0.85f
        binding.viewPager.setPageTransformer(
            when (animationMode) {
                0 -> NoneTransformer(isVertical = mode == 2, animState)   // 无动画：全程静止，落整直接换页
                1 -> null                                       // 默认滑动
                2 -> CoverTransformer(isVertical = mode == 2, isReversed = mode == 1, animState)
                3 -> SimulationTransformer(isVertical = mode == 2, isReversed = mode == 1, animState)
                else -> null
            }
        )
    }

    /** 复位 viewPager 内所有页面 View 的 transform + 折叠状态（换 transformer 前调用）。 */
    private fun resetPageTransforms() {
        val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
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
     * 视口尺寸变化后重新对齐分页器（旋转 / 分屏 / 折叠），由 [setupOverlays] 里的
     * viewPager 布局回调触发 —— 只有在新尺寸的布局跑完之后才拿得到正确的几何。
     *
     * ① 吸附回**尺寸变化前**那一页：⚠️ 必须直接对**内部 RecyclerView** 调 `scrollToPosition`。
     *    `ViewPager2.setCurrentItem(当前页, false)` 在「已经是当前页且滚动空闲」时**直接 return**
     *    （ViewPager2 源码里的早退分支），根本不会重新吸附 —— 旋转后正是这个状态。
     * ② 重绑目标页：让页 item 按新尺寸重新测量/布局、页内 ZoomableImageView 重新 fitCenter。
     *    ⚠️ 走 [applyPageVisual] 而不是裸 `notifyItemChanged`：它会先把译图渲染/预热进缓存再重绑，
     *    否则译图恰好被 LRU 淘汰时重绑会退回显示原图。同槽位重绑不清图（见 ReaderAdapters.loadTo）→ 不闪白。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 设置页返回时本 Activity 已被销毁重建，字段不保留 —— 当前页、翻译模式、和
        // 「这次重建正是从设置页返回」都要带过去，否则会跳回上次写库的页并把模式打回手动。
        outState.putInt(STATE_CURRENT_PAGE, currentPage)
        outState.putBoolean(STATE_FROM_SETTINGS, returnedFromSettings)
        outState.putInt(STATE_TRANSLATE_MODE, restoredTranslateMode)
    }

    private fun realignPagerAfterResize() {
        if (mode == 3) { pendingRealignPage = -1; return } // Webtoon 行高由 webtoonList 的宽度监听重算
        // 目标页用**尺寸变化前**记下的那一页：新布局可能让 ViewPager2 把「吸附页」重算成别页
        // 并派发 onPageSelected，那时 currentPage 已被改掉，照它对齐会停到错的一页上。
        val target = if (pendingRealignPage >= 0) pendingRealignPage else currentPage
        pendingRealignPage = -1
        val rv = binding.viewPager.getChildAt(0) as? RecyclerView ?: return
        rv.scrollToPosition(target)
        // 上面那次重排若让 ViewPager2 派发了 onPageSelected，currentPage 会漂到别页 → 拉回来
        // （与 onPageSelected 相同的副作用集合：断点续读 / 队列窗口 / 页码；无动画模式的锚点也要同步）
        if (currentPage != target) {
            currentPage = target
            if (animationMode == 0) animState.anchorPage = target
            lastTranslateClickMs = 0L
            ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = target))
            translationController?.onCurrentPageChanged()
            refreshOverlay()
        }
        applyPageVisual(target)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 趁布局还没跑（currentPage 还没被 resize 期间的 onPageSelected 改掉）先记下当前页
        pendingRealignPage = currentPage
        applyBackground()
        applyAnimation()
        // 横竖屏都是单页、翻译也不再按朝向禁用 → 旋转后无需重建 adapter，也不用停翻译队列
        refreshTranslationChrome()
    }

    override fun onDestroy() {
        // 退出阅读器：停止前台队列并回退手动模式（章节后台任务保留）。
        // ⚠️ 提示统一在 onStop 里发（`isFinishing` 那条路径），这里**只兜底**：
        // Activity 正在销毁时弹的东西经常看不见
        setTranslatingPulse(false)
        val wasActive = translationController?.translateMode?.value != ReaderTranslationController.MODE_MANUAL
        translationController?.let { c ->
            jobFinishedListener?.let { l -> c.removeJobFinishedListener(l) }
            jobFinishedListener = null
            // ⚠️ 只停前台队列 + 解除 UI 绑定，**保留章节后台任务**（用户要求关掉阅读器也继续翻）
            c.onReaderClosed()
        }
        // ⚠️ 关闭后再问一次 hub 能不能回收：任务跑完就再也没有 `chapterJobs` 发射了，
        // 只靠那个收集协程等不到这次机会 → 控制器（renderLru 预算 100MB）+ 页图数据源常驻到进程结束。
        // 有任务在跑时 `releaseIfIdle` 会自己判断并拒绝。
        if (::manga.isInitialized) ReaderTranslationHub.onReaderClosed(manga.id)
        // ⚠️ 必须清掉状态浮层：它是**进程级单例 + TYPE_APPLICATION_OVERLAY 系统窗口**，
        // 退出阅读器后「检测中…／翻译中…」会挂在桌面/其它页面上，且没有任何入口能消掉
        // （直到下一次翻译成功或失败）。翻译在途时退出阅读器就会触发。
        // ⚠️ 先清「正在超分…」的句柄表再 dismiss：`dismiss()` 会把芯片一起收走，
        // 但**不会**动我们这张表 —— 留着过期 id 没害处（id 永不复用），但没必要攒着。
        clearAllSrRunningChips()
        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
        // 兜底：onStop 没发过（进程被杀/未走 onStop 的路径）而当时确实在自动翻 → 补一次
        if (wasActive && !pausedToManualNotified) {
            notifyUser(getString(R.string.reader_translate_force_stopped_to_manual))
        }
        super.onDestroy()
    }

    /** 章节目录弹窗打开期间的实时刷新任务（关闭即结束）。 */
    private var chapterTocRefresh: Job? = null

    /** 「正在翻译」时翻译按钮的呼吸动画（结束/取消时停掉）。 */
    private var translatingPulse: AnimatorSet? = null

    /** ReaderAnim 日志限流（见 onPageScrolled：逐帧打日志会把 300 行日志窗口冲光）。 */
    private var lastAnimLogMs = 0L
    private var lastAnimLogPage = -1

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            // 滚动标记（连续）。回翻判定用「标记相对锚点」而非瞬时增量：
            // 瞬时增量（marker < prev）会在用户中途反向/松手时立刻翻转 isBackward → 折叠镜像瞬间
            // 跳到另一边。相对锚点判定只在 marker 越过 anchor、那次折叠归零（不可见）时才翻转 → 反向平滑。
            val marker = position + positionOffset
            // 锚点：仅在静止（吸附到整数）时更新为当前停留页；转场期间保持不变
            if (kotlin.math.abs(marker - kotlin.math.round(marker)) < 0.02f) {
                animState.anchorPage = currentPage
            }
            animState.isBackward = marker < animState.anchorPage
            // 导航进度 = 滚动标记 - 锚点（Kototoro resolveAdvancedNavigationProgress）
            animState.navigationProgress = (marker - animState.anchorPage).coerceIn(-1f, 1f)
            if (mode == 1 || animationMode >= 2) {
                // ⚠️ **不要每帧都打**：`onPageScrolled` 每帧回调一次（~8ms），I 级日志会把
                // `starflow.log` 的 300 行窗口几秒内冲光 —— 用户报"翻译没反应，日志里什么都没有"，
                // 根因就是翻译日志被打进来几秒后被这些刷屏挤掉了（排查时完全看不到真凶）。
                // 只在「翻到新页」或「停稳（offset≈0）」时打一条，并限流到 ≥250ms。
                val now = SystemClock.elapsedRealtime()
                val settled = positionOffset < 0.01f
                val pageChanged = position != lastAnimLogPage
                if ((pageChanged || settled) && now - lastAnimLogMs >= ANIM_LOG_MIN_MS) {
                    lastAnimLogPage = position
                    lastAnimLogMs = now
                    LogCollector.i(
                        "ReaderAnim",
                        "scroll p=$position off=$positionOffset marker=$marker nav=${animState.navigationProgress} " +
                            "anchor=${animState.anchorPage} mode=$mode anim=$animationMode bw=${animState.isBackward}"
                    )
                }
            }
        }

        override fun onPageSelected(position: Int) {
            currentPage = position.coerceIn(0, (source.size - 1).coerceAtLeast(0))
            // 无动画模式：选中后锚点同步到新页，避免下次拖拽时还把上一页钉在中心
            if (animationMode == 0) animState.anchorPage = currentPage
            ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = currentPage))
            // 翻页后重置双击判定窗口：否则「单击 → 翻页 → 300ms 内再单击」会被当成双击，
            // 直接取消翻译（连击状态在 Activity 这侧，controller 的 onCurrentPageChanged 管不到）
            lastTranslateClickMs = 0L
            translationController?.onCurrentPageChanged()
            refreshOverlay()
            refreshTranslationChrome()
            applyPageVisual(currentPage)
        }
    }

    private fun applyDirection() {
        // Webtoon / 竖排 无左右方向概念
        if (mode == 3 || mode == 2) return
        val isRtl = mode == 1
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        binding.viewPager.layoutDirection = dir
        (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.layoutDirection = dir
    }

    private fun handleTap(x: Float, y: Float) {
        val w = binding.viewPager.width.toFloat()
        val h = binding.viewPager.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (consumeChromeOrMenuTap(x, y, w, h)) return
        if (mode == 2) {
            // 竖排：点击上半=上一页，下半=下一页
            turnPage(if (y >= h / 2f) 1 else -1)
            return
        }
        val isRtl = mode == 1
        val goNext = if (isRtl) x < w / 2f else x >= w / 2f
        turnPage(if (goNext) 1 else -1)
    }

    /**
     * 单击的「非翻页」分区（分页 / Webtoon 共用），返回 true 表示这次点击已被消费、不要再翻页。
     * - 右上角（y 顶部 22% 且 x 右侧 28%）：弹出菜单
     * - 屏幕正中间的一格（Koto 九宫格的中格，横竖各三等分）：显隐上下 UI
     *
     * ⚠️ 只有**单击确认**（[GestureDetector.onSingleTapConfirmed]）才会走到这里：滑动翻页会把
     * 事件交给 ViewPager2/RecyclerView 并触发 ACTION_CANCEL，双击缩放走 onDoubleTap —— 两者都
     * 不会产生单击确认，所以不会误触；左右/上下翻页区也都在中格之外。
     */
    private fun consumeChromeOrMenuTap(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (y < h * 0.22f && x > w * 0.72f) {
            showMenu()
            return true
        }
        if (isCenterTapZone(x, y, w, h)) {
            toggleChrome()
            return true
        }
        return false
    }

    /** 屏幕正中间一格的判定（Koto 九宫格：横竖各三等分取中格）。 */
    private fun isCenterTapZone(x: Float, y: Float, w: Float, h: Float): Boolean =
        x >= w / 3f && x < w * 2f / 3f && y >= h / 3f && y < h * 2f / 3f

    /** 点屏幕中间：切换上下 UI 显隐（一直保持，无自动恢复）。 */
    private fun toggleChrome() {
        chromeHidden = !chromeHidden
        lastInteractionMs = SystemClock.elapsedRealtime()
        applyChromeVisibility()
    }

    /**
     * 应用上下 UI 显隐（淡入淡出）。翻译浮层组与**独立的超分浮层组**各有自己的显隐约束，
     * 显示前先让 [refreshTranslationChrome] 把它们定到位，不该显示的就不参与淡入。
     */
    private fun applyChromeVisibility() {
        if (!chromeHidden) refreshTranslationChrome()
        // 本就不显示的（Webtoon / 未译页 / 超分关闭）不参与动画，否则会把它强行淡出来
        val shown = listOf(binding.translateGroup, binding.srGroup)
            .filter { it.visibility == View.VISIBLE }
        for (v in chromeViews() + shown) {
            if (chromeHidden) fadeOutChrome(v) else fadeInChrome(v)
        }
    }

    /** 固定四件套：顶部返回 / 菜单 / 页码 + 底部进度条（翻译浮层组按页状态单独判定）。 */
    private fun chromeViews(): List<View> = listOf(
        binding.btnBack, binding.btnMenu, binding.tvPageIndicator, binding.bottomProgress,
    )

    /** 淡出并在结束后置 GONE —— ⚠️ 只把 alpha 打到 0 的话控件**仍然可点**（会误触到看不见的按钮）。 */
    private fun fadeOutChrome(v: View) {
        // 淡出的 160ms 里控件还在屏上（alpha 渐到 0），期间点上去仍会触发返回/菜单/翻译 ——
        // 必须**立刻**关掉可交互（GONE 要等动画结束才设得上）。禁用后可点控件只吞事件不响应，
        // 所以这段窗口最多丢一次点击，不会误触发任何动作。
        setChromeInteractive(v, false)
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(CHROME_FADE_MS)
            // 动画被新动画打断时 end action 也会跑，用「已确实淡到 0」兜住，避免提前 GONE
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

    /**
     * 递归开关整棵子树的 `isEnabled`（含 SubView 上的可点控件：返回/菜单在 FrameLayout 里，
     * 上一页/下一页与三个翻译按钮是各容器里的叶子）。禁用而不是改 `isClickable`：恢复时把
     * clickable 一律置 true 会让原本**不可点**的层开始吞事件，反而挡住底下翻页器的手势。
     */
    private fun setChromeInteractive(v: View, interactive: Boolean) {
        v.isEnabled = interactive
        if (v is ViewGroup) for (i in 0 until v.childCount) setChromeInteractive(v.getChildAt(i), interactive)
    }

    private fun onInteraction() {
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    private fun turnPage(delta: Int) {
        if (mode == 3) return // webtoon 用滚动，不含自动翻页
        val clamped = (currentPage + delta).coerceIn(0, (source.size - 1).coerceAtLeast(0))
        binding.viewPager.setCurrentItem(clamped, animationSupportsAnim())
    }

    /** 动画开关：无动画(0)时点击/自动翻页直接跳（setCurrentItem 无平滑），其余模式走 transformer 补间。 */
    private fun animationSupportsAnim(): Boolean = animationMode != 0

    private fun goToPage(page: Int) {
        val p = page.coerceIn(0, (source.size - 1).coerceAtLeast(0))
        // 显式跳页后，尺寸变化前记下的「待对齐页」已过期（见 realignPagerAfterResize）
        pendingRealignPage = -1
        if (mode == 3) {
            // Webtoon：定位到顶部（scrollToPosition 不保证贴顶）
            (binding.webtoonList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
            currentPage = p
            ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = p))
            refreshOverlay()
            return
        }
        binding.viewPager.setCurrentItem(p, false)
    }

    // ===== 覆盖层 =====

    private fun setupOverlays() {
        binding.btnBack.setOnClickListener { finish() }
        // 底部进度条两侧的箭头 = **切章**（用户口径）：不再是上一页/下一页。
        // 翻页改由滑动 / 点击左右（竖排为上下）半屏完成；到头给「当前已是第一/最后一章」提示。
        binding.btnPrev.setOnClickListener { switchChapter(-1) }
        binding.btnNext.setOnClickListener { switchChapter(1) }
        binding.btnMenu.setOnClickListener { showMenu() }
        // 顶部页码胶囊带章节信息，点它打开章节目录（与小说阅读器的顶部章名同一个入口语义）
        binding.tvPageIndicator.setOnClickListener { openChapterDialog() }

        // 分页进度：只注册一次（applyPager 会重建 adapter，但回调挂在 viewPager 上，无需重复注册）
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
        // ⚠️ 视口尺寸变化（旋转 / 分屏 / 折叠）后必须重新对齐分页器。
        // ViewPager2 内部的 RecyclerView 把滚动位置按**像素**保留，旧视口下的偏移量在新视口里
        // 不再落在页边界上 → 画面停在两页之间（各露半张），要等用户点一下/滑一下触发真实滚动
        // 才吸附回来。原先靠「旋转时重建 adapter」隐式兜住（换 adapter → 整体重绑 + 重新吸附），
        // 单页重构去掉那条链后就暴露了。这里在布局完成后显式补回来（旧尺寸为 0 = 首次布局，跳过）。
        binding.viewPager.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l == or_ - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            if (or_ - ol == 0 || ob - ot == 0) return@addOnLayoutChangeListener
            realignPagerAfterResize()
        }
        // Webtoon：行高是按「绑定那一刻的 View 宽度」钉死的，宽度一变（旋转/分屏）旧值就失真
        // （图片按错误宽高比 fitCenter，两侧留白带），必须重绑让适配器按新宽度重算。
        // 只注册一次；重绑后宽度不变 → 不会再次触发，无循环风险。
        binding.webtoonList.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ ->
            if (r - l != or_ - ol && mode == 3) {
                val a = binding.webtoonList.adapter ?: return@addOnLayoutChangeListener
                if (a.itemCount > 0) a.notifyItemRangeChanged(0, a.itemCount)
            }
        }
        // 手势：仿真记录折线触点（返回 false 不打断手势）
        binding.viewPager.setOnTouchListener { _, e ->
            if (animationMode == 3 && mode != 3) {
                val size = if (mode == 2) binding.viewPager.width.toFloat() else binding.viewPager.height.toFloat()
                if (size > 0f) {
                    animState.foldStartFraction = ((if (mode == 2) e.x else e.y) / size).coerceIn(0f, 1f)
                }
            }
            false
        }
        // 高级动画：保证叠放绘制顺序——按 translationZ 升序画（z 高者后画=在上层）。
        // ViewPager2 重叠页的 z 排序不可靠，显式设置绘制回调后封面/卷曲的「上/下层」才稳定
        (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.setChildDrawingOrderCallback(
            object : androidx.recyclerview.widget.RecyclerView.ChildDrawingOrderCallback {
                override fun onGetChildDrawingOrder(count: Int, i: Int): Int {
                    if (count <= 1) return i
                    val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return i
                    return (0 until count).sortedBy { rv.getChildAt(it).translationZ }[i]
                }
            }
        )
        // Webtoon 滚动进度：滚动时更新当前页/进度条/lastReadPage
        binding.webtoonList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                updateFromWebtoonScroll()
            }
        })
        // Webtoon 点击 = 平滑定位到每页开始位置；长按 = 预览（与分页模式一致）
        webtoonTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode != 3) return false
                webtoonTapToPage(e.y, e.x)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (mode != 3) return
                lastInteractionMs = SystemClock.elapsedRealtime()
                openPagePreview()
            }
        })
        binding.webtoonList.setOnTouchListener { _, e ->
            webtoonTapDetector?.onTouchEvent(e)
            false // 不消费，交给 RecyclerView 正常滚动
        }

        binding.readerProgress.onSeek = { page -> goToPage(page) }
        binding.readerProgress.onLongPress = {
            lastInteractionMs = SystemClock.elapsedRealtime()
            openPagePreview()
        }
    }

    /** 滚动后：把当前可见页同步到 currentPage / 进度条 / 断点续读，并预热附近几页的译图。 */
    private fun updateFromWebtoonScroll() {
        if (mode != 3) return
        val lm = binding.webtoonList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val page = first.coerceIn(0, (source.size - 1).coerceAtLeast(0))
        if (page == currentPage) return
        currentPage = page
        ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = currentPage))
        refreshOverlay()
        // 只预热当前位置上下几页：Webtoon 可能上百页，一次渲染全部会爆内存
        translationController?.prewarmWebtoon(currentPage)
    }

    /** Webtoon：把当前位置附近几页重绑（译图渲染完成后刷上去）。
     *  ⚠️ 必须并入**当前可见页范围**：预热的中心是首个可见页，遇到一屏装得下 3 页以上的短页时，
     *  屏幕尾部会落出 ±半径 之外 —— 只按中心重绑，那几页在切回「原图」后仍停在旧译图上。 */
    private fun refreshWebtoonRange(center: Int) {
        val a = binding.webtoonList.adapter ?: return
        val radius = ReaderTranslationController.WEBTOON_PREWARM_RADIUS
        var from = (center - radius).coerceAtLeast(0)
        var to = (center + radius).coerceAtMost((source.size - 1).coerceAtLeast(0))
        val lm = binding.webtoonList.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
        val last = lm?.findLastVisibleItemPosition() ?: androidx.recyclerview.widget.RecyclerView.NO_POSITION
        if (first != androidx.recyclerview.widget.RecyclerView.NO_POSITION &&
            last != androidx.recyclerview.widget.RecyclerView.NO_POSITION
        ) {
            from = minOf(from, first)
            to = maxOf(to, last)
        }
        for (p in from..to) a.notifyItemChanged(p)
    }

    /** Webtoon 点击：点下半屏=平滑滚动到下一页顶部，上半屏=上一页顶部；中间一格=显隐 UI，右上角=菜单。 */
    private fun webtoonTapToPage(y: Float, x: Float) {
        val w = binding.webtoonList.width.toFloat()
        val h = binding.webtoonList.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (consumeChromeOrMenuTap(x, y, w, h)) return
        val lm = binding.webtoonList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val cur = lm.findFirstVisibleItemPosition()
        if (cur == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val delta = if (y >= h / 2f) 1 else -1
        webtoonSmoothScrollToTop((cur + delta).coerceIn(0, (source.size - 1).coerceAtLeast(0)))
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    /** Webtoon 平滑滚动到指定页顶部（Koto 式点击定位每页开始位置）。 */
    private fun webtoonSmoothScrollToTop(page: Int) {
        val rv = binding.webtoonList
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        // 页面一般已预取可见：精确贴顶 = 平滑滚动 targetView.top 像素（其顶部对齐可视区顶）
        val targetView = lm.findViewByPosition(page)
        if (targetView != null && targetView.top != 0) {
            rv.smoothScrollBy(0, targetView.top)
            return
        }
        val scroller = object : LinearSmoothScroller(this) {}
        scroller.targetPosition = page
        lm.startSmoothScroll(scroller)
    }

    private fun refreshOverlay() {
        // 顶部胶囊 = 章标题 · 章内第几页/本章共几页（用户口径：页码组件也要有章节信息）。
        // 章标题走 mangaChapterLabel（有文件夹名就显示它），与目录弹窗/翻译面板同一份
        val chapter = currentChapter()
        binding.tvPageIndicator.text = if (chapter != null) {
            getString(
                R.string.reader_page_indicator_chapter,
                mangaChapterLabel(this, chapter),
                (currentPage - chapter.startPage + 1).coerceAtLeast(1),
                chapter.pageCount
            )
        } else {
            getString(R.string.reader_page_indicator, currentPage + 1, source.size)
        }
        // 进度条仍按**全书**页数（拖拽寻页/长按预览都是整本的，绿条也是整本口径）；
        // 章内位置由顶部胶囊表达，两者不冲突
        binding.readerProgress.setPage(currentPage, source.size)
    }

    // ===== 阅读器内嵌翻译 =====

    /** 右下角翻译浮层组接线（逻辑走 ReaderTranslationController）。 */
    private fun setupTranslationUi() {
        // 先按当前模式收一次浮层组：启动即 Webtoon 时不能等到 load() 返回才隐藏，
        // 否则开屏几十毫秒内右下角会闪一个翻译按钮（此时点它只会弹"该模式不支持"）
        refreshTranslationChrome()
        val controller = translationController ?: return
        binding.btnTranslate.setOnClickListener {
            // ⚠️ "点了没反应"排查的第一现场：这条日志只说明"点击到了"，后面没别的日志
            // 才说明是流程里被拦住了（配合 ReaderAnim 已限流，日志窗口不会再被刷掉）
            LogCollector.d("ReaderTranslate", "翻译按钮点击：page=$currentPage mode=$mode disableByMode=${isTranslateDisabledByMode()}")
            // 「重新翻译」才转一圈（用户口径：重新翻译可以是旋转；三态切换不要）
            if (translationController?.stateOf(currentPage) == ImportedPageTranslation.STATE_SUCCESS) {
                binding.ivTranslate.animate().rotationBy(360f).setDuration(420).start()
            }
            if (isTranslateDisabledByMode()) {
                notifyUser(getString(R.string.reader_translate_disabled_mode))
                return@setOnClickListener
            }
            // 单击 = **只提示，绝不打断**；快速双击 = 取消并回退手动（用户明确要求）
            val now = SystemClock.elapsedRealtime()
            val isDouble = now - lastTranslateClickMs <= DOUBLE_CLICK_MS
            lastTranslateClickMs = if (isDouble) 0L else now

            when (val r = controller.onTranslateButtonClick(isDouble)) {
                is TranslateClick.Hint -> {
                    // 提示走「替换顶部芯片」的进度样式（与截屏翻译同一套）。浮层开关被关掉、
                    // 或没有悬浮窗权限时，[notifyUser] 会退回 Toast —— 否则点翻译「毫无反馈」。
                    notifyUser(r.text, AppNotice.Style.PROGRESS)
                }
                TranslateClick.CancelledToManual -> {
                    setTranslatingPulse(false)
                    TranslationStatusOverlay.getInstance(this).dismiss()
                    // 双击强制取消 = 强制退出 + 回退手动（用户口径：提示前一句固定，后一句只在真回退时才接）。
                    // 走统一出口：浮层画不出来时至少还有 Toast，否则"双击了但什么都没说"
                    notifyUser(getString(R.string.reader_translate_force_stopped_to_manual))
                    refreshTranslationChrome()
                    refreshProgressTranslation()
                }
                TranslateClick.Busy -> {
                    notifyUser(getString(R.string.reader_translate_busy))
                    refreshTranslationChrome()
                }
                TranslateClick.StartedManual, TranslateClick.Ignored -> Unit
            }
        }
        // 成功页：三态循环（译文/原文/纯原图）。
        // Webtoon 没有单页三态（连续滚动下"当前页"语义不唯一），整屏切换改由阅读模式分段器
        // 上「连续滑动」按钮的两态控制（见 ReaderMenuSheet），本按钮在 Webtoon 下整组隐藏。
        binding.btnToggleTranslate.setOnClickListener {
            // ⚠️ 三态切换**不要旋转**（用户口径：三态别用旋转、重新翻译才用旋转）
            controller.cycleVisual(currentPage)
        }
        // 失败页：感叹号 → 小气泡显示失败原因（不弹窗）
        binding.btnFailTranslate.setOnClickListener { showFailBubble() }
        // 超分三件套（与翻译三件套对称，都在 [refreshTranslationChrome] 里统一定显隐）：
        //   · 超分 / 重新超分 —— 无条件跑一次
        //   · 原图 ⇄ 超分底图 —— 瞬时切换，不挂进度提示
        //   · 删除本页超分结果 —— 二次确认
        // ⚠️ 提示一律走 **app 状态浮层**（用户口径："应该也用 app 系统提示，不要用手机底部 Toast"）。
        binding.btnSrPage.setOnClickListener { onSrEnhanceClicked() }
        binding.btnSrToggle.setOnClickListener { onSrToggleClicked() }
        binding.btnSrClear.setOnClickListener { onSrClearClicked() }
        // 清除本页译文：**必须先二次确认**（用户口径：所有删除/清空操作都要确认）
        binding.btnClearTranslate.setOnClickListener { confirmClearPage(currentPage) }
        // 右下角按钮组 + 底部左右翻页键：按下缩放反馈（原先点了完全没有视觉反馈）
        attachPressFeedback(
            // 并集：master 补了 btnClearTranslate，超分线补了超分三件套（2026-09-30 合并）
            binding.btnClearTranslate, binding.btnTranslate, binding.btnToggleTranslate, binding.btnFailTranslate,
            binding.btnSrPage, binding.btnSrToggle, binding.btnSrClear, binding.btnPrev, binding.btnNext,
        )
        applyPageImageSource()
    }

    /**
     * 按下反馈：按下缩到 0.9、抬起/取消弹回。
     *
     * ⚠️ 监听器**返回 false**（不消费事件）：这样点击照旧走 `OnClickListener`，
     * 只是额外多一层动画 —— 若返回 true 就得自己补 `performClick()`，容易把别人的点击逻辑吃掉。
     */
    private fun attachPressFeedback(vararg views: View) {
        views.forEach { v ->
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }
    }

    /**
     * 「正在翻译」时的呼吸动画（翻译按钮轻微脉动）。
     *
     * 用户口径：点翻译/三态等按钮**必须有反馈**。除了按下缩放，翻译期间也让按钮"活着"，
     * 一眼能看出任务在跑；结束（成功/失败/取消）时停掉并复位。
     */
    private fun setTranslatingPulse(on: Boolean) {
        val v = binding.btnTranslate
        if (on) {
            if (translatingPulse != null) return
            // ⚠️ X/Y 两个动画要装进**同一个 AnimatorSet** 一并持有：只存其中一个的话，
            // 取消时另一个会一直循环下去（按钮永远在抖）。
            val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.08f)
            val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.08f)
            listOf(sx, sy).forEach {
                it.duration = 520
                it.repeatMode = ValueAnimator.REVERSE
                it.repeatCount = ValueAnimator.INFINITE
            }
            translatingPulse = AnimatorSet().apply {
                playTogether(sx, sy)
                start()
            }
        } else {
            translatingPulse?.cancel()
            translatingPulse = null
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
    }

    /** Webtoon（连续滚动）暂不支持单页翻译（"当前页"语义不唯一）。横竖屏都是单页，翻译不受朝向影响。 */
    private fun isTranslateDisabledByMode(): Boolean = mode == 3

    // ===== 章节：导航 / 目录 / 本章批量翻译 =====

    /** 当前页所在的章下标（章节表为空时恒 0）。 */
    private fun currentChapterIndex(): Int = chapterIndexOf(chapters, currentPage)

    private fun currentChapter(): MangaChapter? = chapters.getOrNull(currentChapterIndex())

    /**
     * 切到 [index] 章（跳到该章第一页）。
     * 阅读记录只记一个位置（[ImportedManga.lastReadPage]）—— 这正是不做「每章记忆」的原因。
     */
    private fun goToChapterIndex(index: Int) {
        val chapter = chapters.getOrNull(index) ?: return
        goToPage(chapter.startPage)
        // Webtoon 下 goToPage 直接改 currentPage、不派发 onPageSelected；分页模式下 setCurrentItem
        // 的 onPageSelected 也在下一帧。这里显式刷一次，保证胶囊与面板立刻是新章
        refreshOverlay()
        refreshTranslationChrome()
        refreshProgressTranslation()
    }

    /** 上一章 / 下一章；到头给提示（用户口径：「当前已是第一章 / 最后一章」）。 */
    private fun switchChapter(delta: Int) {
        val target = currentChapterIndex() + delta
        if (chapters.isEmpty() || target !in chapters.indices) {
            notifyUser(
                getString(if (delta < 0) R.string.reader_chapter_first else R.string.reader_chapter_last)
            )
            return
        }
        goToChapterIndex(target)
    }

    /** 章节目录弹窗（顶部页码胶囊 / 翻译面板章标题点开，同一个）。 */
    private fun openChapterDialog() {
        if (chapters.isEmpty()) return
        val handle = ReaderChapterDialog.show(
            context = this,
            chapters = chapters,
            currentIndex = currentChapterIndex(),
            statusOf = { index -> chapterTocStatus(index) },
            dark = isDarkBackground(),
        ) { index -> goToChapterIndex(index) }

        // 弹窗打开期间**持续推状态**（用户要"看得到各章的实时反应状态"）：
        // 任务进度/等待页数/译文数都在变，只取打开那一刻的快照就会出现"翻译在涨、目录不动"。
        // 轮询 400ms 足够（只在弹窗开着时跑，关闭即结束）。
        chapterTocRefresh?.cancel()
        chapterTocRefresh = lifecycleScope.launch {
            while (handle.isShowing && isActive) {
                delay(CHAPTER_TOC_REFRESH_MS)
                handle.refresh()
            }
        }
    }

    /** 章节目录里某章的实时状态（与面板的章卡片同一批数据源）。 */
    private fun chapterTocStatus(index: Int): ReaderChapterDialog.Status {
        val controller = translationController ?: return ReaderChapterDialog.Status()
        val chapter = chapters.getOrNull(index) ?: return ReaderChapterDialog.Status()
        val job = controller.chapterJob(index)
        val waiting = controller.panelWaitingPages().count { it in chapter.startPage..chapter.endPage }
        return ReaderChapterDialog.Status(
            success = controller.successCount(chapter.startPage, chapter.endPage),
            total = chapter.pageCount,
            running = job?.state == ChapterJobState.RUNNING || job?.state == ChapterJobState.QUEUED,
            paused = job?.state == ChapterJobState.PAUSED,
            jobDone = job?.done ?: 0,
            jobTotal = job?.total ?: 0,
            waiting = waiting,
        )
    }

    /**
     * 面板回读的章节快照。
     *
     * ⚠️ 这是「宿主 → 面板」的唯一真值来源：面板是打开那一刻的快照，翻页/跑批期间必须能取到此刻状态
     * （与小说面板的 `NovelPanelHostState` 同一约定）。
     */
    private fun chapterPanelState(): ChapterPanelState {
        val c = translationController
        val jobs = c?.chapterJobs?.value.orEmpty()
        return ChapterPanelState(
            chapters = chapters,
            currentChapter = currentChapterIndex(),
            records = c?.records() ?: emptyList(),
            jobs = jobs.associate { it.chapterIndex to it.state },
            jobDone = jobs.associate { it.chapterIndex to it.done },
            jobTotal = jobs.associate { it.chapterIndex to it.total },
            waitingPages = c?.panelWaitingPages() ?: emptySet(),
            ocrPages = c?.ocrPages() ?: emptySet(),
            translatingPages = c?.translatingPages() ?: emptySet(),
            // 超分那一路（记录列表切到「超分」页签时显示）
            srRecords = c?.srRecords() ?: emptyList(),
            srJob = c?.srChapterJob?.value,
        )
    }

    /** 章节批量任务的进度浮层 + 收尾提示（在 [onCreate] 里接线）。 */
    private fun setupChapterBatchUi() {
        val controller = translationController ?: return
        // ⚠️ 监听器式而不是单个回调：hub（通知栏）也要收这个事件，单个 var 会被后设的覆盖
        val finished: (Int, Int, Int, Boolean) -> Unit = { _, ok, total, cancelled ->
            runOnUiThread {
                TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
                // 整章翻完是「结果」，走统一出口（浮层不可用时退 Toast），不能静默
                if (!cancelled) notifyUser(getString(R.string.reader_translate_chapter_finished, ok, total))
                refreshProgressTranslation()
                refreshTranslationChrome()
            }
        }
        controller.addJobFinishedListener(finished)
        jobFinishedListener = finished
        // **超分本章**：任务每推进一步就把面板刷新一次。
        // ⚠️ 必须有：面板是「打开那一刻的快照」，宿主不推它就永远停在开始那一下 ——
        //    用户会看到"点了超分本章，面板一动不动"。一页一次（每页几秒），不会掉帧。
        lifecycleScope.launch {
            controller.srChapterJob.collect { pushPanelNow() }
        }
        // 进度浮层同时跟**任务进度**与**在途阶段**（识别中 / 翻译中）走：
        // 只跟 jobs 的话，阶段变化（OCR → 翻译）不改 done，用户会看到「明明在调 API，还写着识别中」。
        lifecycleScope.launch {
            combine(controller.chapterJobs, controller.chapterInFlight) { jobs, inFlight -> jobs to inFlight }
                .collect { (jobs, inFlight) ->
                    val running = jobs.filter { it.state == ChapterJobState.RUNNING }
                    refreshProgressTranslation()
                    if (running.isEmpty()) return@collect
                    val done = running.sumOf { it.done }
                    val total = running.sumOf { it.total }
                    if (!statusOverlayEnabled()) return@collect
                    val base = getString(R.string.reader_translate_chapter_running, done, total)
                    // 「识别中 P3 · 翻译中 P4, P5」——用户口径：进行中只有这两个状态，必须说清是哪个
                    val stages = chapterStageText(inFlight)
                    val text = if (stages.isEmpty()) base else "$base · $stages"
                    TranslationStatusOverlay.getInstance(this@MangaReaderActivity)
                        .showImmediate(text, autoDismiss = false)
                }
        }
    }

    /**
     * 在途阶段的文案：`识别中 P3 · 翻译中 P4, P5`。
     *
     * 识别（OCR）恒串行 → 最多 1 页；翻译按并发设置 → 最多 N 页。
     * 两者都为空（例如任务刚提交、还没排到页）时返回空串，调用方就不追加这一段。
     */
    private fun chapterStageText(
        inFlight: List<InFlightTask>,
    ): String {
        fun pages(stage: ChapterTaskStage) =
            inFlight.filter { it.stage == stage }.map { it.page }.sorted().distinct()
        val entries = ArrayList<String>(2)
        pages(ChapterTaskStage.OCR).takeIf { it.isNotEmpty() }?.let { ps ->
            entries += getString(
                R.string.reader_translate_stage_entry,
                getString(R.string.reader_translate_state_ocr),
                ps.joinToString(", ") { "P${it + 1}" },
            )
        }
        pages(ChapterTaskStage.TRANSLATE).takeIf { it.isNotEmpty() }?.let { ps ->
            entries += getString(
                R.string.reader_translate_stage_entry,
                getString(R.string.reader_translate_state_translating),
                ps.joinToString(", ") { "P${it + 1}" },
            )
        }
        return entries.joinToString(" · ")
    }

    /**
     * 章卡片的**主按钮**（用户口径：没有任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续）。
     *
     * - 首次点击 → 弹确认（默认**只翻未完成**、不覆盖已成功的页；可强行「全部重翻」）
     * - 跑批中 → 暂停（等待中的页留在队列里，不清除）
     * - 已暂停 → 继续
     */
    private fun onChapterPrimaryClicked(chapterIndex: Int) {
        // ⚠️ 这两个"静默 return"以前什么都不打 —— 用户报"点了没反应"时无从下手。
        // 现在各留一条 W 级日志：面板按钮点了没反应时，看日志立刻能分清是哪一种。
        val controller = translationController
        if (controller == null) {
            LogCollector.w("ReaderTranslate", "翻译本章：控制器为空（阅读器还没准备好）")
            return
        }
        val chapter = chapters.getOrNull(chapterIndex)
        if (chapter == null) {
            LogCollector.w("ReaderTranslate", "翻译本章：章节 $chapterIndex 不在章节表（size=${chapters.size}）")
            return
        }
        when (controller.chapterJob(chapterIndex)?.state) {
            ChapterJobState.RUNNING, ChapterJobState.QUEUED -> {
                controller.pauseChapterJob(chapterIndex)
                refreshProgressTranslation()
                return
            }
            ChapterJobState.PAUSED -> {
                controller.resumeChapterJob(chapterIndex)
                TranslationJobService.start(applicationContext)
                refreshProgressTranslation()
                return
            }
            else -> Unit
        }
        val pages = chapter.startPage..chapter.endPage
        val untranslated = pages.count { controller.stateOf(it) == ImportedPageTranslation.STATE_IDLE }
        val failed = pages.count { controller.stateOf(it) == ImportedPageTranslation.STATE_FAILED }
        val done = pages.count { controller.stateOf(it) == ImportedPageTranslation.STATE_SUCCESS }
        val all = pages.toList()
        val pending = all.filter { controller.stateOf(it) != ImportedPageTranslation.STATE_SUCCESS }
        // 弹窗跟随**阅读背景**深浅（用户口径：弹窗也要适配主题配色，别跟系统主题走）
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_translate_chapter_confirm_title)
            setMessage(
                getString(
                    R.string.reader_translate_chapter_confirm_msg,
                    chapter.pageCount, untranslated, failed, done
                )
            )
            // 正按钮 = 「只翻未完成」→ 系统默认聚焦它 = 默认**不覆盖**（用户口径）
            setPositiveButton(R.string.reader_translate_chapter_only_pending) { _, _ ->
                startChapterBatch(chapterIndex, pending)
            }
            // 中按钮 = 强行覆盖（连已成功的页一起重翻）
            setNeutralButton(R.string.reader_translate_chapter_overwrite) { _, _ ->
                startChapterBatch(chapterIndex, all)
            }
            setNegativeButton(R.string.cancel, null)
        }
    }

    /**
     * 章卡片的**次按钮**：有任务 = 取消该章任务（丢弃等待中的页，**已翻好的译文保留**）；
     * 没任务 = 清除本章译文（二次确认）。
     */
    private fun onChapterSecondaryClicked(chapterIndex: Int) {
        val controller = translationController ?: return
        val job = controller.chapterJob(chapterIndex)
        if (job != null && job.isActive) {
            controller.cancelChapterJob(chapterIndex)
            TranslationStatusOverlay.getInstance(this).dismiss()
            // 取消 = **强制结束在途页**（不入库、不等待）。整章任务跑在手动模式下，
            // 所以只报「翻译进程已强制退出」，不接"回退到手动模式"（用户口径）
            showForceStoppedNotice(toManual = false)
            refreshProgressTranslation()
            return
        }
        confirmClearChapter(chapterIndex)
    }

    // ========== 超分本章（面板「超分」记录页签） ==========

    /**
     * 超分章卡片的**主按钮**：没在跑 = 超分本章；跑着 = 取消。
     *
     * ## 互斥（用户口径 2026-10）
     * 「不能出现两边都开启批量翻译某章和超分某章」—— 翻译在跑时**直接提示并返回**，
     * 不静默（静默就是"点了没反应"）。
     */
    private fun onSrChapterPrimaryClicked(chapterIndex: Int) {
        val controller = translationController ?: return
        if (controller.srChapterJob.value?.chapterIndex == chapterIndex) {
            controller.cancelSrChapterJob()
            pushPanelNow()
            notifyUser(getString(R.string.reader_sr_chapter_cancelled))
            return
        }
        if (controller.srBatchBlockedByTranslate()) {
            notifyUser(getString(R.string.reader_sr_blocked_by_translate), AppNotice.Style.ERROR)
            return
        }
        // 没选/没下模型时**先拦住**：否则会一页页失败，用户得等一整章才知道原因
        if (!SuperResolutionEngines.isSrModelUsable(this, prefs)) {
            notifyUser(getString(R.string.reader_sr_need_model), AppNotice.Style.ERROR)
            return
        }
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        // 只超**还没超成功**的页（已有的结果不白跑 —— 与"同模型不重超"同一条口径）
        val done = controller.srRecords()
            .filter { it.state == ImportedPageSr.STATE_SUCCESS }
            .map { it.pageIndex }
            .toSet()
        val targets = (chapter.startPage..chapter.endPage).filter { it !in done }
        if (targets.isEmpty()) {
            notifyUser(getString(R.string.reader_sr_chapter_nothing))
            return
        }
        controller.startSrChapterJob(chapterIndex, targets)
        notifyUser(getString(R.string.reader_sr_chapter_started))
        pushPanelNow()
    }

    /** 超分章卡片的**次按钮**：清除本章超分（**二次确认** —— 所有删除都要确认）。 */
    private fun confirmClearChapterSr(chapterIndex: Int) {
        val controller = translationController ?: return
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_sr_clear_chapter_title)
            setMessage(getString(R.string.reader_sr_clear_chapter_msg))
            setPositiveButton(R.string.reader_sr_clear_chapter_ok) { _, _ ->
                lifecycleScope.launch {
                    controller.clearChapterSr(chapter, chapterIndex)
                    pushPanelNow()
                    invalidateRenderInputsAndReRender()
                    notifyUser(getString(R.string.reader_sr_chapter_cleared))
                }
            }
            setNegativeButton(R.string.cancel, null)
        }
    }

    /** 超分记录行内的「删除」：删这一页的超分结果（与右下角那枚删除按钮同一件事）。 */
    private fun confirmDeleteSrPage(page: Int) {
        val controller = translationController ?: return
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_sr_clear_confirm_title)
            setMessage(getString(R.string.reader_sr_clear_confirm_msg, page + 1))
            setPositiveButton(R.string.reader_sr_clear_ok) { _, _ ->
                lifecycleScope.launch {
                    controller.deleteSrResult(page, dropRecord = true)
                    pushPanelNow()
                    if (page == currentPage) invalidateRenderInputsAndReRender()
                    notifyUser(getString(R.string.reader_sr_page_deleted))
                }
            }
            setNegativeButton(R.string.cancel, null)
        }
    }

    private fun startChapterBatch(chapterIndex: Int, pages: List<Int>) {
        val controller = translationController ?: return
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        if (pages.isEmpty()) {
            notifyUser(getString(R.string.reader_translate_chapter_nothing))
            return
        }
        // **互斥**（用户口径 2026-10）：超分本章在跑时不许再开翻译本章 —— 两个批量任务都要抢
        // `OcrLock`，同时开就是互相拖死；而且用户根本分不清哪条进度是哪条。要有提示，不静默。
        if (controller.translateBatchBlockedBySr()) {
            notifyUser(getString(R.string.reader_translate_blocked_by_sr), AppNotice.Style.ERROR)
            return
        }
        // 章标题一起带过去：后台任务要靠它显示通知栏文案（阅读器关掉后拿不到 chapters）
        controller.startChapterJob(
            chapterIndex = chapterIndex,
            pages = pages,
            label = mangaChapterLabel(this, chapter),
            startPage = chapter.startPage,
        )
        // 任务在应用级后台跑：把前台服务拉起来（通知栏进度 + 暂停/取消按钮）
        TranslationJobService.start(applicationContext)
        refreshProgressTranslation()
    }

    /**
     * 面板里改了译文字号 / 自动字号。
     *
     * ⚠️ 译文字号**不在渲染缓存 key 里**（key 只有 `page:idx:MODE`）→ 必须先把已渲染的译图作废，
     * 再按新字号重渲染当前页，否则拖完滑块屏幕上还是旧字号（`invalidateRenders` 就是干这个的）。
     */
    /**
     * 底图/排版输入变了 → 作废渲染缓存并重渲染当前页。
     *
     * ⚠️ 两处共用（字号变更 / Anime4K 档位变更）：它们改的都不是「译文」而是**渲染的输入**
     * —— 字号影响排版，Anime4K 影响底图（`SrPageEnhancer` 会按设置指纹自动作废增强缓存）。
     * 不作废的话屏幕上还是旧档/旧字号的图。
     */
    private fun invalidateRenderInputsAndReRender() {
        val c = translationController ?: return
        c.invalidateRenders()
        if (mode == 3) {
            // Webtoon：译图是逐页预热的，作废后重新预热可见范围
            c.prewarmWebtoon(currentPage)
            refreshWebtoonRange(currentPage)
        } else {
            applyPageVisual(currentPage)
        }
    }

    private fun onFontSizeChangedFromPanel() = invalidateRenderInputsAndReRender()

    /** Anime4K 档位变更（阅读器调色面板）：与字号同一条链路 */
    /**
     * 调色面板「处理后」格子用的 **Anime4K** 增强图（超分**没有**产出时走的那条路）。
     *
     * ⚠️ **必须在 IO 线程调**：`enhanceWithAnime4k` 要建 EGL 上下文 + 编译着色器 +
     * 跑最多 49 趟 pass，放主线程就是"一开面板就卡死"。
     * ⚠️ 关着 / 失败 → null（面板回落原图）。失败**只记日志**：这是被动预览，用户没主动要求什么。
     * ⚠️ 开关一律读 `CustomPreference` 的默认 prefs —— 本 Activity 自持的 `prefs` 是**另一个
     * 命名文件**，读它永远看不到用户在调色面板改的 Anime4K 档位。
     */
    private suspend fun anime4kPreviewFor(src: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
        val p = CustomPreference.getInstance(applicationContext).getSharedPreferences()
        if (!Anime4kMode.isEnabled(p)) return@withContext null
        val r = SuperResolutionEngines.enhanceWithAnime4k(applicationContext, p, src)
        if (!r.ok) LogCollector.d("MangaReader", "面板 Anime4K 预览未产出: ${r.reason} ${r.detail ?: ""}")
        r.bitmap
    }

    private fun onAnime4kModeChangedFromPanel() = invalidateRenderInputsAndReRender()
    /** 清除本章译文（二次确认，防误删；用户口径：**只清当前章**，不做整本清理入口）。 */
    private fun confirmClearChapter(chapterIndex: Int = currentChapterIndex()) {
        val controller = translationController ?: return
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        val affected = (chapter.startPage..chapter.endPage).count {
            controller.stateOf(it) != ImportedPageTranslation.STATE_IDLE
        }
        if (affected == 0) {
            notifyUser(getString(R.string.reader_translate_chapter_nothing))
            return
        }
        // 清空本章也要跟阅读背景（用户口径：所有弹窗都要适配主题配色）
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_translate_clear_confirm_title)
            setMessage(getString(R.string.reader_translate_clear_confirm_msg, affected))
            setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    val removed = controller.clearPageRange(chapter.startPage, chapter.endPage)
                    refreshProgressTranslation()
                    refreshTranslationChrome()
                    applyPageVisual(currentPage)
                    if (removed > 0) {
                        notifyUser(getString(R.string.reader_translate_chapter_cleared))
                    }
                }
            }
            setNegativeButton(R.string.cancel, null)
        }
    }

    /**
     * 清除**本页**译文（右下角清除图标 + 面板行内「删除」共用）。
     *
     * ⚠️ **所有删除/清空操作都要二次确认**（用户口径）——以前这两个入口都是点了立刻删。
     */
    private fun confirmClearPage(page: Int) {
        if (translationController == null) return
        // ⚠️ 走 ReaderDialogs.show（= 跟随**阅读背景**深浅）：裸 AlertDialog 跟系统主题走，
        // 深色阅读背景 + 浅色系统下就是一块突兀的白底（用户报的"弹窗没适配主题配色"）
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_clear_page_confirm_title)
            setMessage(getString(R.string.reader_clear_page_confirm_msg, page + 1))
            setNegativeButton(R.string.user_cancel, null)
            setPositiveButton(R.string.reader_clear_page_confirm_ok) { _, _ -> deletePageTranslation(page) }
        }
    }

    /** 删除某一页的翻译数据（确认之后才调）。删完该页回落原图、进度条绿条同步缩短。 */
    private fun deletePageTranslation(page: Int) {
        val controller = translationController ?: return
        lifecycleScope.launch {
            controller.deletePage(page)
            refreshProgressTranslation()
            refreshTranslationChrome()
            applyPageVisual(currentPage)
            notifyUser(getString(R.string.reader_translate_page_deleted))
        }
    }

    /** 状态浮层总开关（关闭后所有提示必须改走 Toast，否则用户点按钮毫无反馈）。 */
    private fun statusOverlayEnabled(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("status_overlay_enabled", true)

    /**
     * **阅读器所有用户提示的唯一出口**（用户口径 2026-10：「阅读器所有的提示报错信息全部使用
     * 顶部系统弹窗」，即截屏翻译那套 `TranslationStatusOverlay` —— 显示位置 / 时长走个性化设置，
     * 见 `Status_Position` / `Status_Duration`）。
     *
     * 判断与兜底只剩一份：[AppNotice]（全应用共用，书架也用同一套）。它内部判两条 ——
     * ① `status_overlay_enabled` 开关被关掉；② 没有「显示在其他应用上层」权限（浮层**静默失败**）
     * —— 任一成立就退回系统 Toast，否则用户点了按钮「毫无反馈」。
     *
     * @param autoDismiss 只有 [AppNotice.Style.PROGRESS] 用得上：false = 常驻到被替换
     *   （「正在超分…」这类进行中状态）。
     */
    private fun notifyUser(
        text: String,
        style: AppNotice.Style = AppNotice.Style.INFO,
        autoDismiss: Boolean = true,
    ) = AppNotice.show(this, text, style, autoDismiss = autoDismiss)

    /**
     * **超分结果提示的统一出口**（用户口径：超分也要用 app 的「系统提示」，不要用手机底部 Toast）。
     *
     * ⚠️ **只收超分自己那条「正在超分…」，不再 `dismiss()` 清屏**（用户口径 2026-10）：
     * 「超分执行的时候也要有提示信息，而且要和翻译中**一起出现**」—— `dismiss()` 会把并排的
     * 「翻译中…」一起抹掉，那正是"两条并存"做不到的原因。收尾改由 [clearAllSrRunningChips]
     * 精确完成。（守卫 `SrReaderWiringTest.everySrNoticeClearsOnlyItsOwnRunningChip` 盯着这条。）
     *
     * @param isError 失败原因 → 红色 chip（可点复制，方便用户把原文发出来）；普通提示 → 黑底 chip
     */
    private fun showSrNotice(text: String, isError: Boolean) {
        clearAllSrRunningChips()
        notifyUser(text, if (isError) AppNotice.Style.ERROR else AppNotice.Style.INFO)
    }

    /**
     * 超分「进行中」提示的开合（**与「翻译中…」并排共存**，用户口径 2026-10）。
     *
     * ⚠️ 走 `AppNotice.showRunning` 而**不是** `Style.PROGRESS`：后者是 `showImmediate` =
     * **替换**顶部那一条 —— 超分一开始就把「翻译中…」顶掉，用户永远看不到两条并存
     * （`TranslationStatusOverlay.showRunning` 的注释里列了三个入口各自为什么不行）。
     *
     * ⚠️ **按页记账**：整章批量时每页都会起一次超分，几页可以同时在等 `OcrLock`。
     * 只存一个句柄的话后一页会覆盖前一页 → 前一页收尾时把**后一页**的芯片摘掉，
     * 屏幕上就是"芯片随机消失"。
     */
    private fun showSrRunning(page: Int, running: Boolean) {
        clearSrRunningChip(page)   // 同页重复进入（重试 / 重翻）先收掉上一条，避免叠成两条
        if (running) {
            srRunningChipByPage[page] =
                AppNotice.showRunning(this, getString(R.string.sr_enhancing_page, page + 1))
        }
    }

    /** 手动点「超分 / 重新超分」时的进行中提示（与自动超分走同一条路）。 */
    private fun showSrProgress(page: Int) = showSrRunning(page, running = true)

    private fun clearSrRunningChip(page: Int) {
        AppNotice.clearRunning(this, srRunningChipByPage.remove(page) ?: 0L)
    }

    /**
     * 收掉**全部**「正在超分…」芯片（超分出结果时要顶掉它们）。
     *
     * ⚠️ 只动超分自己这一类芯片：并排的「翻译中…」「正在翻译本章…」一条都不能碰 ——
     * 这正是它取代原来那句 `overlay.dismiss()` 的原因。
     */
    private fun clearAllSrRunningChips() {
        for (id in srRunningChipByPage.values) AppNotice.clearRunning(this, id)
        srRunningChipByPage.clear()
    }

    /** 注入「页图提供者」：适配器绑定页时优先取译文/原文渲染图（无则原图），
     *  避免 RecyclerView 重绑/复用把已显示的译图覆盖回原图。 */
    private fun applyPageImageSource() {
        val controller = translationController ?: return
        val provider: (Int) -> android.graphics.Bitmap? = { page -> controller.cachedDisplayBitmap(page) }
        pageAdapter?.setPageImageProvider(provider)
    }

    /**
     * 状态浮层（与截屏翻译路线一致）：检测中 / 翻译中 / 完成 / 失败。
     *
     * ⚠️ 两类消息的口径不同：
     * - **进行中**（DETECTING/TRANSLATING）是**连续状态**，直接走浮层的常驻芯片：
     *   浮层开关关掉时保持静默是那个设置本身的语义，不能退化成每页两条 Toast 刷屏。
     * - **结果/报错**（完成 / 失败 / 队列耗尽）走 [notifyUser] 统一出口，
     *   浮层画不出来（没权限）时至少还有 Toast，不会「翻完什么都没说」。
     * 成功/失败必须 dismiss 掉常驻的「翻译中」进度芯片，否则一直挂着（还会跨场景残留）。
     */
    private fun onTranslatePhase(phase: ReaderTranslatePhase, message: String?) {
        runOnUiThread {
            val p = translationController?.queuePage?.value ?: -1
            // 带页码：增量连续翻页时用户要看得出进度走到哪一页
            fun withPage(base: String) =
                if (p >= 0) getString(R.string.reader_translate_status_page, base, p + 1) else base
            // 进行中的常驻芯片：唯一一处**有意**不走 notifyUser 的浮层用法（见上面的口径）
            fun progress(text: String) =
                TranslationStatusOverlay.getInstance(this).showImmediate(text, autoDismiss = false)

            when (phase) {
                // message 优先：分批时管线会给出「识别中（1/2）…」这类带批次的文案（与截屏翻译对齐）
                ReaderTranslatePhase.DETECTING -> {
                    setTranslatingPulse(true)
                    progress(withPage(message ?: getString(R.string.reader_translate_detecting)))
                }
                ReaderTranslatePhase.TRANSLATING -> {
                    setTranslatingPulse(true)
                    progress(withPage(message ?: getString(R.string.reader_translate_in_progress)))
                }
                ReaderTranslatePhase.SUCCESS -> {
                    setTranslatingPulse(false)
                    TranslationStatusOverlay.getInstance(this).dismiss()
                    // message 携带缓存说明（「12 条里命中 3 条」）；无缓存命中时它是 null，走原文案。
                    // 用户必须能分辨「真的调了 API」和「全部命中缓存」——否则同页重翻会像凭空成功。
                    notifyUser(message ?: getString(R.string.reader_translate_done))
                    refreshProgressTranslation()
                    // 成功页要立刻出现三态按钮（此前要等下一次 onVisual 重绑才出现）
                    refreshTranslationChrome()
                }
                ReaderTranslatePhase.FAILED -> {
                    setTranslatingPulse(false)
                    TranslationStatusOverlay.getInstance(this).dismiss()
                    notifyUser(message ?: getString(R.string.reader_translate_failed), AppNotice.Style.ERROR)
                    refreshProgressTranslation()
                    // ⚠️ 必须刷新：失败感叹号只在 refreshTranslationChrome 里被置 VISIBLE，
                    // 而错误 chip 几秒后就自动消失 —— 不刷的话用户此后再也没有入口看失败原因
                    //（控制器发 FAILED 时不走 onVisual）
                    refreshTranslationChrome()
                }
                // 队列跑完：提示一下随即消失，翻页后队列会自动重启
                ReaderTranslatePhase.QUEUE_DRAINED -> {
                    setTranslatingPulse(false)
                    TranslationStatusOverlay.getInstance(this).dismiss()
                    notifyUser(getString(R.string.reader_translate_queue_drained))
                    refreshProgressTranslation()
                }
            }
        }
    }

    /** 翻到失败页时点感叹号：小气泡显示失败原因（不用弹窗）。 */
    private fun showFailBubble() {
        val controller = translationController ?: return
        val reason = controller.failMessageOf(currentPage) ?: getString(R.string.reader_translate_failed)
        // 走统一出口：浮层画不出来时（开关关掉 / 没悬浮窗权限）至少还有 Toast，
        // 否则用户点了红色感叹号「什么都没发生」，而失败原因是他唯一能自查的东西
        notifyUser(getString(R.string.reader_translate_failed_page_hint, currentPage + 1, reason))
    }

    /** 同步翻译浮层组：成功页显示三态按钮、失败页显示感叹号；隐藏态 / Webtoon 整组隐藏。 */
    private fun refreshTranslationChrome() {
        // Webtoon（连续滚动）不支持单页翻译，也没有单页三态 → 整个浮层组（翻译/重翻 +
        // 三态 + 失败感叹号）隐藏，只留底部模式分段器上「连续滑动」按钮的两态角标。
        // 用户在中间点过一下的「隐藏上下 UI」同理：整组收起，直到再点一次中间。
        // ⚠️ 必须在 controller 判空之前：否则控制器未就绪时整组会留在屏幕上没人收。
        if (chromeHidden || mode == 3) {
            binding.translateGroup.visibility = View.GONE
            // ⚠️ 超分组必须**一起**收起：`refreshSrButtons` 里那句 `mode == 3 → srGroup GONE`
            //    在这个早退分支下永远执行不到（它在本函数末尾才被调）→ 从分页模式切到
            //    连续滑动后，左下角的「超分/切换/删除」三件套会一直压在 Webtoon 上，
            //    而且点它作用的是 currentPage（Webtoon 没有单页语义）→ 用户看到"点了没反应"。
            binding.srGroup.visibility = View.GONE
            return
        }
        binding.translateGroup.visibility = View.VISIBLE

        val controller = translationController ?: return
        val state = controller.stateOf(currentPage)
        val translated = state == ImportedPageTranslation.STATE_SUCCESS
        val failed = state == ImportedPageTranslation.STATE_FAILED

        binding.btnToggleTranslate.visibility = if (translated) View.VISIBLE else View.GONE
        binding.btnFailTranslate.visibility = if (failed) View.VISIBLE else View.GONE
        // 清除本页译文（**最右边**，用户口径）：只在当前页**有译文**时出现
        binding.btnClearTranslate.visibility = if (translated) View.VISIBLE else View.GONE
        refreshSrButtons(controller)
        // 翻译按钮图标：成功 → 重翻图标；未译/失败 → 翻译图标
        binding.ivTranslate.setImageResource(
            if (translated) R.drawable.ic_refresh else R.drawable.ic_reader_translate
        )
        if (translated) {
            binding.ivToggleTranslate.setImageResource(overlayModeIcon(controller.currentVisual(currentPage)))
        }
        // 走到这里说明是分页模式（Webtoon 在上面已整组隐藏）→ 按钮恒可用，无需置灰
    }

    /** 三态图标（与截屏翻译一致：相机=译文 / 图库=原文 / 眼睛=纯原图）。 */
    private fun overlayModeIcon(mode: TranslationCacheManager.OverlayMode): Int = when (mode) {
        TranslationCacheManager.OverlayMode.TRANSLATED -> android.R.drawable.ic_menu_camera
        TranslationCacheManager.OverlayMode.ORIGINAL -> android.R.drawable.ic_menu_gallery
        else -> android.R.drawable.ic_menu_view
    }

    /**
     * 超分三件套的显隐与图标（**与翻译三件套完全对称**，用户口径 2026-10）：
     *
     * | 元素 | 何时显示 | 图标 |
     * |---|---|---|
     * | `btn_sr_page` 超分/重新超分 | 超分开启时**恒显示** | 有结果 → `ic_refresh`（同"重翻"）；无 → `ic_reader_sr` |
     * | `btn_sr_toggle` 原图⇄超分 | **有结果才显示**（同三态按钮） | 正显示超分图 → `ic_reader_sr` + 高亮蓝；显示原图 → 图库图标 + 白 |
     * | `btn_sr_clear` 删除超分结果 | **有结果才显示**（同清除译文） | 删除图标 |
     *
     * ⚠️ 超分关闭时三枚一起 GONE（用户口径：阅读器不显示超分相关按钮）。
     * ⚠️ 以前只有一枚按钮既跑超分又切换 —— 用户看不出点下去会发生什么
     *    （真机反馈「切换按钮和超分按钮重复了」「超分完之后为什么没办法切换」）。
     */
    private fun refreshSrButtons(controller: ReaderTranslationController) {
        val action = controller.srActionOf(currentPage)
        // ⚠️ **整组的显隐在这里统一决定**（它现在是独立的一组，不再挂在翻译组里）：
        // 超分关闭 → 整组 GONE；Webtoon（mode==3）/ 隐藏上下 UI 时也整组 GONE。
        // ⚠️ 注意：**只有"超分功能关闭"才 GONE** —— 用户把某一页切回原图**不是**关闭超分，
        //    那一页的三枚按钮必须留着（否则就再也切不回超分图了，真机踩过）。
        if (action == ReaderTranslationController.SrAction.HIDDEN || chromeHidden || mode == 3) {
            binding.srGroup.visibility = View.GONE
            return
        }
        binding.srGroup.visibility = View.VISIBLE
        val hasResult = controller.hasSrResult(currentPage)
        // ① 超分 / 重新超分：恒显示
        binding.btnSrPage.visibility = View.VISIBLE
        binding.ivSrPage.setImageResource(if (hasResult) R.drawable.ic_refresh else R.drawable.ic_reader_sr)
        val busy = action == ReaderTranslationController.SrAction.BUSY
        binding.btnSrPage.isEnabled = !busy
        binding.ivSrPage.alpha = if (busy) 0.4f else 1f
        // ② 二态切换 + ③ 删除：**有超分结果才出现**
        binding.btnSrToggle.visibility = if (hasResult) View.VISIBLE else View.GONE
        binding.btnSrClear.visibility = if (hasResult) View.VISIBLE else View.GONE
        if (hasResult) {
            val showingSr = controller.srBaseOn(currentPage)
            binding.ivSrToggle.setImageResource(
                if (showingSr) R.drawable.ic_reader_sr else android.R.drawable.ic_menu_gallery
            )
            binding.ivSrToggle.imageTintList = android.content.res.ColorStateList.valueOf(
                if (showingSr) 0xFF55AEEA.toInt() else Color.WHITE
            )
        }
    }

    /**
     * 「超分 / 重新超分」：无条件跑一次（有结果就覆盖）。
     *
     * ⚠️ **只有这一路才挂「正在超分…」常驻提示**；二态切换是瞬时的（真机日志实证：
     * 切换时冒一句「正在超分第 N 页…」会让用户以为"没法切换，它又重超了一遍"）。
     */
    private fun onSrEnhanceClicked() {
        val controller = translationController ?: return
        if (controller.srActionOf(currentPage) == ReaderTranslationController.SrAction.BUSY) {
            showSrNotice(getString(R.string.sr_fail_busy), isError = false)
            return
        }
        binding.btnSrPage.isEnabled = false
        showSrProgress(currentPage)
        lifecycleScope.launch {
            val result = controller.onSrButtonClicked(currentPage)
            binding.btnSrPage.isEnabled = true
            refreshTranslationChrome()
            when (result) {
                is ReaderTranslationController.SrClickResult.Failed -> showSrNotice(result.message, isError = true)
                is ReaderTranslationController.SrClickResult.Done ->
                    showSrNotice(getString(R.string.sr_done_page, currentPage + 1), isError = false)
            }
        }
    }

    /** 「原图 ⇄ 超分底图」二态切换（瞬时，不挂进度提示）。 */
    private fun onSrToggleClicked() {
        val controller = translationController ?: return
        lifecycleScope.launch {
            controller.toggleSrBase(currentPage)
            refreshTranslationChrome()
            // ⚠️ 文案按**切换后的真实显示态**取，不要用 `toggleSrBase` 返回的 `SrAction`：
            // 那个枚举描述的是"按钮的下一个动作"（SHOW_ORIGINAL = 当前正显示超分底图），
            // 而且这一页正在超分（BUSY）时它返回的根本不是二态值 → 文案会张冠李戴。
            showSrNotice(
                getString(
                    if (controller.srBaseOn(currentPage)) R.string.sr_show_sr_page
                    else R.string.sr_show_original_page,
                    currentPage + 1,
                ),
                isError = false,
            )
        }
    }

    /** 「删除本页超分结果」：**二次确认**（与清除译文同一约定：所有删除都要确认）。 */
    private fun onSrClearClicked() {
        val controller = translationController ?: return
        ReaderDialogs.show(this, isDarkBackground()) {
            setTitle(R.string.reader_sr_clear_confirm_title)
            setMessage(getString(R.string.reader_sr_clear_confirm_msg, currentPage + 1))
            setPositiveButton(R.string.reader_sr_clear_ok) { _, _ ->
                lifecycleScope.launch {
                    val deleted = controller.deleteSrResult(currentPage)
                    refreshTranslationChrome()
                    showSrNotice(
                        getString(
                            if (deleted) R.string.sr_clear_done_page else R.string.sr_clear_none_page,
                            currentPage + 1,
                        ),
                        isError = false,
                    )
                }
            }
            setNegativeButton(R.string.cancel, null)
        }
    }
    /** 刷新进度条上的「已翻译」绿色区间。 */
    private fun refreshProgressTranslation() {
        binding.readerProgress.setTranslatedPages(translationController?.translatedPages() ?: emptySet())
        // 顺带把打开着的面板一起刷新：面板是打开那一刻的快照，宿主不推它就停在旧状态
        // （`ReaderMenuSheet.notifyTranslateChanged` 以前从来没有调用方 —— 面板开着时
        // 译文在涨、每页列表与汇总却一直不动）
        //
        // ⚠️ **必须节流**：本方法在每页的每个阶段变化（QUEUED/OCR/TRANSLATE/结算 ≈ 4 次/页）都会被调，
        // 而面板推送要排序全部页记录 + 整表重建（`notifyDataSetChanged` + childRowsOf 扫描）——
        // 章节任务跑起来就是主线程持续掉帧。300ms 合并一次，肉眼无差别。
        val sheet = supportFragmentManager.findFragmentByTag(ReaderMenuSheet.TAG) as? ReaderMenuSheet ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPanelPushMs < PANEL_PUSH_MIN_MS) return
        lastPanelPushMs = now
        // 状态由面板自己从 `cb.currentChapterState()` 回读（唯一状态源），宿主只负责"叫它刷新"
        sheet.notifyTranslateChanged()
    }

    /** 上一次把状态推给面板的时刻（节流用，见 [refreshProgressTranslation]）。 */
    private var lastPanelPushMs = 0L

    /**
     * 把面板状态**立即**推一次。
     *
     * ⚠️ 与 [refreshProgressTranslation] 的节流版分工不同：那个是**高频**路径（每页四个阶段变化）
     * 必须合并；这里是**用户显式操作**（点了超分本章 / 清空本章），晚 300ms 才更新会被当成"点了没反应"。
     */
    private fun pushPanelNow() {
        val sheet = supportFragmentManager.findFragmentByTag(ReaderMenuSheet.TAG) as? ReaderMenuSheet ?: return
        lastPanelPushMs = android.os.SystemClock.elapsedRealtime()
        sheet.notifyTranslateChanged()
    }

    /** 把指定页显示切到 controller 的当前态（译文/原文/原图）。
     *  不直接写 visibleImage（它可能是邻页，写错视图 = 错图）；而是把该页当前态渲染图预热进缓存后
     *  `notifyItemChanged` 重绑，由适配器经「页图提供者」按【槽位】取回正确的图。 */
    private fun applyPageVisual(pageIndex: Int) {
        val controller = translationController ?: return
        refreshTranslationChrome()
        // 进度条的绿色「已翻译」区间在每次翻译完成后都要刷新。
        // ⚠️ 必须挂在这里：后台队列页翻完时 `onPhase` 被有意静音（不给非当前页刷状态浮层），
        // 而本方法由控制器的 onVisual 对**每一页**完成都会调用 → 是唯一能覆盖队列页的刷新点。
        refreshProgressTranslation()
        // Webtoon：译图由 prewarmWebtoon 限范围预热，这里只把附近几页重绑即可
        if (mode == 3) {
            refreshWebtoonRange(pageIndex)
            return
        }
        val visual = if (controller.stateOf(pageIndex) == ImportedPageTranslation.STATE_SUCCESS)
            controller.currentVisual(pageIndex) else null
        lifecycleScope.launch(Dispatchers.IO) {
            if (visual != null && visual != TranslationCacheManager.OverlayMode.PLAIN) {
                controller.visualBitmap(pageIndex, visual) // 渲染 + 预热缓存
            } else {
                // 没有 overlay 可渲染（未翻译 / 纯原图态）：仍要把**显示底图**预热进内存 ——
                // 适配器取图走 `cachedDisplayBitmap`，它只读内存；不预热的话开着超分也只显示糊的原图。
                controller.warmSrBase(pageIndex)
            }
            runOnUiThread {
                // 底图的探测发生在 IO 侧（`srModelByPage` 在那里 seed）→ 回来后再刷一次右下角按钮
                refreshTranslationChrome()
                pageAdapter?.notifyItemChanged(pageIndex)
            }
        }
    }

    private fun openPagePreview() {
        // dark = 阅读背景深浅（缩略图预览弹窗也要适配，不再恒白底）
        ReaderPagePreviewDialog(this, source, currentPage, isDarkBackground()) { page ->
            goToPage(page)
            refreshOverlay()
        }.show()
    }

    // ===== 底部工具栏 =====

    private fun showMenu() {
        if (supportFragmentManager.findFragmentByTag(ReaderMenuSheet.TAG) != null) return
        val dark = isDarkBackground()
        // 调色对比图：异步加载当前页
        lifecycleScope.launch {
            val previewBmp = withContext(Dispatchers.IO) { source.loadFull(currentPage) }
            // 「处理后」预览格要能看到**超分结果**（用户口径）：必须在 IO 侧准备好，
            // 面板是主线程构建的，绝不能在里面读盘/缩放（那正是"开面板卡死"的老毛病）。
            val srPreview = previewBmp?.let { bmp ->
                withContext(Dispatchers.IO) {
                    translationController?.srPreviewFor(currentPage, bmp.width)
                }
            }
            // ⚠️ 超分没有产出时（超分关着 / 没下载模型 → 没有已落盘的超分图），「处理后」预览格
            //    必须自己跑一遍 **Anime4K**：否则右侧永远等于原图，切 Anime4K 档位看不到任何变化
            //    （用户报的"切了档预览没反应"）。而在面板里跑推理就是"一点就卡"，
            //    所以和 srPreview 一样**在 IO 侧算好**再交给面板。
            val anime4kPreview = if (previewBmp != null && srPreview == null) {
                withContext(Dispatchers.IO) { anime4kPreviewFor(previewBmp) }
            } else {
                null
            }

            // ⚠️ **顺序敏感**：面板一打开，控制器就会回退到手动模式（setPanelOpen → pauseToManual），
            // 所以必须在这里、回退发生**之前**记下模式：
            //   · 提示用 —— 从自动/增量退下来要告知用户，否则用户以为设置被清掉了
            //   · 面板初值用 —— 否则面板会直接显示"手动"，连"退回过"都看不出来
            // 控制器侧没有对应回调可用：它只能看到"原本有没有任务在跑"，队列空闲时不会提示，
            // 而那种情况恰恰最需要提示。
            val modeBeforeOpen = translationController?.translateMode?.value
                ?: ReaderTranslationController.MODE_MANUAL
            val sheet = ReaderMenuSheet(
                ReaderMenuState(
                    mode = mode,
                    animation = animationMode,
                    bg = bgMode,
                    autoTurn = autoTurnEnabled,
                    intervalSec = autoTurnIntervalSec,
                    colorFilter = colorFilter,
                    rotateLabel = rotateLabel(),
                    downloadLabel = getString(R.string.reader_download_original),
                    isDarkPanel = dark,
                    previewBitmap = previewBmp,
                    srPreviewBitmap = srPreview,
                    anime4kPreviewBitmap = anime4kPreview,
                    webtoonTranslated = webtoonTranslated,
                    translateMode = modeBeforeOpen,
                    debounceMs = translationController?.debounceMs?.value ?: 500,
                    aheadPages = translationController?.aheadPages?.value ?: 5,
                    pageTranslations = translationController?.records() ?: emptyList(),
                    chapters = chapters,
                    currentChapter = currentChapterIndex(),
                    chapterJobs = translationController?.chapterJobs?.value.orEmpty()
                        .associate { it.chapterIndex to it.state },
                    chapterJobDone = translationController?.chapterJobs?.value.orEmpty()
                        .associate { it.chapterIndex to it.done },
                    chapterJobTotal = translationController?.chapterJobs?.value.orEmpty()
                        .associate { it.chapterIndex to it.total },
                    waitingPages = translationController?.panelWaitingPages() ?: emptySet(),
                    ocrPages = translationController?.ocrPages() ?: emptySet(),
                    translatingPages = translationController?.translatingPages() ?: emptySet(),
                    // ⚠️ 读默认 prefs（与控制器/写入侧同一个文件，别用阅读器自己的 `prefs`）
                    concurrency = TranslationConcurrency.mangaConcurrency(
                        applicationContext,
                        PreferenceManager.getDefaultSharedPreferences(applicationContext),
                    )
                ),
            ReaderMenuCallbacks(
                onMode = { m ->
                    prefs.edit().putInt(KEY_MODE, m).apply()
                    mode = m
                    // 连续滑动的显示态先落到控制器，applyPager() 里的预热才会按当前态渲染
                    translationController?.setWebtoonTranslated(webtoonTranslated)
                    applyPager()
                    goToPage(currentPage)
                    // Webtoon 不支持翻译：切过去时把队列停掉并退回手动。
                    // 否则队列会在用户看不到的地方继续往后翻，而按钮已被禁用、用户停不掉。
                    if (isTranslateDisabledByMode()) {
                        translationController?.setMode(ReaderTranslationController.MODE_MANUAL)
                        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
                        // Webtoon 下整个翻译浮层组都隐藏，但面板可能正开着 —— 模式选项也要回到手动
                        (supportFragmentManager.findFragmentByTag(ReaderMenuSheet.TAG) as? ReaderMenuSheet)
                            ?.setTranslateMode(ReaderTranslationController.MODE_MANUAL)
                    }
                    // 切模式后按钮的置灰态要重刷
                    refreshTranslationChrome()
                    // Webtoon ↔ 分页切换后重算自动翻页（webtoon 禁用、分页按开关恢复）
                    updateAutoTurn()
                },
                // 「连续滑动」按钮再次点击：模式不变，只切 原图 ↔ 译文
                onWebtoonTranslated = { v ->
                    webtoonTranslated = v
                    prefs.edit().putBoolean(KEY_WEBTOON_TRANSLATED, v).apply()
                    translationController?.setWebtoonTranslated(v)
                    // **全量重绑**：所有已附着页一次性换到新状态，不做逐页增量刷新。
                    // 每次 onBindViewHolder 都会 cancel 掉该 holder 上一次的取图 → 旧状态的在途结果
                    // 不会晚一步落回来。行高已按原图宽高比钉死 + 关掉了 itemAnimator，重绑不闪不跳。
                    // ⚠️ 用 notifyItemRangeChanged（update 事件）而不是 notifyDataSetChanged：
                    // 后者是 structure-changed 事件，会重置布局锚点、把滚动位置弹掉。
                    val wl = binding.webtoonList
                    val n = wl.adapter?.itemCount ?: 0
                    if (n > 0) wl.adapter?.notifyItemRangeChanged(0, n)
                    // 切到译文：补齐附近还没渲染的页（已渲染的在缓存里，秒回）；切到原图无需渲染
                    translationController?.prewarmWebtoon(currentPage)
                },
                onAnimation = { a -> prefs.edit().putInt(KEY_ANIM, a).apply(); animationMode = a; applyAnimation() },
                onBackground = { b -> prefs.edit().putInt(KEY_BG, b).apply(); bgMode = b; applyBackground() },
                onAutoTurn = { enabled, interval ->
                    prefs.edit().putBoolean(KEY_AUTO_TURN, enabled).putInt(KEY_INTERVAL, interval).apply()
                    autoTurnEnabled = enabled
                    autoTurnIntervalSec = interval
                    updateAutoTurn()
                },
                onColorFilterChanged = { f -> applyColorFilter(f) },
                onResetColor = {
                    // 复用 applyColorFilter：它写 prefs + 更新 colorFilter + 给**两条适配器**上实时滤镜。
                    // 原来这里手写 prefs 后调 reloadCurrentPageColor()（notifyItemChanged 触发重绑，
                    // 重新解码是异步的）→ 点重置后页面不会立刻恢复，面板侧也拿不到任何刷新信号。
                    applyColorFilter(ReaderColorFilter.EMPTY)
                },
                onRotate = { updateRotateMode((rotateMode + 1) % 3, persist = true) },
                onDownload = { showDownloadDialog() },
                onSettings = {
                    // 从个性化设置返回后重建阅读器（配置项要重新读取才生效，见 onStart）。
                    // 顺带记下翻译模式：控制器随 Activity 一起重建，不恢复的话自动/增量会退回手动。
                    returnedFromSettings = true
                    restoredTranslateMode =
                        translationController?.translateMode?.value ?: ReaderTranslationController.MODE_MANUAL
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION))
                },
                // ⚠️ 面板必须能回读宿主**真实**模式：onPanelOpened 里 setPanelOpen(true) 会把模式
                // 回退到手动（ReaderTranslationController.pauseToManual）。面板若只认打开时的旧快照，
                // 就会显示「自动」而实际是手动 —— 用户想切回自动时点的正是那个已勾选的条目，
                // RadioButton 同组内重复选中不派发回调 → 模式彻底切不动。
                currentTranslateMode = { translationController?.translateMode?.value ?: 0 },
                onTranslateMode = { m ->
                    translationController?.setMode(m)
                    // 切回手动时队列已停，「翻译中…」常驻芯片必须清掉，否则会一直挂在屏幕上
                    if (m == ReaderTranslationController.MODE_MANUAL) {
                        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
                    }
                    refreshTranslationChrome()
                },
                onDebounceMs = { ms -> translationController?.debounceMs?.value = ms },
                onAheadPages = { n -> translationController?.aheadPages?.value = n },
                onTranslatePageJump = { page -> goToPage(page) },
                // 翻译面板开合：打开时暂停队列（用户在调设置），关闭后才恢复 ——
                // 即"选了自动/增量也要等退出面板才开始翻"
                onPanelOpened = {
                    translationController?.setPanelOpen(true)
                    TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
                    // 回退到手动必须告知（modeBeforeOpen 是回退前的模式，见上方说明）
                    if (modeBeforeOpen != ReaderTranslationController.MODE_MANUAL) {
                        showPausedToManualNotice()
                    }
                },
                onPanelClosed = { translationController?.setPanelOpen(false) },
                onOpenModelManagement = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT))
                },
                onOpenApiConfig = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_API_CONFIG))
                },
                // 超分模型行：跳模型管理页的「超分」Tab（EXTRA_MODEL_SHOW_SR 让页面直接落在超分那一页）
                onOpenSrModelManagement = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT)
                        .putExtra(SettingPageActivity.EXTRA_MODEL_SHOW_SR, true))
                },
                // 阅读器独立超分开关：值已由面板写入 prefs，这里只负责提示。
                // ⚠️ 不在这里预热引擎：2x 模型加载要 1s+，开关一拨就卡一下体感很差；
                //   首次真正翻页时自然建立，失败也会按约定静默降级回原图。
                onReaderSrChanged = { enabled ->
                    // 用户口径：超分的提示也走 app 状态浮层，不用手机底部 Toast
                    showSrNotice(
                        getString(if (enabled) R.string.reader_sr_switch_on else R.string.reader_sr_switch_off),
                        isError = false,
                    )
                    // 开关改的是**显示的底图**（超分底图 ⇄ 原图）→ 与字号/Anime4K 同一条链路：
                    // 作废渲染缓存 + 重渲染当前页。不作废的话关掉开关画面上还是超分图。
                    onAnime4kModeChangedFromPanel()
                    refreshTranslationChrome()
                },
                onReaderSrAutoChanged = { enabled ->
                    showSrNotice(
                        getString(if (enabled) R.string.reader_sr_auto_on else R.string.reader_sr_auto_off),
                        isError = false,
                    )
                },
                // ===== 章节卡片：主按钮（翻译本章/暂停/继续）+ 次按钮（清除本章译文/取消）=====
                onChapterSelected = { index -> goToChapterIndex(index) },
                // 超分记录列表（面板切到「超分」页签）同源接线
                onSrChapterPrimary = { index -> onSrChapterPrimaryClicked(index) },
                onSrChapterSecondary = { index -> confirmClearChapterSr(index) },
                onSrDeletePage = { page -> confirmDeleteSrPage(page) },
                onChapterPrimary = { index -> onChapterPrimaryClicked(index) },
                onChapterSecondary = { index -> onChapterSecondaryClicked(index) },
                // 面板行内「删除」与右下角清除图标共用同一个确认流程
                onDeletePage = { page -> confirmClearPage(page) },
                currentChapterState = { chapterPanelState() },
                onConcurrency = { n ->
                    // ⚠️ 必须写**默认 prefs**：控制器（`ReaderTranslationController.appPrefs`）与
                    // `TranslationConcurrency.mangaConcurrency` 都读 `getDefaultSharedPreferences`。
                    // 写到阅读器自己的 `manga_reader` 文件里，滑块看着能调、值也存下来了，
                    // 但批量翻译永远用默认值 —— 静默失效（典型的"两个 prefs 文件"坑）。
                    PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .edit().putInt(TranslationConcurrency.KEY_MANGA, n).apply()
                },
                // 面板里拖字号滑块 / 切「自动」：作废已渲染译图并重渲染当前页
                onFontSizeChanged = { onFontSizeChangedFromPanel() },
                // Anime4K 档位变更：与字号同一条链路（作废渲染缓存 + 重渲染当前页）
                onAnime4kModeChanged = { onAnime4kModeChangedFromPanel() }
            )
        )
        sheet.show(supportFragmentManager, ReaderMenuSheet.TAG)
        }
    }

    // ===== 背景（含面板配色来源判断） =====

    private fun applyBackground() {
        val bg = resolveBgColor()
        binding.root.setBackgroundColor(bg)
        binding.viewPager.setBackgroundColor(bg)
        binding.webtoonList.setBackgroundColor(bg)
        // 进度条配色固定（压在半透明深色胶囊上），不随页面背景深浅变化
        binding.tvPageIndicator.setTextColor(if (isDarkBackground()) Color.WHITE else Color.BLACK)
    }

    private fun resolveBgColor(): Int = when (bgMode) {
        1 -> Color.rgb(0xF0, 0xF0, 0xEE)          // Light
        2 -> Color.rgb(0x18, 0x18, 0x1C)          // Dark
        3 -> Color.WHITE
        4 -> Color.BLACK
        5 -> {
            // 自动：跟系统夜间（与 isDarkBackground() 同一判断源，避免 app 强制主题把两者拆散）
            if (isSystemDark()) Color.BLACK else Color.WHITE
        }
        else -> if (isSystemDark()) Color.BLACK else Color.WHITE
    }

    /**
     * 系统是否深色（独立于 app 强制主题）。⚠️ 全局主题切换不得影响阅读器「默认/自动」背景——
     * 读 Resources.getSystem()（框架系统资源），不受 AppCompat 强制日夜影响。
     */
    private fun isSystemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isDarkBackground(): Boolean = when (bgMode) {
        2, 4 -> true
        3 -> false
        5 -> isSystemDark()
        1 -> false
        else -> isSystemDark()
    }

    // ===== 自动翻页 =====

    private fun updateAutoTurn() {
        autoTurnJob?.cancel()
        autoTurnJob = null
        if (!autoTurnEnabled || mode == 3 || source.size < 2) return
        autoTurnJob = lifecycleScope.launch {
            val intervalMs = autoTurnIntervalSec.toLong() * 1000
            while (isActive) {
                delay(intervalMs)
                if (SystemClock.elapsedRealtime() - lastInteractionMs < 2000L) continue
                turnPage(1)
            }
        }
    }

    // ===== 颜色矫正 =====

    private fun loadColor(): ReaderColorFilter = ReaderColorFilter(
        brightness = prefs.getFloat(KEY_BRIGHTNESS, 0f),
        contrast = prefs.getFloat(KEY_CONTRAST, 0f),
        isInverted = prefs.getBoolean(KEY_INVERT, false),
        isGrayscale = prefs.getBoolean(KEY_GRAY, false),
        isBookBackground = prefs.getBoolean(KEY_BOOK, false)
    )

    private fun applyColorFilter(f: ReaderColorFilter) {
        prefs.edit()
            .putFloat(KEY_BRIGHTNESS, f.brightness)
            .putFloat(KEY_CONTRAST, f.contrast)
            .putBoolean(KEY_INVERT, f.isInverted)
            .putBoolean(KEY_GRAY, f.isGrayscale)
            .putBoolean(KEY_BOOK, f.isBookBackground)
            .apply()
        colorFilter = f
        // 实时预览当前可见页。⚠️ **两条适配器都要刷**：分页只有一页（visibleImage 单槽），
        // Webtoon 同屏多页（按 attached child 逐页上滤镜）。只刷分页那条的话，
        // 「连续滑动」模式下拖滑块/点重置完全没反应，要滚动一下才生效。
        pageAdapter?.applyLiveColor(f)
        (binding.webtoonList.adapter as? WebtoonAdapter)?.applyLiveColor(f)
    }

    // ===== 旋转（configChanges 声明，不重建 Activity） =====

    private fun updateRotateMode(mode: Int, persist: Boolean) {
        rotateMode = mode
        if (persist) prefs.edit().putInt(KEY_ROTATE, mode).apply()
        requestedOrientation = when (mode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun rotateLabel(): String = when (rotateMode) {
        1 -> getString(R.string.reader_rotate_landscape)
        2 -> getString(R.string.reader_rotate_follow)
        else -> getString(R.string.reader_rotate_portrait)
    }

    // ===== 原文下载 =====

    private fun showDownloadDialog() {
        val dark = isDarkBackground()
        // ⚠️ 用**对应 night 模式的上下文**建弹窗（面板/按钮色由主题给），
        // 自定义内容视图再过 tintCustomView 按亮度翻转 —— 不再"换底 + 一律刷浅色"
        // （那正是"白底白字"的来源：面板底是主题给的白色，文字却被刷成了浅色）
        val themed = ReaderDialogs.context(this, dark)
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_reader_download, null, false)
        val dialog = AlertDialog.Builder(themed).setView(view).setNegativeButton(R.string.cancel, null).create()
        dialog.show()
        ReaderDialogs.style(dialog, dark)
        ReaderDialogs.tintCustomView(view, dark)

        view.findViewById<View>(R.id.row_download_original).setOnClickListener {
            dialog.dismiss(); exportOriginal()
        }
        view.findViewById<View>(R.id.row_download_translated).setOnClickListener {
            dialog.dismiss(); exportTranslated(both = false)
        }
        view.findViewById<View>(R.id.row_download_both).setOnClickListener {
            dialog.dismiss(); exportTranslated(both = true)
        }
    }

    private fun exportOriginal() {
        if (exportJob?.isActive == true) {
            notifyUser(getString(R.string.reader_download_busy))
            return
        }
        notifyUser(getString(R.string.reader_download_started))
        exportJob = lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { exportOriginalZip() }
            if (name != null) {
                notifyUser(getString(R.string.reader_download_done, name))
            } else {
                notifyUser(getString(R.string.reader_download_failed), AppNotice.Style.ERROR)
            }
        }
    }

    /**
     * 打包导出**已翻译页**：[both] = true 出双语包（每页原名原文 + `_译文.jpg`），false 只出译文。
     *
     * 文件名沿用原包的序号（见 [ExportNaming]）——只翻译了 1/2/5/6 页，包里就是 001/002/005/006，
     * 不是 1/2/3/4；未翻译的页不导出（译文无从谈起）。渲染在 IO 逐页进行，进度走状态浮层。
     */
    private fun exportTranslated(both: Boolean) {
        val controller = translationController ?: return
        if (exportJob?.isActive == true) {
            notifyUser(getString(R.string.reader_download_busy))
            return
        }
        val total = controller.translatedPages().size
        if (total == 0) {
            notifyUser(getString(R.string.reader_download_nothing))
            return
        }
        notifyUser(getString(R.string.reader_download_started))
        exportJob = lifecycleScope.launch {
            val tmp = File(cacheDir, "manga_export_${System.currentTimeMillis()}.zip")
            val overlay = TranslationStatusOverlay.getInstance(this@MangaReaderActivity)
            val showProgress = statusOverlayEnabled()
            // 「我到底显示过没有」：dismiss 只能按这个判据，不能只看开关。
            // 浮层是进程级共享单例，dismiss() 会清掉**全部**堆叠消息 —— 后台导出（跨 onStop 继续跑）
            // 在开关开着但从未显示过的情况下收尾，就会顺手清掉悬浮窗服务正在显示的翻译状态芯片。
            var progressShown = false
            // 进度只在**前台**显示：状态浮层是进程级 TYPE_APPLICATION_OVERLAY 窗口，而导出是
            // lifecycleScope 上**跨 onStop 继续跑**的任务（翻译队列 onStop 会暂停，导出不会）——
            // 不在前台还继续 showImmediate，就会把「正在导出」芯片重新贴到别的应用上挂到导出结束。
            fun progressVisible() = showProgress && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (progressVisible()) {
                progressShown = true
                overlay.showImmediate(getString(R.string.reader_download_progress, 0, total), autoDismiss = false)
            }
            try {
                val outcome = ReaderExport.exportTranslated(
                    source = source,
                    controller = controller,
                    both = both,
                    // 后缀随界面语言（中文 `_译文` / 英文 `_translated`），不在导出层硬编码
                    translatedSuffix = getString(R.string.reader_export_suffix_translated),
                    tempFile = tmp,
                ) { done, sum ->
                    if (progressVisible()) {
                        progressShown = true
                        overlay.showImmediate(getString(R.string.reader_download_progress, done, sum), autoDismiss = false)
                    }
                }
                // ⚠️ 只在自己显示过时才 dismiss（progressShown，不是开关）：浮层是共享单例且
                // dismiss() 清空**全部**堆叠消息，开关开着但没显示过时静默收尾会误伤别的芯片
                if (progressShown) overlay.dismiss()
                // 结果同样走 app 顶部浮层（用户口径：阅读器的提示全部用它），失败 = 红底芯片
                val notice = exportMessage(outcome, both, tmp)
                notifyUser(notice.text, if (notice.isError) AppNotice.Style.ERROR else AppNotice.Style.INFO)
            } finally {
                // ⚠️ 清理必须在 finally：导出中途退出阅读器会取消 lifecycleScope，上面所有
                // delete 都跑不到 —— 几百 MB 的临时包会一直躺在 cacheDir 里没人清
                tmp.delete()
            }
        }
    }

    /**
     * 导出结束后的用户可见结果：区分「没得导」「失败」「成功但跳过了页」。
     *
     * 文案带上「是不是报错」：调用方要据此选红底芯片还是普通芯片（阅读器的提示全部走
     * [notifyUser]）。别在调用方拿文案去和 `reader_download_failed` 比字符串 ——
     * 那样一改文案就静默退化成普通提示。
     */
    private data class ExportNotice(val text: String, val isError: Boolean)

    private suspend fun exportMessage(outcome: ExportOutcome, both: Boolean, tmp: File): ExportNotice = when (outcome) {
        ExportOutcome.Empty -> ExportNotice(getString(R.string.reader_download_nothing), false)
        ExportOutcome.Failed -> ExportNotice(getString(R.string.reader_download_failed), true)
        is ExportOutcome.Done -> {
            if (outcome.written == 0) {
                // 一页都没成功 → 不能把空包当成功交付（跳过的页已在日志里）
                ExportNotice(getString(R.string.reader_download_failed), true)
            } else {
                val display = displayNameFor(both)
                val saved = withContext(Dispatchers.IO) { writeToDownloads(display, tmp) }
                when {
                    !saved -> ExportNotice(getString(R.string.reader_download_failed), true)
                    // 跳过的页必须说出来：包不完整时用户得知道
                    outcome.skipped > 0 -> ExportNotice(
                        getString(R.string.reader_download_done_skipped, display, outcome.skipped), false
                    )
                    else -> ExportNotice(getString(R.string.reader_download_done, display), false)
                }
            }
        }
    }

    /** 下载文件名：书名（已过滤非法字符）+ 后缀（后缀随界面语言，见 strings）。 */
    private fun displayNameFor(both: Boolean): String = safeTitle() + getString(
        if (both) R.string.reader_export_zip_suffix_both else R.string.reader_export_zip_suffix_translated
    )

    /**
     * 书名取出用于文件名：过滤 `\ / : * ? " < > |` 与**控制字符**（换行/制表符同样非法），
     * 结果为空时给个兜底名 —— MediaStore 对非法 DISPLAY_NAME 会直接抛异常，表现为「导出失败」。
     */
    private fun safeTitle(): String =
        manga.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "manga" }

    private fun exportOriginalZip(): String? {
        val display = safeTitle() + getString(R.string.reader_export_zip_suffix_original)
        val tmp = File(cacheDir, "manga_export_${System.currentTimeMillis()}.zip")
        return try {
            if (manga.isArchive) {
                File(manga.localRoot).inputStream().use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
            } else {
                ZipOutputStream(FileOutputStream(tmp)).use { zip ->
                    for (i in 0 until source.size) {
                        val key = source.key(i) ?: continue
                        val file = File(manga.localRoot, key); if (!file.isFile) continue
                        // ⚠️ 用**原 key**（含子目录）而不是扁平化成文件名：目录导入的子目录漫画
                        // ch1/001.jpg 与 ch2/001.jpg 拍平后会撞名，ZipOutputStream 直接抛
                        // duplicate entry → 整包导出失败（与 ReaderExport 的规则保持一致）
                        zip.putNextEntry(java.util.zip.ZipEntry(key))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            val ok = writeToDownloads(display, tmp)
            if (ok) display else null
        } catch (e: Exception) {
            LogCollector.e("MangaReader", "exportOriginalZip failed tmp=$tmp", e)
            null
        } finally {
            tmp.delete()
        }
    }

    private fun writeToDownloads(displayName: String, file: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        var uri: Uri? = null
        return try {
            // ⚠️ insert 也必须在 try 里：MediaProvider 不可用（存储未挂载/受限）会抛
            // SecurityException / IllegalArgumentException，而本方法由协程直接调用 ——
            // 抛出去就是未捕获异常 → 进程崩溃
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val ok = uri?.let {
                contentResolver.openOutputStream(it)?.use { o -> file.inputStream().use { s -> s.copyTo(o) } } != null
            } ?: false
            // 写失败要把 MediaStore 里的空行删掉，否则下载目录留一个 0 字节的「已导出」幽灵文件
            if (!ok) uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            ok
        } catch (e: Exception) {
            LogCollector.e("MangaReader", "writeToDownloads failed name=$displayName", e)
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            false
        }
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
}