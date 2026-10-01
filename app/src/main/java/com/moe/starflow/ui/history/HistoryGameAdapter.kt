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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryGameAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryEntry, HistoryGameAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryGameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
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
