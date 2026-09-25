package com.moe.starflow.me.model

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.databinding.DialogLlamacppParamsBinding
import com.moe.starflow.databinding.FragmentLlamacppModelBinding
import com.moe.starflow.databinding.ItemLlamacppModelRowBinding
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.download.ModelKey
import com.moe.starflow.llamacpp.LlamaCppImporter
import com.moe.starflow.llamacpp.LlamaCppModel
import com.moe.starflow.llamacpp.LlamaCppModelSource
import com.moe.starflow.llamacpp.LlamaCppModelStore
import com.moe.starflow.llamacpp.LlamaCppParams
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * LlamaCpp 模型管理页。
 *
 * 结构（用户 2026-09 定稿）：
 *  - **内置模型**卡片组：Hy-MT2 1.8B（1.25-bit / Q4_K_M 两张卡），仍走既有下载流水线
 *    （ModelKey.HY_MT2_GROUP / ModelKey.HY_MT2_Q4_KM），可下载/暂停/继续/取消/删除，
 *    下载完成后点卡片激活；
 *  - **导入的模型**列表：用户从本地 SAF 选择的任意 .gguf（允许激活/改参数/删除）；
 *  - **添加模型 = 直接导入本地 GGUF**（不做预设列表下载）。
 *
 * 所有状态以 `LlamaCppModelStore.models`（清单 + 磁盘检查）与
 * `ModelDownloadRepository`（内置模型下载状态）为真值，本页只负责渲染与转发操作。
 *
 * ⚠️ **卡片间距**：行模板 `item_llamacpp_model_row.xml` 用 `layout_marginBottom` 提供卡片间距，
 * 所以 inflate 必须走 [inflateModelRow]（带父容器）——`null` parent 会丢掉 margin，
 * 相邻卡片会贴在一起像重叠。
 */
class LlamaCppModelFragment : Fragment() {

    private var _binding: FragmentLlamacppModelBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: ModelDownloadRepository
    private lateinit var prefs: CustomPreference

    // SAF：选一个本地 .gguf（.gguf 没有标准 MIME，MIME 过滤传通配，选完按扩展名 + GGUF 文件头校验）
    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) startImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLlamacppModelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = ModelDownloadRepository.getInstance(requireContext())
        LlamaCppModelStore.init(requireContext())
        LlamaCppModelStore.ensureLoadedSync()

        binding.btnIntroLink.setOnClickListener { showIntro() }
        binding.btnAddModel.setOnClickListener { pickLocalModel() }
        binding.btnCancelImport.setOnClickListener { LlamaCppImporter.cancel() }

        // 导入进度来自应用级任务：离开页面再回来也能接着显示
        viewLifecycleOwner.lifecycleScope.launch {
            LlamaCppImporter.progress.collectLatest { renderImportProgress(it) }
        }

        if (!prefs.getBoolean("Read_LlamaCpp_Introduce", false)) showIntro()

        viewLifecycleOwner.lifecycleScope.launch {
            // 清单一变就重绘；同时把内置模型的磁盘状态刷新一遍（可能被外部删除）
            LlamaCppModelStore.models.collectLatest {
                // 型号列表可能被删/加，activeId 也可能一起变
                renderAll(it, LlamaCppModelStore.activeId.value)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // ⚠️ 必须单独观察 activeId：切换模型只改它，不重绘的话 RadioButton 会出现「多个都选中」
            LlamaCppModelStore.activeId.collectLatest { activeId ->
                renderAll(LlamaCppModelStore.models.value, activeId)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // 内置模型可能被外部删除（或刚下载完）→ 把**每个**内置条目对应的下载 key 都刷新一遍
            LlamaCppModelStore.builtinModelKeys().forEach { repo.refreshFromDisk(it) }
            repo.observe().collectLatest { renderAll(LlamaCppModelStore.models.value, LlamaCppModelStore.activeId.value) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ⚠️ 这里**不**取消导入：导入跑在 LlamaCppImporter 的应用级作用域里，
        //    离开页面只是停止观察进度（1~3GB 的拷贝不该因为退个页面就白拷）
        _binding = null
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private fun renderAll(models: List<LlamaCppModel>, activeId: String?) {
        val b = _binding ?: return

        val builtins = models.filter { it.source == LlamaCppModelSource.BUILTIN }
        fillRows(b.builtinContainer, builtins) { buildRow(it, activeId, b.builtinContainer) }

        val imported = models.filter { it.source == LlamaCppModelSource.IMPORTED }
        b.importedEmpty.visibility = if (imported.isEmpty()) View.VISIBLE else View.GONE
        fillRows(b.importedContainer, imported) { buildRow(it, activeId, b.importedContainer) }
    }

    /**
     * 往容器里铺卡片：**最后一张不留 `layout_marginBottom`**（区块间距由下一段标题的 marginTop 负责），
     * 卡片之间的间距取自行模板 XML 里的 `layout_marginBottom`（必须经 [inflateModelRow] 带父容器 inflate 才拿得到）。
     */
    private fun fillRows(
        container: LinearLayout,
        models: List<LlamaCppModel>,
        build: (LlamaCppModel) -> View,
    ) {
        container.removeAllViews()
        models.forEachIndexed { index, m ->
            val v = build(m)
            if (index == models.lastIndex) {
                (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            container.addView(v)
        }
    }

    private fun buildRow(m: LlamaCppModel, activeId: String?, parent: ViewGroup): View {
        val row = inflateModelRow(layoutInflater, parent)
        val ctx = requireContext()

        row.rowName.text = m.displayName
        val missing = LlamaCppModelStore.fileMissing(m)
        val sourceLabel = getString(
            if (m.source == LlamaCppModelSource.BUILTIN) R.string.llamacpp_source_builtin
            else R.string.llamacpp_source_imported
        )
        row.rowMeta.text = getString(
            R.string.llamacpp_meta_format,
            formatBytes(if (m.sizeBytes > 0) m.sizeBytes else m.absoluteFile.length()),
            sourceLabel,
        )

        // 角标：使用中 / 文件丢失 / 内置
        val badge = when {
            m.id == activeId -> getString(R.string.llamacpp_badge_active)
            missing -> getString(R.string.llamacpp_badge_missing)
            m.source == LlamaCppModelSource.BUILTIN -> getString(R.string.llamacpp_badge_builtin)
            else -> null
        }
        row.rowBadge.visibility = if (badge == null) View.GONE else View.VISIBLE
        if (badge != null) {
            row.rowBadge.text = badge
            row.rowBadge.setTextColor(
                if (m.id == activeId) ctx.getColor(R.color.success) else ctx.getColor(R.color.text_tertiary)
            )
        }

        row.rowActive.isChecked = (m.id == activeId)
        // 点卡片 = 设为当前使用（不再单独放按钮）；已是当前/文件缺失时只提示
        val activate = View.OnClickListener {
            if (missing) {
                UiUtils.showToast(ctx, getString(R.string.llamacpp_model_file_missing, m.displayName), isShort = true)
            } else if (m.id != activeId) {
                LlamaCppModelStore.setActive(m.id)
                UiUtils.showToast(ctx, getString(R.string.llamacpp_active_set, m.displayName), isShort = true)
            }
        }
        row.root.setOnClickListener(activate)
        row.rowActive.setOnClickListener(activate)

        // 按钮右对齐、按内容宽度；同一时刻最多 3 个
        row.rowDelete.visibility = if (m.source == LlamaCppModelSource.BUILTIN && missing) View.GONE else View.VISIBLE
        row.rowDelete.setOnClickListener { confirmDelete(m) }

        // 内置模型可以有多个（1.25-bit / Q4_K_M）：每行用**自己的**下载 key
        val key = builtinKeyOf(m)
        val state = key?.let { repo.getState(it) }
        if (key != null && state != null) {
            // 内置模型：下载控制（状态来自下载流水线）
            row.rowStatus.visibility = View.VISIBLE
            row.rowProgress.visibility = if (state is DownloadState.Running || state is DownloadState.Paused) View.VISIBLE else View.GONE
            when (state) {
                DownloadState.Idle, DownloadState.Done -> {
                    row.rowDownload.visibility = if (missing) View.VISIBLE else View.GONE
                    row.rowStatus.text = getString(
                        if (missing) R.string.model_status_idle else R.string.model_status_done
                    )
                    row.rowProgress.progress = if (missing) 0 else 100
                }
                is DownloadState.Running -> {
                    row.rowPause.visibility = View.VISIBLE
                    row.rowCancel.visibility = View.VISIBLE
                    row.rowProgress.progress = state.currentFileProgress
                    row.rowStatus.text = getString(R.string.model_status_running_single, state.currentFileProgress)
                }
                is DownloadState.Paused -> {
                    row.rowResume.visibility = View.VISIBLE
                    row.rowCancel.visibility = View.VISIBLE
                    row.rowStatus.text = getString(R.string.model_status_paused, formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes))
                }
                is DownloadState.Partial -> {
                    row.rowResume.visibility = View.VISIBLE
                    row.rowStatus.text = getString(R.string.model_status_partial, formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes))
                }
            }
            row.rowDownload.setOnClickListener {
                ModelDownloadService.startDownload(requireContext(), key, isResume = false)
            }
            row.rowPause.setOnClickListener { ModelDownloadService.pauseDownload(requireContext(), key) }
            row.rowResume.setOnClickListener {
                ModelDownloadService.startDownload(requireContext(), key, isResume = true)
            }
            row.rowCancel.setOnClickListener { ModelDownloadService.cancelDownload(requireContext(), key) }
        } else {
            row.rowStatus.visibility = View.GONE
            row.rowProgress.visibility = View.GONE
        }

        // 下载/续传进行中只放 [暂停/继续][取消]；其余状态才有 [模型设置]
        val busy = state is DownloadState.Running || state is DownloadState.Paused || state is DownloadState.Partial
        row.rowParams.visibility = if (busy) View.GONE else View.VISIBLE
        row.rowParams.setOnClickListener { showParamsDialog(m) }
        return row.root
    }

    /** 内置模型对应的下载 key（`builtinModelKey` 是枚举名，防止清单被改坏时崩）。 */
    private fun builtinKeyOf(m: LlamaCppModel): ModelKey? =
        m.builtinModelKey?.let { name -> runCatching { ModelKey.valueOf(name) }.getOrNull() }

    // ───────────────────────── 导入 ─────────────────────────

    private fun pickLocalModel() {
        // .gguf 无标准 MIME，给 */*；真正的校验在 LlamaCppImporter（扩展名 + GGUF 文件头）
        runCatching { openDocumentLauncher.launch(arrayOf("*/*")) }
            .onFailure { UiUtils.showToast(requireContext(), getString(R.string.llamacpp_import_failed, it.message ?: "")) }
    }

    /**
     * 启动导入。**不在这里等结果**：导入跑在应用级作用域里（LlamaCppImporter），
     * 离开页面不会中断；本页只是观察 [LlamaCppImporter.progress] 显示进度。
     */
    private fun startImport(uri: Uri) {
        if (!LlamaCppImporter.start(requireContext(), uri)) {
            UiUtils.showToast(requireContext(), getString(R.string.llamacpp_import_running), isShort = true)
        }
    }

    /** 渲染导入进度（回到页面时如果后台仍在导入，会立刻接着显示）。 */
    private fun renderImportProgress(p: LlamaCppImporter.ImportProgress?) {
        val b = _binding ?: return
        if (p == null) {
            b.importProgressBox.visibility = View.GONE
            b.btnAddModel.isEnabled = true
        } else {
            b.importProgressBox.visibility = View.VISIBLE
            b.importProgress.progress = p.percent
            b.importProgressText.text =
                p.fileName + "\n" + getString(R.string.llamacpp_importing, p.percent)
            b.btnAddModel.isEnabled = false
        }
    }

    // ───────────────────────── 删除 ─────────────────────────

    private fun confirmDelete(m: LlamaCppModel) {
        val isBuiltin = m.source == LlamaCppModelSource.BUILTIN
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.llamacpp_delete_title)
            .setMessage(
                getString(
                    if (isBuiltin) R.string.llamacpp_delete_builtin_message else R.string.llamacpp_delete_message,
                    m.displayName,
                )
            )
            .setNegativeButton(R.string.user_cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (isBuiltin) {
                        // 内置：只删下载下来的文件，条目保留（之后可重新下载）
                        builtinKeyOf(m)?.let { repo.deleteDownload(it) }
                    } else {
                        runCatching {
                            m.absoluteFile.delete()
                            com.moe.starflow.llamacpp.LlamaCppPaths.retagMarker(m.absoluteFile).delete()
                        }
                        LlamaCppModelStore.remove(m.id)
                    }
                    UiUtils.showToast(requireContext(), getString(R.string.llamacpp_delete_done), isShort = true)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    // ───────────────────────── 参数 ─────────────────────────

    private fun showParamsDialog(m: LlamaCppModel) {
        val b = DialogLlamacppParamsBinding.inflate(layoutInflater)
        val isBuiltin = m.source == LlamaCppModelSource.BUILTIN
        val p = m.params

        b.noteText.text = getString(
            if (isBuiltin) R.string.llamacpp_params_builtin_note else R.string.llamacpp_params_generic_note
        ) + "\n\n" + getString(R.string.llamacpp_params_effect_hint)
        b.systemPromptBox.visibility = if (isBuiltin) View.GONE else View.VISIBLE
        b.thinkingBox.visibility = if (isBuiltin) View.GONE else View.VISIBLE
        b.thinkingHint.visibility = if (isBuiltin) View.GONE else View.VISIBLE

        b.promptEdit.setText(p.promptTemplate)
        b.systemPromptEdit.setText(p.systemPrompt)
        b.tempEdit.setText(p.temperature.toString())
        b.topPEdit.setText(p.topP.toString())
        b.topKEdit.setText(p.topK.toString())
        b.repPenaltyEdit.setText(p.repetitionPenalty.toString())
        b.maxTokensEdit.setText(p.maxTokens.toString())
        b.threadsEdit.setText(p.threads.toString())
        b.batchThreadsEdit.setText(p.batchThreads.toString())
        b.switchThinking.isChecked = p.enableThinking

        val contextOptions = arrayOf("1024", "2048", "4096", "8192")
        b.contextSelect.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, contextOptions)
        )
        b.contextSelect.setText(p.contextSize.toString(), false)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.llamacpp_params_title, m.displayName))
            .setView(b.root)
            .setCancelable(true)
            .create()

        b.btnHelp.setOnClickListener { showParamsHelp() }
        b.btnReset.setOnClickListener {
            val d = LlamaCppParams.forSource(m.source)
            b.promptEdit.setText(d.promptTemplate)
            b.systemPromptEdit.setText(d.systemPrompt)
            b.tempEdit.setText(d.temperature.toString())
            b.topPEdit.setText(d.topP.toString())
            b.topKEdit.setText(d.topK.toString())
            b.repPenaltyEdit.setText(d.repetitionPenalty.toString())
            b.maxTokensEdit.setText(d.maxTokens.toString())
            b.threadsEdit.setText(d.threads.toString())
            b.batchThreadsEdit.setText(d.batchThreads.toString())
            b.switchThinking.isChecked = d.enableThinking
            b.contextSelect.setText(d.contextSize.toString(), false)
        }
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnSave.setOnClickListener {
            val defaults = LlamaCppParams.forSource(m.source)
            val updated = p.copy(
                promptTemplate = b.promptEdit.text?.toString()?.takeIf { it.isNotBlank() } ?: defaults.promptTemplate,
                systemPrompt = b.systemPromptEdit.text?.toString() ?: defaults.systemPrompt,
                temperature = b.tempEdit.text?.toString()?.toFloatOrNull()?.coerceIn(0f, 2f) ?: defaults.temperature,
                topP = b.topPEdit.text?.toString()?.toFloatOrNull()?.coerceIn(0.01f, 1f) ?: defaults.topP,
                topK = b.topKEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 200) ?: defaults.topK,
                repetitionPenalty = b.repPenaltyEdit.text?.toString()?.toFloatOrNull()?.coerceIn(0.5f, 2f)
                    ?: defaults.repetitionPenalty,
                maxTokens = b.maxTokensEdit.text?.toString()?.toIntOrNull()?.coerceIn(16, 8192) ?: defaults.maxTokens,
                contextSize = b.contextSelect.text?.toString()?.toIntOrNull() ?: defaults.contextSize,
                threads = b.threadsEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 16) ?: defaults.threads,
                batchThreads = b.batchThreadsEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 16) ?: defaults.batchThreads,
                enableThinking = b.switchThinking.isChecked,
            )
            LlamaCppModelStore.updateParams(m.id, updated)
            dialog.dismiss()
            UiUtils.showToast(requireContext(), getString(R.string.llamacpp_params_saved), isShort = true)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    /** 参数说明：把原来老详情页里每个参数右侧问号弹出的说明合并成一个弹窗（新页面用按钮入口）。 */
    private fun showParamsHelp() {
        val items = listOf(
            R.string.llamacpp_prompt_label to R.string.llamacpp_help_prompt,
            R.string.llamacpp_threads_label to R.string.llamacpp_help_threads,
            R.string.llamacpp_batch_threads_label to R.string.llamacpp_help_batch_threads,
            R.string.llamacpp_context_label to R.string.llamacpp_help_context,
            R.string.llamacpp_temp_label to R.string.llamacpp_help_temperature,
            R.string.llamacpp_top_p_label to R.string.llamacpp_help_top_p,
            R.string.llamacpp_top_k_label to R.string.llamacpp_help_top_k,
            R.string.llamacpp_rep_penalty_label to R.string.llamacpp_help_rep_penalty,
            R.string.llamacpp_max_tokens_label to R.string.llamacpp_help_max_tokens,
        )
        // 标签 + 说明成对列出，中间空行分隔；不带任何 CJK 标点，中英文都适用
        val message = items.joinToString("\n\n") { (label, help) ->
            getString(label) + "\n" + getString(help)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.llamacpp_params_help)
            .setMessage(message)
            .setPositiveButton(R.string.user_known, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showIntro() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.llamacpp_intro_title)
            .setMessage(R.string.llamacpp_intro_content)
            .setCancelable(false)
            .setPositiveButton(R.string.user_known, null)
            .setNeutralButton(R.string.introduce_not_show_again) { _, _ ->
                prefs.setBoolean("Read_LlamaCpp_Introduce", true)
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }

    companion object {
        private const val TAG = "LlamaCppModelFragment"
    }
}

/**
 * inflate 一张模型卡片行。
 *
 * ⚠️ `parent` **故意不可空**：`inflate(inflater, null, false)` 时 XML 里的 `layout_*` 属性
 * 一个都不会被解析（LayoutParams 只在有父容器时才生成），随后 `addView` 会让 LinearLayout
 * 补一份 `margin = 0` 的默认 params —— 结果是 **相邻卡片零间距**，20dp 圆角贴在一起，
 * 看起来像卡片互相重叠（2026-09 用户反馈的故障）。
 * 带父容器 inflate（`attachToRoot = false`）即可正确拿到 `layout_marginBottom` 的卡片间距。
 */
internal fun inflateModelRow(
    inflater: LayoutInflater,
    parent: ViewGroup,
): ItemLlamacppModelRowBinding = ItemLlamacppModelRowBinding.inflate(inflater, parent, false)
