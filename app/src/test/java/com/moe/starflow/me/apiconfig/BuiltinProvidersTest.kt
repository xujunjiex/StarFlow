package com.moe.starflow.me.apiconfig

import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 API 提供商定义的**持久化契约**测试。
 *
 * ⚠️ `OpenAIProviderConfig.name`（火山引擎 / 智谱AI / DeepSeek / 通义千问）是**持久化身份 key**：
 * `BuiltInProviderMod` 靠它匹配用户已存的配置（API Key、提示词、模型选择）。
 * 把它改成英文或别的叫法，老用户的配置会**静默失配**（不报错，表现为"设置被重置成默认"）。
 *
 * 显示名要走 `nameRes` + `displayName(context)`，不要动 `name`。
 * 本测试就是这条约束的守卫 —— 如果它红了，先读上面这段再动手。
 */
class BuiltinProvidersTest {

    /** 顺序和取值都是契约：这份列表是用户配置的匹配键 */
    private val expectedNames = listOf("火山引擎", "智谱AI", "DeepSeek", "通义千问")

    @Test
    fun providerNamesAreStablePersistentKeys() {
        assertEquals(
            "内置厂商 name 是持久化身份 key，改名会让老用户配置静默失配 —— 显示名请改 nameRes",
            expectedNames,
            BuiltinProviders.providers.map { it.name }
        )
    }

    @Test
    fun providerNamesAreUnique() {
        val names = BuiltinProviders.providers.map { it.name }
        assertEquals("name 不能重复，否则配置会互相覆盖", names.size, names.toSet().size)
    }

    @Test
    fun everyProviderIsMarkedBuiltinAndHasDisplayNameRes() {
        for (p in BuiltinProviders.providers) {
            assertEquals(
                "${p.name} 必须是内置类型",
                OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN, p.providerType
            )
            assertNotEquals("${p.name} 必须配 nameRes（本地化显示名）", 0, p.nameRes)
        }
    }

    @Test
    fun everyProviderHasUsableBaseUrlAndModels() {
        for (p in BuiltinProviders.providers) {
            assertTrue("${p.name} 缺 baseUrl", p.baseUrl.startsWith("https://"))
            assertTrue("${p.name} 缺 consoleUrl", p.consoleUrl.isNotEmpty())

            if (p.models.isEmpty()) {
                // 不预置模型的厂商（DeepSeek / 通义千问）：models 为空、modelName 为空，
                // 等用户手动添加或点「获取模型列表」拉取
                assertEquals("${p.name} 不预置模型时 modelName 必须为空", "", p.modelName)
                continue
            }
            assertTrue("${p.name} 的 models 不能为空", p.models.isNotEmpty())
            assertTrue(
                "${p.name} 的 selectedModelIndex 越界（${p.selectedModelIndex} / ${p.models.size}）",
                p.selectedModelIndex in p.models.indices
            )
            assertEquals(
                "${p.name} 的 modelName 必须与所选下标一致",
                p.models[p.selectedModelIndex], p.modelName
            )
        }
    }

    /**
     * 四个内置厂商都提供「获取模型列表」；其中 DeepSeek / 通义千问**不预置**模型（初始为空），
     * 火山引擎 / 智谱AI 保留预置列表当兵底。
     */
    @Test
    fun everyProviderSupportsFetchAndOnlyTwoSkipPresets() {
        assertTrue(
            "四个内置厂商都应支持获取模型列表",
            BuiltinProviders.providers.all { it.supportsModelFetch }
        )
        val byName = BuiltinProviders.providers.associateBy { it.name }

        for (name in listOf("DeepSeek", "通义千问")) {
            val p = byName.getValue(name)
            assertEquals("$name 不应预置任何模型", emptyList<String>(), p.models)
            assertEquals("$name 无预置时 modelName 必须为空", "", p.modelName)
        }
        for (name in listOf("火山引擎", "智谱AI")) {
            val p = byName.getValue(name)
            assertTrue("$name 应保留预置列表", p.models.isNotEmpty())
            assertEquals(
                "$name 的 modelName 必须与所选下标一致",
                p.models[p.selectedModelIndex], p.modelName
            )
        }
    }

    /** 每个内置厂商的默认模型必须就是 models[selectedModelIndex]（面板显示 == 引擎调用） */
    @Test
    fun defaultModelNameMatchesSelectedIndex() {
        for (p in BuiltinProviders.providers) {
            if (p.models.isEmpty()) continue
            assertEquals(
                "${p.name} 的 modelName 与 models[selectedModelIndex] 不一致",
                p.models[p.selectedModelIndex], p.modelName
            )
        }
    }

    /** 续写类型必须落在发送层的 prefill 白名单内，否则永远不会发 prefill */
    @Test
    fun continuationTypesAreWithinThePrefillWhitelist() {
        val whitelist = setOf(
            OpenAIProviderConfig.CONTINUATION_STANDARD,
            OpenAIProviderConfig.CONTINUATION_PARTIAL,
            OpenAIProviderConfig.CONTINUATION_PREFIX,
            OpenAIProviderConfig.CONTINUATION_JSON
        )
        for (p in BuiltinProviders.providers) {
            assertTrue(
                "${p.name} 的 continuationType='${p.continuationType}' 不在发送层白名单里",
                p.continuationType in whitelist
            )
        }
    }

    /** 各厂商的续写方式是与服务端约定的，不能随手改（改了会 hang 或格式出错） */
    @Test
    fun continuationTypePerProviderIsPinned() {
        val byName = BuiltinProviders.providers.associateBy { it.name }
        assertEquals(
            OpenAIProviderConfig.CONTINUATION_STANDARD,
            byName.getValue("火山引擎").continuationType
        )
        assertEquals(
            OpenAIProviderConfig.CONTINUATION_JSON,
            byName.getValue("智谱AI").continuationType
        )
        assertEquals(
            OpenAIProviderConfig.CONTINUATION_PREFIX,
            byName.getValue("DeepSeek").continuationType
        )
        assertEquals(
            OpenAIProviderConfig.CONTINUATION_PARTIAL,
            byName.getValue("通义千问").continuationType
        )
    }

    /** DeepSeek 推理模型默认会思考 → 内置默认强制关闭以保翻译速度；其余跟随模型默认 */
    @Test
    fun onlyDeepSeekDefaultsToThinkingDisabled() {
        for (p in BuiltinProviders.providers) {
            val expected = if (p.name == "DeepSeek") {
                OpenAIProviderConfig.THINKING_FORCE_DISABLED
            } else {
                OpenAIProviderConfig.THINKING_DEFAULT
            }
            assertEquals("${p.name} 的 thinkingMode 默认值", expected, p.thinkingMode)
        }
    }

    /** 每个内置厂商都必须带默认提示词，否则用户清空后没有回退值 */
    @Test
    fun everyProviderCarriesDefaultPrompts() {
        for (p in BuiltinProviders.providers) {
            assertTrue("${p.name} 缺 defaultSystemPrompt", p.defaultSystemPrompt.isNotEmpty())
            assertTrue("${p.name} 缺 defaultUserPrompt", p.defaultUserPrompt.isNotEmpty())
            assertTrue("${p.name} 缺 defaultMangaSystemPrompt", p.defaultMangaSystemPrompt.isNotEmpty())
            assertTrue("${p.name} 缺 defaultMangaUserPrompt", p.defaultMangaUserPrompt.isNotEmpty())
            assertTrue(
                "${p.name} 的默认提示词必须含 usetolang 占位符",
                p.defaultSystemPrompt.contains("usetolang")
            )
            assertTrue(
                "${p.name} 的默认用户提示词必须含 usesourcetext 占位符",
                p.defaultUserPrompt.contains("usesourcetext")
            )
        }
    }

    /** 智谱只保留三个免费模型，各自标注能力；顺序即下标语义，改动会影响老用户的已存选择 */
    @Test
    fun zhipuKeepsOnlyLabelledFreeModels() {
        val zhipu = BuiltinProviders.providers.first { it.name == "智谱AI" }
        assertEquals(
            listOf("glm-4-flash-250414", "glm-4.6v-flash", "glm-4.7-flash"),
            zhipu.models
        )
        // 标注存的是字符串资源 id（值文案见 values/values-zh，随应用语言渲染）
        assertEquals(R.string.model_label_free_text, zhipu.modelLabels["glm-4-flash-250414"])
        assertEquals(R.string.model_label_free_vision, zhipu.modelLabels["glm-4.6v-flash"])
        assertEquals(R.string.model_label_free_thinking, zhipu.modelLabels["glm-4.7-flash"])
        assertEquals("三个免费模型都要有标注", zhipu.models.size, zhipu.modelLabels.size)
        assertEquals("默认用第一个（文本）", "glm-4-flash-250414", zhipu.modelName)
    }

    /** 标注不能指向不存在的模型 —— 改了 models 忘了改 labels 会静默丢标注 */
    @Test
    fun modelLabelsOnlyReferenceExistingModels() {
        for (p in BuiltinProviders.providers) {
            for (key in p.modelLabels.keys) {
                assertTrue(
                    "${p.name} 的 modelLabels 指向了不在 models 里的 '$key'",
                    key in p.models
                )
            }
        }
    }

    /** 智谱走 JSON 结构化输出，它的漫画提示词要求返回 translations 数组 */
    @Test
    fun zhipuMangaPromptRequestsJsonArray() {
        val zhipu = BuiltinProviders.providers.first { it.name == "智谱AI" }
        assertTrue(
            "智谱默认漫画提示词必须要求 JSON 数组输出",
            zhipu.defaultMangaSystemPrompt.contains("translations")
        )
    }
}
