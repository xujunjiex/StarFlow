package com.moe.starflow.mangaimport.ui

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
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.utils.UiUtils
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
 * 导入中的占位（`ImportedManga.importing`）：封面位压一层进度遮罩（进度条 + 百分比 + ✕ 取消），
 * **不可打开、不可多选**（它还没有本地文件，选中会让「标为已读/删除」作用在半成品上）。
 *
 * @param onItemClick 非多选时点击卡片：进阅读器
 * @param onSelectionChanged 选中态变化回调（selectionMode, 已选数量），驱动顶部栏
 * @param onCancelImport 点击占位卡上的 ✕：请求取消该次导入
 */
class MangaGridAdapter(
    displayMode: DisplayMode,
    private val onItemClick: (ImportedManga) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit,
    private val onCancelImport: (Long) -> Unit = {}
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
     *
     * ⚠️ 导入中的占位不可选中（见类注释）。
     */
    fun toggleSelection(id: Long) {
        if (currentList.firstOrNull { it.id == id }?.importing == true) return
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

    /** 是否已全选（用于全选按钮文案）。**只统计可选项**：导入中的占位不参与。 */
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
        val ctx = h.itemView.context
        h.binding.tvTitle.text = item.title
        // 单行标题统一规范：省略号 + 选中滚动（见 UiUtils.marqueeTitle）
        UiUtils.marqueeTitle(h.binding.tvTitle)
        h.binding.tvTitle.isSelected = false
        // 网格卡没有独立的状态行：导入进度就写在页数那一行
        h.binding.tvPageCount.text = if (item.importing) {
            importStatusText(ctx, item)
        } else {
            ctx.getString(R.string.import_pages_count, item.pageCount)
        }
        // 网格/紧凑网格不显示简介（详细信息列表才显示）
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
            item = item
        )
    }

    private fun bindRow(h: RowVH, item: ImportedManga) {
        val ctx = h.itemView.context
        h.binding.tvTitle.text = item.title
        UiUtils.marqueeTitle(h.binding.tvTitle)
        h.binding.tvTitle.isSelected = false

        if (item.importing) {
            // 导入中：信息行改成进度文案（页数/大小还不知道），阅读进度行让位
            h.binding.tvInfoLine.text = importStatusText(ctx, item)
        } else {
            // 详细信息：页数 · 大小
            val size = formatSize(item.sizeBytes)
            val pages = ctx.getString(R.string.import_pages_count, item.pageCount)
            h.binding.tvInfoLine.text = if (size.isNotEmpty()) "$pages · $size" else pages
        }

        // 阅读进度（已开始阅读时显示；导入中的占位不算已读）
        if (!item.importing && item.lastReadPage > 0) {
            h.binding.tvProgress.visibility = View.VISIBLE
            h.binding.tvProgress.text = ctx.getString(
                R.string.import_read_progress,
                item.lastReadPage + 1,
                item.pageCount
            )
        } else {
            h.binding.tvProgress.visibility = View.GONE
        }

        // 简介（非空显示）
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
            item = item
        )
    }

    /** 占位的进度文案：优先百分比，其次阶段名。 */
    private fun importStatusText(ctx: Context, item: ImportedManga): String =
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
        item: ImportedManga
    ) {
        val importing = item.importing
        // 占位没有本地路径，绝不能被判成「文件丢失」
        val lost = item.lost && !importing
        val isRead = !importing && !lost && item.pageCount > 0 && item.lastReadPage >= item.pageCount - 1
        readBadge.visibility = if (isRead) View.VISIBLE else View.GONE
        lostBadge.visibility = if (lost) View.VISIBLE else View.GONE
        // 文件丢失时封面压暗，提示为失效条目
        coverView.alpha = if (lost) 0.35f else 1f

        val cover = item.coverPath?.let { File(it) }
        Glide.with(root)
            .load(cover)
            .placeholder(R.drawable.import_manga_icon)
            .into(coverView)

        // 导入中的占位：图片位压一层进度遮罩（进度未知 → 不确定进度条）
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
            // 导入中的占位不可打开（还没有本地文件）
            if (item.importing) return@setOnClickListener
            // 标题选中置位 → marquee 滚动显示完整标题；复用/取消时 bind 里复位
            titleView.isSelected = true
            if (selectionMode) toggleSelection(item.id) else onItemClick(item)
        }
        root.setOnLongClickListener {
            if (item.importing) return@setOnLongClickListener false
            toggleSelection(item.id)
            true
        }
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