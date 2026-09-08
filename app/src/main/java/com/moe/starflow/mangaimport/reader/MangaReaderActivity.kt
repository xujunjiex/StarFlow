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
import android.view.LayoutInflater
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
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.databinding.ActivityMangaReaderBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.me.settings.SettingPageActivity
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

        setupOverlays()
        applyBackground()
        updateRotateMode(rotateMode, persist = false)
        applyPager()

        goToPage(manga.lastReadPage.coerceIn(0, (source.size - 1).coerceAtLeast(0)))
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
            // Webtoon：竖列表
            binding.viewPager.visibility = View.GONE
            binding.webtoonList.visibility = View.VISIBLE
            binding.webtoonList.layoutManager = LinearLayoutManager(this)
            binding.webtoonList.adapter = WebtoonAdapter(source) { colorFilter }
            return
        }
        binding.webtoonList.adapter = null
        binding.webtoonList.visibility = View.GONE
        binding.viewPager.visibility = View.VISIBLE

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 横屏且非 Webtoon → 双页
        isDoublePage = landscape
        if (isDoublePage) {
            doubleAdapter = DoublePageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap)
            binding.viewPager.adapter = doubleAdapter
        } else {
            pageAdapter = ReaderPageAdapter(source, { colorFilter }, ::onInteraction, ::handleTap)
            binding.viewPager.adapter = pageAdapter
        }
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
        applyDirection()
        applyAnimation()
    }

    /** 翻页动画：0无(直接跳) / 1默认(滑动) / 2高级(景深) / 3仿真(翻页)。 */
    private fun applyAnimation() {
        binding.viewPager.setPageTransformer(
            when (animationMode) {
                2 -> DepthTransformer()
                3 -> PageTurnTransformer()
                else -> null
            }
        )
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
        override fun onPageSelected(position: Int) {
            val page = if (isDoublePage) position * 2 else position
            currentPage = page.coerceIn(0, (source.size - 1).coerceAtLeast(0))
            ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = currentPage))
            refreshOverlay()
        }
    }

    private fun applyDirection() {
        if (mode == 3) return
        val isRtl = mode == 1
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        binding.viewPager.layoutDirection = dir
        (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.layoutDirection = dir
    }

    private fun handleTap(x: Float, y: Float) {
        val w = binding.viewPager.width.toFloat()
        val h = binding.viewPager.height.toFloat()
        if (w <= 0f) return
        if (y < h * 0.22f && x > w * 0.72f) { showMenu(); return }
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

    /** 动画 switch：0=无动画直接跳，其余走默认补间。仿真/高级暂未实现真翻页特效，退化为默认。 */
    private fun animationSupportsAnim(): Boolean = animationMode != 0

    private fun goToPage(page: Int) {
        val p = page.coerceIn(0, (source.size - 1).coerceAtLeast(0))
        if (mode == 3) {
            binding.webtoonList.scrollToPosition(p)
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
        binding.btnTranslate.setOnClickListener {
            UiUtils.showToast(this, getString(R.string.reader_translate_pending))
        }
        binding.btnMenu.setOnClickListener { showMenu() }

        binding.readerProgress.onSeek = { page -> goToPage(page) }
        binding.readerProgress.onLongPress = {
            lastInteractionMs = SystemClock.elapsedRealtime()
            openPagePreview()
        }
    }

    private fun refreshOverlay() {
        binding.tvPageIndicator.text = getString(R.string.reader_page_indicator, currentPage + 1, source.size)
        binding.readerProgress.setPage(currentPage, source.size)
    }

    private fun openPagePreview() {
        ReaderPagePreviewDialog(this, source, currentPage) { page ->
            binding.webtoonList.adapter?.let {
                // webtoon 直接用 scroll
                binding.webtoonList.scrollToPosition(page)
            }
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
                    previewBitmap = previewBmp
                ),
            ReaderMenuCallbacks(
                onMode = { m ->
                    prefs.edit().putInt(KEY_MODE, m).apply()
                    mode = m
                    applyPager()
                    goToPage(currentPage)
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