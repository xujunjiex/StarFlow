package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTranslationInfoTest {

    @Test
    fun sourceSupported_usesSourceLangs() {
        assertTrue(ReaderTranslationInfo.isSourceSupported("ja", setOf("ja", "en")))
        assertFalse(ReaderTranslationInfo.isSourceSupported("ru", setOf("ja", "en")))
    }

    @Test
    fun targetSupported_hymt2_whitelist() {
        // Hy-MT2 白名单：zh 在 38 种内，sv 不在
        assertTrue(ReaderTranslationInfo.isTargetSupported("zh", isHyMt2 = true, supportedCodes = setOf("zh", "ja"), disabledTargets = emptySet()))
        assertFalse(ReaderTranslationInfo.isTargetSupported("sv", isHyMt2 = true, supportedCodes = setOf("zh", "ja"), disabledTargets = emptySet()))
    }

    @Test
    fun targetSupported_api_allAllowed() {
        // 非 Hy-MT2（NLLB/API）：30 种池内全支持
        assertTrue(ReaderTranslationInfo.isTargetSupported("zh", isHyMt2 = false, supportedCodes = emptySet(), disabledTargets = emptySet()))
        assertTrue(ReaderTranslationInfo.isTargetSupported("sv", isHyMt2 = false, supportedCodes = emptySet(), disabledTargets = emptySet()))
    }
}