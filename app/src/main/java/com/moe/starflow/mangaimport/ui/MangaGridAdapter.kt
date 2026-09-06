package com.moe.starflow.mangaimport.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moe.starflow.R
import com.moe.starflow.databinding.ItemImportMangaCardBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import java.io.File

/** 书架显示模式。 */
enum class DisplayMode { LIST, DETAILED_LIST, GRID, COMPACT_GRID }

/**
 * 书架网格 adapter（封面 + 书名）。
 * @param onItemClick 点击进阅读器
 * @param onItemLongClick 长按弹删除
 */
class MangaGridAdapter(
    private val onItemClick: (ImportedManga) -> Unit,
    private val onItemLongClick: (ImportedManga) -> Unit
) : ListAdapter<ImportedManga, MangaGridAdapter.VH>(Diff()) {

    class VH(val binding: ItemImportMangaCardBinding) : RecyclerView.ViewHolder(binding.root)

    class Diff : DiffUtil.ItemCallback<ImportedManga>() {
        override fun areItemsTheSame(a: ImportedManga, b: ImportedManga) = a.id == b.id
        override fun areContentsTheSame(a: ImportedManga, b: ImportedManga) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemImportMangaCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.tvTitle.text = item.title
        holder.binding.tvPageCount.text = "${item.pageCount} 页"
        val cover = item.coverPath?.let { File(it) }
        Glide.with(holder.itemView)
            .load(cover)
            .placeholder(R.drawable.import_manga_icon)
            .into(holder.binding.ivCover)
        holder.binding.root.setOnClickListener { onItemClick(item) }
        holder.binding.root.setOnLongClickListener { onItemLongClick(item); true }
    }
}
