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

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.moe.starflow.R
import com.moe.starflow.translate.screenshot.AccessibilityServiceManager
import com.moe.starflow.translate.FloatingBallService
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.me.ManageActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody


class APIConfig : PreferenceFragmentCompat() {
    private lateinit var allTranslationKeys: List<String>
    private lateinit var prefs: CustomPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = CustomPreference.getInstance(requireContext())
        if(prefs.getInt("Translate_Mode", 0) == 0){
            setPreferencesFromResource(R.xml.preferences_ocr, rootKey)
            allTranslationKeys = listOf(
                "nllb_translation", "hymt2_translation",
                "ui_bing_translation_text", "ui_niu_translation_text",
                "ui_volc_translation_text", "ui_azure_translation_text", "ui_deepl_translation_text",
                "ui_baidu_translation_text", "ui_tencent_translation_text"
            )
        }else{
            setPreferencesFromResource(R.xml.preferences_pic, rootKey)
            allTranslationKeys = listOf(
                "ui_baidu_translation_pic", "ui_tencent_translation_pic"
            )
        }

        // 设置每个选项的监听器
        allTranslationKeys.forEach { key ->
            findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (isAnyTranslationServiceRunning()) {
                        Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }
                    // 如果打开了这个选项，关闭其他所有选项
                    changeCustomPreferences(key)
                    setKey(key)
                    true
                }else{
                    Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        if (prefs.getInt("Translate_Mode", 0) == 0){

            findPreference<Preference>("manage_nllb_model")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_NLLB)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("manage_hymt2_model")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_HYMT2)
                }
                startActivity(intent)
                true
            }

            // 应用淡灰色小字样式到所有管理按钮
            listOf("ui_manage_niu_api_text", "ui_manage_volc_api_text", "ui_manage_azure_api_text",
                "ui_manage_deepl_api_text", "ui_manage_baidu_api_text", "ui_manage_tencent_api_text").forEach { key ->
                findPreference<Preference>(key)?.let { pref ->
                    val origTitle = pref.title?.toString() ?: return@let
                    pref.title = android.text.SpannableString(origTitle).apply {
                        setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)), 0, origTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, origTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            findPreference<Preference>("ui_manage_niu_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_NIU_API)
                }
                startActivity(intent)
                true
            }

            // 动态OpenAI兼容API厂商列表
            setupOpenAIProviderList(prefs)

            findPreference<Preference>("ui_manage_volc_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_VOLC_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_azure_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_AZURE_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_deepl_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_DEEPL_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_baidu_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_tencent_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API)
                }
                startActivity(intent)
                true
            }

            // 自定义云端文本翻译API已禁用
        }else{
            // 应用淡灰色小字样式到图片翻译管理按钮
            listOf("ui_manage_baidu_api_pic", "ui_manage_tencent_api_pic").forEach { key ->
                findPreference<Preference>(key)?.let { pref ->
                    val origTitle = pref.title?.toString() ?: return@let
                    pref.title = android.text.SpannableString(origTitle).apply {
                        setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)), 0, origTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, origTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            findPreference<Preference>("ui_manage_baidu_api_pic")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_tencent_api_pic")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API)
                }
                startActivity(intent)
                true
            }

            // 动态自定义图片 API
            ConfigurationStorage.migrateOldPicConfigs(prefs)
            setupDynamicCustomApiList(prefs, false)
        }

        // 从 SharedPreferences 加载设置
        loadSettingsFromSharedPreferences()
    }

    private fun changeCustomPreferences(key: String) {
        when (key){
            "nllb_translation" -> {
                prefs.setInt("Text_API", Constants.TextApi.AI.id)
                prefs.setInt("Text_AI", Constants.TextAI.NLLB.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "nllb_translation")
            }
            "hymt2_translation" -> {
                prefs.setInt("Text_API", Constants.TextApi.AI.id)
                prefs.setInt("Text_AI", Constants.TextAI.HYMT2.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                LogCollector.d("APIConfig", "hymt2_translation")
            }
            "ui_bing_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.BING.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR bing")
            }
            "ui_niu_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.NIUTRANS.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR niu")
            }
            "ui_openai_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.OPENAI.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR openai")
            }
            "ui_volc_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.VOLC.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR volc")
            }
            "ui_azure_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.AZURE.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR azure")
            }
            "ui_deepl_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.DEEPL.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR deepl")
            }
            "ui_baidu_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.BAIDU.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR baidu")
            }
            "ui_baidu_translation_pic"->{
                prefs.setInt("Pic_API", Constants.PicApi.BAIDU.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "pic baidu")
            }
            "ui_tencent_translation_text" -> {
                prefs.setInt("Text_API", Constants.TextApi.TENCENT.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR tencent")
            }
            "ui_tencent_translation_pic" -> {
                prefs.setInt("Pic_API", Constants.PicApi.TENCENT.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "pic tencent")
            }
        }
    }

    private fun loadSettingsFromSharedPreferences() {
        // 迁移：ML Kit 移除后 Text_AI 只有 NLLB=0，老用户可能存的是旧值 1（原 NLLB）
        if (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == 1) {
            prefs.setInt("Text_AI", Constants.TextAI.NLLB.id)
        }

        // 获取当前设置
        val translateMode = prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
        val picApi = prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
        val customPicApi = prefs.getInt("Custom_Pic_API", 0)

        // 加载设置到UI上
        when {
            translateMode == Constants.TranslateMode.TEXT.id -> when (textApi) {
                Constants.TextApi.AI.id -> {
                    val textAi = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
                    if (textAi == Constants.TextAI.HYMT2.id) {
                        findPreference<SwitchPreferenceCompat>("hymt2_translation")?.isChecked = true
                    } else {
                        findPreference<SwitchPreferenceCompat>("nllb_translation")?.isChecked = true
                    }
                    setKey(if (textAi == Constants.TextAI.HYMT2.id) "hymt2_translation" else "nllb_translation")
                }
                Constants.TextApi.BING.id -> {
                    val key = "ui_bing_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.NIUTRANS.id -> {
                    val key = "ui_niu_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.OPENAI.id -> {
                    // OpenAI通过厂商列表的select switch来选中
                    val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
                    val allProviders = ConfigurationStorage.loadAllProviders(prefs)
                    if (selectedProvider < allProviders.size) {
                        val key = "ui_openai_provider_select_$selectedProvider"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    }
                }
                Constants.TextApi.VOLC.id -> {
                    val key = "ui_volc_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.AZURE.id -> {
                    val key = "ui_azure_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.DEEPL.id -> {
                    val key = "ui_deepl_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.BAIDU.id -> {
                    val key = "ui_baidu_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.TENCENT.id -> {
                    val key = "ui_tencent_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                else -> {
                    // 自定义云端文本翻译API已禁用，重置为默认Bing
                    prefs.setInt("Text_API", Constants.TextApi.BING.id)
                    val key = "ui_bing_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
            }
            else -> when (picApi) {
                Constants.PicApi.BAIDU.id -> {
                    val key = "ui_baidu_translation_pic"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.PicApi.TENCENT.id -> {
                    val key = "ui_tencent_translation_pic"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                else -> {
                    val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                    if (customPicApi < apiList.size) {
                        val key = "ui_custom_api_pic_$customPicApi"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    }
                }
            }
        }
    }

    private fun setKey(key: String){
        Log.d("APIConfig", "key=$key")
        allTranslationKeys.filter { it != key }.forEach { otherKey ->
            findPreference<SwitchPreferenceCompat>(otherKey)?.isChecked = false
        }
        // 关闭动态 custom API switches
        val isText = prefs.getInt("Translate_Mode", 0) == 0
        val categoryKey = if (isText) "ui_custom_cloud_api_translation_text" else "ui_custom_cloud_api_translation_pic"
        val category = findPreference<PreferenceCategory>(categoryKey)
        category?.let {
            for (i in 0 until it.preferenceCount) {
                val pref = it.getPreference(i)
                if (pref is SwitchPreferenceCompat && pref.key != null && pref.key!!.startsWith("ui_custom_api_")) {
                    pref.isChecked = false
                }
            }
        }
        // 关闭 OpenAI 厂商 switches
        val openaiCategory = findPreference<PreferenceCategory>("ui_openai_providers")
        openaiCategory?.let {
            for (i in 0 until it.preferenceCount) {
                val pref = it.getPreference(i)
                if (pref is SwitchPreferenceCompat && pref.key != null && pref.key!!.startsWith("ui_openai_provider_select_")) {
                    pref.isChecked = false
                }
            }
        }
    }

    private fun isAnyTranslationServiceRunning(): Boolean {
        return AccessibilityServiceManager.getService() != null &&
                (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) ||
                 ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))
    }

    private fun setupDynamicCustomApiList(prefs: CustomPreference, isText: Boolean) {
        val categoryKey = if (isText) "ui_custom_cloud_api_translation_text" else "ui_custom_cloud_api_translation_pic"
        val category = findPreference<PreferenceCategory>(categoryKey) ?: return

        category.removeAll()

        if (isText) {
            val apiList = ConfigurationStorage.loadTextConfigList(prefs)
            val selectedIndex = prefs.getInt("Custom_Text_API", 0)

            apiList.forEachIndexed { index, named ->
                val switchKey = "ui_custom_api_text_$index"
                val manageKey = "ui_manage_custom_api_text_$index"

                val switchPref = SwitchPreferenceCompat(requireContext()).apply {
                    key = switchKey
                    title = named.name
                    isIconSpaceReserved = true
                    isChecked = selectedIndex == index && prefs.getInt("Text_API", 0) == Constants.TextApi.CUSTOM_TEXT.id
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean) {
                            if (isAnyTranslationServiceRunning()) {
                                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                                return@setOnPreferenceChangeListener false
                            }
                            prefs.setInt("Text_API", Constants.TextApi.CUSTOM_TEXT.id)
                            prefs.setInt("Custom_Text_API", index)
                            prefs.setString("Source_Language", "ja")
                            prefs.setString("Target_Language", "zh")
                            refreshCustomApiSwitches(category, switchKey)
                            allTranslationKeys.forEach { key ->
                                findPreference<SwitchPreferenceCompat>(key)?.isChecked = false
                            }
                            true
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
                category.addPreference(switchPref)

                val managePref = Preference(requireContext()).apply {
                    key = manageKey
                    val manageTitle = getString(R.string.custom_api_manage)
                    title = android.text.SpannableString(manageTitle).apply {
                        setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                            putExtra(ManageActivity.EXTRA_IS_NEW, false)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(managePref)
            }

            if (apiList.size < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
                val addPref = Preference(requireContext()).apply {
                    key = "ui_custom_api_text_add"
                    title = getString(R.string.custom_api_add_new)
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, apiList.size)
                            putExtra(ManageActivity.EXTRA_IS_NEW, true)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(addPref)
            }
        } else {
            val apiList = ConfigurationStorage.loadPicConfigList(prefs)
            val selectedIndex = prefs.getInt("Custom_Pic_API", 0)

            apiList.forEachIndexed { index, named ->
                val switchKey = "ui_custom_api_pic_$index"
                val manageKey = "ui_manage_custom_api_pic_$index"

                val switchPref = SwitchPreferenceCompat(requireContext()).apply {
                    key = switchKey
                    title = named.name
                    isIconSpaceReserved = true
                    isChecked = selectedIndex == index && prefs.getInt("Pic_API", 0) == Constants.PicApi.CUSTOM_PIC.id
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean) {
                            if (isAnyTranslationServiceRunning()) {
                                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                                return@setOnPreferenceChangeListener false
                            }
                            prefs.setInt("Pic_API", Constants.PicApi.CUSTOM_PIC.id)
                            prefs.setInt("Custom_Pic_API", index)
                            prefs.setString("Source_Language", "ja")
                            prefs.setString("Target_Language", "zh")
                            refreshCustomApiSwitches(category, switchKey)
                            allTranslationKeys.forEach { key ->
                                findPreference<SwitchPreferenceCompat>(key)?.isChecked = false
                            }
                            true
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
                category.addPreference(switchPref)

                val managePref = Preference(requireContext()).apply {
                    key = manageKey
                    val manageTitle = getString(R.string.custom_api_manage)
                    title = android.text.SpannableString(manageTitle).apply {
                        setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                            putExtra(ManageActivity.EXTRA_IS_NEW, false)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(managePref)
            }

            if (apiList.size < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
                val addPref = Preference(requireContext()).apply {
                    key = "ui_custom_api_pic_add"
                    title = getString(R.string.custom_api_add_new)
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, apiList.size)
                            putExtra(ManageActivity.EXTRA_IS_NEW, true)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(addPref)
            }
        }
    }

    private fun refreshCustomApiSwitches(category: PreferenceCategory, activeKey: String) {
        for (i in 0 until category.preferenceCount) {
            val pref = category.getPreference(i)
            if (pref is SwitchPreferenceCompat && pref.key != null && pref.key != activeKey && pref.key!!.startsWith("ui_custom_api_")) {
                pref.isChecked = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页面返回时刷新厂商列表
        if (::prefs.isInitialized) {
            setupOpenAIProviderList(prefs)
            setupDynamicCustomApiList(prefs, false)
        }
    }

    private fun setupOpenAIProviderList(prefs: CustomPreference) {
        val category = findPreference<PreferenceCategory>("ui_openai_providers") ?: return
        category.removeAll()

        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
        val isOpenAISelected = prefs.getInt("Text_API", 0) == Constants.TextApi.OPENAI.id

        // 自定义API计数器（用于轮流分配图标）
        var customIndex = 0

        allProviders.forEachIndexed { index, provider ->
            // Switch - 选择此厂商
            val switchKey = "ui_openai_provider_select_$index"
            val switchPref = SwitchPreferenceCompat(requireContext()).apply {
                key = switchKey
                title = provider.name
                if (provider.isBuiltin) {
                    icon = androidx.core.content.ContextCompat.getDrawable(
                        requireContext(),
                        builtinIconRes(provider.name)
                    )
                } else {
                    // 自定义API轮流使用3个图标
                    icon = androidx.core.content.ContextCompat.getDrawable(
                        requireContext(),
                        customIconRes(customIndex)
                    )
                    customIndex++
                }
                summary = provider.modelName
                isIconSpaceReserved = true
                isChecked = isOpenAISelected && selectedProvider == index
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        if (isAnyTranslationServiceRunning()) {
                            Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
                        }
                        prefs.setInt("Text_API", Constants.TextApi.OPENAI.id)
                        prefs.setInt("OpenAI_Selected_Provider", index)
                        prefs.setString("Source_Language", "ja")
                        prefs.setString("Target_Language", "zh")
                        // 关闭所有其他OpenAI厂商switch
                        for (i in 0 until category.preferenceCount) {
                            val p = category.getPreference(i)
                            if (p is SwitchPreferenceCompat && p.key != null && p.key!!.startsWith("ui_openai_provider_select_") && p.key != switchKey) {
                                p.isChecked = false
                            }
                        }
                        // 关闭非OpenAI的switch
                        allTranslationKeys.forEach { k ->
                            findPreference<SwitchPreferenceCompat>(k)?.isChecked = false
                        }
                        // 关闭自定义API的switch
                        val customCategory = findPreference<PreferenceCategory>("ui_custom_cloud_api_translation_text")
                        customCategory?.let { cat ->
                            for (i in 0 until cat.preferenceCount) {
                                val p = cat.getPreference(i)
                                if (p is SwitchPreferenceCompat && p.key != null && p.key!!.startsWith("ui_custom_api_")) {
                                    p.isChecked = false
                                }
                            }
                        }
                        true
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
            category.addPreference(switchPref)

            // 管理按钮
            val managePref = Preference(requireContext()).apply {
                key = "ui_openai_provider_manage_$index"
                val manageTitle = getString(R.string.manage_openai_provider, provider.name)
                title = android.text.SpannableString(manageTitle).apply {
                    setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary)), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, manageTitle.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                        putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                        putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                        putExtra(ManageActivity.EXTRA_IS_NEW, false)
                    }
                    startActivity(intent)
                    true
                }
            }
            category.addPreference(managePref)
        }

        // 添加新厂商按钮（只对用户API计数）
        val userProviderCount = allProviders.count { !it.isBuiltin }
        if (userProviderCount < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
            val addPref = Preference(requireContext()).apply {
                key = "ui_openai_provider_add"
                title = getString(R.string.custom_api_add_new)
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                        putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                        putExtra(ManageActivity.EXTRA_CUSTOM_CODE, allProviders.size)
                        putExtra(ManageActivity.EXTRA_IS_NEW, true)
                    }
                    startActivity(intent)
                    true
                }
            }
            category.addPreference(addPref)
        }
    }

    private fun builtinIconRes(name: String): Int = when (name) {
        "火山引擎" -> R.drawable.ic_provider_doubao
        "智谱AI" -> R.drawable.ic_provider_zhipu
        "DeepSeek" -> R.drawable.ic_provider_deepseek
        "通义千问" -> R.drawable.ic_provider_qianwen
        else -> R.drawable.ic_launcher_foreground
    }

    private fun customIconRes(index: Int): Int = when (index % 3) {
        0 -> R.drawable.ic_provider_custom_1
        1 -> R.drawable.ic_provider_custom_2
        2 -> R.drawable.ic_provider_custom_3
        else -> R.drawable.ic_provider_custom_1
    }

    private fun testOpenAIProvider(provider: OpenAIProviderConfig) {
        var cancelled = false
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.test_provider)
            .setMessage(R.string.testing_connection)
            .setCancelable(true)
            .setNegativeButton(R.string.user_cancel) { _, _ -> cancelled = true }
            .create()
        progressDialog.show()

        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val jsonBody = org.json.JSONObject().apply {
                    put("model", provider.modelName)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", "Hi")
                        })
                    })
                    put("max_tokens", 5)
                }

                val url = if (provider.isBuiltin || provider.autoAppendPath) {
                    if (provider.baseUrl.endsWith("/")) {
                        "${provider.baseUrl}chat/completions"
                    } else {
                        "${provider.baseUrl}/chat/completions"
                    }
                } else {
                    provider.baseUrl
                }

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        if (cancelled) return@runOnUiThread
                        if (response.isSuccessful) {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.test_success)
                                .setMessage(getString(R.string.test_success_detail, response.code))
                                .setPositiveButton(R.string.user_known, null)
                                .show()
                        } else {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.test_failed)
                                .setMessage(getString(R.string.test_failed_detail, response.code, body.take(200)))
                                .setPositiveButton(R.string.user_known, null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    if (cancelled) return@runOnUiThread
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.test_failed)
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton(R.string.user_known, null)
                        .show()
                }
            }
        }.start()
    }
}