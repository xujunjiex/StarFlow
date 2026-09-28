package com.moe.starflow.novel.reader

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.utils.ReaderDialogs
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.databinding.DialogNovelTocBinding
import com.moe.starflow.databinding.ItemNovelTocRowBinding
import com.moe.starflow.novel.model.NovelChapterMeta

/**
 * 章节目录。带**翻译状态徽章**（未翻 / 部分 / 已翻）与当前章高亮。
 *
 * 全书进度由这个面板承载 —— 底部进度条只表示**章内**位置。全书几千页时一像素代表好几页，
 * 拖动毫无精度，那个信息在这里用「哪些章已翻」表达更准。
 *
 * ### 尺寸
 * 高度按「行高 × `min(章数, MAX_ROWS)`」算出来钉到列表上：
 * - `wrap_content` 会让上千章的书把面板撑满整屏
 * - 固定百分比又会让 6 章的小书留一大片空白（用户反馈「初始大小太大」就是这个）
 *
 * 列表撑满剩余空间 → 底部「取消」**永远贴在右下角**。
 *
 * ### 配色
 * **跟随阅读背景深浅**，不随全局主题：深色背景读小说时弹出一个白底目录很刺眼。
 */
object NovelTocDialog {

    /** 列表最多显示多少行（再多就滚动）。 */
    private const val MAX_ROWS = 6

    /** 与 `item_novel_toc_row.xml` 的上下 padding + 14sp 文字算出的行高（dp）。 */
    private const val ROW_HEIGHT_DP = 44

    private const val ACCENT = 0xFF55AEEA.toInt()

    fun show(
        context: Context,
        chapters: List<NovelChapterMeta>,
        stats: Map<Int, NovelChapterStat>,
        /** 每章的**真实可翻译段数**（宿主解析出来的分母），与面板同一份。 */
        totals: Map<Int, Int>,
        currentChapter: Int,
        dark: Boolean = false,
        onPick: (Int) -> Unit,
        /** 某章分母还不知道时问宿主一次（宿主解析完用 [Handle.update] 推回来）。 */
        onNeedTotal: ((Int) -> Unit)? = null,
    ): Handle {
        val binding = DialogNovelTocBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val divider = if (dark) 0x1AFFFFFF else 0x11000000

        binding.tvTocTitle.setTextColor(labelColor)
        binding.tvTocCount.setTextColor(subColor)
        binding.tvTocCount.text = context.getString(R.string.novel_chapter_count, chapters.size)
        binding.btnTocCancel.setTextColor(ACCENT)
        binding.tocDividerTop.setBackgroundColor(divider)
        binding.tocDividerBottom.setBackgroundColor(divider)

        // 高度 = 行高 × min(章数, 上限)：小书贴内容、大书封顶滚动
        binding.rvToc.layoutParams = binding.rvToc.layoutParams.apply {
            height = ((ROW_HEIGHT_DP * density).toInt() * chapters.size.coerceAtMost(MAX_ROWS))
                .coerceAtLeast((ROW_HEIGHT_DP * density).toInt())
        }
        var dialog: AlertDialog? = null
        val adapter = RowAdapter(
            chapters, stats, totals, currentChapter, dark, onNeedTotal,
        ) { position ->
            // ⚠️ **必须在跳章的同时关掉弹窗**：不关的话，章在弹窗背后换了，弹窗里的高亮
            // 还停在被点之前那一章 —— 看起来就是「点了目录，弹窗没跟着更新」。
            dialog?.dismiss()
            onPick(position)
        }
        binding.rvToc.layoutManager = LinearLayoutManager(context)
        binding.rvToc.adapter = adapter
        // 打开就定位到当前章。⚠️ 用 `scrollToPositionWithOffset` 并且**延到布局之后**执行：
        // 弹窗里的列表在 show() 之前还没测量，直接 scrollToPosition 会静默失效，
        // 几百章的书打开后停在第一章、高亮的那一章在屏幕外 —— 同样是「看着没同步」。
        binding.rvToc.post {
            val target = currentChapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            (binding.rvToc.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset((target - 1).coerceAtLeast(0), 0)
        }
        // 行高先按常量估（避免首帧闪一下整屏高），首帧布局完再用**真实行高**校正一次 ——
        // 系统字号放大时行会变高，照常量算会在面板底部留一条空白
        binding.rvToc.post {
            val real = binding.rvToc.getChildAt(0)?.height ?: 0
            if (real > 0) {
                binding.rvToc.layoutParams = binding.rvToc.layoutParams.apply {
                    height = real * chapters.size.coerceAtMost(MAX_ROWS)
                }
            }
        }

        // ⚠️ 走 `ReaderDialogs.show`（**带兜底**）：主题/上下文失败会退回原上下文再建，
        // 绝不能因为"配色"闪退（漫画目录弹窗崩过两次：AppCompat 主题 + window token）
        val dlg = ReaderDialogs.show(context, dark) { setView(binding.root) }
        dialog = dlg
        binding.btnTocCancel.setOnClickListener { dialog?.dismiss() }

        // ⚠️ 宽度也要显式限：AlertDialog 的自定义 View 默认按内容撑，宽屏上会贴满整屏
        val dm = context.resources.displayMetrics
        dlg.window?.setLayout((dm.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return Handle(dlg, adapter)
    }

    /**
     * 目录弹窗句柄：宿主解析出分母后用 [update] 回推，徽章才算得准。
     *
     * ⚠️ 目录**必须**和面板用同一份分母（见 [isChapterDone]）：早先这里直接拿
     * `st.total`（数据库里这一章的行数）判「已完成」，而那是**按批惰性写的**——
     * 只翻了一部分时 `success >= total` 也成立，于是目录上写着「已完成」、面板上写着
     * 「进行中」，同一个事实两个结论（用户报的「某一章没翻完却显示已经全部翻译完成」）。
     */
    class Handle internal constructor(
        private val dialog: AlertDialog?,
        private val adapter: RowAdapter,
    ) {
        /** 宿主解析出分母 / 状态有变化时推回。 */
        fun update(stats: Map<Int, NovelChapterStat>, totals: Map<Int, Int>) {
            adapter.stats = stats
            adapter.totals = totals
        }

        fun dismiss() {
            dialog?.dismiss()
        }
    }

    /** 一章一行：标题 + 状态徽章；当前章高亮。 */
    internal class RowAdapter(
        private val chapters: List<NovelChapterMeta>,
        stats: Map<Int, NovelChapterStat>,
        totals: Map<Int, Int>,
        private val currentChapter: Int,
        private val dark: Boolean,
        private val onNeedTotal: ((Int) -> Unit)?,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        /** 章状态。宿主推来新的就整表重绑（行数少，且「已完成」判据依赖它）。 */
        var stats: Map<Int, NovelChapterStat> = stats
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }

        /** 每章真实可翻译段数（分母）。拿不到就按「未完成」显示，等宿主推回来再重算。 */
        var totals: Map<Int, Int> = totals
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }

        private val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()

        class VH(val binding: ItemNovelTocRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemNovelTocRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = chapters.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.itemView.context
            val meta = chapters[position]
            val isCurrent = position == currentChapter

            holder.itemView.setBackgroundColor(
                when {
                    isCurrent && dark -> 0x3355AEEA
                    isCurrent -> 0x22007AFF
                    else -> android.graphics.Color.TRANSPARENT
                }
            )
            // 与面板章行同一套标题规则（有标题就只显示标题，别拼成「第1章 第1节」）
            holder.binding.tvChapterTitle.text = chapterDisplayTitle(ctx, position, meta.title)
            holder.binding.tvChapterTitle.setTextColor(if (isCurrent) ACCENT else labelColor)

            val st = stats[position]
            // ⚠️ 判据与面板**同一个函数** —— 两处各写一份正是上面那个 bug 的来源
            val done = isChapterDone(stats, totals, position)
            val started = st != null && st.success > 0
            val total = totals[position] ?: 0
            if (total <= 0) onNeedTotal?.invoke(position)
            holder.binding.tvChapterBadge.text = when {
                done -> ctx.getString(R.string.novel_chapter_done)
                started -> ctx.getString(R.string.novel_chapter_partial, st!!.success, total)
                else -> ctx.getString(R.string.novel_chapter_unread)
            }
            holder.binding.tvChapterBadge.setTextColor(
                when {
                    done -> 0xFF34C759.toInt()
                    started -> 0xFFFF9F0A.toInt()
                    else -> if (dark) 0xFF6E6E73.toInt() else android.graphics.Color.GRAY
                }
            )
            holder.itemView.setOnClickListener { onPick(position) }
        }
    }
}
