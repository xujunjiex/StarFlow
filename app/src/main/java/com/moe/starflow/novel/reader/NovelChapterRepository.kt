package com.moe.starflow.novel.reader

import android.util.LruCache
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.parser.NovelParsers
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.NovelParagraphType
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
    /**
     * 可翻译段号（TEXT 且非空白）。
     *
     * ⚠️ 算一次存着，别让调用方各自 `filter`：进度条判「这一页翻完没有」、浮层判
     * 「还有没有待翻的段」都要它，而 `refreshOverlay` 在滚动模式下是跟着滚动回调走的。
     */
    val translatableIndexes: Set<Int> = emptySet(),
) {
    /**
     * 非 SKIP 的段（滚动列表就是按这个顺序排的）。
     *
     * ⚠️ **算一次就存住**：`NovelScrollMapping.visibleParagraphs` 以前每次现 `filter`，
     * 而它被 `getItemCount()`（RecyclerView 每次布局都问）、每个 item 的绑定、
     * 以及滚动回调路径反复调用 —— 整本当一章的书（`TxtChapterSplitter.wholeBook`）
     * 段数上万，等于每帧新建一张表。
     *
     * ⚠️ 用 `by lazy` 而不是构造参数：构造参数要带默认值，而**手写的夹具**（单测里直接
     * `ChapterContent(...)` 的地方）会漏传它，于是"列表是空的"这种假象会静默传播；
     * 派生属性没有这个口子。它不参与 data class 的 equals/hashCode（那是对的）。
     */
    val visibleParas: List<NovelParagraph> by lazy {
        paragraphs.filter { it.type != NovelParagraphType.SKIP }
    }

    /**
     * 有没有东西可显示。
     *
     * ⚠️ 不能只看 `paragraphs.isEmpty()`：`NovelParagraphSplitter` 会把不足 4 字的段标成
     * SKIP、分页器又把 SKIP 整段滤掉 —— 于是「整章都是『……』」的章 `paragraphs` 非空、
     * `visibleParas` 为空 → 不显示「本章为空」，页适配器 0 项、**白屏且没有任何解释**，
     * 章行分母也恒为 0。判据要落在"有没有可显示的段"上。
     */
    val isEmpty: Boolean get() = paragraphs.isEmpty() || visibleParas.isEmpty()

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

    /** 段数缓存（与段落缓存分开：章行要段数要得很频繁，不值得每次取整个列表）。 */
    private val paraCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 字数缓存（同上：章行要显示字数，同样不值得每次取整个段落列表）。 */
    private val paraChars = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 段级排版缓存（整章重排时只重建显示文本真的变了的段）。
     *
     * 生命周期与仓库一致：**只在 `load` 里用**（跑在 IO 上），退出阅读器随 [evictAll] 释放。
     */
    private val layoutCache = NovelLayoutCache()

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

    /**
     * 某章**有多少段可翻译**（章行显示的"分母"）。
     *
     * ⚠️ 口径必须是**可翻译段**（TEXT 且非空白），不能是"所有段落"：短行（`……`）与图片
     * 按设计永远不翻译，把它们算进分母的话，含这类段的章**永远达不到**"已翻完"。
     * 同理，分母也不能用「数据库里这一章有几行」（那是按批惰性写的）—— 见
     * `NovelChapterTranslator.ensureChapterRows`。
     *
     * ⚠️ 未翻译的章在数据库里**没有任何行**，`chapterStats` 里也就没有它 —— 这正是
     * 「只有翻过的章显示段数」那个 bug 的根因。所以分母必须自己解析出来：懒解析 + 缓存，
     * 章行一次只显示几行，不必预先解析整本（上千章的书会卡死）。
     */
    suspend fun paragraphCountOf(book: ImportedNovel, chapterIndex: Int): Int {
        val key = "${bookKey(book)}:$chapterIndex:${NovelParagraphSplitter.SPLIT_VERSION}"
        paraCounts[key]?.let { return it }
        val n = paragraphsOf(book, chapterIndex).count { it.isTranslatable() }
        paraCounts[key] = n
        return n
    }

    /**
     * 某章**可翻译正文有多少字**（面板里「本章多少字」，用户拿它估翻译费用）。
     *
     * ⚠️ 口径必须与「分母」一致：只算**可翻译段**（TEXT 且非空白）的字符数 —— 那才是真正会
     * 发给翻译引擎的量。把图片占位（`📷 [图片]`）与不足 4 字的短行算进去会把费用估虚高。
     */
    suspend fun charCountOf(book: ImportedNovel, chapterIndex: Int): Int {
        val key = "${bookKey(book)}:$chapterIndex:${NovelParagraphSplitter.SPLIT_VERSION}"
        paraChars[key]?.let { return it }
        val n = paragraphsOf(book, chapterIndex)
            .filter { it.isTranslatable() }
            .sumOf { it.originalText.length }
        paraChars[key] = n
        return n
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
     * @param anchor 阅读锚点：**它会被强制放在页首**（带位重排用，见 [NovelAnchors.pageOf]）。
     *   传 null = 不强制分页（滚动模式、跳到章末）。⚠️ 它必须进缓存键，否则换了锚点会命中旧页表
     */
    suspend fun load(
        book: ImportedNovel,
        chapterIndex: Int,
        translations: Map<Int, String>,
        displayMode: NovelDisplayMode,
        style: NovelTextStyle,
        widthPx: Int,
        heightPx: Int,
        anchor: NovelAnchor? = null,
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
            "${style.keepParagraphsWhole}:$widthPx:$heightPx:" +
            // ⚠️ 锚点也要进键：同一个锚点在不同阅读位置会切出不同的页表（页首那刀）
            "${anchor?.paraIndex}:${anchor?.fraction}"

        // ⚠️ 先查缓存再算显示文本：`displayParas` 是 O(段数) 的对象分配，双语模式下每段还要
        // 拼一次字符串，而 `loadChapter` 每次改排版 / 切显示模式 / 切背景 / 译文到达都会调到这里。
        // 命中缓存时这些结果**原样丢掉** —— 键里没有任何一项依赖它们，所以可以提前返回。
        content.get(key)?.let { return@withContext it }

        val visible = paras.filter { it.type != NovelParagraphType.SKIP }
        ChapterContent(
            chapterIndex = chapterIndex,
            title = title,
            paragraphs = paras,
            displayTexts = displayTexts,
            pages = NovelPaginator.paginate(displayParas, style, widthPx, heightPx, anchor, layoutCache),
            translatableIndexes = visible.filter { it.isTranslatable() }.mapTo(HashSet()) { it.index },
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
     *
     * ⚠️ **别用它恢复阅读位置**：它给的是「含该段的**第一页**」，段一旦跨页（长段、整章一段）
     * 那就是**段首**所在的页 —— 把读者从段中间拽回段首，正是「翻译之后页面跳变」的成因。
     * 恢复位置用 `NovelAnchors.pageOf`（带段内比例）。
     */
    fun pageOfParagraph(pages: List<NovelPage>, paraIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val idx = pages.indexOfFirst { page -> page.segments.any { it.paraIndex >= paraIndex } }
        return if (idx >= 0) idx else pages.lastIndex
    }

    /**
     * 释放全部缓存。
     *
     * ⚠️ **六个都要清**：`paraCounts` / `paraChars` / `layoutCache` 是另外三份（段数、字数、段级排版），
     * 漏掉的话类注释承诺的「阅读器退出时整体释放」就不成立 —— 它们都按 书+章 增长，
     * 而且键与上面三个不是同一个字符串，不会跟着一起失效。
     */
    fun evictAll() {
        chapterLists.evictAll()
        paragraphs.evictAll()
        content.evictAll()
        paraCounts.clear()
        paraChars.clear()
        layoutCache.clear()
    }
}
