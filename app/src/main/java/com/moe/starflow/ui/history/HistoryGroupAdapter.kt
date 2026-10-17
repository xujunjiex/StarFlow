package com.moe.starflow.ui.history
import com.moe.starflow.ui.viewer.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.HistoryGroup
import com.moe.starflow.data.HistorySession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游戏历史分组适配器
 * 支持日期标题 + 会话子标题 + 历史记录项
 */
class HistoryGroupAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val onDownloadSessionClick: ((HistorySession) -> Unit)? = null
) : ListAdapter<HistoryGroup, HistoryGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 紧凑模式：行内只画译文（[HistoryGameAdapter]），日期/会话标题的留白一起收。
     * 分组标题本身保留 —— 会话标题上的下载按钮和「哪天/哪次会话」的定位靠它。
     */
    var compactMode: Boolean = false
        private set

    /**
     * 切换紧凑模式。行的布局是 bind 时按模式选的，所以必须重绑已显示的行，
     * 否则切换后列表要等滚动才会变样。
     */
    fun setCompactMode(compact: Boolean) {
        if (compactMode == compact) return
        compactMode = compact
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_date_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDateHeader: TextView = view.findViewById(R.id.tvDateHeader)
        private val rvSessions: RecyclerView = view.findViewById(R.id.rvSessions)

        fun bind(group: HistoryGroup) {
            tvDateHeader.text = group.dateLabel
            // 紧凑模式下连分组标题的留白一起收，否则标题本身仍会吃掉不少高度
            val headerPadH = if (compactMode) 8 else 16
            val headerPadV = if (compactMode) 4 else 10
            tvDateHeader.setPadding(
                dp(headerPadH), dp(headerPadV), dp(headerPadH), dp(headerPadV)
            )

            val sessionAdapter = SessionAdapter(onItemClick, onItemLongClick, onDownloadSessionClick)
            rvSessions.layoutManager = LinearLayoutManager(itemView.context)
            rvSessions.adapter = sessionAdapter
            sessionAdapter.submitList(group.sessions)
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()
    }

    private inner class SessionAdapter(
        private val onItemClick: (HistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit,
        private val onDownloadSessionClick: ((HistorySession) -> Unit)?
    ) : ListAdapter<HistorySession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_session, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvSessionHeader: TextView = view.findViewById(R.id.tvSessionHeader)
            private val btnDownload: TextView = view.findViewById(R.id.btnDownloadSession)
            private val rvEntries: RecyclerView = view.findViewById(R.id.rvSessionEntries)

            fun bind(session: HistorySession) {
                val startTime = timeFormat.format(Date(session.startTime))
                val endTime = timeFormat.format(Date(session.endTime))
                tvSessionHeader.text = "$startTime - $endTime (${session.entries.size})"

                // 紧凑模式：会话标题只留最小留白（下载按钮的位置与语义不变）
                val padH = if (compactMode) 8 else 24
                val padV = if (compactMode) 2 else 6
                (tvSessionHeader.parent as? View)?.setPadding(
                    dp(padH), dp(padV), dp(padH), dp(padV)
                )

                btnDownload.visibility = if (onDownloadSessionClick != null) View.VISIBLE else View.GONE
                btnDownload.setOnClickListener { onDownloadSessionClick?.invoke(session) }

                val entryAdapter = HistoryGameAdapter(onItemClick, onItemLongClick, compact = compactMode)
                rvEntries.layoutManager = LinearLayoutManager(itemView.context)
                rvEntries.adapter = entryAdapter
                entryAdapter.submitList(session.entries)
            }

            private fun dp(value: Int): Int =
                (value * itemView.resources.displayMetrics.density).toInt()
        }
    }

    // ========== DiffCallbacks ==========

    class GroupDiffCallback : DiffUtil.ItemCallback<HistoryGroup>() {
        override fun areItemsTheSame(oldItem: HistoryGroup, newItem: HistoryGroup): Boolean {
            return oldItem.dateLabel == newItem.dateLabel
        }

        override fun areContentsTheSame(oldItem: HistoryGroup, newItem: HistoryGroup): Boolean {
            return oldItem == newItem
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<HistorySession>() {
        override fun areItemsTheSame(oldItem: HistorySession, newItem: HistorySession): Boolean {
            return oldItem.sessionId == newItem.sessionId
        }

        override fun areContentsTheSame(oldItem: HistorySession, newItem: HistorySession): Boolean {
            return oldItem == newItem
        }
    }
}
