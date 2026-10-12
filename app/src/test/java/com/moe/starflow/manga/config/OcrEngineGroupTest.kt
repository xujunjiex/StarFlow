package com.moe.starflow.manga.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineGroupTest {
    private val latin = setOf("fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af")
    private val deva = setOf("hi","mr","ne")

    @Test fun allLangs_is30Inclusive() {
        assertEquals(30, OcrEngineGroup.ALL_LANGS.size)
        assertEquals(listOf("zh","zh-TW","en","ja","ko","ru","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","hi","mr","ne","ca","af"), OcrEngineGroup.ALL_LANGS)
    }
    @Test fun mlkit_langs() {
        val g = OcrEngineGroup.MLKIT
        assertTrue(g.sourceLangs.contains("zh") && g.sourceLangs.contains("ko"))
        assertTrue(g.sourceLangs.containsAll(latin) && g.sourceLangs.containsAll(deva))
        assertFalse(g.sourceLangs.contains("ru"))  // ML Kit v2 无 Cyrillic 脚本
    }
    @Test fun v6_noKoRu() {
        val g = OcrEngineGroup.PP_OCR_V6
        assertFalse(g.sourceLangs.contains("ko"))
        assertFalse(g.sourceLangs.contains("ru"))
        assertTrue(g.sourceLangs.contains("fr"))
    }
    @Test fun v5_sixLangs() {
        assertEquals(setOf("zh","zh-TW","en","ja","ko","ru"), OcrEngineGroup.PP_OCR_V5.sourceLangs)
    }
    @Test fun manga_onlyJa() {
        assertEquals(setOf("ja"), OcrEngineGroup.RT_MANGA.sourceLangs)
    }
    @Test fun gameMappings() {
        assertEquals(0, OcrEngineGroup.MLKIT.gameEngine)
        assertEquals(3, OcrEngineGroup.PP_OCR_V6.gameEngine)
        assertEquals(1, OcrEngineGroup.PP_OCR_V5.gameEngine)
        assertEquals(0, OcrEngineGroup.RT_MANGA.gameEngine)
    }
}
