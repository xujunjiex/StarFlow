package com.moe.starflow.sr

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 「超分失败原因」与「提示文案」的守卫（Robolectric，要读真实 `strings.xml`）。
 *
 * 用户口径（2026-10 真机反馈原话）：
 * > 「当前无法超分，提示让我检查模型和尺寸太大；不要搞这么模糊的提示信息，
 * >  到底是什么原因无法超分写清楚！！」
 *
 * 所以这里锁三件事：
 * 1. **每个失败原因都有非空文案**（新增枚举项忘了加字符串 → 用户看到空白提示）
 * 2. **文案互不相同**（两条原因写成同一句话 = 又回到"糊在一起"）
 * 3. **不允许再出现那句糊话**（"请确认已选择并下载超分模型，或该页分辨率超出上限"）
 */
@RunWith(RobolectricTestRunner::class)
class SrOutcomeTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun everyFailReasonHasItsOwnMessage() {
        val texts = mutableMapOf<SrFailReason, String>()
        for (reason in SrFailReason.entries) {
            assertNotEquals("$reason 没有登记文案资源", 0, reason.messageRes)
            // 带参数的文案直接用 message() 拿不到（要传参），这里只校验**资源能解析出来且非空**
            val raw = ctx.getString(reason.messageRes)
            assertTrue("$reason 的文案是空的", raw.isNotBlank())
            texts[reason] = raw
        }
        // 文案必须能区分原因：允许个别措辞相似，但不允许两条**完全相同**
        val dup = texts.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue("这些原因用了同一句文案，等于没区分：$dup", dup.isEmpty())
    }

    @Test
    fun vagueOldMessageIsGone() {
        // 上一版就是这句话，把"没选模型"和"图太大"糊在一起 —— 不许回来
        val all = SrFailReason.entries.joinToString("\n") { ctx.getString(it.messageRes) }
        assertFalse(
            "不允许再出现“请确认已选择并下载超分模型，或该页分辨率超出上限”这种把两种原因糊在一起的提示",
            all.contains("请确认已选择并下载超分模型"),
        )
        // 反过来：尺寸类原因必须**带上上限与实测数字**（文案里得有占位符）
        val size = ctx.getString(SrFailReason.SOURCE_TOO_LARGE.messageRes)
        assertTrue("尺寸类提示必须带占位符（实际像素/上限）：$size", size.contains("%1\$d") && size.contains("%4\$.1f"))
    }

    @Test
    fun messageAppendsTechnicalDetail() {
        val o = SrOutcome.fail(SrFailReason.INFERENCE_FAILED, "output 100x100, expected 120x120")
        val msg = o.message(ctx)
        assertTrue("原因文案要在：$msg", msg.contains(ctx.getString(SrFailReason.INFERENCE_FAILED.messageRes)))
        assertTrue("技术细节要跟在后面（用户复制出来就能定位）：$msg", msg.contains("output 100x100, expected 120x120"))
        // 没有细节时不能留一个空行
        val plain = SrOutcome.fail(SrFailReason.BUSY).message(ctx)
        assertEquals(ctx.getString(SrFailReason.BUSY.messageRes), plain)
    }

    @Test
    fun okOutcomeHasNoReason() {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val o = SrOutcome.ok(bmp)
        assertTrue(o.ok)
        assertEquals(null, o.reason)
        assertEquals("", o.message(ctx))
    }
}
