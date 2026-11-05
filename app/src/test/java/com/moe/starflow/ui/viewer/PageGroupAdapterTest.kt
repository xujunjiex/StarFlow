package com.moe.starflow.ui.viewer

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.PageCacheEntity
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.manga.config.TranslationTextRules
import com.moe.starflow.utils.BitmapLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageGroupAdapterTest {
    @get:Rule val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = TestScope(dispatcher)
    private val cache = BitmapLruCache(20)
    private val pending = mutableListOf<Continuation<Bitmap?>>()
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: PageGroupAdapter
    private lateinit var holder: PageGroupAdapter.ViewHolder

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MT)
        prefs = context.getSharedPreferences("viewer_render_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val original = File(temp.root, "original.png")
        val source = bitmap()
        original.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        val entry = HistoryEntry(
            id = 7, type = TranslationCacheManager.MODE_MANGA, sourceText = "原文",
            translatedText = "译文", imagePath = null, thumbnailPath = null,
            sourceLang = "ja", targetLang = "zh", translatorName = "test",
            originalImagePath = original.absolutePath, bubbleRects = "[]"
        )
        val page = PageCacheEntity(
            historyId = entry.id, pHash = 0, mode = entry.type, lastAccessedAt = 0,
            createdAt = 0, cropRight = 2, cropBottom = 2
        )
        // 只替换耗时渲染：配置读取、绑定、缓存和结果交付均执行真实实现。
        val manager = Mockito.mock(TranslationCacheManager::class.java) { call ->
            if (call.method.name == "renderOverlay") {
                @Suppress("UNCHECKED_CAST")
                pending += call.rawArguments.last() as Continuation<Bitmap?>
                COROUTINE_SUSPENDED
            } else {
                Answers.CALLS_REAL_METHODS.answer(call)
            }
        }
        adapter = PageGroupAdapter(
            listOf(MangaViewerActivity.PageGroup(entry)), mapOf(entry.id to page), cache, manager,
            { TranslationCacheManager.OverlayMode.TRANSLATED }, scope, prefs
        )
        holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
    }

    @After
    fun teardown() {
        scope.cancel()
        holder.imageView.setImageDrawable(null)
        cache.clear()
        Dispatchers.resetMain()
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun bind() {
        adapter.onBindViewHolder(holder, 0)
        scheduler.runCurrent()
    }

    private fun displayed(): Bitmap? = (holder.imageView.drawable as? BitmapDrawable)?.bitmap

    @Test
    fun currentRenderIsDisplayedAndCached() {
        bind()
        val result = bitmap()
        pending.single().resume(result)
        scheduler.runCurrent()

        assertSame(result, displayed())
        assertEquals(1, cache.size)
        assertFalse(result.isRecycled)
    }

    @Test
    fun currentFailureDisplaysItsFallbackWithoutCachingIt() {
        bind()
        pending.single().resumeWithException(IllegalStateException("current render failed"))
        scheduler.runCurrent()

        assertEquals(2, displayed()?.width)
        assertEquals(2, displayed()?.height)
        assertEquals(0, cache.size)
        assertFalse(displayed()!!.isRecycled)
    }

    @Test
    fun oldFontRenderCompletingAfterRebindCannotReplaceTheNewImage() {
        bind()
        prefs.edit().putFloat("Manga_Font_Size", 24f).commit()
        bind()
        val newest = bitmap()
        pending[1].resume(newest)
        scheduler.runCurrent()
        val old = bitmap()
        pending[0].resume(old)
        scheduler.runCurrent()

        assertSame(newest, displayed())
        assertEquals(1, cache.size)
        assertTrue(old.isRecycled)
        assertFalse(newest.isRecycled)
    }

    @Test
    fun rulesChangedBeforeRebindRejectThePendingResult() {
        bind()
        prefs.edit().putString(TranslationTextRules.KEY_REPLACEMENTS, "[{\"from\":\"a\",\"to\":\"b\"}]").commit()
        val old = bitmap()
        pending.single().resume(old)
        scheduler.runCurrent()

        assertNull(displayed())
        assertEquals(0, cache.size)
        assertTrue(old.isRecycled)
    }

    @Test
    fun lateFailureCannotReplaceTheNewImageWithItsFallback() {
        bind()
        prefs.edit().putFloat("Manga_Font_Size", 24f).commit()
        bind()
        val newest = bitmap()
        pending[1].resume(newest)
        scheduler.runCurrent()
        pending[0].resumeWithException(IllegalStateException("old render failed"))
        scheduler.runCurrent()

        assertSame(newest, displayed())
        assertEquals(1, cache.size)
        assertFalse(newest.isRecycled)
    }
}
