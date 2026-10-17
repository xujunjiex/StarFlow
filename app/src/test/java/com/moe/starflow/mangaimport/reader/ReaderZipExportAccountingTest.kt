package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * **译文/双语 zip 的页数口径与两个计数**（行为测试，不是源码 grep）。
 *
 * 为什么必须钉住：这条口径用户踩过两代 ——
 * ① 旧口径只遍历 `translatedPages()`（`STATE_SUCCESS`），而**纯图片页 OCR 为空是 `STATE_FAILED`**，
 *    于是这些页在包里**整个消失**，却既不报"跳过"也不报"没有译文"，用户只看到页码不连续；
 * ② 反向的坑同样存在：未译页现在是"页在、只是没译文"，把它算进 `skipped` 会让用户以为下载缺页
 *    （PDF 那条出口当初就是这么错的，`reader_download_done_untranslated` 文案就是为此加的）。
 *
 * 编码器被替换成假实现（`compressRendered` 测试缝）：只验页数/条目名/计数，真实 JPEG 由
 * `Bitmap.compress` 负责，不需要在这里复现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderZipExportAccountingTest {

    @Before
    fun setUp() {
        // 导出每页都会 withContext(Dispatchers.Main) 上报进度；让它内联执行，
        // 否则 Robolectric 的主循环会等测试线程，表现是 Gradle 测试卡住不结束。
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bmp(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun tempZip(): File = File.createTempFile("reader-export-", ".zip").also { it.deleteOnExit() }

    private class Result(val outcome: ExportOutcome, val entries: Set<String>)

    /**
     * 5 页：1/3（0 基）已译，0/2/4 未译；key 是 `ch1/00N.png`。
     */
    private fun export(
        both: Boolean,
        includeUntranslated: Boolean,
        translated: Set<Int> = setOf(1, 3),
        available: (Int) -> Boolean = { true },
        render: (Int) -> Bitmap? = { bmp() },
    ): Result {
        val file = tempZip()
        val outcome = runBlocking {
            ReaderExport.exportToZip(
                pageCount = 5,
                isTranslated = { it in translated },
                isAvailable = available,
                exportKey = { page -> "ch1/00${page + 1}.png" },
                openOriginal = { page -> ByteArrayInputStream("original-$page".toByteArray()) },
                renderTranslated = render,
                both = both,
                includeUntranslated = includeUntranslated,
                translatedSuffix = "_译文",
                tempFile = file,
                onProgress = { _, _ -> },
                compressRendered = { _, out: OutputStream -> out.write(byteArrayOf(0x11, 0x22)) },
            )
        }
        val entries = ZipFile(file).use { zip -> zip.entries().toList().map { it.name }.toSet() }
        return Result(outcome, entries)
    }

    @Test
    fun legacyMode_keepsOnlyTranslatedPages() {
        val r = export(both = false, includeUntranslated = false)
        assertEquals(ExportOutcome.Done(written = 2, skipped = 0, untranslated = 0), r.outcome)
        assertEquals(setOf("ch1/002.jpg", "ch1/004.jpg"), r.entries)
    }

    @Test
    fun includeUntranslated_backfillsMissingPagesWithTheirOriginalName() {
        val r = export(both = false, includeUntranslated = true)
        assertEquals(ExportOutcome.Done(written = 5, skipped = 0, untranslated = 3), r.outcome)
        assertEquals(
            // 已译页出 .jpg，未译页原样搬原名原扩展名（不重编码）→ 包里扩展名是混排的
            setOf("ch1/001.png", "ch1/002.jpg", "ch1/003.png", "ch1/004.jpg", "ch1/005.png"),
            r.entries,
        )
    }

    @Test
    fun bilingual_pairsTranslatedPagesAndKeepsASingleCopyOfUntranslatedOnes() {
        val r = export(both = true, includeUntranslated = true)
        assertEquals(ExportOutcome.Done(written = 5, skipped = 0, untranslated = 3), r.outcome)
        assertEquals(
            setOf(
                "ch1/001.png",
                "ch1/002.png", "ch1/002_译文.jpg",
                "ch1/003.png",
                "ch1/004.png", "ch1/004_译文.jpg",
                "ch1/005.png",
            ),
            r.entries,
        )
    }

    @Test
    fun bilingual_ignoresIncludeUntranslatedAndStillCoversEveryPage() {
        val r = export(both = true, includeUntranslated = false)
        assertEquals(ExportOutcome.Done(written = 5, skipped = 0, untranslated = 3), r.outcome)
        assertEquals(7, r.entries.size)
    }

    @Test
    fun missingSourcePage_countsAsSkippedNotUntranslated() {
        val r = export(both = false, includeUntranslated = true, available = { it != 2 })
        assertEquals(ExportOutcome.Done(written = 4, skipped = 1, untranslated = 2), r.outcome)
        assertFalse("ch1/003.png" in r.entries)
    }

    @Test
    fun legacyMode_doesNotReportMissingUntranslatedPagesAsSkipped() {
        // 旧口径压根不导出未译页：这些页缺失不该算「跳过」，否则用户会以为包不完整
        val r = export(both = false, includeUntranslated = false, available = { it != 4 })
        assertEquals(ExportOutcome.Done(written = 2, skipped = 0, untranslated = 0), r.outcome)
        assertEquals(setOf("ch1/002.jpg", "ch1/004.jpg"), r.entries)
    }

    @Test
    fun translatedSnapshotButRenderFails_countsAsUntranslatedNotSkipped() {
        // 导出开始时快照说"这页有译文"，渲染却拿不到（用户中途删了译文 / 渲染失败）：
        // 页仍在包里（原图回填），必须算"没有译文"而不是"跳过"
        val r = export(both = false, includeUntranslated = true, translated = setOf(0, 1), render = { null })
        assertEquals(ExportOutcome.Done(written = 5, skipped = 0, untranslated = 5), r.outcome)
        assertEquals(
            setOf("ch1/001.png", "ch1/002.png", "ch1/003.png", "ch1/004.png", "ch1/005.png"),
            r.entries,
        )
    }
}
