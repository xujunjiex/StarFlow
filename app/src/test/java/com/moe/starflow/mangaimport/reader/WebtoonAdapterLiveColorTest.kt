package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Webtoon（连续滑动）实时滤镜守卫。
 *
 * 真实缺陷（本批引入、已修）：`applyLiveColor` 里把 `webtoon_image` 当 `ZoomableImageView` 取，
 * 而 `item_webtoon_page.xml` 里它是**普通 ImageView** → 「连续滑动」模式下第一次拖调色滑块
 * （或点重置）就 ClassCastException 崩掉阅读器。这条路径此前零测试，所以没被拦住。
 *
 * 这里把真实的 item 布局塞进 RecyclerView 当子视图，直接调 `applyLiveColor`：
 * 用错类型会在这里红，而不是在用户手机上崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebtoonAdapterLiveColorTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun adapterWithOneChild(): Pair<WebtoonAdapter, ImageView> {
        val rv = RecyclerView(ctx)
        // ⚠️ 必须给 LayoutManager：item 布局的根是 RecyclerView 参数相关，没有 LM 时 inflate 直接抛
        rv.layoutManager = LinearLayoutManager(ctx)
        // ReaderPageSource 只需要 size/取图，本测试不触发加载（不调 onBindViewHolder）
        val adapter = WebtoonAdapter(ReaderPageSource(isArchive = false, localRoot = "")) { null }
        rv.adapter = adapter
        // onAttachedToRecyclerView 才会记下 attachedList —— 真实路径由 RecyclerView 自己触发，
        // 这里手动调一次（Robolectric 不会真的走 attach 流程）
        adapter.onAttachedToRecyclerView(rv)

        val item = LayoutInflater.from(ctx).inflate(R.layout.item_webtoon_page, rv, false)
        val image = item.findViewById<ImageView>(R.id.webtoon_image)
        rv.addView(item)
        return adapter to image
    }

    @Test
    fun applyLiveColor_setsFilterOnAttachedPages_withoutCrashing() {
        val (adapter, image) = adapterWithOneChild()
        val filter = ReaderColorFilter(
            brightness = 0.3f, contrast = 0f, isInverted = false, isGrayscale = false, isBookBackground = false
        )

        adapter.applyLiveColor(filter)

        assertNotNull("已上屏的 Webtoon 页必须立刻上滤镜", image.colorFilter)
    }

    @Test
    fun applyLiveColor_nullClearsFilter() {
        val (adapter, image) = adapterWithOneChild()
        adapter.applyLiveColor(ReaderColorFilter.EMPTY)
        assertNotNull(image.colorFilter)

        adapter.applyLiveColor(null)
        assertNull("传 null 应清掉滤镜（回到未处理）", image.colorFilter)
    }

    /** 滤镜必须是用户那一份（而不是随手造一个空的），否则「看起来有反应、其实没变」。 */
    @Test
    fun applyLiveColor_usesRequestedColorMatrix() {
        val (adapter, image) = adapterWithOneChild()
        val filter = ReaderColorFilter(
            brightness = 0f, contrast = 0.5f, isInverted = false, isGrayscale = false, isBookBackground = false
        )

        adapter.applyLiveColor(filter)

        val actual = ColorMatrix()
        (image.colorFilter as ColorMatrixColorFilter).getColorMatrix(actual)
        val expected = filter.toColorMatrix().array
        for (i in expected.indices) {
            assertEquals("矩阵第 $i 项", expected[i], actual.array[i], 0.0001f)
        }
    }

    /** 解绑后不该再往一个已经离开屏幕的列表上写（避免持有已回收的视图）。 */
    @Test
    fun afterDetach_applyLiveColorIsNoOp() {
        val (adapter, image) = adapterWithOneChild()
        adapter.onDetachedFromRecyclerView(RecyclerView(ctx))

        adapter.applyLiveColor(ReaderColorFilter.EMPTY)

        assertNull("解绑后不再写入", image.colorFilter)
    }
}
