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

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.PopupWindow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentOpenaiApiBinding
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAIText :Fragment() {
    private lateinit var binding: FragmentOpenaiApiBinding
    private lateinit var prefs: CustomPreference
    private var providerIndex: Int = 0
    private var isNew = false
    private var selectedModelIndex: Int = 0
    private var selectedThinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT
    private val defaultSystemPrompt = "你是专业翻译引擎。将用户提供的文本翻译为usetolang。\n规则：\n1. 只输出译文，不输出解释、标注或附加内容\n2. 保持原文格式（换行、标点风格等）\n3. 翻译应自然流畅，符合目标语言的表达习惯\n4. 专有名词（人名、地名、作品名）保留原文或使用通用译名\n5. 如果文本已经是目标语言，原样返回"
    private val defaultUserPrompt = "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    private var currentTab = 0  // 0=游戏模式, 1=漫画模式
    private var gameSystemPromptCache: String = ""
    private var gameUserPromptCache: String = ""
    private var mangaSystemPrompt: String = ""
    private var mangaUserPrompt: String = ""
    private var defaultMangaSystemPrompt: String = ""
    private var defaultMangaUserPrompt: String = ""

    // 新建用户API时的漫画默认提示词
    private val fallbackMangaSystemPrompt = "你是漫画翻译引擎。逐条翻译以下文本为usetolang，保持每条的[N]编号格式不变。\n规则：\n1. 只输出译文，不输出解释、标注或附加内容\n2. 翻译应口语化、自然，符合漫画对话的语气\n3. 保持编号格式：[1] 译文\n4. 象声词、感叹词根据目标语言习惯调整\n5. 如果文本已经是目标语言，原样返回"
    private val fallbackMangaUserPrompt = "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
        arguments?.let {
            providerIndex = it.getInt("custom_code", 0)
            isNew = it.getBoolean("is_new", false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOpenaiApiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupButtons()
        loadConfig()
        setupThinkingModeSelector()
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConfiguration() }
        binding.btnDelete.setOnClickListener { deleteConfiguration() }
        binding.btnTest.setOnClickListener { testConnection() }
        if (isNew) {
            binding.btnDelete.visibility = View.GONE
        }
    }

    private fun setupTabs() {
        binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_game_mode))
        binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_manga_mode))

        binding.promptModeTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                // Save current tab's content before switching
                saveCurrentTabContent()
                currentTab = tab?.position ?: 0
                switchPromptDisplay()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun saveCurrentTabContent() {
        val currentText = binding.editSystemPrompt.text.toString()
        val currentUserText = binding.editUserPrompt.text.toString()
        if (currentTab == 0) {
            // Save game tab edits before switching to manga tab
            gameSystemPromptCache = currentText
            gameUserPromptCache = currentUserText
        } else {
            // Save manga tab edits before switching to game tab
            mangaSystemPrompt = currentText
            mangaUserPrompt = currentUserText
        }
    }

    private fun switchPromptDisplay() {
        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        if (!isNew && providerIndex < allProviders.size) {
            val provider = allProviders[providerIndex]
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(gameSystemPromptCache.ifEmpty { provider.systemPrompt })
                binding.editUserPrompt.setText(gameUserPromptCache.ifEmpty { provider.userPrompt })
            } else {
                binding.editSystemPrompt.setText(provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt.ifEmpty { fallbackMangaSystemPrompt } })
                binding.editUserPrompt.setText(provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt.ifEmpty { fallbackMangaUserPrompt } })
            }
        } else {
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(gameSystemPromptCache.ifEmpty { defaultSystemPrompt })
                binding.editUserPrompt.setText(gameUserPromptCache.ifEmpty { defaultUserPrompt })
            } else {
                binding.editSystemPrompt.setText(defaultMangaSystemPrompt.ifEmpty { fallbackMangaSystemPrompt })
                binding.editUserPrompt.setText(defaultMangaUserPrompt.ifEmpty { fallbackMangaUserPrompt })
            }
        }
    }

    private fun saveConfiguration() {
        try {
            val allProviders = ConfigurationStorage.loadAllProviders(prefs)
            val isBuiltin = !isNew && providerIndex < allProviders.size && allProviders[providerIndex].isBuiltin

            if (isBuiltin) {
                saveBuiltinConfig(allProviders[providerIndex])
            } else {
                saveUserConfig()
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.failed_save_config, e.message))
        }
    }

    private fun saveBuiltinConfig(original: OpenAIProviderConfig) {
        val apiKey = binding.editApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        // Determine prompts based on current tab
        val systemPrompt: String
        val userPrompt: String
        val mangaSys: String
        val mangaUsr: String

        if (currentTab == 0) {
            // Currently on game tab - editor has game prompts
            systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { original.defaultSystemPrompt }
            userPrompt = binding.editUserPrompt.text.toString().ifBlank { original.defaultUserPrompt }
            mangaSys = mangaSystemPrompt.ifBlank { original.defaultMangaSystemPrompt }
            mangaUsr = mangaUserPrompt.ifBlank { original.defaultMangaUserPrompt }
        } else {
            // Currently on manga tab - editor has manga prompts
            systemPrompt = original.systemPrompt.ifBlank { original.defaultSystemPrompt }
            userPrompt = original.userPrompt.ifBlank { original.defaultUserPrompt }
            mangaSys = binding.editSystemPrompt.text.toString().ifBlank { original.defaultMangaSystemPrompt }
            mangaUsr = binding.editUserPrompt.text.toString().ifBlank { original.defaultMangaUserPrompt }
        }

        val selectedModelIndex = this.selectedModelIndex

        // 保存到内置API修改列表
        val mods = ConfigurationStorage.loadBuiltInProviderMods(prefs).toMutableList()
        val existingIndex = mods.indexOfFirst { it.name == original.name }
        // 关键：保留已有的 customModels（避免保存覆盖掉用户在 popup 中添加的自定义模型）
        val existingCustomModels = if (existingIndex >= 0) mods[existingIndex].customModels else emptyList()
        val mod = BuiltInProviderMod(
            name = original.name,
            apiKey = apiKey,
            systemPrompt = if (systemPrompt != original.defaultSystemPrompt) systemPrompt else null,
            userPrompt = if (userPrompt != original.defaultUserPrompt) userPrompt else null,
            mangaSystemPrompt = if (mangaSys != original.defaultMangaSystemPrompt) mangaSys else null,
            mangaUserPrompt = if (mangaUsr != original.defaultMangaUserPrompt) mangaUsr else null,
            selectedModelIndex = selectedModelIndex,
            customModels = existingCustomModels,
            thinkingMode = selectedThinkingMode
        )
        if (existingIndex >= 0) {
            mods[existingIndex] = mod
        } else {
            mods.add(mod)
        }
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)

        UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
        requireActivity().finish()
    }

    private fun saveUserConfig() {
        val providerName = binding.editProviderName.text.toString().trim()
        if (providerName.isBlank()) {
            throw Exception(getString(R.string.custom_api_name_blank))
        }

        if (binding.editApiKey.text.toString().trim().isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        val normalizedUrl = UrlUtils.normalizeUrl(requireContext(), binding.editBaseUrl.text.toString())

        if (binding.editModelName.text.toString().trim().isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        // Determine prompts based on current tab
        val systemPrompt: String
        val userPrompt: String
        val mangaSys: String
        val mangaUsr: String

        // Load existing provider to preserve unmodified fields
        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        val existingProvider = if (!isNew && providerIndex < allProviders.size) allProviders[providerIndex] else null

        if (currentTab == 0) {
            systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { defaultSystemPrompt }
            userPrompt = binding.editUserPrompt.text.toString().ifBlank { defaultUserPrompt }
            mangaSys = mangaSystemPrompt.ifBlank { fallbackMangaSystemPrompt }
            mangaUsr = mangaUserPrompt.ifBlank { fallbackMangaUserPrompt }
        } else {
            // Preserve existing game prompts when saving from manga tab
            systemPrompt = existingProvider?.systemPrompt?.ifBlank { defaultSystemPrompt } ?: defaultSystemPrompt
            userPrompt = existingProvider?.userPrompt?.ifBlank { defaultUserPrompt } ?: defaultUserPrompt
            mangaSys = binding.editSystemPrompt.text.toString().ifBlank { fallbackMangaSystemPrompt }
            mangaUsr = binding.editUserPrompt.text.toString().ifBlank { fallbackMangaUserPrompt }
        }

        val provider = OpenAIProviderConfig(
            name = providerName,
            apiKey = binding.editApiKey.text.toString().trim(),
            baseUrl = normalizedUrl,
            modelName = binding.editModelName.text.toString().trim(),
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            mangaSystemPrompt = mangaSys,
            mangaUserPrompt = mangaUsr,
            autoAppendPath = binding.switchAutoAppendPath.isChecked,
            thinkingMode = selectedThinkingMode
        )

        lifecycleScope.launch {
            ConfigurationStorage.saveOpenAIProviderToList(prefs, provider, providerIndex - BuiltinProviders.providers.size)
            UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
            requireActivity().finish()
        }
    }

    private fun loadConfig() {
        try {
            val allProviders = ConfigurationStorage.loadAllProviders(prefs)
            if (!isNew && providerIndex < allProviders.size) {
                val provider = allProviders[providerIndex]
                binding.editProviderName.setText(provider.name)
                binding.editApiKey.setText(provider.apiKey)
                binding.editBaseUrl.setText(provider.baseUrl)
                binding.editModelName.setText(provider.modelName)
                binding.editSystemPrompt.setText(provider.systemPrompt)
                binding.editUserPrompt.setText(provider.userPrompt)
                binding.switchAutoAppendPath.isChecked = provider.autoAppendPath
                selectedThinkingMode = provider.thinkingMode

                // 初始化游戏缓存（和编辑框同步）
                gameSystemPromptCache = provider.systemPrompt
                gameUserPromptCache = provider.userPrompt
                // 加载漫画提示词
                mangaSystemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt }
                mangaUserPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
                defaultMangaSystemPrompt = provider.defaultMangaSystemPrompt
                defaultMangaUserPrompt = provider.defaultMangaUserPrompt

                if (provider.isBuiltin) {
                    setupBuiltinMode(provider)
                } else {
                    setupUserMode()
                }
            } else {
                // 新建时设置默认prompt
                binding.editSystemPrompt.setText(defaultSystemPrompt)
                binding.editUserPrompt.setText(defaultUserPrompt)
                gameSystemPromptCache = defaultSystemPrompt
                gameUserPromptCache = defaultUserPrompt
                mangaSystemPrompt = ""
                mangaUserPrompt = ""
                defaultMangaSystemPrompt = fallbackMangaSystemPrompt
                defaultMangaUserPrompt = fallbackMangaUserPrompt
                setupUserMode()
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.error_load_config, e.message ?: ""))
        }
    }

    private fun setupBuiltinMode(provider: OpenAIProviderConfig) {
        // 显示提供商图标和名称（居中显示）
        binding.providerIcon.visibility = View.VISIBLE
        binding.providerIcon.setImageResource(builtinIconRes(provider.name))
        binding.providerNameText.visibility = View.VISIBLE
        binding.providerNameText.text = provider.displayName(requireContext())
        binding.providerNameInputLayout.visibility = View.GONE
        // 隐藏整个URL卡片
        binding.baseUrlCard.visibility = View.GONE

        // 显示控制台链接（不显示URL，点击跳转）
        if (provider.consoleUrl.isNotEmpty()) {
            binding.consoleLink.visibility = View.VISIBLE
            binding.consoleLink.text = getString(R.string.get_api_key)
            binding.consoleLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.consoleUrl)))
            }
        }

        // 模型：点击弹出自定义PopupWindow选择（预设 + 用户自定义）
        binding.modelInputLayout.visibility = View.GONE
        binding.modelSpinnerLayout.visibility = View.VISIBLE
        selectedModelIndex = provider.selectedModelIndex
        // 首次加载时用 displayModels 渲染顶部文本（避免页面打开时空白）
        val initialCustoms = ConfigurationStorage.loadBuiltInProviderMods(prefs)
            .find { it.name == provider.name }?.customModels ?: emptyList()
        val initialDisplay = provider.models + initialCustoms
        binding.modelSelector.text = initialDisplay.getOrElse(selectedModelIndex) { initialDisplay[0] }
        // 关键：每次点击都从 prefs 重新读取 customModels（否则闭包会持有旧 list，
        // 添加/删除自定义模型后再次打开 popup 会看不到最新列表）
        binding.modelSelector.setOnClickListener { anchor ->
            val latestCustoms = ConfigurationStorage.loadBuiltInProviderMods(prefs)
                .find { it.name == provider.name }?.customModels ?: emptyList()
            val latestDisplay = provider.models + latestCustoms
            showModelPopup(provider, latestDisplay, anchor)
        }
        binding.modelTips.visibility = View.VISIBLE

        // 重置按钮
        binding.btnResetSystemPrompt.visibility = View.VISIBLE
        binding.btnResetUserPrompt.visibility = View.VISIBLE
        binding.btnResetSystemPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(provider.defaultSystemPrompt)
            } else {
                binding.editSystemPrompt.setText(provider.defaultMangaSystemPrompt)
            }
        }
        binding.btnResetUserPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editUserPrompt.setText(provider.defaultUserPrompt)
            } else {
                binding.editUserPrompt.setText(provider.defaultMangaUserPrompt)
            }
        }

        // 隐藏删除按钮
        binding.btnDelete.visibility = View.GONE
    }

    private fun builtinIconRes(name: String): Int = when (name) {
        "火山引擎" -> R.drawable.ic_provider_doubao
        "智谱AI" -> R.drawable.ic_provider_zhipu
        "DeepSeek" -> R.drawable.ic_provider_deepseek
        "通义千问" -> R.drawable.ic_provider_qianwen
        else -> R.drawable.ic_launcher_foreground
    }

    private fun setupUserMode() {
        // 名称和URL可编辑
        binding.editProviderName.isEnabled = true
        binding.editProviderName.alpha = 1.0f
        binding.editBaseUrl.isEnabled = true
        binding.editBaseUrl.alpha = 1.0f
        binding.providerNameText.visibility = View.GONE
        binding.providerNameInputLayout.visibility = View.VISIBLE
        binding.providerIcon.visibility = View.GONE
        binding.baseUrlCard.visibility = View.VISIBLE
        binding.builtinUrlBadge.visibility = View.GONE

        // 模型：文本输入
        binding.modelInputLayout.visibility = View.VISIBLE
        binding.modelSpinnerLayout.visibility = View.GONE
        binding.modelTips.visibility = View.VISIBLE

        // 防御性：isNew=true 路径下 loadConfig 不会设置 switchAutoAppendPath，
        // XML 默认值不一定可靠（部分主题/换主题可能未应用）。
        // 用户模式下，baseUrl 通常不带 /chat/completions，所以默认勾选更安全。
        if (isNew) {
            binding.switchAutoAppendPath.isChecked = true
        }

        // 重置按钮：游戏模式重置到通用翻译prompt，漫画模式重置到内置漫画默认prompt
        binding.btnResetSystemPrompt.visibility = View.VISIBLE
        binding.btnResetUserPrompt.visibility = View.VISIBLE
        binding.btnResetSystemPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(defaultSystemPrompt)
            } else {
                binding.editSystemPrompt.setText(fallbackMangaSystemPrompt)
            }
        }
        binding.btnResetUserPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editUserPrompt.setText(defaultUserPrompt)
            } else {
                binding.editUserPrompt.setText(fallbackMangaUserPrompt)
            }
        }

        // 显示删除按钮（非新建时）
        if (!isNew) {
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun setupThinkingModeSelector() {
        binding.thinkingModeSelector.text = thinkingModeLabel(selectedThinkingMode)
        binding.thinkingModeSelector.setOnClickListener {
            val options = arrayOf(
                getString(R.string.thinking_mode_option_default),
                getString(R.string.thinking_mode_option_disabled),
                getString(R.string.thinking_mode_option_enabled)
            )
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.thinking_mode_title)
                .setSingleChoiceItems(options, selectedThinkingMode) { dialog, which ->
                    selectedThinkingMode = which
                    binding.thinkingModeSelector.text = options[which]
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.user_cancel, null)
                .create()
                .apply { window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
                .show()
        }
    }

    private fun thinkingModeLabel(mode: Int): String = when (mode) {
        OpenAIProviderConfig.THINKING_FORCE_DISABLED -> getString(R.string.thinking_mode_option_disabled)
        OpenAIProviderConfig.THINKING_FORCE_ENABLED -> getString(R.string.thinking_mode_option_enabled)
        else -> getString(R.string.thinking_mode_option_default)
    }

    private fun showModelPopup(provider: OpenAIProviderConfig, models: List<String>, anchor: View) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_model_selector, null)
        val container = popupView.findViewById<android.widget.LinearLayout>(R.id.model_list_container)
        val btnAddCustom = popupView.findViewById<TextView>(R.id.btn_add_custom)

        val freeModels = setOf("glm-4-flash-250414")
        val presetSize = provider.models.size

        // 用可变 List 容器持有「当前展示列表」，方便添加 / 删除后整体重画
        val displayModelsState = models.toMutableList()
        // 当前处于「× 删除模式」的自定义条目下标（null 表示全部收起）
        var deleteModeIndex: Int? = null

        fun refreshItems() {
            container.removeAllViews()
            displayModelsState.forEachIndexed { index, model ->
                val isSelected = index == selectedModelIndex
                val isCustom = index >= presetSize
                val displayName = if (model in freeModels) "$model（免费）" else model

                if (!isCustom) {
                    // ===== 预设条目：纯文本 =====
                    val item = TextView(context).apply {
                        text = displayName
                        textSize = 15f
                        setPadding(
                            (24 * density).toInt(),
                            (14 * density).toInt(),
                            (24 * density).toInt(),
                            (14 * density).toInt()
                        )
                        setTextColor(if (isSelected) Color.parseColor("#55AEEA") else androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(R.drawable.ripple_item_bg)
                        setOnClickListener {
                            selectedModelIndex = index
                            binding.modelSelector.text = model
                            popupWindow?.dismiss()
                        }
                    }
                    container.addView(item)
                } else {
                    // ===== 自定义条目：长按进入删除模式 =====
                    val row = android.widget.FrameLayout(context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setBackgroundResource(R.drawable.ripple_item_bg)
                    }

                    val nameView = TextView(context).apply {
                        text = displayName
                        textSize = 15f
                        setPadding(
                            (24 * density).toInt(),
                            (14 * density).toInt(),
                            (24 * density).toInt(),
                            (14 * density).toInt()
                        )
                        setTextColor(if (isSelected) Color.parseColor("#55AEEA") else androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            // 选中并关闭弹窗（即使在删除模式，点名称也视为选中）
                            selectedModelIndex = index
                            binding.modelSelector.text = model
                            popupWindow?.dismiss()
                        }
                        setOnLongClickListener {
                            // 切换删除模式：先触发震动反馈
                            try {
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            } catch (_: Exception) { /* 设备不支持时静默忽略 */ }
                            deleteModeIndex = if (deleteModeIndex == index) null else index
                            refreshItems()
                            true
                        }
                    }
                    row.addView(
                        nameView,
                        android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )

                    // 长按后右上角显示小「×」（克制风格，与列表普通条目一致）
                    if (deleteModeIndex == index) {
                        val deleteBtn = TextView(context).apply {
                            text = "×"
                            textSize = 16f
                            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                            gravity = Gravity.CENTER
                            // 小的点击区域，无需背景色
                            setPadding(
                                (12 * density).toInt(),
                                (8 * density).toInt(),
                                (12 * density).toInt(),
                                (8 * density).toInt()
                            )
                            isClickable = true
                            isFocusable = true
                            // 初始为不可见 + 从右侧偏移，渐入
                            alpha = 0f
                            translationX = (16 * density).toFloat()
                            setOnClickListener {
                                // 防御性处理：删除流程包在 try/catch 中，避免任何意外导致 Activity 崩溃
                                try {
                                    // 从持久化中取真实 customModels 并删除
                                    val currentCustoms = ConfigurationStorage.loadBuiltInProviderMods(prefs)
                                        .find { it.name == provider.name }?.customModels ?: emptyList()
                                    val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
                                        presetSize = presetSize,
                                        customModels = currentCustoms,
                                        deleteIndex = index,
                                        selectedIndex = selectedModelIndex
                                    )
                                    selectedModelIndex = newSelected
                                    persistCustomModels(provider, newCustoms)
                                    displayModelsState.removeAt(index)
                                    deleteModeIndex = null
                                    refreshItems()
                                } catch (e: Exception) {
                                    LogCollector.e("OpenAIText", "delete custom model failed", e)
                                    try {
                                        deleteModeIndex = null
                                        refreshItems()
                                    } catch (_: Exception) { /* ignore */ }
                                }
                            }
                        }
                        row.addView(
                            deleteBtn,
                            android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.END or Gravity.CENTER_VERTICAL
                            ).apply {
                                marginEnd = (16 * density).toInt()
                            }
                        )
                        // 渐入动画：alpha 0→1 + translationX 16dp→0，180ms
                        deleteBtn.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(180L)
                            .start()
                    }

                    container.addView(row)
                }

                // 条目间分割线
                if (index < displayModelsState.size - 1) {
                    val divider = View(context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                        ).apply {
                            marginStart = (16 * density).toInt()
                            marginEnd = (16 * density).toInt()
                        }
                        setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.divider))
                    }
                    container.addView(divider)
                }
            }
        }

        refreshItems()

        // 底部「+ 添加自定义」按钮
        btnAddCustom.setOnClickListener {
            // dialog 关闭后统一重弹 popup（无论用户是否真的添加成功），
            // 因为 AlertDialog 抢焦点期间 popup 视图状态可能未及时刷新，
            // 必须重弹才能保证用户看到的列表和 prefs 一致。
            showAddCustomDialog(
                provider = provider,
                existingDisplay = displayModelsState.toList(),
                onAdded = { newName ->
                    // 写入 prefs
                    val allMods = ConfigurationStorage.loadBuiltInProviderMods(prefs).toMutableList()
                    val existingIndex = allMods.indexOfFirst { it.name == provider.name }
                    val newCustoms: List<String>
                    if (existingIndex >= 0) {
                        val old = allMods[existingIndex]
                        newCustoms = old.customModels + newName
                        allMods[existingIndex] = old.copy(customModels = newCustoms)
                    } else {
                        newCustoms = listOf(newName)
                        allMods.add(
                            BuiltInProviderMod(
                                name = provider.name,
                                apiKey = provider.apiKey,
                                customModels = newCustoms
                            )
                        )
                    }
                    ConfigurationStorage.saveBuiltInProviderMods(prefs, allMods)
                    // 更新当前 popup 内部状态：选中新加的
                    displayModelsState.add(newName)
                    selectedModelIndex = displayModelsState.size - 1
                    binding.modelSelector.text = newName
                    deleteModeIndex = null
                },
                onDismiss = {
                    // 重弹 popup，确保显示最新列表（用户视觉上「实时」看到新条目）
                    popupWindow?.dismiss()
                    // 从 prefs 取最新 customModels 重新构造 displayModels
                    val latestCustoms = ConfigurationStorage.loadBuiltInProviderMods(prefs)
                        .find { it.name == provider.name }?.customModels ?: emptyList()
                    val latestDisplay = provider.models + latestCustoms
                    // 重新弹出 popup（必须 post 一次避免 dialog 关闭动画期间 popup 抢占焦点）
                    binding.modelSelector.post {
                        showModelPopup(provider, latestDisplay, binding.modelSelector)
                    }
                }
            )
        }

        val popup = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.elevation = 8f * density
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.START)
        popupWindow = popup
    }

    /**
     * 将当前 provider 的 customModels 列表写回 prefs（保持其他字段不变）。
     */
    private fun persistCustomModels(provider: OpenAIProviderConfig, newCustoms: List<String>) {
        val allMods = ConfigurationStorage.loadBuiltInProviderMods(prefs).toMutableList()
        val existingIndex = allMods.indexOfFirst { it.name == provider.name }
        if (existingIndex >= 0) {
            allMods[existingIndex] = allMods[existingIndex].copy(customModels = newCustoms)
        } else {
            allMods.add(
                BuiltInProviderMod(
                    name = provider.name,
                    apiKey = provider.apiKey,
                    customModels = newCustoms
                )
            )
        }
        ConfigurationStorage.saveBuiltInProviderMods(prefs, allMods)
    }

    private var popupWindow: PopupWindow? = null

    private fun testConnection() {
        val apiKey = binding.editApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            UiUtils.showToast(requireContext(), getString(R.string.fill_blank))
            return
        }

        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        val isBuiltin = !isNew && providerIndex < allProviders.size && allProviders[providerIndex].isBuiltin

        val baseUrl: String
        val modelName: String
        if (isBuiltin) {
            val provider = allProviders[providerIndex]
            baseUrl = provider.baseUrl
            val customModels = ConfigurationStorage.loadBuiltInProviderMods(prefs)
                .find { it.name == provider.name }?.customModels ?: emptyList()
            val displayModels = provider.models + customModels
            modelName = displayModels.getOrElse(selectedModelIndex) { displayModels[0] }
        } else {
            baseUrl = binding.editBaseUrl.text.toString().trim()
            modelName = binding.editModelName.text.toString().trim()
            if (baseUrl.isBlank() || modelName.isBlank()) {
                UiUtils.showToast(requireContext(), getString(R.string.fill_blank))
                return
            }
        }

        // 取消标志：用户中途关闭弹窗时设为 true，线程回调检测后跳过弹结果
        val cancelled = AtomicBoolean(false)
        binding.btnTest.isEnabled = false

        // 自定义加载弹窗（可关闭 + "取消"按钮）
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.testing_connection))
            .setMessage("")
            .setCancelable(true)
            .setNegativeButton(R.string.user_cancel) { _, _ ->
                cancelled.set(true)
            }
            .create()
        // 任何方式关闭（返回键、点击外部、取消按钮）都视为取消
        loadingDialog.setOnDismissListener {
            cancelled.set(true)
            if (isAdded) binding.btnTest.isEnabled = true
        }
        loadingDialog.show()
        loadingDialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // 日志 tag = "OpenAIText"，便于在设置页日志查看器 / logcat 中过滤
                // 修复：providerIndex 在 isNew=true 时等于 allProviders.size（越界），要安全访问
                val providerLabel = if (providerIndex in allProviders.indices) {
                    allProviders[providerIndex].displayName(requireContext())
                } else {
                    "custom[new]"  // 新建自定义 provider
                }
                LogCollector.d(
                    "OpenAIText",
                    "testConnection: provider=$providerLabel, " +
                        "model=$modelName, baseUrl=$baseUrl, selectedModelIndex=$selectedModelIndex"
                )
                LogCollector.d("OpenAIText", "testConnection: OkHttp 客户端构建完成, 开始构造 request")

                // 测试 prompt：明确询问模型名称 + 简短翻译（双重验证 API 连通性 + 模型可用性）
                val testSystemPrompt = "你是翻译引擎。只输出译文或回答问题，不输出任何解释。"
                val testUserPrompt = "请用中文回答两个问题：" +
                    "1. 你是什么模型？（请告诉我具体的模型名称或版本）" +
                    "2. 将以下日语翻译成中文：こんにちは、世界。今日はとても良い天気ですね。"

                val jsonBody = org.json.JSONObject().apply {
                    put("model", modelName)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put("content", testSystemPrompt)
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", testUserPrompt)
                        })
                    })
                    put("max_tokens", 300)
                    put("temperature", 0.3)
                    put("stream", false)
                    // 思考模式三态：0=不发送（兼容不支持思考参数的模型）/ 1=强制关闭 / 2=强制开启
                    when (selectedThinkingMode) {
                        OpenAIProviderConfig.THINKING_FORCE_DISABLED ->
                            put("thinking", org.json.JSONObject().apply { put("type", "disabled") })
                        OpenAIProviderConfig.THINKING_FORCE_ENABLED ->
                            put("thinking", org.json.JSONObject().apply { put("type", "enabled") })
                    }
                }

                val normalizedUrl = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
                    baseUrl
                } else {
                    "https://$baseUrl"
                }

                val url = if (isBuiltin || binding.switchAutoAppendPath.isChecked) {
                    if (normalizedUrl.endsWith("/")) {
                        "${normalizedUrl}chat/completions"
                    } else {
                        "$normalizedUrl/chat/completions"
                    }
                } else {
                    normalizedUrl
                }

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                LogCollector.d("OpenAIText", "testConnection: Request: $jsonBody")

                client.newCall(request).execute().use { response ->
                    LogCollector.d("OpenAIText", "testConnection: 收到响应, code=${response.code}")
                    val body = response.body?.string() ?: "Empty response"
                    LogCollector.d("OpenAIText", "testConnection: Response: $body")
                    activity?.runOnUiThread {
                        if (cancelled.get()) return@runOnUiThread
                        loadingDialog.dismiss()
                        if (response.isSuccessful) {
                            val result = try {
                                val jsonObj = org.json.JSONObject(body)
                                jsonObj.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                    .trim()
                            } catch (e: Exception) {
                                body
                            }
                            showCustomDialog(getString(R.string.test_success), result, isCancelable = true)
                        } else {
                            LogCollector.e("OpenAIText", "testConnection: HTTP ${response.code}, body=${body.take(300)}")
                            showCustomDialog(getString(R.string.test_failed), "HTTP ${response.code}\n${body.take(300)}", isCancelable = true)
                        }
                    }
                }
            } catch (e: Exception) {
                LogCollector.e("OpenAIText", "testConnection: 异常 type=${e.javaClass.name}, msg=${e.message}", e)
                activity?.runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    loadingDialog.dismiss()
                    showCustomDialog(getString(R.string.test_failed), e.message ?: "Unknown error", isCancelable = true)
                }
            }
        }.start()
    }

    /**
     * 弹出添加自定义模型对话框。
     * @param provider 当前 provider（用于校验重复）
     * @param existingDisplay 当前 popup 中的 displayModels（用于校验重复）
     * @param onAdded 校验通过后回调，传入 trim 后的新模型名
     * @param onDismiss dialog 关闭后回调（无论是否成功添加都触发）
     */
    private fun showAddCustomDialog(
        provider: OpenAIProviderConfig,
        existingDisplay: List<String>,
        onAdded: (String) -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.hint_custom_model_name)
            setSingleLine(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_custom_model)
            .setView(container)
            .setPositiveButton(R.string.add, null) // 在 show 后再绑定，避免自动关闭
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text.toString().trim()
                when {
                    name.isEmpty() -> {
                        UiUtils.showToast(requireContext(), getString(R.string.model_name_empty))
                    }
                    name.length > 100 -> {
                        UiUtils.showToast(requireContext(), getString(R.string.model_name_too_long))
                    }
                    name in existingDisplay -> {
                        UiUtils.showToast(requireContext(), getString(R.string.model_name_duplicate))
                    }
                    existingDisplay.size - provider.models.size >= ConfigurationStorage.MAX_CUSTOM_MODELS_PER_PROVIDER -> {
                        UiUtils.showToast(requireContext(), getString(R.string.model_count_limit))
                    }
                    else -> {
                        onAdded(name)
                        dialog.dismiss()
                    }
                }
            }
        }
        // dialog 关闭后（无论成功/取消）刷新 popup，保证用户始终看到最新状态
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.show()
    }

    private fun showCustomDialog(title: String, message: String?, isCancelable: Boolean): AlertDialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message ?: "")
            .setCancelable(isCancelable)
            .setNeutralButton(R.string.copy) { _, _ ->
                // 复制测试结果/错误信息到剪贴板，便于反馈给开发者
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("test_result", message ?: ""))
                UiUtils.showToast(requireContext(), getString(R.string.copied), isShort = true)
            }
            .apply {
                if (isCancelable) {
                    setPositiveButton(R.string.user_known, null)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        return dialog
    }

    private fun deleteConfiguration() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_api_delete)
            .setMessage(R.string.custom_api_delete_confirm)
            .setPositiveButton(R.string.user_known) { _, _ ->
                // 用户API的索引需要减去内置API数量
                val userIndex = providerIndex - BuiltinProviders.providers.size
                ConfigurationStorage.deleteOpenAIProvider(prefs, userIndex)
                val currentIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                if (currentIndex == providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", 0)
                } else if (currentIndex > providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", currentIndex - 1)
                }
                UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
                requireActivity().finish()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
            .show()
    }
}
