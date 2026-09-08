package com.moe.starflow.mangaimport

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentImportMangaBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.mangaimport.data.StorageDirStore
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
 * 展示导入漫画清单网格，支持导入（文件/文件夹）、存储目录切换、显示选项、长按删除。
 *
 * 存储目录与目录导入需要「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE），
 * 授权后 SAF 选择器可选任意已有目录（Kototoro 同款做法）；未授权时引导去系统设置页授权。
 */
class ImportMangaFragment : Fragment() {

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MangaGridAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortByAdded = false

    // 文件导入（不需所有文件权限，单文件多选可用）
    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importArchives(uris)
        }

    // 导入文件夹（需要所有文件权限）
    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistReadable(uri)
                importDirectory(uri)
            }
        }

    // 存储目录（需要所有文件权限，可选任意已有目录）
    private val pickStorageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistReadable(uri)
                val name = DocumentFile.fromTreeUri(requireContext(), uri)?.name
                    ?: uri.lastPathSegment
                    ?: getString(R.string.storage_directory)
                StorageDirStore.setCustom(requireContext(), uri.toString(), name)
                updateStorageDirLabel()
                UiUtils.showToast(requireContext(), getString(R.string.storage_dir_set))
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
            onItemClick = { manga ->
                val intent = Intent(requireContext(), com.moe.starflow.mangaimport.reader.MangaReaderActivity::class.java)
                intent.putExtra(com.moe.starflow.mangaimport.reader.MangaReaderActivity.EXTRA_MANGA_ID, manga.id)
                startActivity(intent)
            },
            onItemLongClick = { showDeleteDialog(it) }
        )
        binding.recyclerView.adapter = adapter

        binding.fabImport.setOnClickListener {
            ImportDialog.show(
                requireContext(),
                onPickFiles = { pickFilesLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onPickSingleDir = { ensureAllFilesAccessThen { pickDirLauncher.launch(null) } },
                onPickMultiDir = { ensureAllFilesAccessThen { pickDirLauncher.launch(null) } }
            )
        }

        binding.tvDisplayOptions.setOnClickListener {
            DisplayOptionsSheet(displayMode, gridSize, sortByAdded) { m, s, sa ->
                applyDisplay(m, s, sa)
            }.show(parentFragmentManager, DisplayOptionsSheet.TAG)
        }

        binding.tvStorageDir.setOnClickListener {
            ensureAllFilesAccessThen { pickStorageLauncher.launch(null) }
        }
        updateStorageDirLabel()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页授权返回后刷新
        refresh()
        updateStorageDirLabel()
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

    /** 检查「所有文件访问」权限，未授权则弹引导去系统设置页；已授权执行 onGranted。 */
    private fun ensureAllFilesAccessThen(onGranted: () -> Unit) {
        if (StorageDirStore.hasAllFilesAccess(requireContext())) {
            onGranted()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.all_files_access_title)
            .setMessage(R.string.all_files_access_msg)
            .setPositiveButton(R.string.all_files_access_go) { _, _ ->
                try {
                    startActivity(StorageDirStore.allFilesAccessIntent(requireContext()))
                } catch (e: Exception) {
                    UiUtils.showToast(requireContext(), getString(R.string.all_files_access_fail))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadDisplayPrefs() {
        val p = requireContext().getSharedPreferences("manga_import", android.content.Context.MODE_PRIVATE)
        displayMode = runCatching { DisplayMode.valueOf(p.getString("display_mode", DisplayMode.GRID.name)!!) }
            .getOrDefault(DisplayMode.GRID)
        gridSize = p.getInt("grid_size", 3)
        sortByAdded = p.getBoolean("sort_by_added", false)
    }

    /** 存储目录入口显示：默认 → app 目录路径；自定义 → 文件夹名。 */
    private fun updateStorageDirLabel() {
        binding.tvStorageDir.text = StorageDirStore.describe(requireContext())
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
        val list = if (sortByAdded) list0.sortedByDescending { it.addedAt } else list0.sortedBy { it.title }
        val cols = when (displayMode) {
            DisplayMode.GRID -> gridSize
            DisplayMode.COMPACT_GRID -> gridSize + 1
            else -> 1
        }
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), cols)
        adapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDeleteDialog(manga: ImportedManga) {
        AlertDialog.Builder(requireContext())
            .setTitle(manga.title)
            .setMessage(getString(R.string.import_manga_delete_confirm))
            .setPositiveButton(getString(R.string.confirm_delete)) { _, _ ->
                ImportedMangaStore.remove(requireContext(), manga.id)
                deleteImportedFiles(manga)
                refresh()
                UiUtils.showToast(requireContext(), getString(R.string.import_manga_deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 删除导入的本地内容：File 路径或 content uri 两种都兼容。 */
    private fun deleteImportedFiles(manga: ImportedManga) {
        try {
            if (manga.localRoot.startsWith("content://")) {
                val doc = if (manga.isArchive) {
                    DocumentFile.fromSingleUri(requireContext(), Uri.parse(manga.localRoot))
                } else {
                    DocumentFile.fromTreeUri(requireContext(), Uri.parse(manga.localRoot))
                }
                doc?.delete()
            } else {
                val local = File(manga.localRoot)
                if (local.isDirectory) local.deleteRecursively() else local.delete()
            }
            manga.coverPath?.let { File(it).delete() }
        } catch (e: Exception) {
            LogCollector.w("ImportMangaFragment", "删除导入文件失败: ${manga.id}", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}