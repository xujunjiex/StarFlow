package com.moe.starflow.novel.translate

import android.content.Context
import androidx.room.Room
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.NovelParagraphTranslationDao
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.translate.TranslationResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChapterTranslatorTest {

    private lateinit var db: TranslationHistoryDatabase
    private lateinit var dao: NovelParagraphTranslationDao

    private val book = ImportedNovel(
        id = 1,
        title = "书",
        localRoot = "/tmp/1",
        format = NovelFormat.TXT,
        chapterCount = 2,
        addedAt = 5000L,
    )

    /** 段 0、2 是正文，段 1 是过短段（不参与翻译，但**占 index**）。 */
    private val paragraphs = listOf(
        NovelParagraph(0, NovelParagraphType.TEXT, "第一段原文"),
        NovelParagraph(1, NovelParagraphType.SKIP, "……"),
        NovelParagraph(2, NovelParagraphType.TEXT, "第二段原文"),
    )

    /**
     * 假翻译器：解析 prompt 里的 `[N]` 前缀，按**同样的编号**回译文，并统计调用次数。
     * 这样它同时验证了「引擎把真实 paraIndex 发出去」这件事。
     */
    private class FakeTranslator(
        var calls: Int = 0,
        var failAll: Boolean = false,
        /** true = 回文里丢掉编号（用于测位置兜底）。 */
        var dropNumbering: Boolean = false,
    ) : NovelTextTranslator {
        override val name: String get() = "fake"

        override fun translate(
            prompt: String,
            sourceLang: String,
            targetLang: String,
            callback: (TranslationResult) -> Unit,
        ) {
            calls++
            if (failAll) {
                callback(TranslationResult.Error(Exception("boom")))
                return
            }
            val matched = prompt.lineSequence()
                .mapNotNull { LINE.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }
                .toList()
            val body = if (dropNumbering) {
                matched.joinToString("\n\n") { (_, text) -> "T:$text" }
            } else {
                // ⚠️ **逆序**回包：按请求顺序回的话「按键位对应」和「按位置对应」结果完全相同，
                // 改成位置映射也能过 —— 那是假绿（译文静默错位是最怕的一种错）
                matched.reversed().joinToString("\n") { (n, text) -> "[$n] T:$text" }
            }
            callback(TranslationResult.Success(body))
        }

        override fun cancel() {}

        companion object {
            private val LINE = Regex("""^\[(\d+)]\s*(.*)$""")
        }
    }

    private fun translatorFor(
        fake: FakeTranslator,
        splitVersion: Int = SPLIT_VERSION,
    ) = NovelChapterTranslator(dao, NovelTranslationEngine(fake), splitVersion)

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, TranslationHistoryDatabase::class.java).build()
        dao = db.novelParagraphTranslationDao()
    }

    @After
    fun tearDown() = db.close()

    // ===== 正常路径 =====

    /** 一批一批翻完，每批都落库（「翻译本章」走的就是这条批路径）。 */
    @Test
    fun `逐批翻完并落库`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "fake")
        t.translateBatch(book, 0, paragraphs, listOf(2), "ja", "zh", "fake")

        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.novelKey == "5000" })
    }

    /** 过短段不参与翻译，但它的 index 必须被跳过而不是被占用。 */
    @Test
    fun `SKIP 段不送翻译且不占译文行`() = runBlocking {
        translatorFor(FakeTranslator()).translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(listOf(0, 2), rows.map { it.paraIndex })
    }

    /** 真实 paraIndex 必须原样发出去 —— 发成「位置」会让译文整体错位。 */
    @Test
    fun `译文按键位对应而不是按回文顺序`() = runBlocking {
        // 段号故意不连续：0 与 2
        translatorFor(FakeTranslator()).translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        val map = translatorFor(FakeTranslator()).loadTranslations(book, 0)
        assertEquals("T:第一段原文", map[0])
        assertEquals("T:第二段原文", map[2])
        assertTrue("段 1 是 SKIP，不该有译文", !map.containsKey(1))
    }

    // ===== 失败路径 =====

    /**
     * **分母**必须是「整章可翻译段数」，不是"这一批写了几行"。
     *
     * 回归（用户报的「某一章没翻完却显示已经全部翻译完成」）：章徽章/筛选/目录的判据是
     * `success >= total`，而 `total` 是 `COUNT(*)`。行是**按批惰性写的** —— 只翻一批时
     * `total == success`，于是刚点了「翻译本章」的一章立刻被标成「已翻译」。
     * 修法是在翻每一批之前先把整章的可翻译段落补齐成 IDLE 行（`ensureChapterRows`）。
     */
    @Test
    fun `翻一批后本章分母立刻是整章可翻译段数`() = runBlocking {
        val t = translatorFor(FakeTranslator())

        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "fake")

        val stat = t.chapterStats(book)[0]
        assertEquals("分母 = 段 0 + 段 2（段 1 是 SKIP，不算）", 2, stat?.total)
        assertEquals("只翻了一段", 1, stat?.success)
        val idle = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
            .filter { it.state == NovelParagraphTranslation.STATE_IDLE }
        assertEquals("没翻的那段是 IDLE（待翻），不是缺行", listOf(2), idle.map { it.paraIndex })
    }

    /**
     * 失败必须写 FAILED 且**译文为空**（不是空串成功）。
     * 空串一旦被当成成功，该段永远显示空白且再也不会被重试。
     */
    @Test
    fun `全部失败时标记 FAILED 且不写空译文`() = runBlocking {
        translatorFor(FakeTranslator(failAll = true))
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "x")
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == NovelParagraphTranslation.STATE_FAILED })
        assertTrue(rows.all { it.translatedText.isEmpty() })
        assertTrue(rows.all { it.failCode != null })
    }

    /**
     * **失败原因必须带上原始报错**：用户要的不是"失败了"，是"为什么失败"
     * （`failCode` 会原样显示在面板章行的展开里）。
     */
    @Test
    fun `失败时 failCode 带出原始报错`() = runBlocking {
        val boom = object : NovelTextTranslator {
            override fun translate(
                prompt: String,
                sourceLang: String,
                targetLang: String,
                callback: (TranslationResult) -> Unit,
            ) {
                callback(TranslationResult.Error(java.io.IOException("HTTP 429 Too Many Requests")))
            }
        }
        val t = NovelChapterTranslator(dao, NovelTranslationEngine(boom), SPLIT_VERSION)

        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "boom")

        val row = dao.forChapter(1, "5000", 0, SPLIT_VERSION).first { it.paraIndex == 0 }
        assertEquals(NovelParagraphTranslation.STATE_FAILED, row.state)
        assertTrue("failCode 要含原始报错，实际=${row.failCode}", row.failCode.orEmpty().contains("HTTP 429"))
    }

    /** 整批被拒（内容审查等）时会降级为逐段重试，把其余段落救回来。 */
    @Test
    fun `整批失败会降级逐段重试一次`() = runBlocking {
        val fake = FirstCallFailsTranslator()
        val r = NovelChapterTranslator(dao, NovelTranslationEngine(fake), SPLIT_VERSION)
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "flaky")

        assertEquals("T:第一段原文", r.translations[0])
        assertEquals("T:第二段原文", r.translations[2])
        assertEquals("整批 1 次 + 逐段 2 次", 3, fake.calls)
    }

    /** 第一次调用失败、之后成功；回文带真实编号。 */
    private class FirstCallFailsTranslator : NovelTextTranslator {
        var calls = 0
        override val name: String get() = "flaky"

        override fun translate(
            prompt: String,
            sourceLang: String,
            targetLang: String,
            callback: (TranslationResult) -> Unit,
        ) {
            calls++
            if (calls == 1) {
                callback(TranslationResult.Error(Exception("batch rejected")))
                return
            }
            val matched = prompt.lineSequence()
                .mapNotNull { LINE.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }
                .joinToString("\n") { (n, t) -> "[$n] T:$t" }
            callback(TranslationResult.Success(matched))
        }

        override fun cancel() {}

        private companion object {
            val LINE = Regex("""^\[(\d+)]\s*(.*)$""")
        }
    }

    // ===== 位置兜底 =====

    /** 模型完全丢掉编号时按位置兜底（条数一致才接受）。 */
    @Test
    fun `模型丢掉编号时按位置兜底`() = runBlocking {
        val r = translatorFor(FakeTranslator(dropNumbering = true))
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals("T:第一段原文", r.translations[0])
        assertEquals("T:第二段原文", r.translations[2])
    }

    // ===== 读回与版本 =====

    @Test
    fun `splitVersion 不同的旧译文读不出来`() = runBlocking {
        translatorFor(FakeTranslator(), splitVersion = 1)
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertTrue(translatorFor(FakeTranslator(), splitVersion = 2).loadTranslations(book, 0).isEmpty())
    }

    @Test
    fun `resetStale 清掉残留的翻译中状态`() = runBlocking {
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "5000", chapterIndex = 0, paraIndex = 0,
                sourceText = "x", state = NovelParagraphTranslation.STATE_TRANSLATING,
                splitVersion = SPLIT_VERSION,
            )
        )
        translatorFor(FakeTranslator()).resetStale(book)
        assertEquals(
            NovelParagraphTranslation.STATE_IDLE,
            dao.forChapter(1, "5000", 0, SPLIT_VERSION).single().state,
        )
    }

    @Test
    fun `章状态聚合给出成功数`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals(2, t.chapterStats(book)[0]?.success)
        assertEquals(2, t.chapterStats(book)[0]?.total)
    }

    /**
     * 删除必须按 (id, key) **成对**：书籍 id 会被复用，只按 id 删会把复用同 id 的**新书**译文删掉。
     * （书架删书走的就是这条 SQL —— `NovelShelfFragment` 的清理路径。）
     */
    @Test
    fun `按指纹删且不误伤同 id 的另一本书`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "9999", chapterIndex = 0, paraIndex = 0,
                sourceText = "别的书", state = NovelParagraphTranslation.STATE_SUCCESS,
                splitVersion = SPLIT_VERSION,
            )
        )
        dao.deleteForNovelScoped(1, "5000")
        assertTrue(t.loadTranslations(book, 0).isEmpty())
        assertEquals("同 id 不同指纹的书不能被误删", 1, dao.countFor(1, "9999"))
    }

    /**
     * 进入阅读器时顺手清掉**旧分段版本**的行（`resetStale` 里一起做）。
     *
     * 分段规则升级后旧行的段号映射已经错了，留着既会显示错位的译文、又会占着主键。
     */
    @Test
    fun `resetStale 清掉非当前分段版本的旧行`() = runBlocking {
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "5000", chapterIndex = 0, paraIndex = 0,
                sourceText = "旧版本的段", state = NovelParagraphTranslation.STATE_SUCCESS,
                translatedText = "旧译文", splitVersion = SPLIT_VERSION - 1,
            )
        )

        translatorFor(FakeTranslator()).resetStale(book)

        assertTrue(
            "旧版本的行必须被清掉",
            dao.forChapter(1, "5000", 0, SPLIT_VERSION - 1).isEmpty(),
        )
    }

    /**
     * **清除选中段的译文**（长按多选 → 右下角清除）：
     * 读回立刻为空（阅读器随即回落原文），没被选的段不受影响，**行数不变**（那是本章分母）。
     */
    @Test
    fun `清除选中段的译文`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals(2, t.loadTranslations(book, 0).size)

        t.clearParagraphs(book, 0, listOf(0))

        val left = t.loadTranslations(book, 0)
        assertTrue("被清的段不能再读到译文", !left.containsKey(0))
        assertEquals("没被选的段要留着", "T:第二段原文", left[2])
        val stat = t.chapterStats(book)[0]
        assertEquals("分母（库里行数）不能变少 —— 清除是重置不是删行", 2, stat?.total)
        assertEquals("成功数要少一个", 1, stat?.success)
    }

    /** 清除也要清掉失败原因（否则章行还挂着旧报错）。 */
    @Test
    fun `清除会一并清掉失败原因`() = runBlocking {
        val boom = object : NovelTextTranslator {
            override fun translate(
                prompt: String,
                sourceLang: String,
                targetLang: String,
                callback: (TranslationResult) -> Unit,
            ) {
                callback(TranslationResult.Error(java.io.IOException("HTTP 429 Too Many Requests")))
            }
        }
        val t = NovelChapterTranslator(dao, NovelTranslationEngine(boom), SPLIT_VERSION)
        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "boom")
        assertTrue("前提：这一段确实失败了", t.failuresOf(book).isNotEmpty())

        t.clearParagraphs(book, 0, listOf(0))

        assertTrue("清除后失败明细里不该还有它", t.failuresOf(book).isEmpty())
    }

    /** 整章只有不可翻译段（SKIP）→ 一个请求都不发，也不写任何行。 */
    @Test
    fun `没有可翻译段时不发请求`() = runBlocking {
        val fake = FakeTranslator()
        val only = listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……"))

        val r = translatorFor(fake).translateBatch(book, 0, only, listOf(0), "ja", "zh", "fake")

        assertEquals(0, fake.calls)
        assertTrue(r.isEmpty)
        assertTrue(dao.forChapter(1, "5000", 0, SPLIT_VERSION).isEmpty())
    }

    // ===== 单批超长预警（阈值判据收在 NovelChapterTranslator.translateBatch 一处） =====

    /**
     * 超过阈值 → 问用户；用户**取消** → 这一批不发请求、不写库
     * （段保持"没翻过"，不是 FAILED：用户改大阈值或直接重来即可）。
     */
    @Test
    fun `超长批用户取消时不发请求也不写库`() = runBlocking {
        val fake = FakeTranslator()
        var asked: Pair<Int, Int>? = null
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(
                threshold = { 3 },
                confirm = { est, th -> asked = est to th; false },
            ),
        )

        val r = t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertTrue(r.isEmpty)
        assertEquals("问过一次", 1, (if (asked != null) 1 else 0))
        assertEquals("阈值原样传下去", 3, asked?.second)
        assertTrue("估算必须真的超过阈值（否则这条测试没测到）", (asked?.first ?: 0) > 3)
        assertEquals("不能发请求", 0, fake.calls)
        assertTrue("不能写任何行", dao.forChapter(1, "5000", 0, SPLIT_VERSION).isEmpty())
    }

    /** 用户选「继续」→ 照常翻译并落库。 */
    @Test
    fun `超长批用户继续时照常翻译`() = runBlocking {
        val fake = FakeTranslator()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(threshold = { 1 }, confirm = { _, _ -> true }),
        )

        val r = t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertEquals(mapOf(0 to "T:第一段原文", 2 to "T:第二段原文"), r.translations)
        assertEquals(2, t.chapterStats(book)[0]?.success)
    }

    /** 没超阈值（或没接预警）时**一次都不问**，老路径零回归。 */
    @Test
    fun `没超阈值时不打扰用户`() = runBlocking {
        var asked = 0
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(FakeTranslator()), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(threshold = { NovelBatchWarning.DEFAULT_THRESHOLD }) { _, _ ->
                asked++
                true
            },
        )

        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertEquals(0, asked)
    }

    private companion object {
        const val SPLIT_VERSION = 1
    }
}
