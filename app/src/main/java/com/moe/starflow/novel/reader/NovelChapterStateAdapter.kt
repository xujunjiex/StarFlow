package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.mangaimport.reader.CardBackdrop
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.translate.NovelWaitingBatch
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.translate.batch.ChapterJobState
import java.util.Locale

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

/**
 * 这一章是否**真的翻完**了 —— 面板章行与目录徽章的**唯一判据**。
 *
 * ⚠️ 必须两处共用一份：各写一份的结果是目录写「已完成」、面板写「进行中」，同一个事实
 * 两个结论（用户报的「某一章没翻完却显示已经全部翻译完成」）。
 *
 * ⚠️ 分母只认宿主解析出来的**真实可翻译段数**（[totals]），别退回 `st.total`：那是数据库里
 * 这一章的行数，而**行是按批惰性写的** —— 只翻了一部分时 `success >= total` 也成立。
 * 拿不到分母时按"未完成"显示，等宿主推回来再重算（宁可暂时不显示完成，也不能误报完成）。
 */
internal fun isChapterDone(
    stats: Map<Int, NovelChapterStat>,
    totals: Map<Int, Int>,
    index: Int,
): Boolean {
    val st = stats[index] ?: return false
    val total = totals[index] ?: 0
    return total > 0 && st.success >= total
}

/**
 * 章行的「本章多少字」标签。
 *
 * ⚠️ 给**精确值**（千位分隔）而不是「1.2 万字」：这一格是给用户**估翻译费用**用的，
 * 费用按字符数算，四舍五入到"万"就估不准了。
 * ⚠️ 数量只是**可翻译段**的字数（与分母同口径），不含图片占位与不足 4 字的短行。
 */
internal fun chapterCharLabel(context: Context, count: Int): String =
    context.getString(R.string.novel_chapter_chars, String.format(Locale.US, "%,d", count))

/** 翻译面板里的一行：**章节卡片** 或 该章排队中的批（「等待」）。 */
sealed interface NovelTranslateRow {
    /**
     * 章卡片。
     *
     * ⚠️ 只带**结构**信息（章号 + 是否展开）：段数/字数/失败/任务状态一律在绑定那一刻
     * **从适配器的 map 里现读**。曾经把它们快照进行对象，而 `chars`/`currentChapter` 这几个
     * setter 只 `notifyItemRangeChanged`（不重建行）→ 行里存的是**旧值**，字数永远显示不出来
     * （`NovelPanelSyncTest` 抓到的：`3 of 40 paragraphs` 后面少一截）。
     */
    data class Chapter(val chapterIndex: Int, val expanded: Boolean) : NovelTranslateRow

    /** 章内**排队中**的一批（纯内存态，任务没了就消失）。 */
    data class Batch(
        val chapterIndex: Int,
        val batch: NovelWaitingBatch,
        /** true = **正在提交/等待返回**（琥珀高亮）；false = 还排在队列里（「等待」）。 */
        val active: Boolean = false,
    ) : NovelTranslateRow
}

/**
 * 翻译面板「记录列表」的**两段式**列表（与漫画 `ReaderPageStateAdapter` 同一形态）：
 * 每章一张**卡片**（16sp 加粗标题 + 两个按钮），展开后列出**该章排队中的批**（标「等待」）
 * 与失败明细。
 *
 * 用户口径（2026-10）：
 * - 章标题比批/页行大（16sp 加粗 vs 13sp），卡片上直接放两个按钮：
 *   主按钮「翻译本章 ⇄ 暂停 ⇄ 继续」、次按钮「清除本章译文 ⇄ 取消」
 * - **点卡片空白处 = 跳到该章**（切章只从记录里点；面板里不再有切章组件）
 * - 排队中的批标「等待」（**纯内存态，不写库**）
 */
class NovelChapterStateAdapter(
    private val onJump: (Int) -> Unit,
    /** 章卡片主按钮：没任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续。 */
    private val onChapterPrimary: (Int) -> Unit = {},
    /** 章卡片次按钮：有任务 = 取消；没任务 = 清除本章译文（确认弹窗在面板里）。 */
    private val onChapterSecondary: (Int) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** 展开/收起明细的过渡时长（与漫画同一套观感）。 */
        private const val EXPAND_ANIM_MS = 160L

        /** ⚠️ 章节卡片必须是 0：测试与调用方按 `viewType = 0` 取卡片视图。 */
        private const val TYPE_CHAPTER = 0
    }

    /**
     * ⚠️ 四个 setter 都要**先比再刷**：宿主现在会在换章/翻页/译文到达时推全量状态
     * （见 `NovelPanelSheet.renderHostState`），值没变还照刷的话，一次翻页就要重绑整张章列表 ——
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
            // ⚠️ 必须 rebuild 而不是只 notifyItemRangeChanged：`done` 与「已完成」筛选都
            // 拿 totals 当分母，分母到齐后要重算可见列表
            rebuild()
        }

    /**
     * 每章**可翻译正文字数**（章行显示，用户拿它估翻译费用）。与 [totals] 同一次懒解析。
     *
     * ⚠️ 只 `notifyItemRangeChanged` 不 `rebuild`：字数不参与 [passesFilter]，
     * 值到齐时重建整表会把用户展开的失败详情收起来。
     */
    var chars: Map<Int, Int> = emptyMap()
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

    /** 每章的后台任务（null/缺省 = 没任务）：按钮文案、徽章、筛选都读它。 */
    var jobs: Map<Int, ChapterJob> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /**
     * 每章排队中（还没开始翻）的批 —— **纯内存态**，来自宿主的 `waitingPages`。
     *
     * ⚠️ **必须 rebuild**：面板的推送顺序是「先 waiting、后 active」，而 `active` 为空时会提前 return；
     * 少了这里的 rebuild，"刚排上队的批"要等下一次别的字段变化才出现（`NovelPanelSyncTest`
     * 的「排队中的批显示等待行」正是这么红的）。
     */
    var waiting: Map<Int, List<NovelWaitingBatch>> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /**
     * **正在提交/等待返回**的批（用户口径：这些片段的背景要高亮）。
     * 与 [waiting] 一样是纯内存态；先渲染在飞批（它们更靠前），再渲染等待批。
     */
    var active: Map<Int, List<NovelWaitingBatch>> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
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

    /**
     * 每章的失败明细（章行展开显示「为什么失败」）。
     *
     * ⚠️ **必须 rebuild 而不是只 notifyItemRangeChanged**：`passesFilter` 在
     * 「失败」筛选（filterKey == 3）下读的正是 `failures`，只刷可见项的话**缓存过滤表
     * `visible` 不变** —— 面板开着「失败」筛选时，刚失败的章不会出现在列表里
     * （其余三个 setter 都调 rebuild，这条是漏的）。
     */
    var failures: Map<Int, List<NovelFailureRow>> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** 面板深浅（随阅读背景切换），行内文字配色跟随。 */
    /** 药丸底：徽章与按钮统一用它（圆角 + 半透明填充，随面板深浅）。 */
    private fun pill(argb: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f * density
        setColor(argb)
    }

    private var density: Float = 1f

    var dark = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    class ChapterVH(item: View) : RecyclerView.ViewHolder(item)

    /**
     * 过滤后的章号列表（缓存）。
     *
     * ⚠️ 不能每次 `getItemCount` / `onBindViewHolder` 现算：那是 O(章数) 一次遍历，
     * 绑 2000 章就是 O(n²)。书本上千章很常见。
     */
    private var visible: List<Int> = emptyList()

    /** 展开展开的章（下标集合）：展开后才列「等待」的批与失败明细。 */
    private val expanded = mutableSetOf<Int>()

    /** 整表行（卡片 + 展开章里的批行）。 */
    /** 列表项 = **每章一个**（展开的批行挂在卡片内部）。 */
    private var rows: List<NovelTranslateRow.Chapter> = emptyList()

    private fun rebuild() {
        if (chapters.isNotEmpty() && expanded.isEmpty()) expanded += currentChapter
        expanded.retainAll(chapters.indices.toSet())
        val all = chapters.indices.toList()
        visible = if (filterKey == 0) all else all.filter { passesFilter(it) }
        rows = buildRows()
        notifyDataSetChanged()
    }

    /**
     * **每章一行**（一个章 = 一个列表项）。
     *
     * ⚠️ 展开的批行**不再是独立列表项**：它们由 [bindChildren] inflate 进卡片自己的
     * `chapter_children` 容器 → 视觉上落在卡片的框**之内**（用户口径：
     * 「每个章节卡片外面有一个框，pxx 行要在框里面」）。
     */
    private fun buildRows(): List<NovelTranslateRow.Chapter> {
        if (visible.isEmpty()) return emptyList()
        return visible.map { NovelTranslateRow.Chapter(it, it in expanded) }
    }

    /** 某章展开后要显示的批行（在飞批在前、等待批在后）。 */
    private fun childRowsOf(index: Int): List<NovelTranslateRow.Batch> {
        if (index !in expanded) return emptyList()
        val out = ArrayList<NovelTranslateRow.Batch>()
        active[index].orEmpty().forEach { out += NovelTranslateRow.Batch(index, it, active = true) }
        waiting[index].orEmpty().forEach { out += NovelTranslateRow.Batch(index, it, active = false) }
        return out
    }

    /**
     * 把该章展开后的批行填进卡片的 `chapter_children`。
     * ⚠️ 数量一致时只重绑、不重建（面板每次推送都会整表重建，重复 inflate 会卡）。
     */
    private fun bindChildren(card: View, index: Int) {
        val box = card.findViewById<LinearLayout>(R.id.chapter_children) ?: return
        val children = childRowsOf(index)
        if (box.childCount != children.size) {
            box.removeAllViews()
            val inflater = LayoutInflater.from(box.context)
            repeat(children.size) {
                box.addView(inflater.inflate(R.layout.item_translate_page_state, box, false))
            }
        }
        children.forEachIndexed { i, row -> bindBatch(box.getChildAt(i), row) }
    }

    /** 章行是否命中当前状态标签。 */
    private fun passesFilter(index: Int): Boolean {
        val st = stats[index]
        val done = isDone(index)
        val failed = failures[index].orEmpty().isNotEmpty()
        val jobActive = jobs[index]?.isActive == true
        val running = jobActive || (st != null && st.success > 0 && !done)
        return when (filterKey) {
            1 -> done
            2 -> running
            3 -> failed
            else -> true
        }
    }

    /** 当前过滤后的章号列表（只读）。 */
    fun visibleIndexes(): List<Int> = visible

    /** 这一章的分母（见 [isChapterDone]）。 */
    private fun totalOf(index: Int): Int = totals[index] ?: 0

    private fun isDone(index: Int): Boolean = isChapterDone(stats, totals, index)

    override fun getItemViewType(position: Int): Int = TYPE_CHAPTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        ChapterVH(LayoutInflater.from(parent.context).inflate(R.layout.item_novel_chapter_card, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // 列表项只有章卡片；批行由 bindChapter → bindChildren 填进卡片内部
        rows.getOrNull(position)?.let { bindChapter(holder.itemView, it) }
    }

    /**
     * 章卡片：大字号标题 + 徽章 + 展开箭头 + 两个按钮。
     *
     * 按钮文案随任务状态切换（用户口径，与漫画逐条对齐）：
     * - 无任务：`翻译本章` / `清除本章译文`
     * - 跑着：`暂停` / `取消`
     * - 暂停：`继续` / `取消`
     */
    private fun bindChapter(item: View, row: NovelTranslateRow.Chapter) {
        if (density == 1f) density = item.resources.displayMetrics.density
        val ctx = item.context
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val accent = 0xFF55AEEA.toInt()

        // ⚠️ 一律**现读**（见 [NovelTranslateRow.Chapter] 的说明：行对象只带结构信息）
        val index = row.chapterIndex
        val success = stats[index]?.success ?: 0
        val total = totalOf(index)
        val charCount = chars[index] ?: 0
        val failed = failures[index].orEmpty()
        val job = jobs[index]
        val waitingCount = waiting[index].orEmpty().size
        val jobActive = job?.isActive == true

        item.findViewById<TextView>(R.id.tv_chapter_label).apply {
            text = chapterDisplayTitle(ctx, index, chapters.getOrNull(index)?.title)
            setTextColor(if (index == currentChapter) accent else labelColor)
        }

        val running = job?.state == ChapterJobState.RUNNING || job?.state == ChapterJobState.QUEUED
        val paused = job?.state == ChapterJobState.PAUSED
        item.findViewById<TextView>(R.id.tv_chapter_badge).apply {
            text = when {
                running -> ctx.getString(R.string.novel_chapter_running_batches, job?.done ?: 0, job?.total ?: 0)
                paused -> ctx.getString(R.string.novel_chapter_paused_batches, job?.done ?: 0, job?.total ?: 0)
                waitingCount > 0 -> ctx.getString(R.string.reader_translate_state_waiting)
                isDone(index) -> ctx.getString(R.string.novel_chapter_done)
                success > 0 -> ctx.getString(R.string.novel_chapter_partial, success, total)
                else -> ctx.getString(R.string.novel_chapter_unread)
            }
            setTextColor(
                when {
                    running -> accent
                    paused -> 0xFFFF9F0A.toInt()
                    isDone(index) -> 0xFF34C759.toInt()
                    success > 0 -> 0xFFFF9F0A.toInt()
                    else -> subColor
                }
            )
            // 徽章做成小药丸（与漫画一致；不然在卡片上就是一行裸文字）
            background = pill(
                when {
                    running -> if (dark) 0x332E86C9 else 0x1A2E86C9
                    paused -> if (dark) 0x33FF9F0A else 0x1AFF9F0A
                    isDone(index) -> if (dark) 0x3334C759 else 0x1A34C759
                    else -> if (dark) 0x1AFFFFFF else 0x0F000000
                }
            )
        }

        // 卡片底（圆角 + 独立底色 + 卡片间留白，见 CardBackdrop）
        CardBackdrop.apply(item, CardBackdrop.Tone.CARD, dark)

        val activeCount = active[index].orEmpty().size
        val hasChildren = waitingCount > 0 || activeCount > 0 || failed.isNotEmpty()
        item.findViewById<TextView>(R.id.tv_chapter_expand).apply {
            text = if (row.expanded) "▾" else "▸"
            visibility = if (hasChildren) View.VISIBLE else View.INVISIBLE
            setTextColor(subColor)
            setOnClickListener { toggleChapter(index) }
        }

        item.findViewById<TextView>(R.id.btn_chapter_primary).apply {
            text = when {
                running -> ctx.getString(R.string.reader_translate_chapter_pause)
                paused -> ctx.getString(R.string.reader_translate_chapter_resume)
                else -> ctx.getString(R.string.reader_translate_chapter_translate)
            }
            setTextColor(if (running || paused) 0xFFFF9F0A.toInt() else accent)
            background = pill(
                when {
                    running || paused -> if (dark) 0x33FF9F0A else 0x1AFF9F0A
                    else -> if (dark) 0x332E86C9 else 0x1A2E86C9
                }
            )
            setOnClickListener { onChapterPrimary(index) }
        }
        item.findViewById<TextView>(R.id.btn_chapter_secondary).apply {
            // ⚠️ 只在**任务真的还在跑/暂停**时才显示「取消」；完成态必须是「清空译文」
            //（用户报的"已经翻完了还一直显示暂停和取消"）
            text = if (running || paused) ctx.getString(R.string.cancel)
            else ctx.getString(R.string.novel_translate_clear_chapter)
            setTextColor(0xFFCC5555.toInt())
            background = pill(if (dark) 0x33CC5555 else 0x14CC5555)
            setOnClickListener { onChapterSecondary(index) }
        }

        // 第二行：失败原因优先，其次「已翻 x/y · 字数」；有等待批时补一句还剩多少批
        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            if (totals[index] == null || chars[index] == null) onNeedTotal?.invoke(index)
            val base = when {
                failed.isNotEmpty() -> ctx.getString(
                    R.string.novel_chapter_failed_hint,
                    failed.size,
                    NovelFailCode.label(ctx, failed.first().failCode),
                )
                // 「已翻 3/40 · 12,345字」：段数给进度，字数给**费用估算**（用户要求放同一行）
                total > 0 && charCount > 0 -> ctx.getString(
                    R.string.novel_chapter_progress_chars,
                    ctx.getString(R.string.novel_chapter_progress, success, total),
                    chapterCharLabel(ctx, charCount),
                )
                total > 0 -> ctx.getString(R.string.novel_chapter_progress, success, total)
                charCount > 0 -> chapterCharLabel(ctx, charCount)
                else -> ""
            }
            val waitingText = when {
                activeCount > 0 && waitingCount > 0 ->
                    ctx.getString(R.string.novel_chapter_active_waiting_batches, activeCount, waitingCount)
                activeCount > 0 -> ctx.getString(R.string.novel_chapter_active_batches, activeCount)
                waitingCount > 0 -> ctx.getString(R.string.novel_chapter_waiting_batches, waitingCount)
                else -> ""
            }
            text = listOf(base, waitingText).filter { it.isNotBlank() }.joinToString(" · ")
            setTextColor(subColor)
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        // 失败明细：展开该章且确有失败时才显示
        val detail = item.findViewById<View>(R.id.detail_panel)
        val wantDetail = row.expanded && failed.isNotEmpty()
        // ⚠️ 展开/收起要有过渡（与漫画一致）：先让 TransitionManager 记下"展开前"的样子再改可见性
        val lastExpanded = item.getTag(R.id.detail_panel) as? Boolean
        if (lastExpanded != null && lastExpanded != wantDetail) {
            androidx.transition.TransitionManager.beginDelayedTransition(
                item as android.view.ViewGroup,
                androidx.transition.AutoTransition().setDuration(EXPAND_ANIM_MS)
            )
        }
        item.setTag(R.id.detail_panel, wantDetail)
        CardBackdrop.apply(detail, CardBackdrop.Tone.DETAIL, dark)
        if (wantDetail) {
            item.findViewById<TextView>(R.id.tv_detail_meta).apply {
                text = ctx.getString(R.string.novel_chapter_failed_meta, index + 1, failed.size)
                setTextColor(labelColor)
            }
            item.findViewById<TextView>(R.id.tv_detail_list).apply {
                text = failed.joinToString("\n") {
                    "#${it.paraIndex}  ${NovelFailCode.label(ctx, it.failCode)}  ${it.sourceText.take(20)}"
                }
                setTextColor(subColor)
            }
            item.findViewById<TextView>(R.id.tv_detail_collapse).apply {
                setTextColor(accent)
                setOnClickListener { collapseChapter(index) }
            }
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }

        // 点卡片空白处 = 跳到该章（并展开）；点箭头 = 只展开/收起
        item.setOnClickListener {
            onJump(index)
            expandChapter(index)
        }

        // **展开的批行填进卡片自己的容器**（在卡片的框里面）
        bindChildren(item, index)
    }

    /** 章内「等待」的批：一行一批，徽章固定「等待」（复用共用的页行布局）。 */
    private fun bindBatch(item: View, row: NovelTranslateRow.Batch) {
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val batch = row.batch
        if (density == 1f) density = item.resources.displayMetrics.density
        // 等待行也做成卡片（用户口径：展开出来的行不能是一行裸文字）
        // 子行**在卡片的框里**：只有状态需要区分时才染色（在飞=琥珀、等待=中性）
        CardBackdrop.apply(
            item,
            if (row.active) CardBackdrop.Tone.ACTIVE else CardBackdrop.Tone.WAITING,
            dark,
        )
        item.findViewById<TextView>(R.id.tv_page_label).apply {
            text = item.context.getString(
                R.string.novel_translate_batch_row,
                batch.ordinal + 1,
                batch.firstPara + 1,
                batch.lastPara + 1,
            )
            setTextColor(labelColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        item.findViewById<TextView>(R.id.tv_state_badge).apply {
            setText(
                if (row.active) R.string.reader_translate_state_translating
                else R.string.reader_translate_state_waiting
            )
            setBackgroundResource(
                if (row.active) R.drawable.bg_state_translating else R.drawable.bg_state_idle
            )
        }
        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            text = ""
            visibility = View.GONE
            setTextColor(subColor)
        }
        // 「详情 / 删除」是漫画独有的（共用布局里默认 gone）—— 小说这边**显式**保持 gone
        item.findViewById<TextView>(R.id.btn_row_detail).visibility = View.GONE
        item.findViewById<TextView>(R.id.btn_row_delete).visibility = View.GONE
        item.findViewById<View>(R.id.detail_panel).visibility = View.GONE
        item.setOnClickListener { onJump(row.chapterIndex) }
    }

    // ===== 展开态 =====

    private fun expandChapter(index: Int) {
        if (expanded.add(index)) rebuild()
    }

    private fun collapseChapter(index: Int) {
        if (expanded.remove(index)) rebuild()
    }

    fun toggleChapter(index: Int) {
        if (!expanded.remove(index)) expanded += index
        rebuild()
    }
}
