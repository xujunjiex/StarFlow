package com.moe.starflow.library

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.mangaimport.ImportMangaFragment
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryShelfImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun importProgressStaysAboveTheShelfWithoutAddingOrDuplicatingBookRows() {
        val app = RuntimeEnvironment.getApplication()
        val root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        com.bumptech.glide.Glide.init(app, com.bumptech.glide.GlideBuilder().setDiskCache { com.bumptech.glide.load.engine.cache.DiskCacheAdapter() })
        LibraryScanFixtures.zip(File(root, "manga_import/existing.cbz"), "001.png" to LibraryScanFixtures.png())
        runBlocking { LibraryScanner.scan(app, "manga") }
        val file = temp.newFile("new.cbz")
        LibraryScanFixtures.zip(file, "001.png" to LibraryScanFixtures.png())
        val uri = Uri.parse("content://import-progress/new.cbz")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(file.readBytes()) {
            private fun waitForCopy() {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Copy was not released" }
            }
            override fun read(): Int { waitForCopy(); return super.read() }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int { waitForCopy(); return super.read(bytes, offset, count) }
        }
        shadowOf(app.contentResolver).registerInputStream(uri, stream)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.Base_Theme_MT)
        activity.setup()
        val fragment = ImportMangaFragment()
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        try {
            val shelf = fragment.requireView().findViewById<RecyclerView>(R.id.recyclerView)
            awaitIdle { shelf.adapter?.itemCount == 1 && !LibraryScanner.state.value.running }
            ImportManager.importArchives(app, listOf(uri))
            val status = fragment.requireView().findViewById<TextView>(R.id.library_status)
            val cancel = fragment.requireView().findViewById<View>(R.id.library_import_cancel)
            awaitIdle { entered.count == 0L && status.visibility == View.VISIBLE && status.text.toString().contains("Importing") }
            assertEquals(1, shelf.adapter!!.itemCount)
            assertEquals(View.VISIBLE, cancel.visibility)
            cancel.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.dismiss()
            release.countDown()
            awaitIdle { ImportManager.tasks.value.isEmpty() && shelf.adapter!!.itemCount == 2 }
            assertEquals(2, shelf.adapter!!.itemCount)
        } finally {
            release.countDown()
            awaitIdle { ImportManager.tasks.value.isEmpty() }
            activity.pause().stop().destroy()
            com.bumptech.glide.Glide.tearDown()
        }
    }

    private fun awaitIdle(complete: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (complete()) return
            Thread.sleep(10)
        }
        throw AssertionError("Shelf/import state did not settle")
    }
}
