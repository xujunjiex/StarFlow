package com.moe.starflow.novel.reader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.novel.model.NovelChapterMeta

/**
 * 翻译面板「每章一行」。
 *
 * ⚠️ **复用漫画面板的行布局** `item_translate_page_state.xml`（同一个文件、同一套徽章
 * drawable），只是把「P{n}」换成「第 n 章」、「页状态」换成「章状态」。两个阅读器的面板
 * 必须长得一样 —— 各画一套行样式是最容易悄悄跑偏的地方。
 */
/**
 * 章行 / 目录 / 顶部胶囊显示的章标题。
 *
 * ⚠️ **有标题就只显示标题**：以前一律在前面拼「第N章」，于是没有章节标记的书
 * （兜底按字数切成「第1节」「第2节」…）会显示成「第1章 第1节」这种双编号 ——
 * 用户看到就是"莫名其妙"。只有该章**没有标题**时才用「第N章」兜底。
 */
internal fun chapterDisplayTitle(context: Context, index: Int, title: String?): String =
    title?.trim()?.takeIf { it.isNotEmpty() }
        ?: context.getString(R.string.novel_chapter_label, index + 1)

class NovelChapterStateAdapter(
    private val onJump: (Int) -> Unit,
) : RecyclerView.Adapter<NovelChapterStateAdapter.VH>() {

    /**
     * ⚠️ 三个 setter 都要**先比再刷**：宿主现在会在换章/翻页/译文到达时推全量状态
     * （见 `NovelPanelSheet.notifyHostState`），值没变还照刷的话，一次翻页就要重绑整张章列表 ——
     * 上千章的书上是实打实的卡顿。
     */
    var chapters: List<NovelChapterMeta> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /**
     * 每章的**总段数**（分母）。由宿主推来（懒解析 + 缓存）。
     *
     * ⚠️ 未翻译的章在 `stats` 里根本没有条目，只靠 stats 就会「只有翻过的章显示段数」。
     */
    var totals: Map<Int, Int> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** 某章段数还不知道时问宿主一次（宿主解析完会推回来）。 */
    var onNeedTotal: ((Int) -> Unit)? = null

    /** 章状态（翻译进度）。 */
    var stats: Map<Int, NovelChapterStat> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    var currentChapter: Int = -1
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /**
     * 状态标签过滤：0 全部 / 1 完成 / 2 进行中 / 3 失败（与面板上的 chip 一一对应）。
     *
     * ⚠️ 以前只有「全部 / 没翻完」两格，用户看不到"完成/进行中/失败"的分开视图。
     */
    var filterKey: Int = 0
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** 每章的失败明细（章行展开显示「为什么失败」）。 */
    var failures: Map<Int, List<NovelFailureRow>> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** 当前展开的**行下标**（同漫画：一次只展开一行）。 */
    private var expandedIndex: Int? = null

    /** 面板深浅（随阅读背景切换），行内文字配色跟随。 */
    var dark = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

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
        visible = if (filterKey == 0) all else all.filter { passesFilter(it) }
        expandedIndex = null
        notifyDataSetChanged()
    }

    /** 章行是否命中当前状态标签。 */
    private fun passesFilter(index: Int): Boolean {
        val st = stats[index]
        val done = st != null && st.total > 0 && st.success >= st.total
        val failed = failures[index].orEmpty().isNotEmpty()
        val running = st != null && st.success > 0 && !done
        return when (filterKey) {
            1 -> done
            2 -> running
            3 -> failed
            else -> true
        }
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
            text = chapterDisplayTitle(item.context, index, chapters.getOrNull(index)?.title)
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
        // 这一行借的是「失败原因」那一格来显示段落进度 —— 章没有失败原因可言，
        // 空着反而让行高和漫画面板对不齐
        //
        // ⚠️ 分母优先取宿主解析出来的**真实段数**，其次才是数据库统计（未翻的章没有统计行）
        val total = totals[index] ?: st?.total ?: 0
        if (total == 0) onNeedTotal?.invoke(index)
        val failed = failures[index].orEmpty()
        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            // 有失败时**优先显示失败原因**（用户要的就是"为什么失败"），否则显示段落进度
            text = when {
                failed.isNotEmpty() -> item.context.getString(
                    R.string.novel_chapter_failed_hint,
                    failed.size,
                    NovelFailCode.label(item.context, failed.first().failCode),
                )
                total > 0 -> item.context.getString(R.string.novel_chapter_progress, st?.success ?: 0, total)
                else -> ""
            }
            setTextColor(subColor)
            visibility = if (!text.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        // 有失败才给「详情」按钮；展开后逐条列出 段号 + 原因 + 原文前 20 字（与漫画同一套展开）
        val expanded = expandedIndex == position
        val btnDetail = item.findViewById<TextView>(R.id.btn_row_detail)
        btnDetail.visibility = if (failed.isEmpty()) View.GONE else View.VISIBLE
        btnDetail.setTextColor(accent)
        btnDetail.text = item.context.getString(
            if (expanded) R.string.reader_translate_collapse else R.string.reader_translate_row_detail,
        )
        val detail = item.findViewById<View>(R.id.detail_panel)
        if (expanded && failed.isNotEmpty()) {
            item.findViewById<TextView>(R.id.tv_detail_meta).apply {
                text = item.context.getString(R.string.novel_chapter_failed_meta, index + 1, failed.size)
                setTextColor(labelColor)
            }
            item.findViewById<TextView>(R.id.tv_detail_list).apply {
                text = failed.joinToString("\n") {
                    "#${it.paraIndex}  ${NovelFailCode.label(item.context, it.failCode)}  ${it.sourceText.take(20)}"
                }
                setTextColor(subColor)
            }
            item.findViewById<TextView>(R.id.tv_detail_collapse).setTextColor(accent)
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }
        btnDetail.setOnClickListener {
            val prev = expandedIndex
            expandedIndex = if (expanded) null else position
            if (prev != null) notifyItemChanged(prev)
            notifyItemChanged(position)
        }

        // 点整行 = 跳章；展开态点行不跳（避免误触）
        item.setOnClickListener { if (!expanded) onJump(index) }
    }
}
