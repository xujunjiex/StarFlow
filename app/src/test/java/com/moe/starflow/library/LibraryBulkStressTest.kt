package com.moe.starflow.library

import android.content.Context
import android.net.Uri
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.novel.data.NovelImportManager
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real disk payloads and production managers; no device or existing user library is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryBulkStressTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var app: Context
    private lateinit var root: File

    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
        ImportManager.drainEvents()
        NovelImportManager.drainEvents()
        Unit
    }

    @Test fun hundredMixedImportsQueueAndQueuedCancellationNeverReadsOrCreatesACopy() = runBlocking {
        val manga = temp.newFile("manga.cbz")
        val novel = temp.newFile("novel.epub")
        LibraryScanFixtures.zip(manga, "001.png" to LibraryScanFixtures.png())
        LibraryScanFixtures.epub(novel)
        val gate = CountDownLatch(1)
        val entered = AtomicInteger()
        val touched = java.util.concurrent.ConcurrentHashMap.newKeySet<Uri>()
        val streams = (0 until 100).map { index ->
            val source = if (index % 2 == 0) manga else novel
            val uri = Uri.parse("content://bulk-stress/book-$index.${source.extension}")
            val stream = object : FilterInputStream(source.inputStream()) {
                var firstRead = true
                private fun blockOnce() {
                    if (!firstRead) return
                    firstRead = false
                    touched += uri
                    entered.incrementAndGet()
                    check(gate.await(30, TimeUnit.SECONDS)) { "Import copy was not released" }
                }
                override fun read(): Int { blockOnce(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    blockOnce(); return `in`.read(bytes, offset, count)
                }
            }
            shadowOf(app.contentResolver).registerInputStream(uri, stream)
            uri to stream
        }
        try {
            ImportManager.importArchives(app, streams.map { it.first }.filterIndexed { i, _ -> i % 2 == 0 })
            NovelImportManager.importFiles(app, streams.map { it.first }.filterIndexed { i, _ -> i % 2 != 0 })
            await { ImportManager.tasks.value.size + NovelImportManager.tasks.value.size == 100 && entered.get() >= 2 }
            assertEquals("Both shelves must share the same copy/decoder limit", 2, entered.get())
            for (kind in listOf("manga", "novel")) {
                val scan = withTimeout(10_000) { LibraryScanner.scan(app, kind) }
                assertEquals(0, scan.added)
                assertTrue(scan.failures.toString(), scan.failures.isEmpty())
            }
            val queued = ImportManager.tasks.value.first { it.progress.phase == ImportPhase.QUEUED }
            val untouched = touched.toSet()
            val session = LibrarySessions.current(app)
            assertNull(session.database.catalog().meta("uuid:manga:${queued.id}"))
            assertTrue(ImportManager.cancel(queued.id))
            await { ImportManager.tasks.value.none { it.id == queued.id } }
            assertEquals(untouched, touched.toSet())
            assertNull(session.database.catalog().meta("uuid:manga:${queued.id}"))
            val activeManga = ImportManager.tasks.value.firstOrNull { it.progress.phase == ImportPhase.COPYING }
            val active = activeManga ?: NovelImportManager.tasks.value.first { it.progress.phase == ImportPhase.COPYING }
            val activeKind = if (activeManga != null) "manga" else "novel"
            val activeUuid = session.database.catalog().meta("uuid:$activeKind:${active.id}")!!
            assertTrue(if (activeManga != null) ImportManager.cancel(active.id) else NovelImportManager.cancel(active.id))
            gate.countDown()
            await(120_000) { ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty() }
            assertEquals(if (activeManga != null) 48 else 49, ImportedMangaStore.load(app).size)
            assertEquals(if (activeManga != null) 50 else 49, NovelStore.load(app).size)
            assertFalse(session.local("${activeKind}_import/$activeUuid").exists())
            val events = ImportManager.drainEvents()
            assertTrue(events.toString(), events.isEmpty())
            assertTrue(NovelImportManager.drainEvents().isEmpty())
            assertEquals(99, touched.size)
        } finally {
            gate.countDown()
            ImportManager.tasks.value.forEach { ImportManager.cancel(it.id) }
            NovelImportManager.tasks.value.forEach { NovelImportManager.cancel(it.id) }
            await(120_000) { ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty() }
            streams.forEach { it.second.close() }
        }
    }

    @Test fun hundredFailedImportsReleaseTheirSlotsAndKeepEveryFailureDuringConcurrentDrains() = runBlocking {
        val invalid = temp.newFile("empty.cbz")
        val uri = Uri.fromFile(invalid)
        val mangaFailures = mutableListOf<com.moe.starflow.mangaimport.data.ImportEvent>()
        val novelFailures = mutableListOf<com.moe.starflow.novel.data.NovelImportEvent>()
        ImportManager.importArchives(app, List(50) { uri })
        NovelImportManager.importFiles(app, List(50) { uri })
        try {
            await(120_000) {
                mangaFailures += ImportManager.drainEvents()
                novelFailures += NovelImportManager.drainEvents()
                mangaFailures.size + novelFailures.size == 100 &&
                    ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty()
            }
            assertEquals(50, mangaFailures.size)
            assertEquals(50, novelFailures.size)
            assertTrue(mangaFailures.all { it is com.moe.starflow.mangaimport.data.ImportEvent.Failed })
            assertTrue(novelFailures.all { it is com.moe.starflow.novel.data.NovelImportEvent.Failed })
            assertTrue(LibrarySessions.current(app).books("manga").isEmpty())
            assertTrue(LibrarySessions.current(app).books("novel").isEmpty())
            assertTrue(File(root, "manga_import").listFiles()!!.isEmpty())
            assertTrue(File(root, "novel_import").listFiles()!!.isEmpty())
        } finally {
            ImportManager.tasks.value.forEach { ImportManager.cancel(it.id) }
            NovelImportManager.tasks.value.forEach { NovelImportManager.cancel(it.id) }
            await(120_000) { ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty() }
        }
    }

    @Test fun unavailableCatalogReportsEveryReservationFailure() = runBlocking {
        val uri = Uri.fromFile(temp.newFile("unavailable.cbz"))
        LibrarySessions.current(app).close()
        ImportManager.importArchives(app, List(10) { uri })
        NovelImportManager.importFiles(app, List(10) { uri })
        await {
            ImportManager.events.value.size == 10 && NovelImportManager.events.value.size == 10
        }
        assertEquals(10, ImportManager.drainEvents().size)
        assertEquals(10, NovelImportManager.drainEvents().size)
        assertTrue(ImportManager.tasks.value.isEmpty())
        assertTrue(NovelImportManager.tasks.value.isEmpty())
    }

    @Test fun hundredArchivesOverOneGigabyteImportRescanAndDeleteWithoutLossOrResurrection() = runBlocking {
        val fixtures = temp.newFolder("sources")
        val sources = (0 until 100).map { i ->
            File(fixtures, "book-$i.${if (i % 2 == 0) "cbz" else "epub"}").also {
                archiveWithPayload(it, novel = i % 2 != 0)
            }
        }
        val bytes = sources.sumOf { it.length() }
        assertTrue("Use actual archive bytes, not sparse padding or a declared size", bytes >= 1_000_000_000L)
        val started = System.nanoTime()
        val importJobs = LibrarySessions.changes.value
        ImportManager.importArchives(app, sources.filterIndexed { i, _ -> i % 2 == 0 }.map(Uri::fromFile))
        NovelImportManager.importFiles(app, sources.filterIndexed { i, _ -> i % 2 != 0 }.map(Uri::fromFile))
        try {
            await(180_000) {
                assertTrue(ImportManager.events.value.toString(), ImportManager.events.value.isEmpty())
                assertTrue(NovelImportManager.events.value.toString(), NovelImportManager.events.value.isEmpty())
                LibrarySessions.changes.value != importJobs &&
                    ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty() &&
                    LibrarySessions.current(app).books("manga").size + LibrarySessions.current(app).books("novel").size == 100
            }
            val importedAt = System.nanoTime()
            assertTrue(ImportManager.drainEvents().isEmpty())
            assertTrue(NovelImportManager.drainEvents().isEmpty())
            val session = LibrarySessions.current(app)
            val original = session.books("manga") + session.books("novel")
            assertEquals(100, original.map { it.uuid }.toSet().size)
            assertEquals(bytes, original.sumOf { session.local(it.source).length() })
            original.forEach { book ->
                val copied = session.local(book.source)
                assertEquals("Copied archive checksum: ${copied.name}", crc(File(fixtures, copied.name)), crc(copied))
            }
            repeat(2) {
                for (kind in listOf("manga", "novel")) {
                    val result = LibraryScanner.scan(app, kind)
                    assertEquals(result.failures.joinToString(), emptyList<String>(), result.failures)
                    assertEquals(0, result.added)
                }
            }
            assertEquals(original.map { it.uuid }.toSet(), (session.books("manga") + session.books("novel")).map { it.uuid }.toSet())
            val scannedAt = System.nanoTime()
            // Both requests race scans deliberately; no stale candidate may recreate an imported wrapper.
            for (kind in listOf("manga", "novel")) {
                val books = original.filter { it.kind == kind }
                LibraryDeletion.start(app, kind, books.map { it.id }, books.associate { it.id to it.uuid })
            }
            val scans = listOf("manga", "novel").map { kind -> async(Dispatchers.IO) { LibraryScanner.scan(app, kind) } }
            scans.awaitAll().forEach { assertTrue(it.failures.toString(), it.failures.isEmpty()) }
            await(180_000) { !LibraryDeletion.state.value.running && session.books("manga").isEmpty() && session.books("novel").isEmpty() }
            assertTrue(LibraryDeletion.state.value.failures.toString(), LibraryDeletion.state.value.failures.isEmpty())
            for (kind in listOf("manga", "novel")) {
                val result = LibraryScanner.scan(app, kind)
                assertEquals(0, result.added)
                assertTrue(result.failures.toString(), result.failures.isEmpty())
                assertTrue(File(root, "${kind}_import").listFiles()!!.isEmpty())
            }
            assertEquals(0, session.database.catalog().pendingCount())
            assertTrue(File(root, ".starflow/books").listFiles()!!.isEmpty())
            assertTrue("External originals must survive deletion", sources.all(File::isFile))
            val deletedAt = System.nanoTime()
            // Discover the same 1GB as manually placed sources, rather than only rescanning known books.
            sources.forEachIndexed { index, source ->
                source.copyTo(File(root, "${if (index % 2 == 0) "manga" else "novel"}_import/${source.name}"))
            }
            for (kind in listOf("manga", "novel")) {
                val result = LibraryScanner.scan(app, kind)
                assertTrue(result.failures.toString(), result.failures.isEmpty())
                assertEquals(50, result.added)
            }
            val discoveredAt = System.nanoTime()
            val discovered = session.books("manga") + session.books("novel")
            assertEquals(100, discovered.map { it.uuid }.toSet().size)
            assertTrue(discovered.none { book -> original.any { it.uuid == book.uuid } })
            for (kind in listOf("manga", "novel")) {
                val books = discovered.filter { it.kind == kind }
                LibraryDeletion.start(app, kind, books.map { it.id }, books.associate { it.id to it.uuid })
            }
            await(180_000) { !LibraryDeletion.state.value.running && session.books("manga").isEmpty() && session.books("novel").isEmpty() }
            for (kind in listOf("manga", "novel")) {
                assertTrue(File(root, "${kind}_import").listFiles()!!.isEmpty())
                assertEquals(0, LibraryScanner.scan(app, kind).added)
            }
            assertTrue(sources.all(File::isFile))
            println("BULK_STRESS files=100 bytes=$bytes importMs=${(importedAt-started)/1_000_000} verifyAndScanMs=${(scannedAt-importedAt)/1_000_000} deleteMs=${(deletedAt-scannedAt)/1_000_000} discoveryWithCopyMs=${(discoveredAt-deletedAt)/1_000_000} deleteDiscoveredMs=${(System.nanoTime()-discoveredAt)/1_000_000}")
        } finally {
            ImportManager.tasks.value.forEach { ImportManager.cancel(it.id) }
            NovelImportManager.tasks.value.forEach { NovelImportManager.cancel(it.id) }
            await(180_000) { ImportManager.tasks.value.isEmpty() && NovelImportManager.tasks.value.isEmpty() && !LibraryDeletion.state.value.running }
        }
    }

    @Test fun scanWaitsForDeletionWhileAReaderStillHoldsTheSource() = runBlocking {
        val source = File(root, "manga_import/book.cbz")
        LibraryScanFixtures.zip(source, "001.png" to LibraryScanFixtures.png())
        assertEquals(1, LibraryScanner.scan(app, "manga").added)
        val book = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        val reader = LibrarySources.acquire(source)
        var scan: Deferred<LibraryScanState>? = null
        try {
            LibraryDeletion.start(app, "manga", listOf(book.id))
            await { session.database.catalog().book("manga", book.id)?.deleting == true }
            val entered = CompletableDeferred<Unit>()
            scan = async(Dispatchers.IO) { entered.complete(Unit); LibraryScanner.scan(app, "manga") }
            entered.await()
            delay(200)
            assertFalse("Scanning cannot inspect sources while deletion is waiting for their reader", scan.isCompleted)
        } finally { reader.close() }
        assertTrue(withTimeout(10_000) { scan!!.await() }.failures.isEmpty())
        await { !LibraryDeletion.state.value.running && session.books("manga").isEmpty() }
        assertFalse(source.exists())
    }

    private suspend fun await(timeout: Long = 30_000, done: () -> Boolean) = withTimeout(timeout) {
        while (!done()) delay(10)
    }

    private fun crc(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
        }
        return crc.value
    }

    private fun archiveWithPayload(file: File, novel: Boolean) {
        val entries = if (novel) listOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>".toByteArray(),
            "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${file.name}</dc:title></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""".toByteArray(),
            "chapter.xhtml" to "<html><body><p>A complete text chapter.</p></body></html>".toByteArray(),
        ) else listOf("001.png" to LibraryScanFixtures.png())
        val buffer = ByteArray(64 * 1024).also { java.util.Random(42).nextBytes(it) }
        val crc = CRC32().apply { repeat(160) { update(buffer) } }.value
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED; size = 10L * 1024 * 1024; compressedSize = size; this.crc = crc
            })
            repeat(160) { zip.write(buffer) }
            zip.closeEntry()
        }
    }
}
