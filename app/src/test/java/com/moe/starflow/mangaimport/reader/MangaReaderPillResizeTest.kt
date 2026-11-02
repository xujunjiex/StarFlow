package com.moe.starflow.mangaimport.reader

import android.view.View
import com.moe.starflow.R
import com.moe.starflow.databinding.ActivityMangaReaderBinding
import com.moe.starflow.mangaimport.data.MangaChapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
class MangaReaderPillResizeTest {
    private val chapter = MangaChapter(1, "Long chapter title ".repeat(40), 0, 1)

    // Only bind the real reader chrome: page loading and translation are unrelated to resizing it.
    private fun reader(mode: Int): Pair<MangaReaderActivity, ActivityMangaReaderBinding> {
        val activity = Robolectric.buildActivity(MangaReaderActivity::class.java).get()
        activity.setTheme(R.style.Theme_MT)
        val binding = ActivityMangaReaderBinding.inflate(activity.layoutInflater)
        ReflectionHelpers.setField(activity, "binding", binding)
        ReflectionHelpers.setField(activity, "chapters", listOf(chapter))
        ReflectionHelpers.setField(activity, "mode", mode)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setupOverlays")
        return activity to binding
    }

    private fun layout(binding: ActivityMangaReaderBinding, width: Int) {
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, width, 600)
    }

    private fun updatePageLabel(activity: MangaReaderActivity, binding: ActivityMangaReaderBinding) {
        binding.tvPageIndicator.text = ReflectionHelpers.callInstanceMethod<CharSequence>(
            activity, "chapterPillText", ClassParameter.from(MangaChapter::class.java, chapter))
    }

    @Test fun firstLayoutReplacesTheWidthChosenBeforeTheWindowWasMeasured() {
        val (activity, binding) = reader(0)
        updatePageLabel(activity, binding)

        layout(binding, 800)

        assertEquals(ReaderPillLayout.maxPillWidthPx(800, activity.resources.displayMetrics.density),
            binding.tvPageIndicator.maxWidth)
    }

    @Test fun pagedReaderResizesThePillWithoutSelectingAnotherPage() = assertResizesWithoutPaging(0)

    @Test fun webtoonReaderResizesThePillWithoutScrolling() = assertResizesWithoutPaging(3)

    private fun assertResizesWithoutPaging(mode: Int) {
        val (activity, binding) = reader(mode)
        layout(binding, 800)
        updatePageLabel(activity, binding)
        val wideText = binding.tvPageIndicator.text.toString()
        val wideMax = binding.tvPageIndicator.maxWidth

        layout(binding, 360)

        assertEquals(ReaderPillLayout.maxPillWidthPx(360, activity.resources.displayMetrics.density),
            binding.tvPageIndicator.maxWidth)
        assertTrue(binding.tvPageIndicator.text.length < wideText.length)
        val counter = activity.getString(R.string.reader_page_indicator_chapter, "", 1, 1)
        assertTrue(binding.tvPageIndicator.text.toString().endsWith(counter))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "currentPage"))

        layout(binding, 800)

        assertEquals(wideMax, binding.tvPageIndicator.maxWidth)
        assertEquals(wideText, binding.tvPageIndicator.text.toString())
    }
}
