package com.moe.starflow.mangaimport.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moe.starflow.R
import com.moe.starflow.databinding.ItemImportMangaCardBinding
import com.moe.starflow.databinding.ItemImportMangaRowBinding
import com.moe.starflow.mangaimport.data.ImportedManga
import java.io.File
import java.util.Locale

/** 书架显示模式（默认=详细信息列表）。 */
enum class DisplayMode { DETAILED_LIST, GRID }

/**
 * 书架 adapter（网格 + 行两种布局）。
 *
 * - GRID：网格卡（竖版封面 + 书名 + 页数，列数由显示选项的网格尺寸控制）
 * - DETAILED_LIST：横排行（左竖版缩略图 + 名称/页数/大小/阅读进度 + 简介）
 *
 * 多选管理（Koto 式）：长按进入多选模式，点选/长按增减选中，卡片显示勾选态；
 * 非多选时点击卡片进阅读器。
 *
 * @param onItemClick 非多选时点击卡片：进阅读器
 * @param onSelectionChanged 选中态变化回调（selectionMode, 已选数量），驱动顶部栏
 */
class MangaGridAdapter(
    displayMode: DisplayMode,
    private val onItemClick: (ImportedManga) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit
) : ListAdapter<ImportedManga, RecyclerView.ViewHolder>(Diff()) {

    private var displayMode = displayMode
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    val isSelectionMode: Boolean get() = selectionMode

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    /** 显示模式变化（网格/列表/尺寸），viewType 变化整体重建渲染。 */
    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        notifyDataSetChanged()
    }

    /** 进入多选并选中该项（首次长按）。 */
    fun enterSelection(id: Long) {
        selectionMode = true
        selectedIds += id
        onItemChanged(id)
        onSelectionChanged(true, selectedIds.size)
    }

    /** 退出多选模式。 */
    fun exitSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(false, 0)
    }

    /**
     * 点/长按卡片：多选模式内切换选中；否则进入多选。
     * 最后一个已选项被取消时自动退出多选。
     */
    fun toggleSelection(id: Long) {
        if (!selectionMode) {
            enterSelection(id)
            return
        }
        if (selectedIds.remove(id)) {
            onItemChanged(id)
            if (selectedIds.isEmpty()) {
                exitSelection()
            } else {
                onSelectionChanged(true, selectedIds.size)
            }
        } else {
            selectedIds += id
            onItemChanged(id)
            onSelectionChanged(true, selectedIds.size)
        }
    }

    private fun onItemChanged(id: Long) {
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    /** 是否已全选（用于全选按钮文案）。 */
    fun isAllSelected(): Boolean =
        selectionMode && currentList.isNotEmpty() && selectedIds.size == currentList.size

    /** 全选 / 取消全选 切换。 */
    fun toggleSelectAll() {
        if (isAllSelected()) {
            exitSelection()
        } else {
            selectionMode = true
            selectedIds.addAll(currentList.map { it.id })
            notifyDataSetChanged()
            onSelectionChanged(true, selectedIds.size)
        }
    }

    class Diff : DiffUtil.ItemCallback<ImportedManga>() {
        override fun areItemsTheSame(a: ImportedManga, b: ImportedManga) = a.id == b.id
        override fun areContentsTheSame(a: ImportedManga, b: ImportedManga) = a == b
    }

    class CardVH(val binding: ItemImportMangaCardBinding) : RecyclerView.ViewHolder(binding.root)
    class RowVH(val binding: ItemImportMangaRowBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_ROW = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (displayMode == DisplayMode.GRID) TYPE_CARD else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_CARD) {
            CardVH(ItemImportMangaCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            RowVH(ItemImportMangaRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is CardVH -> bindCard(holder, item)
            is RowVH -> bindRow(holder, item)
        }
    }

    private fun bindCard(h: CardVH, item: ImportedManga) {
        h.binding.tvTitle.text = item.title
        h.binding.tvPageCount.text = h.itemView.context.getString(R.string.import_pages_count, item.pageCount)
        // 网格/紧凑网格不显示简介（详细信息列表才显示）
        bindCover(
            coverView = h.binding.ivCover,
            readBadge = h.binding.tvReadBadge,
            dim = h.binding.viewSelectionDim,
            check = h.binding.ivSelectionCheck,
            root = h.binding.root,
            item = item
        )
    }

    private fun bindRow(h: RowVH, item: ImportedManga) {
        h.binding.tvTitle.text = item.title

        // 详细信息：页数 · 大小
        val size = formatSize(item.sizeBytes)
        val pages = h.itemView.context.getString(R.string.import_pages_count, item.pageCount)
        h.binding.tvInfoLine.text = if (size.isNotEmpty()) "$pages · $size" else pages

        // 阅读进度（已开始阅读时显示）
        if (item.lastReadPage > 0) {
            h.binding.tvProgress.visibility = View.VISIBLE
            h.binding.tvProgress.text = h.itemView.context.getString(
                R.string.import_read_progress,
                item.lastReadPage + 1,
                item.pageCount
            )
        } else {
            h.binding.tvProgress.visibility = View.GONE
        }

        // 简介（非空显示）
        if (item.description.isNotBlank()) {
            h.binding.tvDescription.visibility = View.VISIBLE
            h.binding.tvDescription.text = item.description
        } else {
            h.binding.tvDescription.visibility = View.GONE
        }

        bindCover(
            coverView = h.binding.ivCover,
            readBadge = h.binding.tvReadBadge,
            dim = h.binding.viewSelectionDim,
            check = h.binding.ivSelectionCheck,
            root = h.binding.root,
            item = item
        )
    }

    /** 封面加载 + 已读徽章 + 多选勾选态 + 点击/长按（卡片与行共用）。 */
    private fun bindCover(
        coverView: android.widget.ImageView,
        readBadge: View,
        dim: View,
        check: View,
        root: View,
        item: ImportedManga
    ) {
        val isRead = item.pageCount > 0 && item.lastReadPage >= item.pageCount - 1
        readBadge.visibility = if (isRead) View.VISIBLE else View.GONE

        val cover = item.coverPath?.let { File(it) }
        Glide.with(root)
            .load(cover)
            .placeholder(R.drawable.import_manga_icon)
            .into(coverView)

        val selected = selectionMode && item.id in selectedIds
        dim.visibility = if (selected) View.VISIBLE else View.GONE
        check.visibility = if (selected) View.VISIBLE else View.GONE

        root.setOnClickListener {
            if (selectionMode) toggleSelection(item.id) else onItemClick(item)
        }
        root.setOnLongClickListener { toggleSelection(item.id); true }
    }

    /** 文件大小格式化：B / KB / MB / GB。 */
    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024 * 1024))
    }
}