package com.moe.starflow.translate

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference

/** 翻译模型显示名单一来源（主页状态栏 / 阅读器翻译面板共用）。 */
object TranslatorNames {

    /** 当前翻译模型名（NLLB/Hy-MT2/各 API），从 Text_API/Text_AI 判断。 */
    fun of(context: Context, prefs: CustomPreference): String =
        when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
            Constants.TextApi.AI.id ->
                if (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.HYMT2.id) "Hy-MT2" else "NLLB"
            Constants.TextApi.BING.id -> context.getString(R.string.bingapi_name)
            Constants.TextApi.NIUTRANS.id -> context.getString(R.string.niuapi_name)
            Constants.TextApi.OPENAI.id -> {
                val list = ConfigurationStorage.loadAllProviders(prefs)
                val i = prefs.getInt("OpenAI_Selected_Provider", 0)
                if (i < list.size) list[i].name else context.getString(R.string.uniaiapi_name)
            }
            Constants.TextApi.VOLC.id -> context.getString(R.string.volcapi_name)
            Constants.TextApi.AZURE.id -> context.getString(R.string.azureapi_name)
            Constants.TextApi.DEEPL.id -> context.getString(R.string.deeplapi_name)
            Constants.TextApi.BAIDU.id -> context.getString(R.string.baiduapi_name)
            Constants.TextApi.TENCENT.id -> context.getString(R.string.tencentapi_name)
            Constants.TextApi.CUSTOM_TEXT.id -> context.getString(R.string.custom)
            else -> context.getString(R.string.bingapi_name)
        }
}