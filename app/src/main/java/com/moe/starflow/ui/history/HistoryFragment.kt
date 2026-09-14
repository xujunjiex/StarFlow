package com.moe.starflow.ui.history
import com.moe.starflow.ui.viewer.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.material.tabs.TabLayout
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.databinding.FragmentHistoryBinding
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    companion object {
        private const val TAG = "HistoryFragment"
    }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var gameGroupAdapter: HistoryGroupAdapter
    private lateinit var mangaGroupAdapter: HistoryMangaGroupAdapter

    // 0=游戏, 1=漫画
    private var currentTab = TranslationCacheManager.MODE_GAME

    // SAF 下载
    private var pendingDownloadZip: File? = null
    private val REQUEST_DOWNLOAD_ZIP = 1001
    private var pendingGameContent: String? = null
    private val REQUEST_DOWNLOAD_TXT = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheManager = TranslationCacheManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupViewModeTabs()
        setupRecyclerViews()
        switchTab(TranslationCacheManager.MODE_GAME) // 初始化游戏 tab 的视图状态
        setupClearAllCacheButton()
        setupRefreshButton()
        setupSettingsButton()

        loadHistory()
    }

    private fun setupTabs() {
        binding.historyTabLayout.addTab(
            binding.historyTabLayout.newTab().setText(R.string.history_game_tab)
        )
        binding.historyTabLayout.addTab(
            binding.historyTabLayout.newTab().setText(R.string.history_manga_tab)
        )

        binding.historyTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: TranslationCacheManager.MODE_GAME
                switchTab(currentTab)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerViews() {
        // 游戏历史：分组适配器
        gameGroupAdapter = HistoryGroupAdapter(
            onItemClick = { entry -> copyTranslatedText(entry) },
            onItemLongClick = { entry -> showDeleteDialog(entry) },
            onDownloadSessionClick = { session -> downloadGameSession(session) }
        )
        binding.rvGameHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGameHistory.adapter = gameGroupAdapter

        // 漫画历史：分组适配器
        val prefs = CustomPreference.getInstance(requireContext())
        val displayMode = prefs.getString("history_display_mode", "large")
        val viewMode = prefs.getString("history_view_mode", "default")
        mangaGroupAdapter = HistoryMangaGroupAdapter(
            onItemClick = { grouped -> openMangaViewer(grouped) },
            onItemLongClick = { grouped -> showDeleteDialog(grouped) },
            displayMode = displayMode,
            isManageView = (viewMode == "manage"),
            onThumbnailClick = { entry ->
                openMangaViewer(GroupedHistoryEntry(entry, 1, listOf(entry.id)))
            },
            onDownloadSessionClick = { session -> downloadSession(session) }
        )
        binding.rvMangaHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMangaHistory.adapter = mangaGroupAdapter
    }

    private fun setupClearAllCacheButton() {
        binding.btnClearAllCache.setOnClickListener {
            showClearAllCacheDialog()
        }
    }

    private fun setupRefreshButton() {
        binding.btnRefreshHistory.setOnClickListener {
            loadHistory()
            com.moe.starflow.utils.UiUtils.showToast(requireContext(), getString(R.string.history_refreshed))
        }
    }

    private fun setupSettingsButton() {
        binding.btnHistorySettings.setOnClickListener { view ->
            showHistorySettingsMenu(view)
        }
    }

    private fun showHistorySettingsMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val prefs = CustomPreference.getInstance(requireContext())
        val displayMode = prefs.getString("history_display_mode", "large")

        val displayOptions = arrayOf(
            getString(R.string.history_display_list),
            getString(R.string.history_display_large),
            getString(R.string.history_display_medium),
            getString(R.string.history_display_small)
        )
        val currentDisplayIdx = when (displayMode) {
            "list" -> 0; "large" -> 1; "medium" -> 2; "small" -> 3
            else -> 1
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        container.addView(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.history_display_mode_label)
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 24, 0, 8)
        })

        val displayGroup = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val displayValues = arrayOf("list", "large", "medium", "small")
        displayOptions.forEachIndexed { idx, label ->
            displayGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = label
                id = idx
                isChecked = idx == currentDisplayIdx
            })
        }
        container.addView(displayGroup)

        val cacheCountValues = intArrayOf(0, 20, 50, 100, 200, 500)
        val currentCacheCount = prefs.getString("translation_cache_count", "100").toIntOrNull() ?: 100
        val currentCacheIdx = cacheCountValues.indexOfFirst { it == currentCacheCount }.coerceAtLeast(3)

        val cacheLabel = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.cache_count_title) + ": ${cacheCountValues[currentCacheIdx]}"
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 24, 0, 8)
        }
        container.addView(cacheLabel)

        val cacheSeekBar = android.widget.SeekBar(requireContext()).apply {
            max = cacheCountValues.size - 1
            progress = currentCacheIdx
        }
        cacheSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val v = cacheCountValues[progress]
                cacheLabel.text = getString(R.string.cache_count_title) + ": " + if (v == 0) getString(R.string.cache_disabled) else "$v"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        container.addView(cacheSeekBar)

        // 重翻引擎选择
        container.addView(android.widget.TextView(requireContext()).apply {
            text = "重翻引擎"
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 24, 0, 8)
        })
        val engineValues = arrayOf("PP_OCR_V5", "MANGA_OCR", "MLKIT", "PP_OCR_V6")
        val engineNames = arrayOf("PP-OCRv5", "manga-ocr", "ML Kit", "PP-OCRv6")
        val savedEngine = prefs.getString("history_retranslate_engine", "PP_OCR_V5")
        val currentEngineIdx = engineValues.indexOfFirst { it == savedEngine }.coerceAtLeast(0)
        val engineGroup = android.widget.RadioGroup(requireContext()).apply { orientation = android.widget.RadioGroup.VERTICAL }
        engineNames.forEachIndexed { idx, name ->
            engineGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = name; id = idx; isChecked = idx == currentEngineIdx
            })
        }
        container.addView(engineGroup)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_settings_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newDisplayIdx = displayGroup.checkedRadioButtonId
                if (newDisplayIdx >= 0) {
                    prefs.setString("history_display_mode", displayValues[newDisplayIdx])
                }
                prefs.setString("translation_cache_count", cacheCountValues[cacheSeekBar.progress].toString())
                val newEngineIdx = engineGroup.checkedRadioButtonId
                if (newEngineIdx >= 0) {
                    prefs.setString("history_retranslate_engine", engineValues[newEngineIdx])
                }
                loadHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                show()
            }
    }

    private fun setupViewModeTabs() {
        val prefs = CustomPreference.getInstance(requireContext())

        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText(R.string.history_view_default))
        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText(R.string.history_view_manage))

        val savedMode = prefs.getString("history_view_mode", "default")
        if (savedMode == "manage") {
            binding.viewModeTabLayout.selectTab(binding.viewModeTabLayout.getTabAt(1))
        }

        binding.viewModeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isManage = tab?.position == 1
                prefs.setString("history_view_mode", if (isManage) "manage" else "default")
                updateEngineSelectorVisibility()
                loadHistory()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // 引擎选择已移入设置弹窗，不再在主界面显示
    private fun updateEngineSelectorVisibility() {
        val prefs = CustomPreference.getInstance(requireContext())
        // 引擎选择已移入设置弹窗
        @Suppress("UNUSED_VARIABLE") val isManageView = prefs.getString("history_view_mode", "default") == "manage"
        @Suppress("UNUSED_VARIABLE") val isMangaTab = currentTab == TranslationCacheManager.MODE_MANGA
        binding.engineSelectorLayout.visibility = View.GONE // 引擎选择已移入设置弹窗
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        // Game tab: hide view mode switching and engine selector
        val isManga = tab == TranslationCacheManager.MODE_MANGA
        binding.viewModeTabLayout.visibility = if (isManga) View.VISIBLE else View.GONE
        if (!isManga) {
            binding.engineSelectorLayout.visibility = View.GONE
        }
        when (tab) {
            TranslationCacheManager.MODE_GAME -> {
                binding.rvGameHistory.visibility = View.VISIBLE
                binding.rvMangaHistory.visibility = View.GONE
            }
            TranslationCacheManager.MODE_MANGA -> {
                binding.rvGameHistory.visibility = View.GONE
                binding.rvMangaHistory.visibility = View.VISIBLE
            }
        }
        updateEngineSelectorVisibility()
        loadHistory()
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prefs = CustomPreference.getInstance(requireContext())
                val viewMode = prefs.getString("history_view_mode", "default")
                val sortByUpdated = (viewMode == "default")
                val displayMode = prefs.getString("history_display_mode", "large")

                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        // 游戏历史始终按进程（sessionId）分组，不按日期合并
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = false)
                        gameGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: game groups=${groups.size}, sessions total=${groups.sumOf { it.sessions.size }}, entries total=${groups.sumOf { g -> g.sessions.sumOf { it.entries.size } }}")
                        for ((gi, g) in groups.withIndex()) {
                            for ((si, s) in g.sessions.withIndex()) {
                                LogCollector.d(TAG, "  group[$gi].session[$si]: id=${s.sessionId.take(8)}, entries=${s.entries.size}")
                            }
                        }
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        mangaGroupAdapter.isManageView = (viewMode == "manage")
                        mangaGroupAdapter.setDisplayMode(displayMode)
                        mangaGroupAdapter.setSortByUpdated(sortByUpdated)
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)

                        mangaGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: manga groups=${groups.size}, sort=${if (sortByUpdated) "default" else "manage"}, display=$displayMode")
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "loadHistory failed", e)
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun copyTranslatedText(entry: HistoryEntry) {
        val sourceText = entry.sourceText ?: ""
        val translatedText = entry.translatedText ?: ""
        if (sourceText.isEmpty() && translatedText.isEmpty()) return

        val text = buildString {
            if (sourceText.isNotEmpty()) {
                append("原文: ")
                append(sourceText)
            }
            if (translatedText.isNotEmpty()) {
                if (sourceText.isNotEmpty()) append("\n")
                append("译文: ")
                append(translatedText)
            }
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translated_text", text))
        Toast.makeText(requireContext(), R.string.text_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showDarkDialog(
        message: String,
        title: String? = null,
        positiveText: String = "确定",
        negativeText: String? = null,
        onPositive: () -> Unit = {},
        onNegative: (() -> Unit)? = null
    ) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_dark, null)
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

        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener { onPositive(); dialog.dismiss() }
        if (negativeText != null) {
            btnNegative.setOnClickListener { onNegative?.invoke(); dialog.dismiss() }
        }

        dialog.show()
    }

    private fun showDeleteDialog(grouped: GroupedHistoryEntry) {
        val ids = grouped.allEntryIds
        val isGroup = ids.size > 1
        val message = if (isGroup) {
            getString(R.string.delete_history_group_confirm, ids.size)
        } else {
            getString(R.string.delete_history_confirm)
        }
        showDarkDialog(
            message = message,
            positiveText = getString(R.string.confirm),
            negativeText = getString(R.string.user_cancel),
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistories(ids)
                        LogCollector.d(TAG, "Deleted history group: ${ids.size} entries")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Delete history group failed", e)
                    }
                }
            }
        )
    }

    /** 游戏历史：单条删除（游戏模式无 pHash 分组概念） */
    private fun showDeleteDialog(entry: HistoryEntry) {
        showDarkDialog(
            message = getString(R.string.delete_history_confirm),
            positiveText = getString(R.string.confirm),
            negativeText = getString(R.string.user_cancel),
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistory(entry.id)
                        LogCollector.d(TAG, "Deleted history: id=${entry.id}")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Delete history failed", e)
                    }
                }
            }
        )
    }

    private fun showClearDialog() {
        showDarkDialog(
            message = getString(R.string.confirm_clear_history),
            positiveText = getString(R.string.confirm),
            negativeText = getString(R.string.user_cancel),
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.clearHistory(currentTab)
                        LogCollector.d(TAG, "Cleared history: type=$currentTab")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Clear history failed", e)
                    }
                }
            }
        )
    }

    private fun showClearAllCacheDialog() {
        showDarkDialog(
            title = getString(R.string.clear_cache_title),
            message = getString(R.string.clear_cache_confirm),
            positiveText = getString(R.string.confirm),
            negativeText = getString(R.string.user_cancel),
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.clearHistory(currentTab)
                        LogCollector.d(TAG, "Cleared history: type=$currentTab")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Clear all cache failed", e)
                    }
                }
            }
        )
    }

    /**
     * 打开漫画图片浏览页（支持同 pHash 多尺寸切换）
     */
    private fun openMangaViewer(grouped: GroupedHistoryEntry) {
        val prefs = CustomPreference.getInstance(requireContext())
        val isManage = prefs.getString("history_view_mode", "default") == "manage"
        val intent = Intent(requireContext(), MangaViewerActivity::class.java).apply {
            putExtra(MangaViewerActivity.EXTRA_ENTRY_ID, grouped.representative.id)
            putExtra(MangaViewerActivity.EXTRA_ENTRY_IDS, grouped.allEntryIds.toLongArray())
            putExtra(MangaViewerActivity.EXTRA_IS_MANAGE_VIEW, isManage)
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        (requireContext() as? Activity)?.overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }


    // ========== Session ZIP download ==========

    /**
     * 下载进程组图片为 ZIP
     */
    private fun downloadSession(session: com.moe.starflow.data.HistorySession) {
        lifecycleScope.launch {
            try {
                // Check for multi-size variants
                val hasMultiVariant = session.entries.any { it.variantCount > 1 }

                if (hasMultiVariant) {
                    withContext(Dispatchers.Main) {
                        showDarkDialog(
                            message = "该进程组包含多个尺寸的翻译结果，将全部下载。",
                            positiveText = "全部下载",
                            negativeText = "取消",
                            onPositive = {
                                lifecycleScope.launch { doDownloadSession(session) }
                            }
                        )
                    }
                } else {
                    doDownloadSession(session)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Download session failed", e)
                Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun doDownloadSession(session: com.moe.starflow.data.HistorySession) {
        // 展开所有变体（session.entries 只含代表，通过 variantIds 获取全部）
        val allEntryIds = session.entries.flatMap { e -> e.variantIds.ifEmpty { listOf(e.id) } }.distinct()
        val allEntriesMap = if (allEntryIds.size > session.entries.size) {
            cacheManager.getHistoryByIds(allEntryIds).associateBy { it.id }
        } else {
            emptyMap()
        }

        // 进度提示
        val progressDialog = withContext(Dispatchers.Main) {
            android.app.ProgressDialog(requireContext()).apply {
                setMessage("正在准备下载...")
                setCancelable(false)
                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                setMax(allEntryIds.size)
                show()
            }
        }

        val config = cacheManager.getOverlayConfig(CustomPreference.getInstance(requireContext()).getSharedPreferences())
        val tempFiles = mutableListOf<File>()
        val tempDir = File(requireContext().cacheDir, "download_${System.currentTimeMillis()}").also { it.mkdirs() }

        try {
            LogCollector.d("HistoryFragment", "doDownloadSession: ${session.entries.size} page groups")
            var skippedCount = 0
            withContext(Dispatchers.IO) {
            for ((pageIdx, groupEntry) in session.entries.withIndex()) {
                val pageNum = pageIdx + 1
                // 按 variantIds 顺序获取每个变体并渲染
                val variantIds = groupEntry.variantIds.ifEmpty { listOf(groupEntry.id) }
                val variants = if (variantIds.size > 1) {
                    variantIds.mapNotNull { allEntriesMap[it] }
                } else {
                    listOf(groupEntry)
                }
                for ((vi, entry) in variants.withIndex()) {
                    LogCollector.d("HistoryFragment", "download page[$pageNum] variant[$vi] id=${entry.id} bubbleRects=${if (entry.bubbleRects.isNullOrBlank()) "EMPTY" else "${entry.bubbleRects.length} chars"}")
                    val bitmap: Bitmap? = if (entry.bubbleRects.isNullOrBlank()) {
                        entry.imagePath?.let { BitmapFactory.decodeFile(it) }
                    } else {
                        val pageCache = cacheManager.getPageCacheByHistoryId(entry.id)
                        if (pageCache != null) {
                            cacheManager.renderOverlay(entry, pageCache, TranslationCacheManager.OverlayMode.TRANSLATED, forFullImage = false, config = config)
                        } else entry.imagePath?.let { BitmapFactory.decodeFile(it) }
                    }

                    if (bitmap != null) {
                        val isMultiVariant = variants.size > 1
                        val fileName = if (isMultiVariant) {
                            if (vi == 0) "page_%02d.jpg".format(pageNum)
                            else "page_%02d_v%d.jpg".format(pageNum, vi + 1)
                        } else {
                            "page_%02d.jpg".format(pageNum)
                        }
                        val file = File(tempDir, fileName)
                        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                        tempFiles.add(file)
                        LogCollector.d("HistoryFragment", "saved ${fileName} size=${file.length()}B")
                        bitmap.recycle()
                    } else {
                        skippedCount++
                    }

                    withContext(Dispatchers.Main) { progressDialog.progress = tempFiles.size }
                }
            }
            } // end withContext(IO) for render loop

            withContext(Dispatchers.Main) { progressDialog.dismiss() }

            if (skippedCount > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "跳过 $skippedCount 张无法解析的页面", Toast.LENGTH_SHORT).show()
                }
            }
            if (tempFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "没有可下载的图片", Toast.LENGTH_SHORT).show()
                }
                return
            }

            withContext(Dispatchers.IO) {
                val zipFile = File(requireContext().cacheDir, "session_${session.sessionId}.zip")
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (file in tempFiles) {
                        zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                LogCollector.d(TAG, "zip created at ${zipFile.absolutePath} size=${zipFile.length()}B")
                tempDir.deleteRecursively()

                withContext(Dispatchers.Main) {
                    // 用 MediaStore API 写入 Downloads 公共目录（Android 11+ Scoped Storage 兼容）
                    try {
                        val resolver = requireContext().contentResolver
                        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        } else null
                        val fileName = "session_${session.sessionId.take(8)}.zip"
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            }
                        }
                        val targetUri = if (collection != null) {
                            resolver.insert(collection, values)
                        } else {
                            // Android 10 fallback
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS
                            )
                            val targetFile = File(downloadDir, fileName)
                            android.net.Uri.fromFile(targetFile)
                        }
                        if (targetUri == null) throw Exception("insert returned null")

                        resolver.openOutputStream(targetUri)?.use { out ->
                            zipFile.inputStream().use { it.copyTo(out) }
                        }
                        LogCollector.d(TAG, "zip saved to MediaStore uri=$targetUri")
                        com.moe.starflow.utils.UiUtils.showToast(requireContext(), "已保存到 Download/$fileName")
                        zipFile.delete()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "MediaStore save failed, fallback to SAF", e)
                        // fallback: SAF
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "session_${session.sessionId.take(8)}.zip")
                        }
                        pendingDownloadZip = zipFile
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_DOWNLOAD_ZIP)
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { try { progressDialog.dismiss() } catch (_: Exception) {} }
            tempDir.deleteRecursively()
            tempFiles.forEach { it.delete() }
            LogCollector.e(TAG, "Download session failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 下载游戏进程组翻译为 TXT 文件
     */
    private fun downloadGameSession(session: com.moe.starflow.data.HistorySession) {
        lifecycleScope.launch {
            var progressDialog: android.app.ProgressDialog? = null
            try {
                // 如果会话条目为空，尝试直接从 DB 获取（兜底）
                val entriesToDownload = if (session.entries.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        LogCollector.w(TAG, "downloadGameSession: session entries empty, fetching from DB directly")
                        cacheManager.getHistory(TranslationCacheManager.MODE_GAME, limit = 10000)
                            .filter { it.sessionId == session.sessionId || it.lastSessionId == session.sessionId }
                    }
                } else {
                    session.entries
                }

                LogCollector.d(TAG, "downloadGameSession: sessionId=${session.sessionId.take(8)}, entries=${entriesToDownload.size}")
                for ((i, e) in entriesToDownload.withIndex()) {
                    LogCollector.d(TAG, "  entry[$i]: src=${e.sourceText?.take(20) ?: "null"}, trans=${e.translatedText?.take(20) ?: "null"}")
                }

                // 进度提示
                progressDialog = withContext(Dispatchers.Main) {
                    android.app.ProgressDialog(requireContext()).apply {
                        setMessage("正在准备下载...")
                        setCancelable(false)
                        show()
                    }
                }

                val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val content = buildString {
                    if (entriesToDownload.isEmpty()) {
                        append("(无翻译记录)\n")
                    } else {
                        for (entry in entriesToDownload) {
                            val time = dateFormat.format(Date(entry.updatedAt))
                            append("[$time]\n")
                            val src = entry.sourceText
                            val trans = entry.translatedText
                            if (src.isNullOrEmpty() && trans.isNullOrEmpty()) {
                                append("(空记录)\n")
                            } else {
                                if (!src.isNullOrEmpty()) append("原文: $src\n")
                                if (!trans.isNullOrEmpty()) append("译文: $trans\n")
                            }
                            append("---\n")
                        }
                    }
                }
                withContext(Dispatchers.Main) { progressDialog?.dismiss() }

                // 直接通过 SAF 写入，不用临时文件
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "session_${session.sessionId.take(8)}.txt")
                    }
                    pendingGameContent = content
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_DOWNLOAD_TXT)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Download game session failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载失败")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_DOWNLOAD_ZIP && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            pendingDownloadZip?.let { zip ->
                                LogCollector.d(TAG, "zip saved uri=$uri, zipFile=${zip.absolutePath} size=${zip.length()}")
                                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                                    zip.inputStream().use { it.copyTo(out) }
                                }
                                LogCollector.d(TAG, "zip copied to uri, deleting temp file")
                                zip.delete()
                            }
                        }
                        com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载完成")
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Download save failed", e)
                        com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载失败")
                    }
                }
            }
            pendingDownloadZip = null
        }
        if (requestCode == REQUEST_DOWNLOAD_TXT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val content = pendingGameContent
                pendingGameContent = null
                if (content != null) {
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                                    out.write(content.toByteArray(Charsets.UTF_8))
                                }
                            }
                            com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载完成")
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "Download txt save failed", e)
                            com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载失败")
                        }
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
