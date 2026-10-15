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
import org.junit.Assert.assertEquals
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
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            LinearLayout(requireContext()).apply { addView(TextView(context)) }
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            LibraryShelfBinding.observe(this, "manga", (view as ViewGroup).getChildAt(0) as TextView, {}, {}, isActive = { active })
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
