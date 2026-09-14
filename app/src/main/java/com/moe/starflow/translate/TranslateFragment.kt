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

package com.moe.starflow.translate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.moe.starflow.utils.LogCollector
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.starflow.launch.FirstLaunchPage
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.me.about.AboutMe
import com.moe.starflow.utils.UpdateChecker
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentTranslateBinding
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.me.ManageActivity
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.NotificationChecker
import com.moe.starflow.utils.NotificationResult
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.ThemeManager
import com.moe.starflow.utils.UiUtils
import com.moe.starflow.utils.UpdateResult
import kotlinx.coroutines.launch
import java.io.File

val TAG = "TranslateFragment"

class TranslateFragment : Fragment() {
    private lateinit var binding: FragmentTranslateBinding
    private lateinit var updateChecker: UpdateChecker
    private lateinit var notificationChecker: NotificationChecker
    private lateinit var prefs: CustomPreference
    private lateinit var serviceStopReceiver: BroadcastReceiver
    private lateinit var mangaServiceStopReceiver: BroadcastReceiver
    private var languagePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    companion object {
        private var updateCheckedThisSession = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化广播接收器
        serviceStopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED) {
                    setTitleAndButton(false)
                }
            }
        }

        mangaServiceStopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BroadcastAction.ACTION_MANGA_SERVICE_STOPPED) {
                    setMangaButtonState(false)
                }
            }
        }

        // 创建文件夹
        val modelDir = File(requireContext().getExternalFilesDir(null), "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        prefs = CustomPreference.getInstance(requireContext())
        updateChecker = UpdateChecker(requireContext())
        notificationChecker = NotificationChecker()
    }

    override fun onStart() {
        super.onStart()

        LogCollector.d(TAG, "onStart")

        // 注册广播接收器
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            serviceStopReceiver,
            IntentFilter(BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED)
        )
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            mangaServiceStopReceiver,
            IntentFilter(BroadcastAction.ACTION_MANGA_SERVICE_STOPPED)
        )

        // 监听语言/OCR组/翻译引擎 prefs 变化：实时刷新首页语言标签 + 顶部双行状态栏
        if (languagePrefsListener == null) {
            languagePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "Source_Language" || key == "Target_Language") {
                    refreshLanguageLabels()
                }
                if (key == "Ocr_Engine_Group" || key == "Text_API" || key == "Text_AI") {
                    refreshEngineStatusBar()
                }
            }
        }
        prefs.getSharedPreferences().registerOnSharedPreferenceChangeListener(languagePrefsListener)

        // 主题按钮图标随上次选择（跟随系统/浅/暗）
        refreshThemeIcon(ThemeManager.currentMode(requireContext()))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTranslateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 显示版本号
        binding.versionText.text = "v${com.moe.starflow.BuildConfig.VERSION_NAME}"

        if (!updateCheckedThisSession) {
            updateCheckedThisSession = true
            checkForUpdate()
        }
        checkNotification()
        setTitleAndButton(ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java))
        setMangaButtonState(ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))

        val helpIntent = Intent(requireContext(), FirstLaunchPage::class.java)
        binding.help.setOnClickListener {
            startActivity(helpIntent)
        }

        binding.notice.setOnClickListener {
            UiUtils.showToast(requireContext(), getString(R.string.getting_notification), isShort = false)
            checkNotification(true)
        }

        binding.startButton.setOnClickListener {
            if (!ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                // Stop manga translation if running
                if (ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
                    MangaFloatingService.stop(requireContext())
                    setMangaButtonState(false)
                }
                // checkScreenshotMethod 放最后，因为它会弹出授权窗口并返回 false，
                // 授权成功后由 ScreenCapturePermissionActivity 自动启动服务
                if (checkAndroidSDK() && checkFloatingBall() && checkNotify() && checkTranslateAPI() && checkCombination() && checkScreenshotMethod("game")) {
                    if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt(
                            "Text_API",
                            Constants.TextApi.BING.id
                        ) == Constants.TextApi.AI.id) && (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.NLLB.id)
                    ) {
                        checkRAM()
                    }
                    launchFloatingBallService()
                }
            } else {
                stopFloatingBallService()
            }
        }

        // 漫画翻译按钮
        binding.mangaButton.setOnClickListener {
            if (!ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
                // Stop normal translation if running
                if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                    stopFloatingBallService()
                }
                // checkScreenshotMethod 放最后，因为它会弹出授权窗口并返回 false，
                // 授权成功后由 ScreenCapturePermissionActivity 自动启动服务
                if (checkAndroidSDK() && checkFloatingBall() && checkScreenshotMethod("manga")) {
                    MangaFloatingService.start(requireContext())
                    UiUtils.showToast(requireContext(), "漫画翻译已启动", isShort = false)
                    setMangaButtonState(true)
                }
            } else {
                MangaFloatingService.stop(requireContext())
                UiUtils.showToast(requireContext(), "漫画翻译已停止", isShort = false)
                setMangaButtonState(false)
            }
        }

        refreshLanguageLabels()
        refreshEngineStatusBar()

        binding.oriLanguage.setOnClickListener {
            showLanguageListDialog(1)
        }

        binding.tarLanguage.setOnClickListener {
            showLanguageListDialog(2)
        }

        // 全局主题三态切换（跟随系统/浅色/暗色）：主页右上角按钮
        binding.themeBtn.setOnClickListener {
            val mode = ThemeManager.cycle(requireContext())
            refreshThemeIcon(mode)
            UiUtils.showToast(requireContext(), themeLabel(mode), isShort = true)
        }
    }

    /** 主题三态按钮图标 + 无障碍描述（跟随系统/浅/暗）。 */
    private fun refreshThemeIcon(mode: String) {
        val res = when (mode) {
            ThemeManager.MODE_LIGHT -> R.drawable.ic_theme_light
            ThemeManager.MODE_DARK -> R.drawable.ic_theme_dark
            else -> R.drawable.ic_theme_auto
        }
        binding.themeBtn.setImageResource(res)
        binding.themeBtn.contentDescription = themeLabel(mode)
    }

    private fun themeLabel(mode: String): String = when (mode) {
        ThemeManager.MODE_LIGHT -> getString(R.string.theme_light)
        ThemeManager.MODE_DARK -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_follow_system)
    }

    /**
     * 刷新首页源/目标语言标签。从 prefs 读取当前值。
     * 调用时机：onViewCreated 初始化、首页手动选择语言、prefs 变化（悬浮窗内切换语言）时。
     */
    private fun refreshLanguageLabels() {
        if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt("Text_API", Constants.TextApi.BING.id) == Constants.TextApi.CUSTOM_TEXT.id)) {
            binding.SourceLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Source_Language", "ja")).getDisplayName()
            binding.TargetLanguageName.text = getString(R.string.custom_api_select_language)
        } else if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.PIC.id) && (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id) == Constants.PicApi.CUSTOM_PIC.id)) {
            binding.SourceLanguageName.text = getString(R.string.custom_api_select_language)
            binding.TargetLanguageName.text = getString(R.string.custom_api_select_language)
        } else {
            binding.SourceLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Source_Language", "ja")).getDisplayName()
            binding.TargetLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Target_Language", "zh")).getDisplayName()
        }
    }

    /**
     * 刷新首页 top_bar 双行状态栏：上=OCR 模型，下=翻译模型（位于通知/问号图标之间）。
     * 调用时机：onViewCreated 初始化、OCR组/翻译引擎 prefs 变化。
     */
    private fun refreshEngineStatusBar() {
        val group = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences())
        binding.selectedAPI.text = getString(R.string.engine_status_ocr, getString(group.labelRes))
        binding.engineSubtitle.text = getString(R.string.engine_status_translator, getCurrentTranslatorName())
    }

    /** 当前翻译模型名（NLLB/Hy-MT2/各 API），统一走 TranslatorNames。 */
    private fun getCurrentTranslatorName(): String =
        com.moe.starflow.translate.TranslatorNames.of(requireContext(), prefs)

    private fun checkForUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    LogCollector.d(TAG, "Update available: ${result.versionName}")
                    if (prefs.getLong("Ignore_Version", 0) != result.versionCode) showUpdateDialog(
                        result
                    )
                }

                is UpdateResult.NoUpdate -> {
                    LogCollector.d(TAG, "Already latest version")
                }

                is UpdateResult.Error -> {
                    LogCollector.e(TAG, "Update check failed (network error)")
                }
            }
        }
    }

    private fun checkNotification(userGet: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = notificationChecker.checkNotification()) {
                is NotificationResult.NotificationAvailable -> {
                    val readNotice = prefs.getLong("Read_Notice", 0)
                    LogCollector.d(TAG, "Notification available: code=${result.notificationCode}, readNotice=$readNotice, userGet=$userGet")
                    if (readNotice != result.notificationCode || userGet) {
                        showNotificationDialog(result)
                    } else {
                        LogCollector.d(TAG, "Notification already read, skip")
                    }
                }

                is NotificationResult.Error -> {
                    LogCollector.e(TAG, "Notification check failed")
                    if (userGet) UiUtils.showToast(requireContext(), getString(R.string.get_notification_error), isShort = false)
                }
            }
        }
    }

    private fun checkCombination(): Boolean =
        if (prefs.getString("Source_Language", "ja") == prefs.getString("Target_Language", "zh")) {
            UiUtils.showToast(requireContext(), getString(R.string.invalid_combination), isShort = false)
            false
        } else {
            true
        }

    private fun setTitleAndButton(isRunning: Boolean) {
        // selectedAPI/engineSubtitle 双行状态栏（OCR 模型/翻译模型）由 refreshEngineStatusBar() 统一管理，
        // 服务启停广播不覆盖（否则双行会被欢迎文案冲掉）
        refreshEngineStatusBar()
        if (!isRunning) {
            binding.startButton.text = getString(R.string.start_ball)
            binding.startButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            binding.startButton.text = getString(R.string.stop_ball)
            binding.startButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.red))
        }
    }

    private fun setMangaButtonState(isRunning: Boolean) {
        if (isRunning) {
            binding.mangaButton.text = "停止漫画翻译"
            binding.mangaButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.red))
        } else {
            binding.mangaButton.text = "漫画翻译"
            binding.mangaButton.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#6200EE"))
        }
    }

    private fun checkAndroidSDK(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.android_sdk_old_title)
                .setMessage(R.string.android_sdk_old_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            return false
        } else {
            return true
        }
    }

    private fun checkTranslateAPI(): Boolean {
        val translateMode = prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
        val picApi = prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)

        val ret: Boolean = when {
            translateMode == Constants.TranslateMode.TEXT.id -> when (textApi) {
                Constants.TextApi.AI.id -> {
                    // 用磁盘文件检查 NLLB 是否已完整下载，替代旧的 Download_NLLB 布尔标记
                    val nllbDownloaded = ModelDownloadRepository.getInstance(requireContext())
                        .isFullyDownloaded(ModelKey.NLLB_GROUP)
                    val hymt2Downloaded = ModelDownloadRepository.getInstance(requireContext())
                        .isFullyDownloaded(ModelKey.HY_MT2_GROUP)
                    if (textAi == Constants.TextAI.NLLB.id && !nllbDownloaded) {
                        LogCollector.d(TAG, "NLLB fully downloaded: $nllbDownloaded")
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.nllb_not_download_title)
                            .setMessage(R.string.nllb_not_download_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_download) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_NLLB
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else if (textAi == Constants.TextAI.HYMT2.id && !hymt2Downloaded) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.hymt2_not_download_title)
                            .setMessage(R.string.hymt2_not_download_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_download) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_HYMT2
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.BING.id -> {
                    true
                }

                Constants.TextApi.NIUTRANS.id -> {
                    if (prefs.getString("Niutrans_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.niuapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_NIU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.OPENAI.id -> {
                    val providerList = ConfigurationStorage.loadAllProviders(prefs)
                    val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
                    if (providerList.isEmpty() || selectedProvider >= providerList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.VOLC.id -> {
                    if (prefs.getString("Volc_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.volcapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_VOLC_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.AZURE.id -> {
                    if (prefs.getString("Azure_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.azureapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_AZURE_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.DEEPL.id -> {
                    if (prefs.getString("DeepL_Translate_APIKEY_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.deeplapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_DEEPL_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.BAIDU.id -> {
                    if (prefs.getString("Baidu_Translate_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.baiduapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.TENCENT.id -> {
                    if (prefs.getString("Tencent_Cloud_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.tencentapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                    val selectedIndex = prefs.getInt("Custom_Text_API", 0)
                    if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }
            }

            else -> when (picApi) {
                Constants.PicApi.BAIDU.id -> {
                    if (prefs.getString("Baidu_Translate_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.baiduapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.PicApi.TENCENT.id -> {
                    if (prefs.getString("Tencent_Cloud_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.tencentapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                    val selectedIndex = prefs.getInt("Custom_Pic_API", 0)
                    if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }
            }
        }
        return ret
    }

    private fun checkAccessibilityService(): Boolean {
        val accessibilityManager =
            requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        // 用 ComponentName 对象对比，兼容各厂商返回的 ID 格式差异
        // （Xiaomi/HyperOS 返回短格式 com.moe.starflow/.translate.xxx，
        //  原生 Android 返回完整格式 com.moe.starflow/com.moe.starflow.translate.xxx）
        val expected = android.content.ComponentName(
            requireContext(),
            ScreenShotAccessibilityService::class.java
        )
        val ret = enabledServices.any {
            expected == android.content.ComponentName.unflattenFromString(it.id)
        }
        LogCollector.d(TAG, "enabled: ${enabledServices.map { it.id }}, expected: $expected, result: $ret")
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_accessibility_service_title)
                .setMessage(R.string.not_accessibility_service_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    // 检查截图方式是否可用
    // 对于 MediaProjection 模式：始终弹出授权窗口，授权成功后由 ScreenCapturePermissionActivity 自动启动服务
    // 返回 false 表示异步处理（授权窗口已弹出），返回 true 表示可直接继续
    private fun checkScreenshotMethod(serviceType: String): Boolean {
        val method = prefs.getString("Screenshot_Method", "0").toIntOrNull() ?: 0
        return when (method) {
            0 -> {
                // MediaProjection 模式：始终弹出授权窗口
                // 不再检查 MediaProjectionIntentHolder.intent 是否已存在，
                // 每次点击都重新授权，确保用户知情同意
                ScreenCapturePermissionActivity.start(requireContext(), serviceType)
                false  // 异步处理，授权结果由 ScreenCapturePermissionActivity.onActivityResult 处理
            }
            1 -> {
                // AccessibilityService 模式：检查服务是否开启
                checkAccessibilityService()
            }
            else -> false
        }
    }

    private fun checkFloatingBall(): Boolean {
        // 检测是否有悬浮窗权限
        val ret = Settings.canDrawOverlays(requireContext())
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_floating_title)
                .setMessage(R.string.not_floating_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    private fun checkNotify(): Boolean {
        val notificationManager =
            requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ret = notificationManager.areNotificationsEnabled()
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_notify_title)
                .setMessage(R.string.not_notify_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    private fun checkRAM() {
        val activityManager =
            requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryGB = memoryInfo.totalMem / (1024 * 1024 * 1024.0)

        if (totalMemoryGB < 6) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.small_ram_title)
                .setMessage(R.string.small_ram_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
    }

    private fun showUpdateDialog(update: UpdateResult.UpdateAvailable) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.find_new_version)
            .setMessage(
                getString(R.string.version_name) + update.versionName + "\n${update.versionDescription}\n" + getString(
                    R.string.update_prompt
                )
            )
            .setCancelable(false)
            .setNeutralButton(R.string.ignore_update) { _, _ ->
                prefs.setLong("Ignore_Version", update.versionCode)
            }
            .setPositiveButton(R.string.go_to_update) { _, _ ->
                prefs.setBoolean(AboutMe.ARG_AUTO_CHECK_UPDATE, true)
                requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.me_fragment
            }
            .setNegativeButton(R.string.not_update, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.let { win ->
            val dm = resources.displayMetrics
            val maxW = (dm.widthPixels * 0.90).toInt()
            val maxH = (dm.heightPixels * 0.70).toInt()
            win.setLayout(maxW, maxH)
        }
    }

    private fun showNotificationDialog(notification: NotificationResult.NotificationAvailable) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(notification.notificationName)
            .setMessage(android.text.Html.fromHtml(notification.notificationContent, android.text.Html.FROM_HTML_MODE_LEGACY))
            .setCancelable(false)
            .setPositiveButton(R.string.user_known) { _, _ ->
                prefs.setLong("Read_Notice", notification.notificationCode)
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.let { win ->
            val dm = resources.displayMetrics
            val maxW = (dm.widthPixels * 0.90).toInt()
            // 高度用 WRAP_CONTENT 让公告内容少时自适应、内容多时由 setMessage 内置 ScrollView 自动滚动，
            // 避免固定 maxH 导致短内容时底部大片空白（CLAUDE.md 「弹窗高度」踩坑）
            val desiredH = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            win.setLayout(maxW, desiredH)
        }
    }

    private fun showLanguageListDialog(type: Int) {
        if (((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt(
                "Text_API",
                Constants.TextApi.BING.id
            ) == Constants.TextApi.CUSTOM_TEXT.id) && (type == 2)) || ((prefs.getInt(
                "Translate_Mode",
                Constants.TranslateMode.TEXT.id
            ) == Constants.TranslateMode.PIC.id) && (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id) == Constants.PicApi.CUSTOM_PIC.id))
        ) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.custom_api_select_language_title)
                .setMessage(R.string.custom_api_select_language_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        } else {
            // 源语言(type=1)：按当前 OCR 组排序置灰；目标语言(type=2)：按翻译模型过滤
            val ocrGroup = if (type == 1) com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences()) else null
            val locales = TranslateTools.getLanguagesList(requireContext(), type, ocrGroup) ?: return
            LogCollector.d(TAG, locales.toString())
            val disabledTargets = if (type == 2) TranslateTools.getDisabledTargetLangs(prefs) else emptySet()
            // Hy-MT2 只支持官方 38 种目标语言：用白名单（supportedCodes）置灰其余 ~30 种，避免选了模型不支持的语种输出垃圾
            val isHyMt2 = prefs.getInt("Text_API", Constants.TextApi.BING.id) == Constants.TextApi.AI.id &&
                prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.HYMT2.id
            val enabled = when (type) {
                1 -> locales.map { ocrGroup!!.sourceLangs.contains(it.getOriCode()) }
                2 -> if (isHyMt2) {
                    locales.map { it.getOriCode() in translationapi.hymt2translation.HyMt2Languages.supportedCodes }
                } else {
                    locales.map { it.getOriCode() !in disabledTargets }
                }
                else -> null
            }
            LanguageSelectionDialog(
                requireContext(),
                type,
                locales,
                onLanguageSelected = { selectedLocale ->
                    if (type == 1) {
                        LogCollector.d(TAG, "Source_Language：" + selectedLocale.getOriCode())
                        prefs.setString("Source_Language", selectedLocale.getOriCode())
                        binding.SourceLanguageName.text =
                            CustomLocale.getInstance(prefs.getString("Source_Language", "ja"))
                                .getDisplayName()
                    } else {
                        LogCollector.d(TAG, "Target_Language：" + selectedLocale.getOriCode())
                        prefs.setString("Target_Language", selectedLocale.getOriCode())
                        binding.TargetLanguageName.text =
                            CustomLocale.getInstance(prefs.getString("Target_Language", "zh"))
                                .getDisplayName()
                    }
                },
                enabled = enabled,
                onDisabledClick = when (type) {
                    1 -> { locale ->
                        val supportedNames = com.moe.starflow.manga.config.OcrEngineGroup.entries
                            .filter { it.sourceLangs.contains(locale.getOriCode()) }
                            .joinToString(" / ") { getString(it.labelRes) }
                        val msg = if (supportedNames.isEmpty()) {
                            "该语言当前 OCR 模型不支持"
                        } else {
                            "该语言当前 OCR 模型不支持，请使用 $supportedNames"
                        }
                        AlertDialog.Builder(requireContext())
                            .setMessage(msg)
                            .setPositiveButton(R.string.user_known, null)
                            .create().also { it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }.show()
                    }
                    2 -> { locale ->
                        AlertDialog.Builder(requireContext())
                            .setMessage("该语言当前翻译模型不支持，请使用 NLLB 或 API 翻译")
                            .setPositiveButton(R.string.user_known, null)
                            .create().also { it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }.show()
                    }
                    else -> null
                }
            ).show()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()

        LogCollector.d(TAG, "onStop")

        // 注销广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(serviceStopReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(mangaServiceStopReceiver)

        // 注销语言 prefs 监听（onStart 注册，onStop 注销，与 binding 生命周期对齐）
        languagePrefsListener?.let { prefs.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(it) }
    }

    override fun onResume() {
        super.onResume()

        LogCollector.d(TAG, "onResume")

        // 刷新语言标签：后台期间（onStop 未监听时）悬浮窗切换语言后回到首页，prefs 变化事件已错过
        refreshLanguageLabels()
        setTitleAndButton(ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java))
        setMangaButtonState(ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))
    }

    // 实际启动服务的方法
    private fun launchFloatingBallService() {
        try {
            // 检查服务是否已经在运行
            if (!ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                val serviceIntent = Intent(requireContext(), FloatingBallService::class.java)
                requireContext().startService(serviceIntent)
                UiUtils.showToast(requireContext(), getString(R.string.startup_success), isShort = true)
                setTitleAndButton(true)
            } else {
                setTitleAndButton(true)
                UiUtils.showToast(requireContext(), "already running", isShort = false)
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.startup_failure, e.toString()), isShort = false)
        }
    }

    private fun stopFloatingBallService() {
        try {
            // 检查服务是否已经停止
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                val intent = Intent(requireContext(), FloatingBallService::class.java)
                requireContext().stopService(intent)
                UiUtils.showToast(requireContext(), getString(R.string.stop_success), isShort = true)
                setTitleAndButton(false)
            } else {
                setTitleAndButton(false)
                UiUtils.showToast(requireContext(), "already stopped", isShort = false)
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.stop_failed, e.toString()), isShort = false)
        }
    }
}