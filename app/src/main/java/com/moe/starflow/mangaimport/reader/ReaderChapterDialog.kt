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

/**
 * 漫画章节目录（顶部页码胶囊 / 翻译面板章标题点开）。
 *
 * 与小说目录同一套做法（`NovelTocDialog`）：高度按「行高 × min(章数, 上限)」钉死，
 * 小书贴内容、大书封顶滚动；配色**跟随阅读背景深浅**，不随全局主题。
 *
 * 每行右侧是**本章已译进度**（已译 5/20）—— 用户要「某章翻到哪」一眼可见，
 * 而不是只看个章名。
 */
object ReaderChapterDialog {

    private const val MAX_ROWS = 6

    /** 与 `item_manga_toc_row.xml` 的上下 padding + 14sp 文字算出的行高（dp）。 */
    private const val ROW_HEIGHT_DP = 44

    private const val ACCENT = 0xFF55AEEA.toInt()

    fun show(
        context: Context,
        chapters: List<MangaChapter>,
        currentIndex: Int,
        /** 本章已成功翻译的页数（宿主按翻译记录统计）。 */
        successOf: (MangaChapter) -> Int,
        dark: Boolean = false,
        onPick: (Int) -> Unit,
    ) {
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
        val adapter = RowAdapter(chapters, currentIndex, dark, successOf) { position ->
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

        dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()
        binding.btnTocCancel.setOnClickListener { dialog?.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white)
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 一章一行：标题（有文件夹名就显示它）+ 已译进度；当前章高亮。 */
    private class RowAdapter(
        private val chapters: List<MangaChapter>,
        private val currentIndex: Int,
        private val dark: Boolean,
        private val successOf: (MangaChapter) -> Int,
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

            val done = successOf(chapter)
            holder.binding.tvChapterBadge.text =
                ctx.getString(R.string.reader_chapter_badge, done, chapter.pageCount)
            holder.binding.tvChapterBadge.setTextColor(
                when {
                    done >= chapter.pageCount && chapter.pageCount > 0 -> 0xFF34C759.toInt()
                    done > 0 -> 0xFFFF9F0A.toInt()
                    else -> if (dark) 0xFF6E6E73.toInt() else android.graphics.Color.GRAY
                }
            )
            holder.itemView.setOnClickListener { onPick(position) }
        }
    }
}
