package com.moe.starflow.mangaimport.translate

import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.sr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [SrStoreTestOs::class])
class ReaderSuperResolutionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var book: SrTestBook
    private lateinit var manga: ImportedManga
    private lateinit var pages: ReaderPageSource
    private lateinit var reader: ReaderSuperResolution
    private val inputs = Collections.synchronizedList(mutableListOf<Bitmap>())
    private val events = Events()
    private var process: suspend (Int, Bitmap, SrRequest, JSONObject, Long) -> SrOutcome =
        { page, bitmap, request, execution, generation -> save(page, bitmap, request, execution, generation) }

    private class Events : ReaderSuperResolution.Host {
        @Volatile override var uiAttached = true
        val changed = Collections.synchronizedList(mutableListOf<Int>())
        val invalidated = Collections.synchronizedList(mutableListOf<Int>())
        val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        var webtoonCleared = 0
        override suspend fun onBaseChanged(page: Int) { changed += page }
        override fun evictPageRenders(page: Int) { invalidated += page }
        override fun clearWebtoonCache() { webtoonCleared++ }
        override suspend fun evictAllRendersForChapter() = Unit
        override fun onVisual() = Unit
        override fun onSrNotice(text: String, isError: Boolean) = Unit
        override fun onSrProgress(page: Int, running: Boolean) { progress += page to running }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        book = SrTestBook(context)
        ZipOutputStream(book.source.outputStream()).use { zip ->
            repeat(2) { page ->
                zip.putNextEntry(ZipEntry("$page.png"))
                val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                bitmap.recycle()
                zip.closeEntry()
            }
        }
        pages = ReaderPageSource(true, book.source.path)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        SrSettings.setReaderEnabled(prefs, true)
        SrModelManager.setActive(prefs, SrModelManager.allKeys.first())
        manga = ImportedManga(
            id = book.id, title = "SR lifecycle", localRoot = book.source.path,
            isArchive = true, coverPath = null, pageCount = 2, addedAt = 1,
            bookUuid = book.uuid, libraryLocationId = book.session.locationId,
        )
        reader = ReaderSuperResolution(
            context, manga, scope,
            source = object : ReaderSuperResolution.Source {
                override val pageSource get() = pages
                override fun isAvailable(page: Int) = page in 0 until pages.size
                override fun loadFull(page: Int): Bitmap =
                    Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).also { inputs += it }
            },
            host = events,
            backend = object : ReaderSuperResolution.Backend {
                override suspend fun preflight(request: SrRequest): SrOutcome? = null
                override suspend fun enhanceAndStore(
                    page: Int, src: Bitmap, request: SrRequest, runId: Long,
                    execution: JSONObject, expectedGeneration: Long,
                ): SrOutcome = process(page, src, request, execution, expectedGeneration)
            },
        )
    }

    @After
    fun tearDown() = runBlocking {
        reader.stopPageWork()
        reader.cancelChaptersAndJoin()
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        // The component borrows this source; its owner closes it after work has exited.
        pages.close()
        Dispatchers.resetMain()
    }

    private fun save(page: Int, bitmap: Bitmap, request: SrRequest, execution: JSONObject, generation: Long): SrOutcome =
        if (SrStore.save(context, book.id, page, bitmap, request.model!!.name, book.key,
                request.fingerprint, execution, generation)) SrOutcome.stored()
        else SrOutcome.fail(SrFailReason.CANCELLED)

    private suspend fun awaitIdle() {
        withTimeout(5_000) { while (reader.srChapterJobs.value.isNotEmpty()) delay(10) }
    }

    @Test
    fun detachingUiKeepsChapterWorkAndRecords_andReleasesDecodedInputs() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        process = { page, bitmap, request, execution, generation ->
            started.complete(Unit)
            finish.await()
            save(page, bitmap, request, execution, generation)
        }
        try {
            assertTrue(reader.startSrChapterJob(0, listOf(0)))
            withTimeout(5_000) { started.await() }
            events.uiAttached = false
            assertFalse(inputs.single().isRecycled)
            finish.complete(Unit)
            awaitIdle()
            assertEquals(setOf(0), reader.srPages())
            assertEquals(ImportedPageSr.STATE_SUCCESS, reader.srRecords().single().state)
            assertEquals(listOf(0 to true, 0 to false), events.progress.toList())
            assertTrue(inputs.single().isRecycled)
            assertNotNull(pages.loadFull(0)?.also { it.recycle() })
        } finally { finish.complete(Unit) }
    }

    @Test
    fun cancellationRetainsStoppingChapterUntilCleanup_andThenStartsTheNext() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        process = { page, bitmap, request, execution, generation ->
            if (page == 0) {
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { stopping.complete(Unit); cleanup.await() } }
            } else save(page, bitmap, request, execution, generation)
        }
        try {
            reader.startSrChapterJob(0, listOf(0))
            withTimeout(5_000) { started.await() }
            reader.startSrChapterJob(1, listOf(1))
            reader.cancelSrChapterJob(0)
            withTimeout(5_000) { stopping.await() }
            assertTrue(reader.srChapterJobs.value.first().stopping)
            assertTrue(reader.srChapterJobs.value.last().queued)
            assertEquals(1, inputs.size)
            assertFalse(inputs.single().isRecycled)
            cleanup.complete(Unit)
            awaitIdle()
            assertEquals(setOf(1), reader.srPages())
            assertTrue(inputs.all { it.isRecycled })
            assertEquals(listOf(0 to true, 0 to false, 1 to true, 1 to false), events.progress.toList())
        } finally { cleanup.complete(Unit) }
    }

    @Test
    fun baseChangesInvalidateRendering_andBorrowedBitmapsSurviveEviction() = runBlocking {
        reader.startSrChapterJob(0, listOf(0))
        awaitIdle()
        val original = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        try {
            val base = reader.srBaseFor(0, original, original.width)!!
            assertSame(base.bitmap, reader.cachedBase(0))
            events.changed.clear()
            reader.toggleSrBase(0)
            assertEquals(listOf(0), events.changed.toList())
            assertNull(reader.cachedBase(0))
            assertTrue(reader.hasSrResult(0))
            assertFalse(base.bitmap.isRecycled)
            reader.toggleSrBase(0)
            reader.warmSrBase(0)
            val cached = reader.cachedBase(0)!!
            assertTrue(reader.deleteSrResult(0, dropRecord = true))
            assertNull(reader.cachedBase(0))
            assertTrue(reader.srPages().isEmpty())
            assertTrue(reader.srRecords().isEmpty())
            assertTrue(events.invalidated.contains(0))
            assertTrue(events.webtoonCleared > 0)
            assertFalse(cached.isRecycled)
        } finally { original.recycle() }
    }

    @Test
    fun facadeUnbindRestoresItsOwnedSource_andClearsActivityCallbacks() = runBlocking {
        reader.startSrChapterJob(0, listOf(0))
        awaitIdle()
        val facade = ReaderTranslationController(context, manga, scope)
        var uiLoads = 0
        var uiRefreshes = 0
        facade.bind(
            loadFull = {
                uiLoads++
                Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
            },
            currentPage = { 0 },
            pageCount = { 2 },
        )
        facade.onVisual = { uiRefreshes++ }
        try {
            facade.warmSrBase(0)
            assertEquals(1, uiLoads)
            facade.invalidateRenders()
            facade.unbindUi()
            facade.warmSrBase(0)
            assertNotNull(facade.cachedDisplayBitmap(0))
            assertEquals("Unbound work must not retain the Activity loader", 1, uiLoads)
            facade.toggleSrBase(0)
            assertEquals(0, uiRefreshes)
            assertTrue(facade.hasSrResult(0))
        } finally { facade.shutdownAndJoin() }
    }

}
