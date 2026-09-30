package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 超分记录列表的一行：**章节卡片** 或 章内某一页的超分记录。 */
sealed interface SrRow {
    data class Header(
        val chapterIndex: Int,
        val label: String,
        /** 本章**超分成功**的页数。 */
        val success: Int,
        val pageCount: Int,
        val expanded: Boolean,
        val selected: Boolean,
        /** 本章正在跑的超分任务（null = 没在跑）。 */
        val job: ReaderTranslationController.SrChapterJob?,
    ) : SrRow

    data class Page(val row: ImportedPageSr, val chapterIndex: Int) : SrRow
}

/**
 * **超分记录列表** —— 与翻译面板的记录列表**同一套形态**（用户口径 2026-10：
 * 「给超分面板也设计一个类似翻译面板那样的记录系统（直接复用相关 UI 和代码逻辑）」）。
 *
 * 复用的具体项：
 * - 布局：`item_translate_chapter_row.xml`（章卡片）+ `item_translate_page_state.xml`（页行 + 详情）
 *   —— 两个面板**共用同一批 XML**，改一处两边一起变
 * - 底色：`CardBackdrop.apply`（卡片）/ `applyNested`（嵌在卡片里的页行：方角 + 左竖线）
 * - 状态徽章：`bg_state_{idle,translating,success,failed}`
 * - 交互：点卡片 = 跳章并展开 / 点箭头 = 展开收起 / 点「详情」= 行内展开（**不弹窗**）/ 点行 = 跳页
 *
 * 与翻译侧**唯一不同**的是内容：
 * | | 翻译 | 超分 |
 * |---|---|---|
 * | 状态 | 未译 / 识别中 / 翻译中 / 完成 / 失败 | 未超 / **超分中** / 完成 / 失败 |
 * | 主按钮 | 翻译本章 ⇄ 暂停 ⇄ 继续 | **超分本章 ⇄ 取消** |
 * | 次按钮 | 清除本章译文 | **清除本章超分** |
 * | 详情 | 逐条原文/译文 | **模型 / 原始与超分后的尺寸与大小 / 时间** |
 */
class ReaderSrStateAdapter(
    /** 章标题的本地化（需要 Context，由面板注入 `mangaChapterLabel`）。 */
    private val chapterLabelOf: (MangaChapter) -> String,
    private val onJump: (Int) -> Unit,
    private val onSelectChapter: (Int) -> Unit,
    /** 章卡片主按钮：没在跑 = 超分本章；跑着 = 取消。 */
    private val onChapterPrimary: (Int) -> Unit,
    /** 章卡片次按钮：清除本章超分（带确认）。 */
    private val onChapterSecondary: (Int) -> Unit,
    /** 页行右侧的「删除」：删这一页的超分结果。 */
    private val onDeletePage: (Int) -> Unit,
) : RecyclerView.Adapter<ReaderSrStateAdapter.VH>() {

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_DONE = 1
        const val FILTER_ONGOING = 2
        const val FILTER_FAILED = 3

        /** 展开/收起详情的过渡时长（与翻译面板同一个值）。 */
        private const val EXPAND_ANIM_MS = 160L
    }

    class VH(item: View) : RecyclerView.ViewHolder(item)

    private var chapters: List<MangaChapter> = emptyList()
    private var records: List<ImportedPageSr> = emptyList()
    private var job: ReaderTranslationController.SrChapterJob? = null
    private var selectedChapter = 0
    private var filter = FILTER_ALL
    private val expanded = mutableSetOf<Int>()
    private var expandedPage: Int? = null
    private var rows: List<SrRow.Header> = emptyList()
    private var itemDensity: Float = 1f

    /** 面板深浅（随阅读背景切换）。 */
    var dark = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * 面板是打开那一刻的快照，宿主每次推送都整表重建（与翻译面板同一条约定）。
     *
     * ⚠️ 默认展开**当前章**（用户一开面板就看到自己刚超的那章）；
     * 判定用「**章表变了**」而不是"第一次提交" —— 宿主会先往面板字段里存一份章表再调这里，
     * 用 `chapters.isEmpty()` 判首次会被那一步提前破坏（翻译面板踩过同一个坑）。
     */
    fun submit(
        chapters: List<MangaChapter>,
        records: List<ImportedPageSr>,
        selectedChapter: Int,
        job: ReaderTranslationController.SrChapterJob? = null,
    ) {
        val chaptersChanged = this.chapters != chapters
        this.chapters = chapters
        this.records = records
        this.selectedChapter = selectedChapter
        this.job = job
        if (chaptersChanged) expanded += selectedChapter
        rebuild()
    }

    fun setFilter(key: Int) {
        if (filter == key) return
        filter = key
        rebuild()
    }

    fun toggleChapter(index: Int) {
        if (!expanded.remove(index)) expanded += index
        rebuild()
    }

    fun expandChapter(index: Int) {
        if (expanded.add(index)) rebuild()
    }

    private fun rebuild() {
        rows = buildRows()
        notifyDataSetChanged()
    }

    /** 页号 → 章下标（二分；章区间连续升序，别每帧建全量映射）。 */
    private fun chapterIndexFor(page: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val c = chapters[mid]
            when {
                page < c.startPage -> hi = mid - 1
                page > c.endPage -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun buildRows(): List<SrRow.Header> {
        if (chapters.isEmpty()) return emptyList()
        val successByChapter = HashMap<Int, Int>()
        for (r in records) {
            if (r.state != ImportedPageSr.STATE_SUCCESS) continue
            val idx = chapterIndexFor(r.pageIndex)
            if (idx >= 0) successByChapter[idx] = (successByChapter[idx] ?: 0) + 1
        }
        return chapters.mapIndexed { index, chapter ->
            SrRow.Header(
                chapterIndex = index,
                label = chapterLabelOf(chapter),
                success = successByChapter[index] ?: 0,
                pageCount = chapter.pageCount,
                expanded = index in expanded,
                selected = index == selectedChapter,
                job = job?.takeIf { it.chapterIndex == index },
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_translate_chapter_row, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) = bindHeader(holder.itemView, rows[position])

    private fun pill(argb: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f * itemDensity
        setColor(argb)
    }

    private fun bindHeader(item: View, row: SrRow.Header) {
        if (itemDensity == 1f) itemDensity = item.resources.displayMetrics.density
        val ctx = item.context
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val accent = 0xFF55AEEA.toInt()
        val amber = 0xFFFF9F0A.toInt()
        CardBackdrop.apply(item, CardBackdrop.Tone.CARD, dark)

        item.findViewById<TextView>(R.id.tv_chapter_label).apply {
            text = row.label
            setTextColor(if (row.selected) accent else labelColor)
        }

        val running = row.job != null
        val allDone = row.pageCount > 0 && row.success >= row.pageCount
        item.findViewById<TextView>(R.id.tv_chapter_badge).apply {
            text = when {
                running -> ctx.getString(R.string.reader_sr_chapter_running, row.job!!.done, row.job.total)
                allDone -> ctx.getString(R.string.reader_sr_chapter_done)
                else -> ctx.getString(R.string.reader_sr_badge, row.success, row.pageCount)
            }
            setTextColor(
                when {
                    running -> accent
                    allDone -> 0xFF34C759.toInt()
                    row.success > 0 -> amber
                    else -> if (dark) 0xFF6E6E73.toInt() else 0xFF888888.toInt()
                }
            )
            background = pill(
                when {
                    running -> if (dark) 0x332E86C9 else 0x1A2E86C9
                    allDone -> if (dark) 0x3334C759 else 0x1A34C759
                    else -> if (dark) 0x1AFFFFFF else 0x0F000000
                }
            )
        }
        item.findViewById<TextView>(R.id.tv_chapter_expand).apply {
            text = if (row.expanded) "▾" else "▸"
            setTextColor(if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt())
        }

        item.findViewById<TextView>(R.id.btn_chapter_primary).apply {
            setTextColor(if (running) amber else accent)
            background = pill(if (running) (if (dark) 0x33FF9F0A else 0x1AFF9F0A) else (if (dark) 0x332E86C9 else 0x1A2E86C9))
            text = ctx.getString(
                if (running) R.string.cancel else R.string.reader_sr_chapter_translate
            )
            setOnClickListener { onChapterPrimary(row.chapterIndex) }
        }
        item.findViewById<TextView>(R.id.btn_chapter_secondary).apply {
            // 跑着的时候不给「清除」：正在写的文件被删掉，下一次超分又会把它写回来，
            // 用户会以为"点了没用"。取消之后再清。
            visibility = if (running) View.GONE else View.VISIBLE
            text = ctx.getString(R.string.reader_sr_chapter_clear)
            setTextColor(0xFFCC5555.toInt())
            background = pill(if (dark) 0x33CC5555 else 0x14CC5555)
            setOnClickListener { onChapterSecondary(row.chapterIndex) }
        }

        item.setOnClickListener {
            onSelectChapter(row.chapterIndex)
            expandChapter(row.chapterIndex)
        }
        item.findViewById<TextView>(R.id.tv_chapter_expand).setOnClickListener {
            toggleChapter(row.chapterIndex)
        }
        bindChildren(item, row)
    }

    private fun bindChildren(card: View, header: SrRow.Header) {
        val box = card.findViewById<LinearLayout>(R.id.chapter_children) ?: return
        val children = if (header.expanded) childRowsOf(header.chapterIndex) else emptyList()
        if (box.childCount != children.size) {
            box.removeAllViews()
            val inflater = LayoutInflater.from(box.context)
            repeat(children.size) {
                box.addView(inflater.inflate(R.layout.item_translate_page_state, box, false))
            }
        }
        children.forEachIndexed { i, row -> bindChild(box.getChildAt(i), header.chapterIndex, row) }
    }

    /** 某章展开后要显示的页行：**只列有记录的页**（未超分的默认不进列表，与翻译面板同口径）。 */
    private fun childRowsOf(chapterIndex: Int): List<SrRow.Page> =
        records
            .filter { chapterIndexFor(it.pageIndex) == chapterIndex }
            .filter { it.state != ImportedPageSr.STATE_IDLE }
            .filter { matchesFilter(it) }
            .sortedBy { it.pageIndex }
            .map { SrRow.Page(it, chapterIndex) }

    private fun matchesFilter(r: ImportedPageSr): Boolean = when (filter) {
        FILTER_DONE -> r.state == ImportedPageSr.STATE_SUCCESS
        FILTER_ONGOING -> r.state == ImportedPageSr.STATE_RUNNING
        FILTER_FAILED -> r.state == ImportedPageSr.STATE_FAILED
        else -> true
    }

    private fun bindChild(item: View, chapterIndex: Int, row: SrRow.Page) {
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val accent = 0xFF55AEEA.toInt()
        val page = row.row
        if (itemDensity == 1f) itemDensity = item.resources.displayMetrics.density

        val tone = when (page.state) {
            ImportedPageSr.STATE_RUNNING -> CardBackdrop.Tone.ACTIVE
            ImportedPageSr.STATE_FAILED -> CardBackdrop.Tone.FAILED
            else -> CardBackdrop.Tone.PLAIN
        }
        CardBackdrop.applyNested(item, tone, dark)

        item.findViewById<TextView>(R.id.tv_page_label).apply {
            text = "P${page.pageIndex + 1}"
            setTextColor(labelColor)
        }
        item.findViewById<TextView>(R.id.tv_state_badge).apply {
            text = item.context.getString(
                when (page.state) {
                    ImportedPageSr.STATE_RUNNING -> R.string.reader_sr_state_running
                    ImportedPageSr.STATE_SUCCESS -> R.string.reader_translate_state_success
                    ImportedPageSr.STATE_FAILED -> R.string.reader_translate_state_failed
                    else -> R.string.reader_translate_state_idle
                }
            )
            setBackgroundResource(
                when (page.state) {
                    ImportedPageSr.STATE_RUNNING -> R.drawable.bg_state_translating
                    ImportedPageSr.STATE_SUCCESS -> R.drawable.bg_state_success
                    ImportedPageSr.STATE_FAILED -> R.drawable.bg_state_failed
                    else -> R.drawable.bg_state_idle
                }
            )
        }

        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            text = page.failMessage
            visibility = if (!page.failMessage.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        val expandedNow = expandedPage == page.pageIndex
        val btnDetail = item.findViewById<TextView>(R.id.btn_row_detail)
        btnDetail.setTextColor(accent)
        btnDetail.visibility = View.VISIBLE
        btnDetail.text = item.context.getString(
            if (expandedNow) R.string.reader_translate_collapse else R.string.reader_translate_row_detail
        )

        item.findViewById<TextView>(R.id.btn_row_delete).apply {
            visibility = View.VISIBLE
            setOnClickListener { onDeletePage(page.pageIndex) }
        }

        val detail = item.findViewById<View>(R.id.detail_panel)
        val holder = (item.getTag(R.id.detail_panel) as? Boolean)
        if (holder != null && holder != expandedNow) {
            androidx.transition.TransitionManager.beginDelayedTransition(
                item as android.view.ViewGroup,
                androidx.transition.AutoTransition().setDuration(EXPAND_ANIM_MS)
            )
        }
        item.setTag(R.id.detail_panel, expandedNow)
        if (expandedNow) {
            fillDetail(item, page, labelColor, subColor)
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }
        CardBackdrop.apply(detail, CardBackdrop.Tone.DETAIL, dark)

        item.setOnClickListener { if (!expandedNow) onJump(page.pageIndex) }
        btnDetail.setOnClickListener {
            expandedPage = if (expandedNow) null else page.pageIndex
            notifyItemChanged(chapterIndex)
        }
    }

    /**
     * 详情内容 = **基础信息**（用户口径 2026-10）：
     * 超分模型、原始与超分后的尺寸与大小、时间。
     *
     * 与翻译侧的详情复用同一对控件（`tv_detail_meta` 元信息行 / `tv_detail_list` 正文块）——
     * 所以配色、字号、展开动画全部自动一致。
     */
    private fun fillDetail(item: View, row: ImportedPageSr, labelColor: Int, subColor: Int) {
        val ctx = item.context
        val time = when {
            row.finishedAtMs > 0L -> row.finishedAtMs
            row.startedAtMs > 0L -> row.startedAtMs
            else -> row.updatedAtMs
        }
        val meta = buildString {
            row.modelName?.takeIf { it.isNotBlank() }?.let { append(ctx.getString(R.string.reader_sr_detail_model, it)) }
            if (time > 0L) {
                if (isNotEmpty()) append(" · ")
                append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time)))
            }
        }
        item.findViewById<TextView>(R.id.tv_detail_meta).apply {
            text = meta.ifBlank { ctx.getString(R.string.reader_sr_detail_none) }
            setTextColor(subColor)
        }

        val sb = StringBuilder()
        if (row.srcWidth > 0 && row.srcHeight > 0) {
            sb.append(ctx.getString(R.string.reader_sr_detail_src, row.srcWidth, row.srcHeight, fmtBytes(row.srcBytes)))
        }
        if (row.outWidth > 0 && row.outHeight > 0) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(ctx.getString(R.string.reader_sr_detail_out, row.outWidth, row.outHeight, fmtBytes(row.outBytes)))
        }
        item.findViewById<TextView>(R.id.tv_detail_list).apply {
            text = sb.toString().ifBlank { ctx.getString(R.string.reader_sr_detail_none) }
            setTextColor(labelColor)
        }
        item.findViewById<TextView>(R.id.tv_detail_collapse).apply {
            setTextColor(0xFF55AEEA.toInt())
            setOnClickListener {
                expandedPage = null
                notifyItemChanged(chapterIndexFor(row.pageIndex).coerceAtLeast(0))
            }
        }
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
