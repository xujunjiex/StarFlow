package com.moe.starflow.library

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.ui.MangaGridAdapter
import com.moe.starflow.mangaimport.ui.DisplayMode
import com.moe.starflow.me.about.FAQPage
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.shelf.NovelShelfAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookRecoveryUiTest {
    private val activity = Robolectric.buildActivity(FragmentActivity::class.java)
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "recovery-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        runBlocking { LibrarySessions.prepare(app) }
        com.bumptech.glide.Glide.init(app, com.bumptech.glide.GlideBuilder().setDiskCache { com.bumptech.glide.load.engine.cache.DiskCacheAdapter() })
        activity.get().setTheme(R.style.Base_Theme_MT); activity.setup()
    }
    @After fun teardown() { activity.pause().stop().destroy(); com.bumptech.glide.Glide.tearDown() }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun recoveryDialogExposesSelectableIdentityAndTargetsTheRecoveryFaqCard() {
        val ctx = activity.get()
        val session = LibrarySessions.current(ctx)
        ImportedMangaStore.add(ctx, ImportedManga(1, "Missing", session.local("manga_import/Book/ch1.pdf").path,
            false, true, null, 9, 1))
        val book = ImportedMangaStore.load(ctx).single()
        BookRecoveryDialog.show(ctx, BookRef(session.locationId, "manga", book.bookUuid))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val views = all(dialog.window!!.decorView)
        assertTrue(views.filterIsInstance<TextView>().any { it.isTextSelectable && it.text.contains(book.bookUuid) })
        assertTrue(views.filterIsInstance<TextView>().any { it.isTextSelectable && it.text.contains("manga_import/Book/ch1.pdf") })
        views.filterIsInstance<TextView>().first { it.text.toString() == ctx.getString(R.string.book_recovery_faq) }.performClick()
        assertEquals(BookRecoveryDialog.FAQ_CARD, shadowOf(ctx).nextStartedActivity.getStringExtra("faq_card"))
        views.filterIsInstance<TextView>().first { it.text.toString() == ctx.getString(R.string.book_data_title) }.performClick()
        val data = shadowOf(ctx).nextStartedActivity
        assertEquals(BookDataActivity::class.java.name, data.component!!.className)
        assertEquals(book.bookUuid, data.getStringExtra("uuid"))
        assertEquals(session.locationId, data.getStringExtra("location"))
        dialog.dismiss()
    }
    @Test fun targetedFaqIsExpandedAfterNavigation() {
        activity.get().intent.putExtra("faq_card", BookRecoveryDialog.FAQ_CARD)
        val faq = FAQPage()
        activity.get().supportFragmentManager.beginTransaction().add(android.R.id.content, faq).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val list = faq.requireView().findViewById<RecyclerView>(R.id.card_recyclerview)
        assertEquals(1, list.adapter!!.getItemViewType(list.adapter!!.itemCount - 1))
    }
    @Test fun onlyMissingMangaAndNovelCardsOfferRecordExportInBothLayouts() {
        val ctx = activity.get()
        val recycler = RecyclerView(ctx).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx) }
        val book = ImportedManga(1, "Book", "/missing", false, false, null, 2, 1, lost = true)
        for (mode in listOf(DisplayMode.GRID, DisplayMode.DETAILED_LIST)) {
            var exports = 0
            val adapter = MangaGridAdapter(mode, {}, { _, _ -> }, onExportRecords = { exports++ })
            adapter.submitList(listOf(book))
            val holder = adapter.onCreateViewHolder(recycler, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            val button = holder.itemView.findViewById<View>(R.id.btn_export_records)
            assertTrue(button is android.widget.ImageButton)
            assertEquals(ctx.getString(R.string.book_records_export), button.contentDescription)
            assertEquals((48 * ctx.resources.displayMetrics.density).toInt(), button.layoutParams.width)
            assertEquals(button.layoutParams.width, button.layoutParams.height)
            if (mode == DisplayMode.GRID) {
                val layout = button.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.END, layout.gravity)
            } else {
                val layout = button.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                assertEquals(0, layout.endToEnd); assertEquals(0, layout.bottomToBottom)
            }
            assertEquals(View.VISIBLE, button.visibility); button.performClick(); assertEquals(1, exports)
            adapter.submitList(listOf(book.copy(lost = false)))
            shadowOf(Looper.getMainLooper()).idle()
            // First-list callbacks are synchronous; subsequent lists use DiffUtil.
            val deadline = System.nanoTime() + 2_000_000_000
            while (adapter.currentList.first().lost && System.nanoTime() < deadline) { Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }
            adapter.onBindViewHolder(holder, 0); assertEquals(View.GONE, button.visibility)
            val novel = ImportedNovel(id = 1, title = "Novel", localRoot = "/missing", format = NovelFormat.TXT,
                coverPath = null, chapterCount = 1, addedAt = 1, lost = true)
            val novels = NovelShelfAdapter(mode, {}, { _, _ -> }, onExportRecords = { exports++ })
            novels.submitList(listOf(novel))
            val novelHolder = novels.onCreateViewHolder(recycler, novels.getItemViewType(0))
            novels.onBindViewHolder(novelHolder, 0)
            assertEquals(View.VISIBLE, novelHolder.itemView.findViewById<View>(R.id.btn_export_records).visibility)
            val healthy = NovelShelfAdapter(mode, {}, { _, _ -> })
            healthy.submitList(listOf(novel.copy(lost = false)))
            val healthyHolder = healthy.onCreateViewHolder(recycler, healthy.getItemViewType(0))
            healthy.onBindViewHolder(healthyHolder, 0)
            assertEquals(View.GONE, healthyHolder.itemView.findViewById<View>(R.id.btn_export_records).visibility)
        }
    }
}
