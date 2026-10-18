package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderExportSrTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var source: ReaderPageSource
    private lateinit var original: ByteArray

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = temp.newFolder("pages")
        val bmp = bitmap(20, 30)
        original = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bmp.recycle()
        root.resolve("001.png").writeBytes(original)
        root.resolve("002.png").writeBytes(original)
        source = ReaderPageSource(false, root.absolutePath)
    }

    @After fun tearDown() { if (::source.isInitialized) source.close(); Dispatchers.resetMain() }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private suspend fun exportTranslated(
        source: ReaderPageSource,
        translatedPages: Set<Int>,
        renderTranslated: (Int, Bitmap?) -> Bitmap?,
        both: Boolean,
        translatedSuffix: String,
        tempFile: java.io.File,
        readSr: (Int) -> Bitmap? = { null },
        onProgress: (Int, Int) -> Unit,
    ): ExportOutcome = ReaderExport.exportToZip(
        pageCount = source.size,
        isTranslated = { it in translatedPages },
        isAvailable = source::pageAvailable,
        exportKey = source::exportKey,
        openOriginal = source::openEntry,
        renderTranslated = { renderTranslated(it, null) },
        both = both,
        includeUntranslated = true,
        translatedSuffix = translatedSuffix,
        tempFile = tempFile,
        onProgress = onProgress,
        readSr = readSr,
        renderTranslatedWithBase = renderTranslated,
    )

    @Test fun originalExportKeepsUnprocessedBytesAndUpscaledDimensions() = runBlocking {
        val out = temp.newFile("original.zip")
        val sr = bitmap(40, 60)
        assertEquals(ExportOutcome.Done(2, 0), ReaderExport.exportOriginal(source, out) {
            if (it == 0) sr else null
        })
        ZipFile(out).use { zip ->
            val decoded = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry("001.jpg")))
            assertEquals(40, decoded.width)
            assertEquals(60, decoded.height)
            decoded.recycle()
            assertArrayEquals(original, zip.getInputStream(zip.getEntry("002.png")).use { it.readBytes() })
        }
        assertTrue(sr.isRecycled)
    }

    @Test fun bilingualExportSharesUpscaledBaseAndFallsBackForOtherPages() = runBlocking {
        val out = temp.newFile("both.zip")
        val bases = mutableListOf<Bitmap>()
        assertEquals(ExportOutcome.Done(2, 0), exportTranslated(
            source = source,
            translatedPages = setOf(0, 1),
            renderTranslated = { page, sr ->
                if (page == 0) { assertSame(bases.single(), sr); sr!!.copy(Bitmap.Config.ARGB_8888, true) }
                else { assertNull(sr); bitmap(20, 30) }
            },
            both = true,
            translatedSuffix = "_translated",
            tempFile = out,
            readSr = { if (it == 0) bitmap(40, 60).also(bases::add) else null },
            onProgress = { _, _ -> },
        ))
        ZipFile(out).use { zip ->
            for (key in listOf("001.jpg", "001_translated.jpg")) {
                val bmp = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry(key)))
                assertEquals(40, bmp.width)
                assertEquals(60, bmp.height)
                bmp.recycle()
            }
            assertArrayEquals(original, zip.getInputStream(zip.getEntry("002.png")).use { it.readBytes() })
            assertNotNull(zip.getEntry("002_translated.jpg"))
        }
        assertTrue(bases.single().isRecycled)
    }

    @Test fun defaultExportRetainsOriginalBytesWithoutUpscaling() = runBlocking {
        val out = temp.newFile("disabled.zip")
        exportTranslated(source, setOf(0), { _, sr ->
            assertNull(sr); bitmap(20, 30)
        }, true, "_translated", out, onProgress = { _, _ -> })
        ZipFile(out).use { zip ->
            assertArrayEquals(original, zip.getInputStream(zip.getEntry("001.png")).use { it.readBytes() })
            assertEquals(3, zip.size())
        }
    }

    @Test fun failedTranslationFallsBackToUpscaledOriginalAndRecyclesBase() = runBlocking {
        val out = temp.newFile("failed.zip")
        val sr = bitmap(40, 60)
        val outcome = exportTranslated(source, setOf(0), { _, _ -> null },
            true, "_translated", out, readSr = { if (it == 0) sr else null }, onProgress = { _, _ -> })
        assertEquals(ExportOutcome.Done(2, 0, 2), outcome)
        assertTrue(sr.isRecycled)
        ZipFile(out).use {
            assertEquals(2, it.size())
            assertNotNull(it.getEntry("001.jpg"))
            assertNotNull(it.getEntry("002.png"))
        }
    }

    @Test fun untranslatedPageFallsBackToUpscaledImageInTranslatedPackage() = runBlocking {
        val out = temp.newFile("fallback.zip")
        val outcome = exportTranslated(source, setOf(0), { _, _ -> bitmap(20, 30) },
            false, "_translated", out, readSr = { if (it == 1) bitmap(40, 60) else null },
            onProgress = { _, _ -> })
        assertEquals(ExportOutcome.Done(2, 0, 1), outcome)
        ZipFile(out).use { zip ->
            assertEquals(2, zip.size())
            val bmp = BitmapFactory.decodeStream(zip.getInputStream(zip.getEntry("002.jpg")))
            assertEquals(40, bmp.width)
            assertEquals(60, bmp.height)
            bmp.recycle()
        }
    }

    @Test fun originalPdfExportsAllPagesWithoutTranslationAndRecyclesBitmaps() = runBlocking {
        val pages = listOf(bitmap(40, 60), bitmap(20, 30))
        val out = ByteArrayOutputStream()
        val outcome = ReaderPdfExport.export(2, emptySet(), { pages[it] }, { fail("No translation expected"); null },
            false, out, { _, _ -> }, encodeJpeg = { byteArrayOf(1, 2) }, originalOnly = true, pageDimensions = { 20 to 30 })
        assertEquals(ExportOutcome.Done(2, 0), outcome)
        val pdf = out.toString("ISO-8859-1")
        assertTrue(pdf.contains("/Width 40 /Height 60"))
        assertTrue(pdf.contains("/Width 20 /Height 30"))
        val mediaBoxes = Regex("/MediaBox \\[([^]]+)]").findAll(pdf).map { it.groupValues[1] }.toList()
        assertEquals(2, mediaBoxes.size)
        assertEquals(mediaBoxes[0], mediaBoxes[1])
        assertTrue(pages.all { it.isRecycled })
    }
}
