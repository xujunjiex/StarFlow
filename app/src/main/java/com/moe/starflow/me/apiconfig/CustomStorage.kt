/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.me.apiconfig
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.utils.CustomPreference
import org.json.JSONArray
import org.json.JSONObject

// 文本翻译数据模型
data class CustomTextAPIConfig(
    val method: String,
    val baseUrl: String,
    val queryParams: List<KeyValuePair>,
    val headers: List<KeyValuePair>,
    val jsonBody: List<KeyValuePair>,
    val jsonResponsePath: String
)

// 图片翻译数据模型
data class CustomPicAPIConfig(
    val method: String,                     // GET 或 POST
    val contentType: String?,               // POST时的Content-Type
    val baseUrl: String,                    // 基础URL
    val queryParams: List<KeyValuePair>,    // GET请求的查询参数
    val headers: List<KeyValuePair>,        // 请求头
    val body: List<KeyValuePair>,           // POST请求的body (JSON或Form)
    val jsonResponsePath: String            // JSON响应解析路径
)

data class KeyValuePair(
    val key: String,
    val value: String
)

// 带名称的文本翻译配置
data class NamedTextAPIConfig(
    val name: String,
    val config: CustomTextAPIConfig
)

// 带名称的图片翻译配置
data class NamedPicAPIConfig(
    val name: String,
    val config: CustomPicAPIConfig
)

// OpenAI兼容API厂商配置
data class OpenAIProviderConfig(
    /**
     * ⚠️ 持久化身份 key：`BuiltInProviderMod.name` 靠它匹配用户已存的修改（CustomStorage.loadAllProviders）。
     * 内置厂商这里是中文名，**不能改成英文**，否则老用户的 API Key/提示词全部失配。展示一律用 [displayName]。
     */
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val systemPrompt: String,
    val userPrompt: String,
    // 内置API相关字段
    val providerType: String = PROVIDER_TYPE_USER,
    /** 内置厂商本地化显示名资源；0 = 用户自建（名称是用户输入的，直接显示 name） */
    @androidx.annotation.StringRes val nameRes: Int = 0,
    val models: List<String> = emptyList(),
    val defaultSystemPrompt: String = "",
    val defaultUserPrompt: String = "",
    val selectedModelIndex: Int = 0,
    val apiFormat: String = FORMAT_CHAT_COMPLETIONS,
    val consoleUrl: String = "",
    val continuationType: String = CONTINUATION_NONE,
    val mangaSystemPrompt: String = "",
    val mangaUserPrompt: String = "",
    val defaultMangaSystemPrompt: String = "",
    val defaultMangaUserPrompt: String = "",
    val autoAppendPath: Boolean = true,
    /** 思考模式：0=跟随模型默认（不发送 thinking 参数）/ 1=强制关闭 / 2=强制开启 */
    val thinkingMode: Int = 0
) {
    companion object {
        const val PROVIDER_TYPE_BUILTIN = "builtin"
        const val PROVIDER_TYPE_USER = "user"
        const val FORMAT_CHAT_COMPLETIONS = "chat_completions"
        const val FORMAT_RESPONSES = "responses"
        const val CONTINUATION_NONE = "none"
        const val CONTINUATION_STANDARD = "standard"
        const val CONTINUATION_PARTIAL = "partial"
        const val CONTINUATION_PREFIX = "prefix"
        const val CONTINUATION_JSON = "json"
        const val THINKING_DEFAULT = 0      // 跟随模型默认，不发送 thinking 参数
        const val THINKING_FORCE_DISABLED = 1  // 强制关闭思考
        const val THINKING_FORCE_ENABLED = 2   // 强制开启思考
    }

    val isBuiltin: Boolean get() = providerType == PROVIDER_TYPE_BUILTIN
    val isResponsesFormat: Boolean get() = apiFormat == FORMAT_RESPONSES

    /** 面向用户的厂商名：内置走资源（中英各一份），用户自建直接用其输入的名称。 */
    fun displayName(context: android.content.Context): String =
        if (nameRes != 0) context.getString(nameRes) else name
}

// 内置API用户修改数据模型
data class BuiltInProviderMod(
    val name: String,
    val apiKey: String = "",
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val mangaSystemPrompt: String? = null,
    val mangaUserPrompt: String? = null,
    val selectedModelIndex: Int = 0,
    /** 用户自定义添加的模型名称列表（仅展示在 PopupWindow 中；预设模型不可加此处） */
    val customModels: List<String> = emptyList(),
    /** 思考模式 diff（null=沿用内置默认）；0=跟随模型默认 / 1=强制关闭 / 2=强制开启 */
    val thinkingMode: Int? = null
)

// SharedPreferences存储
object ConfigurationStorage {
    private const val KEY_METHOD = "method"
    private const val KEY_CONTENT_TYPE = "contentType"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_QUERY_PARAMS = "queryParams"
    private const val KEY_HEADERS = "headers"
    private const val KEY_BODY = "body"
    private const val KEY_JSON_BODY = "jsonBody"
    private const val KEY_JSON_RESPONSE_PATH = "jsonResponsePath"
    private const val KEY_PAIR_KEY = "key"
    private const val KEY_PAIR_VALUE = "value"
    private const val KEY_NAME = "name"
    const val MAX_CUSTOM_API_COUNT = 10

    // 解析键值对列表的辅助函数
    private fun parseKeyValuePairs(jsonArray: JSONArray): List<KeyValuePair> {
        val pairs = mutableListOf<KeyValuePair>()
        for (i in 0 until jsonArray.length()) {
            val pairObject = jsonArray.getJSONObject(i)
            pairs.add(KeyValuePair(
                key = pairObject.getString(KEY_PAIR_KEY),
                value = pairObject.getString(KEY_PAIR_VALUE)
            ))
        }
        return pairs
    }

    fun saveTextConfig(prefs: CustomPreference, config: CustomTextAPIConfig, apiCode: Int) {
        try {
            // 创建主JSONObject
            val jsonObject = JSONObject().apply {
                put(KEY_METHOD, config.method)
                put(KEY_BASE_URL, config.baseUrl)
                put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath)

                // 转换查询参数列表
                put(KEY_QUERY_PARAMS, JSONArray().apply {
                    config.queryParams.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求头列表
                put(KEY_HEADERS, JSONArray().apply {
                    config.headers.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换JSON请求体列表
                put(KEY_JSON_BODY, JSONArray().apply {
                    config.jsonBody.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })
            }

            // 保存到SharedPreferences
            prefs.setString("Custom_Text_API_${apiCode}", jsonObject.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun savePicConfig(pref: CustomPreference, config: CustomPicAPIConfig, apiCode: Int) {
        try {
            val jsonObject = JSONObject().apply {
                put(KEY_METHOD, config.method)
                put(KEY_CONTENT_TYPE, config.contentType)
                put(KEY_BASE_URL, config.baseUrl)
                put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath)

                // 转换查询参数列表
                put(KEY_QUERY_PARAMS, JSONArray().apply {
                    config.queryParams.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求头列表
                put(KEY_HEADERS, JSONArray().apply {
                    config.headers.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求体列表
                put(KEY_BODY, JSONArray().apply {
                    config.body.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })
            }

            pref.setString("Custom_Pic_API_${apiCode}", jsonObject.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadTextConfig(prefs: CustomPreference, apiCode: Int): CustomTextAPIConfig? {
        return try {
            val jsonString = prefs.getString("Custom_Text_API_${apiCode}", "")
            if (jsonString.isEmpty()) return null

            val jsonObject = JSONObject(jsonString)

            CustomTextAPIConfig(
                method = jsonObject.getString(KEY_METHOD),
                baseUrl = jsonObject.getString(KEY_BASE_URL),
                queryParams = parseKeyValuePairs(jsonObject.getJSONArray(KEY_QUERY_PARAMS)),
                headers = parseKeyValuePairs(jsonObject.getJSONArray(KEY_HEADERS)),
                jsonBody = parseKeyValuePairs(jsonObject.getJSONArray(KEY_JSON_BODY)),
                jsonResponsePath = jsonObject.getString(KEY_JSON_RESPONSE_PATH)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadPicConfig(pref: CustomPreference, apiCode: Int): CustomPicAPIConfig? {
        return try {
            val jsonString = pref.getString("Custom_Pic_API_$apiCode", "")
            if (jsonString.isEmpty()) return null

            val jsonObject = JSONObject(jsonString)

            CustomPicAPIConfig(
                method = jsonObject.getString(KEY_METHOD),
                contentType = if (jsonObject.has(KEY_CONTENT_TYPE)) jsonObject.getString(KEY_CONTENT_TYPE) else null, // 使用optString防止出现错误
                baseUrl = jsonObject.getString(KEY_BASE_URL),
                queryParams = parseKeyValuePairs(jsonObject.getJSONArray(KEY_QUERY_PARAMS)),
                headers = parseKeyValuePairs(jsonObject.getJSONArray(KEY_HEADERS)),
                body = parseKeyValuePairs(jsonObject.getJSONArray(KEY_BODY)),
                jsonResponsePath = jsonObject.getString(KEY_JSON_RESPONSE_PATH)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==================== 列表存储方法 ====================

    fun saveTextConfigList(prefs: CustomPreference, list: List<NamedTextAPIConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { named ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, named.name)
                    put(KEY_METHOD, named.config.method)
                    put(KEY_BASE_URL, named.config.baseUrl)
                    put(KEY_JSON_RESPONSE_PATH, named.config.jsonResponsePath)
                    put(KEY_QUERY_PARAMS, JSONArray().apply {
                        named.config.queryParams.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_HEADERS, JSONArray().apply {
                        named.config.headers.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_JSON_BODY, JSONArray().apply {
                        named.config.jsonBody.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                })
            }
            prefs.setString("Custom_Text_APIs", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadTextConfigList(prefs: CustomPreference): List<NamedTextAPIConfig> {
        return try {
            val jsonString = prefs.getString("Custom_Text_APIs", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<NamedTextAPIConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(NamedTextAPIConfig(
                    name = obj.getString(KEY_NAME),
                    config = CustomTextAPIConfig(
                        method = obj.getString(KEY_METHOD),
                        baseUrl = obj.getString(KEY_BASE_URL),
                        queryParams = parseKeyValuePairs(obj.getJSONArray(KEY_QUERY_PARAMS)),
                        headers = parseKeyValuePairs(obj.getJSONArray(KEY_HEADERS)),
                        jsonBody = parseKeyValuePairs(obj.getJSONArray(KEY_JSON_BODY)),
                        jsonResponsePath = obj.getString(KEY_JSON_RESPONSE_PATH)
                    )
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveTextConfigToList(prefs: CustomPreference, named: NamedTextAPIConfig, index: Int) {
        val list = loadTextConfigList(prefs).toMutableList()
        if (index < list.size) {
            list[index] = named
        } else {
            list.add(named)
        }
        saveTextConfigList(prefs, list)
    }

    fun deleteTextConfig(prefs: CustomPreference, index: Int) {
        val list = loadTextConfigList(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveTextConfigList(prefs, list)
        }
    }

    fun savePicConfigList(prefs: CustomPreference, list: List<NamedPicAPIConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { named ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, named.name)
                    put(KEY_METHOD, named.config.method)
                    put(KEY_CONTENT_TYPE, named.config.contentType)
                    put(KEY_BASE_URL, named.config.baseUrl)
                    put(KEY_JSON_RESPONSE_PATH, named.config.jsonResponsePath)
                    put(KEY_QUERY_PARAMS, JSONArray().apply {
                        named.config.queryParams.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_HEADERS, JSONArray().apply {
                        named.config.headers.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_BODY, JSONArray().apply {
                        named.config.body.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                })
            }
            prefs.setString("Custom_Pic_APIs", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadPicConfigList(prefs: CustomPreference): List<NamedPicAPIConfig> {
        return try {
            val jsonString = prefs.getString("Custom_Pic_APIs", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<NamedPicAPIConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(NamedPicAPIConfig(
                    name = obj.getString(KEY_NAME),
                    config = CustomPicAPIConfig(
                        method = obj.getString(KEY_METHOD),
                        contentType = if (obj.has(KEY_CONTENT_TYPE)) obj.getString(KEY_CONTENT_TYPE) else null,
                        baseUrl = obj.getString(KEY_BASE_URL),
                        queryParams = parseKeyValuePairs(obj.getJSONArray(KEY_QUERY_PARAMS)),
                        headers = parseKeyValuePairs(obj.getJSONArray(KEY_HEADERS)),
                        body = parseKeyValuePairs(obj.getJSONArray(KEY_BODY)),
                        jsonResponsePath = obj.getString(KEY_JSON_RESPONSE_PATH)
                    )
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun savePicConfigToList(prefs: CustomPreference, named: NamedPicAPIConfig, index: Int) {
        val list = loadPicConfigList(prefs).toMutableList()
        if (index < list.size) {
            list[index] = named
        } else {
            list.add(named)
        }
        savePicConfigList(prefs, list)
    }

    fun deletePicConfig(prefs: CustomPreference, index: Int) {
        val list = loadPicConfigList(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            savePicConfigList(prefs, list)
        }
    }

    // ==================== 数据迁移 ====================

    fun migrateOldTextConfigs(prefs: CustomPreference) {
        if (prefs.getString("Custom_Text_APIs", "").isNotEmpty()) return
        val migrated = mutableListOf<NamedTextAPIConfig>()
        for (i in 0..2) {
            val oldConfig = loadTextConfig(prefs, i)
            if (oldConfig != null) {
                migrated.add(NamedTextAPIConfig(
                    name = "自定义API${i + 1}",
                    config = oldConfig
                ))
            }
        }
        if (migrated.isNotEmpty()) {
            saveTextConfigList(prefs, migrated)
        }
        prefs.setString("Custom_Text_API_0", "")
        prefs.setString("Custom_Text_API_1", "")
        prefs.setString("Custom_Text_API_2", "")
    }

    fun migrateOldPicConfigs(prefs: CustomPreference) {
        if (prefs.getString("Custom_Pic_APIs", "").isNotEmpty()) return
        val migrated = mutableListOf<NamedPicAPIConfig>()
        for (i in 0..2) {
            val oldConfig = loadPicConfig(prefs, i)
            if (oldConfig != null) {
                migrated.add(NamedPicAPIConfig(
                    name = "自定义API${i + 1}",
                    config = oldConfig
                ))
            }
        }
        if (migrated.isNotEmpty()) {
            savePicConfigList(prefs, migrated)
        }
        prefs.setString("Custom_Pic_API_0", "")
        prefs.setString("Custom_Pic_API_1", "")
        prefs.setString("Custom_Pic_API_2", "")
    }

    // ==================== OpenAI兼容API厂商管理 ====================

    private const val KEY_API_KEY = "apiKey"
    private const val KEY_MODEL_NAME = "modelName"
    private const val KEY_SYSTEM_PROMPT = "systemPrompt"
    private const val KEY_USER_PROMPT = "userPrompt"
    private const val KEY_MANGA_SYSTEM_PROMPT = "mangaSystemPrompt"
    private const val KEY_MANGA_USER_PROMPT = "mangaUserPrompt"

    // ==================== 内置API管理 ====================

    private const val KEY_PROVIDER_TYPE = "providerType"
    private const val KEY_MODELS = "models"
    private const val KEY_DEFAULT_SYSTEM_PROMPT = "defaultSystemPrompt"
    private const val KEY_DEFAULT_USER_PROMPT = "defaultUserPrompt"
    private const val KEY_SELECTED_MODEL_INDEX = "selectedModelIndex"
    private const val KEY_CUSTOM_MODELS = "customModels"
    private const val KEY_THINKING_MODE = "thinkingMode"
    private const val BUILTIN_MODS_KEY = "BuiltIn_Providers_Modifications"

    /** 自定义模型列表上限（防止 UI 列表过长 + 恶意填满） */
    const val MAX_CUSTOM_MODELS_PER_PROVIDER = 20

    fun saveBuiltInProviderMods(prefs: CustomPreference, mods: List<BuiltInProviderMod>) {
        try {
            val jsonArray = JSONArray()
            mods.forEach { mod ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, mod.name)
                    put(KEY_API_KEY, mod.apiKey)
                    put(KEY_SYSTEM_PROMPT, mod.systemPrompt ?: JSONObject.NULL)
                    put(KEY_USER_PROMPT, mod.userPrompt ?: JSONObject.NULL)
                    put(KEY_MANGA_SYSTEM_PROMPT, mod.mangaSystemPrompt ?: JSONObject.NULL)
                    put(KEY_MANGA_USER_PROMPT, mod.mangaUserPrompt ?: JSONObject.NULL)
                    put(KEY_SELECTED_MODEL_INDEX, mod.selectedModelIndex)
                    put(KEY_CUSTOM_MODELS, JSONArray().apply {
                        mod.customModels.forEach { put(it) }
                    })
                    put(KEY_THINKING_MODE, mod.thinkingMode ?: JSONObject.NULL)
                })
            }
            prefs.setString(BUILTIN_MODS_KEY, jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadBuiltInProviderMods(prefs: CustomPreference): List<BuiltInProviderMod> {
        return try {
            val jsonString = prefs.getString(BUILTIN_MODS_KEY, "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<BuiltInProviderMod>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(BuiltInProviderMod(
                    name = obj.getString(KEY_NAME),
                    apiKey = obj.optString(KEY_API_KEY, ""),
                    systemPrompt = if (obj.isNull(KEY_SYSTEM_PROMPT)) null else obj.getString(KEY_SYSTEM_PROMPT),
                    userPrompt = if (obj.isNull(KEY_USER_PROMPT)) null else obj.getString(KEY_USER_PROMPT),
                    mangaSystemPrompt = if (obj.isNull(KEY_MANGA_SYSTEM_PROMPT)) null else obj.getString(KEY_MANGA_SYSTEM_PROMPT),
                    mangaUserPrompt = if (obj.isNull(KEY_MANGA_USER_PROMPT)) null else obj.getString(KEY_MANGA_USER_PROMPT),
                    selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0),
                    customModels = obj.optJSONArray(KEY_CUSTOM_MODELS)?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    thinkingMode = if (obj.isNull(KEY_THINKING_MODE)) null else obj.optInt(KEY_THINKING_MODE, 0)
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun loadAllProviders(prefs: CustomPreference): List<OpenAIProviderConfig> {
        val builtinMods = loadBuiltInProviderMods(prefs)
        val builtinProviders = BuiltinProviders.providers.map { builtin ->
            val mod = builtinMods.find { it.name == builtin.name }
            applyMod(builtin, mod)
        }
        val userProviders = loadOpenAIProviders(prefs)
        return builtinProviders + userProviders
    }

    private fun applyMod(builtin: OpenAIProviderConfig, mod: BuiltInProviderMod?): OpenAIProviderConfig {
        if (mod == null) return builtin
        // 展示列表 = 预设 + 自定义；自定义为空时与原行为等价（modelName = preset[index]）
        val displayModels = builtin.models + mod.customModels
        return builtin.copy(
            apiKey = mod.apiKey,
            systemPrompt = mod.systemPrompt ?: builtin.defaultSystemPrompt,
            userPrompt = mod.userPrompt ?: builtin.defaultUserPrompt,
            mangaSystemPrompt = mod.mangaSystemPrompt ?: builtin.defaultMangaSystemPrompt,
            mangaUserPrompt = mod.mangaUserPrompt ?: builtin.defaultMangaUserPrompt,
            selectedModelIndex = mod.selectedModelIndex,
            thinkingMode = mod.thinkingMode ?: builtin.thinkingMode,
            modelName = displayModels.getOrElse(mod.selectedModelIndex) { displayModels[0] }
        )
    }

    /**
     * 从自定义模型列表中删除指定位置，并返回调整后的 (customModels, selectedModelIndex)。
     *
     * 索引约定：
     *   - 0..presetSize-1 是预设区（不可删除）
     *   - presetSize..presetSize + customModels.size - 1 是自定义区
     *   - deleteIndex 必须落在自定义区
     *
     * 下标回退规则（针对 selectedIndex 在 displayModels 中的位置）：
     *   - selectedIndex < deleteIndex   → 不变（选中的项在删除项之前）
     *   - selectedIndex == deleteIndex  → 不变（删除自己后，该下标自动指向原来的下一项）
     *   - selectedIndex > deleteIndex   → -1（后面的项前移）
     *   - selectedIndex 越界（≥ newDisplaySize）→ 0（兜底）
     */
    fun removeCustomModelAndAdjustIndex(
        presetSize: Int,
        customModels: List<String>,
        deleteIndex: Int,
        selectedIndex: Int
    ): Pair<List<String>, Int> {
        require(presetSize >= 0) { "presetSize must be >= 0" }
        require(deleteIndex >= presetSize) {
            "Cannot delete preset model at index $deleteIndex (presetSize=$presetSize)"
        }
        val customIndex = deleteIndex - presetSize
        require(customIndex in customModels.indices) {
            "deleteIndex=$deleteIndex out of range (customModels.size=${customModels.size})"
        }
        val newCustoms = customModels.toMutableList().apply { removeAt(customIndex) }
        val newDisplaySize = presetSize + newCustoms.size
        val newSelected = when {
            newDisplaySize == 0 -> 0
            selectedIndex < deleteIndex -> selectedIndex
            selectedIndex == deleteIndex -> deleteIndex.coerceAtMost(newDisplaySize - 1)
            else -> (selectedIndex - 1).coerceAtMost(newDisplaySize - 1)
        }
        return newCustoms to newSelected.coerceIn(0, (newDisplaySize - 1).coerceAtLeast(0))
    }

    fun saveOpenAIProviders(prefs: CustomPreference, list: List<OpenAIProviderConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { provider ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, provider.name)
                    put(KEY_API_KEY, provider.apiKey)
                    put(KEY_BASE_URL, provider.baseUrl)
                    put(KEY_MODEL_NAME, provider.modelName)
                    put(KEY_SYSTEM_PROMPT, provider.systemPrompt)
                    put(KEY_USER_PROMPT, provider.userPrompt)
                    put(KEY_MANGA_SYSTEM_PROMPT, provider.mangaSystemPrompt)
                    put(KEY_MANGA_USER_PROMPT, provider.mangaUserPrompt)
                    put(KEY_PROVIDER_TYPE, provider.providerType)
                    put(KEY_SELECTED_MODEL_INDEX, provider.selectedModelIndex)
                    put("autoAppendPath", provider.autoAppendPath)
                    put(KEY_THINKING_MODE, provider.thinkingMode)
                })
            }
            prefs.setString("OpenAI_Providers", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadOpenAIProviders(prefs: CustomPreference): List<OpenAIProviderConfig> {
        return try {
            val jsonString = prefs.getString("OpenAI_Providers", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<OpenAIProviderConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(OpenAIProviderConfig(
                    name = obj.getString(KEY_NAME),
                    apiKey = obj.getString(KEY_API_KEY),
                    baseUrl = obj.getString(KEY_BASE_URL),
                    modelName = obj.getString(KEY_MODEL_NAME),
                    systemPrompt = obj.getString(KEY_SYSTEM_PROMPT),
                    userPrompt = obj.getString(KEY_USER_PROMPT),
                    mangaSystemPrompt = obj.optString(KEY_MANGA_SYSTEM_PROMPT, ""),
                    mangaUserPrompt = obj.optString(KEY_MANGA_USER_PROMPT, ""),
                    providerType = obj.optString(KEY_PROVIDER_TYPE, OpenAIProviderConfig.PROVIDER_TYPE_USER),
                    selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0),
                    autoAppendPath = obj.optBoolean("autoAppendPath", true),
                    thinkingMode = obj.optInt(KEY_THINKING_MODE, OpenAIProviderConfig.THINKING_DEFAULT)
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveOpenAIProviderToList(prefs: CustomPreference, provider: OpenAIProviderConfig, index: Int) {
        val list = loadOpenAIProviders(prefs).toMutableList()
        if (index < list.size) {
            list[index] = provider
        } else {
            list.add(provider)
        }
        saveOpenAIProviders(prefs, list)
    }

    fun deleteOpenAIProvider(prefs: CustomPreference, index: Int) {
        val list = loadOpenAIProviders(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveOpenAIProviders(prefs, list)
        }
    }
}
