package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaImporterTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun setUp() {
        ImportedMangaStore.save(context, emptyList())
    }

    @Test
    fun nextId_emptyListToOne() {
        ImportedMangaStore.save(context, emptyList())
        assertEquals(1L, MangaImporter.nextId(context))
    }

    @Test
    fun nextId_maxPlusOne() {
        ImportedMangaStore.save(
            context,
            listOf(
                // ⚠️ 用具名参数：ImportedManga 会随功能加字段（如 isPdf），位置参数一加就静默错位/编译不过
                ImportedManga(
                    id = 3, title = "a", localRoot = com.moe.starflow.library.LibrarySessions.current(context).local("manga_import/x").path, isArchive = false,
                    coverPath = null, pageCount = 1, addedAt = 1L,
                ),
                ImportedManga(
                    id = 7, title = "b", localRoot = com.moe.starflow.library.LibrarySessions.current(context).local("manga_import/y").path, isArchive = true,
                    coverPath = null, pageCount = 1, addedAt = 1L,
                )
            )
        )
        assertEquals(8L, MangaImporter.nextId(context))
    }
}
