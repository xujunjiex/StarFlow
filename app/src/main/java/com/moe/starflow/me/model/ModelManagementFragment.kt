package com.moe.starflow.me.model
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.moe.starflow.R
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.manga.engine.MangaOcrModelFiles
import com.moe.starflow.manga.config.OcrEngineGroup
import com.moe.starflow.manga.engine.PPOcrModelFiles
import com.moe.starflow.manga.engine.RTDetrModelFiles
import com.moe.starflow.sr.SrModelManager
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.OcrEngineManager
import com.moe.starflow.utils.UiUtils
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch

/**
 * 模型管理页面 — **数据驱动**，可扩展。
 *
 * 新增一个模型只需：
 * 1. 在 `fragment_model_management.xml` 对应分组里加一行
 *    `<include layout="@layout/item_model_row_*" android:id="@+id/xxx_row"/>`
 *    （**include 的 id 必须有且唯一**，它就是该行的根 View）
 * 2. 在 [modelRows] 加一条 [ModelRow]，填 `rowRootId` + 模板内的浏览器按钮 ID
 *
 * 渲染、浏览器按钮接线、磁盘状态刷新全部由 [modelRows] 自动完成，不再需要逐模型写方法。
 *
 * ⚠️ 行模板内部的 ID（`row_status`/`row_action`/`row_cancel`…）在多次 include 之间**是重复的**，
 * 所有子控件查找一律以行根 View 为作用域（[rowRoot]），**禁止全局 `rootView.findViewById`**，
 * 否则会串到别的行上去。
 */
class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }

    companion object {
        private const val ARG_SHOW_SR = "show_sr_tab"

        /**
         * 直接打开「超分」Tab。
         *
         * 供个性化设置的「超分模型管理」、阅读器面板的超分模型入口等使用 ——
         * 用户从别处跳进来时想看的就是超分，不该让他再点一次 Tab。
         */
        fun newInstance(showSrTab: Boolean = false): ModelManagementFragment =
            ModelManagementFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_SHOW_SR, showSrTab) }
            }
    }

    /** 页面展示的所有模型行配置（顺序即页面顺序） */
    private data class ModelRow(
        val modelKey: ModelKey,
        val displayName: String,
        val expectedSize: String,
        /** include 进来的行根 View（其 id 在 XML 里唯一）；子控件查找一律以它为作用域 */
        val rowRootId: Int,
        /** 浏览器按钮（模板内 ID）→ 打开 browser_url（模型主页） */
        val browserBtnIds: List<Int> = emptyList(),
        /** 浏览器按钮（模板内 ID）列表，逐个对应 JSON files 里第 i 个文件的 download_url */
        val fileBrowserBtnIds: List<Int> = emptyList()
    )

    private val modelRows: List<ModelRow> = listOf(
        // 1. RT-DETR-V2（单文件，浏览器按钮开模型主页）
        ModelRow(ModelKey.RT_DETR_V2, "RT-DETR-V2", "~11MB",
            R.id.rtdetr_row, browserBtnIds = listOf(R.id.row_browser)),
        // 2. manga-ocr（3 文件：encoder/decoder/vocab）
        ModelRow(ModelKey.MANGA_OCR_GROUP, "manga-ocr", "~135MB",
            R.id.manga_ocr_row, fileBrowserBtnIds = listOf(
                R.id.row_browser_encoder,
                R.id.row_browser_decoder,
                R.id.row_browser_vocab
            )),
        // 3. PP-OCRv5 检测器（单文件，浏览器按钮开模型主页）
        ModelRow(ModelKey.PP_OCR_V5_DET, "PP-OCRv5 DET", "~4.6MB",
            R.id.v5_det_row, browserBtnIds = listOf(R.id.row_browser)),
        // 4-7. PP-OCRv5 识别器（每个 2 文件：onnx + 字典）
        ModelRow(ModelKey.PP_OCR_V5_REC_ZH, "PP-OCRv5 REC ZH", "~16MB",
            R.id.v5_rec_zh_row, fileBrowserBtnIds = listOf(R.id.row_browser_model, R.id.row_browser_dict)),
        ModelRow(ModelKey.PP_OCR_V5_REC_EN, "PP-OCRv5 REC EN", "~7.5MB",
            R.id.v5_rec_en_row, fileBrowserBtnIds = listOf(R.id.row_browser_model, R.id.row_browser_dict)),
        ModelRow(ModelKey.PP_OCR_V5_REC_KO, "PP-OCRv5 REC KO", "~12.9MB",
            R.id.v5_rec_ko_row, fileBrowserBtnIds = listOf(R.id.row_browser_model, R.id.row_browser_dict)),
        ModelRow(ModelKey.PP_OCR_V5_REC_RU, "PP-OCRv5 REC RU", "~7.7MB",
            R.id.v5_rec_ru_row, fileBrowserBtnIds = listOf(R.id.row_browser_model, R.id.row_browser_dict)),
        // 8-9. PP-OCRv6 medium（单文件，浏览器按钮开文件下载）
        ModelRow(ModelKey.PP_OCR_V6_MEDIUM_DET, "PP-OCRv6 DET (medium)", "~60MB",
            R.id.ppocrv6_medium_det_row, fileBrowserBtnIds = listOf(R.id.row_browser)),
        ModelRow(ModelKey.PP_OCR_V6_MEDIUM_REC, "PP-OCRv6 REC (medium)", "~74MB",
            R.id.ppocrv6_medium_rec_row, fileBrowserBtnIds = listOf(R.id.row_browser))
    )

    // ══════════════════════════════════════════════════════════════════
    // 超分（SR）Tab —— 2026-10
    //
    // ⚠️ **结构与 OCR Tab 完全一致**（XML 里逐行 `<include>`，不再动态拼控件）：
    //      组标题(14sp bold + @drawable/ocr_group_selector + clickable)
    //      → 组「当前使用」(12sp @color/success)
    //      → 卡片(@drawable/setting_shape)
    //           ├ 组描述(11sp #888)
    //           └ 每个模型：标题(14sp bold) → `<include item_model_row_browser>` → 分隔线(@color/divider)
    //
    // 「组」= **模型族**（AnimeJaNai / waifu2x cunet / waifu2x swin_unet）。
    // 点组标题 = 选中该族的**推荐档**（各族第一行）—— 与 OCR「点 4 组标题选引擎」同义。
    //
    // 与 OCR 共用同一套下载/状态/浏览器机制（同一个 ModelDownloadRepository +
    // item_model_row_browser 模板 + renderRowState），差别只有「组」的语义。
    // ⚠️ 行内 ID 跨行重复 → 一律以**行根 View** 为作用域查找（同 OCR 页约定）。
    // ══════════════════════════════════════════════════════════════════

    /** 一行 = 一个超分模型：XML 里的行根 id + 对应 ModelKey */
    /**
     * 一行 = 一个超分模型。
     *
     * @param radioId 行内那个**不可点**的 RadioButton（只用来显示"这一档是当前使用"）。
     *   ⚠️ 为什么必须有它：组的「当前使用」只标到**族**上，同一个族的四个等级看起来一模一样 ——
     *   用户选完 cunet n2 之后，界面上没有任何地方能看出"到底哪一档生效了"。
     */
    private data class SrRow(val modelKey: ModelKey, val rowRootId: Int, val radioId: Int)

    /** 一族 = XML 里一个分组（组标题 / 「当前使用」标记 / 若干行） */
    private data class SrFamily(val titleId: Int, val selectedId: Int, val rows: List<SrRow>)

    /**
     * 超分模型族。**顺序必须与 `fragment_model_management.xml` 里的组顺序一致**
     * （两边各写一份是有意的：XML 管版式、这里管语义；错位了「当前使用」会标到别的族上）。
     */
    private val srFamilies: List<SrFamily> = listOf(
        SrFamily(R.id.sr_aji_group_title, R.id.sr_aji_group_selected, listOf(
            SrRow(ModelKey.SR_ANIMEJANAI_HD_BALANCED, R.id.sr_aji_balanced_row, R.id.sr_aji_balanced_radio),
            SrRow(ModelKey.SR_ANIMEJANAI_HD_PERFORMANCE, R.id.sr_aji_perf_row, R.id.sr_aji_perf_radio),
            SrRow(ModelKey.SR_ANIMEJANAI_HD_SHARP1_BALANCED, R.id.sr_aji_sharp1_balanced_row, R.id.sr_aji_sharp1_balanced_radio),
            SrRow(ModelKey.SR_ANIMEJANAI_HD_SHARP1_PERFORMANCE, R.id.sr_aji_sharp1_perf_row, R.id.sr_aji_sharp1_perf_radio),
            SrRow(ModelKey.SR_ANIMEJANAI_SD_COMPACT, R.id.sr_aji_sd_row, R.id.sr_aji_sd_radio),
        )),
        SrFamily(R.id.sr_w2xc_group_title, R.id.sr_w2xc_group_selected, listOf(
            SrRow(ModelKey.SR_WAIFU2X_CUNET_N0, R.id.sr_w2xc_n0_row, R.id.sr_w2xc_n0_radio),
            SrRow(ModelKey.SR_WAIFU2X_CUNET_N1, R.id.sr_w2xc_n1_row, R.id.sr_w2xc_n1_radio),
            SrRow(ModelKey.SR_WAIFU2X_CUNET_N2, R.id.sr_w2xc_n2_row, R.id.sr_w2xc_n2_radio),
            SrRow(ModelKey.SR_WAIFU2X_CUNET_N3, R.id.sr_w2xc_n3_row, R.id.sr_w2xc_n3_radio),
        )),
        SrFamily(R.id.sr_w2xs_group_title, R.id.sr_w2xs_group_selected, listOf(
            SrRow(ModelKey.SR_WAIFU2X_SWIN_N0, R.id.sr_w2xs_n0_row, R.id.sr_w2xs_n0_radio),
            SrRow(ModelKey.SR_WAIFU2X_SWIN_N1, R.id.sr_w2xs_n1_row, R.id.sr_w2xs_n1_radio),
        ))
    )

    /** 扁平化的全部超分行（磁盘刷新 / 渲染都要遍历它） */
    private val srRows: List<SrRow> get() = srFamilies.flatMap { it.rows }

    /** 每个超分模型的预估体积文案（仅展示用；真实值以 downloadinfo.json 为准） */
    private fun srExpectedSize(key: ModelKey): String = when (key) {
        ModelKey.SR_ANIMEJANAI_HD_BALANCED,
        ModelKey.SR_ANIMEJANAI_HD_SHARP1_BALANCED -> "~1.9MB"
        ModelKey.SR_ANIMEJANAI_HD_PERFORMANCE,
        ModelKey.SR_ANIMEJANAI_HD_SHARP1_PERFORMANCE -> "~0.7MB"
        ModelKey.SR_ANIMEJANAI_SD_COMPACT -> "~1.2MB"
        ModelKey.SR_WAIFU2X_CUNET_N0,
        ModelKey.SR_WAIFU2X_CUNET_N1,
        ModelKey.SR_WAIFU2X_CUNET_N2,
        ModelKey.SR_WAIFU2X_CUNET_N3 -> "~5.2MB"
        ModelKey.SR_WAIFU2X_SWIN_N0,
        ModelKey.SR_WAIFU2X_SWIN_N1 -> "~16.8MB"
        else -> ""
    }

    /** 行根 View —— 模板内部 ID 跨行重复，所有子控件查找必须以它为作用域 */
    private fun rowRoot(row: ModelRow): View = rootView.findViewById(row.rowRootId)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootView = inflater.inflate(R.layout.fragment_model_management, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 顶部 Tab（OCR / 超分）+ 绑定超分组（行来自 XML，这里只接事件）
        setupTabs()
        bindSrGroups()

        // 从别处跳进来（个性化「超分模型管理」、阅读器超分入口）时直接落在超分 Tab
        if (arguments?.getBoolean(ARG_SHOW_SR) == true) selectSrTab()

        // Subscribe to Repository state changes; refresh all model status blocks on each emission.
        viewLifecycleOwner.lifecycleScope.launch {
            // 页面进入时先按磁盘文件重新计算状态（识别已下载/部分下载的模型）
            for (row in modelRows) {
                repo.refreshFromDisk(row.modelKey)
            }
            // 超分模型同样按磁盘刷新 —— 否则刚下载完的超分模型在本页显示为「未下载」
            for (row in srRows) {
                repo.refreshFromDisk(row.modelKey)
            }
            repo.observe().collect {
                renderAll()
            }
        }

        setupBrowserDownloadButtons()
        setupV6TierSwitching()

        // 显示模型存储路径（Android 通用格式）
        val pathText = rootView.findViewById<TextView>(R.id.model_storage_path)
        val fullPath = requireContext().getExternalFilesDir(null)?.absolutePath ?: "N/A"
        // 提取 Android/data/... 部分，去掉 /storage/emulated/0/ 前缀
        val genericPath = if (fullPath.contains("Android/data/")) {
            fullPath.substring(fullPath.indexOf("Android/data/"))
        } else {
            fullPath
        }
        pathText.text = genericPath

        refreshOcrGroupSelection()
    }

    /** 4 组：OcrEngineGroup → (组标题 View, 状态角标 View) */
    private val groupViews = listOf(
        OcrEngineGroup.MLKIT to (R.id.mlkit_group_title to R.id.mlkit_group_selected),
        OcrEngineGroup.PP_OCR_V6 to (R.id.ppocrv6_group_title to R.id.ppocrv6_group_selected),
        OcrEngineGroup.PP_OCR_V5 to (R.id.ppocrv5_group_title to R.id.ppocrv5_group_selected),
        OcrEngineGroup.RT_MANGA to (R.id.rt_manga_group_title to R.id.rt_manga_group_selected)
    )

    /** 刷新 OCR 组选择状态：高亮当前组、未下载组置灰、点击选择/弹提示 */
    private fun refreshOcrGroupSelection() {
        val prefs = CustomPreference.getInstance(requireContext())
        val current = OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences())
        for ((group, ids) in groupViews) {
            val title = rootView.findViewById<View>(ids.first)
            val badge = rootView.findViewById<TextView>(ids.second)
            title.isSelected = (group == current)
            badge.visibility = if (group == current) View.VISIBLE else View.GONE
            // 未下载组置灰：PP-OCRv5 需 det+rec_zh，RT-MANGA 需 RT-DETR+manga-ocr
            val available = when (group) {
                OcrEngineGroup.PP_OCR_V5 -> PPOcrModelFiles.isV5DetDownloaded(requireContext()) && PPOcrModelFiles.isV5RecZhDownloaded(requireContext())
                OcrEngineGroup.RT_MANGA -> RTDetrModelFiles.isModelAvailable(requireContext()) && MangaOcrModelFiles.isModelDownloaded(requireContext())
                else -> true
            }
            title.isEnabled = available
            title.alpha = if (available) 1f else 0.4f
            title.setOnClickListener {
                if (available) {
                    OcrEngineManager.setOcrEngineGroup(prefs.getSharedPreferences(), group)
                    UiUtils.showToast(requireContext(), getString(group.labelRes), isShort = true)
                    refreshOcrGroupSelection()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setMessage(getString(group.requiredModelsRes))
                        .setPositiveButton(R.string.user_known, null)
                        .create().also { it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }.show()
                }
            }
        }
    }

    /** 渲染所有模型行（数据驱动，遍历 [modelRows] + 超分行） */
    private fun renderAll() {
        for (row in modelRows) {
            renderModelBlock(row, repo.getState(row.modelKey))
        }
        for (row in srRows) {
            renderRowState(
                rootView.findViewById(row.rowRootId), row.modelKey,
                getString(SrModelManager.nameResOf(row.modelKey)), srExpectedSize(row.modelKey)
            )
        }
        refreshSrSelection()
        updateV6TierVisibility()
    }

    /** 接线所有浏览器按钮（数据驱动，作用域限定在各自的 [rowRoot]） */
    private fun setupBrowserDownloadButtons() {
        // 超分行（2026-10）：版式与 OCR 行相同（模板 A，单浏览器按钮），
        // 这里跟 OCR 行走同一条接线，别再各写一份
        for (row in srRows) {
            rootView.findViewById<View>(row.rowRootId)
                .findViewById<TextView>(R.id.row_browser)
                ?.setOnClickListener { openBrowser(repo.getBrowserUrl(row.modelKey) ?: "") }
        }
        for (row in modelRows) {
            val root = rowRoot(row)
            row.browserBtnIds.forEach { btnId ->
                root.findViewById<TextView>(btnId)?.setOnClickListener {
                    openBrowser(repo.getBrowserUrl(row.modelKey) ?: "")
                }
            }
            row.fileBrowserBtnIds.forEachIndexed { i, btnId ->
                root.findViewById<TextView>(btnId)?.setOnClickListener {
                    val url = repo.getModelInfo(row.modelKey)?.files?.getOrNull(i)?.downloadUrl
                        ?: repo.getBrowserUrl(row.modelKey)
                        ?: ""
                    openBrowser(url)
                }
            }
        }
    }

    private fun openBrowser(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            LogCollector.e(TAG, "无法打开浏览器: $url", e)
        }
    }

    private fun setButtonDeleteStyle(btn: TextView, isDelete: Boolean) {
        btn.setBackgroundResource(if (isDelete) R.drawable.btn_delete else R.drawable.btn_download)
    }

    /**
     * 统一渲染一个模型的状态块（2 按钮版）
     *
     * - Idle / Partial: 单按钮「下载」
     * - Running: 双按钮「暂停」+「取消」
     * - Paused: 单按钮「继续」
     * - Done: 单按钮「删除」
     */
    private fun renderModelBlock(
        row: ModelRow,
        state: DownloadState
    ) {
        renderRowStateInto(rowRoot(row), row.modelKey, row.displayName, row.expectedSize, state)
        LogCollector.d(TAG, "${row.displayName} state=$state")
    }

    /** 超分行：状态直接问 Repository（OCR 行由 observe 回调带 state 进来） */
    private fun renderRowState(
        root: View,
        modelKey: ModelKey,
        displayName: String,
        expectedSize: String
    ) {
        renderRowStateInto(root, modelKey, displayName, expectedSize, repo.getState(modelKey))
    }

    /**
     * 统一渲染一个模型的状态块（2 按钮版）—— OCR 行与超分行共用。
     *
     * - Idle / Partial: 单按钮「下载」
     * - Running: 双按钮「暂停」+「取消」
     * - Paused: 单按钮「继续」
     * - Done: 单按钮「删除」
     *
     * ⚠️ 所有子控件一律以传入的 [root] 为作用域查找（模板内部 ID 跨行重复）。
     */
    private fun renderRowStateInto(
        root: View,
        modelKey: ModelKey,
        displayName: String,
        expectedSize: String,
        state: DownloadState
    ) {
        val statusText = root.findViewById<TextView>(R.id.row_status)
        val actionBtn = root.findViewById<TextView>(R.id.row_action)
        val cancelBtn = root.findViewById<TextView>(R.id.row_cancel)

        // 默认隐藏 cancel 按钮
        cancelBtn.visibility = View.GONE

        when (state) {
            DownloadState.Idle, is DownloadState.Partial -> {
                statusText.text = getString(R.string.model_status_undownloaded_format, expectedSize)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), modelKey, isResume = state is DownloadState.Partial)
                }
            }
            is DownloadState.Running -> {
                val totalPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                val speed = if (state.speedBytesPerSec > 0) " · ${formatSpeed(state.speedBytesPerSec)}" else ""
                statusText.text = if (state.currentFileCount > 1) {
                    // 多文件显示当前文件进度（第几个 + 当前文件百分比 + 文件名）+ 速度
                    getString(
                        R.string.model_status_running_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        state.currentFileProgress,
                        state.currentFileName
                    ) + speed
                } else {
                    "${getString(R.string.model_downloading)} $totalPct%$speed"
                }
                actionBtn.text = getString(R.string.model_btn_pause)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.pauseDownload(requireContext(), modelKey)
                }
                cancelBtn.visibility = View.VISIBLE
                cancelBtn.setOnClickListener {
                    ModelDownloadService.cancelDownload(requireContext(), modelKey)
                }
            }
            is DownloadState.Paused -> {
                statusText.text = if (state.currentFileCount > 1) {
                    getString(
                        R.string.model_status_paused_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        formatBytes(state.currentFileBytesDownloaded),
                        formatBytes(state.currentFileTotalBytes)
                    )
                } else {
                    getString(
                        R.string.model_status_paused,
                        formatBytes(state.bytesDownloaded),
                        formatBytes(state.totalBytes)
                    )
                }
                actionBtn.text = getString(R.string.model_btn_resume)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), modelKey, isResume = true)
                }
            }
            DownloadState.Done -> {
                val total = repo.getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
                statusText.text = getString(R.string.model_status_with_size_format, formatBytes(total))
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener {
                    confirmDelete(modelKey, displayName)
                }
            }
        }
    }

    private fun confirmDelete(modelKey: ModelKey, displayName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, displayName))
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch { repo.deleteDownload(modelKey) }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))

    // ========== PP-OCRv6 small/medium 切档（v6 特有，保留） ==========

    private fun updateV6TierVisibility() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val detDownloaded = com.moe.starflow.manga.engine.PPOcrModelFiles.isV6MediumDownloaded(requireContext(), "det")
        val recDownloaded = com.moe.starflow.manga.engine.PPOcrModelFiles.isV6MediumDownloaded(requireContext(), "rec")
        val mediumAvailable = detDownloaded && recDownloaded

        mediumRadio.visibility = if (mediumAvailable) android.view.View.VISIBLE else android.view.View.GONE

        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"
        smallRadio.isChecked = currentTier == "small"
        if (mediumAvailable) {
            mediumRadio.isChecked = currentTier == "medium"
        } else if (currentTier == "medium") {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
            smallRadio.isChecked = true
            android.widget.Toast.makeText(requireContext(), "medium 模型不完整，已自动切回 small", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupV6TierSwitching() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"

        smallRadio.isChecked = currentTier == "small"
        mediumRadio.isChecked = currentTier == "medium"

        smallRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
                mediumRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to small")
                android.widget.Toast.makeText(requireContext(), "PP-OCRv6 已切换到 small 模型", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        mediumRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "medium").commit()
                smallRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to medium")
                android.widget.Toast.makeText(requireContext(), "PP-OCRv6 已切换到 medium 模型", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== 超分（SR）Tab ==========

    /** 顶部 Tab：OCR / 超分。切 Tab 只切两个 ScrollView 的可见性，不重建任何内容。 */
    private fun setupTabs() {
        val tabs = rootView.findViewById<TabLayout>(R.id.model_tabs)
        val ocrScroll = rootView.findViewById<View>(R.id.ocr_scroll)
        val srScroll = rootView.findViewById<View>(R.id.sr_scroll)
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText(R.string.model_tab_ocr))
        tabs.addTab(tabs.newTab().setText(R.string.model_tab_sr))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val isSr = tab.position == 1
                ocrScroll.visibility = if (isSr) View.GONE else View.VISIBLE
                srScroll.visibility = if (isSr) View.VISIBLE else View.GONE
                // 切到超分时按磁盘重算一次状态（用户可能刚在别的入口删过模型）
                if (isSr) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        for (row in srRows) repo.refreshFromDisk(row.modelKey)
                    }
                    refreshSrSelection()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    /**
     * 绑定超分组的事件（行本身来自 XML，与 OCR 页同一套 include）。
     *
     * - 点**组标题** = 选中该族**推荐档**（各族第一行）；与 OCR「点组标题选引擎」同义
     * - 点**行** = 选中该行模型
     * - 行内「🔗 浏览器」按钮的接线由 `setupBrowserDownloadButtons()` 统一处理（同 OCR）
     */
    private fun bindSrGroups() {
        for (family in srFamilies) {
            rootView.findViewById<View>(family.titleId).setOnClickListener {
                family.rows.firstOrNull()?.let { selectSrModel(it.modelKey) }
            }
            for (row in family.rows) {
                rootView.findViewById<View>(row.rowRootId).setOnClickListener {
                    selectSrModel(row.modelKey)
                }
            }
        }
        refreshSrSelection()
    }

    /** 选中某个超分模型（写 prefs + 刷新「当前使用」+ 提示） */
    private fun selectSrModel(key: ModelKey) {
        val prefs = CustomPreference.getInstance(requireContext()).getSharedPreferences()
        SrModelManager.setActive(prefs, key)
        refreshSrSelection()
        UiUtils.showToast(
            requireContext(),
            getString(R.string.sr_active_switched, getString(SrModelManager.nameResOf(key))),
            isShort = true
        )
        LogCollector.d(TAG, "SR active model -> $key")
    }

    /**
     * 刷新各族的「当前使用」标记 **+ 每一行的等级选中圈**（与 OCR 页 `*_group_selected` 同一套）。
     *
     * ⚠️ **每个族 / 每一行都要显式赋值 VISIBLE/GONE / isChecked** —— 只写"命中的那个"会把上一轮的
     * 状态留在屏幕上（配置页那批开关踩过同一个坑；模型页在这种"部分更新"下的症状是
     * 「勾看着还在旧档上」，而它又不崩不报错，极难归因）。
     *
     * 两级标记分工（用户口径：组内要能看出选了哪一档）：
     * - 族标题上的「当前使用」= 当前模型属于**这一族**
     * - 行内 RadioButton = 当前模型就是**这一档**
     */
    private fun refreshSrSelection() {
        val prefs = CustomPreference.getInstance(requireContext()).getSharedPreferences()
        val active = SrModelManager.getActiveKey(prefs)
        for (family in srFamilies) {
            val owned = family.rows.any { it.modelKey == active }
            rootView.findViewById<View>(family.selectedId).visibility =
                if (owned) View.VISIBLE else View.GONE
            for (row in family.rows) {
                rootView.findViewById<RadioButton>(row.radioId).isChecked = row.modelKey == active
            }
        }
    }

    /** 从别处回到本页（用户在别处换了模型 / 删了模型文件）→ 选中态必须重读，不能只信 onCreateView 那次。 */
    override fun onResume() {
        super.onResume()
        refreshSrSelection()
        // 顺便按磁盘重算一次超分行的状态（`refreshFromDisk` 是 suspend，只能在协程里调）
        val tabs = rootView.findViewById<TabLayout>(R.id.model_tabs) ?: return
        if (tabs.selectedTabPosition != 1) return
        viewLifecycleOwner.lifecycleScope.launch {
            for (row in srRows) repo.refreshFromDisk(row.modelKey)
            refreshSrSelection()
        }
    }

    /** 跳转到超分模型页（供阅读器调试面板等入口使用） */
    fun selectSrTab() {
        rootView.findViewById<TabLayout>(R.id.model_tabs)?.getTabAt(1)?.select()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
