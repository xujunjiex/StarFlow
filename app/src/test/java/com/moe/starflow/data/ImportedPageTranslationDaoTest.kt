package com.moe.starflow.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImportedPageTranslationDaoTest {

    private var db: TranslationHistoryDatabase? = null
    private fun dao() = db!!.importedPageTranslationDao()

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun row(page: Int, state: Int) = ImportedPageTranslation(
        mangaId = 7L, pageIndex = page,
        state = state,
        sourceText = "[1] src-$page", translatedText = "[1] t-$page",
        bubbleRects = null, failCode = null, failMessage = null, updatedAtMs = page.toLong(),
        mangaKey = "manga|1",
    )

    @Test
    fun upsertThenGetRoundTrip() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().upsert(row(3, ImportedPageTranslation.STATE_SUCCESS))
        val one = dao().get(7L, 3)
        assertEquals(ImportedPageTranslation.STATE_SUCCESS, one?.state)
        assertEquals("[1] t-3", one?.translatedText)
    }

    @Test
    fun upsertReplacesSameKeyAndKeepsOrderedList() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().upsert(row(5, ImportedPageTranslation.STATE_IDLE))
        // REPLACE 覆盖同 key
        dao().upsert(row(5, ImportedPageTranslation.STATE_FAILED).copy(failCode = "OCR_EMPTY"))
        dao().upsert(row(1, ImportedPageTranslation.STATE_SUCCESS))
        dao().upsert(row(3, ImportedPageTranslation.STATE_TRANSLATING))

        val updated = dao().get(7L, 5)
        assertEquals(ImportedPageTranslation.STATE_FAILED, updated?.state)
        assertEquals("OCR_EMPTY", updated?.failCode)

        val all = dao().forManga(7L, "manga|1")
        assertEquals(listOf(1, 3, 5), all.map { it.pageIndex })
        assertEquals(listOf(ImportedPageTranslation.STATE_SUCCESS, ImportedPageTranslation.STATE_TRANSLATING, ImportedPageTranslation.STATE_FAILED), all.map { it.state })
        assertNull(dao().get(7L, 4))
    }

    @Test
    fun rowsWithDifferentMangaKeyAreIgnored() = runBlocking {
        // 根因用例：删除漫画后重导复用同一 mangaId，但身份指纹（title|addedAt）不同，
        // 旧孤儿记录绝不串到新漫画。
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().upsert(row(1, ImportedPageTranslation.STATE_SUCCESS).copy(mangaKey = "old-title|100"))
        dao().upsert(row(2, ImportedPageTranslation.STATE_SUCCESS).copy(mangaKey = "manga|1"))
        assertEquals(listOf(2), dao().forManga(7L, "manga|1").map { it.pageIndex })
        assertEquals(1, dao().forManga(7L, "old-title|100").size)
    }

    @Test
    fun deleteMangaScopesToMangaId() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().upsert(row(1, ImportedPageTranslation.STATE_SUCCESS))
        dao().upsert(row(1, ImportedPageTranslation.STATE_SUCCESS).copy(mangaId = 99L))
        dao().deleteManga(7L)
        assertEquals(0, dao().forManga(7L, "manga|1").size)
        assertEquals(1, dao().forManga(99L, "manga|1").size)
    }

    /**
     * `resetTranslating` 是「记录永久卡在翻译中」的唯一兜底：该状态在翻译**开始时**写库，
     * 退出阅读器/崩溃会让它停在 TRANSLATING，之后该页既不显示译文也再也翻不了。
     *
     * ⚠️ @Query 里写的是字面量 `state = 0` / `state = 1`（注解里引用不了 Kotlin 常量），
     * 所以这条断言同时也锁住「常量值 ↔ 查询字面量」这个契约：改常量必须同步改查询。
     */
    @Test
    fun resetTranslatingOnlyClearsTranslatingOfSameMangaKey() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        // 契约守卫：查询把 1 当 TRANSLATING、0 当 IDLE
        assertEquals(1, ImportedPageTranslation.STATE_TRANSLATING)
        assertEquals(0, ImportedPageTranslation.STATE_IDLE)

        dao().upsert(row(1, ImportedPageTranslation.STATE_TRANSLATING))
        dao().upsert(row(2, ImportedPageTranslation.STATE_SUCCESS))
        dao().upsert(row(3, ImportedPageTranslation.STATE_FAILED))
        // 别的漫画（指纹不同）不得被清
        dao().upsert(row(4, ImportedPageTranslation.STATE_TRANSLATING).copy(mangaKey = "other|9"))

        dao().resetTranslating(7L, "manga|1")

        assertEquals(ImportedPageTranslation.STATE_IDLE, dao().get(7L, 1)?.state)
        assertEquals(ImportedPageTranslation.STATE_SUCCESS, dao().get(7L, 2)?.state)
        assertEquals(ImportedPageTranslation.STATE_FAILED, dao().get(7L, 3)?.state)
        assertEquals(
            "不同 mangaKey 的记录不得被清",
            ImportedPageTranslation.STATE_TRANSLATING, dao().get(7L, 4)?.state
        )
    }
}