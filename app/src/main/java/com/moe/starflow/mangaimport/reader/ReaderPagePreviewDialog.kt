package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.utils.ReaderDialogs
import com.moe.starflow.databinding.ItemPagePreviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面预览弹窗：每行 3 张缩略图、可下滑，点击跳转对应页。缩略图从 [ReaderPageSource] 惰性解码
 * （带缓存），当前页高亮边框。
 *
 * ## 范围 = 调用方给的页区间（漫画阅读器传**当前章**）
 * 长按的是「本章的进度条」，网格就该只列本章的页（用户口径 2026-11：「长按应该是选择当前章节的
 * 预览，而不是整部书所有的章节」）。调用方用 `ReaderProgressScope.pageRange()` 算区间，
 * 与进度条拖拽/绿条/紫条同一个坐标域 —— 否则同一条进度条上「拖」是章内、「长按」是整本，两套口径。
 *
 * 格子里显示的序号是**章内第几页**（与顶部胶囊一致），点击回传的是**全书页号**（宿主直接跳页）。
 */
class ReaderPagePreviewDialog(
    private val context: Context,
    private val source: ReaderPageSource,
    /** 预览范围：**全书页号**闭区间（调用方按当前章算，见 `ReaderProgressScope.pageRange`）。 */
    private val pages: IntRange,
    /** 当前页（**全书页号**）；不在 [pages] 内时不高亮（理论上不会，章表过期已由 scope 兜底）。 */
    currentPage: Int,
    /** 章节标签：有就拼进标题，让用户一眼看出网格只含本章。 */
    private val chapterLabel: String? = null,
    /** 阅读背景是否深色（弹窗底色/文字跟它走，见 `utils/ReaderDialogs`）。 */
    private val dark: Boolean = false,
    private val onSelect: (Int) -> Unit
) {

    private val pageCount: Int = pages.count()

    /** 当前页的格号（0 基）；不在范围内给 -1。 */
    private val currentLocal: Int =
        if (pageCount <= 0) -1 else (currentPage - pages.first).takeIf { it in 0 until pageCount } ?: -1

    /** 显示弹窗；返回实例便于调用方/测试检查内容。 */
    fun show(): AlertDialog {
        // 自定义内容视图（XML 里写死颜色）→ 用 tintCustomView 按亮度翻转
        val themed = ReaderDialogs.context(context, dark)
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_page_preview, null, false)
        view.findViewById<TextView>(R.id.tv_preview_title).text = if (chapterLabel.isNullOrBlank()) {
            themed.getString(R.string.reader_preview_title)
        } else {
            themed.getString(R.string.reader_preview_title_chapter, chapterLabel)
        }
        val rv = view.findViewById<RecyclerView>(R.id.rv_preview)
        rv.layoutManager = GridLayoutManager(context, 3)
        val dialog = AlertDialog.Builder(themed)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        rv.adapter = PreviewAdapter(source, pages, currentLocal, onSelect, dialog, dark)
        dialog.show()
        // ⚠️ 底 + 文字全部走公共实现：窗口底贴本项目的圆角底，自定义内容视图按亮度翻转
        //（"恒白底" 与 "一律刷浅色" 都会出事，见 ReaderDialogs 头注释）
        ReaderDialogs.style(dialog, dark)
        ReaderDialogs.tintCustomView(view, dark)
        // 长按进度条打开的预览：内容淡入 + 轻微缩放进入动画
        try {
            view.alpha = 0f
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.post {
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
        } catch (ignored: Exception) {
        }
        return dialog
    }

    private class PreviewAdapter(
        private val source: ReaderPageSource,
        /** 全书页号区间（见 [ReaderPagePreviewDialog.pages]）。 */
        private val pages: IntRange,
        /** 当前页在网格里的格号（-1 = 不在范围内）。 */
        private val currentLocal: Int,
        /** 点击回传**全书页号**。 */
        private val onSelect: (Int) -> Unit,
        private val dialog: AlertDialog,
        /** 阅读背景深色 → 格子底换成深色变体（浅底在深色弹窗上是亮斑）。 */
        private val dark: Boolean
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        class VH(val binding: ItemPagePreviewBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = pages.count()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPagePreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val binding = holder.binding
            val globalPage = pages.first + position
            // 序号 = **章内**第几页（与顶部胶囊同一个口径）；点击回传的才是全书页号
            binding.tvPreviewPage.text = (position + 1).toString()
            binding.ivPreviewThumb.setImageDrawable(null)
            // 当前页高亮边框（格子底同样跟阅读背景深浅）
            binding.root.setBackgroundResource(
                when {
                    position == currentLocal && dark -> R.drawable.bg_preview_current_dark
                    position == currentLocal -> R.drawable.bg_preview_current
                    dark -> R.drawable.bg_preview_cell_dark
                    else -> R.drawable.bg_preview_cell
                }
            )
            binding.root.setOnClickListener {
                dialog.dismiss()
                onSelect(globalPage)
            }
            scope.launch {
                val thumb = withContext(Dispatchers.IO) { source.loadThumb(globalPage) }
                if (thumb != null) {
                    binding.ivPreviewThumb.setImageBitmap(thumb)
                }
            }
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            scope.cancel()
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }
}
