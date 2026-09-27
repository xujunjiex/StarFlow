package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 段级排版缓存的守卫（「译文每来一批就重排整章」的性能修复）。
 *
 * 这一块**必须**测：缓存命中错就会把**另一段的排版**画在这一段上 ——
 * 那是静默错位，不报错、只是"看着不对"，比不缓存难查得多。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelLayoutCacheTest {

    private val style = NovelTextStyle(40f, 1.2f, 10f, 20f)
    private val text = "他抬起头，看了看窗外的天色。"

    private fun Counter() = object { var builds = 0 }

    @Test
    fun `同样输入复用同一份排版`() {
        val cache = NovelLayoutCache()
        val c = Counter()

        val a = cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }
        val b = cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }

        assertSame("同一段同一份文本必须复用", a, b)
        assertEquals(1, c.builds)
    }

    /** 文本变了必须重排：复用旧排版等于把上一段的文字画到这一段上。 */
    @Test
    fun `文本变化后不复用`() {
        val cache = NovelLayoutCache()
        val c = Counter()

        val a = cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }
        val b = cache.layout(0, "$text 多了一句", style, 300) {
            c.builds++; NovelTextRenderer.build("$text 多了一句", style, 300)
        }

        assertNotSame(a, b)
        assertEquals(2, c.builds)
    }

    /** 排版参数变了必须重排：改字号后复用旧 layout 就是「改了没反应」。 */
    @Test
    fun `排版参数变化后不复用`() {
        val cache = NovelLayoutCache()
        val bigger = style.copy(fontSizePx = 60f)
        val c = Counter()

        val a = cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }
        val b = cache.layout(0, text, bigger, 300) { c.builds++; NovelTextRenderer.build(text, bigger, 300) }

        assertNotSame(a, b)
        assertEquals(2, c.builds)
    }

    /** 宽度变了必须重排（横竖屏 / 改页宽，换行位置整段都不同）。 */
    @Test
    fun `宽度变化后不复用`() {
        val cache = NovelLayoutCache()
        val c = Counter()

        val a = cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }
        val b = cache.layout(0, text, style, 500) { c.builds++; NovelTextRenderer.build(text, style, 500) }

        assertNotSame(a, b)
        assertEquals(2, c.builds)
    }

    @Test
    fun `clear 之后必须重排`() {
        val cache = NovelLayoutCache()
        val c = Counter()

        cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }
        cache.clear()
        cache.layout(0, text, style, 300) { c.builds++; NovelTextRenderer.build(text, style, 300) }

        assertEquals(2, c.builds)
    }

    /**
     * **分页真的把排版交给了缓存**。
     *
     * 上面几条只测缓存本身；这一条证明 `NovelPaginator.paginate` 确实把 `layoutCache` 用上了 ——
     * 不然缓存永远是空的，整章重排的性能修复等于没接线，而单测不会红。
     */
    @Test
    fun `paginate 会把整章的排版填进缓存`() {
        val cache = NovelLayoutCache()
        val paras = listOf(NovelParagraph(0, NovelParagraphType.TEXT, text))
        val width = 320

        NovelPaginator.paginate(paras, style, width, 640, null, cache)

        val c = Counter()
        // 命中缓存的调用不会执行 builder；执行了说明 paginate 没往里写
        cache.layout(0, text, style, style.contentWidthPx(width)) {
            c.builds++
            NovelTextRenderer.build(text, style, style.contentWidthPx(width))
        }
        assertEquals("paginate 必须把每段的排版存进缓存，实际重建了 ${c.builds} 次", 0, c.builds)
    }
}
