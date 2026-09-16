package translationapi.openaitranslation

import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `OpenAITranslation` 请求构建的特征化测试。
 *
 * 这段逻辑是 CLAUDE.md 里记的「易踩坑重灾区」，此前完全没有测试覆盖：
 * - **assistant prefill 白名单守卫**：用户自定义 API 的 `continuationType` 默认是空字符串，
 *   给不支持续写的服务端发假 prefill 会让它 hang 到 30s 超时。
 *   所以只有 `standard` / `partial` / `prefix` 三种才放行，其余（含空串、未知值）一律不发。
 * - **thinkingMode 三态**：0=跟随模型默认（**必须完全不发参数**）／1=强制关闭／2=强制开启。
 *   0 时若误发 `thinking` 会让硅基流动等严格校验参数的模型直接 400。
 * - **JSON 模式**：智谱走 `response_format`，**不发** prefill。
 * - **上下文历史**：`updateContext(history, enabled)` 决定是否插入 user/assistant 对。
 *
 * ⚠️ `buildRequestBody` 是 private，这里用反射调用。签名叫法改了会在这里报
 * `NoSuchMethodException` —— 那就是提醒你同步更新本文件的契约。
 */
@RunWith(RobolectricTestRunner::class)
class OpenAITranslationRequestBodyTest {

    private fun api(
        continuationType: String = OpenAIProviderConfig.CONTINUATION_NONE,
        prefillContent: String = "",
        thinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT
    ) = OpenAITranslation(
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "SYS usetolang",
        userPrompt = "USR usesourcetext",
        continuationType = continuationType,
        prefillContent = prefillContent,
        thinkingMode = thinkingMode
    )

    /**
     * 与生产路径一致：`translate()` 里是 `buildRequestBody(systemPrompt, userPrompt, thinkingMode)`，
     * 其中 `thinkingMode` 取自**构造字段**。所以这里也反射读同一个字段，
     * 避免测试自己传一个和构造值不同的参数、把两边读成两回事。
     */
    private fun buildBody(
        api: OpenAITranslation,
        systemPrompt: String = "SYS",
        userPrompt: String = "USR"
    ): JSONObject {
        val field = OpenAITranslation::class.java.getDeclaredField("thinkingMode")
        field.isAccessible = true
        val method = OpenAITranslation::class.java.getDeclaredMethod(
            "buildRequestBody",
            String::class.java, String::class.java, Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        val raw = method.invoke(api, systemPrompt, userPrompt, field.getInt(api)) as String
        return JSONObject(raw)
    }

    private fun messages(body: JSONObject): JSONArray = body.getJSONArray("messages")

    private fun roles(body: JSONObject): List<String> {
        val arr = messages(body)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("role") }
    }

    private fun assistantMessageOrNull(body: JSONObject): JSONObject? {
        val arr = messages(body)
        for (i in 0 until arr.length()) {
            val msg = arr.getJSONObject(i)
            if (msg.getString("role") == "assistant") return msg
        }
        return null
    }

    // ── 基本结构 ──

    @Test
    fun messagesStartWithSystemAndEndWithUser() {
        val body = buildBody(api(), systemPrompt = "S", userPrompt = "U")
        assertEquals(listOf("system", "user"), roles(body))
        assertEquals("S", messages(body).getJSONObject(0).getString("content"))
        assertEquals("U", messages(body).getJSONObject(1).getString("content"))
    }

    @Test
    fun basicSamplingFieldsPresent() {
        val body = buildBody(api())
        assertEquals("test-model", body.getString("model"))
        assertEquals(1000, body.getInt("max_tokens"))
        assertEquals(false, body.getBoolean("stream"))
        assertTrue("必须带 temperature", body.has("temperature"))
    }

    // ── thinkingMode 三态 ──

    /** 0 = 跟随模型默认：**完全不发** thinking 参数（发出去会让严格校验的模型 400） */
    @Test
    fun thinkingDefaultOmitsParameterEntirely() {
        val body = buildBody(api(thinkingMode = OpenAIProviderConfig.THINKING_DEFAULT))
        assertFalse("THINKING_DEFAULT 不能发送 thinking 参数", body.has("thinking"))
    }

    @Test
    fun thinkingForceDisabledSendsDisabled() {
        val body = buildBody(api(thinkingMode = OpenAIProviderConfig.THINKING_FORCE_DISABLED))
        assertEquals(
            "disabled",
            body.getJSONObject("thinking").getString("type")
        )
    }

    @Test
    fun thinkingForceEnabledSendsEnabled() {
        val body = buildBody(api(thinkingMode = OpenAIProviderConfig.THINKING_FORCE_ENABLED))
        assertEquals(
            "enabled",
            body.getJSONObject("thinking").getString("type")
        )
    }

    /** 构造时传入的 thinkingMode 必须原样进入请求体（生产路径就是构造字段 → 方法参数） */
    @Test
    fun constructorThinkingModeReachesRequestBody() {
        val body = buildBody(api(thinkingMode = OpenAIProviderConfig.THINKING_FORCE_ENABLED))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
    }

    // ── assistant prefill 白名单守卫 ──

    @Test
    fun prefillStandardAddsAssistantWithoutExtraFlags() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD, prefillContent = "[1]")
        )
        assertEquals(listOf("system", "user", "assistant"), roles(body))
        val assistant = assistantMessageOrNull(body)!!
        assertEquals("[1]", assistant.getString("content"))
        assertFalse("standard 不该带 partial", assistant.has("partial"))
        assertFalse("standard 不该带 prefix", assistant.has("prefix"))
    }

    @Test
    fun prefillPartialAddsPartialFlag() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_PARTIAL, prefillContent = "[1]")
        )
        assertEquals(true, assistantMessageOrNull(body)!!.getBoolean("partial"))
    }

    @Test
    fun prefillPrefixAddsPrefixFlag() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_PREFIX, prefillContent = "[1]")
        )
        assertEquals(true, assistantMessageOrNull(body)!!.getBoolean("prefix"))
    }

    /** ⚠️ 核心守卫：自定义 API 的 continuationType 默认是空串，绝不能因此发出 prefill */
    @Test
    fun emptyContinuationTypeSendsNoPrefill() {
        val body = buildBody(api(continuationType = "", prefillContent = "[1]"))
        assertEquals(listOf("system", "user"), roles(body))
        assertFalse("空 continuationType 必须不发 prefill", body.has("response_format"))
    }

    @Test
    fun unknownContinuationTypeSendsNoPrefill() {
        val body = buildBody(api(continuationType = "whatever", prefillContent = "[1]"))
        assertEquals(listOf("system", "user"), roles(body))
    }

    @Test
    fun noneContinuationTypeSendsNoPrefill() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_NONE, prefillContent = "[1]")
        )
        assertEquals(listOf("system", "user"), roles(body))
    }

    @Test
    fun blankPrefillContentSendsNoAssistantEvenForAllowedType() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD, prefillContent = "")
        )
        assertEquals(listOf("system", "user"), roles(body))
    }

    // ── JSON 模式（智谱）──

    @Test
    fun jsonModeSendsResponseFormatAndNoPrefill() {
        val body = buildBody(
            api(continuationType = OpenAIProviderConfig.CONTINUATION_JSON, prefillContent = "[1]")
        )
        assertEquals(
            "json_object",
            body.getJSONObject("response_format").getString("type")
        )
        assertEquals("JSON 模式靠 response_format 控制，不能发 prefill", listOf("system", "user"), roles(body))
    }

    @Test
    fun nonJsonModeSendsNoResponseFormat() {
        val body = buildBody(api(continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD))
        assertFalse(body.has("response_format"))
    }

    // ── 上下文历史 ──

    @Test
    fun enabledContextInsertsHistoryPairs() {
        val api = api()
        api.updateContext(listOf("原文1" to "译文1", "原文2" to "译文2"), enabled = true)
        val body = buildBody(api, systemPrompt = "S", userPrompt = "U")

        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant", "user"),
            roles(body)
        )
        val arr = messages(body)
        assertEquals("翻译：原文1", arr.getJSONObject(1).getString("content"))
        assertEquals("译文1", arr.getJSONObject(2).getString("content"))
        assertEquals("翻译：原文2", arr.getJSONObject(3).getString("content"))
        assertEquals("译文2", arr.getJSONObject(4).getString("content"))
        assertEquals("U", arr.getJSONObject(5).getString("content"))
    }

    @Test
    fun disabledContextSkipsHistory() {
        val api = api()
        api.updateContext(listOf("原文1" to "译文1"), enabled = false)
        assertEquals(listOf("system", "user"), roles(buildBody(api)))
    }

    @Test
    fun enabledButEmptyContextSkipsHistory() {
        val api = api()
        api.updateContext(emptyList(), enabled = true)
        assertEquals(listOf("system", "user"), roles(buildBody(api)))
    }

    @Test
    fun contextAndPrefillCombineInOrder() {
        val api = api(continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD, prefillContent = "[1]")
        api.updateContext(listOf("原文" to "译文"), enabled = true)
        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant"),
            roles(buildBody(api))
        )
    }

    // ── buildSystemPrompt：上下文前缀 ──

    private fun buildSystemPrompt(api: OpenAITranslation, toLang: String): String {
        val method = OpenAITranslation::class.java
            .getDeclaredMethod("buildSystemPrompt", String::class.java)
        method.isAccessible = true
        return method.invoke(api, toLang) as String
    }

    @Test
    fun systemPromptReplacesTargetLanguagePlaceholder() {
        val api = OpenAITranslation(
            apiKey = "k", model = "m",
            systemPrompt = "翻译为usetolang", userPrompt = "u"
        )
        assertEquals("翻译为中文", buildSystemPrompt(api, "中文"))
    }

    @Test
    fun systemPromptGetsContextPrefixOnlyWhenContextIsOnAndNonEmpty() {
        val api = OpenAITranslation(
            apiKey = "k", model = "m",
            systemPrompt = "P", userPrompt = "u"
        )
        // 上下文关
        assertFalse(buildSystemPrompt(api, "中文").startsWith("根据上下文"))

        // 开了但历史为空 → 也不加前缀
        api.updateContext(emptyList(), enabled = true)
        assertFalse(buildSystemPrompt(api, "中文").startsWith("根据上下文"))

        // 开了且有历史 → 加前缀
        api.updateContext(listOf("a" to "b"), enabled = true)
        val withPrefix = buildSystemPrompt(api, "中文")
        assertTrue(withPrefix.startsWith("根据上下文剧情进行翻译"))
        assertTrue("原提示词必须保留在前缀之后", withPrefix.endsWith("P"))
    }

    // ── buildUserPrompt：占位符替换 ──

    private fun buildUserPrompt(api: OpenAITranslation, text: String, from: String, to: String): String {
        val method = OpenAITranslation::class.java.getDeclaredMethod(
            "buildUserPrompt", String::class.java, String::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(api, text, from, to) as String
    }

    @Test
    fun userPromptReplacesSourceTextPlaceholder() {
        val api = OpenAITranslation(
            apiKey = "k", model = "m",
            systemPrompt = "s", userPrompt = "将以下文本翻译：\n\nusesourcetext"
        )
        val out = buildUserPrompt(api, "こんにちは\n世界", "ja", "zh")
        assertTrue("原文必须被完整替换进模板", out.endsWith("こんにちは\n世界"))
        assertFalse(out.contains("usesourcetext"))
    }

    @Test
    fun userPromptLeavesNoUnreplacedPlaceholders() {
        val api = OpenAITranslation(
            apiKey = "k", model = "m",
            systemPrompt = "s",
            userPrompt = "从usefromlang翻到usetolang：usesourcetext"
        )
        val out = buildUserPrompt(api, "hello", "en", "zh")
        assertFalse("usefromlang 必须被替换", out.contains("usefromlang"))
        assertFalse("usetolang 必须被替换", out.contains("usetolang"))
        assertFalse("usesourcetext 必须被替换", out.contains("usesourcetext"))
        assertTrue(out.contains("hello"))
    }
}
