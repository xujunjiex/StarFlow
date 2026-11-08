package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 连续滚动上屏图的**解码宽度**守卫。
 *
 * 真实缺陷（用户报「连续滚动默认全屏画质非常模糊」）：`loadWebtoon` 过去只按 2 的幂算
 * `inSampleSize`，条件只有「解码宽 ≤ 屏宽」。于是宽 1600 的页在 1220 宽的屏幕上被解成
 * **800 宽**、再被 ImageView 拉回 1220 —— 1.5 倍插值放大，图源明明比屏幕清晰。
 *
 * 这里用真实 PNG 走一遍 `ReaderPageSource.loadWebtoon`，钉住三条：
 * 1. 输出宽 = 目标宽（1600→1220），不是 800；
 * 2. 缓存按**宽度**区分（换宽度必须重新解码，否则旋转/分屏后一直用旧图）；
 * 3. 不会放大到超过图源宽度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebtoonDecodeQualityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var source: ReaderPageSource

    @Before
    fun setUp() {
        val root = temp.newFolder("pages")
        val bitmap = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        root.resolve("001.png").writeBytes(png)
        source = ReaderPageSource(isArchive = false, localRoot = root.absolutePath)
    }

    @After
    fun tearDown() {
        if (::source.isInitialized) source.close()
    }

    @Test
    fun `decodesExactlyAtTheTargetWidth`() {
        val bitmap = source.loadWebtoon(0, 1220)
        assertNotNull("页图必须能解出来", bitmap)
        assertEquals("1600 宽的页在 1220 屏幕上必须解成 1220 宽（旧实现是 800）", 1220, bitmap!!.width)
        assertEquals("高度按原宽高比", 1830, bitmap.height)
    }

    @Test
    fun `cacheIsKeyedByWidth`() {
        val wide = source.loadWebtoon(0, 1220)
        val narrow = source.loadWebtoon(0, 800)
        assertEquals(1220, wide!!.width)
        assertEquals("换宽度必须按新宽度重新解码（旧实现按页号缓存会返回 1220 那张）", 800, narrow!!.width)
        assertNotSame("两张不同宽度的图不能是同一个对象", wide, narrow)
    }

    @Test
    fun `neverUpscalesBeyondTheSource`() {
        // 目标宽比图源还大时只按图源尺寸出图 —— 不编造像素
        val bitmap = source.loadWebtoon(0, 4000)
        assertEquals(1600, bitmap!!.width)
        assertEquals(2400, bitmap.height)
    }
}
