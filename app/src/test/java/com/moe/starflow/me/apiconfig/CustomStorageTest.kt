package com.moe.starflow.me.apiconfig

import com.moe.starflow.utils.CustomPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 单元测试：内置 API 自定义模型管理
 *
 * 覆盖：
 *  - removeCustomModelAndAdjustIndex 的 4 种下标规则 + 非法输入
 *  - 序列化往返（saveBuiltInProviderMods / loadBuiltInProviderMods）
 *  - 老数据迁移兼容（JSON 中无 customModels 字段）
 */
@RunWith(RobolectricTestRunner::class)
class CustomStorageTest {

    private lateinit var prefs: CustomPreference

    @Before
    fun setUp() {
        prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        // 清空相关 key
        prefs.remove("BuiltIn_Providers_Modifications")
    }

    @After
    fun tearDown() {
        prefs.remove("BuiltIn_Providers_Modifications")
    }

    // ============ removeCustomModelAndAdjustIndex ============
    //
    // displayModels 布局：[p0..p_{presetSize-1}][custom[0]..custom[size-1]]
    // 即自定义区下标范围 [presetSize, presetSize + customModels.size)
    // customIndex = deleteIndex - presetSize

    @Test
    fun remove_selectedBeforeDelete_unchanged() {
        // preset=3, custom=[a,b,c] → display=[p0,p1,p2,a,b,c] (size=6)
        // 自定义区下标：3=a, 4=b, 5=c
        // 选 a (idx=3)，删除 c (idx=5, customIndex=2)
        // selected < deleteIndex → 不变
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 3,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 5,
            selectedIndex = 3
        )
        assertEquals(listOf("a", "b"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedEqualsDelete_keepIndexPointsToNext() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 a (idx=2)，删除 a (idx=2, customIndex=0)
        // selected == deleteIndex → 不变，删除后下标 2 自动指向 b
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 2,
            selectedIndex = 2
        )
        assertEquals(listOf("b", "c"), newCustoms)
        assertEquals(2, newSelected)
    }

    @Test
    fun remove_selectedEqualsDeleteMiddle_keepIndex() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 b (idx=3)，删除 b (idx=3, customIndex=1)
        // selected == deleteIndex → 不变，删除后下标 3 自动指向 c
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 3,
            selectedIndex = 3
        )
        assertEquals(listOf("a", "c"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedEqualsDeleteLastItem_keepIndexClamped() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 c (idx=4)，删除 c (idx=4, customIndex=2)
        // selected == deleteIndex，但删除后下标 4 越界 → 兜底为 3 (b)
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 4,
            selectedIndex = 4
        )
        assertEquals(listOf("a", "b"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedAfterDelete_shiftsBack() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 c (idx=4)，删除 a (idx=2, customIndex=0)
        // selected > deleteIndex → -1 → 3 (指向新位置 c)
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 2,
            selectedIndex = 4
        )
        assertEquals(listOf("b", "c"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_lastCustom_outOfRangeReset() {
        // preset=2, custom=[a] → display=[p0,p1,a] (size=3)
        // 自定义区下标：2=a
        // 选 a (idx=2)，删除 a (idx=2)
        // 删除后 customModels 为空，displaySize=2 (只剩预设)
        // selectedIndex 2 越界，兜底为最后一个预设的下标 1
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a"),
            deleteIndex = 2,
            selectedIndex = 2
        )
        assertEquals(emptyList<String>(), newCustoms)
        assertEquals(1, newSelected) // 兜底到最后一个预设
    }

    @Test
    fun remove_presetIndex_throws() {
        try {
            ConfigurationStorage.removeCustomModelAndAdjustIndex(
                presetSize = 3,
                customModels = listOf("a"),
                deleteIndex = 1, // 指向预设区
                selectedIndex = 0
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot delete preset") == true)
        }
    }

    // ============ 序列化 ============

    @Test
    fun roundtrip_builtInProviderMod_withCustomModels() {
        val mods = listOf(
            BuiltInProviderMod(
                name = "火山引擎",
                apiKey = "k1",
                systemPrompt = null,
                userPrompt = null,
                mangaSystemPrompt = null,
                mangaUserPrompt = null,
                selectedModelIndex = 2,
                customModels = listOf("custom-1", "custom-2")
            )
        )
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertEquals(1, loaded.size)
        val first = loaded[0]
        assertEquals("火山引擎", first.name)
        assertEquals("k1", first.apiKey)
        assertEquals(2, first.selectedModelIndex)
        assertEquals(listOf("custom-1", "custom-2"), first.customModels)
    }

    @Test
    fun migration_oldJson_withoutCustomModelsField_returnsEmpty() {
        // 模拟老数据 JSON：无 customModels 字段
        prefs.setString(
            "BuiltIn_Providers_Modifications",
            """[{"name":"DeepSeek","apiKey":"old-key","selectedModelIndex":1}]"""
        )
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertEquals(1, loaded.size)
        assertEquals("DeepSeek", loaded[0].name)
        assertEquals("old-key", loaded[0].apiKey)
        assertEquals(1, loaded[0].selectedModelIndex)
        assertEquals(emptyList<String>(), loaded[0].customModels) // 迁移默认空
    }

    // ============ 思考模式（thinkingMode） ============

    // ============ 模型名一致性（面板显示 == 引擎实际调用） ============
    //
    // `OpenAIProviderConfig` 同时有 modelName 和 models + selectedModelIndex：
    //   面板显示 models[selectedModelIndex]（OpenAIText）
    //   引擎调用 modelName（TranslatorFactory）
    // 两者必须永远一致。内置列表改版后老用户存的 selectedModelIndex 会越界，
    // 这时**必须把 modelName 与 selectedModelIndex 一起归一** —— 只归一一个的话，
    // 弹窗里没有任何模型高亮，用户看不到自己实际在跑哪个模型。

    @Test
    fun outOfRangeSelectedIndex_fallsBackToFirstModel_indexAndNameStayInSync() {
        // 内置列表改版前存的下标（新列表只有 3 项 → 越界）
        ConfigurationStorage.saveBuiltInProviderMods(
            prefs, listOf(BuiltInProviderMod(name = "智谱AI", selectedModelIndex = 3))
        )
        val zhipu = ConfigurationStorage.loadAllProviders(prefs).first { it.name == "智谱AI" }

        assertEquals("越界下标必须归一到 0", 0, zhipu.selectedModelIndex)
        assertEquals("下标必须落在 models 范围内", zhipu.models[zhipu.selectedModelIndex], zhipu.modelName)
        assertEquals("glm-4-flash-250414", zhipu.modelName)
    }

    @Test
    fun inRangeSelectedIndex_isPreservedAndNameMatchesIndex() {
        ConfigurationStorage.saveBuiltInProviderMods(
            prefs, listOf(BuiltInProviderMod(name = "智谱AI", selectedModelIndex = 2))
        )
        val zhipu = ConfigurationStorage.loadAllProviders(prefs).first { it.name == "智谱AI" }

        assertEquals(2, zhipu.selectedModelIndex)
        assertEquals("glm-4.7-flash", zhipu.modelName)
    }

    @Test
    fun selectedIndexPointingIntoCustomModels_staysConsistent() {
        ConfigurationStorage.saveBuiltInProviderMods(
            prefs,
            listOf(
                BuiltInProviderMod(
                    name = "智谱AI",
                    selectedModelIndex = 3,               // 预设 3 项 → 落进自定义区第一项
                    customModels = listOf("my-finetune")
                )
            )
        )
        val zhipu = ConfigurationStorage.loadAllProviders(prefs).first { it.name == "智谱AI" }

        assertEquals(3, zhipu.selectedModelIndex)
        assertEquals("my-finetune", zhipu.modelName)
    }

    /**
     * 全量兜底：任何来源的 provider，`modelName` 必须等于**展示列表**里 selectedModelIndex 指的那一项。
     *
     * ⚠️ 展示列表 = `provider.models`（预设）**+** `BuiltInProviderMod.customModels`（自定义）——
     * 自定义模型不挂在 config 上，所以 `selectedModelIndex` 完全可能 ≥ `models.size`。
     * 面板（OpenAIText）与引擎（TranslatorFactory→modelName）必须取同一个元素。
     */
    @Test
    fun everyLoadedProviderKeepsIndexAndNameInSync() {
        val mods = listOf(
            BuiltInProviderMod(name = "智谱AI", selectedModelIndex = 99),                    // 越界
            BuiltInProviderMod(name = "DeepSeek", selectedModelIndex = 1),                   // 预设区内
            BuiltInProviderMod(
                name = "火山引擎",
                customModels = listOf("my-finetune"),
                selectedModelIndex = 3                                                        // 落进自定义区
            )
        )
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)
        val customByProvider = mods.associate { it.name to it.customModels }

        for (p in ConfigurationStorage.loadAllProviders(prefs)) {
            val display = p.models + (customByProvider[p.name] ?: emptyList())
            if (display.isEmpty()) {
                // 不预置模型的厂商（DeepSeek）在用户添加之前就是这个状态：不崩、modelName 为空
                assertEquals("${p.name} 无模型时 modelName 必须为空", "", p.modelName)
                continue
            }
            assertTrue(
                "${p.name} 的 selectedModelIndex=${p.selectedModelIndex} 越界（展示列表 ${display.size} 项）",
                p.selectedModelIndex in display.indices
            )
            assertEquals(
                "${p.name} 的面板显示模型与引擎调用模型不一致",
                display[p.selectedModelIndex], p.modelName
            )
        }
    }

    /** DeepSeek 不预置模型：加进自定义模型后，下标与模型名要按展示列表对齐 */
    @Test
    fun deepSeekWithUserAddedModels_staysConsistent() {
        ConfigurationStorage.saveBuiltInProviderMods(
            prefs,
            listOf(
                BuiltInProviderMod(
                    name = "DeepSeek",
                    customModels = listOf("deepseek-flash", "deepseek-v4-pro"),
                    selectedModelIndex = 1
                )
            )
        )
        val ds = ConfigurationStorage.loadAllProviders(prefs).first { it.name == "DeepSeek" }

        assertEquals("预置列表为空", emptyList<String>(), ds.models)
        assertEquals(1, ds.selectedModelIndex)
        assertEquals("deepseek-v4-pro", ds.modelName)
    }

    @Test
    fun roundtrip_builtInProviderMod_withThinkingMode() {
        val mods = listOf(
            BuiltInProviderMod(
                name = "DeepSeek",
                apiKey = "k1",
                selectedModelIndex = 1,
                thinkingMode = OpenAIProviderConfig.THINKING_DEFAULT
            )
        )
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertEquals(1, loaded.size)
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, loaded[0].thinkingMode)
    }

    @Test
    fun migration_oldBuiltinJson_withoutThinkingMode_returnsNull() {
        // 老数据 JSON：无 thinkingMode 字段 → mod.thinkingMode = null → applyMod 回退内置默认
        prefs.setString(
            "BuiltIn_Providers_Modifications",
            """[{"name":"DeepSeek","apiKey":"old-key","selectedModelIndex":1}]"""
        )
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertNull(loaded[0].thinkingMode)
        // DeepSeek 内置默认强制关闭思考，老用户升级后行为不变
        val providers = ConfigurationStorage.loadAllProviders(prefs)
        val deepseek = providers.first { it.name == "DeepSeek" }
        assertEquals(OpenAIProviderConfig.THINKING_FORCE_DISABLED, deepseek.thinkingMode)
    }

    @Test
    fun builtInDefault_deepseekForceDisabled_othersFollowDefault() {
        // 无 mod 时：DeepSeek 默认强制关闭思考（保持旧行为），其余内置跟随模型默认
        val providers = ConfigurationStorage.loadAllProviders(prefs)
        val deepseek = providers.first { it.name == "DeepSeek" }
        assertEquals(OpenAIProviderConfig.THINKING_FORCE_DISABLED, deepseek.thinkingMode)
        val volcano = providers.first { it.name == "火山引擎" }
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, volcano.thinkingMode)
        val zhipu = providers.first { it.name == "智谱AI" }
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, zhipu.thinkingMode)
        val qwen = providers.first { it.name == "通义千问" }
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, qwen.thinkingMode)
    }

    @Test
    fun roundtrip_openAIProvider_withThinkingMode() {
        // 用户自定义 provider 往返：硅基流动默认 0，本地推理模型强制开启 2
        val providers = listOf(
            OpenAIProviderConfig(
                name = "硅基流动",
                apiKey = "k1",
                baseUrl = "https://api.siliconflow.cn/v1",
                modelName = "tencent/Hunyuan-MT-7B",
                systemPrompt = "s",
                userPrompt = "u",
                providerType = OpenAIProviderConfig.PROVIDER_TYPE_USER,
                thinkingMode = OpenAIProviderConfig.THINKING_DEFAULT
            ),
            OpenAIProviderConfig(
                name = "本地推理",
                apiKey = "k2",
                baseUrl = "http://localhost:8080/v1",
                modelName = "qwen-r1",
                systemPrompt = "s",
                userPrompt = "u",
                providerType = OpenAIProviderConfig.PROVIDER_TYPE_USER,
                thinkingMode = OpenAIProviderConfig.THINKING_FORCE_ENABLED
            )
        )
        ConfigurationStorage.saveOpenAIProviders(prefs, providers)
        val loaded = ConfigurationStorage.loadOpenAIProviders(prefs)
        assertEquals(2, loaded.size)
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, loaded[0].thinkingMode)
        assertEquals(OpenAIProviderConfig.THINKING_FORCE_ENABLED, loaded[1].thinkingMode)
    }

    @Test
    fun migration_oldOpenAIProviderJson_withoutThinkingMode_returnsDefault() {
        // 老用户自定义 provider JSON：无 thinkingMode → 默认 0（跟随模型默认，兼容硅基流动）
        prefs.setString(
            "OpenAI_Providers",
            """[{"name":"硅基流动","apiKey":"k","baseUrl":"https://api.siliconflow.cn/v1","modelName":"tencent/Hunyuan-MT-7B","systemPrompt":"s","userPrompt":"u","providerType":"user"}]"""
        )
        val loaded = ConfigurationStorage.loadOpenAIProviders(prefs)
        assertEquals(1, loaded.size)
        assertEquals(OpenAIProviderConfig.THINKING_DEFAULT, loaded[0].thinkingMode)
    }
}
