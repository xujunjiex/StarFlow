package com.moe.starflow.novel.reader

import android.util.LruCache
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.parser.NovelParsers
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一章的完整加载结果。
 *
 * @param paragraphs **原文**段落（译文查找、句子统计用）
 * @param displayTexts `paraIndex -> 当前要显示的完整文本`：原文 / 译文 / 「原文+译文」
 *   由 [NovelPageBilingual.displayText] 统一决定
 * @param pages 按 [displayTexts] 分出来的页。**字符区间是 [displayTexts] 里的下标**
 */
data class ChapterContent(
    val chapterIndex: Int,
    val title: String,
    val paragraphs: List<NovelParagraph>,
    val displayTexts: Map<Int, String>,
    val pages: List<NovelPage>,
) {
    val isEmpty: Boolean get() = paragraphs.isEmpty()

    /** 某段在本章当前该显示的完整文本。 */
    fun displayOf(paraIndex: Int): String = displayTexts[paraIndex].orEmpty()
}

/**
 * 章正文加载 + 分页缓存。
 *
 * ### 分页在「显示文本」上做，不在原文上做
 * 译文通常比原文长。若按原文分页、再拿译文去画，页面会溢出、最后一截被裁掉；
 * 双语模式更糟 —— 两串长度不同，用原文的字符区间去切译文直接错位。
 * 所以**分页与绘制共用 [ChapterContent.displayTexts]**，字符区间永远在同一个字符串上。
 *
 * ### 三级缓存，各有各的理由
 * - [chapterLists]：书的章节目录。目录解析要读文件（EPUB 读 OPF+NCX，TXT 要跑一遍分章
 *   正则），而它每次翻页都会被问到，不缓存等于每翻一页重解析整本书。
 * - [paragraphs]：章原文切分结果，与排版、译文都无关 —— 改字号或译文到达时不必重读文件。
 * - [content]：最终结果（含页表）。键里必须带**排版参数与显示文本版本**：只按章号缓存的话，
 *   改字号或译文到达后都会拿到旧页表，表现为「改了没反应」。
 *
 * 缓存都是**实例级**（不是 object），阅读器退出时 [evictAll] 即可整体释放。
 */
class NovelChapterRepository {


    private companion object {
        const val TAG = "NovelChapterRepository"

        /** 章内容缓存保留章数（当前章 + 前后各一章，外加切模式/改排版的余量）。 */
        const val MAX_CACHED_CHAPTERS = 6

        /** 目录缓存保留书本数。 */
        const val MAX_CACHED_BOOKS = 2
    }

    private val chapterLists = LruCache<String, List<NovelChapterMeta>>(MAX_CACHED_BOOKS)
    private val paragraphs = LruCache<String, List<NovelParagraph>>(MAX_CACHED_CHAPTERS)
    private val content = LruCache<String, ChapterContent>(MAX_CACHED_CHAPTERS)

    /** 书的身份键：书籍 id 会被复用，必须带 addedAt 指纹。 */
    private fun bookKey(book: ImportedNovel) = "${book.id}:${book.addedAt}"

    /**
     * 取书的章节目录（带缓存）。文件丢失/损坏时返回空表 —— 由调用方展示「文件丢失」提示，
     * 而不是在这里崩。
     */
    suspend fun chaptersOf(book: ImportedNovel): List<NovelChapterMeta> = withContext(Dispatchers.IO) {
        val key = bookKey(book)
        chapterLists.get(key)?.let { return@withContext it }
        val list = runCatching { NovelParsers.forFormat(book.format).parse(File(book.localRoot)).chapters }
            .onFailure { LogCollector.e(TAG, "解析目录失败: ${book.localRoot}", it) }
            .getOrDefault(emptyList())
        chapterLists.put(key, list)
        list
    }

    /** 读章原文段落（带缓存）。读不到时返回空表。 */
    suspend fun paragraphsOf(book: ImportedNovel, chapterIndex: Int): List<NovelParagraph> =
        withContext(Dispatchers.IO) {
            val meta = chaptersOf(book).getOrNull(chapterIndex) ?: return@withContext emptyList()
            val key = "${bookKey(book)}:$chapterIndex:${NovelParagraphSplitter.SPLIT_VERSION}"
            paragraphs.get(key) ?: run {
                val text = runCatching {
                    NovelParsers.forFormat(book.format).loadChapter(File(book.localRoot), meta.locator)
                }.onFailure {
                    LogCollector.e(TAG, "读章失败 ch=$chapterIndex locator=${meta.locator}", it)
                }.getOrDefault("")
                NovelParagraphSplitter.split(text).also { paragraphs.put(key, it) }
            }
        }

    /**
     * 加载一章并分页。
     *
     * @param translations 本章译文 `paraIndex -> 译文`；决定每段显示原文还是译文
     */
    suspend fun load(
        book: ImportedNovel,
        chapterIndex: Int,
        translations: Map<Int, String>,
        displayMode: NovelDisplayMode,
        style: NovelTextStyle,
        widthPx: Int,
        heightPx: Int,
    ): ChapterContent = withContext(Dispatchers.IO) {
        val title = chaptersOf(book).getOrNull(chapterIndex)?.title.orEmpty()
        val paras = paragraphsOf(book, chapterIndex)
        if (paras.isEmpty()) {
            return@withContext ChapterContent(chapterIndex, title, emptyList(), emptyMap(), emptyList())
        }

        // 显示文本：分页与绘制的唯一共同来源（见类注释）
        val displayParas = paras.map { p ->
            p.copy(originalText = NovelPageBilingual.displayText(p.originalText, translations[p.index], displayMode))
        }
        val displayTexts = displayParas.associate { it.index to it.originalText }

        val key = "${bookKey(book)}:$chapterIndex:${NovelParagraphSplitter.SPLIT_VERSION}:" +
            "${displayMode.name}:${revisionOf(translations)}:" +
            "${style.fontSizePx}:${style.lineSpacingMultiplier}:${style.paragraphSpacingPx}:" +
            "${style.paddingPx}:${style.topPaddingPx}:${style.bottomPaddingPx}:" +
            // ⚠️ 整段保护必须进缓存键：关掉自动排版后同一章会重排出**不同的页表**，
            // 不进键就会命中旧页表、开关看起来"没生效"
            "${style.keepParagraphsWhole}:$widthPx:$heightPx"

        content.get(key) ?: ChapterContent(
            chapterIndex = chapterIndex,
            title = title,
            paragraphs = paras,
            displayTexts = displayTexts,
            pages = NovelPaginator.paginate(displayParas, style, widthPx, heightPx),
        ).also { content.put(key, it) }
    }

    /**
     * 译文集的版本号（进缓存键）。
     *
     * 用「条数 + 每条的段号与长度」而不是 `hashCode()`：需要的是「内容变了键就变」，
     * 而 `Map.hashCode` 对调换顺序/等长替换可能给出同一个值 —— 那样会命中过期页表，
     * 正好复现「译文到达后页面没重排」的问题。遍历一遍几百个段很便宜。
     */
    private fun revisionOf(translations: Map<Int, String>): Int {
        var h = translations.size
        for ((k, v) in translations) h = h * 31 + k * 31 + v.length
        return h
    }

    /**
     * 段落号 → 页号（断点续读用）。
     *
     * ⚠️ 按**段落号**而不是页号或字符偏移定位：页号会随字号/行距/译文到达而变，
     * 而段落号是内容本身的属性，永远指同一个位置。
     *
     * 段落号超出本章内容时取**末页**：这发生在「上次读到的位置比现在的内容还靠后」
     * （换版本、缓存里的旧值），用户本来就在末尾附近，回到第一页更突兀。
     */
    fun pageOfParagraph(pages: List<NovelPage>, paraIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val idx = pages.indexOfFirst { page -> page.segments.any { it.paraIndex >= paraIndex } }
        return if (idx >= 0) idx else pages.lastIndex
    }

    fun evictAll() {
        chapterLists.evictAll()
        paragraphs.evictAll()
        content.evictAll()
    }
}
