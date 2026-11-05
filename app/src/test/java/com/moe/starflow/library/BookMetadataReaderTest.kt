package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.mangaimport.data.MangaPageRef
import com.moe.starflow.mangaimport.data.MangaChapter
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
    private val json = """{"series_title":"SPY×FAMILY","episode_label":"139話 前編","page_title":"139話 前編","page_count":17,"summary":"Original summary","permalink":"https://example.test/episode/139"}"""

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
        assertEquals("Original summary", book.description)
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

    @Test
    fun chapterMetadata_readsChaptersThatAreArchives() {
        val dir = temp.newFolder("archive-chapters")
        writeZip(File(dir, "ch1.cbz"), "first")
        writeZip(File(dir, "ch2.cbz"), "second")
        val chapters = listOf(
            MangaChapter(number = 1, title = "ch1.cbz", startPage = 0, pageCount = 2),
            MangaChapter(number = 2, title = "ch2.cbz", startPage = 2, pageCount = 2),
        )
        assertEquals("first", BookMetadataReader.chapterMetadata(dir, chapters[0], null)?.title)
        assertEquals("second", BookMetadataReader.chapterMetadata(dir, chapters[1], null)?.title)
    }

    @Test fun chapterMetadataUsesIndexedPathsEvenWhenDisplayTitlesAreIdentical() {
        val dir = temp.newFolder("indexed-chapters")
        writeZip(File(dir, "10.cbz"), "ten")
        writeZip(File(dir, "2.cbz"), "two")
        val index = MangaSourceIndex(listOf(
            MangaPageRef("10.cbz", "ZIP", "001.jpg", "10.cbz", 0),
            MangaPageRef("2.cbz", "ZIP", "001.jpg", "2.cbz", 0),
        ))
        assertEquals("ten", BookMetadataReader.chapterMetadata(dir, MangaChapter(1, "Same title", 0, 1), index)?.title)
        assertEquals("two", BookMetadataReader.chapterMetadata(dir, MangaChapter(2, "Same title", 1, 1), index)?.title)
    }

    @Test
    fun chapterMetadata_readsChaptersInsideOneArchive() {
        val zip = temp.newFile("one-book.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { out ->
            listOf("ch1" to "first", "ch2" to "second").forEach { (dir, title) ->
                out.putNextEntry(java.util.zip.ZipEntry("$dir/ComicInfo.xml"))
                out.write("<ComicInfo><Title>$title</Title></ComicInfo>".toByteArray())
                out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("$dir/001.jpg"))
                out.write(ByteArray(4))
                out.closeEntry()
            }
        }
        val chapters = listOf(
            MangaChapter(number = 1, title = "ch1", startPage = 0, pageCount = 1),
            MangaChapter(number = 2, title = "ch2", startPage = 1, pageCount = 1),
        )
        assertEquals("first", BookMetadataReader.chapterMetadata(zip, chapters[0], null)?.title)
        assertEquals("second", BookMetadataReader.chapterMetadata(zip, chapters[1], null)?.title)
    }

    @Test fun singleChapterReadsInnerMetadataButMultipleChaptersDoNotPromoteIt() {
        val dir = temp.newFolder("single-inner")
        writeZip(File(dir, "chapter.cbz"), "inner")
        val index = MangaSourceIndex(listOf(MangaPageRef("chapter.cbz", "ZIP", "001.jpg", "chapter.cbz", 0)))
        assertEquals("inner", BookMetadataReader.bookMetadata(dir, index.chapters, index)?.title)
        writeZip(File(dir, "second.cbz"), "second")
        val multiple = index.copy(pages = index.pages + MangaPageRef("second.cbz", "ZIP", "001.jpg", "second.cbz", 0))
        assertNull(BookMetadataReader.bookMetadata(dir, multiple.chapters, multiple))
        assertNull(BookMetadataReader.bookMetadata(dir, index.chapters, multiple))
    }

    @Test fun archiveRootIncludesComicBookInfoCommentWithoutMetadataEntries() {
        val zip = temp.newFile("comment.cbz")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.setComment("""{"ComicBookInfo/1.0":{"title":"Comment title"}}""")
            out.putNextEntry(java.util.zip.ZipEntry("001.jpg")); out.write(byteArrayOf(1)); out.closeEntry()
        }
        assertEquals("Comment title", ComicMetadataParser.parse(BookMetadataReader.bookRootTexts(zip))?.title)
    }

    @Test fun singleChapterMergesMissingRootFieldsFromTheInnerChapter() {
        val dir = temp.newFolder("single-fields")
        File(dir, "ComicInfo.xml").writeText("<ComicInfo><Title>Whole title</Title></ComicInfo>")
        File(dir, "chapter").mkdirs()
        File(dir, "chapter/meta.json").writeText("""{"summary":"Inner summary","author":"Inner author"}""")
        val index = MangaSourceIndex(listOf(MangaPageRef("chapter/001.jpg", "IMAGE", "", "chapter", 0)))
        val result = BookMetadataReader.bookMetadata(dir, index.chapters, index)!!
        assertEquals("Whole title", result.title)
        assertEquals("Inner summary", result.summary)
        assertEquals("Inner author", result.writer)
    }

    @Test fun looseRootChapterDoesNotUseTheNextChaptersMetadata() {
        val dir = temp.newFolder("loose-root")
        File(dir, "chapter").mkdirs()
        File(dir, "chapter/meta.json").writeText("""{"summary":"Chapter only"}""")
        val index = MangaSourceIndex(listOf(
            MangaPageRef("001.jpg", "IMAGE", "", "", 0),
            MangaPageRef("chapter/001.jpg", "IMAGE", "", "chapter", 0)))
        assertNull(BookMetadataReader.chapterMetadata(dir, index.chapters.first(), index))
        assertNull(BookMetadataReader.bookMetadata(dir, index.chapters, index))
    }

    @Test fun nestedArchiveWrappersAndBackslashEntryNamesResolveCorrectly() {
        val zip = temp.newFile("wrapped.cbz")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("Outer\\Book\\ComicInfo.xml"))
            out.write("<ComicInfo><Summary>Wrapped</Summary></ComicInfo>".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("Outer\\Book\\001.jpg")); out.write(byteArrayOf(1)); out.closeEntry()
        }
        assertEquals("Wrapped", ComicMetadataParser.parse(BookMetadataReader.bookRootTexts(zip))?.summary)
    }

    private fun writeZip(target: File, title: String) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(target)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("ComicInfo.xml"))
            out.write("<ComicInfo><Title>$title</Title></ComicInfo>".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("001.jpg")); out.write(byteArrayOf(1)); out.closeEntry()
        }
    }

    @Test fun backslashEntriesUseTheIndexedChapterRatherThanTheFirstMetadataFile() {
        val zip = temp.newFile("chapters.cbz")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            (1..2).forEach { n ->
                out.putNextEntry(java.util.zip.ZipEntry("Outer\\Book\\$n\\001.jpg")); out.write(byteArrayOf(1)); out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("Outer\\Book\\$n\\meta.json"))
                out.write("""{"summary":"Chapter $n"}""".toByteArray()); out.closeEntry()
            }
        }
        val index = MangaSourceIndex((1..2).map { n ->
            MangaPageRef(zip.name, "ZIP", "Outer\\Book\\$n\\001.jpg", "", 0)
        }, standalone = true)
        assertNull(BookMetadataReader.bookMetadata(zip, index.chapters, index))
        assertEquals("Chapter 2", BookMetadataReader.chapterMetadata(zip, index.chapters[1], index)?.summary)
    }

    @Test fun unreadableArchiveIsDifferentFromAnArchiveWithoutMetadata() {
        val zip = temp.newFile("empty.cbz")
        java.util.zip.ZipOutputStream(zip.outputStream()).close()
        assertNull(BookMetadataReader.bookMetadata(zip, emptyList()))
        zip.writeText("corrupt archive")
        assertThrows(java.io.IOException::class.java) { BookMetadataReader.bookMetadata(zip, emptyList()) }
    }
}
