package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.databinding.ItemPagePreviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面预览弹窗：每行 3 张缩略图、可下滑，点击跳转对应页。
 * 缩略图从 [ReaderPageSource] 惰性解码（带缓存），当前页高亮边框。
 */
class ReaderPagePreviewDialog(
    private val context: Context,
    private val source: ReaderPageSource,
    currentPage: Int,
    private val onSelect: (Int) -> Unit
) {

    private val currentPage = currentPage.coerceIn(0, (source.size - 1).coerceAtLeast(0))

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_page_preview, null, false)
        val rv = view.findViewById<RecyclerView>(R.id.rv_preview)
        rv.layoutManager = GridLayoutManager(context, 3)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        rv.adapter = PreviewAdapter(source, currentPage, onSelect, dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_white)
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
    }

    private class PreviewAdapter(
        private val source: ReaderPageSource,
        private val currentPage: Int,
        private val onSelect: (Int) -> Unit,
        private val dialog: AlertDialog
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        class VH(val binding: ItemPagePreviewBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = source.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPagePreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val binding = holder.binding
            binding.tvPreviewPage.text = (position + 1).toString()
            binding.ivPreviewThumb.setImageDrawable(null)
            // 当前页高亮边框
            binding.root.setBackgroundResource(
                if (position == currentPage) R.drawable.bg_preview_current else R.drawable.bg_preview_cell
            )
            binding.root.setOnClickListener {
                dialog.dismiss()
                onSelect(position)
            }
            scope.launch {
                val thumb = withContext(Dispatchers.IO) { source.loadThumb(position) }
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