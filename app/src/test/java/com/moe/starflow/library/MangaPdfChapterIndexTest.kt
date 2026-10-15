package com.moe.starflow.library

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MangaPdfChapterIndexTest.PdfMetadata::class])
class MangaPdfChapterIndexTest {
    @get:Rule val temp = TemporaryFolder()
    @Implements(PdfRenderer::class)
    class PdfMetadata {
        companion object { var count = 12 }
        @Implementation fun __constructor__(descriptor: ParcelFileDescriptor) { }
        @Implementation fun getPageCount() = count
        @Implementation fun close() { }
    }
    @Test fun pdfPagesUseThePdfPageTreeOrderAndChangedPaginationCannotReuseOldRecords() {
        val folder = temp.newFolder("Book")
        val pdf = File(folder, "Chapter 1.pdf").apply { writeText("%PDF-1.4\noriginal") }
        val original = pdf.readBytes()
        PdfMetadata.count = 12
        val first = MangaSourceIndex.build(folder)
        assertEquals((0 until 12).map(Int::toString), first.pages.map { it.entry })
        assertEquals("Chapter 1", first.chapters.single().title)
        assertArrayEquals(original, pdf.readBytes())
        pdf.appendText(" changed")
        PdfMetadata.count = 13
        val changed = MangaSourceIndex.build(folder, first)
        assertEquals(12, changed.pages.size)
        assertEquals(listOf("Chapter 1.pdf"), changed.problems)
        assertEquals(first.pages.map { it.key }, changed.pages.map { it.key })
        assertThrows(IllegalStateException::class.java) {
            com.moe.starflow.mangaimport.reader.ReaderPageSource(false, folder.path, sourceIndex = changed)
        }
        PdfMetadata.count = 12
        pdf.writeBytes(original)
        val restored = MangaSourceIndex.build(folder, changed)
        assertTrue(restored.problems.isEmpty())
        assertEquals(first.pages.map { it.key }, restored.pages.map { it.key })
    }
}
