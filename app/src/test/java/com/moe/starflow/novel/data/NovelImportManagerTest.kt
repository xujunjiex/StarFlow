package com.moe.starflow.novel.data

import android.content.Context
import androidx.room.Room
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.novel.parser.NovelReadLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 导入编排器里**可以脱离进程作用域单测**的那一半：失败归类 + 孤儿译文行的删除语句。
 *
 * （整个 `purgeOrphanTranslations` 跑在进程级作用域、用的是单例 DB，没法在单测里等它落地；
 * 这里直接对着真库验它的 DELETE，把「表名/列名写错」这种编译期看不见的错误钉住。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelImportManagerTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var db: TranslationHistoryDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, TranslationHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(novelId: Long, key: String, chapter: Int, para: Int) = NovelParagraphTranslation(
        novelId = novelId,
        novelKey = key,
        chapterIndex = chapter,
        paraIndex = para,
        sourceText = "原文",
        translatedText = "译文",
        state = NovelParagraphTranslation.STATE_SUCCESS,
    )

    /**
     * 孤儿清理要按 novelId **全删**（不分指纹）：清理时手上只有 id，拿不到指纹
     * （`allNovelIds()` 只给 id），而历史遗留行可能挂着旧指纹。
     *
     * ⚠️ 用户主动删书走的是 `deleteForNovelScoped`（带指纹，防 id 复用误删新书）；
     * 这条路的前提是「这个 id 已经不在书架上了」，所以可以只按 id 删 ——
     * 两套语义不能混用，这条测试连同 `NovelParagraphTranslationDaoTest` 的指纹那条一起看。
     */
    @Test
    fun `按 id 删译文：该 id 的所有指纹都清掉，别的书不动`() = runBlocking {
        val dao = db.novelParagraphTranslationDao()
        dao.upsert(row(5, "1000", 0, 0))
        dao.upsert(row(5, "2000", 1, 1))   // 同 id、不同指纹（历史遗留的孤儿行）
        dao.upsert(row(6, "1000", 0, 0))   // 别的书，一条都不能动

        dao.deleteForNovelId(5)

        assertEquals(0, dao.countFor(5, "1000"))
        assertEquals(0, dao.countFor(5, "2000"))
        assertEquals("别的书的译文不能被牵连", 1, dao.countFor(6, "1000"))
        assertEquals("清完后 id=5 不该再出现在库里", listOf(6L), dao.allNovelIds())
    }

    /** 删除原因必须真的可达：0 字节 → EMPTY（空文件），有内容但没章节 → NO_TEXT_CHAPTER。 */
    @Test
    fun `失败归类认得出空文件与无可读章节`() {
        assertEquals(
            NovelImportFailureReason.EMPTY,
            NovelImportManager.classify(IllegalStateException(NovelImporter.ERROR_EMPTY)),
        )
        assertEquals(
            NovelImportFailureReason.NO_TEXT_CHAPTER,
            NovelImportManager.classify(IllegalStateException(NovelImporter.ERROR_NO_TEXT_CHAPTER)),
        )
        assertEquals(
            NovelImportFailureReason.ENCRYPTED,
            NovelImportManager.classify(IllegalStateException(NovelImporter.ERROR_ENCRYPTED)),
        )
        assertEquals(
            "与常量取值不一致的解析器字面量会落进 UNKNOWN —— 这是要暴露出来的漂移",
            NovelImportFailureReason.UNKNOWN,
            NovelImportManager.classify(IllegalStateException("SOMETHING_ELSE")),
        )
    }

    /**
     * 超限要报「文件太大」而不是「格式不支持」。
     *
     * ⚠️ `TooLargeException` 是 `ZipException` 的**子类**，判据顺序反了就会被
     * `ZipException → NOT_ARCHIVE` 吃掉 —— 用户拿着一份好好的大文件去查「格式/损坏」，
     * 永远查不出结果。这条就是钉那个顺序的。
     */
    @Test
    fun `超限归类为文件太大而不是格式不支持`() {
        assertEquals(
            NovelImportFailureReason.TOO_LARGE,
            NovelImportManager.classify(NovelReadLimits.TooLargeException("xx.txt 超过 100MB 读取上限")),
        )
        assertEquals(
            "普通的 ZipException 仍应是格式/损坏",
            NovelImportFailureReason.NOT_ARCHIVE,
            NovelImportManager.classify(java.util.zip.ZipException("bad zip")),
        )
    }
}
