package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.ChapterTaskStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 翻译面板的一行：**章节卡片** 或 章内某一页的翻译记录。 */
sealed interface TranslateRow {
    data class Header(
        val chapterIndex: Int,
        val label: String,
        val success: Int,
        val pageCount: Int,
        val expanded: Boolean,
        val selected: Boolean,
        /** 该章的后台任务状态（null = 没任务）。按钮文案与「进行中」都按它显示。 */
        val jobState: ChapterJobState?,
        val jobDone: Int,
        /** 本页所属章**任务自己的**总页数（只翻未完成时 ≠ 章页数）。 */
        val jobTotal: Int,
    ) : TranslateRow

    data class Page(
        val row: ImportedPageTranslation,
        val chapterIndex: Int,
        /** 排队中（还没开始翻）：显示「等待」而不是状态徽章。 */
        val waiting: Boolean,
        /**
         * 在途页**此刻在哪一段**（识别中 / 翻译中）；null = 这不是在途页（真记录行）。
         *
         * ⚠️ 用户口径（2026-09-28）：「进行中的状态只包含两个：识别中（OCR）和翻译中」——
         * 并发数只作用于翻译那一段，所以同一时刻最多 1 页识别中 + N 页翻译中。
         * 两者必须**分开显示**，不能都写成「翻译中」（用户看到"一次冒出三个一样的卡片"会懵）。
         */
        val stage: ChapterTaskStage? = null,
    ) : TranslateRow
}

/**
 * 翻译面板的**两段式**列表（漫画）：每章一张**卡片**，展开后才是该章的逐页记录。
 *
 * 用户口径（2026-10 改版）：
 * - 章卡片比页行**大**（16sp 加粗 vs 13sp），卡片上直接放两个按钮：
 *   `翻译本章 / 暂停 / 继续` + `清除本章译文 / 取消`
 * - **点卡片空白处 = 跳到该章**（切章只从记录里切，面板不再有左右切章组件）
 * - 排队中的页显示「等待」（纯内存态，不写库）
 * - 未翻译的页默认不进列表（只列有记录的页 + 正在排队的页）
 *
 * ⚠️ 页行沿用**共用布局** `item_translate_page_state.xml`（小说那边也用同一个文件）：
 * 底部的「删除」按钮只有本适配器会显式 VISIBLE（小说那边不碰它 → 保持 GONE）。
 */
class ReaderPageStateAdapter(
    /** 章标题的本地化（需要 Context，由面板注入 `mangaChapterLabel`）。 */
    private val chapterLabelOf: (MangaChapter) -> String,
    private val onJump: (Int) -> Unit,
    private val onSelectChapter: (Int) -> Unit,
    /** 章卡片主按钮：没有任务 = 翻译本章；跑着 = 暂停；暂停了 = 继续。 */
    private val onChapterPrimary: (Int) -> Unit,
    /** 章卡片次按钮：没有任务 = 清除本章译文（带确认）；有任务 = 取消任务。 */
    private val onChapterSecondary: (Int) -> Unit,
    private val onDeletePage: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_DONE = 1
        const val FILTER_ONGOING = 2
        const val FILTER_FAILED = 3

        private const val TYPE_CHAPTER = 0

        /** 展开/收起详情的过渡时长。 */
        private const val EXPAND_ANIM_MS = 160L

        /** 「轮到这一页了」的淡入时长。 */
        private const val ACTIVE_FADE_MS = 220L
    }

    private var chapters: List<MangaChapter> = emptyList()
    private var records: List<ImportedPageTranslation> = emptyList()
    private var jobs: Map<Int, ChapterJobState> = emptyMap()
    private var jobDone: Map<Int, Int> = emptyMap()
    private var jobTotal: Map<Int, Int> = emptyMap()
    private var waitingPages: Set<Int> = emptySet()

    /** 在途页：**识别中**（OCR 阶段）的页号集合。 */
    private var ocrPages: Set<Int> = emptySet()

    /** 在途页：**翻译中**（请求已发出 / 本地推理）的页号集合。 */
    private var translatingPages: Set<Int> = emptySet()
    private var selectedChapter = 0
    private var filter = FILTER_ALL

    /** 展开的章（下标集合）。默认展开**当前章**，让用户一开面板就看到自己刚翻的页。 */
    private val expanded = mutableSetOf<Int>()

    /** 列表项 = **每章一个**（展开的页行挂在卡片内部，不再是独立列表项）。 */
    private var rows: List<TranslateRow.Header> = emptyList()

    /** 面板深浅（随阅读背景切换），行内文字配色跟随。 */
    var dark = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 面板是打开那一刻的快照，宿主每次推送都整表重建（行数不多，且分组依赖状态）。 */
    fun submit(
        chapters: List<MangaChapter>,
        records: List<ImportedPageTranslation>,
        selectedChapter: Int,
        jobs: Map<Int, ChapterJobState> = emptyMap(),
        jobDone: Map<Int, Int> = emptyMap(),
        jobTotal: Map<Int, Int> = emptyMap(),
        waitingPages: Set<Int> = emptySet(),
        /**
         * 在途页（章节任务正在 OCR/翻译）：必须补成「识别中 / 翻译中」行，
         * 否则卡片会在开始时消失。**两个阶段分开传**（用户口径：进行中只有这两个状态）。
         */
        ocrPages: Set<Int> = emptySet(),
        translatingPages: Set<Int> = emptySet(),
    ) {
        // ⚠️ 判据是「**章表变了**」而不是「这是第一次提交」：宿主会先往面板字段里存一份章表
        // 再调这里，用「this.chapters.isEmpty()」判首次会被那一步提前破坏，
        // 结果是**打开面板时当前章没有自动展开**（用户看到一列章卡片、以为记录丢了）
        val chaptersChanged = this.chapters != chapters
        this.chapters = chapters
        this.records = records
        this.selectedChapter = selectedChapter
        this.jobs = jobs
        this.jobDone = jobDone
        this.jobTotal = jobTotal
        this.waitingPages = waitingPages
        this.ocrPages = ocrPages
        this.translatingPages = translatingPages
        if (chaptersChanged) expanded += selectedChapter
        rebuild()
    }

    fun setFilter(key: Int) {
        if (filter == key) return
        filter = key
        rebuild()
    }

    /** 展开/收起某章（点卡片右侧箭头）。 */
    fun toggleChapter(index: Int) {
        if (!expanded.remove(index)) expanded += index
        rebuild()
    }

    /** 切到某章时把它展开（选中即展开，符合「点了就是看它」的直觉）。 */
    fun expandChapter(index: Int) {
        if (expanded.add(index)) rebuild()
    }

    private fun rebuild() {
        // ⚠️ **展开态必须保住**：宿主每翻完一页/每次阶段变化都 push（整表重建），
        // 以前这里 `expandedPage = null` → 一边翻译一边看详情时，下一帧详情就被收起
        // （注释还写着"不会指错行"，与代码正好相反）。
        // 行是"按页号"记的，重建后不会指错行；页不在列表里时下面 bindChild 自然不显示。
        rows = buildRows()
        notifyDataSetChanged()
    }

    /**
     * 页号 → 章下标（二分查找）。
     *
     * ⚠️ 不要写成「遍历所有章、把每页塞进一张 map」：面板**每完成一页就整表重建**，
     * 几百页的书每帧建一次全量映射纯属白烧（章节区间是连续的升序区间，二分 O(log n) 就够）。
     * 返回 -1 = 不属于任何章（章节表与实际页数不一致时会出现）。
     */
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

    /**
     * **每章一行**（一个章 = 一个列表项）。
     *
     * ⚠️ 展开的 pxx 行**不再是独立的列表项**：它们由 [bindChildren] inflate 到卡片自己的
     * `chapter_children` 容器里，所以视觉上落在卡片的边框**之内**
     * （用户口径：「每个章节卡片外面有一个框，pxx 行要在框里面」，强调过三次）。
     * 这样也避免了"另起一张卡"的观感。
     */
    private fun buildRows(): List<TranslateRow.Header> {
        if (chapters.isEmpty()) return emptyList()
        val successByChapter = HashMap<Int, Int>()
        for (r in records) {
            if (r.state != ImportedPageTranslation.STATE_SUCCESS) continue
            val idx = chapterIndexFor(r.pageIndex)
            if (idx >= 0) successByChapter[idx] = (successByChapter[idx] ?: 0) + 1
        }
        return chapters.mapIndexed { index, chapter ->
            TranslateRow.Header(
                chapterIndex = index,
                label = chapterLabelOf(chapter),
                success = successByChapter[index] ?: 0,
                pageCount = chapter.pageCount,
                expanded = index in expanded,
                selected = index == selectedChapter,
                jobState = jobs[index],
                jobDone = jobDone[index] ?: 0,
                jobTotal = jobTotal[index] ?: 0,
            )
        }
    }

    /** 页号所属章的下标 = 它在列表里的位置（列表项就是每章一个）。 */
    private fun notifyPositionOfPage(page: Int): Int = chapterIndexFor(page).coerceAtLeast(0)

    /** 某章展开后要显示的页行（已有记录的页 + 在途页 + 排队中的页），按筛选过滤。 */
    private fun childRowsOf(chapterIndex: Int): List<TranslateRow.Page> {
        val chapter = chapters.getOrNull(chapterIndex) ?: return emptyList()
        // ⚠️ 宿主传进来的 `waitingPages` 里**也包含预取中的在途页**（`QUEUED`：已进流水线、
        // 还没轮到识别）。它们必须先按「等待」渲染 —— 减掉它们，否则会走下面的在途分支被标成
        // 「识别中/翻译中」（用户报的"一启动同时出现 3 个识别中"就是这么来的）。
        val inFlight = (ocrPages + translatingPages) - waitingPages
        val recorded = records
            .filter { chapterIndexFor(it.pageIndex) == chapterIndex }
            .filter { it.state != ImportedPageTranslation.STATE_IDLE }
            // ⚠️ **在途页一律走阶段行**（见 ①），哪怕它带着上一次的记录：重翻时库里还是
            // SUCCESS/FAILED，照记录渲染会出现「明明在识别中，卡片写着已完成」。
            .filter { it.pageIndex !in inFlight }
            .filter { filter == FILTER_ALL || matchesFilter(it) }
            .sortedBy { it.pageIndex }
        val out = ArrayList<TranslateRow.Page>(recorded.size + 6)
        // ① **在途页**：库状态可能还是 IDLE（OCR 阶段不写库），而面板不显示 IDLE 行
        //    → 不补这些页，用户就会看到「正在翻译的页卡片突然消失」（2026-09-28 用户报的）。
        //    ⚠️ **两个阶段分别标注**：识别中（OCR，恒只有 1 页）/ 翻译中（并发 N 页）。
        //    用户口径就是"进行中只包含这两个状态"，两个都写成「翻译中」会被当成 bug。
        if (filter == FILTER_ALL || filter == FILTER_ONGOING) {
            (chapter.startPage..chapter.endPage)
                .filter { it in inFlight }
                .sorted()
                .forEach { page ->
                    out += TranslateRow.Page(
                        ImportedPageTranslation(
                            mangaId = -1, pageIndex = page,
                            state = ImportedPageTranslation.STATE_TRANSLATING,
                        ),
                        chapterIndex, waiting = false,
                        stage = if (page in ocrPages) ChapterTaskStage.OCR else ChapterTaskStage.TRANSLATE,
                    )
                }
        }
        recorded.forEach { out += TranslateRow.Page(it, chapterIndex, waiting = false) }
        // ② 「等待」的页不在 records 里（纯内存态），单独补在后面
        if (filter == FILTER_ALL || filter == FILTER_ONGOING) {
            val shown = recorded.map { it.pageIndex }.toSet() + out.map { it.row.pageIndex }
            (chapter.startPage..chapter.endPage)
                .filter { it in waitingPages && it !in shown }
                .sorted()
                .forEach { page ->
                    out += TranslateRow.Page(
                        ImportedPageTranslation(
                            mangaId = -1, pageIndex = page,
                            state = ImportedPageTranslation.STATE_IDLE,
                        ),
                        chapterIndex, waiting = true,
                    )
                }
        }
        return out
    }

    private fun matchesFilter(r: ImportedPageTranslation): Boolean = when (filter) {
        FILTER_DONE -> r.state == ImportedPageTranslation.STATE_SUCCESS
        FILTER_ONGOING -> r.state == ImportedPageTranslation.STATE_TRANSLATING
        FILTER_FAILED -> r.state == ImportedPageTranslation.STATE_FAILED
        else -> true
    }

    /** 当前展开了详情的**页号**（0 基；null = 没有展开的详情）。 */
    private var expandedPage: Int? = null

    override fun getItemViewType(position: Int): Int = TYPE_CHAPTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        HeaderVH(LayoutInflater.from(parent.context).inflate(R.layout.item_translate_chapter_row, parent, false))

    override fun getItemCount(): Int = rows.size

    class HeaderVH(item: View) : RecyclerView.ViewHolder(item)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindHeader(holder.itemView, rows[position])
    }

    /**
     * 章卡片：大字号标题 + 已译徽章 + 展开箭头 + 两个动作按钮。
     *
     * 按钮文案随任务状态切换（用户口径）：
     * - 无任务：`翻译本章` / `清除本章译文`
     * - 跑着：`暂停` / `取消`
     * - 暂停：`继续` / `取消`
     */
    private fun bindHeader(item: View, row: TranslateRow.Header) {
        if (itemDensity == 1f) itemDensity = item.resources.displayMetrics.density
        val ctx = item.context
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val accent = 0xFF55AEEA.toInt()
        val amber = 0xFFFF9F0A.toInt()
        // 卡片底（圆角 + 独立底色，随面板深浅）
        CardBackdrop.apply(item, CardBackdrop.Tone.CARD, dark)

        item.findViewById<TextView>(R.id.tv_chapter_label).apply {
            text = row.label
            setTextColor(if (row.selected) accent else labelColor)
        }

        val running = row.jobState == ChapterJobState.RUNNING || row.jobState == ChapterJobState.QUEUED
        val paused = row.jobState == ChapterJobState.PAUSED
        // 「这一章翻完了」= 成功页数覆盖整章（用户口径：完成的章要有**完成标记**，而不是只剩按钮在变）
        val allDone = row.pageCount > 0 && row.success >= row.pageCount
        item.findViewById<TextView>(R.id.tv_chapter_badge).apply {
            text = when {
                // ⚠️ 分母用**任务自己的 total**：默认「只翻未完成」时任务 total 只是待翻页数，
                // 用章页数会出现「已译 12/22」→「翻译中 0/22」→ 停在 10/22 的倒退（小说侧一直是 done/total）。
                running -> ctx.getString(
                    R.string.reader_translate_chapter_running,
                    row.jobDone,
                    if (row.jobTotal > 0) row.jobTotal else row.pageCount,
                )
                paused -> ctx.getString(
                    R.string.chapter_translate_notify_paused,
                    row.jobDone,
                    if (row.jobTotal > 0) row.jobTotal else row.pageCount,
                )
                allDone -> ctx.getString(R.string.reader_translate_chapter_done)
                else -> ctx.getString(R.string.reader_chapter_badge, row.success, row.pageCount)
            }
            setTextColor(
                when {
                    running -> accent
                    paused -> amber
                    allDone -> 0xFF34C759.toInt()
                    row.success > 0 -> amber
                    else -> if (dark) 0xFF6E6E73.toInt() else 0xFF888888.toInt()
                }
            )
            background = pill(
                when {
                    running -> if (dark) 0x332E86C9 else 0x1A2E86C9
                    paused -> if (dark) 0x33FF9F0A else 0x1AFF9F0A
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
            text = when {
                running -> ctx.getString(R.string.reader_translate_chapter_pause)
                paused -> ctx.getString(R.string.reader_translate_chapter_resume)
                else -> ctx.getString(R.string.reader_translate_chapter_translate)
            }
            setTextColor(if (running || paused) amber else accent)
            // 主按钮做成"实心药丸"：卡片上一个明确的主操作，不再是一行裸文字
            background = pill(
                when {
                    running || paused -> if (dark) 0x33FF9F0A else 0x1AFF9F0A
                    else -> if (dark) 0x332E86C9 else 0x1A2E86C9
                }
            )
            setOnClickListener { onChapterPrimary(row.chapterIndex) }
        }
        item.findViewById<TextView>(R.id.btn_chapter_secondary).apply {
            // ⚠️ 只在**任务真的还在跑/暂停**时才显示「取消」；完成态必须是「清空译文」
            //（用户报的"已经翻完了还一直显示暂停和取消"）
            text = if (running || paused) ctx.getString(R.string.cancel)
            else ctx.getString(R.string.reader_translate_chapter_clear)
            setTextColor(0xFFCC5555.toInt())
            background = pill(if (dark) 0x33CC5555 else 0x14CC5555)
            setOnClickListener { onChapterSecondary(row.chapterIndex) }
        }

        // 点卡片空白处 = 跳该章（切章只从记录里切）；点箭头 = 只展开/收起
        item.setOnClickListener {
            onSelectChapter(row.chapterIndex)
            expandChapter(row.chapterIndex)
        }
        item.findViewById<TextView>(R.id.tv_chapter_expand).setOnClickListener {
            toggleChapter(row.chapterIndex)
        }

        // **展开的 pxx 行填进卡片自己的容器**（在卡片的框里面）
        bindChildren(item, row)
    }

    /**
     * 把该章展开后的页行填进卡片的 `chapter_children` 容器。
     *
     * ⚠️ **复用已有子视图**：面板每完成一页就会整表重建，几百页的章每次都重新 inflate
     * 会把面板拖卡；数量一致时只重绑，数量变了才重建。
     */
    private fun bindChildren(card: View, header: TranslateRow.Header) {
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

    /** 药丸底：按钮/徽章统一用它（圆角 + 半透明填充，随面板深浅）。 */
    private fun pill(argb: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f * itemDensity
        setColor(argb)
    }

    private var itemDensity: Float = 1f

    private fun bindChild(item: View, chapterIndex: Int, row: TranslateRow.Page) {
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val accent = 0xFF55AEEA.toInt()
        val page = row.row
        if (itemDensity == 1f) itemDensity = item.resources.displayMetrics.density

        // 子行**在卡片的框里**：普通行不画底（白卡片直接透出来），只有状态需要区分时才染色
        // —— 排队中=中性、「识别中」=蓝色、**正在提交/等待返回=琥珀高亮**、失败=淡红（用户口径）
        val tone = when {
            row.waiting -> CardBackdrop.Tone.WAITING
            row.stage == ChapterTaskStage.OCR -> CardBackdrop.Tone.OCR
            row.stage == ChapterTaskStage.TRANSLATE ||
                page.state == ImportedPageTranslation.STATE_TRANSLATING -> CardBackdrop.Tone.ACTIVE
            page.state == ImportedPageTranslation.STATE_FAILED -> CardBackdrop.Tone.FAILED
            else -> null
        }
        // ⚠️ 子行必须走 `applyNested`（方角 + 左侧 3dp 竖线 + 上下零间距）：
        // 用普通 `apply` 是 8dp 圆角独立色块 → 看着像"另起一张卡"（用户 2026-09-27 反馈过两次，
        // 这个函数就是为它写的，之前一直没人调用）。
        // 普通行也走 applyNested（`Tone.PLAIN` 就是为它准备的）：全用它才能保证"所有展开行
        // 都是方角 + 左侧竖线"，不会出现带状态的方角行与无状态的透明行混排。
        CardBackdrop.applyNested(item, tone ?: CardBackdrop.Tone.PLAIN, dark)

        item.findViewById<TextView>(R.id.tv_page_label).apply {
            text = "P${page.pageIndex + 1}"
            setTextColor(labelColor)
        }
        val badge = item.findViewById<TextView>(R.id.tv_state_badge)
        if (row.waiting) {
            // 排队中：纯内存态（不写库），显示「等待」
            badge.setText(R.string.reader_translate_state_waiting)
            badge.setBackgroundResource(R.drawable.bg_state_idle)
        } else if (row.stage == ChapterTaskStage.OCR) {
            // 识别中（OCR 串行阶段）—— 与「翻译中」必须区分开
            badge.setText(R.string.reader_translate_state_ocr)
            badge.setBackgroundResource(R.drawable.bg_state_ocr)
        } else if (row.stage == ChapterTaskStage.TRANSLATE) {
            badge.setText(R.string.reader_translate_state_translating)
            badge.setBackgroundResource(R.drawable.bg_state_translating)
        } else {
            badge.setText(contextString(item.context, when (page.state) {
                ImportedPageTranslation.STATE_TRANSLATING -> R.string.reader_translate_state_translating
                ImportedPageTranslation.STATE_SUCCESS -> R.string.reader_translate_state_success
                ImportedPageTranslation.STATE_FAILED -> R.string.reader_translate_state_failed
                else -> R.string.reader_translate_state_idle
            }))
            badge.setBackgroundResource(
                when (page.state) {
                    ImportedPageTranslation.STATE_SUCCESS -> R.drawable.bg_state_success
                    ImportedPageTranslation.STATE_FAILED -> R.drawable.bg_state_failed
                    ImportedPageTranslation.STATE_TRANSLATING -> R.drawable.bg_state_translating
                    else -> R.drawable.bg_state_idle
                }
            )
        }

        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            text = page.failMessage
            visibility = if (!row.waiting && !page.failMessage.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        // 详情的展开态按**页号**记（列表项不再是每页一项）
        val expanded = expandedPage == page.pageIndex
        val btnDetail = item.findViewById<TextView>(R.id.btn_row_detail)
        btnDetail.setTextColor(accent)
        btnDetail.visibility = if (row.waiting) View.GONE else View.VISIBLE
        btnDetail.text = contextString(item.context,
            if (expanded) R.string.reader_translate_collapse else R.string.reader_translate_row_detail)

        // 删除本页译文（整行数据）。⚠️ 必须**显式**置 VISIBLE：本布局与小说面板共用，
        // 默认 gone，回收复用时不赋值就会把上一行的按钮状态带过来（或永远不显示）
        item.findViewById<TextView>(R.id.btn_row_delete).apply {
            visibility = if (row.waiting) View.GONE else View.VISIBLE
            setOnClickListener { onDeletePage(page.pageIndex) }
        }

        val detail = item.findViewById<View>(R.id.detail_panel)
        val wantDetail = expanded && !row.waiting
        // ⚠️ **展开/收起要有动画**（用户口径）：先让 TransitionManager 记下"展开前"的样子，
        // 再改可见性 —— 之后那次布局就会把高度变化补成过渡；直接 setVisibility 是硬切、很生硬。
        val holder = (item.getTag(R.id.detail_panel) as? Boolean)
        if (holder != null && holder != wantDetail) {
            androidx.transition.TransitionManager.beginDelayedTransition(
                item as android.view.ViewGroup,
                androidx.transition.AutoTransition().setDuration(EXPAND_ANIM_MS)
            )
        }
        item.setTag(R.id.detail_panel, wantDetail)
        if (wantDetail) {
            fillDetail(item, page, labelColor, subColor, accent)
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }
        // 详情块也做成圆角块（不再是一块直角灰底）
        CardBackdrop.apply(detail, CardBackdrop.Tone.DETAIL, dark)

        // 正在提交/等待返回的那一行：淡入一下，让"轮到它了"看得见
        if (!row.waiting && (row.stage != null || page.state == ImportedPageTranslation.STATE_TRANSLATING)) {
            item.alpha = 0.55f
            item.animate().alpha(1f).setDuration(ACTIVE_FADE_MS).start()
        } else {
            item.animate().cancel()
            item.alpha = 1f
        }

        // 点整行 = 跳页；点详情按钮 = 展开/收起（重绑这一章的子行）
        item.setOnClickListener { if (!expanded && !row.waiting) onJump(page.pageIndex) }
        btnDetail.setOnClickListener {
            expandedPage = if (expanded) null else page.pageIndex
            notifyItemChanged(chapterIndex)
        }
    }

    /** 像屏幕翻译历史那样：元数据（翻译器/语言/时间）+ 逐条 原文(淡)+译文(主)。拖拽选择复制，无复制按钮。 */
    private fun fillDetail(
        item: View,
        row: ImportedPageTranslation,
        labelColor: Int,
        subColor: Int,
        accent: Int
    ) {
        val meta = buildString {
            row.translatorName?.takeIf { it.isNotBlank() }?.let { append(it).append("\n") }
            val langs = listOfNotNull(row.sourceLang, row.targetLang)
            if (langs.isNotEmpty()) append(langs.joinToString(" → ")).append(" · ")
            if (row.updatedAtMs > 0L) {
                append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(row.updatedAtMs)))
            }
        }
        item.findViewById<TextView>(R.id.tv_detail_meta).apply {
            text = meta.trim()
            setTextColor(subColor)
        }

        val originals = TranslationCacheUtils.parseIndexedTextList(row.sourceText)
        val translations = TranslationCacheUtils.parseIndexedTextList(row.translatedText)
        val sb = SpannableStringBuilder()
        for (i in originals.indices) {
            sb.append("[${i + 1}] ")
            sb.append(originals[i], ForegroundColorSpan(subColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n[${i + 1}] ")
            sb.append(translations.getOrNull(i).orEmpty(), ForegroundColorSpan(labelColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < originals.size - 1) sb.append("\n\n")
        }
        item.findViewById<TextView>(R.id.tv_detail_list).text = sb
        item.findViewById<TextView>(R.id.tv_detail_list).setTextColor(labelColor)

        // 底部收起（与顶部「详情/收起」同一逻辑）
        item.findViewById<TextView>(R.id.tv_detail_collapse).apply {
            setTextColor(accent)
            setOnClickListener {
                expandedPage = null
                notifyItemChanged(notifyPositionOfPage(row.pageIndex))
            }
        }
    }

    private fun contextString(context: Context, res: Int): String = context.getString(res)
}
