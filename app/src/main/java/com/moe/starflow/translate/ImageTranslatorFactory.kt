package com.moe.starflow.translate

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.manga.config.OcrEngineGroup
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.LogCollector
import translationapi.baidutranslation.BaiduTranslationImage
import translationapi.customtranslation.CustomTranslationImage
import translationapi.tencentcloud.TencentTranslationImage

/**
 * 图片翻译接口工厂：`Pic_API` 是唯一来源，图片模式不读 `Text_API`。
 *
 * 每个调用方（服务 / 阅读器控制器 / 插图管线）应持有自己的实例；接口实例上的
 * `cancelTranslation()` 是“取消本实例全部在飞请求”，不用于跨任务共享。
 */
object ImageTranslatorFactory {

    private const val TAG = "ImageTranslatorFactory"

    fun create(context: Context, prefs: CustomPreference): TranslationPicAPI? {
        return try {
            when (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)) {
                Constants.PicApi.BAIDU.id -> {
                    val appId = KeystoreManager.retrieveKey(context, "Baidu_Translate_ACCOUNT")
                    val secret = KeystoreManager.retrieveKey(context, "Baidu_Translate_SECRETKEY")
                    if (appId.isNullOrBlank() || secret.isNullOrBlank()) {
                        ImageTranslationLog.configMissing(
                            BaiduTranslationImage.PROVIDER, "factory",
                            missing = missingKeys(appId, secret, "Baidu_Translate_ACCOUNT", "Baidu_Translate_SECRETKEY"),
                        )
                        null
                    } else {
                        BaiduTranslationImage(appId, secret)
                    }
                }
                Constants.PicApi.TENCENT.id -> {
                    val secretId = KeystoreManager.retrieveKey(context, "Tencent_Cloud_ACCOUNT")
                    val secretKey = KeystoreManager.retrieveKey(context, "Tencent_Cloud_SECRETKEY")
                    if (secretId.isNullOrBlank() || secretKey.isNullOrBlank()) {
                        ImageTranslationLog.configMissing(
                            TencentTranslationImage.PROVIDER, "factory",
                            missing = missingKeys(
                                secretId, secretKey, "Tencent_Cloud_ACCOUNT", "Tencent_Cloud_SECRETKEY",
                            ),
                        )
                        null
                    } else {
                        TencentTranslationImage(secretId, secretKey)
                    }
                }
                Constants.PicApi.CUSTOM_PIC.id -> {
                    val list = ConfigurationStorage.loadPicConfigList(prefs)
                    val index = prefs.getInt("Custom_Pic_API", 0)
                    val selected = list.getOrNull(index)
                    if (selected == null) {
                        ImageTranslationLog.configMissing(
                            "${CustomTranslationImage.PROVIDER_PREFIX}#$index", "factory",
                            missing = "custom pic api #$index (size=${list.size})",
                        )
                        null
                    } else {
                        CustomTranslationImage(selected.config, "${CustomTranslationImage.PROVIDER_PREFIX}#$index")
                    }
                }
                else -> {
                    val raw = prefs.getInt("Pic_API", 0)
                    LogCollector.e(TAG, "未知图片翻译 API: $raw")
                    null
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "创建图片翻译接口失败 provider=${providerId(prefs)}", e)
            null
        }
    }

    /**
     * 稳定 provider id（日志 `provider=` 字段，**不要用本地化名称**）：`baidu` / `tencent` / `custom#<序号>`。
     */
    fun providerId(prefs: CustomPreference): String = when (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)) {
        Constants.PicApi.BAIDU.id -> BaiduTranslationImage.PROVIDER
        Constants.PicApi.TENCENT.id -> TencentTranslationImage.PROVIDER
        Constants.PicApi.CUSTOM_PIC.id ->
            "${CustomTranslationImage.PROVIDER_PREFIX}#${prefs.getInt("Custom_Pic_API", 0)}"
        else -> "pic:${prefs.getInt("Pic_API", 0)}"
    }

    private fun missingKeys(first: String?, second: String?, firstName: String, secondName: String): String =
        listOfNotNull(
            firstName.takeIf { first.isNullOrBlank() },
            secondName.takeIf { second.isNullOrBlank() },
        ).joinToString(",")

    /** 供模式页/首页启动前校验；不实际创建 OkHttp 客户端。 */
    fun isConfigured(context: Context, prefs: CustomPreference): Boolean = when (
        prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
    ) {
        Constants.PicApi.BAIDU.id ->
            hasKey(context, "Baidu_Translate_ACCOUNT") && hasKey(context, "Baidu_Translate_SECRETKEY")
        Constants.PicApi.TENCENT.id ->
            hasKey(context, "Tencent_Cloud_ACCOUNT") && hasKey(context, "Tencent_Cloud_SECRETKEY")
        Constants.PicApi.CUSTOM_PIC.id -> {
            val list = ConfigurationStorage.loadPicConfigList(prefs)
            list.getOrNull(prefs.getInt("Custom_Pic_API", 0)) != null
        }
        else -> false
    }

    fun label(context: Context, prefs: CustomPreference): String = when (
        prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
    ) {
        Constants.PicApi.BAIDU.id -> context.getString(R.string.baiduapi_name)
        Constants.PicApi.TENCENT.id -> context.getString(R.string.tencentapi_name)
        Constants.PicApi.CUSTOM_PIC.id -> context.getString(R.string.custom)
        else -> context.getString(R.string.translation_engine_label)
    }

    /**
     * 图片接口支持的源语言代码；自定义接口没有语言表时返回全量语言池。
     * 悬浮窗的常用语言循环用它与 `OcrEngineGroup.FLOATING_COMMON_LANGS` 取交集。
     */
    fun supportedSourceLangs(context: Context): Set<String> =
        TranslateTools.getLanguagesList(context, 1, forImage = true)
            ?.map { it.getOriCode() }
            ?.toSet()
            .orEmpty()
            .ifEmpty { OcrEngineGroup.ALL_LANGS.toSet() }

    private fun hasKey(context: Context, alias: String): Boolean =
        runCatching { !KeystoreManager.retrieveKey(context, alias).isNullOrBlank() }.getOrDefault(false)
}
