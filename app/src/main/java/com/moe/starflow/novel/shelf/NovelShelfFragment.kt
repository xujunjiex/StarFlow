package com.moe.starflow.novel.shelf

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.databinding.FragmentNovelShelfBinding
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.mangaimport.ui.DisplayOptionsSheet
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
 * 交互与漫画书架**逐项对齐**：网格/详细信息两种布局 + 显示选项（列数/排序）+ Koto 式多选
 * （长按进入、全选、已读/未读/删除/重命名）+ 下拉刷新 + 右下 FAB + 返回键退出多选。
 * 显示的卡片布局也直接共用漫画那两份 XML（见 [NovelShelfAdapter]）。
 *
 * 导入语义不同：文件夹导入时「**整个夹 = 一部小说**，夹内每个文本文件 = 一章」
 * （漫画那边是「整个夹 = 一部」，夹内是页）。
 *
 * ⚠️ 本 Fragment 被 `ImportMangaFragment` 内嵌承载（tab 切换），所以**不要依赖 Activity 是
 * 某个具体类型**，也不要在 `onCreate` 里做需要宿主的事。
 */
class NovelShelfFragment : Fragment() {

    private companion object {
        const val TAG = "NovelShelfFragment"

        /** ⚠️ 与漫画书架**同一份 prefs 与同一批键**：显示选项是全局的，两边改一处即一致。 */
        const val PREFS = "manga_import"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_GRID_SIZE = "grid_size"
        const val KEY_SORT_ADDED = "sort_by_added"
    }

    private var _binding: FragmentNovelShelfBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: NovelShelfAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortByAdded = false

    /** 最近一次渲染的清单（长按菜单要拿完整条目）。 */
    private var rendered: List<ImportedNovel> = emptyList()

    private lateinit var backCallback: OnBackPressedCallback

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) context?.let { NovelImportManager.importFiles(it, uris) }
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) context?.let { NovelImportManager.importDirectory(it, uri) }
    }

    // 换封面（单选时）：photo picker 选图 → 复制进 covers/ 并替换 coverPath
    private val pickCoverLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) changeCover(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNovelShelfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadDisplayPrefs()

        // 返回键：多选模式下先退出多选（与漫画书架一致）。⚠️ 必须在 showSelectionUi 之前建好，
        // 那里会写 backCallback.isEnabled
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.exitSelection()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        adapter = NovelShelfAdapter(
            displayMode = displayMode,
            onItemClick = { novel -> startActivity(NovelReaderActivity.intent(requireContext(), novel.id)) },
            onSelectionChanged = { mode, count -> showSelectionUi(mode, count) },
            onCancelImport = { id -> confirmCancelImport(id) },
        )
        binding.novelShelfList.adapter = adapter
        binding.novelShelfRefresh.setOnRefreshListener { refresh() }
        binding.novelShelfImport.setOnClickListener { showImportDialog() }

        // 顶部栏 / 多选操作栏
        binding.novelTvSettings.setOnClickListener {
            DisplayOptionsSheet(displayMode, gridSize, sortByAdded) { m, s, sa -> applyDisplay(m, s, sa) }
                .show(parentFragmentManager, DisplayOptionsSheet.TAG)
        }
        binding.novelTvCloseSelection.setOnClickListener { adapter.exitSelection() }
        binding.novelIvSelectAll.setOnClickListener { adapter.toggleSelectAll() }
        binding.novelActionRead.setOnClickListener { markSelected(read = true) }
        binding.novelActionUnread.setOnClickListener { markSelected(read = false) }
        binding.novelActionDelete.setOnClickListener { deleteSelected() }
        binding.novelActionRename.setOnClickListener { renameSelected() }
        binding.novelActionCover.setOnClickListener {
            pickCoverLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.novelActionDesc.setOnClickListener { editDesc() }
        showSelectionUi(false, 0)

        observeImportTasks()
        refresh()
    }

    /** 宿主（`ImportMangaFragment`）切走 tab 时调用：否则切回来顶部栏还在多选态、与列表对不上。 */
    fun exitSelection() {
        if (::adapter.isInitialized) adapter.exitSelection()
    }

    // ===== 显示选项（与漫画书架共用同一份 prefs） =====

    private fun loadDisplayPrefs() {
        val p = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        displayMode = runCatching { DisplayMode.valueOf(p.getString(KEY_DISPLAY_MODE, DisplayMode.DETAILED_LIST.name)!!) }
            .getOrDefault(DisplayMode.DETAILED_LIST)
        gridSize = p.getInt(KEY_GRID_SIZE, 3)
        sortByAdded = p.getBoolean(KEY_SORT_ADDED, false)
    }

    private fun applyDisplay(mode: DisplayMode, size: Int, sortAdded: Boolean) {
        displayMode = mode
        gridSize = size
        sortByAdded = sortAdded
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, mode.name)
            .putInt(KEY_GRID_SIZE, size)
            .putBoolean(KEY_SORT_ADDED, sortAdded)
            .apply()
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

    /** 取消导入前先确认：会删掉已复制的内容（与漫画书架同一套措辞与流程）。 */
    private fun confirmCancelImport(id: Long) {
        val title = NovelImportManager.tasks.value.firstOrNull { it.id == id }?.title ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_cancel_title)
            .setMessage(getString(R.string.import_cancel_msg, title))
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (!NovelImportManager.cancel(id)) {
                    // 点确认的瞬间刚好导入完成：不能报「已取消」（那是假话），如实提示已完成
                    UiUtils.showToast(requireContext(), getString(R.string.import_already_done), true)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
    }

    /**
     * 观察导入任务。
     *
     * ⚠️ 只取 `format == NOVEL` 的任务：漫画与小说共用 [com.moe.starflow.mangaimport.data.ImportTask]
     * 这个数据结构，不过滤会让两边的占位卡片串台。
     */
    private fun observeImportTasks() {
        viewLifecycleOwner.lifecycleScope.launch {
            NovelImportManager.tasks.collect { refresh() }
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
            val all = placeholders + stored.filterNot { s -> placeholders.any { it.id == s.id } }
            val list = if (sortByAdded) all.sortedByDescending { it.addedAt } else all.sortedBy { it.title }
            val cols = if (displayMode == DisplayMode.GRID) gridSize else 1
            // ⚠️ 只在列数变化时重建 LayoutManager：refresh 会被进度回调高频调用，
            // 每次换 LayoutManager 会把列表滚回顶部
            val lm = binding.novelShelfList.layoutManager
            if (lm !is GridLayoutManager || lm.spanCount != cols) {
                binding.novelShelfList.layoutManager = GridLayoutManager(ctx, cols)
            }
            adapter.setDisplayMode(displayMode)
            render(list)
            binding.novelShelfRefresh.isRefreshing = false
        }
    }

    private fun render(list: List<ImportedNovel>) {
        rendered = list
        adapter.submitList(list)
        binding.novelShelfEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ===== 多选（Koto 式，与漫画书架同一套 UI 两态） =====

    private fun showSelectionUi(mode: Boolean, count: Int) {
        backCallback.isEnabled = mode
        binding.novelTvTitle.visibility = if (mode) View.GONE else View.VISIBLE
        binding.novelTvSelectionCount.visibility = if (mode) View.VISIBLE else View.GONE
        binding.novelTvSettings.visibility = if (mode) View.GONE else View.VISIBLE
        binding.novelTvCloseSelection.visibility = if (mode) View.VISIBLE else View.GONE
        binding.novelIvSelectAll.visibility = if (mode) View.VISIBLE else View.GONE
        binding.novelSelectionActions.visibility = if (mode) View.VISIBLE else View.GONE
        val single = mode && count == 1
        binding.novelSelectionActionsSingle.visibility = if (single) View.VISIBLE else View.GONE
        binding.novelActionDivider.visibility = if (single) View.VISIBLE else View.GONE
        binding.novelActionCover.visibility = if (single) View.VISIBLE else View.GONE
        binding.novelActionDesc.visibility = if (single) View.VISIBLE else View.GONE
        binding.novelActionRename.visibility = if (single) View.VISIBLE else View.GONE
        if (mode) binding.novelTvSelectionCount.text = getString(R.string.import_selected_count, count)
    }

    private fun selectedNovels(): List<ImportedNovel> {
        val ids = adapter.selectedIds()
        return rendered.filter { it.id in ids }
    }

    /** 标为已读/未读：进度推到最后一章 / 清零。 */
    private fun markSelected(read: Boolean) {
        val picked = selectedNovels()
        if (picked.isEmpty()) {
            adapter.exitSelection()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                picked.forEach { n ->
                    val updated = if (read) {
                        n.copy(
                            lastReadChapter = (n.chapterCount - 1).coerceAtLeast(0),
                            lastReadParaIndex = Int.MAX_VALUE,
                            lastReadPage = 0,
                        )
                    } else {
                        n.copy(lastReadChapter = 0, lastReadParaIndex = 0, lastReadPage = 0)
                    }
                    NovelStore.update(requireContext(), updated)
                }
            }
            adapter.exitSelection()
            refresh()
        }
    }

    private fun deleteSelected() {
        val picked = selectedNovels()
        if (picked.isEmpty()) {
            adapter.exitSelection()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.novel_manage_delete)
            .setMessage(
                if (picked.size == 1) getString(R.string.novel_delete_confirm, picked[0].title)
                else getString(R.string.novel_delete_confirm_multi, picked.size)
            )
            .setPositiveButton(R.string.confirm) { _, _ ->
                val ctx = requireContext()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { picked.forEach { deleteNovelFilesAndStore(ctx, it) } }
                    adapter.exitSelection()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删除一本书：清单、本地文件、译文记录三处都要清。
     *
     * ⚠️ 译文要按 (id, key) **成对**删：书籍 id 会被复用，只按 id 删可能误伤用户随后
     * 导入的、复用同一 id 的新书。
     */
    private suspend fun deleteNovelFilesAndStore(ctx: Context, novel: ImportedNovel) {
        NovelStore.remove(ctx, novel.id)
        runCatching { NovelImporter.deleteBookFiles(ctx, novel.id) }
        runCatching {
            TranslationHistoryDatabase.getInstance(ctx)
                .novelParagraphTranslationDao()
                .deleteForNovelScoped(novel.id, novel.translationKey)
        }.onFailure { LogCollector.w(TAG, "删除译文记录失败 id=${novel.id}", it) }
    }

    private fun renameSelected() {
        val novel = selectedNovels().firstOrNull() ?: return
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
                adapter.exitSelection()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 更换封面（单选）：photo picker 选图 → 缩放存进 covers/ 并替换 coverPath。 */
    private fun changeCover(uri: android.net.Uri) {
        val novel = selectedNovels().firstOrNull() ?: return
        adapter.exitSelection()
        viewLifecycleOwner.lifecycleScope.launch {
            val newCover = withContext(Dispatchers.IO) {
                runCatching { NovelImporter.replaceCover(requireContext(), novel.id, uri) }
                    .onFailure { LogCollector.w(TAG, "更换封面失败 id=${novel.id}", it) }
                    .getOrNull()
            }
            if (newCover != null) {
                NovelStore.update(requireContext(), novel.copy(coverPath = newCover))
            } else {
                UiUtils.showToast(requireContext(), getString(R.string.novel_cover_failed), true)
            }
            refresh()
        }
    }

    /** 编辑简介（单选）：弹多行输入框设置 description（详情列表里显示）。 */
    private fun editDesc() {
        val novel = selectedNovels().firstOrNull() ?: return

        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.import_desc_hint)
            setText(novel.description)
            setSelection(novel.description.length)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 3
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_desc_title)
            .setView(container)
            .setPositiveButton(R.string.confirm, null) // 在 show 后再绑定，避免自动关闭
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                NovelStore.update(requireContext(), novel.copy(description = editText.text.toString().trim()))
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        adapter.exitSelection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
