package com.moe.starflow.translate

import android.graphics.Bitmap
import com.moe.starflow.utils.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图片直传的**日志格式契约**（用户口径：「有人用如果出问题，系统要能返回准确的日志信息」）。
 *
 * 这条日志是用户导出 `starflow.log` 后唯一的现场证据，字段名被写进
 * `translate/CLAUDE.md` 的排障表 —— 改字段名/少字段都是**破坏排障**，不是改文案：
 * - 每条请求恰好两条（`→` 开始 + `✓/✗/⏹` 结束）；
 * - 结束行必须带 `provider` / `ctx` / `stage`（失败时）/ 耗时；
 * - 凭证必须脱敏（这里用 `api_key=SECRET` 做哨兵）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageTranslationLogTest {

    private fun linesOf(ctx: String): List<String> =
        LogCollector.getAllLogs()
            .filter { it.tag == ImageTranslationLog.TAG && it.message.contains(ctx) }
            .map { it.message }

    private fun session(ctx: String): ImageTranslationLog.Session {
        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        return ImageTranslationLog.start(
            provider = "baidu",
            requestContext = ctx,
            sourceLanguage = "ja",
            targetLanguage = "zh",
            sourceWidth = 240,
            sourceHeight = 160,
            upload = ImageUpload.Prepared(bmp, owned = false, scaleToOriginal = 2f),
        )
    }

    @Test
    fun `success writes exactly a start line and an outcome line`() {
        val ctx = "test-ok-${System.nanoTime()}"
        val s = session(ctx)
        s.uploaded(4096)
        s.http(200)
        s.ok(blocks = 3, textOnly = false, chars = 42)
        // 再调一次不能刷出第三条（避免"重复回调"把日志预算吃光）
        s.ok(blocks = 3, textOnly = false, chars = 42)

        val lines = linesOf(ctx)
        assertEquals("一次请求两行：开始 + 结束", 2, lines.size)
        assertTrue(lines[0].startsWith("→ provider=baidu"))
        assertTrue(lines[0].contains("lang=ja->zh"))
        assertTrue(lines[0].contains("src=240x160"))
        assertTrue(lines[0].contains("upload=120x80"))
        assertTrue(lines[0].contains("scale=2.000"))
        assertTrue("结束行要能看出成功了什么", lines[1].startsWith("✓ provider=baidu"))
        assertTrue(lines[1].contains("http=200"))
        assertTrue(lines[1].contains("blocks=3"))
        assertTrue(lines[1].contains("textOnly=false"))
        assertTrue(lines[1].contains("chars=42"))
        assertTrue(lines[1].contains("bytes=4KB"))
        assertTrue(lines[1].contains("ms"))
    }

    @Test
    fun `failure line carries stage provider code http and redacted message`() {
        val ctx = "test-fail-${System.nanoTime()}"
        val s = session(ctx)
        s.uploaded(1024)
        s.http(200)
        s.fail(
            ImageApiException(
                message = "Baidu image translation API error 54001: invalid sign api_key=SECRET",
                stage = "provider",
                providerCode = "54001",
                httpStatus = 200,
            ),
        )

        val line = linesOf(ctx).last()
        assertTrue(line.startsWith("✗ provider=baidu"))
        assertTrue("失败层要能一眼看出来", line.contains("stage=provider"))
        assertTrue("服务商错误码要单独成字段", line.contains("code=54001"))
        assertTrue(line.contains("http=200"))
        assertTrue(line.contains("msg=\""))
        assertFalse("凭证不能进日志", line.contains("SECRET"))
        assertTrue("凭证要打码而不是整段删除", line.contains("[redacted]"))
    }

    @Test
    fun `cancellation is logged as cancelled not as failure`() {
        val ctx = "test-cancel-${System.nanoTime()}"
        val s = session(ctx)
        s.cancelled()
        s.fail(IllegalStateException("不该再记一条"))

        val lines = linesOf(ctx)
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("⏹ provider=baidu"))
    }

    @Test
    fun `unknown exception still records a stage`() {
        val ctx = "test-unknown-${System.nanoTime()}"
        val s = session(ctx)
        s.fail(java.io.IOException("socket closed"), fallbackStage = "request")

        val line = linesOf(ctx).last()
        assertTrue(line.contains("stage=request"))
        assertTrue("没有错误码时用 - 占位，保持字段对齐", line.contains("code=-"))
        assertTrue(line.contains("http=-"))
    }

    @Test
    fun `config missing is throttled per host instead of per page`() {
        // 未配置图片接口时阅读器会「每页都试一次」—— 不节流会把 300 行窗口全刷成同一句
        val host = "cfghost${System.nanoTime()}"
        ImageTranslationLog.configMissing("baidu", "$host page=1", "Baidu_Translate_ACCOUNT")
        ImageTranslationLog.configMissing("baidu", "$host page=2", "Baidu_Translate_ACCOUNT")
        ImageTranslationLog.configMissing("baidu", "$host page=3", "Baidu_Translate_ACCOUNT")

        val lines = linesOf(host)
        assertEquals("同一宿主 + 同一原因只记一条", 1, lines.size)
        assertTrue(lines[0].contains("stage=config"))
        assertTrue(lines[0].contains("missing=Baidu_Translate_ACCOUNT"))
    }
}
