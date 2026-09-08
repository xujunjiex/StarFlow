package com.moe.starflow.mangaimport.reader

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
}

private fun bindImageCommon(img: ZoomableImageView, shared: Shared, page: Int) {
    if (img.onInteraction == null) img.onInteraction = shared.onInteraction
    if (img.onSingleTapConfirmed == null) img.onSingleTapConfirmed = shared.onTap
    loadTo(img, shared, page)
}

/** 在一个 ZoomableImageView 上：清图、上滤镜、异步载全图。shared.visibleImage 供实时预览。 */
private fun loadTo(
    img: ZoomableImageView,
    shared: Shared,
    page: Int
) {
    img.setImageBitmap(null)
    img.colorFilter = shared.filter()?.toColorFilter()
    shared.visibleImage = img
    val slot = page
    shared.scope.launch {
        val bmp = withContext(Dispatchers.IO) { shared.source.loadFull(slot) }
        if (bmp != null) img.setImageBitmap(bmp)
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

    /** 实时预览：直接给当前可见页上滤镜。 */
    fun applyLiveColor(f: ReaderColorFilter?) {
        shared.visibleImage?.colorFilter = f?.toColorFilter()
    }

    class VH(val binding: ItemMangaReaderPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMangaReaderPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = source.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindImageCommon(holder.binding.zoomableImage, shared, position)
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

    class VH(val binding: ItemReaderDoublePageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemReaderDoublePageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val left = position * 2
        val right = left + 1
        bindImageCommon(holder.binding.zoomableLeft, shared, left)
        if (right < source.size) {
            holder.binding.zoomableRight.visibility = View.VISIBLE
            bindImageCommon(holder.binding.zoomableRight, shared, right)
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
        img.setImageDrawable(null)
        img.colorFilter = shared.filter()?.toColorFilter()
        shared.scope.launch {
            val bmp = withContext(Dispatchers.IO) { shared.source.loadFull(position) }
            if (bmp != null) img.setImageBitmap(bmp)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        shared.scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}