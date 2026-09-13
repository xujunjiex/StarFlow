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
 * 漫画阅读器（复刻 Kototoro）：4 阅读模式(LTR/RTL/竖排/Webtoon) + 横屏双页 +
 * 4 翻页动画 + 6 阅读背景 + 颜色矫正 + 自动翻页 + 底部四图标工具栏 + 薄进度条(拖拽/长按预览) +
 * 右下角单个翻页按钮 + 右上角菜单键。
 */
class MangaReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MANGA_ID = "manga_id"

        private const val PREFS = "manga_reader"
        private const val KEY_MODE = "reader_mode"          // 0 LTR 1 RTL 2 竖排 3 Webtoon
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
    private var animationMode = 1
    private var bgMode = 0

    private var pageAdapter: ReaderPageAdapter? = null
    private var doubleAdapter: DoublePageAdapter? = null
    private var isDoublePage = false

    /** 仿真/高级动画共享状态（折线触点 + 翻页方向）。 */
    private val animState = ReaderAnimationState()
    private var webtoonTapDetector: GestureDetector? = null
    private var translationController: ReaderTranslationController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersive()

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = prefs.getInt(KEY_MODE, 0)
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

        // 阅读器内嵌翻译：载入每页记录（含失败/成功态），接左下角三态按钮与右下角翻译按钮
        translationController = ReaderTranslationController(this, manga, lifecycleScope)
        lifecycleScope.launch {
            translationController?.load()
            refreshTranslationChrome()
        }
        setupTranslationUi()
    }

    override fun onStart() {
        super.onStart()
        updateAutoTurn()
    }

    override fun onStop() {
        super.onStop()
        autoTurnJob?.cancel()
        autoTurnJob = null
    }

    override fun onResume() {
        super.onResume()
        // 横竖屏切换后保持正确的分页/双页布局
        refreshPagerForOrientation()
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

    // ===== 分页 / 双页 / Webtoon 切换 =====

    private fun applyPager() {
        if (mode == 3) {
            // Webtoon：连续竖滚列表（无翻页动画/自动翻页，滚动即翻页）
            binding.webtoonList.adapter = WebtoonAdapter(source) { colorFilter }
            binding.webtoonList.layoutManager = LinearLayoutManager(this)
            binding.webtoonList.visibility = View.VISIBLE
            binding.viewPager.visibility = View.GONE
            return
        }
        binding.webtoonList.adapter = null
        binding.webtoonList.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE

        // 竖排模式（Koto VERTICAL）= 竖向整页 Pager，逐页上/下切换；其余横向
        binding.viewPager.orientation =
            if (mode == 2) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 横屏双页仅对水平模式（LTR/RTL）生效；竖排保持单页竖向，不参与双页
        isDoublePage = landscape && mode != 2
        if (isDoublePage) {
            doubleAdapter = DoublePageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap)
            binding.viewPager.adapter = doubleAdapter
        } else {
            pageAdapter = ReaderPageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap)
            binding.viewPager.adapter = pageAdapter
        }
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
        if (isDoublePage) {
            // 双页阅读：只用默认滑动（不叠加封面/翻页动画）
            binding.viewPager.setPageTransformer(null)
            return
        }
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
        refreshPagerForOrientation()
    }

    private fun refreshPagerForOrientation() {
        // 复用现有 adapter 或切换双页
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (mode == 3) return
        if (landscape != isDoublePage) applyPager()
    }

    /** 当前「进度条」用的页码：分页=当前页；双页=左页；Webtoon=首个可见页(简化为进度条拖动页)。 */
    private var doublePageIndex = 0

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
            val page = if (isDoublePage) position * 2 else position
            currentPage = page.coerceIn(0, (source.size - 1).coerceAtLeast(0))
            // 无动画模式：选中后锚点同步到新页，避免下次拖拽时还把上一页钉在中心
            if (animationMode == 0) animState.anchorPage = currentPage
            ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = currentPage))
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
        val step = if (isDoublePage) 2 else 1
        val target = (currentPage + delta * step)
        val clamped = target.coerceIn(0, (source.size - 1).coerceAtLeast(0))
        if (isDoublePage) {
            binding.viewPager.setCurrentItem(clamped / 2, animationSupportsAnim())
        } else {
            binding.viewPager.setCurrentItem(clamped, animationSupportsAnim())
        }
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
        if (isDoublePage) binding.viewPager.setCurrentItem(p / 2, false)
        else binding.viewPager.setCurrentItem(p, false)
    }

    // ===== 覆盖层 =====

    private fun setupOverlays() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { turnPage(-1) }
        binding.btnNext.setOnClickListener { turnPage(1) }
        binding.btnMenu.setOnClickListener { showMenu() }

        // 分页进度：只注册一次（applyPager 会重建 adapter，但回调挂在 viewPager 上，无需重复注册）
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
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

    /** Webtoon 模式滚动后：把当前可见页同步到 currentPage / 进度条 / 断点续读。 */
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

    /** 右下角翻译按钮 + 左下角三态按钮接线（逻辑走 ReaderTranslationController）。 */
    private fun setupTranslationUi() {
        val controller = translationController ?: return
        binding.btnTranslate.setOnClickListener {
            when (controller.stateOf(currentPage)) {
                ImportedPageTranslation.STATE_TRANSLATING ->
                    UiUtils.showToast(this, getString(R.string.reader_translate_translating))
                else -> translateNow()   // 未翻译/失败→翻译；已成功→重翻
            }
        }
        binding.btnToggleTranslate.setOnClickListener {
            controller.cycleVisual(currentPage) { applyPageVisual(currentPage) }
        }
        applyPageImageSource()
    }

    /** 注入「页图提供者」：适配器绑定页时优先取译文/原文渲染图（无则原图），
     *  避免 RecyclerView 重绑/复用把已显示的译图覆盖回原图。 */
    private fun applyPageImageSource() {
        val controller = translationController ?: return
        val provider: (Int) -> android.graphics.Bitmap? = { page -> controller.cachedDisplayBitmap(page) }
        pageAdapter?.setPageImageProvider(provider)
        doubleAdapter?.setPageImageProvider(provider)
    }

    /** 状态浮层（与截屏翻译路线一致）：检测中 / 翻译中 / 完成 / 失败。
     *  成功/失败必须 dismiss 掉常驻的「翻译中」进度芯片，否则一直挂着（还会跨场景残留）。 */
    private fun onTranslatePhase(phase: ReaderTranslatePhase, message: String?) {
        runOnUiThread {
            val overlay = TranslationStatusOverlay.getInstance(this)
            when (phase) {
                ReaderTranslatePhase.DETECTING ->
                    overlay.showImmediate(getString(R.string.reader_translate_detecting), autoDismiss = false)
                ReaderTranslatePhase.TRANSLATING ->
                    overlay.showImmediate(getString(R.string.reader_translate_in_progress), autoDismiss = false)
                ReaderTranslatePhase.SUCCESS -> {
                    overlay.dismiss()
                    overlay.show(getString(R.string.reader_translate_done))
                }
                ReaderTranslatePhase.FAILED -> {
                    overlay.dismiss()
                    overlay.showError(message ?: getString(R.string.reader_translate_failed))
                }
            }
        }
    }

    /** 翻译/重翻当前页。 */
    private fun translateNow() = translateNow(currentPage)

    /** 翻译/重翻指定页（IO 线程跑管线）。 */
    private fun translateNow(page: Int) {
        val controller = translationController ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            controller.translatePage(
                page,
                loadFull = { source.loadFull(it) },
                onToast = { msg -> runOnUiThread { UiUtils.showToast(this@MangaReaderActivity, msg) } },
                onVisual = { runOnUiThread { applyPageVisual(page) } },
                onPhase = ::onTranslatePhase,
            )
        }
    }

    /** 同步三态按钮可见性 + 翻译/重翻图标 + 三态图标（照搬截屏翻译：译文/原文/纯原图）。 */
    private fun refreshTranslationChrome() {
        val controller = translationController ?: return
        val translated = controller.stateOf(currentPage) == ImportedPageTranslation.STATE_SUCCESS
        binding.btnToggleTranslate.visibility = if (translated) View.VISIBLE else View.GONE
        // 翻译按钮图标：成功 → 重翻图标；未译/失败 → 翻译图标
        binding.ivTranslate.setImageResource(
            if (translated) R.drawable.ic_refresh else R.drawable.ic_reader_translate
        )
        if (translated) {
            binding.ivToggleTranslate.setImageResource(
                when (controller.currentVisual(currentPage)) {
                    TranslationCacheManager.OverlayMode.TRANSLATED -> android.R.drawable.ic_menu_camera
                    TranslationCacheManager.OverlayMode.ORIGINAL -> android.R.drawable.ic_menu_gallery
                    else -> android.R.drawable.ic_menu_view
                }
            )
        }
    }

    /** 把指定页显示切到 controller 的当前态（译文/原文/原图）。
     *  不直接写 visibleImage（它可能是邻页，写错视图 = 错图）；而是把该页当前态渲染图预热进缓存后
     *  `notifyItemChanged` 重绑，由适配器经「页图提供者」按【槽位】取回正确的图。 */
    private fun applyPageVisual(pageIndex: Int) {
        val controller = translationController ?: return
        refreshTranslationChrome()
        val mode = if (controller.stateOf(pageIndex) == ImportedPageTranslation.STATE_SUCCESS)
            controller.currentVisual(pageIndex) else null
        lifecycleScope.launch(Dispatchers.IO) {
            if (mode != null && mode != TranslationCacheManager.OverlayMode.PLAIN) {
                controller.visualBitmap(pageIndex, mode) { source.loadFull(it) } // 渲染 + 预热缓存
            }
            runOnUiThread {
                pageAdapter?.notifyItemChanged(pageIndex)
                doubleAdapter?.notifyItemChanged(pageIndex / 2)
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
                    translateMode = translationController?.translateMode?.value ?: 0,
                    pageTranslations = translationController?.records() ?: emptyList()
                ),
            ReaderMenuCallbacks(
                onMode = { m ->
                    prefs.edit().putInt(KEY_MODE, m).apply()
                    mode = m
                    applyPager()
                    goToPage(currentPage)
                    // Webtoon ↔ 分页切换后重算自动翻页（webtoon 禁用、分页按开关恢复）
                    updateAutoTurn()
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
                onTranslateMode = { _ -> },   // 阶段一手动模式固定，无需动作
                onTranslatePageJump = { page -> goToPage(page) }
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
        // 进度条轨道/手柄取反色适配背景 + 页面指示文字
        binding.readerProgress.darkBackground = isDarkBackground()
        binding.tvPageIndicator.setTextColor(if (isDarkBackground()) Color.WHITE else Color.BLACK)
    }

    private fun resolveBgColor(): Int = when (bgMode) {
        1 -> Color.rgb(0xF0, 0xF0, 0xEE)          // Light
        2 -> Color.rgb(0x18, 0x18, 0x1C)          // Dark
        3 -> Color.WHITE
        4 -> Color.BLACK
        5 -> {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night) Color.BLACK else Color.WHITE
        }
        else -> if (isSystemDark()) Color.BLACK else Color.WHITE
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
        doubleAdapter?.applyLiveColor(f)
    }

    private fun reloadCurrentPageColor() {
        pageAdapter?.notifyItemChanged(currentPage)
        doubleAdapter?.notifyItemChanged(currentPage / 2)
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
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.dialog_background)
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