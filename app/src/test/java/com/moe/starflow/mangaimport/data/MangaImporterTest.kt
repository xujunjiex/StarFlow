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
                ImportedManga(3, "a", "/x", false, null, 1, 1L),
                ImportedManga(7, "b", "/y", true, null, 1, 1L)
            )
        )
        assertEquals(8L, MangaImporter.nextId(context))
    }
}
