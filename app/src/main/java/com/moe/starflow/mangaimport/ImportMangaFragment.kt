package com.moe.starflow.mangaimport

import android.app.AlertDialog
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
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.mangaimport.data.ImportEvent
import com.moe.starflow.mangaimport.data.ImportFailureReason
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.mangaimport.data.ShelfCleanup
import com.moe.starflow.mangaimport.data.StorageDirStore
import com.moe.starflow.mangaimport.reader.MangaReaderActivity
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.mangaimport.ui.DisplayOptionsSheet
import com.moe.starflow.mangaimport.ui.ImportDialog
import com.moe.starflow.mangaimport.ui.MangaGridAdapter
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
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
    private var resultDialog: AlertDialog? = null

    /** 正在展示的那条（旋转时放回队列，避免提示永久丢失）。 */
    private var resultDialogContent: Pair<String, String>? = null

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
            onCancelImport = { id -> confirmCancelImport(id) }
        )
        binding.recyclerView.adapter = adapter

        binding.fabImport.setOnClickListener {
            ImportDialog.show(
                requireContext(),
                onPickFiles = { pickFilesLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onPickSingleDir = { pickDirLauncher.launch(null) }
            )
        }

        binding.tvSettings.setOnClickListener {
            DisplayOptionsSheet(displayMode, gridSize, sortByAdded) { m, s, sa ->
                applyDisplay(m, s, sa)
            }.show(parentFragmentManager, DisplayOptionsSheet.TAG)
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
        binding.swipeRefresh.setOnRefreshListener {
            refresh()
            binding.swipeRefresh.isRefreshing = false
        }

        refresh()

        // 一次性迁移 + 清理孤儿翻译记录：
        //  - 旧版 filesDir 目录搬到外部专属目录（Android/data/<pkg>/files）；
        //  - 已删除漫画的 imported_page_translation 旧行（书架删了但 DB 没清 → id 复用会把旧译图
        //    错误映射到新导入的漫画）按「当前书架不存在的 mangaId」批量清掉（根因修复，删除时也会即时清）。
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                StorageDirStore.migrate(requireContext())
                purgeOrphanTranslations(requireContext())
            }
            refresh()
        }

        // 导入进度：占位卡片实时刷新（tasks 由进程级 ImportManager 持有，与 View 生命周期解耦，
        // 旋转/切页不会中断导入；回到本页时这里立刻收到当前进度）
        viewLifecycleOwner.lifecycleScope.launch {
            ImportManager.tasks.collect { refresh(forceLostCheck = false) }
        }

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

    private fun openReader(manga: ImportedManga) {
        // 本地文件可能已被手动删除：先拦下，避免进阅读器读到「文件丢失」又退回
        if (!File(manga.localRoot).exists()) {
            UiUtils.showToast(requireContext(), getString(R.string.reader_file_lost))
            return
        }
        val intent = Intent(requireContext(), MangaReaderActivity::class.java)
        intent.putExtra(MangaReaderActivity.EXTRA_MANGA_ID, manga.id)
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
                    UiUtils.showToast(requireContext(), getString(R.string.import_rename_empty))
                    return@setOnClickListener
                }
                ImportedMangaStore.update(requireContext(), manga.copy(title = name))
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
        viewLifecycleOwner.lifecycleScope.launch {
            val newCover = withContext(Dispatchers.IO) {
                MangaImporter.replaceCover(requireContext(), id, uri)
            }
            if (newCover != null) {
                ImportedMangaStore.update(requireContext(), manga.copy(coverPath = newCover))
            }
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
                ImportedMangaStore.update(
                    requireContext(),
                    manga.copy(description = editText.text.toString().trim())
                )
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
                ImportedMangaStore.update(requireContext(), m.copy(lastReadPage = target))
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
        val message = if (withRecords.isEmpty()) {
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
            .setMessage(message)
            .setPositiveButton(R.string.import_delete) { _, _ -> performDelete(toDelete) }
            .setNegativeButton(R.string.cancel, null)
        // 「先去导出」只在单选时给出（多选时点了不知道该导哪本）：点了不删，直接进阅读器
        if (toDelete.size == 1 && withRecords.size == 1) {
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
        toDelete.forEach { manga ->
            ImportedMangaStore.remove(requireContext(), manga.id)
            deleteImportedFiles(manga)
        }
        adapter.exitSelection()
        refresh()
        UiUtils.showToast(requireContext(), getString(R.string.import_manga_deleted))
        // ⚠️ 翻译记录不能放 lifecycleScope：确认后立刻切页/旋转会把协程取消掉，DELETE 就永不执行
        // （清单已同步删，书架上看不出来 → 静默孤儿行）。走进程级作用域；
        // 且必须把整条条目传过去：删除按 (id, 指纹) 成对进行，见 ShelfCleanup.purgeTranslations。
        ShelfCleanup.purgeTranslations(requireContext(), toDelete)
    }

    /** 删除导入的本地内容（应用专属目录 getExternalFilesDir/manga_import/<id>/ 复制件 + 封面）。 */
    private fun deleteImportedFiles(manga: ImportedManga) {
        try {
            val local = File(manga.localRoot)
            if (local.isDirectory) local.deleteRecursively() else local.delete()
            manga.coverPath?.let { File(it).delete() }
        } catch (e: Exception) {
            LogCollector.w("ImportMangaFragment", "删除导入文件失败: ${manga.id}", e)
        }
    }

    /** 删除书架里已不存在的漫画的孤儿翻译记录（防止旧译图因 id 复用串到新漫画）。 */
    private suspend fun purgeOrphanTranslations(context: android.content.Context) {
        try {
            val dao = TranslationHistoryDatabase.getInstance(context).importedPageTranslationDao()
            val validIds = ImportedMangaStore.load(context).map { it.id }.toSet()
            val orphanIds = dao.allMangaIds().filter { it !in validIds }
            orphanIds.forEach { id ->
                try {
                    // ⚠️ 逐个二次确认：本方法也是异步的，期间用户完全可能刚好导入了一本复用同 id 的新书
                    // （id = 清单最大 id + 1）。不再确认就按 id 删，会删掉新书的翻译行。
                    if (ImportedMangaStore.load(context).any { it.id == id }) return@forEach
                    dao.deleteManga(id)
                } catch (e: Exception) {
                    LogCollector.w("ImportMangaFragment", "清理孤儿翻译记录失败 id=$id", e)
                }
            }
        } catch (e: Exception) {
            LogCollector.w("ImportMangaFragment", "孤儿翻译记录清理失败", e)
        }
    }

    // ===== 显示选项 =====

    private fun loadDisplayPrefs() {
        val p = requireContext().getSharedPreferences("manga_import", android.content.Context.MODE_PRIVATE)
        displayMode = runCatching { DisplayMode.valueOf(p.getString("display_mode", DisplayMode.DETAILED_LIST.name)!!) }
            .getOrDefault(DisplayMode.DETAILED_LIST)
        gridSize = p.getInt("grid_size", 3)
        sortByAdded = p.getBoolean("sort_by_added", false)
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
    // 本页只做两件事 —— ① 观察 tasks 渲染占位卡片（图片位显示进度）② 把结果事件排队弹窗。
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
                    UiUtils.showToast(requireContext(), getString(R.string.import_cancel_too_late))
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
                    getString(
                        when (event.reason) {
                            ImportFailureReason.NOT_ARCHIVE -> R.string.import_fail_not_archive
                            ImportFailureReason.UNREADABLE -> R.string.import_fail_unreadable
                            ImportFailureReason.DIRECTORY -> R.string.import_fail_directory
                            ImportFailureReason.UNKNOWN -> R.string.import_fail_unknown
                        }
                    )
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
        resultDialog = AlertDialog.Builder(requireContext())
            .setTitle(item.first)
            .setMessage(item.second)
            .setPositiveButton(R.string.confirm) { _, _ ->
                dialogShowing = false
                resultDialogContent = null
                showNextResultDialog()
            }
            .setOnCancelListener {
                dialogShowing = false
                resultDialogContent = null
                showNextResultDialog()
            }
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
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
        val list0 = ImportedMangaStore.load(requireContext())
        // 推导「文件丢失」标记（瞬态，不入库）：localRoot 不存在 → lost=true。
        // 放入 data class 参与 DiffUtil 相等比较，文件被删/恢复后对应格子自动重绘。
        // ⚠️ 导入中的占位不算：它 localRoot 为空，按此判据会全部标成「文件丢失」
        val withLost = if (!forceLostCheck && list0 == cachedStored) {
            cachedStoredWithLost
        } else {
            list0.map { m -> if (m.importing) m else m.copy(lost = !File(m.localRoot).exists()) }
                .also {
                    cachedStored = list0
                    cachedStoredWithLost = it
                }
        }
        // 进行中的导入 → 占位卡片（在最前，导入完成的瞬间被同 id 的真实条目顶替，DiffUtil 原地刷新）
        val placeholders = ImportManager.tasks.value.map { it.toPlaceholder() }
        val all = placeholders + withLost
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