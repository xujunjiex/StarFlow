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
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
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

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MangaGridAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortByAdded = false

    // 返回键：多选模式下退出多选
    private lateinit var backCallback: OnBackPressedCallback

    // 文件导入（SAF 多选 zip/cbz）
    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importArchives(uris)
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
                importDirectory(uri)
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
            onSelectionChanged = { mode, count -> showSelectionUi(mode, count) }
        )
        binding.recyclerView.adapter = adapter

        binding.fabImport.setOnClickListener {
            ImportDialog.show(
                requireContext(),
                onPickFiles = { pickFilesLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onPickSingleDir = { pickDirLauncher.launch(null) },
                onPickMultiDir = { pickDirLauncher.launch(null) }
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

        // 一次性迁移：旧版存放在 filesDir（用户不可访问）的导入漫画搬到外部专属目录
        // （Android/data/<pkg>/files），完成后刷新清单让新路径生效
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                StorageDirStore.migrate(requireContext())
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    /** 删除选中的漫画（含 app 内部存储的本地副本）。 */
    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val toDelete = ImportedMangaStore.load(requireContext()).filter { it.id in ids }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_delete)
            .setMessage(getString(R.string.import_delete_selected_confirm, ids.size))
            .setPositiveButton(R.string.import_delete) { _, _ ->
                toDelete.forEach { manga ->
                    ImportedMangaStore.remove(requireContext(), manga.id)
                    deleteImportedFiles(manga)
                }
                adapter.exitSelection()
                refresh()
                UiUtils.showToast(requireContext(), getString(R.string.import_manga_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
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

    private fun importArchives(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching { MangaImporter.importArchive(requireContext(), uri) }
                }
            }
            refresh()
        }
    }

    private fun importDirectory(uri: Uri) {
        // 阶段一：单夹/多夹都按「整个夹=一部」处理（多夹遍历属后续后端）
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { MangaImporter.importDirectory(requireContext(), uri) }
            }
            refresh()
        }
    }

    private fun refresh() {
        val list0 = ImportedMangaStore.load(requireContext())
        // 推导「文件丢失」标记（瞬态，不入库）：localRoot 不存在 → lost=true。
        // 放入 data class 参与 DiffUtil 相等比较，文件被删/恢复后对应格子自动重绘
        val withLost = list0.map { m -> m.copy(lost = !File(m.localRoot).exists()) }
        val list = if (sortByAdded) withLost.sortedByDescending { it.addedAt } else withLost.sortedBy { it.title }
        val cols = when (displayMode) {
            DisplayMode.GRID -> gridSize
            else -> 1
        }
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), cols)
        adapter.setDisplayMode(displayMode)
        adapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}