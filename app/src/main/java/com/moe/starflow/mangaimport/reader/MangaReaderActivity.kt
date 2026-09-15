package com.moe.starflow.mangaimport.reader

import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.databinding.ActivityMangaReaderBinding
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.translate.ReaderTranslatePhase
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.mangaimport.translate.TranslateClick
import com.moe.starflow.me.settings.SettingPageActivity
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        const val EXTRA_MANGA_ID = "manga_id"

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
    }

    private lateinit var binding: ActivityMangaReaderBinding
    private lateinit var manga: ImportedManga
    private lateinit var source: ReaderPageSource
    private lateinit var prefs: android.content.SharedPreferences

    private var currentPage = 0
    private var colorFilter = ReaderColorFilter.EMPTY
    private var autoTurnEnabled = false
    private var autoTurnIntervalSec = 5
    private var autoTurnJob: Job? = null
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
            // 本地文件已丢失/损坏（可能被手动删除）：不再展示空白阅读器
            UiUtils.showToast(this, getString(R.string.reader_file_lost))
            finish()
            return
        }

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyPager()

        goToPage(manga.lastReadPage.coerceIn(0, (source.size - 1).coerceAtLeast(0)))

        // 阅读器内嵌翻译：载入每页记录（含失败/成功态），接右下角翻译按钮与进度条
        translationController = ReaderTranslationController(this, manga, lifecycleScope).also { c ->
            c.bind(
                loadFull = { source.loadFull(it) },
                currentPage = { currentPage },
                pageCount = { source.size },
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
            // Webtoon 显示态（原图/译文）落在控制器上：applyPager()/预热都按它决定渲不渲染
            c.setWebtoonTranslated(webtoonTranslated)
            // 打开面板 / 退出阅读器暂停翻译并回退手动 → 系统底部提示
            c.onPaused = {
                runOnUiThread {
                    UiUtils.showToast(this, getString(R.string.reader_translate_paused_to_manual))
                }
            }
        }
        lifecycleScope.launch {
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
    }

    override fun onStart() {
        super.onStart()
        updateAutoTurn()
        translationController?.resumeFromBackground()
    }

    override fun onStop() {
        super.onStop()
        autoTurnJob?.cancel()
        autoTurnJob = null
        // 切后台必须暂停队列：lifecycleScope 不会因 onStop 取消，否则 OCR + 翻译 + HyMT2 推理
        // 会在后台整段跑，且常驻状态芯片（系统窗口）会一直盖在别的应用上
        translationController?.pauseForBackground()
        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
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
        pageAdapter = ReaderPageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBackground()
        applyAnimation()
        // 横竖屏都是单页、翻译也不再按朝向禁用 → 旋转后无需重建 adapter，也不用停翻译队列
        refreshTranslationChrome()
    }

    override fun onDestroy() {
        // 退出阅读器：停止翻译并回退手动模式，有在跑则底部提示
        val wasActive = translationController?.translateMode?.value != ReaderTranslationController.MODE_MANUAL
        translationController?.shutdown()
        // ⚠️ 必须清掉状态浮层：它是**进程级单例 + TYPE_APPLICATION_OVERLAY 系统窗口**，
        // 退出阅读器后「检测中…／翻译中…」会挂在桌面/其它页面上，且没有任何入口能消掉
        // （直到下一次翻译成功或失败）。翻译在途时退出阅读器就会触发。
        TranslationStatusOverlay.getInstance(this@MangaReaderActivity).dismiss()
        if (wasActive) UiUtils.showToast(this, getString(R.string.reader_translate_paused_to_manual))
        super.onDestroy()
    }

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
                LogCollector.i("ReaderAnim", "scroll p=$position off=$positionOffset marker=$marker nav=${animState.navigationProgress} anchor=${animState.anchorPage} mode=$mode anim=$animationMode bw=${animState.isBackward}")
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
        if (y < h * 0.22f && x > w * 0.72f) { showMenu(); return }
        if (mode == 2) {
            // 竖排：点击上半=上一页，下半=下一页
            turnPage(if (y >= h / 2f) 1 else -1)
            return
        }
        val isRtl = mode == 1
        val goNext = if (isRtl) x < w / 2f else x >= w / 2f
        turnPage(if (goNext) 1 else -1)
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
        binding.btnPrev.setOnClickListener { turnPage(-1) }
        binding.btnNext.setOnClickListener { turnPage(1) }
        binding.btnMenu.setOnClickListener { showMenu() }

        // 分页进度：只注册一次（applyPager 会重建 adapter，但回调挂在 viewPager 上，无需重复注册）
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
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

    /** Webtoon 点击：点下半屏=平滑滚动到下一页顶部，上半屏=上一页顶部；右上角菜单位仍弹菜单。 */
    private fun webtoonTapToPage(y: Float, x: Float) {
        val w = binding.webtoonList.width.toFloat()
        val h = binding.webtoonList.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (y < h * 0.22f && x > w * 0.72f) { showMenu(); return }
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
        binding.tvPageIndicator.text = getString(R.string.reader_page_indicator, currentPage + 1, source.size)
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
            if (isTranslateDisabledByMode()) {
                UiUtils.showToast(this, getString(R.string.reader_translate_disabled_mode))
                return@setOnClickListener
            }
            // 单击 = **只提示，绝不打断**；快速双击 = 取消并回退手动（用户明确要求）
            val now = SystemClock.elapsedRealtime()
            val isDouble = now - lastTranslateClickMs <= DOUBLE_CLICK_MS
            lastTranslateClickMs = if (isDouble) 0L else now

            when (val r = controller.onTranslateButtonClick(isDouble)) {
                is TranslateClick.Hint -> {
                    // ⚠️ 状态浮层受 status_overlay_enabled 开关控制，关掉后 showImmediate 直接 return，
                    // 用户点按钮会**毫无反馈**（像坏了）。所以开关关闭时退回系统 Toast。
                    if (statusOverlayEnabled()) {
                        TranslationStatusOverlay.getInstance(this).showImmediate(r.text, autoDismiss = true)
                    } else {
                        UiUtils.showToast(this, r.text)
                    }
                }
                TranslateClick.CancelledToManual -> {
                    val overlay = TranslationStatusOverlay.getInstance(this)
                    overlay.dismiss()
                    overlay.show(getString(R.string.reader_translate_cancelled))
                    refreshTranslationChrome()
                    refreshProgressTranslation()
                }
                TranslateClick.Busy -> {
                    UiUtils.showToast(this, getString(R.string.reader_translate_busy))
                    refreshTranslationChrome()
                }
                TranslateClick.StartedManual, TranslateClick.Ignored -> Unit
            }
        }
        // 成功页：三态循环（译文/原文/纯原图）。
        // Webtoon 没有单页三态（连续滚动下"当前页"语义不唯一），整屏切换改由阅读模式分段器
        // 上「连续滑动」按钮的两态控制（见 ReaderMenuSheet），本按钮在 Webtoon 下整组隐藏。
        binding.btnToggleTranslate.setOnClickListener {
            controller.cycleVisual(currentPage)
        }
        // 失败页：感叹号 → 小气泡显示失败原因（不弹窗）
        binding.btnFailTranslate.setOnClickListener { showFailBubble() }
        applyPageImageSource()
    }

    /** Webtoon（连续滚动）暂不支持单页翻译（"当前页"语义不唯一）。横竖屏都是单页，翻译不受朝向影响。 */
    private fun isTranslateDisabledByMode(): Boolean = mode == 3

    /** 状态浮层总开关（关闭后所有 Hint 必须改走 Toast，否则用户点按钮毫无反馈）。 */
    private fun statusOverlayEnabled(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("status_overlay_enabled", true)

    /** 注入「页图提供者」：适配器绑定页时优先取译文/原文渲染图（无则原图），
     *  避免 RecyclerView 重绑/复用把已显示的译图覆盖回原图。 */
    private fun applyPageImageSource() {
        val controller = translationController ?: return
        val provider: (Int) -> android.graphics.Bitmap? = { page -> controller.cachedDisplayBitmap(page) }
        pageAdapter?.setPageImageProvider(provider)
    }

    /** 状态浮层（与截屏翻译路线一致）：检测中 / 翻译中 / 完成 / 失败。
     *  成功/失败必须 dismiss 掉常驻的「翻译中」进度芯片，否则一直挂着（还会跨场景残留）。 */
    private fun onTranslatePhase(phase: ReaderTranslatePhase, message: String?) {
        runOnUiThread {
            val overlay = TranslationStatusOverlay.getInstance(this)
            val p = translationController?.queuePage?.value ?: -1
            // 带页码：增量连续翻页时用户要看得出进度走到哪一页
            fun withPage(base: String) =
                if (p >= 0) getString(R.string.reader_translate_status_page, base, p + 1) else base

            when (phase) {
                // message 优先：分批时管线会给出「识别中（1/2）…」这类带批次的文案（与截屏翻译对齐）
                ReaderTranslatePhase.DETECTING -> overlay.showImmediate(
                    withPage(message ?: getString(R.string.reader_translate_detecting)), autoDismiss = false
                )
                ReaderTranslatePhase.TRANSLATING -> overlay.showImmediate(
                    withPage(message ?: getString(R.string.reader_translate_in_progress)), autoDismiss = false
                )
                ReaderTranslatePhase.SUCCESS -> {
                    overlay.dismiss()
                    overlay.show(getString(R.string.reader_translate_done))
                    refreshProgressTranslation()
                }
                ReaderTranslatePhase.FAILED -> {
                    overlay.dismiss()
                    overlay.showError(message ?: getString(R.string.reader_translate_failed))
                    refreshProgressTranslation()
                }
                // 队列跑完：提示一下随即消失，翻页后队列会自动重启
                ReaderTranslatePhase.QUEUE_DRAINED -> {
                    overlay.dismiss()
                    overlay.show(getString(R.string.reader_translate_queue_drained))
                    refreshProgressTranslation()
                }
            }
        }
    }

    /** 翻到失败页时点感叹号：小气泡显示失败原因（不用弹窗）。 */
    private fun showFailBubble() {
        val controller = translationController ?: return
        val reason = controller.failMessageOf(currentPage) ?: getString(R.string.reader_translate_failed)
        TranslationStatusOverlay.getInstance(this)
            .show(getString(R.string.reader_translate_failed_page_hint, currentPage + 1, reason))
    }

    /** 同步翻译浮层组：成功页显示三态按钮、失败页显示感叹号；Webtoon 整组隐藏。 */
    private fun refreshTranslationChrome() {
        // Webtoon（连续滚动）不支持单页翻译，也没有单页三态 → 整个浮层组（翻译/重翻 +
        // 三态 + 失败感叹号）隐藏，只留底部模式分段器上「连续滑动」按钮的两态角标。
        // ⚠️ 必须在 controller 判空之前：否则控制器未就绪时整组会留在屏幕上没人收。
        if (mode == 3) {
            binding.translateGroup.visibility = View.GONE
            return
        }
        binding.translateGroup.visibility = View.VISIBLE

        val controller = translationController ?: return
        val state = controller.stateOf(currentPage)
        val translated = state == ImportedPageTranslation.STATE_SUCCESS
        val failed = state == ImportedPageTranslation.STATE_FAILED

        binding.btnToggleTranslate.visibility = if (translated) View.VISIBLE else View.GONE
        binding.btnFailTranslate.visibility = if (failed) View.VISIBLE else View.GONE
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

    /** 刷新进度条上的「已翻译」绿色区间。 */
    private fun refreshProgressTranslation() {
        binding.readerProgress.setTranslatedPages(translationController?.translatedPages() ?: emptySet())
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
            }
            runOnUiThread {
                pageAdapter?.notifyItemChanged(pageIndex)
            }
        }
    }

    private fun openPagePreview() {
        ReaderPagePreviewDialog(this, source, currentPage) { page ->
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
                    webtoonTranslated = webtoonTranslated,
                    translateMode = translationController?.translateMode?.value ?: 0,
                    debounceMs = translationController?.debounceMs?.value ?: 500,
                    aheadPages = translationController?.aheadPages?.value ?: 5,
                    pageTranslations = translationController?.records() ?: emptyList()
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
                    prefs.edit().putFloat(KEY_BRIGHTNESS, 0f).putFloat(KEY_CONTRAST, 0f)
                        .putBoolean(KEY_INVERT, false).putBoolean(KEY_GRAY, false).putBoolean(KEY_BOOK, false).apply()
                    colorFilter = ReaderColorFilter.EMPTY
                    reloadCurrentPageColor()
                },
                onRotate = { updateRotateMode((rotateMode + 1) % 3, persist = true) },
                onDownload = { showDownloadDialog() },
                onSettings = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION))
                },
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
                },
                onPanelClosed = { translationController?.setPanelOpen(false) },
                onOpenModelManagement = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT))
                },
                onOpenApiConfig = {
                    startActivity(Intent(this@MangaReaderActivity, SettingPageActivity::class.java)
                        .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_API_CONFIG))
                }
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
        // 实时预览当前可见页
        pageAdapter?.applyLiveColor(f)
    }

    private fun reloadCurrentPageColor() {
        pageAdapter?.notifyItemChanged(currentPage)
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
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reader_download, null, false)
        val dialog = AlertDialog.Builder(this).setView(view).setNegativeButton(R.string.cancel, null).create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white)
        if (dark) {
            fun recolor(v: View) {
                if (v is android.widget.TextView) v.setTextColor(0xFFE2E2E4.toInt())
                if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i))
            }
            recolor(view as ViewGroup)
        }

        view.findViewById<View>(R.id.row_download_original).setOnClickListener {
            dialog.dismiss(); exportOriginal()
        }
        view.findViewById<View>(R.id.row_download_translated).setOnClickListener {
            UiUtils.showToast(this, getString(R.string.reader_download_pending))
        }
        view.findViewById<View>(R.id.row_download_both).setOnClickListener {
            UiUtils.showToast(this, getString(R.string.reader_download_pending))
        }
    }

    private fun exportOriginal() {
        UiUtils.showToast(this, getString(R.string.reader_download_started))
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { exportOriginalZip() }
            UiUtils.showToast(this@MangaReaderActivity,
                if (name != null) getString(R.string.reader_download_done, name)
                else getString(R.string.reader_download_failed))
        }
    }

    private fun exportOriginalZip(): String? = try {
        val safe = manga.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val display = "$safe-原文.zip"
        val tmp = File(cacheDir, "manga_export_${System.currentTimeMillis()}.zip")
        if (manga.isArchive) {
            File(manga.localRoot).inputStream().use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
        } else {
            ZipOutputStream(FileOutputStream(tmp)).use { zip ->
                for (i in 0 until source.size) {
                    val key = source.key(i) ?: continue
                    val file = File(manga.localRoot, key); if (!file.isFile) continue
                    zip.putNextEntry(java.util.zip.ZipEntry(key.substringAfterLast('/')))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val ok = writeToDownloads(display, tmp)
        tmp.delete()
        if (ok) display else null
    } catch (e: Exception) {
        null
    }

    private fun writeToDownloads(displayName: String, file: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        return try {
            uri?.let { contentResolver.openOutputStream(it)?.use { o -> file.inputStream().use { s -> s.copyTo(o) } } != null } ?: false
        } catch (e: Exception) {
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