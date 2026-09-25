package com.moe.starflow.novel.shelf

import android.content.Context
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
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.UiUtils
import java.io.File
import java.util.Locale

/**
 * 小说书架 adapter（网格 + 详细信息行两种布局）。
 *
 * ⚠️ **绑定的是漫画书架的同一批布局**（`item_import_manga_card` / `item_import_manga_row`）：
 * 封面尺寸、卡片圆角、已读/丢失徽章、勾选遮罩、导入进度遮罩、标题与信息行的字号全都直接共用，
 * 两边不可能各自跑偏。这里只是把「页数」换成「章数」、把「阅读进度（页）」换成
 * 「阅读进度（章）」，并且不绑定小说没有的简介。
 *
 * 多选语义与漫画完全一致（Koto 式）：长按进入多选，点选/长按增减，导入中的占位不可选。
 */
class NovelShelfAdapter(
    displayMode: DisplayMode,
    private val onItemClick: (ImportedNovel) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit,
    private val onCancelImport: (Long) -> Unit = {},
) : ListAdapter<ImportedNovel, RecyclerView.ViewHolder>(Diff()) {

    private var displayMode = displayMode
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    val isSelectionMode: Boolean get() = selectionMode

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    /** 显示模式变化（网格/列表），viewType 变化整体重建渲染。 */
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
     *
     * ⚠️ 导入中的占位不可选中（还没有本地文件，选中会让「标为已读/删除」作用在半成品上）。
     */
    fun toggleSelection(id: Long) {
        if (currentList.firstOrNull { it.id == id }?.importing == true) return
        if (!selectionMode) {
            enterSelection(id)
            return
        }
        if (selectedIds.remove(id)) {
            onItemChanged(id)
            if (selectedIds.isEmpty()) exitSelection() else onSelectionChanged(true, selectedIds.size)
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

    /** 是否已全选。**只统计可选项**：导入中的占位不参与。 */
    fun isAllSelected(): Boolean {
        val selectable = currentList.count { !it.importing }
        return selectionMode && selectable > 0 && selectedIds.size == selectable
    }

    /** 全选 / 取消全选 切换（跳过导入中的占位）。 */
    fun toggleSelectAll() {
        if (isAllSelected()) {
            exitSelection()
            return
        }
        val ids = currentList.filterNot { it.importing }.map { it.id }
        if (ids.isEmpty()) return
        selectionMode = true
        selectedIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged(true, selectedIds.size)
    }

    class Diff : DiffUtil.ItemCallback<ImportedNovel>() {
        override fun areItemsTheSame(a: ImportedNovel, b: ImportedNovel) = a.id == b.id
        override fun areContentsTheSame(a: ImportedNovel, b: ImportedNovel) = a == b
    }

    class CardVH(val binding: ItemImportMangaCardBinding) : RecyclerView.ViewHolder(binding.root)
    class RowVH(val binding: ItemImportMangaRowBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_CARD = 0
        const val TYPE_ROW = 1
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

    private fun bindCard(h: CardVH, item: ImportedNovel) {
        val ctx = h.itemView.context
        h.binding.tvTitle.text = item.title
        UiUtils.marqueeTitle(h.binding.tvTitle)
        h.binding.tvTitle.isSelected = false
        // 网格卡没有独立的状态行：导入进度就写在章数那一行
        h.binding.tvPageCount.text = if (item.importing) {
            importStatusText(ctx, item)
        } else {
            ctx.getString(R.string.novel_chapter_count, item.chapterCount)
        }
        bindCover(
            coverView = h.binding.ivCover,
            readBadge = h.binding.tvReadBadge,
            lostBadge = h.binding.tvLostBadge,
            dim = h.binding.viewSelectionDim,
            check = h.binding.ivSelectionCheck,
            overlay = h.binding.importOverlay,
            percentView = h.binding.tvImportPercent,
            bar = h.binding.pbImport,
            cancel = h.binding.tvImportCancel,
            root = h.binding.root,
            titleView = h.binding.tvTitle,
            item = item,
        )
    }

    private fun bindRow(h: RowVH, item: ImportedNovel) {
        val ctx = h.itemView.context
        h.binding.tvTitle.text = item.title
        UiUtils.marqueeTitle(h.binding.tvTitle)
        h.binding.tvTitle.isSelected = false

        if (item.importing) {
            h.binding.tvInfoLine.text = importStatusText(ctx, item)
        } else {
            val size = formatSize(item.sizeBytes)
            val chapters = ctx.getString(R.string.novel_chapter_count, item.chapterCount)
            h.binding.tvInfoLine.text = if (size.isNotEmpty()) "$chapters · $size" else chapters
        }

        // 阅读进度（已开始阅读时显示；导入中的占位不算已读）
        if (!item.importing && (item.lastReadChapter > 0 || item.lastReadParaIndex > 0)) {
            h.binding.tvProgress.visibility = View.VISIBLE
            h.binding.tvProgress.text = ctx.getString(
                R.string.novel_read_progress,
                (item.lastReadChapter + 1).coerceAtMost(item.chapterCount.coerceAtLeast(1)),
                item.chapterCount,
            )
        } else {
            h.binding.tvProgress.visibility = View.GONE
        }

        // 简介（非空显示）：与漫画书架同一行、同一位置，由「写简介」写入
        if (!item.importing && item.description.isNotBlank()) {
            h.binding.tvDescription.visibility = View.VISIBLE
            h.binding.tvDescription.text = item.description
        } else {
            h.binding.tvDescription.visibility = View.GONE
        }

        bindCover(
            coverView = h.binding.ivCover,
            readBadge = h.binding.tvReadBadge,
            lostBadge = h.binding.tvLostBadge,
            dim = h.binding.viewSelectionDim,
            check = h.binding.ivSelectionCheck,
            overlay = h.binding.importOverlay,
            percentView = h.binding.tvImportPercent,
            bar = h.binding.pbImport,
            cancel = h.binding.tvImportCancel,
            root = h.binding.root,
            titleView = h.binding.tvTitle,
            item = item,
        )
    }

    /** 占位的进度文案：优先百分比，其次阶段名。 */
    private fun importStatusText(ctx: Context, item: ImportedNovel): String =
        when (item.importPhase) {
            ImportPhase.SCANNING -> ctx.getString(R.string.import_task_scanning)
            ImportPhase.COPYING, null -> if (item.importPercent in 0..100) {
                ctx.getString(R.string.import_task_copying_percent, item.importPercent)
            } else {
                ctx.getString(R.string.import_task_copying)
            }
        }

    /** 封面加载 + 已读/文件丢失徽章 + 多选勾选态 + 导入进度遮罩 + 点击/长按（卡片与行共用）。 */
    private fun bindCover(
        coverView: android.widget.ImageView,
        readBadge: View,
        lostBadge: View,
        dim: View,
        check: View,
        overlay: View,
        percentView: android.widget.TextView,
        bar: android.widget.ProgressBar,
        cancel: View,
        root: View,
        titleView: android.widget.TextView,
        item: ImportedNovel,
    ) {
        val importing = item.importing
        // 占位没有本地路径，绝不能被判成「文件丢失」
        val lost = item.lost && !importing
        // 「已读完」的判据与漫画同构：进度落在最后一章且章内已推进过
        val isRead = !importing && !lost && item.chapterCount > 0 &&
            item.lastReadChapter >= item.chapterCount - 1 && item.lastReadParaIndex > 0
        readBadge.visibility = if (isRead) View.VISIBLE else View.GONE
        lostBadge.visibility = if (lost) View.VISIBLE else View.GONE
        coverView.alpha = if (lost) 0.35f else 1f

        val cover = item.coverPath?.let { File(it) }
        Glide.with(root)
            .load(cover)
            .placeholder(R.drawable.import_manga_icon)
            .into(coverView)

        overlay.visibility = if (importing) View.VISIBLE else View.GONE
        if (importing) {
            val p = item.importPercent
            val determinate = p in 0..100
            bar.isIndeterminate = !determinate
            if (determinate) bar.progress = p
            percentView.text = if (determinate) {
                root.context.getString(R.string.import_task_percent, p)
            } else {
                ""
            }
            cancel.setOnClickListener { onCancelImport(item.id) }
        } else {
            cancel.setOnClickListener(null)
        }

        val selected = selectionMode && item.id in selectedIds
        dim.visibility = if (selected) View.VISIBLE else View.GONE
        check.visibility = if (selected) View.VISIBLE else View.GONE

        root.setOnClickListener {
            if (item.importing) return@setOnClickListener
            titleView.isSelected = true
            if (selectionMode) toggleSelection(item.id) else onItemClick(item)
        }
        root.setOnLongClickListener {
            if (item.importing) return@setOnLongClickListener false
            toggleSelection(item.id)
            true
        }
    }

    /** 文件大小格式化：B / KB / MB / GB（与漫画书架同口径）。 */
    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024 * 1024))
    }
}
