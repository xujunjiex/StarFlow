package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.databinding.DialogMangaTocBinding
import com.moe.starflow.databinding.ItemMangaTocRowBinding
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.mangaChapterLabel
import com.moe.starflow.utils.ReaderDialogs

/**
 * 漫画章节目录（**入口只有一个：点顶部页码胶囊** —— `MangaReaderActivity.tvPageIndicator`）。
 *
 * ⚠️ 别照旧注释去找"面板里的章标题"：`ReaderMenuSheet` **从来没引用过本类**
 * （`git log -S ReaderChapterDialog -- ReaderMenuSheet.kt` 是空的）。面板那边在
 * 2026-09 的章卡片改版里删掉的是「◀ 章 / 章 ▶」**切章**行，那本来也不是开目录的入口。
 * 小说侧的同款是 `NovelTocDialog`（入口 = `activity_novel_reader.xml` 的 `top_pill`）。
 *
 * 与小说目录同一套做法：高度按「行高 × min(章数, 上限)」钉死，
 * 小书贴内容、大书封顶滚动；配色**跟随阅读背景深浅**（走 `ReaderDialogs`），不随全局主题。
 *
 * 每行右侧是**本章实时状态**（用户口径 2026-09-28：点顶部胶囊要像文本那边一样展开、
 * 看得到各章的反应状态）：
 * | 状态 | 文案 |
 * |---|---|
 * | 正在翻 | 「翻译中 3/22」 |
 * | 暂停 | 「已暂停 3/22」 |
 * | 全部页成功 | 「翻译完成」（绿） |
 * | 有译文 | 「已译 5/22」（琥珀） |
 * | 没译过 | 「已译 0/22」（灰） |
 * 排队中的页数附在后面（有任务时）：「· 等待 7 页」。
 *
 * ⚠️ 宿主必须**在弹窗打开期间持续推新状态**（[Handle.refresh]）：任务进度、等待页数、
 * 译文数都在变，只取打开那一刻的快照就会出现"翻译在涨、目录里却一直不动"。
 */
object ReaderChapterDialog {

    private const val MAX_ROWS = 6

    /** 与 `item_manga_toc_row.xml` 的上下 padding + 14sp 文字算出的行高（dp）。 */
    private const val ROW_HEIGHT_DP = 44

    private const val ACCENT = 0xFF55AEEA.toInt()
    private const val AMBER = 0xFFFF9F0A.toInt()
    private const val GREEN = 0xFF34C759.toInt()

    /** 一章在目录里的**实时状态**（宿主按当前任务 / 等待页 / 译文统计填）。 */
    data class Status(
        /** 本章已成功翻译的页数。 */
        val success: Int = 0,
        /** 本章总页数。 */
        val total: Int = 0,
        /** 有章节批量任务在跑（含排队）。 */
        val running: Boolean = false,
        /** 任务被暂停。 */
        val paused: Boolean = false,
        /** 任务里已完成（结算）的页数。 */
        val jobDone: Int = 0,
        /** 任务自己的总页数（默认「只翻未完成」时 ≠ [total]，徽章分母要用它）。 */
        val jobTotal: Int = 0,
        /** 队列里还在等的页数（面板上的「等待」）。 */
        val waiting: Int = 0,
    ) {
        val allDone: Boolean get() = total > 0 && success >= total
    }

    /** 打开后的句柄：宿主用它把新状态推回弹窗。 */
    class Handle internal constructor(
        private val dialog: AlertDialog,
        /** 重绑列表（Adapter 是 private 类型，这里只暴露行为，不暴露类型）。 */
        private val onRefresh: () -> Unit,
    ) {
        /** 重新取一遍状态并重绑（宿主在弹窗打开期间定期调）。 */
        fun refresh() {
            if (dialog.isShowing) onRefresh()
        }

        val isShowing: Boolean get() = dialog.isShowing

        fun dismiss() = dialog.dismiss()
    }

    fun show(
        context: Context,
        chapters: List<MangaChapter>,
        currentIndex: Int,
        /** 取某章的实时状态（下标 = 章下标）。 */
        statusOf: (Int) -> Status,
        dark: Boolean = false,
        onPick: (Int) -> Unit,
    ): Handle {
        val binding = DialogMangaTocBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val divider = if (dark) 0x1AFFFFFF else 0x11000000

        binding.tvTocTitle.setTextColor(labelColor)
        binding.tvTocCount.setTextColor(subColor)
        binding.tvTocCount.text = context.getString(R.string.reader_chapter_count, chapters.size)
        binding.btnTocCancel.setTextColor(ACCENT)
        binding.tocDividerTop.setBackgroundColor(divider)
        binding.tocDividerBottom.setBackgroundColor(divider)

        binding.rvToc.layoutParams = binding.rvToc.layoutParams.apply {
            height = ((ROW_HEIGHT_DP * density).toInt() * chapters.size.coerceAtMost(MAX_ROWS))
                .coerceAtLeast((ROW_HEIGHT_DP * density).toInt())
        }
        var dialog: AlertDialog? = null
        val adapter = RowAdapter(chapters, currentIndex, dark, statusOf) { position ->
            // ⚠️ 跳章的同时必须关掉弹窗：不关的话章在弹窗背后换了，弹窗里的高亮还停在被点之前
            // 那一章 —— 看起来就是「点了目录没反应」（小说目录同一个坑）
            dialog?.dismiss()
            onPick(position)
        }
        binding.rvToc.layoutManager = LinearLayoutManager(context)
        binding.rvToc.adapter = adapter
        // 打开就定位到当前章：⚠️ 必须延到布局之后，弹窗里的列表在 show() 之前还没测量，
        // 直接 scrollToPosition 会静默失效，几百章的书打开后停在第一章
        binding.rvToc.post {
            val target = currentIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            (binding.rvToc.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset((target - 1).coerceAtLeast(0), 0)
        }
        // 行高先按常量估（避免首帧闪一下整屏高），首帧布局完再用**真实行高**校正一次
        binding.rvToc.post {
            val real = binding.rvToc.getChildAt(0)?.height ?: 0
            if (real > 0) {
                binding.rvToc.layoutParams = binding.rvToc.layoutParams.apply {
                    height = real * chapters.size.coerceAtMost(MAX_ROWS)
                }
            }
        }

        // ⚠️ 走 `ReaderDialogs.show`（**带兜底**）：主题/上下文那套万一失败会退回原上下文再建，
        // 绝不能因为"配色"让用户看到闪退（这里崩过两次：AppCompat 主题 + window token）
        val dialog2 = ReaderDialogs.show(context, dark) { setView(binding.root) }
        dialog = dialog2
        binding.btnTocCancel.setOnClickListener { dialog?.dismiss() }

        val dm = context.resources.displayMetrics
        dialog2.window?.setLayout((dm.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return Handle(dialog2) { adapter.notifyDataSetChanged() }
    }

    /** 一章一行：标题 + **实时状态**徽章；当前章高亮。 */
    private class RowAdapter(
        private val chapters: List<MangaChapter>,
        private val currentIndex: Int,
        private val dark: Boolean,
        private val statusOf: (Int) -> Status,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        private val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()

        class VH(val binding: ItemMangaTocRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMangaTocRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = chapters.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.itemView.context
            val chapter = chapters[position]
            val isCurrent = position == currentIndex

            holder.itemView.setBackgroundColor(
                when {
                    isCurrent && dark -> 0x3355AEEA
                    isCurrent -> 0x22007AFF
                    else -> android.graphics.Color.TRANSPARENT
                }
            )
            holder.binding.tvChapterTitle.text = mangaChapterLabel(ctx, chapter)
            holder.binding.tvChapterTitle.setTextColor(if (isCurrent) ACCENT else labelColor)

            val st = statusOf(position)
            // 状态优先：正在跑/暂停时显示**任务进度**（比"已译页数"更能说明"现在在干什么"）
            val base = when {
                // ⚠️ 分母用任务自己的 total（只翻未完成时 ≠ 章页数），否则进度会「倒退」
                st.running -> ctx.getString(
                    R.string.reader_translate_chapter_running,
                    st.jobDone,
                    if (st.jobTotal > 0) st.jobTotal else st.total,
                )
                st.paused -> ctx.getString(
                    R.string.chapter_translate_notify_paused,
                    st.jobDone,
                    if (st.jobTotal > 0) st.jobTotal else st.total,
                )
                st.allDone -> ctx.getString(R.string.reader_translate_chapter_done)
                else -> ctx.getString(R.string.reader_chapter_badge, st.success, st.total)
            }
            // 队列里还在等的页数（面板上的「等待」）——有任务时才有意义
            holder.binding.tvChapterBadge.text =
                if (st.waiting > 0) base + " · " + ctx.getString(R.string.reader_chapter_badge_waiting, st.waiting)
                else base
            holder.binding.tvChapterBadge.setTextColor(
                when {
                    st.running -> ACCENT
                    st.paused -> AMBER
                    st.allDone -> GREEN
                    st.success > 0 -> AMBER
                    else -> if (dark) 0xFF6E6E73.toInt() else android.graphics.Color.GRAY
                }
            )
            holder.itemView.setOnClickListener { onPick(position) }
        }
    }
}
