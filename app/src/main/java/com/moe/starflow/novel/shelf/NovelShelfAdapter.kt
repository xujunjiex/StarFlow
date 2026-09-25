package com.moe.starflow.novel.shelf

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moe.starflow.R
import com.moe.starflow.novel.data.ImportedNovel
import java.io.File

/**
 * 小说书架适配器。
 *
 * ⚠️ 用 `ListAdapter` + `DiffUtil`：导入完成时占位条目会被真实条目**原地替换**（id 相同），
 * 没有 DiffUtil 就会整列表重绑、封面闪烁。
 *
 * ⚠️ 用 `areContentsTheSame` 比的是整个数据类：占位卡片的进度每次变化都会触发重绑，
 * 这正是进度条要的效果；而真实条目只有字段真变了才重绑。
 */
class NovelShelfAdapter(
    private val onClick: (ImportedNovel) -> Unit,
    private val onCancelImport: (Long) -> Unit,
    private val onLongClick: (ImportedNovel) -> Unit,
) : ListAdapter<ImportedNovel, NovelShelfAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ImportedNovel>() {
            override fun areItemsTheSame(a: ImportedNovel, b: ImportedNovel) = a.id == b.id
            override fun areContentsTheSame(a: ImportedNovel, b: ImportedNovel) = a == b
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.novel_cover)
        val title: TextView = view.findViewById(R.id.novel_item_title)
        val subtitle: TextView = view.findViewById(R.id.novel_item_subtitle)
        val progress: ProgressBar = view.findViewById(R.id.novel_item_progress)
        val cancel: TextView = view.findViewById(R.id.novel_item_cancel)
        val badge: TextView = view.findViewById(R.id.novel_item_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_novel_shelf_card, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        holder.title.text = item.title

        holder.cancel.visibility = if (item.importing) View.VISIBLE else View.GONE
        holder.progress.visibility = if (item.importing) View.VISIBLE else View.GONE
        holder.badge.visibility = if (item.lost) View.VISIBLE else View.GONE

        if (item.importing) {
            val pct = item.importPercent
            holder.progress.isIndeterminate = pct < 0
            if (pct >= 0) holder.progress.progress = pct
            holder.subtitle.text = ctx.getString(
                R.string.novel_importing_percent,
                if (pct < 0) "…" else "$pct%",
            )
            // 占位没有封面：清掉复用 View 上残留的旧图，否则会显示上一本书的封面
            holder.cover.setImageDrawable(null)
        } else {
            holder.subtitle.text = ctx.getString(R.string.novel_chapter_count, item.chapterCount)
            val cover = item.coverPath
            if (!cover.isNullOrEmpty() && File(cover).exists()) {
                Glide.with(holder.cover).load(File(cover)).into(holder.cover)
            } else {
                holder.cover.setImageDrawable(null)
            }
        }

        holder.itemView.setOnClickListener { if (!item.importing) onClick(item) }
        holder.itemView.setOnLongClickListener {
            if (!item.importing) onLongClick(item)
            !item.importing
        }
        holder.cancel.setOnClickListener { onCancelImport(item.id) }
    }
}
