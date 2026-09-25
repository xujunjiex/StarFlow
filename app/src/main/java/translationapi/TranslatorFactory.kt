package translationapi

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.LogCollector
import translationapi.azuretranslation.AzureTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.bingtranslation.BingTranslation
import translationapi.customtranslation.CustomTranslationText
import translationapi.deepltranslation.DeepLTranslation
import com.moe.starflow.llamacpp.LlamaCppModelStore
import com.moe.starflow.llamacpp.LlamaCppSharedHolder
import com.moe.starflow.llamacpp.LlamaCppTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.nllbtranslation.NLLBTranslation
import com.moe.starflow.me.apiconfig.BuiltinProviders
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import translationapi.openaitranslation.OpenAITranslation
import translationapi.tencentcloud.TencentTranslationText
import translationapi.volctranslation.VolcTranslation

/**
 * 共享翻译引擎工厂：从 prefs（Text_API / Text_AI）创建 TranslationTextAPI。
 * 从 FloatingBallService.initialize / MangaFloatingService.initTranslator 的 when 块抽取，
 * 逻辑逐行保留。mode 决定 OpenAI 分支的提示词与续写格式：
 * - GAME / TEXT：游戏式纯文本 prompt（provider.systemPrompt），无漫画续写格式
 * - MANGA：漫画 prompt（provider.mangaSystemPrompt）+ continuationType + "[1] " prefill
 * 返回 null = 配置缺失或未知引擎（调用方自行提示）。
 */
object TranslatorFactory {

    private const val TAG = "TranslatorFactory"

    enum class Mode { GAME, MANGA, TEXT }

    fun create(context: Context, prefs: CustomPreference, mode: Mode): TranslationTextAPI? {
        // 引擎切换后换出旧共享模型：切到 NLLB/在线 API 时，缓存的 LlamaCpp 实例不再有调用方，
        // 立即把几百 MB ~ 几 GB 的模型换出内存（get() 只在 LlamaCpp 分支被调，切走后不会自动触发）
        LlamaCppModelStore.init(context)
        val activeModel = LlamaCppModelStore.active()
        LlamaCppSharedHolder.releaseIfNotCurrent(activeModel)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        return try {
            when (textApi) {
                Constants.TextApi.AI.id -> {
                    when (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)) {
                        Constants.TextAI.NLLB.id, 1 -> {  // 1 = 升级前 NLLB 旧值
                            LogCollector.d(TAG, "引擎初始化: NLLB Translation")
                            NLLBTranslation(context)
                        }
                        // 本地 GGUF 引擎（原 Hy-MT2 分支；Text_AI 持久化值 2 保持不变，老用户配置不失配）
                        Constants.TextAI.HYMT2.id -> {
                            if (activeModel == null) {
                                LogCollector.e(TAG, "未选择 LlamaCpp 模型（清单为空）")
                                null
                            } else {
                                LogCollector.d(TAG, "引擎初始化: LlamaCpp（${activeModel.displayName}）")
                                // 全 app 共享同一热模型实例：避免各自冷加载（冷加载后解码明显变慢）
                                LlamaCppSharedHolder.get(context, activeModel)
                            }
                        }
                        else -> {
                            LogCollector.e(TAG, "Unknown AI Translator: ${prefs.getInt("Text_AI", 0)}")
                            null
                        }
                    }
                }
                Constants.TextApi.BING.id -> BingTranslation()
                Constants.TextApi.NIUTRANS.id -> NiuTranslation(KeystoreManager.retrieveKey(context, "Niutrans")!!)
                Constants.TextApi.OPENAI.id -> createOpenAI(context, prefs, mode)
                Constants.TextApi.VOLC.id -> VolcTranslation(
                    KeystoreManager.retrieveKey(context, "Volc_ACCOUNT")!!,
                    KeystoreManager.retrieveKey(context, "Volc_SECRETKEY")!!
                )
                Constants.TextApi.AZURE.id -> AzureTranslation(KeystoreManager.retrieveKey(context, "Azure")!!)
                Constants.TextApi.DEEPL.id -> DeepLTranslation(
                    KeystoreManager.retrieveKey(context, "DeepL_Translate_HOST")!!,
                    KeystoreManager.retrieveKey(context, "DeepL_Translate_APIKEY")!!
                )
                Constants.TextApi.BAIDU.id -> BaiduTranslationText(
                    KeystoreManager.retrieveKey(context, "Baidu_Translate_ACCOUNT")!!,
                    KeystoreManager.retrieveKey(context, "Baidu_Translate_SECRETKEY")!!
                )
                Constants.TextApi.TENCENT.id -> TencentTranslationText(
                    KeystoreManager.retrieveKey(context, "Tencent_Cloud_ACCOUNT")!!,
                    KeystoreManager.retrieveKey(context, "Tencent_Cloud_SECRETKEY")!!
                )
                Constants.TextApi.CUSTOM_TEXT.id -> createCustom(prefs)
                else -> {
                    LogCollector.e(TAG, "Unknown Text API: $textApi")
                    null
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "引擎创建异常: ${e.message}", e)
            null
        }
    }

    private fun createOpenAI(context: Context, prefs: CustomPreference, mode: Mode): OpenAITranslation? {
        val providerList = ConfigurationStorage.loadAllProviders(prefs)
        val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
        if (providerList.isEmpty() || selectedIndex >= providerList.size) {
            LogCollector.e(TAG, "No OpenAI Provider Config Found")
            return null
        }
        val provider = providerList[selectedIndex]
        return if (mode == Mode.MANGA) {
            // 漫画：manga prompt + 续写格式控制（与 MangaFloatingService 原逻辑逐行一致）
            val effectiveContinuationType = if (provider.isBuiltin) provider.continuationType
            else OpenAIProviderConfig.CONTINUATION_NONE
            val effectiveSystemPrompt = if (provider.isBuiltin)
                provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt }
            else provider.mangaSystemPrompt.ifEmpty { BuiltinProviders.DEFAULT_MANGA_SYSTEM_PROMPT }
            val effectiveUserPrompt = if (provider.isBuiltin)
                provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
            else provider.mangaUserPrompt.ifEmpty { BuiltinProviders.DEFAULT_MANGA_USER_PROMPT }
            OpenAITranslation(
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.modelName,
                systemPrompt = effectiveSystemPrompt,
                userPrompt = effectiveUserPrompt,
                continuationType = effectiveContinuationType,
                prefillContent = if (effectiveContinuationType != OpenAIProviderConfig.CONTINUATION_NONE &&
                    effectiveContinuationType != OpenAIProviderConfig.CONTINUATION_JSON) "[1] " else "",
                autoAppendPath = provider.autoAppendPath,
                thinkingMode = provider.thinkingMode,
                appContext = context
            )
        } else {
            // 游戏/文本：纯文本 prompt，无续写（与 FloatingBallService 原逻辑逐行一致）
            val effectiveSystemPrompt = if (provider.isBuiltin)
                provider.systemPrompt.ifEmpty { provider.defaultSystemPrompt }
            else provider.systemPrompt.ifEmpty { BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
            val effectiveUserPrompt = if (provider.isBuiltin)
                provider.userPrompt.ifEmpty { provider.defaultUserPrompt }
            else provider.userPrompt.ifEmpty { BuiltinProviders.DEFAULT_USER_PROMPT }
            OpenAITranslation(
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.modelName,
                systemPrompt = effectiveSystemPrompt,
                userPrompt = effectiveUserPrompt,
                autoAppendPath = provider.autoAppendPath,
                thinkingMode = provider.thinkingMode,
                appContext = context
            )
        }
    }

    private fun createCustom(prefs: CustomPreference): CustomTranslationText? {
        val apiList = ConfigurationStorage.loadTextConfigList(prefs)
        val selectedIndex = prefs.getInt("Custom_Text_API", 0)
        if (apiList.isEmpty() || selectedIndex >= apiList.size) {
            LogCollector.e(TAG, "No Custom Text API Config Found")
            return null
        }
        return CustomTranslationText(apiList[selectedIndex].config)
    }

    /** 本地引擎判定：NLLB / LlamaCpp（本地 GGUF）为本地离线推理，其余为联网 API。 */
    fun isLocal(translator: TranslationTextAPI): Boolean =
        translator is NLLBTranslation || translator is LlamaCppTranslation

    /**
     * 文本翻译页专用：LlamaCpp 走进程级共享实例（跨页面切换不释放、不重载整个模型），
     * 其余引擎同 create(TEXT)。
     */
    fun createForText(context: Context, prefs: CustomPreference): TranslationTextAPI? {
        val t = create(context, prefs, Mode.TEXT) ?: return null
        return if (t is LlamaCppTranslation) {
            LlamaCppModelStore.active()?.let { LlamaCppSharedHolder.get(context, it) } ?: t
        } else t
    }

    /** 引擎展示名：OpenAI 兼容显示实际模型名，本地 GGUF 显示当前模型名。用于页面引擎指示条。 */
    fun engineLabel(context: Context, prefs: CustomPreference): String = when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
        Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)) {
            Constants.TextAI.NLLB.id, 1 -> "NLLB"
            Constants.TextAI.HYMT2.id -> {
                LlamaCppModelStore.init(context)
                LlamaCppModelStore.active()?.displayName ?: context.getString(R.string.llamacpp_name)
            }
            else -> context.getString(R.string.ai_engine_label)
        }
        Constants.TextApi.BING.id -> context.getString(R.string.bingapi_name)
        Constants.TextApi.NIUTRANS.id -> context.getString(R.string.niuapi_name)
        Constants.TextApi.OPENAI.id -> {
            val providers = ConfigurationStorage.loadAllProviders(prefs)
            val idx = prefs.getInt("OpenAI_Selected_Provider", 0)
            providers.getOrNull(idx)?.modelName ?: "OpenAI"
        }
        Constants.TextApi.VOLC.id -> context.getString(R.string.volcapi_name)
        Constants.TextApi.AZURE.id -> "Azure"
        Constants.TextApi.DEEPL.id -> "DeepL"
        Constants.TextApi.BAIDU.id -> context.getString(R.string.baiduapi_name)
        Constants.TextApi.TENCENT.id -> context.getString(R.string.tencentapi_name)
        Constants.TextApi.CUSTOM_TEXT.id -> context.getString(R.string.custom)
        else -> context.getString(R.string.translation_engine_label)
    }
}
