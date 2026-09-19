package com.moe.starflow.ui.viewer
import com.moe.starflow.ui.history.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.data.CacheEntry
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.PageCacheEntity
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.databinding.ActivityMangaViewerBinding
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.engine.ComicBubbleDetector
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.engine.MangaOcrModelFiles
import com.moe.starflow.manga.engine.MangaOcrRecognizer
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.manga.render.OverlayRenderer
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.manga.TranslateUtils
import com.moe.starflow.translate.screenshot.ScreenshotManager
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.BitmapLruCache
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.LogCollector

import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.me.apiconfig.BuiltinProviders
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaViewerActivity"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_IDS = "entry_ids"  // 同 pHash 多尺寸条目
        const val EXTRA_IS_MANAGE_VIEW = "is_manage_view"

        /**
         * renderCache 上限（单位：渲染后的 bitmap 张数，key=historyId_mode_crop）。
         * crop 图典型尺寸 ~1200×1800 ARGB_8888 ≈ 8MB，3 mode/entry ≈ 25MB，
         * 20 条 ≈ 170MB 名义上限。运行期不主动 recycle（靠 GC），onDestroy 时 clear() 全回收。
         * BitmapLruCache 基于 LinkedHashMap access-order。
         */
        const val MAX_RENDER_CACHE_ENTRIES = 20
    }

    private lateinit var binding: ActivityMangaViewerBinding
    private lateinit var cacheManager: TranslationCacheManager

    // 每个 pHash 组：代表条目 + 所有尺寸变体
    data class PageGroup(
        var representative: HistoryEntry,
        val variants: MutableList<HistoryEntry> = mutableListOf(representative)
    ) {
        /** 当前活跃的 entry.id（默认代表条目） */
        fun activeEntryId(activeVariantId: Long?): Long =
            activeVariantId ?: representative.id
    }
    private val pageGroups = mutableListOf<PageGroup>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var isPanelExpanded = false
    private var groupEntryIds: List<Long> = emptyList()
    private var overlayState = TranslationCacheManager.OverlayMode.TRANSLATED  // 默认译文
    private val renderCache = BitmapLruCache(maxSize = MAX_RENDER_CACHE_ENTRIES)  // key="historyId_mode"
    private var pageCacheMap: Map<Long, PageCacheEntity> = emptyMap()
    private var savedClickedEntryId: Long = -1L
    private var savedEntryIds: LongArray? = null
    private var isManageView = false
    private var currentPagePosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // UI 同步字体（开关开启时）：挂 Factory2，inflate 即应用。
        // ⚠️ 必须在 super.onCreate 前挂——AppCompat 在 super.onCreate 里已 setFactory2，
        // 之后再挂会抛 IllegalStateException 被静默吞掉 → 字体失效（旧实现踩过）。
        com.moe.starflow.utils.FontSync.install(this)
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMangaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cacheManager = TranslationCacheManager(this)

        val clickedEntryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val entryIds = intent.getLongArrayExtra(EXTRA_ENTRY_IDS)
        savedClickedEntryId = clickedEntryId
        savedEntryIds = entryIds
        isManageView = intent.getBooleanExtra(EXTRA_IS_MANAGE_VIEW, false)

        setupViews()
        loadData(clickedEntryId, entryIds)
    }

    private fun setupViews() {
        // 关闭按钮
        binding.btnClose.setOnClickListener { finish() }

        // 旋转视图按钮：仅 view 层旋转 90° + 自适应 fitCenter 铺满整个屏幕，
        // 不修改 cache bitmap。消除旋转后黑边裁切，横屏截图旋转后占满屏幕。
        binding.btnRotate.setOnClickListener {
            val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return@setOnClickListener
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i) ?: continue
                val vh = rv.getChildViewHolder(child) as? PageGroupAdapter.ViewHolder ?: continue
                vh.imageView.rotateAndFit90()
            }
        }

        // 查看译文按钮
        binding.btnShowTranslation.setOnClickListener {
            togglePanel()
        }

        // 关闭底部面板
        binding.btnCloseSheet.setOnClickListener {
            collapsePanel()
        }

        // 删除当前条目（顶部按钮）
        binding.btnDeleteEntry.setOnClickListener {
            confirmDeleteCurrentEntry()
        }

        // 三态循环切换：译文 → 原文 → 纯原图
        binding.btnToggleImage.setOnClickListener {
            overlayState = when (overlayState) {
                TranslationCacheManager.OverlayMode.TRANSLATED -> TranslationCacheManager.OverlayMode.ORIGINAL
                TranslationCacheManager.OverlayMode.ORIGINAL -> TranslationCacheManager.OverlayMode.PLAIN
                TranslationCacheManager.OverlayMode.PLAIN -> TranslationCacheManager.OverlayMode.TRANSLATED
            }
            binding.btnToggleImage.setImageResource(when (overlayState) {
                TranslationCacheManager.OverlayMode.TRANSLATED -> android.R.drawable.ic_menu_camera
                TranslationCacheManager.OverlayMode.ORIGINAL -> android.R.drawable.ic_menu_gallery
                TranslationCacheManager.OverlayMode.PLAIN -> android.R.drawable.ic_menu_view
            })
            // BitmapLruCache 自动淘汰冷数据，三种 mode 的 bitmap 均独立缓存。
            // 切三态时重置旋转方向为正常（未旋转），配合新 mode 重新渲染。
            val pos = binding.viewPager.currentItem
            (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(pos)
                ?.let { (it as? PageGroupAdapter.ViewHolder)?.imageView?.resetRotation() }
            val adapter = binding.viewPager.adapter as? PageGroupAdapter
            adapter?.notifyItemChanged(pos)
        }

        // Variant spinner
        binding.spinnerVariant.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val group = pageGroups.getOrNull(binding.viewPager.currentItem) ?: return
                val variant = group.variants.getOrNull(position) ?: return
                val pagePosition = binding.viewPager.currentItem
                activeVariantIds[pagePosition] = variant.id
                // 切换尺寸时重置 overlay 状态为译文
                overlayState = TranslationCacheManager.OverlayMode.TRANSLATED
                binding.btnToggleImage.setImageResource(android.R.drawable.ic_menu_camera)
                // 关键顺序：先清空当前 page 的 ImageView 旧 bitmap 引用，再 recycle，最后 rebind。
                // 否则 recycleSafeRenderCache 回收旧 variant bitmap 后，ViewHolder.ImageView
                // 仍持有该引用，next draw cycle 抛 "Canvas: trying to use a recycled bitmap"。
                clearImageViewRefForPage(pagePosition)
                val adapter = binding.viewPager.adapter as? PageGroupAdapter
                adapter?.setActiveVariant(pagePosition, variant.id)
                // 排除当前 page 的 entry（切回去时能命中 cache），
                // 同时排除相邻 page entry（ViewPager2 默认 offscreenPageLimit > 0，左右相邻 page 仍在 attach + draw）
                recycleSafeRenderCache(currentEntryIds = collectLiveEntryIds(pagePosition))
                adapter?.notifyItemChanged(pagePosition)
                if (isPanelExpanded) expandPanel()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 重新翻译按钮 — 按当前条目原有的框选区域重新 OCR+翻译+渲染，
        // 原地替换该条目记录，结果在当前详细页实时刷新。
        binding.btnRetranslate.setOnClickListener {
            val entry = getCurrentVariant()
            val originalPath = entry.originalImagePath
            if (originalPath.isNullOrEmpty() || !java.io.File(originalPath).exists()) {
                com.moe.starflow.utils.UiUtils.showToast(this, getString(R.string.toast_original_unavailable))
                return@setOnClickListener
            }
            val pageCache = pageCacheMap[entry.id]
            if (pageCache == null ||
                pageCache.cropRight <= pageCache.cropLeft || pageCache.cropBottom <= pageCache.cropTop) {
                com.moe.starflow.utils.UiUtils.showToast(this, getString(R.string.toast_crop_unavailable))
                return@setOnClickListener
            }
            if (!com.moe.starflow.manga.OcrLock.tryAcquire()) {
                com.moe.starflow.utils.UiUtils.showToast(this, getString(R.string.toast_translating_wait))
                return@setOnClickListener
            }
            performRetranslate(entry, pageCache, originalPath)
        }

        // 非管理视图隐藏重新翻译按钮
        if (!isManageView) {
            binding.btnRetranslate.visibility = View.GONE
        }

        // ViewPager 翻页监听
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                // 翻页时重置为译文 overlay
                overlayState = TranslationCacheManager.OverlayMode.TRANSLATED
                binding.btnToggleImage.setImageResource(android.R.drawable.ic_menu_camera)
                // 翻页后当前 page 改变：保留当前页 + 左右相邻页 bitmap（它们仍被 RecyclerView 持有且正在 draw）
                recycleSafeRenderCache(currentEntryIds = collectLiveEntryIds(position))
                val adapter = binding.viewPager.adapter as? PageGroupAdapter
                // 通知旧页面和新页面重新渲染
                if (currentPagePosition != position) {
                    adapter?.notifyItemChanged(currentPagePosition)
                }
                currentPagePosition = position
                // 翻页时关闭面板
                if (isPanelExpanded) {
                    collapsePanel()
                }
                updateVariantSpinner(position)
            }
        })

        // 初始隐藏底部面板
        binding.bottomSheetPanel.post {
            binding.bottomSheetPanel.translationY = binding.bottomSheetPanel.height.toFloat()
        }
    }

    private fun loadData(clickedEntryId: Long, entryIds: LongArray? = null) {
        lifecycleScope.launch {
            try {
                // 加载所有漫画历史
                val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)

                if (allEntries.isEmpty()) {
                    com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.no_translation_data))
                    finish()
                    return@launch
                }

                // 保存分组 ID（来自历史列表点击）
                groupEntryIds = entryIds?.toList() ?: emptyList()

                // 按 pHash 分组（相似度 ≥ 0.85 视为同一页）
                pageGroups.clear()
                pageGroups.addAll(buildPageGroups(allEntries))

                // 预加载所有 entry 的 PageCacheEntity（用于渲染时获取 crop 坐标）
                val allIds = allEntries.map { it.id }
                val pageCaches = cacheManager.getPageCachesByHistoryIds(allIds)
                val pageCacheMap = pageCaches.associateBy { it.historyId }
                this@MangaViewerActivity.pageCacheMap = pageCacheMap

                // 设置 ViewPager（每页 = 一个 pHash 组）
                val adapter = PageGroupAdapter(
                    pageGroups = pageGroups,
                    pageCacheMap = pageCacheMap,
                    renderCache = renderCache,
                    cacheManager = cacheManager,
                    getOverlayState = { overlayState },
                    lifecycleScope = lifecycleScope,
                    prefs = CustomPreference.getInstance(this@MangaViewerActivity).getSharedPreferences()
                )
                binding.viewPager.adapter = adapter
                // ViewPager2 内部的 RecyclerView 默认 clipChildren=true，会裁掉旋转后超出 page bounds 的绘制。
                // 旋转 ImageView 时图片绘制可能溢出 page 边界，必须让 RecyclerView 及其父层不裁剪，
                // 否则旋转后上下/左右被裁出黑边。
                (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.let { rv ->
                    rv.clipChildren = false
                    rv.setClipToOutline(false)
                }

                // 跳转到点击的组
                val clickedGroupId = pageGroups.indexOfFirst {
                    it.variants.any { v -> v.id == clickedEntryId }
                }
                val safeIndex = if (clickedGroupId >= 0) clickedGroupId else 0

                // 设置初始活跃变体（点击的条目或代表条目）
                val clickedGroup = pageGroups.getOrNull(safeIndex)
                if (clickedGroup != null) {
                    val initialVariant = if (clickedEntryId > 0 && clickedGroup.variants.any { it.id == clickedEntryId }) {
                        clickedEntryId
                    } else {
                        clickedGroup.representative.id
                    }
                    activeVariantIds[safeIndex] = initialVariant
                }

                binding.viewPager.setCurrentItem(safeIndex, false)
                updatePageIndicator(safeIndex)
                updateVariantSpinner(safeIndex)
                updateVariantSpinner(safeIndex)

                LogCollector.d(TAG, "加载漫画历史, ${pageGroups.size} 页, 跳转到 #$safeIndex")
            } catch (e: Exception) {
                LogCollector.e(TAG, "加载数据失败", e)
                com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.no_translation_data))
                finish()
            }
        }
    }

    /**
     * 按 256-bit 扩展哈希相似度分组，与 TranslationCacheManager.groupMangaEntriesByPHash 一致。
     */
    private fun buildPageGroups(allEntries: List<HistoryEntry>): List<PageGroup> {
        val idToEntry = allEntries.associateBy { it.id }
        val grouped = cacheManager.groupMangaEntriesByPHash(allEntries)
        return grouped.map { rep ->
            val variantEntries = rep.variantIds.mapNotNull { idToEntry[it] }
            PageGroup(
                representative = rep,
                variants = variantEntries.toMutableList()
            )
        }
    }

    /**
     * 更新 variant spinner（尺寸选择器）。
     */
    private fun updateVariantSpinner(position: Int) {
        val group = pageGroups.getOrNull(position)
        if (group == null || group.variants.size <= 1) {
            binding.spinnerVariant.visibility = android.view.View.GONE
            return
        }
        binding.spinnerVariant.visibility = android.view.View.VISIBLE
        val items = group.variants.map { v ->
            // 显示用户实际框选的尺寸（crop 区域），而不是原图尺寸
            val pc = pageCacheMap[v.id]
            if (pc != null) {
                val cropW = (pc.cropRight - pc.cropLeft).coerceAtLeast(0)
                val cropH = (pc.cropBottom - pc.cropTop).coerceAtLeast(0)
                if (cropW > 0 && cropH > 0) "${cropW}×${cropH}" else "?"
            } else {
                // fallback：读文件头
                val path = v.originalImagePath ?: v.imagePath ?: v.thumbnailPath
                path?.let { getImageDimensions(it) } ?: "?"
            }
        }
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item_dark, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        binding.spinnerVariant.adapter = adapter
        val activeId = activeVariantIds[position] ?: group.representative.id
        val idx = group.variants.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        binding.spinnerVariant.setSelection(idx, false)
    }

    /**
     * 根据当前偏好设置创建翻译器实例。
     * 复用 TranslatorFactory.create(Mode.MANGA)（漫画 prompt + 续写格式逐行一致），避免与工厂逻辑漂移。
     * 顺带修复：原实现 CUSTOM_TEXT 用旧格式存储（loadTextConfig/Custom_Text_API_N），工厂用新格式（Custom_Text_APIs），
     * 新格式用户在该页自定义翻译引擎会失效。
     */
    private fun createTranslator(prefs: CustomPreference): TranslationTextAPI? =
        translationapi.TranslatorFactory.create(this, prefs, translationapi.TranslatorFactory.Mode.MANGA)

    /**
     * 将引擎名称字符串映射为 DetEngine 和 OcrEngine 枚举。
     */
    private fun mapEngineToDetOcr(engineName: String): Pair<DetEngine, OcrEngine> {
        return when (engineName) {
            "PP_OCR_V5" -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
            "MANGA_OCR" -> DetEngine.RT_DETR_V2 to OcrEngine.MangaOcr  // 与 MangaFloatingService 一致
            "PP_OCR_V6" -> DetEngine.PP_OCR_V6 to OcrEngine.PPOcrV6
            "MLKIT" -> DetEngine.MLKIT to OcrEngine.MLKit
            else -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
        }
    }

    /**
     * 初始化检测和识别所需的引擎。
     */
    private suspend fun initializeEngines(det: DetEngine, ocr: OcrEngine) {
        // Det 引擎
        when (det) {
            DetEngine.PP_OCR_V5 -> PPOcrV5Engine.initialize(this@MangaViewerActivity)
            DetEngine.PP_OCR_V6 -> PPOcrV6Engine.initialize(this@MangaViewerActivity)
            DetEngine.RT_DETR_V2 -> ComicBubbleDetector.initialize(this@MangaViewerActivity)
            DetEngine.MLKIT -> {} // 无需初始化
        }
        // Ocr 引擎
        when (ocr) {
            OcrEngine.PPOcrV5 -> PPOcrV5Engine.initialize(this@MangaViewerActivity)
            OcrEngine.PPOcrV6 -> PPOcrV6Engine.initialize(this@MangaViewerActivity)
            OcrEngine.MangaOcr -> {
                if (MangaOcrModelFiles.isModelDownloaded(this@MangaViewerActivity)) {
                    MangaOcrRecognizer.initialize(this@MangaViewerActivity, useAssets = false)
                } else {
                    throw IllegalStateException("manga-ocr 模型未下载，请先在模型管理页面下载")
                }
            }
            OcrEngine.MLKit -> {} // 无需初始化
        }
    }

    private fun buildRetranslateName(translator: TranslationTextAPI, det: DetEngine, ocr: OcrEngine, prefs: CustomPreference): String {
        val model = translator.modelName
        val apiStr = if (model.isNotEmpty()) model else translator.javaClass.simpleName
        val detStr = when (det) {
            DetEngine.MLKIT -> "MLKit"
            DetEngine.RT_DETR_V2 -> "RT-DETR"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
            DetEngine.PP_OCR_V6 -> "PP-OCRv6"
        }
        val ocrStr = when (ocr) {
            OcrEngine.MLKit -> "MLKit"
            OcrEngine.MangaOcr -> "manga-ocr"
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
            OcrEngine.PPOcrV6 -> "PP-OCRv6"
        }
        val parts = mutableListOf(apiStr, "$detStr+$ocrStr")
        if (det == DetEngine.PP_OCR_V5 || ocr == OcrEngine.PPOcrV5) {
            val box = prefs.getFloat("ppocr_det_box_thresh", 0.3f)
            val unclip = prefs.getFloat("ppocr_det_unclip_ratio", 1.6f)
            val score = prefs.getFloat("ppocr_text_score_thresh", 0.5f)
            parts.add("box=%.2f unclip=%.1f score=%.2f".format(box, unclip, score))
        }
        return parts.joinToString(" | ")
    }

    private fun getImageDimensions(path: String?): String {
        if (path == null) return "?"
        return try {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, options)
            "${options.outWidth}×${options.outHeight}"
        } catch (e: Exception) { "?" }
    }

    private fun updatePageIndicator(position: Int) {
        binding.tvPageIndicator.text = "${position + 1}/${pageGroups.size}"
    }

    /**
     * 获取当前页组的活跃变体（用户选中的尺寸，或默认代表条目）。
     */
    private fun getCurrentVariant(): HistoryEntry {
        val position = binding.viewPager.currentItem
        val group = pageGroups.getOrNull(position) ?: return pageGroups.first().representative
        // 如果有活跃变体 ID，用它；否则用代表条目
        val activeId = activeVariantIds[position]
        return if (activeId != null) {
            group.variants.find { it.id == activeId } ?: group.representative
        } else {
            group.representative
        }
    }

    // 每页当前活跃的变体 ID（尺寸按钮选中的）
    private val activeVariantIds = mutableMapOf<Int, Long>()

    private fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        val position = binding.viewPager.currentItem
        if (position < 0 || position >= pageGroups.size) {
            LogCollector.w(TAG, "expandPanel: invalid position=$position, size=${pageGroups.size}")
            return
        }

        val entry = getCurrentVariant()
        LogCollector.d(TAG, "expandPanel: entryId=${entry.id}, sourceText=${entry.sourceText?.take(30)}, translatedText=${entry.translatedText?.take(30)}")

        // 翻译信息栏：完整参数、尺寸、语言、时间
        val dimStr = getImageDimensions(entry.imagePath ?: entry.thumbnailPath)
        val timeStr = dateFormat.format(Date(entry.updatedAt))

        // 完整参数信息（translatorName 包含所有参数）
        val fullInfo = entry.translatorName
        val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val createdStr = fullDateFormat.format(Date(entry.createdAt))
        val updatedStr = fullDateFormat.format(Date(entry.updatedAt))
        // 64-bit 跟历史列表一致（pHash 段），高位不被截断
        val phashStr = if (entry.pHash != 0L) "pHash:${String.format("%016X", entry.pHash)}" else ""
        binding.tvTranslationInfo.text = "$fullInfo\n" +
            getString(R.string.manga_viewer_size_line, dimStr, entry.sourceLang, entry.targetLang, timeStr) +
            "\n" + getString(R.string.history_created_format, createdStr) +
            "\n" + getString(R.string.history_updated_format, updatedStr) +
            "\n$phashStr"

        val detailList = buildDetailList(entry)
        LogCollector.d(TAG, "expandPanel: detailList size=${detailList.size}")

        // 构建单个可选中文本块（ScrollView + TextView，支持跨条目选择）
        val text = android.text.SpannableStringBuilder()
        for (item in detailList) {
            val start = text.length
            if (start > 0) text.append("\n")
            // 原文（淡色，小号）
            if (item.ocrText.isNotEmpty() && item.ocrText != "-") {
                val s = text.length
                text.append(item.ocrText)
                text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.argb(153, 255, 255, 255)), s, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.AbsoluteSizeSpan(11, true), s, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append("\n")
            }

            // 译文（白色，正文大小）
            if (item.translatedText.isNotEmpty()) {
                val s = text.length
                text.append(item.translatedText)
                text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), s, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.AbsoluteSizeSpan(12, true), s, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.tvTranslationDetail.text = text

        // 等布局完成后获取实际高度
        binding.bottomSheetPanel.post {
            val panelHeight = binding.bottomSheetPanel.height.toFloat()
            binding.bottomSheetPanel.translationY = panelHeight
            binding.bottomSheetPanel.animate()
                .translationY(0f)
                .setDuration(250)
                .start()
        }

        isPanelExpanded = true
    }

    private fun collapsePanel() {
        val panelHeight = binding.bottomSheetPanel.height.toFloat()
        binding.bottomSheetPanel.animate()
            .translationY(panelHeight)
            .setDuration(250)
            .start()

        isPanelExpanded = false
    }

    private fun showDarkDialog(
        message: String,
        title: String? = null,
        positiveText: String = "确定",
        negativeText: String? = null,
        onPositive: () -> Unit = {},
        onNegative: (() -> Unit)? = null
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_dark, null)
        val tvTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.dialogMessage)
        val btnPositive = view.findViewById<TextView>(R.id.dialogBtnPositive)
        val btnNegative = view.findViewById<TextView>(R.id.dialogBtnNegative)

        tvMessage.text = message
        btnPositive.text = positiveText

        if (title != null) {
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = title
        }
        if (negativeText != null) {
            btnNegative.visibility = View.VISIBLE
            btnNegative.text = negativeText
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener { onPositive(); dialog.dismiss() }
        if (negativeText != null) {
            btnNegative.setOnClickListener { onNegative?.invoke(); dialog.dismiss() }
        }

        dialog.show()
    }

    private fun confirmDeleteCurrentEntry() {
        val entry = getCurrentVariant()
        showDarkDialog(
            message = getString(R.string.delete_history_confirm),
            positiveText = getString(R.string.delete),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistory(entry.id)
                        // 从当前组中移除该变体
                        val position = binding.viewPager.currentItem
                        val group = pageGroups.getOrNull(position)
                        if (group != null) {
                            group.variants.removeIf { it.id == entry.id }
                            if (group.variants.isEmpty()) {
                                // 组已空，移除整页
                                pageGroups.removeAt(position)
                                if (pageGroups.isEmpty()) {
                                    com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.history_deleted))
                                    finish()
                                    return@launch
                                }
                                binding.viewPager.adapter?.notifyItemRemoved(position)
                                updatePageIndicator(binding.viewPager.currentItem.coerceAtMost(pageGroups.size - 1))
                            } else {
                                // 还有其他变体，切换到第一个
                                group.representative = group.variants.first()
                                activeVariantIds[position] = group.representative.id
                                binding.viewPager.adapter?.notifyItemChanged(position)
                                updateVariantSpinner(position)
                            }
                        }
                        com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.history_deleted))
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "删除失败", e)
                    }
                }
            }
        )
    }

    private fun buildDetailList(entry: HistoryEntry): List<TranslationDetailItem> {
        val items = mutableListOf<TranslationDetailItem>()

        val ocrText = entry.sourceText
        val aiText = entry.translatedText

        when {
            !ocrText.isNullOrEmpty() && !aiText.isNullOrEmpty() -> {
                // 解析编号格式的译文
                val ocrLines = parseNumberedText(ocrText)
                val aiLines = parseNumberedText(aiText)

                val maxCount = maxOf(ocrLines.size, aiLines.size)
                for (i in 0 until maxCount) {
                    items.add(
                        TranslationDetailItem(
                            index = i + 1,
                            ocrText = ocrLines.getOrNull(i) ?: "",
                            translatedText = aiLines.getOrNull(i) ?: ""
                        )
                    )
                }
            }
            !aiText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = "", translatedText = aiText))
            }
            !ocrText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = ocrText, translatedText = ""))
            }
            else -> {
                items.add(TranslationDetailItem(index = 1, ocrText = getString(R.string.no_translation_data), translatedText = ""))
            }
        }

        return items
    }

    private fun parseNumberedText(text: String): List<String> {
        // 解析 [1] text 格式
        val regex = Regex("""\[(\d+)]\s*""")
        val parts = regex.split(text).filter { it.isNotBlank() }
        return parts.map { it.trim() }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BitmapLruCache.clear() 内部 recycle 所有 bitmap，Activity 销毁时 ViewHolder 已释放 → 安全
        renderCache.clear()
        binding.viewPager.adapter = null
    }

    /**
     * 系统内存压力回调。低内存时主动清理非可见页面的 renderCache。
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val currentIds = collectLiveEntryIds(binding.viewPager.currentItem)
            clearAttachedImageViewRefsNotIn(currentIds)
            renderCache.retainEntries(currentIds)
            LogCollector.d(TAG, "onTrimMemory level=$level, retained=${currentIds.size}, cacheSize=${renderCache.size}")
        }
    }

    /**
     * 清理孤儿 renderCache 条目：保留与 [currentEntryIds] 匹配的条目，其余 recycle。
     *
     * 调用时机：
     * - 翻页：保留当前页 ± 2 的活跃条目，recycle 远离页面的 bitmap
     * - 切换尺寸：保留新 variant 的条目，recycle 旧 variant 的 bitmap
     * - 重新翻译后：保留当前可见页的条目，recycle 旧 entryId 的 bitmap
     */
    /**
     * 按当前条目原有的框选区域重新 OCR+翻译+渲染，原地替换该记录，
     * 结果在当前详细页实时刷新（overlay 图 + 底部详情面板）。
     */
    private fun performRetranslate(
        entry: HistoryEntry,
        pageCache: PageCacheEntity,
        originalPath: String
    ) {
        binding.btnRetranslate.isEnabled = false
        com.moe.starflow.utils.UiUtils.showToast(this, getString(R.string.toast_translating_now))
        lifecycleScope.launch {
            var originalBmp: Bitmap?
            try {
                val cropLeftPx = pageCache.cropLeft
                val cropTopPx = pageCache.cropTop
                val cropRightPx = pageCache.cropRight
                val cropBottomPx = pageCache.cropBottom
                LogCollector.d(TAG, "performRetranslate: entryId=${entry.id}, crop=$cropLeftPx,$cropTopPx-$cropRightPx,$cropBottomPx")

                withContext(Dispatchers.IO) {
                    originalBmp = BitmapFactory.decodeFile(originalPath)
                        ?: throw Exception("原图加载失败")
                    val cropRect = android.graphics.RectF(
                        cropLeftPx.toFloat(), cropTopPx.toFloat(),
                        cropRightPx.toFloat(), cropBottomPx.toFloat()
                    )
                    val cropped = ScreenshotManager.cropBitmap(originalBmp!!, cropRect, android.graphics.Point(0, 0))
                        ?: throw Exception("裁剪原图失败：crop=$cropLeftPx,$cropTopPx-$cropRightPx,$cropBottomPx " +
                            "原图=${originalBmp!!.width}x${originalBmp!!.height}")
                    try {
                        val prefs = CustomPreference.getInstance(this@MangaViewerActivity)
                        val engineName = prefs.getString("history_retranslate_engine", "PP_OCR_V5")
                        val (detEngine, ocrEngine) = mapEngineToDetOcr(engineName)
                        val sourceLang = prefs.getString("Source_Language", "ja")
                        val targetLang = prefs.getString("Target_Language", "zh")

                        initializeEngines(detEngine, ocrEngine)

                        val ocrResults = DetectionBridge.runOCR(cropped, sourceLang, detEngine.value, ocrEngine.value, this@MangaViewerActivity)
                        if (ocrResults.isEmpty()) throw Exception("OCR 未识别到文字")
                        // 重翻遵循用户配置的竖排方向
                        val textDirection = VerticalFlow.fromPref(prefs.getString("Manga_Text_Direction", "0")).toTextDirection()
                        val bubbles = DetectionBridge.ocrToBubbleRegions(ocrResults, textDirection)
                        if (bubbles.isEmpty()) throw Exception("无有效文字区域")
                        val translator = createTranslator(prefs) ?: throw Exception("翻译器创建失败")
                        try {
                            val translatedBubbles = com.moe.starflow.manga.TranslateUtils.translateBubbles(
                                translator, bubbles, sourceLang, targetLang, prefs)
                            if (translatedBubbles.isEmpty()) throw Exception("翻译失败")
                            val ocrTexts = bubbles.map { it.texts.first() }
                            val numberedText = ocrTexts.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("\n")
                            val transText = translatedBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
                            val translatorName = buildRetranslateName(translator, detEngine, ocrEngine, prefs)

                            // 原地更新：保留 historyId，crop 不变，仅替换原文/译文/bubbleRects/translatorName
                            val ok = cacheManager.refreshCacheInPlace(
                                historyId = entry.id,
                                newSourceText = numberedText,
                                newTranslatedText = transText,
                                newBubbleRects = TranslationCacheUtils.serializeBubbleRects(translatedBubbles),
                                newCropLeft = cropLeftPx,
                                newCropTop = cropTopPx,
                                newCropRight = cropRightPx,
                                newCropBottom = cropBottomPx,
                                newTranslatorName = translatorName
                            )
                            if (!ok) throw Exception("原地更新失败：historyId=${entry.id} 不存在或 cache 缺失")
                        } finally {
                            // 每次重译新建的本地模型实例（Hy-MT2 440MB / NLLB）用完即释放，否则泄漏常驻内存
                            translator.release()
                        }
                    } finally {
                        cropped.recycle()
                    }
                }

                // 拉取最新 HistoryEntry + PageCacheEntity，覆盖内存中的快照
                val refreshedEntry = cacheManager.getHistoryById(entry.id)
                val refreshedCache = cacheManager.getCacheByHistoryId(entry.id)
                if (refreshedEntry != null && refreshedCache != null) {
                    pageCacheMap = pageCacheMap.toMutableMap().also { it[entry.id] = refreshedCache }
                    updateInMemoryEntry(refreshedEntry)
                }

                val pos = binding.viewPager.currentItem
                // 只清空即将被回收的 entry（当前页三 mode）对应的 ViewHolder 引用，
                // 相邻页 holder 引用保留避免黑屏；随后 notifyItemChanged 触发重渲染当前页。
                clearAttachedImageViewRefsForEntry(entry.id)
                // 清掉该 entry 三 mode 的缓存 bitmap（让 cache MISS 触发协程重新渲染 overlay）
                for (mode in TranslationCacheManager.OverlayMode.values()) {
                    renderCache.remove(overlayCacheKey(this@MangaViewerActivity, entry.id, mode))
                }
                overlayState = TranslationCacheManager.OverlayMode.TRANSLATED
                binding.btnToggleImage.setImageResource(android.R.drawable.ic_menu_camera)
                (binding.viewPager.adapter as? PageGroupAdapter)?.notifyItemChanged(pos)

                // 若详情面板展开，实时刷新译文详情
                if (isPanelExpanded) expandPanel()

                com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.toast_retranslate_done))
            } catch (e: Exception) {
                LogCollector.e(TAG, "Retranslate failed", e)
                com.moe.starflow.utils.UiUtils.showToast(this@MangaViewerActivity, e.message ?: getString(R.string.toast_retranslate_failed))
            } finally {
                binding.btnRetranslate.isEnabled = true
                com.moe.starflow.manga.OcrLock.release()
            }
        }
    }

    /**
     * 用刷新后的 HistoryEntry 覆盖 pageGroups 中对应条目的内存快照
     * （代表条目 + 变体列表），保证 expandPanel/getCurrentVariant 读到最新译文。
     */
    private fun updateInMemoryEntry(refreshed: HistoryEntry) {
        for (group in pageGroups) {
            if (group.representative.id == refreshed.id) {
                group.representative = refreshed
            }
            val idx = group.variants.indexOfFirst { it.id == refreshed.id }
            if (idx >= 0) group.variants[idx] = refreshed
        }
    }

    private fun recycleSafeRenderCache(currentEntryIds: Set<Long>?) {
        if (currentEntryIds.isNullOrEmpty()) {
            clearAllAttachedImageViewRefs()
            renderCache.clear()
            return
        }
        // 关键：recycle 前，清空那些 entryId 不在保留集合内的 ViewHolder.ImageView 引用。
        // 保留集合内的 holder 引用必须保留（其 bitmap 仍在 cache 中有效且正在显示），
        // 否则清掉再不重新渲染就是黑屏。只清即将被 recycle 的非保留 holder 引用，
        // 避免 ImageView 仍持有即将被 recycle 的 bitmap 导致 draw 崩溃。
        clearAttachedImageViewRefsNotIn(currentEntryIds)
        renderCache.retainEntries(currentEntryIds)
    }

    /**
     * 清空 ViewPager2 RecyclerView 当前所有 attach 的 ViewHolder.ImageView bitmap 引用。
     * 必须在 renderCache.retainEntries / clear / remove 等回收操作之前调用，
     * 避免 ImageView 仍持有即将被 recycle 的 bitmap 导致 draw 崩溃。
     */
    private fun clearAllAttachedImageViewRefs() {
        clearAttachedImageViewRefsNotIn(null)
    }

    /** 清空绑定到指定 entryId 的所有 attached ViewHolder 的 ImageView 引用。 */
    private fun clearAttachedImageViewRefsForEntry(entryId: Long) {
        val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val vh = rv.getChildViewHolder(child) as? PageGroupAdapter.ViewHolder ?: continue
            if (vh.boundEntryId == entryId) vh.imageView.setImageDrawable(null)
        }
    }

    /**
     * 清空 entryId 不在 [retainIds] 内的 ViewHolder.ImageView 引用。
     * [retainIds] = null 表示清空全部。保留集合内的 holder 引用不动（其 bitmap 仍有效且在显示）。
     */
    private fun clearAttachedImageViewRefsNotIn(retainIds: Set<Long>?) {
        val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val vh = rv.getChildViewHolder(child) as? PageGroupAdapter.ViewHolder ?: continue
            if (retainIds != null && vh.boundEntryId in retainIds) continue
            vh.imageView.setImageDrawable(null)
        }
    }

    private fun collectLiveEntryIds(currentPage: Int): Set<Long> {
        val result = mutableSetOf<Long>()
        for (offset in -2..2) {
            val group = pageGroups.getOrNull(currentPage + offset) ?: continue
            val activeId = activeVariantIds[currentPage + offset] ?: group.representative.id
            result.add(activeId)
        }
        return result
    }

    /**
     * 清空指定 page 的 ViewHolder.ImageView bitmap 引用。
     * 用于在 recycleSafeRenderCache / 切换 variant 前主动释放旧引用，
     * 避免 ViewHolder.ImageView 持有已 recycle 的 bitmap 导致后续 draw 崩溃。
     * 调用时机：recycleSafeRenderCache 之前。
     */
    private fun clearImageViewRefForPage(pagePosition: Int) {
        val rv = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        val vh = rv.findViewHolderForAdapterPosition(pagePosition) as? PageGroupAdapter.ViewHolder ?: return
        vh.imageView.setImageDrawable(null)
    }
}

data class TranslationDetailItem(
    val index: Int,
    val ocrText: String,
    val translatedText: String
)

/**
 * 每页显示一个 pHash 组的代表图片。支持三态 overlay 渲染。
 */
/**
 * overlay 渲染缓存 key（"crop" scope：历史页只显示框选范围）。
 *
 * ⚠️ 必须带**译文替换表指纹**：替换表是渲染时套用的，规则一改就该重渲染；而这里以前只拼
 * `id_mode_crop` 且没有 onResume 失效 —— 用户去设置里改了规则再回到查看器，看到的仍是旧图。
 *
 * ⚠️ 格式仍须以 `${entryId}_` 开头：`BitmapLruCache.retainEntries` 靠 `substringBefore('_')` 认 id。
 * ⚠️ 放**文件级**（不在 Activity / PageGroupAdapter 任一类内）：两处都要用同一个 key 格式。
 */
private fun overlayCacheKey(
    context: Context,
    entryId: Long,
    mode: TranslationCacheManager.OverlayMode
): String = "${entryId}_${mode.name}_crop_r${rulesFingerprint(context)}"

private fun rulesFingerprint(context: Context): Int =
    CustomPreference.getInstance(context)
        .getString(TranslationTextRules.KEY_REPLACEMENTS, "")
        .orEmpty().hashCode()

class PageGroupAdapter(
    private val pageGroups: List<MangaViewerActivity.PageGroup>,
    private val pageCacheMap: Map<Long, PageCacheEntity>,
    private val renderCache: BitmapLruCache,
    private val cacheManager: TranslationCacheManager,
    private val getOverlayState: () -> TranslationCacheManager.OverlayMode,
    private val lifecycleScope: kotlinx.coroutines.CoroutineScope,
    private val prefs: android.content.SharedPreferences
) : RecyclerView.Adapter<PageGroupAdapter.ViewHolder>() {

    private val activeVariants = mutableMapOf<Int, Long>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.ivFullImage)
        /** 当前正在为该 ViewHolder 加载/显示的 entryId + mode，用于异步渲染回调时校验
         * holder 是否已被复用到其他条目，避免把过期 bitmap 写到错误的 ImageView 上
         * 或显示已 recycle 的旧 bitmap。 */
        @Volatile var boundEntryId: Long = -1L
        @Volatile var boundMode: TranslationCacheManager.OverlayMode? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_viewer_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = pageGroups[position]
        val activeId = activeVariants[position]
        val entry = if (activeId != null) {
            group.variants.find { it.id == activeId } ?: group.representative
        } else {
            group.representative
        }
        holder.boundEntryId = entry.id
        holder.boundMode = getOverlayState()
        loadImage(holder, entry)
    }

    override fun getItemCount() = pageGroups.size

    fun setActiveVariant(position: Int, entryId: Long) {
        activeVariants[position] = entryId
    }

    /**
     * ViewHolder 离开窗口时仅记录日志。Bitmap 回收由 BitmapLruCache 自动处理：
     * - LRU 淘汰：eldest entry 被 put 触发 evict 时自动 recycle
     * - 孤儿清理：recycleSafeRenderCache → retainEntries 主动移除并 recycle
     */
    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        LogCollector.d("MangaViewer", "onViewDetachedFromWindow: position=${holder.adapterPosition}, cacheSize=${renderCache.size}")
    }

    private fun loadImage(holder: ViewHolder, entry: HistoryEntry) {
        val mode = getOverlayState()
        // cacheKey 包含 scope（crop/full）—— 历史页默认只渲染 crop 区域
        // （"显示框选范围"，不显示原图其它区域）
        val cacheKey = overlayCacheKey(holder.itemView.context, entry.id, mode)

        // 防御性释放：先清空 ImageView 旧 bitmap 引用，避免外部 recycle 后
        // 残留 dangling reference 在 rebind 之间的 draw cycle 中触发崩溃。
        // 配合 onItemSelected 中 clearImageViewRefForPage 形成双重保护。
        holder.imageView.setImageDrawable(null)

        renderCache[cacheKey]?.let { bitmap ->
            // DIAGNOSTIC #6: log bitmap 身份（address），用于追踪 recycle 来源
            LogCollector.d("MangaViewer", "loadImage: cache HIT id=${entry.id} mode=$mode bitmapIdentity=${System.identityHashCode(bitmap)}")
            holder.imageView.resetZoom()
            holder.imageView.setImageBitmap(bitmap)
            return
        }

        LogCollector.d("MangaViewer", "loadImage: cache MISS id=${entry.id} mode=$mode, will renderOverlay")
        if (entry.bubbleRects.isNullOrBlank()) {
            // 旧数据无 bubbleRects：回退到 imagePath/thumbnailPath 原图
            // 注意：历史页显示的就是 crop 区域本身（用户翻译时的视野范围），
            // 不是完整原图。但旧数据没有 PageCacheEntity 来定位 crop，
            // 这里用 entry.imagePath（预渲染译文图）兜底显示。
            val path = entry.imagePath ?: entry.thumbnailPath
            if (path != null && java.io.File(path).exists()) {
                val bmp = BitmapFactory.decodeFile(path)
                LogCollector.d("MangaViewer", "loadImage: decoded from path id=${entry.id} bitmapIdentity=${System.identityHashCode(bmp)}")
                holder.imageView.resetZoom()
                holder.imageView.setImageBitmap(bmp)
            }
            return
        }

        val pageCache = pageCacheMap[entry.id] ?: run {
            LogCollector.e("MangaViewer", "loadImage: pageCache is null for entry ${entry.id}")
            return
        }

        lifecycleScope.launch {
            try {
                // forFullImage=false：只渲染 crop 区域（用户框选范围），
                // 不显示原图其它区域。气泡坐标已在裁剪空间，无需映射。
                val bitmap = cacheManager.renderOverlay(
                    history = entry,
                    pageCache = pageCache,
                    mode = mode,
                    forFullImage = false,
                    config = cacheManager.getOverlayConfig(prefs)
                )
                withContext(Dispatchers.Main) {
                    // 关键：异步渲染完成时校验 holder 是否仍绑定到同一个 entry + mode。
                    // 若期间翻页/切 variant/切三态已复用此 holder 或切换了 mode，
                    // 直接 setImageBitmap 旧 bitmap 会把过期内容写到错误的 ImageView，
                    // 或写入后立即被新一次 loadImage 的 setImageDrawable(null) 覆盖、bitmap
                    // 既未入 cache 也无人 recycle。校验不一致则丢弃此次结果（不入 cache、不 set）。
                    if (holder.boundEntryId != entry.id || holder.boundMode != mode) {
                        LogCollector.d("MangaViewer", "loadImage: stale render discarded holder bound=(${holder.boundEntryId},${holder.boundMode}) req=(${entry.id},$mode)")
                        bitmap?.recycle()
                        return@withContext
                    }
                    if (bitmap != null) {
                        LogCollector.d("MangaViewer", "loadImage: renderOverlay OK id=${entry.id} mode=$mode bitmapIdentity=${System.identityHashCode(bitmap)}")
                        renderCache[cacheKey] = bitmap
                        holder.imageView.resetZoom()
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                LogCollector.e("MangaViewer", "loadImage: renderOverlay 失败 entryId=${entry.id}", e)
                // 回退到原始 full bitmap 后裁剪 crop 区域（保持历史页只显示 crop 区域的设计）
                val fallbackPath = entry.originalImagePath ?: entry.imagePath ?: entry.thumbnailPath
                if (fallbackPath != null) {
                    try {
                        val fallback = BitmapFactory.decodeFile(fallbackPath)
                        if (fallback != null) {
                            // 异常 fallback：若 pageCache 有 crop，裁切到 crop 区域；
                            // 否则显示整张（无 crop 信息的极端情况）。
                            val croppedFallback = try {
                                val cl = pageCache.cropLeft.coerceIn(0, fallback.width)
                                val ct = pageCache.cropTop.coerceIn(0, fallback.height)
                                val cr = pageCache.cropRight.coerceIn(cl, fallback.width)
                                val cb = pageCache.cropBottom.coerceIn(ct, fallback.height)
                                if (cr > cl && cb > ct) Bitmap.createBitmap(fallback, cl, ct, cr - cl, cb - ct) else fallback
                            } catch (_: Exception) { fallback }
                            // .copy() 创建独立副本，避免与原图共享底层 buffer（防止外部 recycle 触发崩溃）
                            val safe = try { croppedFallback.copy(croppedFallback.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { croppedFallback }
                            croppedFallback.takeIf { it !== fallback }?.recycle()
                            fallback.recycle()
                            if (safe != null) {
                                withContext(Dispatchers.Main) {
                                    // 异步回退完成时同样校验 holder 是否仍属同一 entry + mode
                                    if (holder.boundEntryId == entry.id && holder.boundMode == mode) {
                                        holder.imageView.resetZoom()
                                        holder.imageView.setImageBitmap(safe)
                                    } else {
                                        safe.recycle()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
