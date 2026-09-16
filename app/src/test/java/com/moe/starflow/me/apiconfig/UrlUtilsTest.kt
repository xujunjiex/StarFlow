package com.moe.starflow.me.apiconfig

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `UrlUtils.normalizeUrl` / `validateUrl` 的特征化测试。
 *
 * 用户自建 API 常填成 `192.168.1.5:8081/v1` 这种裸 host:port，标准化规则错了
 * 就会拼出畸形 URL，表现为「测试连接失败」但看不出原因。
 */
@RunWith(RobolectricTestRunner::class)
class UrlUtilsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun missingSchemeGetsHttps() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl(ctx, "example.com"))
        assertEquals("https://192.168.1.5:8081/v1", UrlUtils.normalizeUrl(ctx, "192.168.1.5:8081/v1"))
    }

    /** 已带 http:// 的**不能**被升级成 https（用户自建的内网服务多半没有 TLS） */
    @Test
    fun explicitHttpSchemeIsPreserved() {
        assertEquals("http://192.168.1.5:8081", UrlUtils.normalizeUrl(ctx, "http://192.168.1.5:8081"))
    }

    @Test
    fun httpsSchemeIsPreserved() {
        assertEquals("https://api.example.com/v1", UrlUtils.normalizeUrl(ctx, "https://api.example.com/v1"))
    }

    @Test
    fun trailingSlashesAreStripped() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl(ctx, "https://example.com/"))
        assertEquals("https://example.com/v1", UrlUtils.normalizeUrl(ctx, "https://example.com/v1///"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl(ctx, "  https://example.com  "))
    }

    @Test
    fun blankInputThrows() {
        assertThrows { UrlUtils.normalizeUrl(ctx, "") }
        assertThrows { UrlUtils.normalizeUrl(ctx, "   ") }
    }

    /** 没有 host 的输入必须拒绝，不能拼出一个"看起来能连"的 URL 交出去 */
    @Test
    fun inputWithoutHostThrows() {
        assertThrows { UrlUtils.normalizeUrl(ctx, "http://") }
        assertThrows { UrlUtils.normalizeUrl(ctx, "https://") }
    }

    @Test
    fun validateUrlMirrorsNormalize() {
        assertTrue(UrlUtils.validateUrl(ctx, "example.com"))
        assertTrue(UrlUtils.validateUrl(ctx, "http://192.168.1.5:8081"))
        assertFalse(UrlUtils.validateUrl(ctx, ""))
        assertFalse(UrlUtils.validateUrl(ctx, "   "))
        assertFalse(UrlUtils.validateUrl(ctx, "http://"))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("应当抛 IllegalArgumentException，但没有")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
