package com.moe.starflow.mangaimport

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentImportMangaBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.mangaimport.ui.DisplayOptionsSheet
import com.moe.starflow.mangaimport.ui.ImportDialog
import com.moe.starflow.mangaimport.ui.MangaGridAdapter
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入翻译 tab：书架页。展示导入漫画清单网格，支持导入（三选一）、显示选项、长按删除。
 */
class ImportMangaFragment : Fragment() {

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MangaGridAdapter

    private var displayMode = DisplayMode.GRID
    private var gridSize = 3
    private var sortByAdded = false

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importArchives(uris)
        }

    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importDirectory(uri)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportMangaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadDisplayPrefs()

        adapter = MangaGridAdapter(
            onItemClick = { /* 后续 Task 接阅读器跳转 */ },
            onItemLongClick = { showDeleteDialog(it) }
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

        binding.tvDisplayOptions.setOnClickListener {
            DisplayOptionsSheet(displayMode, gridSize, sortByAdded) { m, s, sa ->
                applyDisplay(m, s, sa)
            }.show(parentFragmentManager, DisplayOptionsSheet.TAG)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun loadDisplayPrefs() {
        val p = requireContext().getSharedPreferences("manga_import", android.content.Context.MODE_PRIVATE)
        displayMode = runCatching { DisplayMode.valueOf(p.getString("display_mode", DisplayMode.GRID.name)!!) }
            .getOrDefault(DisplayMode.GRID)
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
                val local = File(manga.localRoot)
                if (local.isDirectory) local.deleteRecursively() else local.delete()
                manga.coverPath?.let { File(it).delete() }
                refresh()
                UiUtils.showToast(requireContext(), getString(R.string.import_manga_deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
