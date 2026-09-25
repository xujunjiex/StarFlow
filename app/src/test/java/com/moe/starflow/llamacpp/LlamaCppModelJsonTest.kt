package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 模型清单（JSON）编解码回归守卫。
 *
 * 清单是本地文件，可能被用户手工改坏；解析必须容错（坏条目跳过），
 * 且 per-model 参数与目录（内置在 models/、导入在 llamacpp/）都要能原样往返。
 *
 * ⚠️ 必须跑 Robolectric：Android 的 `org.json` 在纯 JVM 单测里是**桩实现**
 *    （`unitTests.returnDefaultValues = true` → `optJSONArray` 返回 null、`toString` 返回 null），
 *    不挂 Robolectric 的话这里会"看起来空清单"甚至 NPE。
 */
@RunWith(RobolectricTestRunner::class)
class LlamaCppModelJsonTest {

    private fun sample(source: LlamaCppModelSource, id: String, fileName: String, dirName: String) =
        LlamaCppModel(
            id = id,
            displayName = "测试模型 $id",
            fileName = fileName,
            sizeBytes = 461_860_800L,
            md5 = "9b96b598c36e9ffbfcafedd919b2fd54",
            retaggedMd5 = "aaaa1111bbbb2222cccc3333dddd4444",
            source = source,
            dirName = dirName,
            builtinModelKey = if (source == LlamaCppModelSource.BUILTIN) "HY_MT2_GROUP" else null,
            hyProfile = source == LlamaCppModelSource.BUILTIN,
            params = LlamaCppParams.forSource(source).copy(temperature = 0.42f, topK = 7, maxTokens = 1234),
        )

    @Test
    fun roundTrip_preservesEverything() {
        val models = listOf(
            sample(LlamaCppModelSource.BUILTIN, LlamaCppModelStore.BUILTIN_HYMT2_ID, "Hy-MT2-1.8B-1.25Bit.gguf", "models"),
            sample(LlamaCppModelSource.IMPORTED, "imported:Qwen3-0.6B-Q4_K_M.gguf", "Qwen3-0.6B-Q4_K_M.gguf", "llamacpp"),
        )
        val decoded = LlamaCppJson.decode(LlamaCppJson.encode(models))

        assertEquals(2, decoded.size)
        val builtin = decoded.first { it.source == LlamaCppModelSource.BUILTIN }
        assertEquals(LlamaCppModelStore.BUILTIN_HYMT2_ID, builtin.id)
        assertEquals("models", builtin.dirName)
        assertEquals("HY_MT2_GROUP", builtin.builtinModelKey)
        assertTrue(builtin.hyProfile)
        assertEquals("aaaa1111bbbb2222cccc3333dddd4444", builtin.retaggedMd5)
        assertEquals(0.42f, builtin.params.temperature, 0.0001f)
        assertEquals(7, builtin.params.topK)
        assertEquals(1234, builtin.params.maxTokens)

        val imported = decoded.first { it.source == LlamaCppModelSource.IMPORTED }
        assertEquals("llamacpp", imported.dirName)
        assertNull(imported.builtinModelKey)
        assertTrue(!imported.hyProfile)
        assertEquals(461_860_800L, imported.sizeBytes)
    }

    @Test
    fun decode_skipsBrokenEntriesInsteadOfFailingWholeManifest() {
        val json = """
            {"version":1,"models":[
              {"id":"imported:a.gguf","fileName":"a.gguf","source":"IMPORTED"},
              {"fileName":"missing-id.gguf"},
              {"id":"imported:b.gguf"},
              {"id":"builtin:hymt2","fileName":"Hy-MT2-1.8B-1.25Bit.gguf","source":"BUILTIN","dirName":"models"}
            ]}
        """.trimIndent()
        val decoded = LlamaCppJson.decode(json)
        // 缺 id / 缺 fileName 的两条被跳过，其余保留
        assertEquals(listOf("imported:a.gguf", "builtin:hymt2"), decoded.map { it.id })
    }

    @Test
    fun decode_toleratesUnknownSourceAndMissingParams() {
        val json = """{"models":[{"id":"x","fileName":"x.gguf","source":"SOMETHING_NEW"}]}"""
        val decoded = LlamaCppJson.decode(json)
        assertEquals(1, decoded.size)
        // 未知来源回退 IMPORTED（最保守：不套用内置 Hy-MT2 的白名单与专用通道）
        assertEquals(LlamaCppModelSource.IMPORTED, decoded[0].source)
        assertEquals("llamacpp", decoded[0].dirName)
        // 参数缺失 → 用该来源的默认值补齐，不能是 0/空
        assertEquals(LlamaCppParams.forSource(LlamaCppModelSource.IMPORTED).temperature, decoded[0].params.temperature, 0.0001f)
        assertTrue(decoded[0].params.promptTemplate.isNotBlank())
    }

    @Test
    fun decode_garbageReturnsEmptyList() {
        assertTrue(LlamaCppJson.decode("not json at all").isEmpty())
        assertTrue(LlamaCppJson.decode("{}").isEmpty())
    }

    /**
     * `systemPrompt` 键**缺失**时要回落默认值。
     *
     * 坑：`org.json` 的 `optString` 对缺失键返回空串（不是 null），所以 `?: defaults` 永远不兜底。
     * 将来某版清单少了这个字段，通用模型的 system 段会被静默清空 —— 不是崩溃，是翻译质量悄悄变差。
     */
    @Test
    fun decode_missingSystemPromptFallsBackToDefault() {
        val json = """{"models":[{"id":"x","fileName":"x.gguf","source":"IMPORTED","params":{"temperature":0.6}}]}"""
        val decoded = LlamaCppJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(LlamaCppParams.DEFAULT_SYSTEM_PROMPT, decoded[0].params.systemPrompt)
    }

    /** 显式写空的 system 是**用户的意图**（不想要 system 段），不能被当成缺失而塞回默认值。 */
    @Test
    fun decode_explicitEmptySystemPromptIsKept() {
        val json = """{"models":[{"id":"x","fileName":"x.gguf","source":"IMPORTED","params":{"systemPrompt":""}}]}"""
        val decoded = LlamaCppJson.decode(json)
        assertEquals("", decoded[0].params.systemPrompt)
    }
}
