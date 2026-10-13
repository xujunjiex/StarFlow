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
import com.moe.starflow.databinding.FragmentNovelShelfBinding
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.mangaimport.ui.DisplayOptionsSheet
import com.moe.starflow.mangaimport.ui.DisplaySortOrder
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelImportEvent
import com.moe.starflow.novel.data.NovelImportFailureReason
import com.moe.starflow.novel.data.NovelImportManager
import com.moe.starflow.novel.data.NovelImporter
import com.moe.starflow.novel.data.NovelLibraryScanner
import com.moe.starflow.novel.data.NovelStorageDir
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.reader.NovelReaderActivity
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.AutoScanFailureDialog
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        const val KEY_SORT_RECENT_READ = "sort_recent_read"
    }

    private var _binding: FragmentNovelShelfBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: NovelShelfAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortOrder = DisplaySortOrder.TITLE

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
        binding.novelShelfRefresh.setOnRefreshListener { refreshLibraryState() }
        binding.novelShelfImport.setOnClickListener { showImportDialog() }
        binding.novelShelfEmpty.text = getString(
            R.string.novel_shelf_empty,
            com.moe.starflow.library.LibrarySessions.current(requireContext()).storage.displayPath + "/novel_import",
        )

        // 顶部栏 / 多选操作栏
        binding.novelTvSettings.setOnClickListener {
            com.moe.starflow.library.LibraryShelfMenu.show(this, binding.novelTvSettings,
                onDisplay = { showDisplayOptions() },
                onScan = { com.moe.starflow.library.LibraryScanner.start(requireContext(), "novel") })
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
        com.moe.starflow.library.LibraryShelfBinding.observe(this, "novel", binding.libraryStatus,
            { refresh() }, ::showScanFailures, { adapter.exitSelection() })
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
        val novelPrefs = requireContext().getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
        sortOrder = if (novelPrefs.getBoolean(KEY_SORT_RECENT_READ, false)) {
            DisplaySortOrder.RECENTLY_READ
        } else if (p.getBoolean(KEY_SORT_ADDED, false)) {
            DisplaySortOrder.ADDED
        } else {
            DisplaySortOrder.TITLE
        }
    }

    private fun applyDisplay(mode: DisplayMode, size: Int, order: DisplaySortOrder) {
        displayMode = mode
        gridSize = size
        sortOrder = order
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, mode.name)
            .putInt(KEY_GRID_SIZE, size)
            // Title/added retain the existing shared sort behavior. Recently read is novel-specific.
            .apply()
        if (order != DisplaySortOrder.RECENTLY_READ) {
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SORT_ADDED, order == DisplaySortOrder.ADDED)
                .apply()
        }
        requireContext().getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SORT_RECENT_READ, order == DisplaySortOrder.RECENTLY_READ)
            .apply()
        refresh()
    }

    private fun showDisplayOptions() {
        DisplayOptionsSheet(
            displayMode,
            gridSize,
            sortOrder,
            supportsRecentlyRead = true,
        ) { mode, size, order -> applyDisplay(mode, size, order) }
            .show(parentFragmentManager, DisplayOptionsSheet.TAG)
    }

    // ===== 导入 =====

    private fun showImportDialog() {
        com.moe.starflow.mangaimport.ui.ImportDialog.show(requireContext(),
            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
            onPickSingleDir = { pickDir.launch(null) },
            novel = true)
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
            is NovelImportEvent.Failed -> {
                val message = getString(
                    when (reason) {
                    NovelImportFailureReason.SOURCE_OPEN_FAILED -> R.string.novel_import_reason_source_open
                    NovelImportFailureReason.SOURCE_READ_FAILED -> R.string.novel_import_reason_source_read
                    NovelImportFailureReason.DESTINATION_WRITE_FAILED -> R.string.novel_import_reason_destination_write
                    NovelImportFailureReason.DIRECTORY_SCAN_FAILED -> R.string.novel_import_reason_directory_scan
                    NovelImportFailureReason.DIRECTORY_ACCESS_DENIED -> R.string.novel_import_reason_directory_access
                    NovelImportFailureReason.NOT_ARCHIVE -> R.string.novel_import_reason_not_archive
                    NovelImportFailureReason.UNREADABLE -> R.string.novel_import_reason_unreadable
                    NovelImportFailureReason.NO_TEXT_CHAPTER -> R.string.novel_import_reason_no_text_chapter
                    NovelImportFailureReason.EMPTY -> R.string.novel_import_reason_empty
                    NovelImportFailureReason.TOO_LARGE -> R.string.novel_import_reason_too_large
                    NovelImportFailureReason.ENCRYPTED -> R.string.novel_import_reason_encrypted
                    NovelImportFailureReason.UNKNOWN -> R.string.novel_import_reason_unknown
                }
                )
                failTitle to if (detail.isNullOrBlank()) message else "$message\n\n$detail"
            }
        }
    }

    // ===== 渲染 =====

    private fun refresh() {
        val ctx = context ?: return
        val session = com.moe.starflow.library.LibrarySessions.current(ctx)
        val pinned = session.context()
        binding.novelShelfEmpty.text = getString(R.string.novel_shelf_empty, session.storage.displayPath + "/novel_import")
        viewLifecycleOwner.lifecycleScope.launch {
            val stored = try { withContext(Dispatchers.IO) {
                NovelStore.load(pinned).map { n ->
                    // 「文件丢失」是**按当下磁盘状态实时推导**的瞬态标记，不持久化
                    val path = n.localRoot
                    n.copy(lost = n.lost || path.isNotEmpty() && !com.moe.starflow.library.LibrarySessions.sourceExists(File(path)))
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                session.saveState.value = session.saveState.value.copy(error = e.message ?: e.javaClass.simpleName)
                return@launch
            }
            if (!isAdded || _binding == null || com.moe.starflow.library.LibrarySessions.current(ctx).locationId != session.locationId) return@launch
            val placeholders = NovelImportManager.tasks.value
                .filter { it.format == ImportFormat.NOVEL }
                .map { it.toNovelPlaceholder() }
            val all = placeholders + stored.filterNot { s -> placeholders.any { it.id == s.id } }
            val list = NovelShelfSort.sort(all, sortOrder)
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
                    NovelStore.patch(requireContext(), n) { it.copy(lastReadChapter = updated.lastReadChapter,
                        lastReadParaIndex = updated.lastReadParaIndex, lastReadPage = updated.lastReadPage) }
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
                (if (picked.size == 1) getString(R.string.novel_delete_confirm, picked[0].title)
                else getString(R.string.novel_delete_confirm_multi, picked.size)) + "\n\n" + picked.joinToString("\n") { book ->
                    val session = com.moe.starflow.library.LibrarySessions.forLocation(requireContext(), book.libraryLocationId)
                    session.storage.displayPath + "/" + session.relative(File(book.localRoot))
                }
            )
            .setPositiveButton(R.string.confirm) { _, _ ->
                val ctx = requireContext()
                adapter.exitSelection()
                // ⚠️ 删除必须走**进程级**作用域（NovelImportManager.deleteNovels）：旧实现整段跑在
                // viewLifecycleOwner.lifecycleScope 里 —— 确认后立刻切 tab / 旋转 / 退出书架会把协程
                // 取消掉，而清单是**同步**删的（书架上看不出来），译文记录那条 DELETE 就永远不执行，
                // 每段的原文/译文/失败码永久留在库里。与漫画侧 ShelfCleanup 是同一套做法。
                NovelImportManager.deleteNovels(ctx, picked)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 重命名（单选）：改名不改身份 —— `addedAt` 才是译文指纹，标题变了已翻的译文照旧。
     */
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
                NovelStore.patch(requireContext(), novel) { it.copy(title = name) }
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
        val pinned = com.moe.starflow.library.LibrarySessions.forLocation(requireContext(), novel.libraryLocationId).context()
        com.moe.starflow.library.LibrarySessions.launch(pinned, viewLifecycleOwner.lifecycleScope) {
            val changed = withContext(Dispatchers.IO) {
                com.moe.starflow.library.LibrarySources.acquire(File(novel.localRoot)).use {
                    runCatching {
                        val cover = NovelImporter.replaceCover(pinned, novel.id, uri) ?: return@runCatching false
                        NovelStore.patch(pinned, novel) { it.copy(coverPath = cover) }
                        true
                    }.onFailure { LogCollector.w(TAG, "更换封面失败", it) }.getOrDefault(false)
                }
            }
            if (!changed) UiUtils.showToast(requireContext(), getString(R.string.novel_cover_failed), true)
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
                NovelStore.patch(requireContext(), novel) { it.copy(description = editText.text.toString().trim()) }
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        adapter.exitSelection()
    }

    override fun onResume() {
        super.onResume()
        // 阅读器在另一个 Activity 中写入 lastReadAt；回来时同时刷新进度并扫描外部添加的文件。
        if (_binding != null) {
            refresh()
            }
    }

    /** Refresh indexed status without source recognition. */
    private fun refreshLibraryState() {
        val pinned = com.moe.starflow.library.LibrarySessions.pin(requireContext())
        com.moe.starflow.library.LibrarySessions.launch(pinned, viewLifecycleOwner.lifecycleScope) {
            try { com.moe.starflow.library.LibrarySessions.refreshSources(pinned); refresh() }
            finally { _binding?.novelShelfRefresh?.isRefreshing = false }
        }
    }


    private fun showScanFailures(failures: List<String>) {
        if (failures.isEmpty() || !isAdded) return
        val allDetails = failures.joinToString("\n")
        AutoScanFailureDialog.builder(
            requireContext(),
            getString(R.string.import_auto_scan_failed_title),
            getString(R.string.import_auto_scan_failed_message, failures.size, allDetails),
        )
            .setPositiveButton(R.string.confirm, null)
            .create()
            .also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
