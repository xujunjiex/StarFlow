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

package com.moe.starflow.me.settings
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import com.moe.starflow.R
import com.moe.starflow.translate.screenshot.AccessibilityServiceManager
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.widget.Dialogs
import com.moe.starflow.translate.FloatingBallService
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LanguageManager
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.UiUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class PersonalizationConfig : PreferenceFragmentCompat() {

    companion object {
        // 截图方式：0 = MediaProjection, 1 = AccessibilityService
        private const val SCREENSHOT_METHOD_KEY = "Screenshot_Method"
        private const val SCREENSHOT_METHOD_MEDIAPROJECTION = 0
        private const val SCREENSHOT_METHOD_ACCESSIBILITY = 1

        object BroadcastAction {
            const val ACTION_FLOATING_BALL_ICON_CHANGED = "action_floating_ball_icon_changed"
            const val EXTRA_ICON_KEY = "extra_icon_key"
        }
    }

    private lateinit var prefs: CustomPreference
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }
    private lateinit var ballIconGame: PreferenceWithPreview
    private lateinit var ballIconComic: PreferenceWithPreview
    private var pendingIconKey: String? = null
    private lateinit var ballPress: Preference
    private lateinit var resultFont: Preference
    private lateinit var resultFontSize: Preference
    private lateinit var showSource: ListPreference
    private lateinit var pixelStabilityCheck: SwitchPreference
    private lateinit var pixelThreshold: ListPreference
    private lateinit var pixelCheckInterval: ListPreference
    private lateinit var mangaTextColor: ColorPreferenceCompat
    private lateinit var mangaBgColor: ColorPreferenceCompat
    private lateinit var gestureSingleClick: ListPreference
    private lateinit var gestureDoubleClick: ListPreference
    private lateinit var gestureLongPress: ListPreference

    private lateinit var languagePreference: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = CustomPreference.getInstance(requireContext())
        setPreferencesFromResource(R.xml.personalization, rootKey)

        ballIconGame = findPreference<PreferenceWithPreview>("Icon_Game")!!
        ballIconComic = findPreference<PreferenceWithPreview>("Icon_Comic")!!
        ballPress = findPreference<Preference>("floating_ball_press")!!
        resultFont = findPreference<Preference>("result_font")!!
        resultFontSize = findPreference<Preference>("result_font_size")!!
        showSource = findPreference<ListPreference>("show_source_text")!!
        pixelStabilityCheck = findPreference<SwitchPreference>("pixel_stability_check")!!
        pixelThreshold = findPreference<ListPreference>("pixel_threshold")!!
        pixelCheckInterval = findPreference<ListPreference>("pixel_check_interval")!!
        languagePreference = findPreference<ListPreference>("app_language")!!

        // 悬浮球图片
        ballIconGame.setOnPreferenceClickListener {
            showBallOptionsDialog("Icon_Game")
            true
        }
        ballIconComic.setOnPreferenceClickListener {
            showBallOptionsDialog("Icon_Comic")
            true
        }

        ballPress.setOnPreferenceClickListener {
            showPressDialog()
            true
        }

        // 字体相关（FloatingBallService 监听 prefs 变化实时更新 view，无需先停服务）
        resultFont.setOnPreferenceClickListener {
            showFontOptionsDialog()
            true
        }

        // 字体大小（同上）
        resultFontSize.setOnPreferenceClickListener {
            showFontSizeDialog()
            true
        }

        // UI 同步字体：切换后需重启应用生效，弹窗提示
        findPreference<SwitchPreference>("ui_apply_custom_font")?.setOnPreferenceChangeListener { _, _ ->
            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.ui_apply_custom_font_restart_hint))
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            dialog.show()
            true  // 允许更改（系统写 prefs）
        }

        // 字体颜色
        findPreference<ColorPreferenceCompat>("result_view_font_color")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.setInt("Custom_Result_Font_Color", newValue as Int)
                true
            }
            summary = getString(R.string.font_color_summary)
        }

        // 背景颜色
        findPreference<ColorPreferenceCompat>("result_view_background_color")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.setInt("Custom_Result_Background_Color", newValue as Int)
                true
            }
            summary = getString(R.string.result_background_color_summary)
        }

        // 显示原文
        showSource.setOnPreferenceChangeListener { _, newValue ->
            prefs.setInt("Custom_Show_Source_Mode", newValue.toString().toInt())
            true
        }
        showSource.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
            getString(R.string.show_source_text_summary, showSource.entry)
        }

        // 文字阴影
        findPreference<SwitchPreference>("text_shadow_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.setBoolean("text_shadow_enabled", newValue as Boolean)
                true
            }
        }

        // 翻页稳定性检测
        pixelStabilityCheck.setOnPreferenceChangeListener { _, newValue ->
            prefs.setBoolean("pixel_stability_check", newValue as Boolean)
            true
        }

        // 像素变化阈值
        pixelThreshold.setOnPreferenceChangeListener { _, newValue ->
            prefs.setInt("Game_Pixel_Similar_Threshold", newValue.toString().toInt())
            true
        }
        pixelThreshold.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
            getString(R.string.pixel_threshold_summary, pixelThreshold.entry)
        }

        // 检测间隔
        pixelCheckInterval.setOnPreferenceChangeListener { _, newValue ->
            prefs.setInt("Game_Pixel_Check_Interval", newValue.toString().toInt())
            true
        }
        pixelCheckInterval.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
            getString(R.string.pixel_check_interval_summary, pixelCheckInterval.entry)
        }

        // AI 上下文设置
        findPreference<SwitchPreference>("game_context_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.setBoolean("game_context_enabled", newValue as Boolean)
                true
            }
        }
        findPreference<ListPreference>("game_context_count")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.setString("game_context_count", newValue.toString())
                true
            }
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                getString(R.string.game_context_count_summary, pref.entry)
            }
        }

        // 翻译通知 - 提示位置和时长
        findPreference<ListPreference>("Status_Position")?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entry ?: ""
            }
        }
        findPreference<ListPreference>("Status_Duration")?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entry ?: ""
            }
        }

        // 漫画翻译结果颜色
        mangaTextColor = findPreference("manga_text_color")!!
        mangaBgColor = findPreference("manga_bg_color")!!

        // 悬浮球手势配置
        gestureSingleClick = findPreference("Ball_Gesture_Single_Click")!!
        gestureDoubleClick = findPreference("Ball_Gesture_Double_Click")!!
        gestureLongPress = findPreference("Ball_Gesture_Long_Press")!!
        setupGesturePreference(gestureSingleClick, "Ball_Gesture_Single_Click")
        setupGesturePreference(gestureDoubleClick, "Ball_Gesture_Double_Click")
        setupGesturePreference(gestureLongPress, "Ball_Gesture_Long_Press")

        mangaTextColor.setOnPreferenceChangeListener { _, newValue ->
            prefs.setInt("Manga_Text_Color", newValue as Int)
            true
        }

        mangaBgColor.setOnPreferenceChangeListener { _, newValue ->
            prefs.setInt("Manga_BG_Color", newValue as Int)
            true
        }

        ballIconGame.refreshPreview()
        ballIconComic.refreshPreview()
        updatePressSummary()
        updateFontSummary()
        updateFontSizeSummary()
        setupLanguagePreference()
    }

    /**
     * 设置语言选择功能
     */
    private fun setupLanguagePreference() {
        // 设置当前选中的语言
        val currentLanguage = LanguageManager.getAppLanguage(requireContext())
        languagePreference.value = currentLanguage

        // 更新摘要显示
        languagePreference.summaryProvider = Preference.SummaryProvider<ListPreference> {
            getString(R.string.language_setting_summary, CustomLocale.getInstance(it.value).getDisplayName())
        }

        // 监听语言变化
        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            val newLanguage = newValue as String

            // 如果语言没有变化，直接返回
            if (newLanguage == LanguageManager.getAppLanguage(requireContext())) {
                return@setOnPreferenceChangeListener true
            }

            // 显示确认对话框
            showLanguageChangeDialog(newLanguage)
            false // 暂时不改变值，等待用户确认
        }
    }

    /**
     * 显示语言切换确认对话框
     */
    private fun showLanguageChangeDialog(newLanguage: String) {
        val languageName = CustomLocale.getInstance(newLanguage).getDisplayName()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.language_change_title)
            .setMessage(getString(R.string.language_change_message, languageName))
            .setCancelable(false)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // 保存语言设置
                LanguageManager.setAppLanguage(requireContext(), newLanguage, true)

                // 更新 Preference 的值
                languagePreference.value = newLanguage

                // 提示用户
                UiUtils.showToast(requireContext(), getString(R.string.language_changed), isShort = true)

                // 退出应用，用户重新打开时会应用新语言
                activity?.let { LanguageManager.exitApplication(it) }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun refreshIconPreviews() {
        ballIconGame.refreshPreview()
        ballIconComic.refreshPreview()
    }

    private fun updatePressSummary() {
        ballPress.summary = getString(R.string.floating_ball_press_summary, prefs.getLong("Custom_Long_Press_Delay", 300L).toString())
    }

    private fun updateFontSummary() {
        val base = if (prefs.getString("Custom_Result_Font", "") == "") {
            getString(R.string.font_summary, getString(R.string.font_default))
        } else {
            getString(R.string.font_summary, prefs.getString("Custom_Result_Font", ""))
        }
        resultFont.summary = base
    }

    private fun updateFontSizeSummary() {
        resultFontSize.summary = getString(
            R.string.font_size_summary,
            prefs.getFloat("Custom_Result_Font_Size", 16f).toString(),
            Dialogs.FONT_SIZE_MIN.toInt().toString(),
            Dialogs.FONT_SIZE_MAX.toInt().toString()
        )
    }

    // 手势 key 列表，用于互换逻辑
    private val gestureKeys = listOf("Ball_Gesture_Single_Click", "Ball_Gesture_Double_Click", "Ball_Gesture_Long_Press")
    private val gesturePrefs = mutableListOf<ListPreference>()

    private fun setupGesturePreference(pref: ListPreference, key: String) {
        gesturePrefs.add(pref)
        pref.setOnPreferenceChangeListener { _, newValue ->
            if (isAnyTranslationServiceRunning()) {
                UiUtils.showToast(requireContext(), getString(R.string.stop_service_first), isShort = true)
                false
            } else {
                val newAction = newValue.toString().toInt()
                val otherPrefs = gestureKeys.filter { it != key }
                // 检查是否有冲突，有则互换
                for (otherKey in otherPrefs) {
                    val otherValue = try { prefs.getString(otherKey, "0").toInt() } catch (_: Exception) { 0 }
                    if (otherValue == newAction) {
                        val oldValue = try { prefs.getString(key, "0").toInt() } catch (_: Exception) { 0 }
                        prefs.setString(otherKey, oldValue.toString())
                        val otherPref = gesturePrefs.firstOrNull { it.key == otherKey }
                        if (otherPref != null) updateGestureSummary(otherPref, oldValue)
                        break
                    }
                }
                prefs.setString(key, newAction.toString())
                updateGestureSummary(pref, newAction)
                true
            }
        }
        val currentValue = try { prefs.getString(key, "0").toInt() } catch (_: Exception) { 0 }
        updateGestureSummary(pref, currentValue)
    }

    private fun updateGestureSummary(pref: ListPreference, value: Int) {
        val actionName = when (Constants.BallAction.fromValue(value)) {
            Constants.BallAction.TRANSLATE -> getString(R.string.ball_action_translate)
            Constants.BallAction.MENU -> getString(R.string.ball_action_menu)
            Constants.BallAction.AUTO_TRANSLATE -> getString(R.string.ball_action_auto_translate)
            Constants.BallAction.CLOSE_FLOATING -> getString(R.string.ball_action_close)
        }
        pref.summary = getString(R.string.ball_gesture_summary, actionName)
    }


    private fun showBallOptionsDialog(prefKey: String){
        val options = arrayOf(getString(R.string.ball_icon_default), getString(R.string.pic_choose))
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ball_setting)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handleDefaultIcon(prefKey)
                    1 -> pickPicFile(prefKey)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showPressDialog() {
//        val input = EditText(requireContext()).apply {
//            hint = getString(R.string.current_judgment_time, prefs.getLong("Custom_Long_Press_Delay", 300L).toString())
//            inputType = android.text.InputType.TYPE_CLASS_NUMBER
//
//            // 设置padding
//            val padding = TypedValue.applyDimension(
//                TypedValue.COMPLEX_UNIT_DIP,
//                16f,
//                resources.displayMetrics
//            ).toInt()
//            setPadding(padding, padding, padding, padding)
//        }

        val customView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_message_edittext, null)
        customView.findViewById<TextView>(R.id.dialog_top_message).apply {
            text = getString(R.string.int_only)
        }
        val input = customView.findViewById<EditText>(R.id.dialog_bottom_edittext).apply {
            hint = getString(R.string.current_judgment_time, prefs.getLong("Custom_Long_Press_Delay", 300L).toString())
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.set_floating_ball_press)
            .setView(customView)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    val value = input.text.toString().toLong()
                    prefs.setLong("Custom_Long_Press_Delay", value)
                    updatePressSummary()
                } catch (e: Exception) {
                    UiUtils.showToast(requireContext(), getString(R.string.font_size_invalid), isShort = true)
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showFontOptionsDialog() {
        val options = arrayOf(getString(R.string.font_default), getString(R.string.font_choose))
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_setting)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handleSystemFont()
                    1 -> pickFontFile()
                }
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showFontSizeDialog(){
        val dialog = Dialogs.fontSizeDialog(requireContext(), null){
            updateFontSizeSummary()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun handleDefaultIcon(prefKey: String) {
        // 1. 拿到旧文件名（如果有）
        val oldName = prefs.getString(prefKey, "")
        // 2. 清除 SharedPreferences 中的值
        prefs.setString(prefKey, "")
        // 3. 只删除旧文件，不动整个目录（避免误删另一模式的图）
        if (oldName.isNotEmpty()) {
            val oldFile = File(requireContext().getExternalFilesDir(null), "icon/$oldName")
            if (oldFile.exists()) oldFile.delete()
        }
        // 4. 通知活跃 Service 重读默认值
        LocalBroadcastManager.getInstance(requireContext())
            .sendBroadcast(Intent(BroadcastAction.ACTION_FLOATING_BALL_ICON_CHANGED).putExtra(BroadcastAction.EXTRA_ICON_KEY, prefKey))
        // 5. 更新两个预览（防止另一模式也持有相同摘要）
        refreshIconPreviews()
    }

    private fun handleSystemFont() {
        // 清除自定义字体设置
        prefs.setString("Custom_Result_Font", "")

        // 删除存在的字体文件，节省空间
        File(requireContext().getExternalFilesDir(null), "font").apply {
            if (!exists()) {
                mkdirs()
            } else {
                // 删除目录中的所有文件
                listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        }
        // 更新summary
        updateFontSummary()
    }

    private fun pickPicFile(prefKey: String) {
        pendingIconKey = prefKey
        pickFileLauncher.launch("image/*")
    }

    private fun pickFontFile() {
        pickFileLauncher.launch("font/ttf")
    }

    private fun handleFileSelection(uri: Uri) {
        try {
            // Snapshot and clear pendingIconKey up front so it doesn't leak across paths.
            val iconKey = pendingIconKey ?: ""
            pendingIconKey = null

            // 获取原始文件名
            val originalFileName = requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: throw IllegalStateException("get file name error.")

            // 获取文件扩展名并转换为小写
            val fileExtension = originalFileName.substringAfterLast('.', "").lowercase()

            when (fileExtension) {
                "ttf" -> handleFontFile(uri, originalFileName)
                else -> handleImageFile(uri, originalFileName, iconKey)
            }
        } catch (e: Exception) {
            // 通用错误处理
            UiUtils.showToast(requireContext(), e.message ?: getString(R.string.error_unknown), isShort = false)
        }
    }

    private fun handleFontFile(uri: Uri, originalFileName: String) {
        // 创建字体目录
        val fontDir = File(requireContext().getExternalFilesDir(null), "font").apply {
            if (!exists()) {
                mkdirs()
            } else {
                // 删除目录中的所有文件
                listFiles()?.forEach { it.delete() }
            }
        }

        // 复制文件
        val destinationFile = File(fontDir, originalFileName)
        copyFile(uri, destinationFile)

        // 更新配置
        prefs.setString("Custom_Result_Font", originalFileName)
        updateFontSummary()
        UiUtils.showToast(requireContext(), getString(R.string.set_success), isShort = true)
    }

    private fun handleImageFile(uri: Uri, originalFileName: String, prefKey: String = "") {
        val effectiveKey = if (prefKey.isNotEmpty()) prefKey else "Custom_Floating_Pic"

        // 1. 删除被替换的旧文件
        val oldName = prefs.getString(effectiveKey, "")
        if (oldName.isNotEmpty() && oldName != originalFileName) {
            val oldFile = File(requireContext().getExternalFilesDir(null), "icon/$oldName")
            if (oldFile.exists()) oldFile.delete()
        }

        // 2. 写入新文件
        val iconDir = File(requireContext().getExternalFilesDir(null), "icon").apply {
            if (!exists()) mkdirs()
        }
        val destinationFile = File(iconDir, originalFileName)
        copyFile(uri, destinationFile)

        // 3. 保存到对应的 key
        prefs.setString(effectiveKey, originalFileName)

        // 4. 通知活跃 Service
        LocalBroadcastManager.getInstance(requireContext())
            .sendBroadcast(Intent(BroadcastAction.ACTION_FLOATING_BALL_ICON_CHANGED).putExtra(BroadcastAction.EXTRA_ICON_KEY, effectiveKey))

        // 5. 刷新预览
        refreshIconPreviews()
        UiUtils.showToast(requireContext(), getString(R.string.set_success), isShort = true)
    }

    // 文件复制方法
    private fun copyFile(uri: Uri, destinationFile: File) {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destinationFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("open file error")
    }

    private fun isAnyTranslationServiceRunning(): Boolean {
        return AccessibilityServiceManager.getService() != null &&
                (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) ||
                 ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))
    }

}