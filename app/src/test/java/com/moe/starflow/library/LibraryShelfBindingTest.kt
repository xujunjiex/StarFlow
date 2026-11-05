package com.moe.starflow.library

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import android.widget.ProgressBar
import com.moe.starflow.R
import org.robolectric.shadows.ShadowAlertDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryShelfBindingTest {
    class Shelf : Fragment() {
        var active = true
        var books: List<com.moe.starflow.mangaimport.data.ImportedManga> = emptyList()
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            LinearLayout(requireContext()).apply {
                addView(LinearLayout(context).apply { addView(TextView(context)) })
                addView(ProgressBar(context)); addView(TextView(context)); addView(View(context))
            }
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val host = view as ViewGroup
            LibraryShelfBinding.observe(this, "manga", (host.getChildAt(0) as ViewGroup).getChildAt(0) as TextView,
                host.getChildAt(1) as ProgressBar, host.getChildAt(2) as TextView, host.getChildAt(3),
                { books = ImportedMangaStore.load(requireContext()) }, {}, isActive = { active })
        }
    }

    @Test fun hiddenShelfDoesNotStartAnAutomaticScanAheadOfTheVisibleTab() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "hidden-shelf-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        LibraryScanFixtures.zip(File(root, "manga_import/one.cbz"), "001.png" to LibraryScanFixtures.png())
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val shelf = Shelf().apply { active = false }
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, shelf).commitNow()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, ImportedMangaStore.load(app).size)
            activity.pause().stop()
            shelf.active = true
            activity.start().resume()
            awaitIdle { ImportedMangaStore.load(app).size == 1 && !LibraryScanner.state.value.running }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun enteringAndReenteringTheShelfScansNewSourcesAutomatically() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "shelf-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        LibraryScanFixtures.zip(File(root, "manga_import/one.cbz"), "001.png" to LibraryScanFixtures.png())
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val shelf = Shelf()
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, shelf).commitNow()
        try {
            awaitIdle { ImportedMangaStore.load(app).size == 1 && !LibraryScanner.state.value.running }
            activity.pause().stop()
            LibraryScanFixtures.zip(File(root, "manga_import/two.cbz"), "001.png" to LibraryScanFixtures.png())
            activity.start().resume()
            awaitIdle { ImportedMangaStore.load(app).size == 2 && !LibraryScanner.state.value.running }
            assertEquals(2, ImportedMangaStore.load(app).size)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun saveErrorsAndResumeNeverOpenRecoveryUntilTheUserRequestsDetails() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "shelf-errors-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        val session = LibrarySessions.current(app)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val shelf = Shelf()
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, shelf).commitNow()
        try {
            awaitIdle { LibraryScanner.state.value.location == session.locationId && !LibraryScanner.state.value.running }
            listOf(false, true).forEach { metadata ->
                session.saveState.value = LibrarySaveState(error = "Cannot write files", metadataOnlyError = metadata)
                shadowOf(Looper.getMainLooper()).idle()
                val status = ((shelf.requireView() as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)
                assertEquals(if (metadata) View.GONE else View.VISIBLE, status.visibility)
                assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-storage"))
                assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-report"))
                activity.pause().stop().start().resume()
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-report"))
            }
            awaitIdle { !LibraryScanner.state.value.running }
            session.saveState.value = LibrarySaveState(error = "Cannot write files")
            shadowOf(Looper.getMainLooper()).idle()
            val status = ((shelf.requireView() as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)
            status.performClick()
            assertNotNull(activity.get().supportFragmentManager.findFragmentByTag("library-report"))
            assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-storage"))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun descriptionSaveFailureRefreshesItsCardWithoutAHeaderErrorAndRetryClearsIt() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "description-card-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        val session = LibrarySessions.current(app)
        val source = File(root, "manga_import/Book").apply { mkdirs() }
        ImportedMangaStore.add(app, com.moe.starflow.mangaimport.data.ImportedManga(1, "Book", source.path,
            false, coverPath = null, pageCount = 0, addedAt = 1))
        session.flush()
        val book = ImportedMangaStore.load(app).single()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val shelf = Shelf().apply { active = false }
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, shelf).commitNow()
        val sidecar = File(source, "starflow.json").apply { writeText("unrelated") }
        try {
            ImportedMangaStore.editDescription(app, book, "Manual note")
            session.flush()
            val status = ((shelf.requireView() as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as TextView
            awaitIdle { !shelf.books.single().descriptionFileError.isNullOrBlank() && status.visibility == View.GONE }
            assertEquals(View.GONE, status.visibility)
            assertFalse(status.text.contains("Unrecognized"))
            // An unrelated source-identity failure can still use the header without
            // pulling this work's description error back into the global message.
            val otherUuid = session.uuid("manga", 2)
            val blockedIdentity = File(root, ".starflow/books/$otherUuid/source.json").apply { mkdirs() }
            val other = File(root, "manga_import/Other").apply { mkdirs() }
            ImportedMangaStore.add(app, com.moe.starflow.mangaimport.data.ImportedManga(2, "Other", other.path,
                false, coverPath = null, pageCount = 0, addedAt = 2))
            assertTrue(session.flushPending().failures.any { it.component != "metadata" })
            awaitIdle { status.visibility == View.VISIBLE && status.text.contains("source.json") }
            assertTrue(status.text.contains("source.json"))
            assertFalse(status.text.contains("Unrecognized"))
            assertNull(shelf.books.single { it.bookUuid == otherUuid }.descriptionFileError)
            assertTrue(blockedIdentity.delete())
            sidecar.delete()
            session.flush()
            awaitIdle { shelf.books.single { it.bookUuid == book.bookUuid }.descriptionFileError == null && status.visibility == View.GONE }
            assertEquals(View.GONE, status.visibility)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun storageAndFailedTargetOnlyOfferDestructionAfterAnAdvancedClick() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "shelf-target-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        val active = LibrarySessions.current(app)
        active.saveState.value = LibrarySaveState(error = "Save failed")
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val dialog = LibraryStorageDialog()
        dialog.showNow(activity.get().supportFragmentManager, "library-storage")
        try {
            assertEquals(View.GONE, dialog.requireDialog().findViewById<View>(R.id.library_rebuild).visibility)
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            val target = android.net.Uri.parse("content://missing.provider/tree/target-book-library")
            dialog.change(target)
            awaitIdle { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            val details = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(app.getString(R.string.confirm), details.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
            assertSame(active, LibrarySessions.current(app))
            details.dismiss()
            dialog.requireDialog().findViewById<View>(R.id.library_advanced).performClick()
            dialog.requireDialog().findViewById<View>(R.id.library_rebuild).performClick()
            val confirm = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(app.getString(R.string.library_reinitialize_action), confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
            assertTrue(confirm.findViewById<TextView>(android.R.id.message).text.contains("target-book-library"))
            confirm.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            assertSame(active, LibrarySessions.current(app))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun backgroundReportsAreDeferredAndConsumedOnlyAfterResuming() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "shelf-report-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        val session = LibrarySessions.current(app)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val shelf = Shelf().apply { active = false }
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, shelf).commitNow()
        activity.pause().stop()
        val issue = LibraryScanIssue(LibraryIssueScope.BOOK, "Inspect", "bad.cbz", "Bad package",
            outcome = LibraryBookOutcome.NOT_ADDED)
        val report = LibraryScanState(session.locationId, "manga", issues = listOf(issue), failures = listOf("Bad package"))
        LibraryScanner.deferReport(report)
        LibrarySessions.changes.value += 1
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-report"))
            shelf.active = true
            activity.start().resume()
            awaitIdle { activity.get().supportFragmentManager.findFragmentByTag("library-report") != null && !LibraryScanner.state.value.running }
            assertNull(LibraryScanner.consumeReport(session.locationId, "manga"))
            val visible = activity.get().supportFragmentManager.findFragmentByTag("library-report") as LibraryReportDialog
            assertTrue(visible.requireArguments().getString("details")!!.contains("bad.cbz"))
            visible.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.get().supportFragmentManager.findFragmentByTag("library-report"))
            // Reports from a replaced generation cannot be delivered to the new library.
            LibraryScanner.deferReport(report)
            LibraryScanner.discardReports(session.locationId)
            assertNull(LibraryScanner.consumeReport(session.locationId, "manga"))
        } finally { activity.pause().stop().destroy() }
    }

    private fun awaitIdle(complete: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (complete()) return
            Thread.sleep(10)
        }
        throw AssertionError("Automatic shelf scan did not complete")
    }
}
