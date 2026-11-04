package com.moe.starflow.data

import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 渲染指纹只用于缓存失效：同参数必须同值，任一渲染参数变化必须变值。 */
class OverlayRenderFingerprintTest {

    private fun config(
        fontSize: Float = 16f,
        autoFit: Boolean = true,
        textColor: Int = 0,
        bgColor: Int = 0,
        textDirection: TextDirection = TextDirection.VERTICAL_RL,
        showCacheMarker: Boolean = false,
        horizontalAlign: TextAlign = TextAlign.CENTER,
        trackingRatio: Float = 0.2f,
        leadingRatio: Float = 0.2f,
        mergeOverlap: Boolean = false
    ) = TranslationCacheManager.OverlayConfig(
        fontSize = fontSize,
        autoFit = autoFit,
        textColor = textColor,
        bgColor = bgColor,
        textDirection = textDirection,
        showCacheMarker = showCacheMarker,
        horizontalAlign = horizontalAlign,
        trackingRatio = trackingRatio,
        leadingRatio = leadingRatio,
        mergeOverlap = mergeOverlap
    )

    private fun fingerprint(
        fontSize: Float = 16f,
        autoFit: Boolean = true,
        textColor: Int = 0,
        bgColor: Int = 0,
        textDirection: TextDirection = TextDirection.VERTICAL_RL,
        showCacheMarker: Boolean = false,
        horizontalAlign: TextAlign = TextAlign.CENTER,
        trackingRatio: Float = 0.2f,
        leadingRatio: Float = 0.2f,
        mergeOverlap: Boolean = false,
        fontName: String = ""
    ) = TranslationCacheManager.overlayRenderFingerprint(
        config(
            fontSize, autoFit, textColor, bgColor, textDirection,
            showCacheMarker, horizontalAlign, trackingRatio, leadingRatio, mergeOverlap
        ),
        fontName
    )

    @Test
    fun sameRenderParams_produceSameFingerprint() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun anyRenderParamChange_changesFingerprint() {
        val base = fingerprint()
        assertNotEquals("字号", base, fingerprint(fontSize = 18f))
        assertNotEquals("自动字号", base, fingerprint(autoFit = false))
        assertNotEquals("文字色", base, fingerprint(textColor = 1))
        assertNotEquals("背景色", base, fingerprint(bgColor = 1))
        assertNotEquals("方向", base, fingerprint(textDirection = TextDirection.HORIZONTAL))
        assertNotEquals("缓存标记", base, fingerprint(showCacheMarker = true))
        assertNotEquals("对齐", base, fingerprint(horizontalAlign = TextAlign.LEFT))
        assertNotEquals("字距", base, fingerprint(trackingRatio = 0f))
        assertNotEquals("行距", base, fingerprint(leadingRatio = 0f))
        assertNotEquals("合并", base, fingerprint(mergeOverlap = true))
        assertNotEquals("字体", base, fingerprint(fontName = "custom.ttf"))
    }
}
