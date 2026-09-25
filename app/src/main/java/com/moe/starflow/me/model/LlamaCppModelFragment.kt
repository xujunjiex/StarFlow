package com.moe.starflow.me.model

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
 *  - **内置模型**卡片组：Hy-MT2 1.8B 1.25-bit，仍走既有下载流水线（ModelKey.HY_MT2_GROUP），
 *    可下载/暂停/继续/取消/删除，下载完成后可激活；
 *  - **导入的模型**列表：用户从本地 SAF 选择的任意 .gguf（允许激活/改参数/删除）；
 *  - **添加模型 = 直接导入本地 GGUF**（不做预设列表下载）。
 *
 * 所有状态以 `LlamaCppModelStore.models`（清单 + 磁盘检查）与
 * `ModelDownloadRepository`（内置模型下载状态）为真值，本页只负责渲染与转发操作。
 */
class LlamaCppModelFragment : Fragment() {

    private var _binding: FragmentLlamacppModelBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: ModelDownloadRepository
    private lateinit var prefs: CustomPreference
    private var importJob: Job? = null

    private val builtinKey = ModelKey.HY_MT2_GROUP

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

        binding.introText.text = getString(R.string.llamacpp_intro_content)
        binding.btnAddModel.setOnClickListener { pickLocalModel() }

        if (!prefs.getBoolean("Read_LlamaCpp_Introduce", false)) showIntro()

        viewLifecycleOwner.lifecycleScope.launch {
            // 清单一变就重绘；同时把内置模型的磁盘状态刷新一遍（可能被外部删除）
            LlamaCppModelStore.models.collectLatest { renderAll(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repo.refreshFromDisk(builtinKey)
            repo.observe().collectLatest { renderAll(LlamaCppModelStore.models.value) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        importJob?.cancel()
        _binding = null
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private fun renderAll(models: List<LlamaCppModel>) {
        val b = _binding ?: return
        val activeId = LlamaCppModelStore.activeId.value

        b.builtinContainer.removeAllViews()
        models.filter { it.source == LlamaCppModelSource.BUILTIN }.forEach { m ->
            b.builtinContainer.addView(buildRow(m, activeId))
        }

        val imported = models.filter { it.source == LlamaCppModelSource.IMPORTED }
        b.importedEmpty.visibility = if (imported.isEmpty()) View.VISIBLE else View.GONE
        b.importedContainer.removeAllViews()
        imported.forEach { m -> b.importedContainer.addView(buildRow(m, activeId)) }
    }

    private fun buildRow(m: LlamaCppModel, activeId: String?): View {
        val row = ItemLlamacppModelRowBinding.inflate(layoutInflater, null, false)
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

        row.rowParams.setOnClickListener { showParamsDialog(m) }
        row.rowDelete.setOnClickListener { confirmDelete(m) }

        if (m.source == LlamaCppModelSource.BUILTIN) {
            // 内置模型：下载控制（状态来自下载流水线）
            val state = repo.getState(builtinKey)
            row.rowStatus.visibility = View.VISIBLE
            row.rowProgress.visibility = if (state is DownloadState.Running || state is DownloadState.Paused) View.VISIBLE else View.GONE
            when (state) {
                DownloadState.Idle, DownloadState.Done -> {
                    row.rowDownload.visibility = if (missing) View.VISIBLE else View.GONE
                    row.rowDelete.visibility = if (!missing) View.VISIBLE else View.GONE
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
                ModelDownloadService.startDownload(requireContext(), builtinKey, isResume = false)
            }
            row.rowPause.setOnClickListener { ModelDownloadService.pauseDownload(requireContext(), builtinKey) }
            row.rowResume.setOnClickListener {
                ModelDownloadService.startDownload(requireContext(), builtinKey, isResume = true)
            }
            row.rowCancel.setOnClickListener { ModelDownloadService.cancelDownload(requireContext(), builtinKey) }
        } else {
            row.rowStatus.visibility = View.GONE
            row.rowProgress.visibility = View.GONE
        }
        return row.root
    }

    // ───────────────────────── 导入 ─────────────────────────

    private fun pickLocalModel() {
        // .gguf 无标准 MIME，给 */*；真正的校验在 LlamaCppImporter（扩展名 + GGUF 文件头）
        runCatching { openDocumentLauncher.launch(arrayOf("*/*")) }
            .onFailure { UiUtils.showToast(requireContext(), getString(R.string.llamacpp_import_failed, it.message ?: "")) }
    }

    private fun startImport(uri: Uri) {
        val b = _binding ?: return
        b.importProgressBox.visibility = View.VISIBLE
        b.importProgress.progress = 0
        b.importProgressText.text = getString(R.string.llamacpp_import_started)
        b.btnAddModel.isEnabled = false

        importJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = LlamaCppImporter.import(requireContext(), uri) { pct ->
                val bb = _binding ?: return@import
                bb.importProgress.progress = pct
                bb.importProgressText.text = getString(R.string.llamacpp_importing, pct)
            }
            val bb = _binding ?: return@launch
            bb.importProgressBox.visibility = View.GONE
            bb.btnAddModel.isEnabled = true
            val ctx = context ?: return@launch
            when (result) {
                is LlamaCppImporter.ImportResult.Success -> {
                    LogCollector.d(TAG, "导入成功：${result.model.displayName} retag=${result.retagged}")
                    UiUtils.showToast(ctx, getString(R.string.llamacpp_import_completed, result.model.displayName))
                }
                is LlamaCppImporter.ImportResult.Duplicate ->
                    UiUtils.showToast(ctx, getString(R.string.llamacpp_import_duplicate, result.existing.displayName))
                is LlamaCppImporter.ImportResult.Failed ->
                    UiUtils.showToast(ctx, getString(R.string.llamacpp_import_failed, result.reason))
            }
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
                        repo.deleteDownload(builtinKey)
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
        )
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
