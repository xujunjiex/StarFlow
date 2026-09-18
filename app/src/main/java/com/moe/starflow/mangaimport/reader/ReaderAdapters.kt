package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.databinding.ItemMangaReaderPageBinding
import com.moe.starflow.databinding.ItemWebtoonPageBinding
import com.moe.starflow.ui.viewer.ZoomableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
    /** 当前阅读页（由阅读器注入）。只有绑定到这一页时才更新 [visibleImage]，见 loadTo。 */
    var currentPage: () -> Int = { 0 }
    /** 页图提供者：返回该页「应显示」的图（译文/原文渲染图）或 null（显示原图）。由阅读器注入。
     *  ⚠️ 必须在 IO 线程安全、可同步返回（内部是 LruCache.get）。重绑/复用页时优先用它，防止把译图覆盖回原图。 */
    @Volatile
    var pageImage: ((Int) -> Bitmap?)? = null
}

private fun bindImageCommon(img: ZoomableImageView, shared: Shared, page: Int, isCurrent: () -> Boolean) {
    if (img.onInteraction == null) img.onInteraction = shared.onInteraction
    if (img.onSingleTapConfirmed == null) img.onSingleTapConfirmed = shared.onTap
    // ⚠️ 阅读器把屏幕划分成翻页区/显隐区/菜单区，这些操作与图片缩放互不冲突：
    // 放大的页面上点中间必须仍能显隐 UI、点左右仍能翻页。默认的门控（放大即不派发单击）
    // 会让用户放大后"什么都点不动"，必须双击缩回原尺寸才恢复。
    img.dispatchTapWhenZoomed = true
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
 *  绑定新页时若复用池残留别页图 → 立即清掉，杜绝滑动翻页时「旧页颜色/内容闪一瞬」；
 *  同页重绑（applyPageVisual 的 notifyItemChanged）保留既有图，避免当前页空一帧（对齐 Webtoon 绑定即清）。 */
private fun loadTo(
    img: ZoomableImageView,
    shared: Shared,
    page: Int,
    isCurrent: () -> Boolean
) {
    img.colorFilter = shared.filter()?.toColorFilter()
    // ⚠️ 只有绑定「当前页」时才能记 visibleImage：offscreenPageLimit=1 会顺手绑定邻页，
    // 无条件赋值会让调色面板的实时预览落到邻页的 ImageView 上 —— 拖亮度/对比度时
    // 用户盯着的那页毫无反应，换个页面回来才生效。
    if (page == shared.currentPage()) shared.visibleImage = img
    val slot = page
    if ((img.tag as? Int) != slot) {
        img.tag = slot
        img.setImageBitmap(null)
    }
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
    onTap: (Float, Float) -> Unit,
    /** 当前阅读页提供者：实时滤镜只作用在它对应的 ImageView 上（邻页会被预绑定） */
    currentPage: () -> Int = { 0 }
) : RecyclerView.Adapter<ReaderPageAdapter.VH>() {

    private val shared = Shared(source).apply {
        this.filter = filter
        this.onInteraction = onInteraction
        this.onTap = onTap
        this.currentPage = currentPage
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

/** Webtoon 连续滚动适配器（竖列表，懒加载，图片按宽度铺满）。 */
class WebtoonAdapter(
    private val source: ReaderPageSource,
    filter: () -> ReaderColorFilter?
) : RecyclerView.Adapter<WebtoonAdapter.VH>() {

    private val shared = Shared(source).apply { this.filter = filter }

    class VH(val binding: ItemWebtoonPageBinding) : RecyclerView.ViewHolder(binding.root) {
        /** 本次绑定的取图任务。重绑前必须取消，见 [onBindViewHolder]。 */
        var loadJob: Job? = null

        /** 当前 holder 正在显示哪一页（-1 = 还没绑过）。用于区分「同页重绑」与「复用去显示别页」。 */
        var boundPage = -1
    }

    /**
     * 注入「页图提供者」：已翻译页返回**已经渲染好的译图**（无则 null → 回落未缩放原图）。
     *
     * ⚠️ Webtoon 原本直连 `source.loadWebtoon`，绕过了译图层 —— 即使该页已翻译也永远显示原图。
     * 提供者只读缓存、不做渲染；渲染由 `ReaderTranslationController.prewarmWebtoon` 在后台
     * 按当前位置上下几页限范围预热。
     */
    fun setPageImageProvider(provider: (Int) -> Bitmap?) {
        shared.pageImage = provider
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWebtoonPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = source.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val img = holder.binding.webtoonImage
        val loading = holder.binding.webtoonLoading
        // 按实际/屏幕宽度降采样解码（大漫画防卡死）；宽在首次绑定可能为 0，回退屏幕宽度
        val targetW = (img.width.takeIf { it > 0 }
            ?: holder.itemView.context.resources.displayMetrics.widthPixels).coerceAtLeast(1)

        // ⚠️ 必须先把行高按【页图原始宽高比】钉死，再清图/加载。
        // ImageView 是 wrap_content + adjustViewBounds：`setImageDrawable(null)` 会让行高塌成 0，
        // 图片到达后再撑开 → 列表连锁重排，用户看到的就是「页面一直跳、旧图清不掉、新图不进来」。
        // 原图与译图的宽高比一致（译图渲在按屏宽采样出来的原图上），所以钉一次就够、永久有效。
        val ow = source.originalWidth(position)
        val oh = source.originalHeight(position)
        val pinned = if (ow > 0 && oh > 0) {
            (targetW.toLong() * oh / ow).toInt().coerceIn(1, MAX_WEBTOON_ITEM_HEIGHT)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val lp = img.layoutParams
        if (lp.height != pinned) {
            lp.height = pinned
            img.layoutParams = lp
        }

        img.colorFilter = shared.filter()?.toColorFilter()
        // **同一个 holder 重新绑定同一页**（切显示态 / 预热刷新）→ 保留当前图，等新图到了原地替换：
        // 既不闪一帧空白，也不让列表跳动。只有 holder 被复用去显示**别的页**时才清图 + 转圈。
        // ⚠️ 别改回无条件 `setImageDrawable(null)`：每次重绑都会闪白。
        if (holder.boundPage != position) {
            holder.boundPage = position
            img.setImageDrawable(null)
            loading.visibility = View.VISIBLE
        }
        // ⚠️ 重绑前先取消上一次取图。切换「原图 ↔ 译文」时本页会被连续重绑两次：
        // 第一次（provider 未命中）去慢速解码原图，第二次（缓存已热）秒回译图；
        // 不取消的话慢的那次最后落地，把译图覆盖回原图 —— 用户看到的就是"点了没反应/
        // 状态自己弹回去"。同理，切到原图时在途的译图取回后也会盖住新绑定的原图。
        holder.loadJob?.cancel()
        holder.loadJob = shared.scope.launch {
            // 已翻译页优先用渲染好的译图；否则回落到按宽度降采样的原图
            val bmp = runCatching {
                withContext(Dispatchers.IO) {
                    shared.pageImage?.invoke(position) ?: shared.source.loadWebtoon(position, targetW)
                }
            }.getOrNull()
            if (!isActive) return@launch
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

    private companion object {
        /** 单页最大行高：防宽高比读错（或异常页）时把行高算成天文数字撑爆列表。 */
        const val MAX_WEBTOON_ITEM_HEIGHT = 20_000
    }
}