package com.moe.starflow.mangaimport

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentImportMangaBinding
import com.moe.starflow.mangaimport.data.ImportEvent
import com.moe.starflow.mangaimport.data.ImportFailureReason
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.mangaimport.data.ImportTab
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.mangaimport.data.ShelfCleanup
import com.moe.starflow.mangaimport.data.StorageDirStore
import com.moe.starflow.mangaimport.reader.MangaReaderActivity
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.mangaimport.ui.DisplayOptionsSheet
import com.moe.starflow.mangaimport.ui.DisplaySortOrder
import com.moe.starflow.mangaimport.ui.ImportDialog
import com.moe.starflow.mangaimport.ui.MangaGridAdapter
import com.moe.starflow.novel.shelf.NovelShelfFragment
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.AutoScanFailureDialog
import com.moe.starflow.utils.AppNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入翻译 tab：书架页。
 * 展示导入漫画清单网格，支持导入（文件/文件夹）、显示选项、长按多选管理（Koto 式：
 * 重命名/标为已读/标为未读/删除，删除会一并删掉 app 内部存储的本地副本）。
 *
 * 导入的漫画固定复制进应用专属目录（getExternalFilesDir/manga_import，Android/data 下），无需任何存储权限；
 * 源文件通过 SAF 选择器选取，导入即复制、用完即弃。
 */
class ImportMangaFragment : Fragment() {

    private companion object {
        /** 旋转/回收时带着「还没弹完的导入结果」（见 onSaveInstanceState）。 */
        private const val STATE_DIALOG_QUEUE = "import_dialog_queue"

        /** 小说书架子 Fragment 的 tag（childFragmentManager 里查重用）。 */
        private const val NOVEL_SHELF_TAG = "novel_shelf"
    }

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MangaGridAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortByAdded = false

    // 导入结果弹窗队列：一次导入可能连续产生多个结果（多选压缩包时每个文件一个任务），
    // 顺序弹、不叠窗。事件本身由 ImportManager 攒着（进程级，切页期间产生的不丢）；
    // 已经取出来的这几条在旋转时要靠 onSaveInstanceState 带着走（见下面的 STATE_*）。
    private val dialogQueue = ArrayDeque<Pair<String, String>>()
    private var dialogShowing = false
    private var resultDialog: Dialog? = null

    /** 正在展示的那条（旋转时放回队列，避免提示永久丢失）。 */
    private var resultDialogContent: Pair<String, String>? = null

    /** 「导入」tab 内的当前书架。**持久化**，下次进来停在原处。 */
    private var currentTab = ImportTab.MANGA

    /** 小说书架子 Fragment：首次切到小说才创建，之后常驻（切回漫画不销毁）。 */
    private var novelShelf: Fragment? = null

    // refresh() 的「文件丢失」推导缓存：进度回调高频调 refresh 时复用，避免反复 stat 磁盘
    private var cachedStored: List<ImportedManga> = emptyList()
    private var cachedStoredWithLost: List<ImportedManga> = emptyList()

    // 返回键：多选模式下退出多选
    private lateinit var backCallback: OnBackPressedCallback

    // 文件导入（SAF 多选 zip/cbz）
    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                context?.let { ImportManager.importArchives(it, uris) }
            }
        }

    // 换封面（单选时）：photo picker 选图 → 复制进 covers/ 并替换 coverPath
    private val pickCoverLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) changeCover(uri)
        }

    // 导入文件夹（SAF 选目录，不需要「所有文件访问」权限）
    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistReadable(uri)
                context?.let { ImportManager.importDirectory(it, uri) }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportMangaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadDisplayPrefs()

        adapter = MangaGridAdapter(
            displayMode,
            onItemClick = { manga -> openReader(manga) },
            onSelectionChanged = { mode, count -> showSelectionUi(mode, count) },
            onCancelImport = { id -> confirmCancelImport(id) },
            onExportRecords = { book -> com.moe.starflow.library.BookRecordExport.start(requireContext(),
                com.moe.starflow.library.BookRef(book.libraryLocationId, "manga", book.bookUuid)) },
            onBookData = { book -> com.moe.starflow.library.BookDataActivity.open(requireContext(),
                com.moe.starflow.library.BookRef(book.libraryLocationId, "manga", book.bookUuid)) }
        )
        binding.recyclerView.adapter = adapter

        setupImportTabs()

        binding.fabImport.setOnClickListener {
            ImportDialog.show(
                requireContext(),
                // mime 列表要显式带上 rar/7z/pdf：SAF 选择器按 mime 过滤，只给 zip 的话
                // .cbr/.7z/.pdf 会被置灰选不中（octet-stream 只兜「未知类型」那一部分）
                onPickFiles = {
                    pickFilesLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-cbz",
                            "application/vnd.comicbook+zip",
                            "application/x-rar-compressed",
                            "application/vnd.rar",
                            "application/x-cbr",
                            "application/vnd.comicbook-rar",
                            "application/x-7z-compressed",
                            "application/pdf",
                            "application/octet-stream"
                        )
                    )
                },
                onPickSingleDir = { pickDirLauncher.launch(null) },
            )
        }

        binding.tvSettings.setOnClickListener {
            com.moe.starflow.library.LibraryShelfMenu.show(this, binding.tvSettings,
                onDisplay = { showDisplayOptions() },
                onScan = { com.moe.starflow.library.LibraryScanner.start(requireContext(), "manga") })
        }

        binding.tvCloseSelection.setOnClickListener { adapter.exitSelection() }
        binding.ivSelectAll.setOnClickListener { adapter.toggleSelectAll() }
        binding.actionDelete.setOnClickListener { deleteSelected() }
        binding.actionRead.setOnClickListener { markSelected(read = true) }
        binding.actionUnread.setOnClickListener { markSelected(read = false) }
        binding.actionRename.setOnClickListener { renameSelected() }
        binding.actionCover.setOnClickListener {
            pickCoverLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.actionDesc.setOnClickListener { editDesc() }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.exitSelection()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 下拉刷新：重新加载清单，立即反映编辑/阅读进度等状态
        binding.swipeRefresh.setOnRefreshListener { refreshLibraryState() }
        binding.tvEmpty.text = getString(
            R.string.import_manga_empty_hint,
            com.moe.starflow.library.LibrarySessions.current(requireContext()).storage.displayPath + "/manga_import",
        )

        refresh()

        com.moe.starflow.library.LibraryShelfBinding.observe(this, "manga", binding.libraryStatus,
            { refresh() }, ::enqueueScanFailures, { adapter.exitSelection() }, { currentTab == ImportTab.MANGA }, ::confirmCancelImport)

        // LibraryShelfBinding shows import progress above the shelf; tasks never become book rows.

        // 导入结果：攒在 ImportManager 里的事件取出来排队弹窗
        viewLifecycleOwner.lifecycleScope.launch {
            ImportManager.events.collect {
                ImportManager.drainEvents().forEach { ev -> enqueueResultDialog(ev) }
            }
        }

        // 首帧就有可能在导入（旋转回来/切页回来）→ 立刻按当前任务刷一次
        refresh()
        // 旋转/被系统回收前没弹完的结果弹窗：从 savedInstanceState 恢复后接着弹
        restoreDialogQueue(savedInstanceState)
        showNextResultDialog()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Rescan the selected library without blocking the shelf UI. */
    private fun refreshLibraryState() {
        com.moe.starflow.library.LibraryScanner.start(requireContext(), "manga")
        _binding?.swipeRefresh?.isRefreshing = false
    }


    private fun enqueueScanFailures(failures: List<String>) {
        if (failures.isEmpty() || _binding == null) return
        val allDetails = failures.joinToString("\n")
        val message = getString(R.string.import_auto_scan_failed_message, failures.size, allDetails)
        dialogQueue.addLast(getString(R.string.import_auto_scan_failed_title) to message)
        showNextResultDialog()
    }

    /**
     * 把待展示的导入结果写进 Bundle。
     *
     * ⚠️ 必需：`MainActivity` 没有 `configChanges`，旋转会**重建 Fragment**，而事件一旦被
     * `drainEvents()` 取走就从 `ImportManager` 里消失了 —— 不带着走的话「空文件夹 / 导入失败」
     * 的提示会**永久丢失**（用户只看到书架多了条 0 页书，不知道为什么）。
     * 扁平化成 String 列表存（Pair 不能直接进 Bundle）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val flat = ArrayList<String>(dialogQueue.size * 2)
        dialogQueue.forEach { (title, message) -> flat.add(title); flat.add(message) }
        outState.putStringArrayList(STATE_DIALOG_QUEUE, flat)
    }

    private fun restoreDialogQueue(savedInstanceState: Bundle?) {
        val flat = savedInstanceState?.getStringArrayList(STATE_DIALOG_QUEUE) ?: return
        var i = 0
        while (i + 1 < flat.size) {
            dialogQueue.addLast(flat[i] to flat[i + 1])
            i += 2
        }
    }

    /** 请求持久读权限，保证所选目录在重启后仍可访问。 */
    private fun persistReadable(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 某些场景（write 权限未授予）只取 read
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {
            }
        }
    }

    // ===== 「漫画 | 小说」切换 =====

    /**
     * 顶部切换的初始化。
     *
     * 小说并入本 tab 而不是新增底部 tab 的原因见 [ImportTab]（底部导航硬限 5 个 item）。
     */
    private fun setupImportTabs() {
        val prefs = com.moe.starflow.utils.CustomPreference.getInstance(requireContext())
        currentTab = ImportTab.fromPref(prefs.getString(ImportTab.KEY, ImportTab.MANGA.prefValue))
        binding.importTabManga.setOnClickListener { switchImportTab(ImportTab.MANGA) }
        binding.importTabNovel.setOnClickListener { switchImportTab(ImportTab.NOVEL) }
        applyImportTab()
    }

    private fun switchImportTab(tab: ImportTab) {
        if (tab == currentTab) return
        // 两侧的多选都要先退掉：否则切回来时顶部栏停在多选态、操作栏却对应不上。
        // ⚠️ 小说书架常驻（不 replace），它的多选态不会自己消失，必须显式退。
        if (currentTab == ImportTab.MANGA && adapter.isSelectionMode) {
            adapter.exitSelection()
        }
        if (currentTab == ImportTab.NOVEL) {
            (novelShelf as? NovelShelfFragment)?.exitSelection()
        }
        currentTab = tab
        com.moe.starflow.utils.CustomPreference.getInstance(requireContext())
            .setString(ImportTab.KEY, tab.prefValue)
        applyImportTab()
    }

    /**
     * 应用当前 tab。
     *
     * ⚠️ 小说书架用 `childFragmentManager` **懒加载 + 常驻**（不 replace）：replace 会销毁
     * 小说书架的 View，用户切回漫画再切回来发现滚动位置、导入占位全没了。
     */
    private fun applyImportTab() {
        val novel = currentTab == ImportTab.NOVEL
        binding.importTabManga.alpha = if (novel) 0.55f else 1f
        binding.importTabNovel.alpha = if (novel) 1f else 0.55f

        val mangaVis = if (novel) View.GONE else View.VISIBLE
        binding.topBar.visibility = mangaVis
        binding.mangaContent.visibility = mangaVis
        binding.fabImport.visibility = mangaVis
        if (novel) binding.selectionActions.visibility = View.GONE

        binding.novelShelfContainer.visibility = if (novel) View.VISIBLE else View.GONE

        if (novel && novelShelf == null) {
            novelShelf = NovelShelfFragment()
            childFragmentManager.beginTransaction()
                .replace(R.id.novel_shelf_container, novelShelf!!, NOVEL_SHELF_TAG)
                .commitNow()
        }
        novelShelf?.let {
            childFragmentManager.beginTransaction().setMaxLifecycle(it,
                if (novel) androidx.lifecycle.Lifecycle.State.RESUMED else androidx.lifecycle.Lifecycle.State.STARTED).commitNow()
        }
        if (!novel && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            com.moe.starflow.library.LibraryScanner.start(requireContext(), "manga")
    }

    // ===== 多选管理（Koto 式）=====

    /** 多选态 UI 切换：顶部栏两态 + 操作栏 + 返回键开关。 */
    private fun showSelectionUi(mode: Boolean, count: Int) {
        binding.tvTitle.visibility = if (mode) View.GONE else View.VISIBLE
        binding.tvSelectionCount.visibility = if (mode) View.VISIBLE else View.GONE
        binding.tvSettings.visibility = if (mode) View.GONE else View.VISIBLE
        binding.tvCloseSelection.visibility = if (mode) View.VISIBLE else View.GONE
        binding.ivSelectAll.visibility = if (mode) View.VISIBLE else View.GONE
        binding.selectionActions.visibility = if (mode) View.VISIBLE else View.GONE
        val single = mode && count == 1
        binding.selectionActionsSingle.visibility = if (single) View.VISIBLE else View.GONE
        binding.actionDivider.visibility = if (single) View.VISIBLE else View.GONE
        binding.actionRename.visibility = if (single) View.VISIBLE else View.GONE
        binding.actionCover.visibility = if (single) View.VISIBLE else View.GONE
        binding.actionDesc.visibility = if (single) View.VISIBLE else View.GONE
        if (mode) {
            binding.tvSelectionCount.text = getString(R.string.import_selected_count, count)
        }
        backCallback.isEnabled = mode
    }

    private fun openReader(manga: ImportedManga, sourceWarningShown: Boolean = false) {
        // 本地文件可能已被手动删除：先拦下，避免进阅读器读到「文件丢失」又退回
        if (manga.lost || !com.moe.starflow.library.LibrarySessions.sourceExists(File(manga.localRoot))) {
            com.moe.starflow.library.BookRecoveryDialog.show(requireContext(), com.moe.starflow.library.BookRef(manga.libraryLocationId, "manga", manga.bookUuid))
            return
        }
        if (manga.pageCount == 0) {
            AppNotice.show(requireContext(), getString(R.string.library_empty_directory_hint))
            return
        }
        if (!sourceWarningShown && com.moe.starflow.library.BookSourceChangeDialog.show(requireContext(), manga,
                onRead = {
                    val latest = ImportedMangaStore.load(requireContext()).firstOrNull { it.bookUuid == manga.bookUuid }
                    if (latest != null) openReader(latest, sourceWarningShown = true)
                })) return
        val intent = Intent(requireContext(), MangaReaderActivity::class.java)
        intent.putExtra(MangaReaderActivity.EXTRA_MANGA_ID, manga.id)
        intent.putExtra("source_warning_shown", sourceWarningShown)
        intent.putExtra("source_index_path", manga.sourceIndexPath)
        intent.putExtra("library_location", manga.libraryLocationId)
        intent.putExtra("library_book_uuid", manga.bookUuid)
        startActivity(intent)
    }

    /** 重命名单选中的那部漫画（仅 N==1 可用）。 */
    private fun renameSelected() {
        val ids = adapter.selectedIds()
        if (ids.size != 1) return
        val manga = ImportedMangaStore.load(requireContext()).firstOrNull { it.id == ids.first() }
            ?: return

        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.import_rename_hint)
            setText(manga.title)
            setSelection(manga.title.length)
            setSingleLine(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_rename_title)
            .setView(container)
            .setPositiveButton(R.string.confirm, null) // 在 show 后再绑定，避免自动关闭
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text.toString().trim()
                if (name.isEmpty()) {
                    AppNotice.show(requireContext(), getString(R.string.import_rename_empty))
                    return@setOnClickListener
                }
                ImportedMangaStore.patch(requireContext(), manga) { it.copy(title = name, titleOrigin = "user") }
                dialog.dismiss()
                // 确认后再刷新，立即显示新标题
                refresh()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        adapter.exitSelection()
    }

    /** 更换封面（单选）：photo picker 选图 → 复制进 covers/ 替换 coverPath。 */
    private fun changeCover(uri: Uri) {
        val id = adapter.selectedIds().firstOrNull() ?: return
        val manga = ImportedMangaStore.load(requireContext()).firstOrNull { it.id == id } ?: return
        adapter.exitSelection()
        val pinned = com.moe.starflow.library.LibrarySessions.forLocation(requireContext(), manga.libraryLocationId).context()
        com.moe.starflow.library.LibrarySessions.launch(pinned, viewLifecycleOwner.lifecycleScope) {
            val changed = withContext(Dispatchers.IO) {
                com.moe.starflow.library.LibrarySources.acquire(File(manga.localRoot)).use {
                    runCatching {
                        val cover = MangaImporter.replaceCover(pinned, id, uri) ?: return@runCatching false
                        ImportedMangaStore.patch(pinned, manga) { it.copy(coverPath = cover) }
                        true
                    }.onFailure { LogCollector.w("ImportMangaFragment", "更换封面失败", it) }.getOrDefault(false)
                }
            }
            if (!changed) com.moe.starflow.utils.AppNotice.show(requireContext(), getString(R.string.novel_cover_failed))
            refresh()
        }
    }

    /** 编辑简介（单选）：弹多行输入框设置 description。 */
    private fun editDesc() {
        val id = adapter.selectedIds().firstOrNull() ?: return
        val manga = ImportedMangaStore.load(requireContext()).firstOrNull { it.id == id } ?: return

        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.import_desc_hint)
            setText(manga.description)
            setSelection(manga.description.length)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 3
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
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
                ImportedMangaStore.patch(requireContext(), manga) { it.copy(description = editText.text.toString().trim()) }
                dialog.dismiss()
                // 确认后再刷新，简介立即更新到详情列表
                refresh()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        adapter.exitSelection()
    }

    /** 批量标为已读/未读：已读=跳到最后一页，未读=清零阅读进度。 */
    private fun markSelected(read: Boolean) {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val list = ImportedMangaStore.load(requireContext())
        ids.forEach { id ->
            val m = list.firstOrNull { it.id == id } ?: return@forEach
            val target = if (read) (m.pageCount - 1).coerceAtLeast(0) else 0
            if (target != m.lastReadPage) {
                ImportedMangaStore.patch(requireContext(), m) { it.copy(lastReadPage = target) }
            }
        }
        adapter.exitSelection()
        refresh()
    }

    /**
     * 删除选中的漫画（含 app 内部存储的本地副本 + **每页翻译记录**）。
     *
     * 删除前先查这些书里哪些有翻译记录：译文只在 app 内（DB），删掉不可恢复，
     * 而下载导出是唯一保留手段 —— 所以有记录时必须提示，并给一个「先去导出」的入口。
     */
    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val toDelete = ImportedMangaStore.load(requireContext()).filter { it.id in ids }
        if (toDelete.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            // suspend DAO 查询自带 Room 线程切换，但这里显式下 IO，避免以后改成 JOIN 查询时踩主线程
            val withRecords = withContext(Dispatchers.IO) {
                ShelfCleanup.mangasWithTranslations(ctx.applicationContext, toDelete)
            }
            if (!isAdded) return@launch
            showDeleteConfirm(toDelete, withRecords)
        }
    }

    /** 删除确认弹窗（有翻译记录时文案不同，且单选时多一个「先去导出」）。 */
    private fun showDeleteConfirm(toDelete: List<ImportedManga>, withRecords: List<ImportedManga>) {
        val missing = toDelete.count { it.lost || !com.moe.starflow.library.LibrarySessions.sourceExists(File(it.localRoot)) }
        val message = if (missing > 0) {
            getString(R.string.book_delete_missing, toDelete.size, missing, withRecords.size)
        } else if (withRecords.isEmpty()) {
            getString(R.string.import_delete_selected_confirm, toDelete.size)
        } else {
            getString(
                R.string.import_delete_with_translations_confirm,
                toDelete.size,
                withRecords.size
            )
        }
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_delete)
            .setMessage(message + "\n\n" + toDelete.joinToString("\n") { book ->
                val session = com.moe.starflow.library.LibrarySessions.forLocation(requireContext(), book.libraryLocationId)
                session.storage.displayPath + "/" + session.relative(File(book.localRoot))
            })
            .setPositiveButton(R.string.import_delete) { _, _ -> performDelete(toDelete) }
            .setNegativeButton(R.string.cancel, null)
        // 「先去导出」只在单选时给出（多选时点了不知道该导哪本）：点了不删，直接进阅读器
        if (missing > 0) {
            builder.setNeutralButton(R.string.book_recovery_faq) { _, _ -> com.moe.starflow.library.BookRecoveryDialog.faq(requireContext()) }
        } else if (toDelete.size == 1 && withRecords.size == 1) {
            builder.setNeutralButton(R.string.import_delete_export_first) { _, _ ->
                adapter.exitSelection()
                openReader(toDelete.single())
            }
        }
        builder.create().also {
            it.show()
            it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
    }

    /** 执行删除：① 清单 ② 本地副本（同步，书架立刻反映）③ 翻译记录（进程级作用域，按 (id, 指纹) 删）。 */
    private fun performDelete(toDelete: List<ImportedManga>) {
        val pinned = com.moe.starflow.library.LibrarySessions.forLocation(requireContext(), toDelete.first().libraryLocationId).context()
        com.moe.starflow.library.LibraryDeletion.start(pinned, "manga", toDelete.map { it.id }, toDelete.associate { it.id to it.bookUuid })
        adapter.exitSelection()
        refresh()
    }

    // ===== 显示选项 =====

    private fun loadDisplayPrefs() {
        val p = requireContext().getSharedPreferences("manga_import", android.content.Context.MODE_PRIVATE)
        displayMode = runCatching { DisplayMode.valueOf(p.getString("display_mode", DisplayMode.DETAILED_LIST.name)!!) }
            .getOrDefault(DisplayMode.DETAILED_LIST)
        gridSize = p.getInt("grid_size", 3)
        sortByAdded = p.getBoolean("sort_by_added", false)
    }

    private fun showDisplayOptions() {
        DisplayOptionsSheet(
            displayMode,
            gridSize,
            if (sortByAdded) DisplaySortOrder.ADDED else DisplaySortOrder.TITLE,
        ) { mode, size, order -> applyDisplay(mode, size, order == DisplaySortOrder.ADDED) }
            .show(parentFragmentManager, DisplayOptionsSheet.TAG)
    }

    private fun applyDisplay(mode: DisplayMode, size: Int, sortAdded: Boolean) {
        displayMode = mode
        gridSize = size
        sortByAdded = sortAdded
        requireContext().getSharedPreferences("manga_import", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("display_mode", mode.name)
            .putInt("grid_size", size)
            .putBoolean("sort_by_added", sortAdded)
            .apply()
        refresh()
    }

    // ===== 导入 =====
    //
    // 导入本身不在这里跑：[ImportManager]（进程级作用域）负责复制、进度、取消与入库，
    // LibraryShelfBinding 显示任务进度；本页只显示实际书目并把结果事件排队弹窗。
    // 这样旋转/切页不会掐断导入（旧实现用 viewLifecycleOwner.lifecycleScope 跑导入，
    // 旋转即取消 → 文件复制一半、条目还没入库 → 孤儿目录）。

    /** 取消导入前先确认：会删掉已复制的内容。 */
    private fun confirmCancelImport(id: Long) {
        val title = ImportManager.tasks.value.firstOrNull { it.id == id }?.title ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_cancel_title)
            .setMessage(getString(R.string.import_cancel_msg, title))
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (!ImportManager.cancel(id)) {
                    // 点确认的瞬间刚好导入完成：不能报「已取消」（那是假话），如实提示已完成
                    AppNotice.show(requireContext(), getString(R.string.import_cancel_too_late))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
    }

    /** 把导入结果事件翻成弹窗内容排队（顺序弹，不叠窗）。 */
    private fun enqueueResultDialog(event: ImportEvent) {
        when (event) {
            is ImportEvent.NoImages -> dialogQueue.addLast(
                getString(R.string.import_empty_title) to getString(
                    if (event.isArchive) R.string.import_empty_archive else R.string.import_empty_folder,
                    event.title
                )
            )

            is ImportEvent.Failed -> dialogQueue.addLast(
                getString(R.string.import_result_failed_title) to getString(
                    R.string.import_result_failed_msg,
                    event.title,
                    listOfNotNull(
                        getString(
                            when (event.reason) {
                                ImportFailureReason.NOT_ARCHIVE -> R.string.import_fail_not_archive
                                ImportFailureReason.ARCHIVE_INVALID -> R.string.import_fail_archive_invalid
                                ImportFailureReason.SOURCE_ACCESS_DENIED -> R.string.import_fail_source_access_denied
                                ImportFailureReason.SOURCE_OPEN_FAILED -> R.string.import_fail_source_open_failed
                                ImportFailureReason.SOURCE_READ_FAILED -> R.string.import_fail_source_read_failed
                                ImportFailureReason.DESTINATION_WRITE_FAILED -> R.string.import_fail_destination_write_failed
                                ImportFailureReason.STORAGE_INSUFFICIENT -> R.string.import_fail_storage_insufficient
                                ImportFailureReason.ARCHIVE_LIMIT_EXCEEDED -> R.string.import_fail_archive_limit
                                ImportFailureReason.DIRECTORY_ACCESS_DENIED -> R.string.import_fail_directory_access_denied
                                ImportFailureReason.DIRECTORY_SCAN_FAILED -> R.string.import_fail_directory_scan_failed
                                ImportFailureReason.DIRECTORY_INVALID -> R.string.import_fail_directory_invalid
                                ImportFailureReason.UNREADABLE -> R.string.import_fail_pdf_read_failed
                                // PDF 打不开要分开说：加密有明确的解法（先解密），损坏只能重下
                                ImportFailureReason.PDF_ENCRYPTED -> R.string.import_fail_pdf_encrypted
                                ImportFailureReason.PDF_INVALID -> R.string.import_fail_pdf_invalid
                                ImportFailureReason.UNKNOWN -> R.string.import_fail_unknown
                            }
                        ),
                        event.detail?.let { getString(R.string.import_fail_detail, it) }
                    ).joinToString("\n")
                )
            )
        }
        showNextResultDialog()
    }

    /** 依次显示队列里的导入结果弹窗（前一窗关闭后再弹下一个，避免叠窗）。 */
    private fun showNextResultDialog() {
        if (dialogShowing || dialogQueue.isEmpty()) return
        if (!isAdded) return
        val item = dialogQueue.removeFirst()
        // ⚠️ 记下「正在展示的那条」：onDestroyView（旋转/切页）会 dismiss 掉它，
        // 那时要把它放回队列，重建后才能继续提示 —— 否则事件已被 drain 走 = 提示永久丢失。
        resultDialogContent = item
        dialogShowing = true
        // ⚠️ 弹窗挂在 Activity 上，不随 View 销毁 → 留引用，onDestroyView 里主动 dismiss（防窗口泄漏）
        val onFinished = {
            dialogShowing = false
            resultDialogContent = null
            showNextResultDialog()
        }
        val onPositive = DialogInterface.OnClickListener { _, _ -> onFinished() }
        val onCancel = DialogInterface.OnCancelListener { onFinished() }
        val dialog: Dialog = if (item.first == getString(R.string.import_auto_scan_failed_title) ||
            item.first == getString(R.string.import_result_failed_title)) {
            AutoScanFailureDialog.builder(requireContext(), item.first, item.second)
                .setPositiveButton(R.string.confirm, onPositive)
                .setNeutralButton(R.string.library_storage_title) { _, _ ->
                    onFinished()
                    com.moe.starflow.library.LibraryStorageDialog().show(parentFragmentManager, "library-storage")
                }
                .setOnCancelListener(onCancel)
                .create()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(item.first)
                .setMessage(item.second)
                .setPositiveButton(R.string.confirm, onPositive)
                .setOnCancelListener(onCancel)
                .create()
        }
        resultDialog = dialog.also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
    }

    /**
     * 重建书架列表。
     *
     * @param forceLostCheck 是否重新推导「文件丢失」。⚠️ 进度回调会以 ~12Hz 调本方法，
     *   每次都 stat 一遍所有 localRoot 是白费（真机上表现为导入时列表发涩）；
     *   变化源是「清单本身」或「进行中的任务」时复用上次推导结果即可。
     *   下拉刷新 / onResume / 编辑后调用走默认 true（用户可能刚从文件管理器删过文件）。
     */
    private fun refresh(forceLostCheck: Boolean = true) {
        if (!isAdded || _binding == null) return
        binding.tvEmpty.text = getString(R.string.import_manga_empty_hint,
            com.moe.starflow.library.LibrarySessions.current(requireContext()).storage.displayPath + "/manga_import")
        val list0 = try { ImportedMangaStore.load(requireContext()) }
            catch (e: Exception) {
                val session = com.moe.starflow.library.LibrarySessions.current(requireContext())
                session.saveState.value = session.saveState.value.copy(error = e.message ?: e.javaClass.simpleName)
                return
            }
        // 推导「文件丢失」标记（瞬态，不入库）：localRoot 不存在 → lost=true。
        // 放入 data class 参与 DiffUtil 相等比较，文件被删/恢复后对应格子自动重绘。
        // ⚠️ 导入中的占位不算：它 localRoot 为空，按此判据会全部标成「文件丢失」
        val withLost = if (!forceLostCheck && list0 == cachedStored) {
            cachedStoredWithLost
        } else {
            list0.map { m -> if (m.importing) m else m.copy(lost = m.lost || !com.moe.starflow.library.LibrarySessions.sourceExists(File(m.localRoot))) }
                .also {
                    cachedStored = list0
                    cachedStoredWithLost = it
                }
        }
        val all = withLost
        val list = if (sortByAdded) all.sortedByDescending { it.addedAt } else all.sortedBy { it.title }
        val cols = when (displayMode) {
            DisplayMode.GRID -> gridSize
            else -> 1
        }
        // ⚠️ 只在列数变化时重建 LayoutManager：refresh 现在会被进度回调高频调用，
        // 每次换 LayoutManager 会把列表滚回顶部（导入时列表疯狂跳）
        val lm = binding.recyclerView.layoutManager
        if (lm !is GridLayoutManager || lm.spanCount != cols) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), cols)
        }
        adapter.setDisplayMode(displayMode)
        adapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (currentTab == ImportTab.MANGA && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            !dialogShowing && !adapter.isSelectionMode && !com.moe.starflow.library.LibraryScanner.state.value.running) {
            list.firstOrNull { !it.lost && it.sourceNoticeId.isNotBlank() && it.sourceNoticeSeen != it.sourceNoticeId }?.let { book ->
                com.moe.starflow.library.BookSourceChangeDialog.show(requireContext(), book, onDismiss = { refresh() })
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 导入结果弹窗挂在 Activity 上（不随 View 销毁）：主动收掉，避免窗口泄漏 + 下次重建时叠窗
        resultDialog?.dismiss()
        resultDialog = null
        // ⚠️ 把「正在展示的那条」放回队首：旋转后重建的 Fragment 才能继续提示。
        // （事件早被 drainEvents 取走，不放回就是永久丢失）
        resultDialogContent?.let { dialogQueue.addFirst(it) }
        resultDialogContent = null
        dialogShowing = false
        _binding = null
    }
}
