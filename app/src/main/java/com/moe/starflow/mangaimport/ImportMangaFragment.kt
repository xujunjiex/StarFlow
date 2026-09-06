package com.moe.starflow.mangaimport

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentImportMangaBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.ui.MangaGridAdapter
import java.io.File

/**
 * 导入翻译 tab：书架页。展示导入漫画清单网格，支持长按删除、右下角导入。
 */
class ImportMangaFragment : Fragment() {

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MangaGridAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportMangaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MangaGridAdapter(
            onItemClick = { /* 后续 Task 接阅读器跳转 */ },
            onItemLongClick = { showDeleteDialog(it) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = ImportedMangaStore.load(requireContext())
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
                // 封面缩略图一并删
                manga.coverPath?.let { File(it).delete() }
                refresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
