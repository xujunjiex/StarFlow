package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
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
class MangaDescriptionsTest {
    @get:Rule val temp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var session: LibrarySession

    @Before fun setup() = runBlocking {
        app.getSharedPreferences("library_locations", 0).edit().clear()
            .putString("defaultPath", temp.newFolder("library").path).commit()
        LibrarySessions.prepare(app)
        session = LibrarySessions.current(app)
    }

    private fun directoryBook(): ImportedManga {
        val root = session.local("manga_import/Work").apply { mkdirs() }
        File(root, "meta.json").writeText("""{"summary":"Original summary","tags":["original"]}""")
        ImportedMangaStore.add(app, ImportedManga(1, "Work", root.path, false, coverPath = null,
            pageCount = 1, addedAt = 10, description = "Original summary", metadataVersion = BookMetadataReader.VERSION))
        session.flush()
        return ImportedMangaStore.load(app).single()
    }

    @Test fun editClearRestoreAndReimportKeepProgressAndSourceMetadata() = runBlocking {
        val book = directoryBook()
        val originalFile = File(book.localRoot, "meta.json").readBytes()
        ImportedMangaStore.patch(app, book) { it.copy(lastReadPage = 7, lastReadAt = 123) }
        ImportedMangaStore.editDescription(app, book, "My manual note")
        session.flush()
        val saved = ImportedMangaStore.load(app).single()
        assertEquals("My manual note", saved.effectiveDescription)
        assertEquals(7, saved.lastReadPage)
        assertEquals(123L, saved.lastReadAt)
        val sidecar = File(book.localRoot, "starflow.json")
        assertEquals("My manual note", JSONObject(sidecar.readText()).getString("descriptionOverride"))
        assertEquals("My manual note", BookData.snapshot(app, BookRef(session.locationId, "manga", saved.bookUuid), false)
            .getJSONObject("book").getString("descriptionOverride"))
        ImportedMangaStore.editDescription(app, saved, "")
        session.flush()
        assertEquals("", ImportedMangaStore.load(app).single().effectiveDescription)
        assertEquals("", JSONObject(sidecar.readText()).getString("descriptionOverride"))
        ImportedMangaStore.editDescription(app, saved, null)
        session.flush()
        assertEquals("Original summary", ImportedMangaStore.load(app).single().effectiveDescription)
        assertTrue(JSONObject(sidecar.readText()).isNull("descriptionOverride"))
        assertArrayEquals(originalFile, File(book.localRoot, "meta.json").readBytes())
        ImportedMangaStore.editDescription(app, saved, "Portable note")
        session.flush()
        val restored = MangaDescriptions.restore(book.copy(descriptionOverride = null, descriptionRevision = ""))
        assertEquals("Portable note", restored.effectiveDescription)
        // A normal scan does not import an older companion over the active record.
        sidecar.writeText(sidecar.readText().replace("Portable note", "Old copy"))
        LibraryScanner.scan(app, "manga")
        assertEquals("Portable note", ImportedMangaStore.load(app).single().effectiveDescription)
        session.removeBook("manga", 1)
        ImportedMangaStore.add(app, book.copy(id = 2, bookUuid = "", descriptionOverride = null, descriptionRevision = ""))
        assertEquals("Old copy", ImportedMangaStore.load(app).single().effectiveDescription)
    }

    @Test fun failedMirrorRetainsEditAndDurableRetryThenLatestRevisionWins() {
        val book = directoryBook()
        val sidecar = File(book.localRoot, "starflow.json")
        sidecar.writeText("unrelated user file")
        ImportedMangaStore.editDescription(app, book, "First")
        session.flush()
        assertEquals("First", ImportedMangaStore.load(app).single().effectiveDescription)
        assertEquals("unrelated user file", sidecar.readText())
        val reopened = LibrarySessions.openDatabase(app, session.locationId, session.storageGeneration)
        try {
            assertEquals("First", JSONObject(reopened.catalog().byUuid(book.bookUuid)!!.body).getString("descriptionOverride"))
            assertTrue(reopened.catalog().pending().any { JSONObject(it.body).optString("component") == "metadata" })
        } finally { reopened.close() }
        assertFalse(session.database.catalog().meta("metadataError:${book.bookUuid}").isNullOrBlank())
        assertTrue(session.database.catalog().pending().any { JSONObject(it.body).optString("component") == "metadata" })
        ImportedMangaStore.editDescription(app, book, "Latest")
        session.flush()
        sidecar.delete()
        session.flush()
        assertEquals("Latest", JSONObject(sidecar.readText()).getString("descriptionOverride"))
        assertTrue(session.database.catalog().pending().isEmpty())
    }

    @Test fun archiveEditWritesBesideTheFileWithoutRepacking() {
        val source = session.local("manga_import/Book.cbr").apply { parentFile!!.mkdirs(); writeText("source bytes") }
        ImportedMangaStore.add(app, ImportedManga(1, "Book", source.path, true, coverPath = null, pageCount = 1, addedAt = 1))
        val book = ImportedMangaStore.load(app).single()
        ImportedMangaStore.editDescription(app, book, "RAR note")
        session.flush()
        assertEquals("source bytes", source.readText())
        assertEquals("RAR note", JSONObject(File(source.path + ".starflow.json").readText()).getString("descriptionOverride"))
        assertEquals("RAR note", MangaDescriptions.restore(book.copy(descriptionRevision = "")).effectiveDescription)
        source.delete()
        ImportedMangaStore.editDescription(app, book, "Missing source note")
        session.flush()
        assertEquals("Missing source note", ImportedMangaStore.load(app).single().effectiveDescription)
        assertTrue(session.database.catalog().pending().isNotEmpty())
    }

    @Test fun verifiedOldChapterPollutionIsRemovedButManualContentSurvives() = runBlocking {
        val root = session.local("manga_import/Collection").apply { mkdirs() }
        listOf("a", "b").forEach { name ->
            File(root, name).mkdirs()
            File(root, "$name/001.jpg").writeText("image")
            File(root, "$name/meta.json").writeText("""{"summary":"Chapter $name","tags":["$name"]}""")
        }
        val index = MangaSourceIndex.build(root)
        val uuid = session.uuid("manga", 1)
        val path = index.store(session, uuid)
        val metadata = ComicMetadata(summary = "Chapter a", tags = listOf("a"))
        ImportedMangaStore.add(app, ImportedManga(1, "Collection", root.path, false, coverPath = null,
            pageCount = 2, addedAt = 1, description = ComicMetadataParser.buildDescription(app, metadata, false),
            tags = listOf("a"), metadataVersion = 8, chapters = index.chapters, sourceIndexPath = path))
        LibraryScanner.scan(app, "manga")
        var book = ImportedMangaStore.load(app).single()
        assertEquals("", book.description)
        assertTrue(book.tags.isEmpty())
        ImportedMangaStore.patch(app, book) { it.copy(description = "Chapter a", tags = listOf("manual tag")) }
        LibraryScanner.scan(app, "manga")
        book = ImportedMangaStore.load(app).single()
        assertEquals("", book.description)
        assertEquals(listOf("manual tag"), book.tags)
        ImportedMangaStore.patch(app, book) { it.copy(description = "Uncertain old manual note", tags = listOf("a")) }
        LibraryScanner.scan(app, "manga")
        book = ImportedMangaStore.load(app).single()
        assertEquals("Uncertain old manual note", book.description)
        assertTrue(book.tags.isEmpty())
        ImportedMangaStore.editDescription(app, book, "Chapter a")
        LibraryScanner.scan(app, "manga")
        assertEquals("Chapter a", ImportedMangaStore.load(app).single().effectiveDescription)
    }

    @Test fun dataPageUsesActualReaderPageThenFollowsProgressAndRefreshesOnReturn() {
        val root = session.local("manga_import/Reading").apply { mkdirs() }
        File(root, "meta.json").writeText("""{"summary":"Whole work"}""")
        (1..3).forEach { number ->
            File(root, "$number").mkdirs()
            File(root, "$number/001.jpg").writeText("page")
            File(root, "$number/002.jpg").writeText("page")
            File(root, "$number/meta.json").writeText("""{"summary":"Chapter $number summary"}""")
        }
        val index = MangaSourceIndex.build(root)
        ImportedMangaStore.add(app, ImportedManga(1, "Reading", root.path, false, coverPath = null,
            pageCount = 6, addedAt = 1, description = "Whole work", chapters = index.chapters,
            sourceIndexPath = index.store(session, session.uuid("manga", 1)), metadataVersion = BookMetadataReader.VERSION))
        val book = ImportedMangaStore.load(app).single()
        val intent = android.content.Intent(app, BookDataActivity::class.java).putExtra("location", session.locationId)
            .putExtra("kind", "manga").putExtra("uuid", book.bookUuid).putExtra("currentPage", 4)
        val controller = org.robolectric.Robolectric.buildActivity(BookDataActivity::class.java, intent).setup()
        val activity = controller.get()
        fun texts(view: android.view.View = activity.window.decorView): List<String> = when (view) {
            is android.widget.TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun awaitCondition(expected: String, complete: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (complete()) return
                Thread.sleep(10)
            }
            fail("Missing '$expected' in ${texts()}")
        }
        fun awaitText(expected: String) = awaitCondition(expected) { expected in texts() }
        try {
            awaitText("Chapter 3 summary")
            assertTrue("Whole work" in texts())
            File(root, "meta.json").writeText("""{"summary":"Updated whole work"}""")
            controller.pause().stop().start().resume()
            awaitText("Updated whole work")
            assertEquals("Updated whole work", ImportedMangaStore.load(app).single().effectiveDescription)
            ImportedMangaStore.patch(app, book) { it.copy(lastReadPage = 2, lastReadAt = 100) }
            awaitText("Chapter 2 summary")
            assertFalse("Chapter 3 summary" in texts())
            ImportedMangaStore.editDescription(app, book, "Edited whole work")
            awaitText("Edited whole work")
            assertTrue("Chapter 2 summary" in texts())
            File(root, "2/meta.json").writeText("""{"summary":"Updated chapter 2"}""")
            ImportedMangaStore.patch(app, book) { it.copy(lastReadPage = 3) }
            awaitCondition("same-chapter page progress") { texts().any { "2/2" in it } }
            assertTrue("Chapter 2 summary" in texts())
            assertFalse("Updated chapter 2" in texts())
            controller.pause().stop()
            controller.start().resume()
            awaitText("Updated chapter 2")
            assertTrue("Edited whole work" in texts())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rescanningRefreshesSourceDescriptionWhileKeepingManualOverride() = runBlocking {
        val book = directoryBook()
        LibraryScanner.scan(app, "manga")
        File(book.localRoot, "meta.json").writeText("""{"summary":"Changed source summary"}""")
        LibraryScanner.scan(app, "manga")
        assertEquals("Changed source summary", ImportedMangaStore.load(app).single().effectiveDescription)
        ImportedMangaStore.editDescription(app, book, "Keep manual")
        File(book.localRoot, "meta.json").writeText("""{"summary":"Changed source again, a longer summary"}""")
        LibraryScanner.scan(app, "manga")
        assertEquals("Keep manual", ImportedMangaStore.load(app).single().effectiveDescription)
        ImportedMangaStore.editDescription(app, book, null)
        assertEquals("Changed source again, a longer summary", ImportedMangaStore.load(app).single().effectiveDescription)
    }

    @Test fun legacyLookingManualLinesArePreservedExactly() = runBlocking {
        val book = directoryBook()
        val manual = "Tags: my own reading list\nSource: my handwritten note"
        ImportedMangaStore.patch(app, book) { it.copy(description = manual, metadataVersion = 8) }
        LibraryScanner.scan(app, "manga")
        assertEquals(manual, ImportedMangaStore.load(app).single().effectiveDescription)
        assertEquals(manual, ImportedMangaStore.load(app).single().descriptionOverride)
    }

    @Test fun missingSourceStillAllowsRestoreAndOtherBooksDoNotInheritItsFileError() = runBlocking {
        val book = directoryBook()
        ImportedMangaStore.editDescription(app, book, "Manual")
        session.flush()
        File(book.localRoot, "meta.json").delete()
        File(book.localRoot, "starflow.json").delete()
        File(book.localRoot).delete()
        ImportedMangaStore.editDescription(app, book, null)
        session.flush()
        assertEquals("Original summary", ImportedMangaStore.load(app).single().effectiveDescription)
        val other = session.local("manga_import/Other").apply { mkdirs() }
        ImportedMangaStore.add(app, ImportedManga(2, "Other", other.path, false, coverPath = null, pageCount = 0, addedAt = 2))
        session.flush()
        assertFalse(session.hasPendingSourceFiles())
        val otherBook = ImportedMangaStore.load(app).first { it.id == 2L }
        assertTrue(BookData.snapshot(app, BookRef(session.locationId, "manga", otherBook.bookUuid), false).isNull("saveError"))
        assertFalse(LibraryScanner.scan(app, "manga").needsRecovery)
    }

    @Test fun malformedOrOversizedWorkMetadataNeverAuthorizesLegacyCleanup() = runBlocking {
        val root = session.local("manga_import/Protected").apply { mkdirs() }
        (1..2).forEach { n ->
            File(root, "$n").mkdirs()
            File(root, "$n/001.jpg").writeText("page")
            File(root, "$n/meta.json").writeText("""{"summary":"Chapter only","tags":["chapter"]}""")
        }
        val index = MangaSourceIndex.build(root)
        ImportedMangaStore.add(app, ImportedManga(1, "Protected", root.path, false, coverPath = null,
            pageCount = 2, addedAt = 1, description = "Chapter only", tags = listOf("chapter"), metadataVersion = 8,
            chapters = index.chapters, sourceIndexPath = index.store(session, session.uuid("manga", 1))))
        val metadata = File(root, "meta.json")
        for (text in listOf("{broken", "x".repeat(ComicMetadataParser.MAX_METADATA_BYTES.toInt() + 1))) {
            metadata.writeText(text)
            LibraryScanner.scan(app, "manga")
            val saved = ImportedMangaStore.load(app).single()
            assertEquals("Chapter only", saved.effectiveDescription)
            assertEquals(listOf("chapter"), saved.tags)
        }
    }

    @Test fun sourceUpdatesDoNotReplaceUncertainLegacyManualText() = runBlocking {
        val book = directoryBook()
        ImportedMangaStore.patch(app, book) { it.copy(description = "Old manual note", metadataVersion = 8) }
        LibraryScanner.scan(app, "manga")
        val saved = ImportedMangaStore.load(app).single()
        assertEquals("Old manual note", saved.effectiveDescription)
        assertEquals("Old manual note", saved.descriptionOverride)
        assertFalse(File(book.localRoot, "starflow.json").exists())
    }

    @Test fun shelfDistinguishesAnUnreadSinglePageFromAReadFirstPage() {
        val book = directoryBook().copy(descriptionOverride = "Manual shelf note")
        val context = android.view.ContextThemeWrapper(app, com.moe.starflow.R.style.Base_Theme_MT)
        val parent = android.widget.FrameLayout(context)
        listOf(book to false, book.copy(lastReadAt = 100) to true,
            book.copy(pageCount = 5, lastReadPage = 4) to true).forEach { (value, read) ->
            val adapter = com.moe.starflow.mangaimport.ui.MangaGridAdapter(
                com.moe.starflow.mangaimport.ui.DisplayMode.DETAILED_LIST, {}, { _, _ -> })
            adapter.submitList(listOf(value))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                as com.moe.starflow.mangaimport.ui.MangaGridAdapter.RowVH
            adapter.onBindViewHolder(holder, 0)
            assertEquals(if (read) android.view.View.VISIBLE else android.view.View.GONE, holder.binding.tvReadBadge.visibility)
            assertEquals(if (read) android.view.View.VISIBLE else android.view.View.GONE, holder.binding.tvProgress.visibility)
            assertEquals("Manual shelf note", holder.binding.tvDescription.text.toString())
        }
    }
}
