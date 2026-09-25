package com.moe.starflow.novel.shelf

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.databinding.FragmentNovelShelfBinding
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelImportEvent
import com.moe.starflow.novel.data.NovelImportFailureReason
import com.moe.starflow.novel.data.NovelImportManager
import com.moe.starflow.novel.data.NovelImporter
import com.moe.starflow.novel.data.NovelStorageDir
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.reader.NovelReaderActivity
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 小说书架。
 *
 * 交互与漫画书架对齐（网格 / 导入 / 下拉刷新 / 长按管理），但**导入语义不同**：
 * 文件夹导入时「夹内每个文本文件 = 一部书」，漫画那边是「整个夹 = 一部」。
 *
 * ⚠️ 本 Fragment 同时被两种方式承载（`ImportMangaFragment` 内嵌切换），所以
 * **不要依赖 Activity 是某个具体类型**，也不要在 `onCreate` 里做需要宿主的事。
 */
class NovelShelfFragment : Fragment() {

    private companion object {
        const val TAG = "NovelShelfFragment"
        const val GRID_SPAN = 3
    }

    private var _binding: FragmentNovelShelfBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: NovelShelfAdapter

    /** 最近一次渲染的清单（长按菜单要拿完整条目）。 */
    private var rendered: List<ImportedNovel> = emptyList()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) context?.let { NovelImportManager.importFiles(it, uris) }
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) context?.let { NovelImportManager.importDirectory(it, uri) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNovelShelfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NovelShelfAdapter(
            onClick = { novel -> startActivity(NovelReaderActivity.intent(requireContext(), novel.id)) },
            onCancelImport = { id -> cancelImport(id) },
            onLongClick = { novel -> showManageDialog(novel) },
        )
        binding.novelShelfList.layoutManager = GridLayoutManager(requireContext(), GRID_SPAN)
        binding.novelShelfList.adapter = adapter
        binding.novelShelfRefresh.setOnRefreshListener { refresh() }
        binding.novelShelfImport.setOnClickListener { showImportDialog() }

        observeImportTasks()
        refresh()
    }

    // ===== 导入 =====

    private fun showImportDialog() {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_import_manga, null, false)
        // 与漫画共用同一份布局，各自填自己的选项文案（措辞对齐 koto）
        view.findViewById<TextView>(R.id.btn_import_file).setText(R.string.import_manga_option_files)
        view.findViewById<TextView>(R.id.btn_import_single_dir)
            .setText(R.string.import_option_dir_novel_single)
        view.findViewById<TextView>(R.id.tv_import_file_desc).setText(R.string.import_desc_files_novel)
        view.findViewById<TextView>(R.id.tv_import_dir_desc).setText(R.string.import_desc_dir_novel)
        view.findViewById<TextView>(R.id.tv_storage_hint).text =
            getString(R.string.import_storage_hint, NovelStorageDir.root(requireContext()).absolutePath)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.novel_shelf_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<TextView>(R.id.btn_import_file).setOnClickListener {
            // ⚠️ 传 `*/*` 而不是 text/plain：不同 provider 给 txt 报的 MIME 不一致
            // （text/plain / application/octet-stream / application/x-mobipocket…），
            // 限定 MIME 会让部分文件在选择器里直接灰掉、根本选不中。格式由导入器判定。
            pickFiles.launch(arrayOf("*/*"))
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_import_single_dir).setOnClickListener {
            pickDir.launch(null)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun cancelImport(id: Long) {
        // 返回 false = 该任务已经不在了（已完成/已失败），此时说「已取消」是错的
        val cancelled = NovelImportManager.cancel(id)
        UiUtils.showToast(
            requireContext(),
            getString(if (cancelled) R.string.import_cancelled else R.string.import_already_done),
            true,
        )
    }

    /**
     * 观察导入任务。
     *
     * ⚠️ 只取 `format == NOVEL` 的任务：漫画与小说共用 [com.moe.starflow.mangaimport.data.ImportTask]
     * 这个数据结构，不过滤会让两边的占位卡片串台。
     */
    private fun observeImportTasks() {
        viewLifecycleOwner.lifecycleScope.launch {
            NovelImportManager.tasks.collect { tasks ->
                val placeholders = tasks
                    .filter { it.format == ImportFormat.NOVEL }
                    .map { it.toNovelPlaceholder() }
                render(placeholders + rendered.filterNot { r -> placeholders.any { it.id == r.id } })
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            NovelImportManager.events.collect { drainAndShowEvents() }
        }
    }

    private suspend fun drainAndShowEvents() {
        val events = NovelImportManager.drainEvents()
        if (events.isEmpty() || !isAdded || _binding == null) return
        val event = events.first()
        withContext(Dispatchers.Main) {
            if (!isAdded || _binding == null) return@withContext
            val (title, message) = event.toDialogText()
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show()
            // 事件是攒着取的，一次可能有多条；刷完再取一轮
            if (events.size > 1) viewLifecycleOwner.lifecycleScope.launch { drainAndShowEvents() }
        }
    }

    private fun NovelImportEvent.toDialogText(): Pair<String, String> {
        val failTitle = getString(R.string.novel_import_failed)
        return when (this) {
            is NovelImportEvent.NoChapters -> failTitle to getString(R.string.novel_import_no_chapters)
            is NovelImportEvent.WrongFormat -> failTitle to getString(
                if (isManga) R.string.novel_import_wrong_format_manga else R.string.novel_import_wrong_format_text
            )
            is NovelImportEvent.Failed -> failTitle to getString(
                when (reason) {
                    NovelImportFailureReason.NOT_ARCHIVE -> R.string.novel_import_reason_not_archive
                    NovelImportFailureReason.UNREADABLE -> R.string.novel_import_reason_unreadable
                    NovelImportFailureReason.NO_TEXT_CHAPTER -> R.string.novel_import_reason_no_text_chapter
                    NovelImportFailureReason.EMPTY -> R.string.novel_import_reason_empty
                    NovelImportFailureReason.ENCRYPTED -> R.string.novel_import_reason_encrypted
                    NovelImportFailureReason.UNKNOWN -> R.string.novel_import_reason_unknown
                }
            )
        }
    }

    // ===== 渲染 =====

    private fun refresh() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                NovelStore.load(ctx).map { n ->
                    // 「文件丢失」是**按当下磁盘状态实时推导**的瞬态标记，不持久化
                    val path = n.localRoot
                    n.copy(lost = path.isNotEmpty() && !File(path).exists())
                }
            }
            if (!isAdded || _binding == null) return@launch
            val placeholders = NovelImportManager.tasks.value
                .filter { it.format == ImportFormat.NOVEL }
                .map { it.toNovelPlaceholder() }
            render(placeholders + stored.filterNot { s -> placeholders.any { it.id == s.id } })
            binding.novelShelfRefresh.isRefreshing = false
        }
    }

    private fun render(list: List<ImportedNovel>) {
        rendered = list
        adapter.submitList(list)
        binding.novelShelfEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ===== 管理 =====

    private fun showManageDialog(novel: ImportedNovel) {
        val items = arrayOf(
            getString(R.string.novel_manage_rename),
            getString(if (novel.lastReadPage > 0 || novel.lastReadChapter > 0) R.string.novel_manage_mark_unread else R.string.novel_manage_mark_read),
            getString(R.string.novel_manage_delete),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(novel.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameNovel(novel)
                    1 -> toggleRead(novel)
                    2 -> confirmDelete(novel)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameNovel(novel: ImportedNovel) {
        val input = EditText(requireContext()).apply {
            setText(novel.title)
            setSelection(text.length)
            hint = getString(R.string.novel_manage_rename)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.novel_manage_rename)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                NovelStore.update(requireContext(), novel.copy(title = name))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 标为已读 = 进度推到末尾；标为未读 = 清零。两者都只动进度字段。 */
    private fun toggleRead(novel: ImportedNovel) {
        val read = novel.lastReadPage > 0 || novel.lastReadChapter > 0
        val updated = if (read) {
            novel.copy(lastReadChapter = 0, lastReadParaIndex = 0, lastReadPage = 0)
        } else {
            novel.copy(
                lastReadChapter = (novel.chapterCount - 1).coerceAtLeast(0),
                lastReadParaIndex = Int.MAX_VALUE,
                lastReadPage = 0,
            )
        }
        NovelStore.update(requireContext(), updated)
        refresh()
    }

    private fun confirmDelete(novel: ImportedNovel) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.novel_manage_delete)
            .setMessage(getString(R.string.novel_delete_confirm, novel.title))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteNovel(novel) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删除一本书：清单、本地文件、译文记录三处都要清。
     *
     * ⚠️ 译文要按 (id, key) **成对**删：书籍 id 会被复用，只按 id 删可能误伤用户随后
     * 导入的、复用同一 id 的新书。
     */
    private fun deleteNovel(novel: ImportedNovel) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NovelStore.remove(ctx, novel.id)
                runCatching { NovelImporter.deleteBookFiles(ctx, novel.id) }
                runCatching {
                    TranslationHistoryDatabase.getInstance(ctx)
                        .novelParagraphTranslationDao()
                        .deleteForNovelScoped(novel.id, novel.translationKey)
                }.onFailure { LogCollector.w(TAG, "删除译文记录失败 id=${novel.id}", it) }
            }
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
