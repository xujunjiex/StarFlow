package com.moe.starflow.library

import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.NovelParagraphTranslation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryCodecTest {
    private val uuid = "75b8b39b-c423-4a41-8d57-31b8d144f19f"

    // Encoding uses alphabetical fields, unlike constructor order. Reorder JSON
    // once more to exercise a provider/build-independent portable representation.
    private fun restore(row: Any): Any? {
        val encoded = LibraryCodec.encode(row, "original") as JSONObject
        val reversed = JSONObject()
        encoded.keys().asSequence().toList().sortedDescending().forEach { reversed.put(it, encoded.get(it)) }
        return LibraryCodec.decode(reversed, row.javaClass, "copy")
    }

    @Test fun mangaTranslationRestoresEveryFieldWithCopiedRootIdentity() {
        val row = ImportedPageTranslation(13, 7, 3, "source", "translated", "rectangles",
            "OCR", null, 927L, "original:manga:$uuid", "translator", "ja", "zh", "engine")
        assertEquals(row.copy(mangaKey = "copy:manga:$uuid"), restore(row))
    }

    @Test fun srRestoresDifferentSizesTimesAndNullableFields() {
        val row = ImportedPageSr(19, 5, 2, "model", 100, 200, 312L, 400, 800, 901L,
            null, "detail", 101L, 202L, 303L, "original:manga:$uuid", "chapter/page.png", "{\"scale\":4}")
        assertEquals(row.copy(mangaKey = "copy:manga:$uuid"), restore(row))
    }

    @Test fun novelTranslationRestoresParagraphIdentityAndImagePayload() {
        val row = NovelParagraphTranslation(23, "original:novel:$uuid", 3, 8, "source", "translated",
            2, null, "translator", "en", "zh", 5, 702L, "{\"blocks\":[]}")
        assertEquals(row.copy(novelKey = "copy:novel:$uuid"), restore(row))
    }
}
