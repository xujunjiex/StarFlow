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
                matched.joinToString("\n") { (n, text) -> "[$n] T:$text" }
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

    @Test
    fun `逐批产出累积结果并落库`() = runBlocking {
        val emitted = translatorFor(FakeTranslator())
            .translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 1)
            .toList()

        assertTrue(emitted.isNotEmpty())
        assertTrue("最后一批必须标记完成", emitted.last().isComplete)
        val final = emitted.last().translations
        assertEquals(2, final.size)
        assertEquals("T:第一段原文", final[0])
        assertEquals("T:第二段原文", final[2])

        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == NovelParagraphTranslation.STATE_SUCCESS })
        assertTrue(rows.all { it.novelKey == "5000" })
    }

    /** 两段应合成一批 —— 批次被拆散正是参考实现翻译慢的根因。 */
    @Test
    fun `多段合成一批时只调一次翻译`() = runBlocking {
        val fake = FakeTranslator()
        translatorFor(fake).translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
        assertEquals(1, fake.calls)
    }

    /** 过短段不参与翻译，但它的 index 必须被跳过而不是被占用。 */
    @Test
    fun `SKIP 段不送翻译且不占译文行`() = runBlocking {
        translatorFor(FakeTranslator())
            .translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(listOf(0, 2), rows.map { it.paraIndex })
    }

    /** 真实 paraIndex 必须原样发出去 —— 发成「位置」会让译文整体错位。 */
    @Test
    fun `译文按键位对应而不是按回文顺序`() = runBlocking {
        // 段号故意不连续：0 与 2
        translatorFor(FakeTranslator())
            .translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
        val map = translatorFor(FakeTranslator()).loadTranslations(book, 0)
        assertEquals("T:第一段原文", map[0])
        assertEquals("T:第二段原文", map[2])
        assertTrue("段 1 是 SKIP，不该有译文", !map.containsKey(1))
    }

    // ===== 失败路径 =====

    /**
     * 失败必须写 FAILED 且**译文为空**（不是空串成功）。
     * 空串一旦被当成成功，该段永远显示空白且再也不会被重试。
     */
    @Test
    fun `全部失败时标记 FAILED 且不写空译文`() = runBlocking {
        translatorFor(FakeTranslator(failAll = true))
            .translateChapter(book, 0, paragraphs, "ja", "zh", "x", batchSize = 8).toList()
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == NovelParagraphTranslation.STATE_FAILED })
        assertTrue(rows.all { it.translatedText.isEmpty() })
        assertTrue(rows.all { it.failCode != null })
    }

    /** 整批被拒（内容审查等）时会降级为逐段重试，把其余段落救回来。 */
    @Test
    fun `整批失败会降级逐段重试一次`() = runBlocking {
        val fake = FirstCallFailsTranslator()
        val emitted = NovelChapterTranslator(dao, NovelTranslationEngine(fake), SPLIT_VERSION)
            .translateChapter(book, 0, paragraphs, "ja", "zh", "flaky", batchSize = 8)
            .toList()

        assertEquals("T:第一段原文", emitted.last().translations[0])
        assertEquals("T:第二段原文", emitted.last().translations[2])
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
        val emitted = translatorFor(FakeTranslator(dropNumbering = true))
            .translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8)
            .toList()
        assertEquals("T:第一段原文", emitted.last().translations[0])
        assertEquals("T:第二段原文", emitted.last().translations[2])
    }

    // ===== 读回与版本 =====

    @Test
    fun `splitVersion 不同的旧译文读不出来`() = runBlocking {
        translatorFor(FakeTranslator(), splitVersion = 1)
            .translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
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
        t.translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
        assertEquals(2, t.chapterStats(book)[0]?.success)
        assertEquals(2, t.chapterStats(book)[0]?.total)
    }

    @Test
    fun `清空本书按指纹删且不误伤别的书`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateChapter(book, 0, paragraphs, "ja", "zh", "fake", batchSize = 8).toList()
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "9999", chapterIndex = 0, paraIndex = 0,
                sourceText = "别的书", state = NovelParagraphTranslation.STATE_SUCCESS,
                splitVersion = SPLIT_VERSION,
            )
        )
        t.clearBook(book)
        assertTrue(t.loadTranslations(book, 0).isEmpty())
        assertEquals("同 id 不同指纹的书不能被误删", 1, dao.countFor(1, "9999"))
    }

    @Test
    fun `空章节不发请求直接完成`() = runBlocking {
        val fake = FakeTranslator()
        val emitted = translatorFor(fake)
            .translateChapter(book, 0, listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……")), "ja", "zh", "fake")
            .toList()
        assertEquals(0, fake.calls)
        assertTrue(emitted.last().isComplete)
        assertTrue(emitted.last().translations.isEmpty())
    }

    private companion object {
        const val SPLIT_VERSION = 1
    }
}
