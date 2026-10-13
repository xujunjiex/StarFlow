package com.moe.starflow.manga.render

import org.junit.Assert.*
import org.junit.Test

class VerticalGlyphRulesTest {
    @Test fun dedicatedVerticalFormsCoverBracketFamiliesAndEllipses() {
        val pairs = mapOf('(' to '︵', '）' to '︶', '[' to '﹇', '}' to '︸', '【' to '︻',
            '〖' to '︗', '〔' to '︹', '〈' to '︿', '》' to '︾', '「' to '﹁', '』' to '﹄',
            '｢' to '﹁', '…' to '︙', '‥' to '︰', '—' to '︱', '＿' to '︳', '、' to '︑')
        pairs.forEach { (source, target) ->
            val glyph = VerticalGlyphRules.resolve(source, hasGlyph = { true })
            assertEquals(target.toString(), glyph.text)
            assertEquals(0f, glyph.rotation, 0f)
            assertFalse(glyph.corner)
        }
    }

    @Test fun absentVerticalGlyphRotatesOriginalShapeWhenDefaultFontAlsoLacksForm() {
        "()（）[]{}〔〕【】〖〗〈〉《》「」『』…‥—–_".forEach {
            val glyph = VerticalGlyphRules.resolve(it, hasGlyph = { false })
            assertEquals(it.toString(), glyph.text)
            assertEquals(90f, glyph.rotation, 0f)
        }
    }

    @Test fun missingCustomGlyphUsesDefaultFontVerticalFormBeforeRotation() {
        val glyph = VerticalGlyphRules.resolve(
            ch = '（',
            hasGlyph = { false },
            hasDefaultGlyph = { it == "︵" }
        )

        assertEquals("︵", glyph.text)
        assertEquals(0f, glyph.rotation, 0f)
        assertTrue(glyph.useDefaultTypeface)
    }

    @Test fun otherQuotesDashesWavesAndProlongedSoundsRotateClockwise() {
        "〘〙〚〛“”‘’〝〞〟«»‹›‐‑‒―-－~～〜〰ーｰ゠".forEach {
            assertEquals(90f, VerticalGlyphRules.resolve(it, hasGlyph = { true }).rotation, 0f)
        }
    }

    @Test fun commasAndStopsUsePositionFallbackRatherThanRotation() {
        ",，、。．.､｡".forEach {
            val glyph = VerticalGlyphRules.resolve(it, hasGlyph = { false })
            assertTrue(glyph.corner)
            assertEquals(0f, glyph.rotation, 0f)
        }
    }

    @Test fun uprightAndAlreadyVerticalCharactersAreNeverRotated() {
        "!?！？‼⁇⁈⁉・中文あいうABC123︵︶﹁﹂︙︰︓︖".forEach {
            val glyph = VerticalGlyphRules.resolve(it, hasGlyph = { false })
            assertEquals(it.toString(), glyph.text)
            assertEquals(0f, glyph.rotation, 0f)
            assertFalse(glyph.corner)
        }
    }
}
