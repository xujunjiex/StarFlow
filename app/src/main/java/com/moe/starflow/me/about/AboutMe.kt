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

package com.moe.starflow.me.about
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentAboutMeBinding
import com.moe.starflow.download.ModelDownloadManager
import com.moe.starflow.translate.FloatingBallService
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.UiUtils
import com.moe.starflow.utils.UpdateChecker
import com.moe.starflow.utils.UpdateResult
import com.moe.starflow.me.settings.SettingPageActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class AboutMe : Fragment() {
    private lateinit var binding: FragmentAboutMeBinding
    private lateinit var updateChecker: UpdateChecker

    private var downloadJob: Job? = null
    private var isDownloadCancelled = false
    private var isDownloading = false
    private var installerLaunched = false // 标记安装器是否已启动，防止 onResume 重复触发

    // 权限设置页跳转回调
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 从权限设置页返回，只提示结果，不自动安装
        if (canRequestPackageInstalls()) {
            LogCollector.d("AboutMe", "权限授权成功")
            UiUtils.showToast(requireContext(), getString(R.string.update_downloaded_ready), isShort = false)
        } else {
            LogCollector.d("AboutMe", "用户未授权安装权限")
            UiUtils.showToast(requireContext(), getString(R.string.update_downloaded_ready), isShort = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateChecker = UpdateChecker(requireContext())
        // 启动时清理旧下载
        cleanupOldDownloads()
    }

    override fun onResume() {
        super.onResume()
        // 不再自动触发安装，仅用户点击安装按钮才会跳转
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAboutMeBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButton()

        // 从其他页面跳转过来时自动检查更新
        val prefs = com.moe.starflow.utils.CustomPreference.getInstance(requireContext())
        if (prefs.getBoolean(ARG_AUTO_CHECK_UPDATE, false)) {
            prefs.setBoolean(ARG_AUTO_CHECK_UPDATE, false) // 只触发一次
            checkForUpdate()
        }
    }

    private fun setupButton(){
        binding.translateModeBtn.setOnClickListener{
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_TRANSLATE_MODE)
                startActivity(intent)
            }
        }
        binding.apiConfigBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_API_CONFIG)
                startActivity(intent)
            }
        }
        binding.personalizationBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
                startActivity(intent)
            }
        }
        binding.modelManagementBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT)
                startActivity(intent)
            }
        }
        binding.faqBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingPageActivity::class.java)
            intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_FAQ)
            startActivity(intent)
        }
        binding.updateBtn.setOnClickListener{
            UiUtils.showToast(requireContext(), getString(R.string.getting_update), isShort = false)
            checkForUpdate()
        }
        binding.developerBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingPageActivity::class.java)
            intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_DEVELOPER)
            startActivity(intent)
        }
        binding.viewLogsBtn.setOnClickListener {
            showLogViewerDialog()
        }
    }

    private fun showLogViewerDialog() {
        val logEntries = LogCollector.getAllLogs()
        val logs = LogCollector.getFormattedLogs()
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_viewer, null)
        val logContent = dialogView.findViewById<TextView>(R.id.log_content)
        val logCount = dialogView.findViewById<TextView>(R.id.log_count)

        // 统一日志检测：starflow.log 包含全部日志（Java + native + 崩溃 backtrace）
        val crashHint = buildCrashHint(LogCollector.crashLogFile)
        logCount.text = crashHint.ifEmpty {
            getString(R.string.log_count_format, logEntries.size)
        }

        if (logs.isEmpty()) {
            logContent.text = getString(R.string.log_empty)
        } else {
            val spannable = buildLogSpannable(logEntries)
            logContent.text = spannable
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.log_viewer_title)
            .setView(dialogView)
            .setNegativeButton(R.string.user_cancel, null)
            .create()

        dialogView.findViewById<View>(R.id.btn_copy).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("app_logs", logs))
            UiUtils.showToast(requireContext(), getString(R.string.log_copied), isShort = false)
        }

        dialogView.findViewById<View>(R.id.btn_clear).setOnClickListener {
            LogCollector.clear()
            logContent.text = getString(R.string.log_empty)
            logCount.text = getString(R.string.log_count_format, 0)
        }

        dialogView.findViewById<View>(R.id.btn_export).setOnClickListener {
            exportLogsToFile(logs)
        }

        dialog.show()
    }

    /**
     * 构建日志提示。有统一日志文件（starflow.log）时显示路径 + 大小，
     * 引导用户导出日志反馈给开发者。无文件返回空串（不打扰）。
     */
    private fun buildCrashHint(file: java.io.File?): String {
        val appLog = file?.takeIf { it.exists() && it.length() > 0 } ?: return ""
        return "⚠️ 日志文件：${appLog.absolutePath} (${appLog.length() / 1024}KB)\n\n"
    }

    /**
     * 构建带样式的日志 SpannableString：E 级别日志红色高亮，其余级别使用默认颜色。
     * 日志文本保持可选择，用户可长按并跨行框选后调用系统的复制/翻译动作。
     */
    private fun buildLogSpannable(entries: List<LogCollector.LogEntry>): SpannableString {
        val sb = StringBuilder()
        val errorRanges = mutableListOf<Pair<Int, Int>>() // (start, end) of each error line

        for (entry in entries) {
            val line = entry.format()
            val start = sb.length
            sb.append(line).append("\n")
            if (entry.level == "E") {
                errorRanges.add(start to sb.length)
            }
        }

        val fullText = sb.toString()
        val spannable = SpannableString(fullText)

        for ((start, end) in errorRanges) {
            // 红色高亮
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }

    private fun exportLogsToFile(logs: String) {
        try {
            val dir = java.io.File(requireContext().getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val file = java.io.File(dir, "moe_log_$timestamp.txt")

            // 统一日志文件内容（Java + native + 崩溃 backtrace 都在这里，单一来源）
            val unifiedLog = LogCollector.crashLogFile
            val content = when {
                unifiedLog?.exists() == true && unifiedLog.length() > 0 -> unifiedLog.readText()
                else -> logs
            }
            file.writeText(content)
            UiUtils.showToast(requireContext(), getString(R.string.log_exported, file.absolutePath), isShort = false)
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.log_export_failed, e.message ?: "未知错误"), isShort = false)
        }
    }

    private fun checkForUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> { showUpdateDialog(result) }
                is UpdateResult.NoUpdate -> { UiUtils.showToast(requireContext(), getString(R.string.no_update), isShort = false) }
                else -> { UiUtils.showToast(requireContext(), getString(R.string.internet_error), isShort = false) }
            }
        }
    }

    private fun showUpdateDialog(update: UpdateResult.UpdateAvailable) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_update, null)

        val titleText = dialogView.findViewById<TextView>(R.id.update_title)
        val descText = dialogView.findViewById<TextView>(R.id.update_description)
        val btnDownloadInstall = dialogView.findViewById<View>(R.id.btn_download_install)
        val btnGithubRelease = dialogView.findViewById<View>(R.id.btn_github_release)
        val btnBaidu = dialogView.findViewById<TextView>(R.id.btn_baidu)
        val btnQuark = dialogView.findViewById<TextView>(R.id.btn_quark)
        val cloudSectionTitle = dialogView.findViewById<TextView>(R.id.cloud_section_title)
        val cloudDelayHint = dialogView.findViewById<TextView>(R.id.cloud_delay_hint)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)

        // 设置内容
        titleText.text = "${getString(R.string.find_new_version)} v${update.versionName}"
        descText.visibility = View.GONE

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)

        // 直接下载安装（先检查权限）
        btnDownloadInstall.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            if (update.apkDownloadUrl.isEmpty()) {
                UiUtils.showToast(requireContext(), getString(R.string.update_download_failed, "No APK URL"), isShort = false)
                return@setOnClickListener
            }
            // 先检查安装权限
            if (!canRequestPackageInstalls()) {
                showInstallPermissionDialog {
                    // 用户授权后，开始下载
                    startApkDownload(update.apkDownloadUrl, dialogView, dialog, update.versionCode)
                }
                return@setOnClickListener
            }
            isDownloading = true
            installerLaunched = false
            btnDownloadInstall.isClickable = false
            btnDownloadInstall.alpha = 0.7f
            startApkDownload(update.apkDownloadUrl, dialogView, dialog, update.versionCode)
        }

        // GitHub Release 页面
        btnGithubRelease.setOnClickListener {
            val url = update.downloadUrl.ifEmpty { "https://github.com/xujunjiex/StarFlow/releases" }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // 网盘按钮
        var hasCloudLinks = false
        if (update.baiduUrl.isNotEmpty()) {
            btnBaidu.visibility = View.VISIBLE
            btnBaidu.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.baiduUrl)))
            }
            hasCloudLinks = true
        }
        if (update.quarkUrl.isNotEmpty()) {
            btnQuark.visibility = View.VISIBLE
            btnQuark.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.quarkUrl)))
            }
            hasCloudLinks = true
        }
        if (hasCloudLinks) {
            cloudSectionTitle.visibility = View.VISIBLE
            cloudDelayHint.visibility = View.VISIBLE
        }

        // 检查是否已有下载好的 APK（必须版本匹配）
        val existingApk = getPendingApk(update.versionCode)
        if (existingApk != null && canRequestPackageInstalls()) {
            // 已下载且有权限，显示安装按钮
            val downloadSubtitle = dialogView.findViewById<TextView>(R.id.download_subtitle)
            downloadSubtitle.text = getString(R.string.update_downloaded_ready)
            showInstallReadyUI(dialogView, existingApk)
        }

        // 暂不更新
        btnCancel.setOnClickListener {
            isDownloadCancelled = true
            isDownloading = false
            downloadJob?.cancel()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { win ->
            val maxW = (resources.displayMetrics.widthPixels * 0.90).toInt()
            win.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 检查是否有安装未知来源应用的权限
     */
    private fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().packageManager.canRequestPackageInstalls()
        } else {
            true // Android 8 以下不需要此权限
        }
    }

    /**
     * 显示安装权限说明弹窗
     * @param onAuthorized 用户授权后的回调（用于继续下载或安装）
     */
    private fun showInstallPermissionDialog(onAuthorized: (() -> Unit)? = null) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_content)
            .setCancelable(true)
            .setPositiveButton(R.string.update_go_authorize) { _, _ ->
                // 跳转到权限设置页
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${requireContext().packageName}")
                )
                // 保存回调，onResume 时检查权限并继续
                pendingInstallCallback = onAuthorized
                permissionLauncher.launch(settingsIntent)
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    // 待执行的安装回调（权限授权后继续）
    private var pendingInstallCallback: (() -> Unit)? = null

    /**
     * 获取待安装的 APK 文件（如果存在、合法且版本匹配）
     */
    private fun getPendingApk(expectedVersionCode: Long): java.io.File? {
        val downloadDir = java.io.File(requireContext().getExternalFilesDir(null), "downloads")
        val apkFile = java.io.File(downloadDir, "update.apk")
        if (!apkFile.exists() || apkFile.length() == 0L) return null
        // 检查 meta 文件中的版本号是否匹配
        val metaFile = java.io.File(downloadDir, "update.meta")
        if (metaFile.exists()) {
            try {
                val lines = metaFile.readText().lines()
                if (lines.size >= 2) {
                    val savedVersion = lines[1].trim().toLongOrNull() ?: 0L
                    if (savedVersion != expectedVersionCode) {
                        // 版本不匹配，删除旧文件
                        apkFile.delete()
                        metaFile.delete()
                        return null
                    }
                }
            } catch (_: Exception) {
                // meta 解析失败，无法验证版本，删除不安全的 APK
                apkFile.delete()
                metaFile.delete()
                return null
            }
        } else {
            // meta 文件缺失（可能下载过程中崩溃），无法验证版本
            apkFile.delete()
            return null
        }
        return apkFile
    }

    /**
     * 下载完成后显示安装按钮，用户点击才安装
     */
    private fun showInstallReadyUI(dialogView: View, apkFile: java.io.File) {
        val btnInstallNow = dialogView.findViewById<TextView>(R.id.btn_install_now)
        btnInstallNow.visibility = View.VISIBLE
        btnInstallNow.setOnClickListener {
            installApk(apkFile)
        }
    }

    /**
     * 启动系统安装器安装 APK
     */
    private fun installApk(apkFile: java.io.File) {
        try {
            installerLaunched = true
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                apkFile
            )
            LogCollector.d("AboutMe", "FileProvider URI: $uri")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            LogCollector.d("AboutMe", "安装器已启动")
        } catch (e: Exception) {
            LogCollector.e("AboutMe", "Failed to install APK: ${e.message}", e)
            installerLaunched = false
            UiUtils.showToast(requireContext(), getString(R.string.update_download_failed, e.message ?: ""), isShort = false)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun startApkDownload(
        apkUrl: String,
        dialogView: View,
        dialog: AlertDialog,
        versionCode: Long
    ) {
        val downloadSubtitle = dialogView.findViewById<TextView>(R.id.download_subtitle)
        val progressContainer = dialogView.findViewById<View>(R.id.download_progress_container)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.download_progress)
        val percentText = dialogView.findViewById<TextView>(R.id.download_percent)
        val speedText = dialogView.findViewById<TextView>(R.id.download_speed)
        val btnCancelDownload = dialogView.findViewById<TextView>(R.id.btn_cancel_download)
        val btnGithubRelease = dialogView.findViewById<View>(R.id.btn_github_release)
        val btnBaidu = dialogView.findViewById<TextView>(R.id.btn_baidu)
        val btnQuark = dialogView.findViewById<TextView>(R.id.btn_quark)
        val cloudSectionTitle = dialogView.findViewById<TextView>(R.id.cloud_section_title)
        val cloudDelayHint = dialogView.findViewById<TextView>(R.id.cloud_delay_hint)

        // 切换到下载中状态
        downloadSubtitle.text = getString(R.string.update_download_from_github)
        progressContainer.visibility = View.VISIBLE
        btnCancelDownload.visibility = View.VISIBLE
        progressBar.progress = 0
        percentText.text = getString(R.string.update_download_percent, 0)
        speedText.text = getString(R.string.update_download_speed, 0f)
        btnGithubRelease.isEnabled = false
        btnGithubRelease.alpha = 0.5f
        btnBaidu.isEnabled = false
        btnBaidu.alpha = 0.5f
        btnQuark.isEnabled = false
        btnQuark.alpha = 0.5f
        cloudSectionTitle.visibility = View.GONE
        cloudDelayHint.visibility = View.GONE

        isDownloadCancelled = false
        val handler = Handler(Looper.getMainLooper())

        // 下载到外部文件目录（不会被系统自动清理）
        val downloadDir = java.io.File(requireContext().getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        // 删除旧的下载文件，避免安装旧版本
        val destFile = java.io.File(downloadDir, "update.apk")
        if (destFile.exists()) destFile.delete()
        val oldMeta = java.io.File(downloadDir, "update.meta")
        if (oldMeta.exists()) oldMeta.delete()

        // 取消下载按钮
        btnCancelDownload.setOnClickListener {
            LogCollector.d("AboutMe", "用户取消下载")
            isDownloadCancelled = true
            downloadJob?.cancel()
            destFile.delete()
            resetDownloadUI(dialogView)
            btnCancelDownload.visibility = View.GONE
        }

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            LogCollector.d("AboutMe", "开始下载 APK: $apkUrl -> ${destFile.absolutePath}")
            val result = ModelDownloadManager.downloadModel(
                context = requireContext(),
                url = apkUrl,
                checksum = "", // GitHub assets 可信，不校验
                destFile = destFile,
                onProgress = object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        if (isDownloadCancelled) return
                        val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                        handler.post {
                            progressBar.progress = percent
                            percentText.text = getString(R.string.update_download_percent, percent)
                            speedText.text = getString(R.string.update_download_speed, speed)
                        }
                    }
                },
                maxRetries = 3
            )

            if (isDownloadCancelled) return@launch
            isDownloading = false

            if (result.isSuccess) {
                LogCollector.d("AboutMe", "APK 下载成功: ${destFile.length()} bytes")

                // 写入元数据文件（记录下载时间和目标版本号）
                val metaFile = java.io.File(downloadDir, "update.meta")
                metaFile.writeText("${System.currentTimeMillis()}\n$versionCode")

                // 下载完成，检查安装权限
                downloadSubtitle.text = getString(R.string.update_installing)
                progressBar.progress = 100
                percentText.text = getString(R.string.update_download_percent, 100)
                speedText.text = ""
                btnCancelDownload.visibility = View.GONE

                if (!canRequestPackageInstalls()) {
                    // 没有安装权限，提示用户授权
                    LogCollector.d("AboutMe", "下载完成但缺少安装权限，等待用户授权")
                    downloadSubtitle.text = getString(R.string.update_downloaded_ready)
                    showInstallPermissionDialog()
                    return@launch
                }

                // 有权限，提示用户点击安装
                downloadSubtitle.text = getString(R.string.update_downloaded_ready)
                btnCancelDownload.visibility = View.GONE
                // 显示安装按钮，用户点击才安装
                showInstallReadyUI(dialogView, destFile)
            } else {
                LogCollector.e("AboutMe", "APK download failed: ${result.exceptionOrNull()?.message}")
                resetDownloadUI(dialogView)
                btnCancelDownload.visibility = View.GONE
                UiUtils.showToast(requireContext(), getString(R.string.update_download_failed, result.exceptionOrNull()?.message ?: ""), isShort = false)
            }
        }
    }

    private fun resetDownloadUI(dialogView: View) {
        val downloadSubtitle = dialogView.findViewById<TextView>(R.id.download_subtitle)
        val progressContainer = dialogView.findViewById<View>(R.id.download_progress_container)
        val btnGithubRelease = dialogView.findViewById<View>(R.id.btn_github_release)
        val btnBaidu = dialogView.findViewById<TextView>(R.id.btn_baidu)
        val btnQuark = dialogView.findViewById<TextView>(R.id.btn_quark)
        val cloudSectionTitle = dialogView.findViewById<TextView>(R.id.cloud_section_title)
        val cloudDelayHint = dialogView.findViewById<TextView>(R.id.cloud_delay_hint)
        val btnDownloadInstall = dialogView.findViewById<View>(R.id.btn_download_install)
        val btnCancelDownload = dialogView.findViewById<TextView>(R.id.btn_cancel_download)

        isDownloading = false
        btnDownloadInstall.isClickable = true
        btnDownloadInstall.alpha = 1f
        downloadSubtitle.text = getString(R.string.update_download_from_github)
        progressContainer.visibility = View.GONE
        btnCancelDownload.visibility = View.GONE
        btnGithubRelease.isEnabled = true
        btnGithubRelease.alpha = 1f
        btnBaidu.isEnabled = true
        btnBaidu.alpha = 1f
        btnQuark.isEnabled = true
        btnQuark.alpha = 1f
        if (btnBaidu.visibility == View.VISIBLE || btnQuark.visibility == View.VISIBLE) {
            cloudSectionTitle.visibility = View.VISIBLE
            cloudDelayHint.visibility = View.VISIBLE
        }
    }

    /**
     * 清理超过 24 小时的旧下载文件
     */
    private fun cleanupOldDownloads() {
        try {
            val downloadDir = java.io.File(requireContext().getExternalFilesDir(null), "downloads")
            if (!downloadDir.exists()) return

            val metaFile = java.io.File(downloadDir, "update.meta")
            if (metaFile.exists()) {
                val lines = metaFile.readText().lines()
                val downloadTime = lines.getOrNull(0)?.toLongOrNull() ?: 0
                val ageHours = (System.currentTimeMillis() - downloadTime) / (1000 * 60 * 60)

                if (ageHours > 24) {
                    downloadDir.listFiles()?.forEach { it.delete() }
                    LogCollector.d("AboutMe", "清理超过 ${ageHours}h 的旧更新包")
                }
            } else {
                // 没有 meta 文件但有残留文件，清理
                if (downloadDir.listFiles()?.isNotEmpty() == true) {
                    downloadDir.listFiles()?.forEach { it.delete() }
                    LogCollector.d("AboutMe", "清理无 meta 的残留下载文件")
                }
            }
        } catch (e: Exception) {
            LogCollector.e("AboutMe", "清理旧下载失败: ${e.message}")
        }
    }

    companion object {
        const val ARG_AUTO_CHECK_UPDATE = "auto_check_update"

        /**
         * 清理下载目录（安装成功后调用）
         */
        fun cleanupDownloads(context: Context) {
            try {
                val downloadDir = java.io.File(context.getExternalFilesDir(null), "downloads")
                if (downloadDir.exists()) {
                    downloadDir.listFiles()?.forEach { it.delete() }
                    LogCollector.d("AboutMe", "安装完成，清理下载目录")
                }
            } catch (e: Exception) {
                LogCollector.e("AboutMe", "清理下载目录失败: ${e.message}")
            }
        }
    }

}
