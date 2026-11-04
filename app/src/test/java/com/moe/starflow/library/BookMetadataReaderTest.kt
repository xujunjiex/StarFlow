package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookMetadataReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = """{"series_title":"SPY×FAMILY","episode_label":"139話 前編","page_title":"139話 前編","page_count":17,"permalink":"https://example.test/episode/139"}"""

    @Test fun singlePdfAndGenericMetaInNamedFolderAreReadWithoutModifyingThePdf() {
        val folder = temp.newFolder("139話 前編")
        val pdf = File(folder, "139話 前編.pdf").apply { writeText("%PDF-1.4\nsource bytes") }
        File(folder, "meta.json").writeText(json)
        val original = pdf.readBytes()
        val meta = ComicMetadataParser.parse(BookMetadataReader.sourceTexts(pdf))!!
        assertEquals("SPY×FAMILY", meta.series)
        assertEquals("139話 前編", meta.episode)
        assertArrayEquals(original, pdf.readBytes())
        assertEquals(listOf("139話 前編.pdf", "meta.json"), folder.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun genericMetaIsNotAttachedToEveryArchiveInASharedFolder() {
        val folder = temp.newFolder("group")
        val a = File(folder, "a.zip").apply { writeText("not an archive") }
        File(folder, "b.zip").writeText("not an archive")
        File(folder, "meta.json").writeText(json)
        assertTrue(BookMetadataReader.sidecarTexts(a).isEmpty())
        File(folder, "a.meta.json").writeText(json)
        assertEquals(1, BookMetadataReader.sidecarTexts(a).size)
    }

    @Test fun versionTwoEmptyPdfDescriptionsAreRepairedFromTheirAdjacentMetadata() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
        val folder = File(root, "manga_import/139話 前編").apply { mkdirs() }
        val pdf = File(folder, "139話 前編.pdf").apply { writeText("%PDF-1.4\nsource bytes") }
        File(folder, "meta.json").writeText(json)
        ImportedMangaStore.add(app, ImportedManga(1, "My name", pdf.path, isArchive = false, isPdf = true,
            coverPath = null, pageCount = 17, addedAt = 1, metadataVersion = 2, lastReadPage = 5))
        val uuid = ImportedMangaStore.load(app).single().bookUuid
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        val book = ImportedMangaStore.load(app).single()
        assertTrue(book.description.contains("SPY×FAMILY"))
        assertEquals("My name", book.title)
        assertEquals(uuid, book.bookUuid)
        assertEquals(5, book.lastReadPage)
        assertEquals(BookMetadataReader.VERSION, book.metadataVersion)
    }

    // ===== 书根判定：只认书根，不拿章节/子目录的 json 顶替 =====

    @Test
    fun bookRootTexts_takesOnlyDirectChildren() {
        val dir = temp.newFolder("book")
        File(dir, "meta.json").writeText("""{"title":"root"}""")
        File(dir, "ch1").mkdirs()
        File(dir, "ch1/meta.json").writeText("""{"title":"chapter-1"}""")
        File(dir, "ch2").mkdirs()
        File(dir, "ch2/ComicInfo.xml").writeText("<ComicInfo><Title>chapter-2</Title></ComicInfo>")

        val texts = BookMetadataReader.bookRootTexts(dir)
        assertEquals(listOf("meta.json"), texts.map { it.first })
    }

    @Test
    fun bookRootTexts_emptyWhenOnlyChaptersHaveMetadata() {
        val dir = temp.newFolder("no-root")
        File(dir, "ch1").mkdirs()
        File(dir, "ch1/meta.json").writeText("""{"title":"chapter-1"}""")
        File(dir, "001.jpg").writeText("x")

        assertTrue(BookMetadataReader.bookRootTexts(dir).isEmpty())
    }

    @Test
    fun bookRootTexts_zipStripsSharedWrapperButNotChapters() {
        val zip = temp.newFile("book.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("Book/ComicInfo.xml"))
            out.write("<ComicInfo><Title>root</Title></ComicInfo>".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("Book/ch1/meta.json"))
            out.write("""{"title":"chapter-1"}""".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("Book/001.jpg"))
            out.write(ByteArray(8))
            out.closeEntry()
        }

        val texts = BookMetadataReader.bookRootTexts(zip)
        assertEquals(listOf("Book/ComicInfo.xml"), texts.map { it.first })
    }

    @Test
    fun bookRootTexts_zipWithoutRootMetadataIsEmpty() {
        val zip = temp.newFile("chapters-only.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("Book/ch1/meta.json"))
            out.write("""{"title":"chapter-1"}""".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("Book/001.jpg"))
            out.write(ByteArray(8))
            out.closeEntry()
        }

        assertTrue(BookMetadataReader.bookRootTexts(zip).isEmpty())
    }
}
