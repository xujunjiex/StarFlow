package com.moe.starflow.ui.history
import com.moe.starflow.ui.viewer.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.HistoryEntity
import com.moe.starflow.databinding.ItemHistoryGameBinding
import com.moe.starflow.databinding.ItemHistoryGameCompactBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游戏历史列表适配器。
 *
 * [compact] = true 时走紧凑布局（`item_history_game_compact.xml`）：只画译文、四边留白最小，
 * 用于小尺寸屏/横屏一屏看更多结果；点按复制、长按删除与卡片模式一致。
 */
class HistoryGameAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val compact: Boolean = false
) : ListAdapter<HistoryEntry, RecyclerView.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (compact) {
            CompactViewHolder(ItemHistoryGameCompactBinding.inflate(inflater, parent, false))
        } else {
            ViewHolder(ItemHistoryGameBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = getItem(position)
        when (holder) {
            is ViewHolder -> holder.bind(entry)
            is CompactViewHolder -> holder.bind(entry)
        }
    }

    /** 紧凑行：只有译文（仅 OCR 的记录没有译文，给占位避免出现不可点的零高度行）。 */
    inner class CompactViewHolder(
        private val binding: ItemHistoryGameCompactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HistoryEntry) {
            binding.tvCompactTranslatedText.text = entry.translatedText?.takeIf { it.isNotBlank() }
                ?: binding.root.context.getString(com.moe.starflow.R.string.history_compact_no_translation)
            binding.root.setOnClickListener { onItemClick(entry) }
            binding.root.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
        }
    }

    inner class ViewHolder(
        private val binding: ItemHistoryGameBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HistoryEntry) {
            binding.tvSourceText.text = entry.sourceText ?: ""
            binding.tvTranslatedText.text = entry.translatedText ?: ""
            binding.tvTranslatorName.text = if (entry.processingState == HistoryEntity.PROCESS_OCR)
                binding.root.context.getString(com.moe.starflow.R.string.history_ocr_badge)
            else entry.translatorName
            binding.tvTime.text = dateFormat.format(Date(entry.updatedAt))

            binding.root.setOnClickListener { onItemClick(entry) }
            binding.root.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean {
            return oldItem == newItem
        }
    }
}
