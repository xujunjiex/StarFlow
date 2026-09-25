package com.moe.starflow.novel.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.databinding.ActivityNovelReaderBinding
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelTranslationEngine
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.TranslationTextApiAdapter
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import translationapi.TranslatorFactory

/**
 * 小说阅读器。
 *
 * ⚠️ **与 `MangaReaderActivity` 是两套**：那个的核心抽象是「页 = 位图」，围绕它长出了
 * 竖排 / Webtoon / 卷曲动画 / 颜色矫正 / 译图导出 / `ZoomableImageView` 一整串补丁。
 * 文本没有位图，这些一条都用不上，硬塞进去会让两边都难维护。
 *
 * 共用的是：`ReaderProgressBar`、`MangaFontSize` 字号档位、`OcrLock`、`CustomPreference`。
 *
 * ### 两条主线的状态
 * - **阅读位置**：以「章号 + 章内段落号」为准（见 [persistProgress]）
 * - **翻译**：`translations` 是本章译文，到达后重新分页（译文比原文长，必须重排）
 */
class NovelReaderActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NovelReader"
        const val EXTRA_NOVEL_ID = "novel_id"

        fun intent(context: Context, novelId: Long): Intent =
            Intent(context, NovelReaderActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId)
    }

    private lateinit var binding: ActivityNovelReaderBinding
    private lateinit var repository: NovelChapterRepository
    private lateinit var pageAdapter: NovelPageAdapter
    private lateinit var scrollAdapter: NovelScrollAdapter

    private var book: ImportedNovel? = null
    private var chapterIndex = 0
    private var content: ChapterContent? = null
    private var translations: Map<Int, String> = emptyMap()
    private var translationJob: Job? = null

    private var prefs: CustomPreference? = null
    private var chromeVisible = true

    /** 段落号 → 页码的请求：改排版/译文到达后重新分页时用它把位置找回来。 */
    private var pendingParaIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNovelReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getLongExtra(EXTRA_NOVEL_ID, -1L)
        book = NovelStore.load(this).firstOrNull { it.id == id }
        if (book == null) {
            LogCollector.w(TAG, "找不到书籍 id=$id，关闭阅读器")
            finish()
            return
        }

        repository = NovelChapterRepository()
        pageAdapter = NovelPageAdapter()
        scrollAdapter = NovelScrollAdapter()
        binding.novelPager.adapter = pageAdapter
        binding.novelScroll.layoutManager = LinearLayoutManager(this)
        binding.novelScroll.adapter = scrollAdapter

        prefs = CustomPreference.getInstance(this)
        chapterIndex = book!!.lastReadChapter.coerceAtLeast(0)
        pendingParaIndex = book!!.lastReadParaIndex

        setupListeners()
        setupTapZones()
        applyReaderMode()
        loadChapter(chapterIndex, keepPara = pendingParaIndex)

        lifecycleScope.launch {
            // 清理上次异常退出留下的「翻译中」标记（进程被杀时退出清理不会执行）
            book?.let { runCatching { translator().resetStale(it) } }
            refreshTranslations()
        }
    }

    // ===== 依赖 =====

    private fun db() = TranslationHistoryDatabase.getInstance(this)

    private fun translator() = NovelChapterTranslator(
        dao = db().novelParagraphTranslationDao(),
        engine = NovelTranslationEngine(
            TranslationTextApiAdapter(
                TranslatorFactory.createForText(this, prefs ?: CustomPreference.getInstance(this))
                    ?: throw IllegalStateException("no engine")
            )
        ),
        splitVersion = NovelParagraphSplitter.SPLIT_VERSION,
    )

    // ===== 交互 =====

    private fun setupListeners() {
        binding.novelBack.setOnClickListener { finish() }
        binding.novelToc.setOnClickListener { openToc() }
        binding.novelTabStyle.setOnClickListener { showStylePanel() }
        binding.novelTabTranslate.setOnClickListener { showTranslatePanel() }
        binding.novelTabMore.setOnClickListener { showMorePanel() }
        binding.novelPrevChapter.setOnClickListener { gotoChapter(chapterIndex - 1) }
        binding.novelNextChapter.setOnClickListener { gotoChapter(chapterIndex + 1) }

        binding.novelPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPaged(position)
            }
        })
    }

    /** 单击分区：中间格显隐上下栏，左右半屏翻页（与漫画阅读器同一套约定）。 */
    private fun setupTapZones() {
        binding.novelRoot.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener false
            }
            val w = binding.novelRoot.width.toFloat()
            val h = binding.novelRoot.height.toFloat()
            val x = event.x
            val y = event.y
            val inMiddle = x >= w / 3 && x < w * 2 / 3 && y >= h / 3 && y < h * 2 / 3
            when {
                inMiddle -> {
                    toggleChrome()
                    true
                }
                y >= h / 3 && y < h * 2 / 3 && x < w / 3 -> {
                    turnPage(forward = false)
                    true
                }
                y >= h / 3 && y < h * 2 / 3 && x >= w * 2 / 3 -> {
                    turnPage(forward = true)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val vis = if (chromeVisible) View.VISIBLE else View.GONE
        binding.novelTopBar.visibility = vis
        binding.novelBottomBar.visibility = vis
    }

    /** 翻页；越界则切章（本章第一页往前 → 上一章最后一页，反之下一章第一页）。 */
    private fun turnPage(forward: Boolean) {
        val pages = content?.pages ?: return
        if (pages.isEmpty()) return
        if (NovelPanelStyle.readerMode(prefs!!) == NovelPanelStyle.READER_SCROLL) {
            scrollBy(forward)
            return
        }
        val current = binding.novelPager.currentItem
        val target = if (forward) current + 1 else current - 1
        when {
            target in pages.indices -> binding.novelPager.setCurrentItem(target, true)
            forward -> gotoChapter(chapterIndex + 1)
            else -> gotoChapter(chapterIndex - 1, atLastPage = true)
        }
    }

    private fun scrollBy(forward: Boolean) {
        val lm = binding.novelScroll.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val total = scrollAdapter.itemCount
        val target = if (forward) first + 1 else first - 1
        if (target in 0 until total) {
            binding.novelScroll.scrollToPosition(target)
        } else {
            gotoChapter(if (forward) chapterIndex + 1 else chapterIndex - 1)
        }
    }

    private fun gotoChapter(index: Int, atLastPage: Boolean = false) {
        val total = book?.chapterCount ?: 0
        if (index < 0) {
            toast(R.string.novel_first_chapter)
            return
        }
        if (index >= total) {
            toast(R.string.novel_last_chapter)
            return
        }
        persistProgress()
        loadChapter(index, keepPara = if (atLastPage) Int.MAX_VALUE else 0, goToLastPage = atLastPage)
    }

    // ===== 加载 =====

    private fun applyReaderMode() {
        val scroll = NovelPanelStyle.readerMode(prefs!!) == NovelPanelStyle.READER_SCROLL
        binding.novelPager.visibility = if (scroll) View.GONE else View.VISIBLE
        binding.novelScroll.visibility = if (scroll) View.VISIBLE else View.GONE
        binding.novelProgress.visibility = if (scroll) View.GONE else View.VISIBLE
    }

    /**
     * 加载并渲染一章。
     *
     * @param keepPara 要定位到的段落号（[Int.MAX_VALUE] = 本章末尾）
     */
    private fun loadChapter(index: Int, keepPara: Int = 0, goToLastPage: Boolean = false) {
        val b = book ?: return
        lifecycleScope.launch {
            val display = NovelPanelStyle.displayMode(prefs!!)
            val style = NovelPanelStyle.textStyle(this@NovelReaderActivity, prefs!!)
            val w = binding.novelRoot.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = binding.novelRoot.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

            val loaded = repository.load(b, index, translations, display, style, w, h)
            chapterIndex = index
            content = loaded
            showStatus(if (loaded.isEmpty) getString(R.string.novel_empty_chapter) else null)

            if (NovelPanelStyle.readerMode(prefs!!) == NovelPanelStyle.READER_SCROLL) {
                scrollAdapter.submit(loaded, style)
                val pos = if (keepPara == Int.MAX_VALUE) {
                    (scrollAdapter.itemCount - 1).coerceAtLeast(0)
                } else {
                    NovelScrollMapping.positionOf(loaded, keepPara)
                }
                binding.novelScroll.scrollToPosition(pos)
                binding.novelPageLabel.text = ""
            } else {
                pageAdapter.submit(loaded, style)
                val page = if (keepPara == Int.MAX_VALUE) {
                    loaded.pages.lastIndex.coerceAtLeast(0)
                } else {
                    repository.pageOfParagraph(loaded.pages, keepPara)
                }
                binding.novelPager.setCurrentItem(page, false)
                onPaged(page)
            }
            updateTitle()
            persistProgress()
        }
    }

    private fun onPaged(position: Int) {
        val pages = content?.pages ?: return
        binding.novelProgress.setPage(position, pages.size)
        binding.novelPageLabel.text = getString(R.string.novel_page_label, position + 1, pages.size)
        pendingParaIndex = pages.getOrNull(position)?.segments?.firstOrNull()?.paraIndex ?: 0
    }

    /** 译文到达后：重新分页（译文比原文长）+ 保持位置。 */
    private fun refreshTranslations() {
        val b = book ?: return
        lifecycleScope.launch {
            val map = runCatching { translator().loadTranslations(b, chapterIndex) }
                .getOrDefault(emptyMap())
            if (map == translations) return@launch
            translations = map
            if (content == null) loadChapter(chapterIndex, keepPara = pendingParaIndex)
            else loadChapter(chapterIndex, keepPara = pendingParaIndex)
        }
    }

    private fun updateTitle() {
        val b = book ?: return
        val title = content?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.novel_chapter_label, chapterIndex + 1)
        binding.novelTitle.text = "${b.title} · $title"
    }

    private fun showStatus(message: String?) {
        binding.novelStatus.text = message ?: ""
        binding.novelStatus.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ===== 翻译 =====

    private fun translateCurrentChapter() {
        val b = book ?: return
        val loaded = content ?: return
        translationJob?.cancel()
        translationJob = lifecycleScope.launch {
            if (!OcrLock.tryAcquire()) {
                toast(R.string.novel_translate_busy)
                return@launch
            }
            try {
                val engine = runCatching { translator() }.getOrNull()
                if (engine == null) {
                    toast(R.string.novel_translate_need_config)
                    return@launch
                }
                val src = b.format.let { prefs!!.getString("Manga_Source_Lang", "auto") }
                val tgt = prefs!!.getString("Manga_Target_Lang", "zh")
                engine.translateChapter(
                    book = b,
                    chapterIndex = chapterIndex,
                    paragraphs = loaded.paragraphs,
                    sourceLang = src,
                    targetLang = tgt,
                    translatorName = "novel",
                    batchSize = NovelPanelStyle.batchSize(prefs!!),
                ).collect { refreshTranslations() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "本章翻译失败", e)
            } finally {
                OcrLock.release()
            }
        }
    }

    // ===== 面板 =====

    private fun openToc() {
        val b = book ?: return
        lifecycleScope.launch {
            val stats = runCatching { translator().chapterStats(b) }.getOrDefault(emptyMap())
            val chapters = repository.chaptersOf(b)
            NovelTocDialog.show(this@NovelReaderActivity, chapters, stats, chapterIndex) { picked ->
                gotoChapter(picked)
            }
        }
    }

    private fun showStylePanel() {
        NovelPanelSheet.showStyle(this, prefs!!) {
            loadChapter(chapterIndex, keepPara = pendingParaIndex)
        }
    }

    private fun showTranslatePanel() {
        NovelPanelSheet.showTranslate(
            context = this,
            prefs = prefs!!,
            onTranslateNow = { translateCurrentChapter() },
            onClearBook = {
                val b = book ?: return@showTranslate
                lifecycleScope.launch {
                    runCatching { translator().clearBook(b) }
                    translations = emptyMap()
                    loadChapter(chapterIndex, keepPara = pendingParaIndex)
                    toast(R.string.novel_translate_cleared)
                }
            },
            onChanged = { loadChapter(chapterIndex, keepPara = pendingParaIndex) },
        )
    }

    private fun showMorePanel() {
        NovelPanelSheet.showMore(this, prefs!!) {
            applyReaderMode()
            loadChapter(chapterIndex, keepPara = pendingParaIndex)
        }
    }

    // ===== 进度 =====

    /**
     * 保存阅读位置。
     *
     * ⚠️ 权威锚点是**章内段落号**而不是页号：页号随字号、行距、甚至「译文到达后的重新分页」
     * 而变；段落号是内容本身的属性，永远指向同一处。
     */
    private fun persistProgress() {
        val b = book ?: return
        val page = if (NovelPanelStyle.readerMode(prefs!!) == NovelPanelStyle.READER_SCROLL) {
            (binding.novelScroll.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        } else {
            binding.novelPager.currentItem
        }
        val para = content?.pages?.getOrNull(page)?.segments?.firstOrNull()?.paraIndex ?: pendingParaIndex
        val updated = b.copy(lastReadChapter = chapterIndex, lastReadParaIndex = para, lastReadPage = page)
        book = updated
        NovelStore.update(this, updated)
    }

    override fun onPause() {
        super.onPause()
        persistProgress()
    }

    override fun onDestroy() {
        translationJob?.cancel()
        if (::repository.isInitialized) repository.evictAll()
        super.onDestroy()
    }

    private fun toast(resId: Int) {
        UiUtils.showToast(this, getString(resId), true)
    }
}
