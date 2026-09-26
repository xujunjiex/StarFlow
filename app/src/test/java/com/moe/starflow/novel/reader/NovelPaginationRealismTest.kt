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

    /**
     * ⚠️ 每本书必须给**不同**的 `id`/`addedAt`：`NovelChapterRepository` 的缓存键是
     * `"${id}:${addedAt}"`，全都填 1 会让后面的样本直接命中前一个样本的缓存 ——
     * 那一刻起，「epub 也能分页」测的其实是 `long-en.txt`（页数一模一样就是信号）。
     */
    private fun novelOf(name: String, format: NovelFormat) = ImportedNovel(
        id = name.hashCode().toLong(),
        title = name,
        localRoot = fixture(name).absolutePath,
        format = format,
        chapterCount = 0, // 由 chaptersOf 现取，避免手写数字与样本脱节
        addedAt = name.length.toLong(),
    )

    /**
     * 长样本清单：格式 → 文件名。每一章的每一页都要经得起检查。
     *
     * ⚠️ 正文样本必须是**被翻译的那一侧语言**（英文/日文/韩文，见 [语言样本守卫]）：
     * 拿中文样本测「翻译成中文」等于什么都没测 —— 模型原样返回就能过。
     * 后两条 epub/zip 样本守的是**格式**路径（那两条链路的样本仍是中文，语言覆盖由 TXT 三条负责）。
     */
    private val longFixtures = listOf(
        NovelFormat.TXT to "long-en.txt",
        NovelFormat.TXT to "long-ja.txt",
        NovelFormat.TXT to "long-ko.txt",
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
        val book = novelOf("long-en.txt", NovelFormat.TXT)
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
     * **语言样本守卫**：正文样本必须是被翻译的那一侧语言，不能是中文。
     *
     * ⚠️ 这条守的是「测试有效性」而不是代码：小说要做的是外文 → 中文，中文样本既测不出
     * 断行差异（中文逐字换行、英文按词换行、日文有禁则），也测不出翻译是否真的发生。
     */
    @Test
    fun `语言样本守卫：长样本是英日韩文而不是中文`() {
        val samples = mapOf(
            "long-en.txt" to { s: String -> s.count { it in 'a'..'z' || it in 'A'..'Z' } },
            "long-ja.txt" to { s: String -> s.count { it in '぀'..'ヿ' } },
            "long-ko.txt" to { s: String -> s.count { it in '가'..'힣' } },
        )
        for ((name, count) in samples) {
            val text = fixture(name).readText()
            // 汉字占比：日文里本来就有汉字，但必须混着假名；纯中文样本要卡住
            val han = text.count { it in '一'..'鿿' }
            val ratio = count(text).toDouble() / text.length
            println("LANG $name ratio=${"%.2f".format(ratio)} han=${"%.2f".format(han.toDouble() / text.length)}")
            assertTrue(
                "$name 的特征字符占比只有 ${"%.2f".format(ratio)} —— 样本不像${name}对应的语言",
                ratio > 0.3,
            )
            assertTrue(
                "$name 的汉字占比 ${"%.2f".format(han.toDouble() / text.length)} 过高 —— 又变回中文样本了",
                han.toDouble() / text.length < 0.5,
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
        // 文本样本按字节卡（UTF-8 下就是长度）
        for (name in listOf("long-en.txt", "long-ja.txt", "long-ko.txt", "single-paragraph.txt")) {
            val size = fixture(name).length()
            assertTrue("$name 只有 $size 字节，太短了：请用 tools/gen_novel_fixtures.py --repo 重新生成", size > 20_000)
        }
        // ⚠️ 打包样本（zip/epub）**不能套用同一个字节阈值**：正文被 deflate 压过，
        // 高重复的样本能压到十分之一以下 —— 字节数在这里不是长度的度量。
        // 它们的长度由上面那条「每一章都要能分出多页」保证（那是在解压后的正文上跑的）。
        for (name in listOf("long.epub", "long-html-pack.zip")) {
            assertTrue("$name 缺失或为空", fixture(name).length() > 2_000)
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

    /**
     * **每页渲染出来的高度不得超过正文框** —— 守「文字被画到页面外面」。
     *
     * 这里**照抄渲染的算法**：`NovelPageView` 是按 segment 的行区间去量段落自身 layout 的
     * `getLineTop/getLineBottom` 并累加，所以这条测试量的就是真正会被画出来的高度。
     * 逐 segment 的 `[lineStart, lineEnd)` 必须落在该段 layout 的行数内（越界会被兜底截断，
     * 那样「分页记账」与「实际绘制」就对不上了），累加结果也不得越过正文框。
     *
     * ⚠️ **这条证明不了字体度量那一半**：Robolectric 的文本引擎是桩（不换行、行高恒为
     * `字号 × 行距倍率`），所以「真机行高是字号的 ~1.15~1.5 倍」这件事在这里量不出来。
     * 它守的是**记账与几何的一致性**（行区间合法、段间距只补在段间、总量不超框）；
     * 真机上的字体行高对不对，只能看现场 —— 越界时 `NovelPageView` 会打一条 `PAGE_OVERFLOW`。
     */
    @Test
    fun `每页渲染高度不得超过正文框`() {
        val style = NovelTextStyle(
            fontSizePx = 40f,
            lineSpacingMultiplier = 1.5f,
            paragraphSpacingPx = 24f,
            paddingPx = 20f,
            topPaddingPx = 100f,
            bottomPaddingPx = 120f,
            keepParagraphsWhole = true,
        )
        val paras = (0 until 80).map { i ->
            com.moe.starflow.novel.translate.NovelParagraph(
                i, com.moe.starflow.novel.translate.NovelParagraphType.TEXT, "字".repeat(12),
            )
        }
        val pages = NovelPaginator.paginate(paras, style, 1000, 2000)
        assertTrue("至少要分出多页", pages.size > 1)

        val byIndex = paras.associateBy { it.index }
        val box = style.contentHeightPx(2000)

        for ((i, page) in pages.withIndex()) {
            var rendered = 0f
            for ((idx, seg) in page.segments.withIndex()) {
                val text = byIndex.getValue(seg.paraIndex).originalText
                val layout = NovelTextRenderer.build(text, style, style.contentWidthPx(1000))
                assertTrue(
                    "第 ${i + 1} 页 segment 行区间越界：lineEnd=${seg.lineEnd} > 行数 ${layout.lineCount}",
                    seg.lineStart in 0..layout.lineCount && seg.lineEnd in seg.lineStart..layout.lineCount,
                )
                if (seg.lineEnd > seg.lineStart) {
                    rendered += layout.getLineBottom(seg.lineEnd - 1) - layout.getLineTop(seg.lineStart)
                }
                // 与渲染一致：段间距只补在段与段之间
                if (idx != page.segments.lastIndex) rendered += style.paragraphSpacingPx
            }
            assertTrue(
                "第 ${i + 1} 页渲染高度 $rendered 超出正文框 $box —— 会被画到页面外面",
                rendered <= box + 0.5f,
            )
        }
    }
}
