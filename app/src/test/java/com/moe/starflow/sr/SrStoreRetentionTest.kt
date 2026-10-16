package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [SrStoreTestOs::class])
class SrStoreRetentionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var book: SrTestBook
    private val bookId: Long get() = book.id
    private val bookKey: String get() = book.key
    private val directory: File get() = book.srDir.apply { mkdirs() }

    @Before
    fun setUp() {
        book = SrTestBook(context)
        directory.listFiles().orEmpty().forEach { it.delete() }
        SrStore.bindPages(bookId, bookKey, listOf("chapter/001.jpg", "chapter/002.jpg"))
    }

    @After
    fun tearDown() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun save(page: Int, model: String = "upconv7", canCommit: () -> Boolean = { true }): Boolean {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return try {
            SrStore.save(context, bookId, page, bitmap, model, bookKey, canCommit = canCommit)
        } finally {
            bitmap.recycle()
        }
    }

    private fun manifest(pageKey: String): File = directory.listFiles().orEmpty().single {
        it.extension == "json" && JSONObject(it.readText()).optString("pageKey") == pageKey
    }

    private fun image(manifest: File): File = File(directory, JSONObject(manifest.readText()).getString("image"))

    @Test
    fun savingAboveFormerCapacityLimitKeepsExistingResult() {
        assertTrue(save(0))
        val originalManifest = manifest("chapter/001.jpg")
        val originalImage = image(originalManifest)
        // Extend only the fixture's logical length; no large bitmap is decoded or allocated.
        RandomAccessFile(originalImage, "rw").use { it.setLength(513L * 1024 * 1024) }
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } > 512L * 1024 * 1024)

        assertTrue(save(1))

        assertTrue("Saving another page must not evict the existing result", originalManifest.isFile)
        assertTrue(originalImage.isFile)
        assertTrue(SrStore.exists(context, bookId, 1, bookKey))
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "webp" })
    }

    @Test
    fun successfulRerunReplacesOnlyTheSameSourcePage() {
        assertTrue(save(0))
        assertTrue(save(1))
        val oldImage = image(manifest("chapter/001.jpg"))
        val otherImage = image(manifest("chapter/002.jpg"))
        val oldRevision = SrStore.infoOf(context, bookId, 0, bookKey)!!.revision

        assertTrue(save(0, "cunet"))

        assertFalse(oldImage.exists())
        assertTrue(otherImage.isFile)
        assertNotEquals(oldRevision, SrStore.infoOf(context, bookId, 0, bookKey)!!.revision)
        assertEquals("cunet", SrStore.modelOf(context, bookId, 0, bookKey))
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "webp" })
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun cancelledReplacementPreservesPreviousResultAndRemovesPendingImage() {
        assertTrue(save(0))
        val oldImage = image(manifest("chapter/001.jpg"))
        val oldRevision = SrStore.infoOf(context, bookId, 0, bookKey)!!.revision
        var commitChecks = 0

        assertFalse(save(0, "cunet") { ++commitChecks == 1 })

        assertTrue(oldImage.isFile)
        assertEquals(oldRevision, SrStore.infoOf(context, bookId, 0, bookKey)!!.revision)
        assertEquals("upconv7", SrStore.modelOf(context, bookId, 0, bookKey))
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun interruptedWritesAndUnreferencedImagesAreStillCleaned() {
        assertTrue(save(0))
        val oldImage = image(manifest("chapter/001.jpg"))
        val orphan = File(directory, "unreferenced.webp").apply { writeText("orphan") }
        val pending = File(directory, "interrupted.webp.tmp").apply { writeText("pending") }

        assertTrue(save(1))

        assertTrue(oldImage.isFile)
        assertFalse(orphan.exists())
        assertFalse(pending.exists())
        assertEquals(4, directory.listFiles().orEmpty().size)
    }

    @Test
    fun reorderedPagesKeepResultsBoundToTheirSourcePages() {
        assertTrue(save(0, "upconv7"))
        assertTrue(save(1, "cunet"))

        SrStore.bindPages(bookId, bookKey, listOf("chapter/002.jpg", "chapter/001.jpg"))

        assertEquals("cunet", SrStore.modelOf(context, bookId, 0, bookKey))
        assertEquals("upconv7", SrStore.modelOf(context, bookId, 1, bookKey))
    }
}

/** Robolectric does not implement Os.rename; exercise the real filesystem commit in these tests. */
@Implements(Os::class)
class SrStoreTestOs {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(oldPath: String, newPath: String) {
            Files.move(
                File(oldPath).toPath(), File(newPath).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
