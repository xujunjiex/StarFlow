package com.moe.starflow.ui.history
import com.moe.starflow.ui.viewer.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.content.Context
import android.graphics.BitmapFactory
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 漫画历史条目适配器。
 * 支持多种显示模式：list / large / medium / small
 * @param colorMap entryId → 颜色组号（用于跨 session 的 pHash 分组着色）
 * @param displayMode 显示模式
 * @param onThumbnailClick 缩略图点击回调
 */
class HistoryMangaAdapter(
    private val onItemClick: (GroupedHistoryEntry) -> Unit,
    private val onItemLongClick: (GroupedHistoryEntry) -> Unit,
    private val colorMap: Map<Long, Int> = emptyMap(),
    private val displayMode: String = "large",
    private val sortByUpdated: Boolean = false,
    private val onThumbnailClick: ((HistoryEntry) -> Unit)? = null,
    private val isManageView: Boolean = false
) : ListAdapter<GroupedHistoryEntry, RecyclerView.ViewHolder>(DiffCallback()) {

    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val monthTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isSmall get() = displayMode == "small"
    private val isMedium get() = displayMode == "medium"

    companion object {
        private val GROUP_COLORS = intArrayOf(
            android.graphics.Color.parseColor("#FFFFFF"),    // 默认白色
            android.graphics.Color.parseColor("#15FF6B6B"),  // 红
            android.graphics.Color.parseColor("#154FC3F7"),  // 蓝
            android.graphics.Color.parseColor("#1581C784"),  // 绿
            android.graphics.Color.parseColor("#15FFD54F"),  // 黄
            android.graphics.Color.parseColor("#15CE93D8"),  // 紫
            android.graphics.Color.parseColor("#15FFAB91"),  // 橙
        )

        private val TRANSLATOR_DISPLAY_NAMES = mapOf(
            "OpenAITranslation" to R.string.nt_openai,
            "BingTranslation" to R.string.nt_bing,
            "NLLBTranslation" to R.string.nt_nllb,
            "NiuTransTranslation" to R.string.nt_niu,
            "VolcTranslation" to R.string.nt_volc,
            "DeepLTranslation" to R.string.nt_deepl,
            "BaiduTranslation" to R.string.nt_baidu,
            "TencentCloudTranslation" to R.string.nt_tencent,
            "AzureTranslation" to R.string.nt_azure,
        )

        /** 译名走资源（中英各一份），避免历史页在英文模式下显示「火山/小牛/百度/腾讯」。 */
        fun getDisplayName(context: Context, translatorName: String): String {
            val apiPart = translatorName.split(" | ").first().trim()
            val match = Regex("^(\\w+?)\\((.+)\\)$").find(apiPart)
            if (match != null) {
                val model = match.groupValues[2]
                return model  // 只显示模型名，不要 API 前缀
            }
            return TRANSLATOR_DISPLAY_NAMES[apiPart]?.let { context.getString(it) } ?: apiPart
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (displayMode == "list") 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_history_manga_list else R.layout.item_history_manga
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as ViewHolder).bind(getItem(position))
    }

    inner class ViewHolder(view: View, private val viewType: Int) : RecyclerView.ViewHolder(view) {
        private val ivThumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        private val tvTranslatorName: TextView = view.findViewById(R.id.tv_translator_name)
        // Card mode (viewType == 0):
        private val tvTime: TextView? = if (viewType == 0) view.findViewById(R.id.tv_time) else null
        // List mode (viewType == 1):
        private val tvCreateTime: TextView? = if (viewType == 1) view.findViewById(R.id.tv_create_time) else null
        private val tvModifyTime: TextView? = if (viewType == 1) view.findViewById(R.id.tv_modify_time) else null
        private val tvPhash: TextView = view.findViewById(R.id.tv_phash)
        private val badgeContainer: View = view.findViewById(R.id.badgeContainer)
        private val tvSizeBadge: TextView = view.findViewById(R.id.tv_size_badge)
        private val tvRetranslateBadge: TextView = view.findViewById(R.id.tvRetranslateBadge)

        fun bind(grouped: GroupedHistoryEntry) {
            val entry = grouped.representative

            // 加载缩略图
            if (entry.thumbnailPath != null && File(entry.thumbnailPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(entry.thumbnailPath)
                ivThumbnail.setImageBitmap(bitmap)
            } else {
                ivThumbnail.setImageBitmap(null)
            }

            // 翻译器名（模型名）
            tvTranslatorName.text = getDisplayName(itemView.context, entry.translatorName)

            // pHash
            tvPhash.text = if (entry.pHash != 0L) String.format("%016X", entry.pHash) else ""

            if (viewType == 1) {
                // === List 模式：完整时间信息 ===
                val createdStr = fullDateFormat.format(Date(entry.createdAt))
                val updatedStr = fullDateFormat.format(Date(entry.updatedAt))
                tvCreateTime?.text = itemView.context.getString(R.string.history_created_format, createdStr)
                tvModifyTime?.text = itemView.context.getString(R.string.history_updated_format, updatedStr)

                // 如果创建时间和修改时间相同，隐藏修改时间避免冗余
                if (entry.createdAt == entry.updatedAt) {
                    tvModifyTime?.visibility = View.GONE
                } else {
                    tvModifyTime?.visibility = View.VISIBLE
                }
            } else {
                // === Card 模式（large / medium / small）===
                val updatedStr = if (isSmall) {
                    shortDateFormat.format(Date(entry.updatedAt))
                } else {
                    monthTimeFormat.format(Date(entry.updatedAt))
                }
                tvTime?.text = if (isSmall) {
                    updatedStr
                } else {
                    // 与 list 模式同源（history_updated_format），别再硬编码中文
                    itemView.context.getString(R.string.history_updated_format, updatedStr)
                }

                // 根据显示模式调整文字大小
                if (isMedium) {
                    tvTranslatorName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    tvTime?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                } else if (isSmall) {
                    tvTranslatorName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    tvTime?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                } else {
                    tvTranslatorName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    tvTime?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
            }

            // 徽章：左上🔄重新翻译标记（仅管理视图）、右上尺寸数量
            badgeContainer.visibility = View.GONE
            tvSizeBadge.visibility = View.GONE
            tvRetranslateBadge.visibility = View.GONE
            if (grouped.groupSize > 1) {
                tvSizeBadge.text = "×${grouped.groupSize}"
                tvSizeBadge.visibility = View.VISIBLE
                badgeContainer.visibility = View.VISIBLE
            }
            if (isManageView && entry.isRetranslated) {
                tvRetranslateBadge.visibility = View.VISIBLE
            }

            // 缩略图点击回调
            ivThumbnail.setOnClickListener {
                onThumbnailClick?.invoke(entry)
            }

            // 分组颜色
            val colorIdx = colorMap[entry.id] ?: 0
            val card = itemView as? MaterialCardView
            if (card != null) {
                // 默认组（colorIdx==0）用主题表面色（深色模式不泛白），彩色组保留半透明淡彩
                card.setCardBackgroundColor(
                    if (colorIdx == 0) androidx.core.content.ContextCompat.getColor(itemView.context, R.color.surface)
                    else GROUP_COLORS.getOrElse(colorIdx) { GROUP_COLORS[1] }
                )
                val strokeWidthPx = (1 * itemView.resources.displayMetrics.density).toInt()
                card.strokeWidth = strokeWidthPx
                card.strokeColor = androidx.core.content.ContextCompat.getColor(itemView.context, R.color.divider)
                card.setBackgroundTintList(null)
            }

            itemView.setOnClickListener { onItemClick(grouped) }
            itemView.setOnLongClickListener {
                onItemLongClick(grouped)
                true
            }
        }

    }

    class DiffCallback : DiffUtil.ItemCallback<GroupedHistoryEntry>() {
        override fun areItemsTheSame(oldItem: GroupedHistoryEntry, newItem: GroupedHistoryEntry): Boolean {
            return oldItem.representative.id == newItem.representative.id
        }

        override fun areContentsTheSame(oldItem: GroupedHistoryEntry, newItem: GroupedHistoryEntry): Boolean {
            return oldItem == newItem
        }
    }
}
