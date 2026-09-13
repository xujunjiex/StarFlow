package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.databinding.ItemMangaReaderPageBinding
import com.moe.starflow.databinding.ItemReaderDoublePageBinding
import com.moe.starflow.databinding.ItemWebtoonPageBinding
import com.moe.starflow.ui.viewer.ZoomableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页适配器公共回调/滤镜来源。 */
private class Shared(val source: ReaderPageSource) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var filter: () -> ReaderColorFilter? = { null }
    var onInteraction: () -> Unit = {}
    var onTap: (Float, Float) -> Unit = { _, _ -> }
    @Volatile
    var visibleImage: com.moe.starflow.ui.viewer.ZoomableImageView? = null
    /** 页图提供者：返回该页「应显示」的图（译文/原文渲染图）或 null（显示原图）。由阅读器注入。
     *  ⚠️ 必须在 IO 线程安全、可同步返回（内部是 LruCache.get）。重绑/复用页时优先用它，防止把译图覆盖回原图。 */
    @Volatile
    var pageImage: ((Int) -> Bitmap?)? = null
}

private fun bindImageCommon(img: ZoomableImageView, shared: Shared, page: Int, isCurrent: () -> Boolean) {
    if (img.onInteraction == null) img.onInteraction = shared.onInteraction
    if (img.onSingleTapConfirmed == null) img.onSingleTapConfirmed = shared.onTap
    loadTo(img, shared, page, isCurrent)
}

/**
 * 页面 item 复用/绑定前清除上轮 page transformer 残留的 transform（alpha/缩放/旋转/位移）。
 * ViewPager2 复用池不会自动复位这几项：手动翻页滑走的页带着旧动画状态进池，
 * 换动画方式后重新绑定时会带着残留上屏 → 堆叠/黑屏。必须在每次绑定开始时清零。
 */
private fun resetItemTransform(root: View) {
    root.alpha = 1f
    root.translationX = 0f
    root.translationY = 0f
    root.scaleX = 1f
    root.scaleY = 1f
    root.rotationX = 0f
    root.rotationY = 0f
    root.rotation = 0f
    root.pivotX = root.width / 2f
    root.pivotY = root.height / 2f
    root.translationZ = 0f
    (root as? CurlPageView)?.clearFold()
}

/** 在一个 ZoomableImageView 上：上滤镜、异步载全图。shared.visibleImage 供实时预览。
 *  不清图：切页/重绑瞬间保留旧图直到新图就绪，避免该帧空底黑闪。 */
private fun loadTo(
    img: ZoomableImageView,
    shared: Shared,
    page: Int,
    isCurrent: () -> Boolean
) {
    img.colorFilter = shared.filter()?.toColorFilter()
    shared.visibleImage = img
    val slot = page
    shared.scope.launch {
        val bmp = withContext(Dispatchers.IO) {
            shared.pageImage?.invoke(slot) ?: shared.source.loadFull(slot)
        }
        if (bmp != null && isCurrent()) img.setImageBitmap(bmp)
    }
}

/** 分页适配器（单页，横/竖共用）。 */
class ReaderPageAdapter(
    private val source: ReaderPageSource,
    filter: () -> ReaderColorFilter?,
    onInteraction: () -> Unit,
    onTap: (Float, Float) -> Unit
) : RecyclerView.Adapter<ReaderPageAdapter.VH>() {

    private val shared = Shared(source).apply {
        this.filter = filter
        this.onInteraction = onInteraction
        this.onTap = onTap
    }

    val visibleImage: ZoomableImageView?
        get() = shared.visibleImage

    /** 注入页图提供者（译文/原文渲染图，null=原图）。 */
    fun setPageImageProvider(provider: (Int) -> Bitmap?) {
        shared.pageImage = provider
    }

    /** 实时预览：直接给当前可见页上滤镜。 */
    fun applyLiveColor(f: ReaderColorFilter?) {
        shared.visibleImage?.colorFilter = f?.toColorFilter()
    }

    class VH(val binding: ItemMangaReaderPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMangaReaderPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = source.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        resetItemTransform(holder.itemView)
        // 记页面索引：供高级/仿真动画按索引判定（与左右/上下物理方向无关 → RTL/竖排通用）
        holder.itemView.tag = position
        bindImageCommon(holder.binding.zoomableImage, shared, position) {
            holder.adapterPosition == position
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        shared.scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}

/** 横屏双页适配器：每个 item 是一对页（左=2i，右=2i+1）。 */
class DoublePageAdapter(
    private val source: ReaderPageSource,
    filter: () -> ReaderColorFilter?,
    onInteraction: () -> Unit,
    onTap: (Float, Float) -> Unit
) : RecyclerView.Adapter<DoublePageAdapter.VH>() {

    private val shared = Shared(source).apply {
        this.filter = filter
        this.onInteraction = onInteraction
        this.onTap = onTap
    }

    /** 展开数量 = ceil(页数/2)。 */
    override fun getItemCount(): Int = (source.size + 1) / 2

    fun applyLiveColor(f: ReaderColorFilter?) {
        shared.visibleImage?.colorFilter = f?.toColorFilter()
    }

    /** 最近绑定的页面图片（供阅读器内嵌翻译直接 setImageBitmap）。双页时近似取最近一张。 */
    val visibleImage: ZoomableImageView?
        get() = shared.visibleImage

    /** 注入页图提供者（译文/原文渲染图，null=原图）。 */
    fun setPageImageProvider(provider: (Int) -> Bitmap?) {
        shared.pageImage = provider
    }

    class VH(val binding: ItemReaderDoublePageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemReaderDoublePageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        resetItemTransform(holder.itemView)
        val left = position * 2
        val right = left + 1
        bindImageCommon(holder.binding.zoomableLeft, shared, left) {
            holder.adapterPosition == position
        }
        if (right < source.size) {
            holder.binding.zoomableRight.visibility = View.VISIBLE
            bindImageCommon(holder.binding.zoomableRight, shared, right) {
                holder.adapterPosition == position
            }
        } else {
            holder.binding.zoomableRight.setImageBitmap(null)
            holder.binding.zoomableRight.visibility = View.INVISIBLE
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        shared.scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}

/** Webtoon 连续滚动适配器（竖列表，懒加载，图片按宽度铺满）。 */
class WebtoonAdapter(
    private val source: ReaderPageSource,
    filter: () -> ReaderColorFilter?
) : RecyclerView.Adapter<WebtoonAdapter.VH>() {

    private val shared = Shared(source).apply { this.filter = filter }

    class VH(val binding: ItemWebtoonPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWebtoonPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = source.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val img = holder.binding.webtoonImage
        val loading = holder.binding.webtoonLoading
        img.setImageDrawable(null)
        img.colorFilter = shared.filter()?.toColorFilter()
        loading.visibility = View.VISIBLE
        // 按实际/屏幕宽度降采样解码（大漫画防卡死）；宽在首次绑定可能为 0，回退屏幕宽度
        val targetW = (holder.binding.webtoonImage.width.takeIf { it > 0 }
            ?: holder.itemView.context.resources.displayMetrics.widthPixels).coerceAtLeast(1)
        shared.scope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { shared.source.loadWebtoon(position, targetW) } }.getOrNull()
            // 校验 holder 仍绑定同一 position，避免复用 holder 残留旧页图/转圈状态
            if (holder.adapterPosition == position) {
                if (bmp != null) img.setImageBitmap(bmp)
                loading.visibility = View.GONE
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        shared.scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}