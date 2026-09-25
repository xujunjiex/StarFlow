package com.moe.starflow.novel.reader

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 分页的**现实性**守卫：真机上「能不能翻页」。
 *
 * ### 为什么要单独有这么一条
 * 早期的样本每章只有 100~250 字节（约 2 行字），而真机一屏（1220×2712、16sp）能放 ~39 行 ——
 * 于是**每一章都恒为 1 页**，翻页、翻页动画、阅读模式全都无从测试，
 * 现场表现就是「怎么滑都不动、换章也只是顶部两行字变了、像卡死」。
 * 代码没错，是**样本短到根本翻不动**。
 *
 * 这条测试把两件事钉死：
 * 1. 长样本的**每一章**都必须能分出多页（否则样本又退化了）
 * 2. **相邻页的内容必须不同**（否则就是"所有页画的是同一段文字"那类渲染 bug）
 *
 * ⚠️ 这里显式给 `style` 与视口尺寸，不用设备默认值：Robolectric 的 density 是 mdpi，
 * 用它算出来的「一页能放 111 段」在真机上完全不成立（真机 ~39 行）。尺寸口径必须与真机一致，
 * 断言才有意义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPaginationRealismTest {

    private companion object {
        /** 真机视口（`wm size` 实测 1220×2712）。 */
        const val DEVICE_W = 1220
        const val DEVICE_H = 2712

        /** 默认字号 16sp × scaledDensity(2.75) ≈ 44px；行距 1.5×；段间距/边距 20dp × 2.75。 */
        val DEVICE_STYLE = NovelTextStyle(
            fontSizePx = 44f,
            lineSpacingMultiplier = 1.5f,
            paragraphSpacingPx = 55f,
            paddingPx = 55f,
        )
    }

    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("novel-fixtures/$name")
            ?: error("样本缺失: $name")
        return File(url.toURI())
    }

    private fun novelOf(name: String, format: NovelFormat) = ImportedNovel(
        id = 1,
        title = name,
        localRoot = fixture(name).absolutePath,
        format = format,
        chapterCount = 0, // 由 chaptersOf 现取，避免手写数字与样本脱节
        addedAt = 1L,
    )

    /** 长样本清单：格式 → 文件名。每一章的每一页都要经得起检查。 */
    private val longFixtures = listOf(
        NovelFormat.TXT to "long-utf8.txt",
        NovelFormat.TXT to "long-gbk.txt",
        NovelFormat.EPUB to "long.epub",
        NovelFormat.ZIP_HTML to "long-html-pack.zip",
    )

    @Test
    fun `长样本的每一章都必须能分出多页`() = runBlocking {
        val repo = NovelChapterRepository()
        for ((format, name) in longFixtures) {
            val book = novelOf(name, format)
            val chapters = repo.chaptersOf(book)
            assertTrue("$name 没解析出章节", chapters.isNotEmpty())
            for (i in chapters.indices) {
                val c = repo.load(book, i, emptyMap(), NovelDisplayMode.TRANSLATED, DEVICE_STYLE, DEVICE_W, DEVICE_H)
                println("PAGING $name ch=${i + 1}/${chapters.size} paras=${c.paragraphs.size} pages=${c.pages.size}")
                assertTrue(
                    "$name 第 ${i + 1} 章只有 ${c.pages.size} 页（段落 ${c.paragraphs.size}）——" +
                        "真机上这一章根本翻不动，样本又退化了",
                    c.pages.size > 1,
                )
            }
        }
    }

    /** 相邻页必须画不同的文字：这是「进度条在走、画面不变」那类渲染 bug 的守门员。 */
    @Test
    fun `相邻页的第一段显示文本必须不同`() = runBlocking {
        val repo = NovelChapterRepository()
        val book = novelOf("long-utf8.txt", NovelFormat.TXT)
        val c = repo.load(book, 0, emptyMap(), NovelDisplayMode.TRANSLATED, DEVICE_STYLE, DEVICE_W, DEVICE_H)
        assertTrue("页数太少，测不出翻页：${c.pages.size}", c.pages.size > 3)

        fun headOf(pageIndex: Int): String {
            val seg = c.pages[pageIndex].segments.first()
            return c.displayOf(seg.paraIndex).substring(seg.charStart, seg.charEnd).take(24)
        }
        for (i in 0 until c.pages.size - 1) {
            assertNotEquals(
                "第 ${i + 1} 页与第 ${i + 2} 页的首段文字一样 —— 翻页时画面不会变",
                headOf(i), headOf(i + 1),
            )
        }
    }

    /**
     * 样本自身的体量下限。
     *
     * 上面两条断言都建立在「样本足够长」之上；这条直接守住文件体量，
     * 免得以后有人换样本时又换回每章几百字节的小文件，然后上面的断言**悄悄变得没有意义**
     * （页数 > 1 在长样本上才是一场真考验）。
     */
    @Test
    fun `长样本文件本身的体量必须够大`() {
        for (name in listOf("long-utf8.txt", "long-gbk.txt", "long.epub", "long-html-pack.zip", "single-paragraph.txt")) {
            val size = fixture(name).length()
            assertTrue("$name 只有 $size 字节，太短了：请用 gen_novel_fixtures.py --repo 重新生成", size > 20_000)
        }
    }

    /**
     * 单段超长（整本没有空行）也要能读出来。
     *
     * 这类文件在真机上**靠换行分页**（一段跨越几百行），所以单测里 StaticLayout 不换行时它只有 1 页 ——
     * 这条不测页数，只守「分章兜底 + 段落切分不丢字」。
     */
    @Test
    fun `无空行的单段超长文本不丢字`() = runBlocking {
        val repo = NovelChapterRepository()
        val book = novelOf("single-paragraph.txt", NovelFormat.TXT)
        val c = repo.load(book, 0, emptyMap(), NovelDisplayMode.TRANSLATED, DEVICE_STYLE, DEVICE_W, DEVICE_H)
        assertTrue("至少要有一段", c.paragraphs.isNotEmpty())
        assertTrue(
            "整个文件没有空行，本章应当只有一段（实际 ${c.paragraphs.size} 段）",
            c.paragraphs.size == 1,
        )
        assertTrue(
            "本章段落总字数太少（${c.paragraphs.sumOf { it.originalText.length }}），测不出「无空行」这条路径",
            c.paragraphs.sumOf { it.originalText.length } > 5_000,
        )
    }
}
