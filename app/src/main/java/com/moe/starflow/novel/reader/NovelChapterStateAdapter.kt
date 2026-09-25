package com.moe.starflow.novel.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.novel.model.NovelChapterMeta

/**
 * 翻译面板「每章一行」。
 *
 * ⚠️ **复用漫画面板的行布局** `item_translate_page_state.xml`（同一个文件、同一套徽章
 * drawable），只是把「P{n}」换成「第 n 章」、「页状态」换成「章状态」。两个阅读器的面板
 * 必须长得一样 —— 各画一套行样式是最容易悄悄跑偏的地方。
 */
class NovelChapterStateAdapter(
    private val onJump: (Int) -> Unit,
) : RecyclerView.Adapter<NovelChapterStateAdapter.VH>() {

    /** 章节目录（标题来源）。 */
    var chapters: List<NovelChapterMeta> = emptyList()
        set(value) { field = value; rebuild() }

    /** 章状态（翻译进度）。 */
    var stats: Map<Int, NovelChapterStat> = emptyMap()
        set(value) { field = value; rebuild() }

    var currentChapter: Int = -1
        set(value) { field = value; notifyItemRangeChanged(0, itemCount) }

    /** 过滤：false = 全部；true = 只看「没翻完」的章。 */
    var failuresOnly: Boolean = false
        set(value) { field = value; rebuild() }

    /** 面板深浅（随阅读背景切换），行内文字配色跟随。 */
    var dark = false
        set(value) { field = value; notifyItemRangeChanged(0, itemCount) }

    class VH(item: View) : RecyclerView.ViewHolder(item)

    /**
     * 过滤后的章号列表（缓存）。
     *
     * ⚠️ 不能每次 `getItemCount` / `onBindViewHolder` 现算：那是 O(章数) 一次遍历，
     * 绑 2000 章就是 O(n²)。书本上千章很常见。
     */
    private var visible: List<Int> = emptyList()

    private fun rebuild() {
        val all = chapters.indices.toList()
        visible = if (!failuresOnly) all else all.filter { !isDone(it) }
        notifyDataSetChanged()
    }

    /** 当前过滤后的章号列表（只读）。 */
    fun visibleIndexes(): List<Int> = visible

    private fun isDone(index: Int): Boolean {
        val st = stats[index] ?: return false
        return st.total > 0 && st.success >= st.total
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_translate_page_state, parent, false))

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = holder.itemView
        val index = visible.getOrNull(position) ?: return
        val st = stats[index]

        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val accent = 0xFF55AEEA.toInt()

        item.findViewById<TextView>(R.id.tv_page_label).apply {
            text = item.context.getString(R.string.novel_chapter_label, index + 1) +
                chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() }?.let { "　$it" }.orEmpty()
            setTextColor(if (index == currentChapter) accent else labelColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val badge = item.findViewById<TextView>(R.id.tv_state_badge)
        val done = st != null && st.total > 0 && st.success >= st.total
        val started = st != null && st.success > 0
        badge.setText(
            when {
                done -> item.context.getString(R.string.novel_chapter_done)
                started -> item.context.getString(R.string.novel_chapter_partial, st!!.success, st.total)
                else -> item.context.getString(R.string.novel_chapter_unread)
            }
        )
        badge.setBackgroundResource(
            when {
                done -> R.drawable.bg_state_success
                started -> R.drawable.bg_state_translating
                else -> R.drawable.bg_state_idle
            }
        )

        // 这一行借的是「失败原因」那一格来显示段落进度 —— 章没有失败原因可言，
        // 空着反而让行高和漫画面板对不齐
        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            text = if (st != null) {
                item.context.getString(R.string.novel_chapter_progress, st.success, st.total)
            } else ""
            setTextColor(subColor)
            visibility = if (!text.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        // 章级别的详情展开没有意义（一章几百段），把「详情」按钮收起
        item.findViewById<View>(R.id.btn_row_detail).visibility = View.GONE
        item.findViewById<View>(R.id.detail_panel).visibility = View.GONE

        item.setOnClickListener { onJump(index) }
    }
}
