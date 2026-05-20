package com.moe.moetranslator.manga

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.MainActivity
import com.moe.moetranslator.R
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.CropView
import com.moe.moetranslator.translate.Dialogs
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.bingtranslation.BingTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.volctranslation.VolcTranslation
import translationapi.azuretranslation.AzureTranslation
import translationapi.deepltranslation.DeepLTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.tencentcloud.TencentTranslationText
import translationapi.customtranslation.CustomTranslationText
import translationapi.mlkittranslation.MLKitTranslation
import translationapi.nllbtranslation.NLLBTranslation
import kotlin.math.abs

class MangaFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "MangaFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "manga_floating_service"
        private const val NOTIFICATION_ID = 7

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MangaFloatingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MangaFloatingService::class.java))
        }
    }

    private var longPressDelay = 500L

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBallView: View
    private lateinit var resultOverlayView: ImageView

    private var floatingBallParams: WindowManager.LayoutParams? = null
    private var resultOverlayParams: WindowManager.LayoutParams? = null

    private var ballInitialX = 0
    private var ballInitialY = 0
    private var ballInitialTouchX = 0f
    private var ballInitialTouchY = 0f

    private var isProcessing = false
    private var isResultShowing = false
    private var isMenuShowing = false

    // Progress overlay
    private lateinit var progressOverlayView: android.widget.TextView
    private var progressOverlayParams: WindowManager.LayoutParams? = null
    private var isProgressShowing = false

    // Long press detection
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }
    private var currentGesture: GestureType? = null

    // Auto-translate
    private var isAutoTranslating = false
    private var lastOcrText = ""
    private val autoTranslateHandler = Handler(Looper.getMainLooper())
    private val autoTranslateRunnable = object : Runnable {
        override fun run() {
            if (isAutoTranslating) {
                // 如果翻译结果正在显示，跳过本次触发，避免截到翻译图层
                if (!isResultShowing && !isMenuShowing) {
                    triggerTranslation()
                }
                autoTranslateHandler.postDelayed(this, prefs.getLong("Auto_Translate_Interval", 3000L))
            }
        }
    }

    // Crop selection
    private lateinit var cropView: CropView
    private var cropViewParams: WindowManager.LayoutParams? = null
    private var cropRect: RectF? = null
    private var isCropActive = false
    private var cropConfirmView: View? = null

    private lateinit var prefs: CustomPreference
    private lateinit var config: MangaModeConfig
    private var translatorText: TranslationTextAPI? = null

    private val defaultSystemPrompt = "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n1、根据用户的要求，将文本翻译成指定的目标语言；\n2、保持原意和语气；\n3、尽可能保持格式和结构；\n4、直接返回翻译后的文本，不要有任何解释或附加内容；\n5、如果文本已经是目标语言，请按原样返回。"
    private val defaultUserPrompt = "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    private sealed class GestureType {
        object Click : GestureType()
        object LongPress : GestureType()
        object Drag : GestureType()
    }

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        prefs = CustomPreference.getInstance(this)
        config = loadConfig()
        initTranslator()

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.moetranslator.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initializeViews()
        setupScreenshotCollector()

        Log.d(TAG, "MangaFloatingService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllViews()
        translatorText?.release()
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent("action_manga_floating_service_stopped")
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        Log.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    private fun reloadConfig() {
        config = loadConfig()
        translatorText?.release()
        initTranslator()
    }

    private fun initTranslator() {
        Log.d(TAG, "initTranslator: Text_API=${prefs.getInt("Text_API", Constants.TextApi.BING.id)}")
        try {
            when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
                Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)) {
                    Constants.TextAI.MLKIT.id -> translatorText = MLKitTranslation()
                    Constants.TextAI.NLLB.id -> translatorText = NLLBTranslation(this)
                    else -> { showToast("Unknown Translator.") }
                }
                Constants.TextApi.BING.id -> translatorText = BingTranslation()
                Constants.TextApi.NIUTRANS.id -> translatorText = NiuTranslation(KeystoreManager.retrieveKey(this, "Niutrans")!!)
                Constants.TextApi.OPENAI.id -> translatorText = OpenAITranslation(
                    apiKey = prefs.getString("OpenAI_Api_Key", ""),
                    baseUrl = prefs.getString("OpenAI_Base_Url", ""),
                    model = prefs.getString("OpenAI_Model_Name", ""),
                    systemPrompt = prefs.getString("OpenAI_System_Prompt", defaultSystemPrompt),
                    userPrompt = prefs.getString("OpenAI_User_Prompt", defaultUserPrompt)
                )
                Constants.TextApi.VOLC.id -> translatorText = VolcTranslation(KeystoreManager.retrieveKey(this, "Volc_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Volc_SECRETKEY")!!)
                Constants.TextApi.AZURE.id -> translatorText = AzureTranslation(KeystoreManager.retrieveKey(this, "Azure")!!)
                Constants.TextApi.DEEPL.id -> translatorText = DeepLTranslation(KeystoreManager.retrieveKey(this, "DeepL_Translate_HOST")!!, KeystoreManager.retrieveKey(this, "DeepL_Translate_APIKEY")!!)
                Constants.TextApi.BAIDU.id -> translatorText = BaiduTranslationText(KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY")!!)
                Constants.TextApi.TENCENT.id -> translatorText = TencentTranslationText(KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY")!!)
                Constants.TextApi.CUSTOM_TEXT.id -> {
                    val textConfig = ConfigurationStorage.loadTextConfig(prefs, prefs.getInt("Custom_Text_API", 0))
                    if (textConfig != null) {
                        translatorText = CustomTranslationText(textConfig)
                    } else {
                        showToast("No Custom Text API Config Found.")
                    }
                }
                else -> { showToast("Unknown Translator.") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initTranslator: Exception", e)
            showToast("Initialize Error: ${e.message}")
        }
        Log.d(TAG, "initTranslator: result translatorText=${translatorText?.javaClass?.simpleName}")
    }

    private fun loadConfig(): MangaModeConfig {
        val directionIndex = prefs.getInt("Manga_Text_Direction", 0)
        val direction = TextDirection.entries.getOrElse(directionIndex) { TextDirection.VERTICAL_RL }
        return MangaModeConfig(
            enabled = true,
            textDirection = direction,
            smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
            autoDetectBubble = prefs.getBoolean("Manga_Auto_Detect_Bubble", true),
            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
            autoFontSize = prefs.getBoolean("Manga_Auto_Font_Size", true),
            sourceLang = prefs.getString("Source_Language", "ja"),
            targetLang = prefs.getString("Target_Language", "zh"),
            textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
            bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255))
        )
    }

    @SuppressLint("InflateParams")
    private fun initializeViews() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create floating ball using original layout (65dp icon)
        floatingBallView = LayoutInflater.from(this).inflate(R.layout.floatball_layout, null)

        floatingBallParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.TOP
            x = 80
            y = 300
        }

        windowManager.addView(floatingBallView, floatingBallParams)

        // 加载自定义悬浮球图标
        val customPicName = prefs.getString("Custom_Floating_Pic", "")
        if (customPicName.isNotEmpty()) {
            try {
                val iconFile = java.io.File(getExternalFilesDir(null), "icon/$customPicName")
                if (iconFile.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath)
                    floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
                        .setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load custom icon", e)
            }
        }

        // 加载长按判定时间
        longPressDelay = prefs.getLong("Custom_Long_Press_Delay", 500L)

        setupTouchListener()

        // Result overlay (initially not added)
        resultOverlayView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }

        resultOverlayParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
        }

        // Progress overlay (initially not added)
        progressOverlayView = android.widget.TextView(this).apply {
            text = getString(R.string.manga_translating)
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(48, 32, 48, 32)
            gravity = android.view.Gravity.CENTER
        }

        progressOverlayParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        // Crop view (initially not added)
        cropView = CropView(this)
        cropViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
        }
    }

    // ---------- Touch handling (matches original FloatingBallService pattern) ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        floatingBallView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ballInitialX = floatingBallParams?.x ?: 0
                    ballInitialY = floatingBallParams?.y ?: 0
                    ballInitialTouchX = event.rawX
                    ballInitialTouchY = event.rawY

                    // Start long press detection
                    handler.postDelayed(longPressRunnable, longPressDelay)
                    currentGesture = null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalMoveX = abs(event.rawX - ballInitialTouchX)
                    val totalMoveY = abs(event.rawY - ballInitialTouchY)

                    // Cancel long press if moved too much
                    if (totalMoveX > LONG_PRESS_SLOP || totalMoveY > LONG_PRESS_SLOP) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Drag if moved enough
                    if (totalMoveX > CLICK_SLOP || totalMoveY > CLICK_SLOP) {
                        currentGesture = GestureType.Drag
                        floatingBallParams?.apply {
                            x = (ballInitialX + (event.rawX - ballInitialTouchX)).toInt()
                            y = (ballInitialY + (event.rawY - ballInitialTouchY)).toInt()
                            windowManager.updateViewLayout(floatingBallView, this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)

                    // Click if no gesture detected and within slop
                    if (currentGesture == null) {
                        val totalMoveX = abs(event.rawX - ballInitialTouchX)
                        val totalMoveY = abs(event.rawY - ballInitialTouchY)
                        if (totalMoveX <= CLICK_SLOP && totalMoveY <= CLICK_SLOP) {
                            onBallClicked()
                        }
                    }

                    currentGesture = null
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Long press menu ----------

    private fun handleLongPress() {
        currentGesture = GestureType.LongPress
        // 长按震动反馈动画（缩放+透明度）
        floatingBallView.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                floatingBallView.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
        showMenu()
    }

    private fun showMenu() {
        val directionLabel = when (config.textDirection) {
            TextDirection.VERTICAL_RL -> getString(R.string.manga_mode_vertical_rl)
            TextDirection.VERTICAL_LR -> getString(R.string.manga_mode_vertical_lr)
            TextDirection.HORIZONTAL -> getString(R.string.manga_mode_horizontal)
        }
        val cropLabel = if (cropRect != null) {
            getString(R.string.manga_mode_crop)
        } else {
            getString(R.string.manga_mode_fullscreen)
        }

        val (dialog, listView) = Dialogs.mangaMenuDialog(
            applicationContext, isAutoTranslating, directionLabel, cropLabel
        )

        isMenuShowing = true

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    // 切换全屏/框选
                    if (cropRect != null) {
                        // 当前是框选模式，切换到全屏
                        cropRect = null
                        showToast(getString(R.string.manga_mode_fullscreen))
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(0, "${getString(R.string.manga_crop_toggle)}：${getString(R.string.manga_mode_fullscreen)}")
                    } else {
                        // 当前是全屏模式，切换到框选 - 关闭菜单后再框选
                        dialog.dismiss()
                        handler.postDelayed({ startCropSelection() }, 200)
                    }
                }
                1 -> {
                    // 切换文字方向（不关闭菜单）
                    switchTextDirection()
                    val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                    val newDirLabel = when (config.textDirection) {
                        TextDirection.VERTICAL_RL -> getString(R.string.manga_mode_vertical_rl)
                        TextDirection.VERTICAL_LR -> getString(R.string.manga_mode_vertical_lr)
                        TextDirection.HORIZONTAL -> getString(R.string.manga_mode_horizontal)
                    }
                    adapter.updateLabel(1, "${getString(R.string.manga_direction_switched_short)}：$newDirLabel")
                }
                2 -> {
                    // 字体大小（不关闭菜单，打开子对话框）
                    showFontSizeDialog()
                }
                3 -> {
                    // 自动翻译切换（不关闭菜单）
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                    if (isAutoTranslating) {
                        adapter.updateLabel(3, getString(R.string.manga_menu_stop_auto))
                        adapter.updateIcon(3, R.drawable.stop_auto)
                    } else {
                        adapter.updateLabel(3, getString(R.string.manga_menu_auto_translate))
                        adapter.updateIcon(3, R.drawable.start_auto)
                    }
                }
                4 -> {
                    // 关闭悬浮球
                    dialog.dismiss()
                    stopSelf()
                }
                5 -> {
                    // 返回主界面
                    dialog.dismiss()
                    backToMainActivity()
                }
            }
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.setOnDismissListener {
            isMenuShowing = false
        }
    }

    private fun backToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // ---------- Fullscreen translate ----------

    private fun clearCrop() {
        cropRect = null
        showToast(getString(R.string.manga_mode_fullscreen))
    }

    // ---------- Menu actions ----------

    private fun switchTextDirection() {
        val newDirection = when (config.textDirection) {
            TextDirection.VERTICAL_RL -> TextDirection.VERTICAL_LR
            TextDirection.VERTICAL_LR -> TextDirection.HORIZONTAL
            TextDirection.HORIZONTAL -> TextDirection.VERTICAL_RL
        }
        config = config.copy(textDirection = newDirection)
        prefs.setInt("Manga_Text_Direction", newDirection.ordinal)
        val label = when (newDirection) {
            TextDirection.VERTICAL_RL -> getString(R.string.manga_mode_vertical_rl)
            TextDirection.VERTICAL_LR -> getString(R.string.manga_mode_vertical_lr)
            TextDirection.HORIZONTAL -> getString(R.string.manga_mode_horizontal)
        }
        showToast("${getString(R.string.manga_direction_switched)}：$label")
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf(
            getString(R.string.manga_font_size_auto),
            "12", "14", "16", "18", "20", "24", "28", "32"
        )
        val currentIndex = if (config.autoFontSize) {
            0
        } else {
            val idx = sizes.indexOf(config.fontSize.toInt().toString())
            if (idx < 0) 3 else idx
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.manga_font_size_title))
            .setSingleChoiceItems(sizes, currentIndex) { d, which ->
                if (which == 0) {
                    config = config.copy(autoFontSize = true)
                    prefs.setBoolean("Manga_Auto_Font_Size", true)
                    showToast(getString(R.string.manga_font_size_auto))
                } else {
                    val newSize = sizes[which].toFloat()
                    config = config.copy(fontSize = newSize, autoFontSize = false)
                    prefs.setFloat("Manga_Font_Size", newSize)
                    prefs.setBoolean("Manga_Auto_Font_Size", false)
                    showToast("${sizes[which]}sp")
                }
                d.dismiss()
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    // ---------- Auto-translate ----------

    private fun toggleAutoTranslate() {
        if (isAutoTranslating) {
            stopAutoTranslate()
        } else {
            startAutoTranslate()
        }
    }

    private fun startAutoTranslate() {
        if (AccessibilityServiceManager.getService() == null) {
            showToast(getString(R.string.accessibility_recycle))
            return
        }
        isAutoTranslating = true
        autoTranslateHandler.post(autoTranslateRunnable)
        showToast(getString(R.string.manga_auto_translate_start))
    }

    private fun stopAutoTranslate() {
        isAutoTranslating = false
        lastOcrText = ""
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
        showToast(getString(R.string.manga_auto_translate_stop))
    }

    // ---------- Crop selection ----------

    private fun startCropSelection() {
        if (isCropActive) {
            showToast(getString(R.string.manga_crop_active))
            return
        }

        if (cropRect != null && resources.configuration.orientation == 1) {
            cropView.setRect(cropRect!!)
        } else {
            cropView.setRect(RectF(50f, 50f, 400f, 400f))
        }

        windowManager.addView(cropView, cropViewParams)
        isCropActive = true

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }

        showCropConfirmButton()
    }

    private fun showCropConfirmButton() {
        val confirmBtn = android.widget.Button(this).apply {
            text = "确认"
            setOnClickListener { confirmCrop() }
        }
        cropConfirmView = confirmBtn

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200
        }
        windowManager.addView(confirmBtn, params)
    }

    private fun confirmCrop() {
        cropRect = RectF(cropView.mRect)
        isCropActive = false

        try {
            windowManager.removeView(cropView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing crop view", e)
        }
        try {
            cropConfirmView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing confirm button", e)
        }
        cropConfirmView = null

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }

        showToast(getString(R.string.manga_crop_confirm))
    }

    // ---------- Click handler ----------

    private fun onBallClicked() {
        // 点击脉冲动画
        floatingBallView.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                floatingBallView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(80)
                    .start()
            }
            .start()
        triggerTranslation()
    }

    private fun triggerTranslation() {
        Log.d(TAG, "========== triggerTranslation START ==========")
        if (isProcessing) {
            Log.d(TAG, "triggerTranslation: already processing, skipping")
            showToast(getString(R.string.is_translating))
            return
        }
        if (isCropActive) {
            Log.d(TAG, "triggerTranslation: crop is active, skipping")
            return
        }

        val service = AccessibilityServiceManager.getService()
        Log.d(TAG, "triggerTranslation: accessibilityService=$service")
        if (service == null) {
            showToast(getString(R.string.accessibility_recycle))
            return
        }

        // 只重新加载视觉配置（文字方向、字体等），不重新初始化翻译API
        config = loadConfig()

        isProcessing = true

        // 先关闭结果overlay再截图，避免截到翻译结果
        dismissResultOverlay()

        // 根据模式显示不同的进度文本
        if (isAutoTranslating) {
            showProgressOverlay(getString(R.string.manga_auto_detecting))
        } else {
            showProgressOverlay(getString(R.string.manga_translating))
        }

        Log.d(TAG, "triggerTranslation: translatorText=${translatorText?.javaClass?.simpleName}")
        Log.d(TAG, "triggerTranslation: cropRect=$cropRect")
        if (cropRect != null) {
            Log.d(TAG, "triggerTranslation: taking cropped screenshot")
            AccessibilityServiceManager.takeScreenshot(cropRect, cropView.absolutePointOffset)
        } else {
            Log.d(TAG, "triggerTranslation: taking full screenshot")
            AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
        }
        Log.d(TAG, "========== triggerTranslation END ==========")
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        Log.d(TAG, "setupScreenshotCollector: starting collector coroutine")
        lifecycleScope.launch {
            Log.d(TAG, "Screenshot collector: coroutine started, waiting for screenshots...")
            ScreenshotManager.screenshotFlow.collect { bitmap ->
                Log.d(TAG, "Screenshot collector: BITMAP RECEIVED! ${bitmap.width}x${bitmap.height}")
                try {
                    processMangaScreenshot(bitmap)
                    Log.d(TAG, "Screenshot collector: processMangaScreenshot completed normally")
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot collector: CAUGHT EXCEPTION", e)
                    showToast(getString(R.string.translation_failed, e.message ?: "Unknown error"))
                    isProcessing = false
                    dismissProgressOverlay()
                }
            }
            Log.d(TAG, "Screenshot collector: collect() returned (THIS SHOULD NEVER HAPPEN)")
        }
    }

    // ---------- Manga translation pipeline ----------

    private suspend fun processMangaScreenshot(bitmap: Bitmap) {
        try {
            Log.d(TAG, "processMangaScreenshot: START")
            // Step 1: OCR with location
            Log.d(TAG, "processMangaScreenshot: Step 1 - OCR starting, sourceLang=${config.sourceLang}")
            val textBlocks = withContext(Dispatchers.IO) {
                OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
            }
            Log.d(TAG, "processMangaScreenshot: Step 1 - OCR done, found ${textBlocks.size} text blocks")

            if (textBlocks.isEmpty()) {
                Log.d(TAG, "processMangaScreenshot: No text found, returning early")
                bitmap.recycle()
                if (!isAutoTranslating) {
                    showToast(getString(R.string.no_text_found))
                }
                return
            }

            // 自动翻译模式下，检查文本是否变化
            val currentOcrText = textBlocks.joinToString("\n") { it.text }
            if (isAutoTranslating && !shouldTranslateText(currentOcrText)) {
                Log.d(TAG, "processMangaScreenshot: Auto-translate text unchanged, skipping")
                bitmap.recycle()
                return
            }
            lastOcrText = currentOcrText

            // 更新进度文本为"正在翻译…"
            if (isAutoTranslating) {
                showProgressOverlay(getString(R.string.manga_translating))
            }

            // Step 2: Detect bubbles (or use raw blocks)
            Log.d(TAG, "processMangaScreenshot: Step 2 - Detecting bubbles, autoDetect=${config.autoDetectBubble}")
            val bubbles = if (config.autoDetectBubble) {
                BubbleDetector.detectBubbles(textBlocks)
            } else {
                textBlocks.filter { it.boundingBox != null }.map { block ->
                    BubbleRegion(
                        rect = block.boundingBox!!,
                        texts = listOf(block.text)
                    )
                }
            }
            Log.d(TAG, "processMangaScreenshot: Step 2 - Detected ${bubbles.size} bubbles")

            // Step 3: Translate each bubble
            Log.d(TAG, "processMangaScreenshot: Step 3 - Starting translateBubbles")
            val translatedBubbles = translateBubbles(bubbles)
            Log.d(TAG, "processMangaScreenshot: Step 3 - translateBubbles done, got ${translatedBubbles.size} results")

            // Step 4: Render overlay
            Log.d(TAG, "processMangaScreenshot: Step 4 - Rendering overlay")
            val resultBitmap = withContext(Dispatchers.Default) {
                OverlayRenderer.renderOverlay(
                    original = bitmap,
                    regions = translatedBubbles,
                    direction = config.textDirection,
                    fontSize = config.fontSize,
                    autoFit = config.autoFontSize,
                    textColor = config.textColor,
                    bgColor = config.bgColor
                )
            }
            Log.d(TAG, "processMangaScreenshot: Step 4 - Render done")

            // Step 5: Show result overlay
            Log.d(TAG, "processMangaScreenshot: Step 5 - Showing result overlay")
            withContext(Dispatchers.Main) {
                showResultOverlay(resultBitmap)
            }
            Log.d(TAG, "processMangaScreenshot: Step 5 - DONE")

        } finally {
            bitmap.recycle()
            Log.d(TAG, "processMangaScreenshot: FINALLY - dismissing progress, isProcessing=false")
            isProcessing = false
            dismissProgressOverlay()
        }
    }

    private fun shouldTranslateText(currentText: String): Boolean {
        if (currentText.isBlank()) return false
        // 短文本直接翻译
        if (currentText.length < prefs.getInt("Auto_Translate_Str_Length", 10)) return true
        // 首次翻译
        if (lastOcrText.isEmpty()) return true
        // 相似度检测
        val similarity = UtilTools.calculateSimilarity(lastOcrText, currentText)
        return similarity < prefs.getFloat("Auto_Translate_Str_Similarity", 0.8f)
    }

    private fun cleanOcrText(text: String): String {
        return text
            .replace(Regex("[\\n\\r]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>
    ): List<TranslatedBubble> {
        Log.d(TAG, "translateBubbles: ${bubbles.size} bubbles, translatorText=${translatorText?.javaClass?.simpleName}")
        if (translatorText == null) {
            Log.e(TAG, "translateBubbles: translatorText is NULL!")
            throw RuntimeException("Translation API not initialized")
        }

        // 准备气泡数据：清理文本，过滤空的
        val preparedBubbles = bubbles.mapNotNull { bubble ->
            val cleaned = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) null
            else bubble to cleaned.joinToString("")
        }
        if (preparedBubbles.isEmpty()) return emptyList()

        // AI翻译（OpenAI兼容）用批量请求，机器翻译用逐个请求
        val isAI = translatorText is translationapi.openaitranslation.OpenAITranslation
                || translatorText?.javaClass?.simpleName?.contains("Custom") == true

        return if (isAI && preparedBubbles.size > 1) {
            translateBubblesBatch(preparedBubbles)
        } else {
            translateBubblesSequential(preparedBubbles)
        }
    }

    /**
     * AI翻译：所有气泡合并为一次请求，用编号分隔
     */
    private suspend fun translateBubblesBatch(
        bubbles: List<Pair<BubbleRegion, String>>
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        Log.d(TAG, "translateBubblesBatch: ${bubbles.size} bubbles in 1 request")

        // 构建带编号的文本，前置格式约束保证 AI 按编号返回
        val formatInstruction = "请逐条翻译以下文本，保持每条的[N]编号格式不变，只输出翻译结果，不要添加额外解释：\n"
        val numberedText = formatInstruction + bubbles.mapIndexed { index, (_, text) ->
            "[${index + 1}] $text"
        }.joinToString("\n")

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        // 直接调用翻译API，使用用户在设置页面配置的系统提示词和用户提示词
        // numberedText 会替换用户提示词中的 usesourcetext 占位符
        translatorText?.getTranslation(
            numberedText,
            config.sourceLang,
            config.targetLang
        ) { result ->
            when (result) {
                is TranslationResult.Success -> {
                    resultText = result.translatedText
                }
                is TranslationResult.Error -> {
                    errorMsg = result.error.message ?: "Unknown error"
                }
            }
            latch.countDown()
        } ?: run {
            errorMsg = "translatorText is null"
            latch.countDown()
        }

        val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            throw RuntimeException("AI batch translation timeout (60s)")
        }
        if (errorMsg != null) {
            throw RuntimeException("AI batch translation failed: $errorMsg")
        }

        // 按编号解析结果
        val translations = parseNumberedTranslations(resultText!!, bubbles.size)
        Log.d(TAG, "translateBubblesBatch: parsed ${translations.size} translations")

        bubbles.mapIndexed { index, (bubble, originalText) ->
            TranslatedBubble(
                rect = bubble.rect,
                originalText = originalText,
                translatedText = translations.getOrElse(index) { originalText },
                backgroundColor = Color.TRANSPARENT
            )
        }
    }

    /**
     * 解析带编号的翻译结果
     * 支持格式: "[1] 翻译文本" 或 "1. 翻译文本" 或 "1、翻译文本"
     */
    private fun parseNumberedTranslations(text: String, expectedCount: Int): List<String> {
        val results = mutableListOf<String>()
        // 匹配 [N] 或 N. 或 N、开头的行
        val pattern = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\[\d+]|$)""")
        val matches = pattern.findAll(text).toList()

        if (matches.size >= expectedCount) {
            for (match in matches.take(expectedCount)) {
                results.add(match.groupValues[2].trim())
            }
        } else {
            // 降级：按行拆分
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            for (line in lines) {
                val cleaned = line.replace(Regex("""^\[?\d+]?[.、\s]*"""), "").trim()
                if (cleaned.isNotBlank()) {
                    results.add(cleaned)
                }
            }
        }

        // 补齐不足的部分
        while (results.size < expectedCount) {
            results.add("")
        }
        return results.take(expectedCount)
    }

    /**
     * 机器翻译：逐个气泡请求
     */
    private suspend fun translateBubblesSequential(
        bubbles: List<Pair<BubbleRegion, String>>
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        Log.d(TAG, "translateBubblesSequential: ${bubbles.size} bubbles, sequential")

        val results = mutableListOf<TranslatedBubble>()
        val errors = mutableListOf<String>()
        for ((bubble, combinedText) in bubbles) {
            Log.d(TAG, "translateBubblesSequential: translating '$combinedText'")

            val latch = java.util.concurrent.CountDownLatch(1)
            var successResult: TranslatedBubble? = null
            var errorMsg: String? = null

            translatorText?.getTranslation(
                combinedText,
                config.sourceLang,
                config.targetLang
            ) { result ->
                when (result) {
                    is TranslationResult.Success -> {
                        Log.d(TAG, "translateBubblesSequential: SUCCESS for '$combinedText'")
                        successResult = TranslatedBubble(
                            rect = bubble.rect,
                            originalText = combinedText,
                            translatedText = result.translatedText,
                            backgroundColor = Color.TRANSPARENT
                        )
                    }
                    is TranslationResult.Error -> {
                        errorMsg = result.error.message ?: "Unknown error"
                        Log.e(TAG, "translateBubblesSequential: ERROR: $errorMsg")
                    }
                }
                latch.countDown()
            } ?: run {
                errorMsg = "translatorText is null"
                latch.countDown()
            }

            val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                errorMsg = "Translation timeout (30s)"
            }

            if (successResult != null) {
                results.add(successResult!!)
            } else if (errorMsg != null) {
                errors.add(errorMsg!!)
            }
        }

        Log.d(TAG, "translateBubblesSequential: ${results.size} successful out of ${bubbles.size}")
        if (results.isEmpty() && bubbles.isNotEmpty()) {
            val errorDetail = errors.distinct().joinToString("; ")
            throw RuntimeException("All bubbles failed to translate: $errorDetail")
        }
        results
    }

    // ---------- Result overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(bitmap: Bitmap) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        resultOverlayView.setImageBitmap(bitmap)
        resultOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                dismissResultOverlay()
            }
            true
        }

        // 框选模式下，结果只显示在框选区域内
        if (cropRect != null) {
            val crop = cropRect!!
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = crop.width().toInt()
                height = crop.height().toInt()
                gravity = Gravity.START or Gravity.TOP
                x = crop.left.toInt() + cropView.absolutePointOffset.x
                y = crop.top.toInt() + cropView.absolutePointOffset.y
            }
            resultOverlayView.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        } else {
            // 全屏模式使用 FIT_CENTER 避免图片压缩变形
            resultOverlayView.scaleType = ImageView.ScaleType.FIT_CENTER
            windowManager.addView(resultOverlayView, resultOverlayParams)
        }
        isResultShowing = true

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    }

    private fun dismissResultOverlay() {
        if (isResultShowing) {
            try {
                resultOverlayView.setImageBitmap(null)
                resultOverlayView.setOnTouchListener(null)
                windowManager.removeView(resultOverlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false

            // 重置自动翻译定时器，给用户时间翻页/滑动
            if (isAutoTranslating) {
                val delay = prefs.getLong("Auto_Translate_Dismiss_Delay", 1000L)
                autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
                autoTranslateHandler.postDelayed(autoTranslateRunnable, delay)
            }
        }
    }

    private fun showProgressOverlay(text: String = getString(R.string.manga_translating)) {
        if (isProgressShowing) {
            progressOverlayView.text = text
            return
        }
        try {
            progressOverlayView.text = text
            windowManager.addView(progressOverlayView, progressOverlayParams)
            isProgressShowing = true
            // Keep floating ball on top
            if (isViewAdded(floatingBallView)) {
                windowManager.removeView(floatingBallView)
                windowManager.addView(floatingBallView, floatingBallParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing progress", e)
        }
    }

    private fun dismissProgressOverlay() {
        if (isProgressShowing) {
            try {
                windowManager.removeView(progressOverlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing progress", e)
            }
            isProgressShowing = false
        }
    }

    // ---------- Helpers ----------

    private fun isViewAdded(view: View): Boolean {
        return try {
            windowManager.updateViewLayout(view, view.layoutParams)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun removeAllViews() {
        try {
            if (isViewAdded(floatingBallView)) {
                windowManager.removeView(floatingBallView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating ball", e)
        }
        dismissResultOverlay()
        dismissProgressOverlay()
        handler.removeCallbacks(longPressRunnable)
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
        // Remove crop view if active
        if (isCropActive) {
            try {
                windowManager.removeView(cropView)
            } catch (e: Exception) { /* ignore */ }
            try {
                cropConfirmView?.let { windowManager.removeView(it) }
            } catch (e: Exception) { /* ignore */ }
            isCropActive = false
        }
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Manga Floating Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manga translation floating window"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Manga Translation")
            .setContentText("Floating ball active - tap to translate manga")
            .setSmallIcon(R.drawable.floating_ball_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
