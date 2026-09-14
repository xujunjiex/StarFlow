package com.moe.starflow.manga
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.manga.debug.MangaDebugOverlays
import com.moe.starflow.manga.debug.MangaDebugPanelController
import com.moe.starflow.manga.debug.MangaDebugSliders
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.OcrEngineManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.MainActivity
import com.moe.starflow.R
import com.moe.starflow.me.apiconfig.BuiltinProviders
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import com.moe.starflow.translate.screenshot.AccessibilityProvider
import com.moe.starflow.translate.screenshot.AccessibilityEventHandler
import com.moe.starflow.translate.screenshot.AccessibilityServiceManager
import com.moe.starflow.translate.widget.BallStateManager
import com.moe.starflow.translate.widget.CropView
import com.moe.starflow.translate.widget.Dialogs
import com.moe.starflow.translate.screenshot.MediaProjectionProvider
import com.moe.starflow.translate.screenshot.ScreenshotData
import com.moe.starflow.translate.screenshot.ScreenshotManager
import com.moe.starflow.translate.screenshot.MediaProjectionIntentHolder
import com.moe.starflow.translate.screenshot.ScreenshotProvider
import com.moe.starflow.translate.screenshot.ScreenCapturePermissionActivity
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.TextSimilarity
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import translationapi.bingtranslation.BingTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.doubaotranslation.DoubaoTranslation
import translationapi.volctranslation.VolcTranslation
import translationapi.azuretranslation.AzureTranslation
import translationapi.deepltranslation.DeepLTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.tencentcloud.TencentTranslationText
import translationapi.customtranslation.CustomTranslationText
import translationapi.nllbtranslation.NLLBTranslation
import translationapi.hymt2translation.HyMT2Translation
import translationapi.TranslatorFactory
import com.moe.starflow.data.CacheEntry
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.utils.PerceptualHash
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.roundToInt

class MangaFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "MangaFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "manga_floating_service"
        private const val NOTIFICATION_ID = 7

        // 前台服务通知 ID（MediaProjection 模式需要）
        private const val FOREGROUND_NOTIFICATION_ID = 34766
        private const val SCREEN_CAPTURE_CHANNEL_ID = "screen_capture"

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f
        private const val DOUBLE_CLICK_DELAY = 300L


        // 分批渲染常量
        const val INCREMENTAL_THRESHOLD = 6       // 触发分批的气泡数量阈值


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

    // 手势动作配置
    private var singleClickAction = Constants.BallAction.TRANSLATE
    private var doubleClickAction = Constants.BallAction.AUTO_TRANSLATE
    private var longPressAction = Constants.BallAction.MENU

    // 双击检测
    private var lastClickTime = 0L
    private val singleClickRunnable = Runnable { executeAction(singleClickAction) }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBallView: View
    private lateinit var resultOverlayView: FrameLayout
    private lateinit var resultOverlayImage: ImageView  // overlay 内的图片子 View

    private var floatingBallParams: WindowManager.LayoutParams? = null
    private var resultOverlayParams: WindowManager.LayoutParams? = null

    private var ballInitialX = 0
    private var ballInitialY = 0
    private var ballInitialTouchX = 0f
    private var ballInitialTouchY = 0f

    private var isProcessing = false
    @Volatile private var translationCancelled = false  // 用户强制停止翻译：不保存结果；跨线程读（TranslateUtils 取消轮询）需 volatile
    /** 本次翻译是否已有部分气泡渲染上屏（分批渲染首批 / 本地模型流式出字）：决定单击悬浮球是直接终止还是弹确认 */
    private var partialRenderShown = false
    /** 流式渲染协程的 Job 跟踪：processMangaScreenshot 收尾 join 后再 recycle 截图，避免读取已回收 bitmap 崩溃 */
    private val streamingRenderJobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())
    private var isResultShowing = false
    private var isMenuShowing = false

    // 翻译状态提示条
    private lateinit var statusOverlay: TranslationStatusOverlay

    // SharedPreferences listener（防止被 GC 回收）
    private var prefChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 悬浮球图标变更广播接收器（防止被 GC 回收，跨 onCreate/onDestroy 复用同一实例）
    private var iconChangeReceiver: android.content.BroadcastReceiver? = null

    // 悬浮球状态机
    private var ballStateManager: BallStateManager? = null

    // Long press detection
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }
    private var currentGesture: GestureType? = null

    // AI 上下文（仅 OpenAI 兼容 API）
    private var contextEnabled = false
    private var contextMaxCount = 5
    private val contextHistory = LinkedList<Pair<String, String>>()  // (原文, 译文) 对

    // Auto-translate — 基于图像哈希 + 区域级缓存的智能自动翻译
    // 状态机（autoTranslateEngine.isAutoTranslating/autoTranslateEngine.detectState/hash 等）移入 MangaAutoTranslateEngine
    private var wasAutoTranslatingBeforeCrop = false  // 框选前的自动翻译状态
    private var consecutiveEmptyCount = 0  // 连续未检测到文字的计数（用于检测受保护区域）
    private var pendingAutoStart = false   // 等待权限授权后自动启动
    private lateinit var autoTranslateEngine: MangaAutoTranslateEngine
    private lateinit var engineManager: MangaEngineManager
    // 区域级翻译缓存（TranslatedRegion/translatedRegions 移入 RegionCacheManager）
    private val regionCache = RegionCacheManager()

    // Crop selection
    private lateinit var cropView: CropView
    private var cropViewParams: WindowManager.LayoutParams? = null
    private var cropRect: RectF? = null
    private var isCropActive = false

    private lateinit var prefs: CustomPreference
    private lateinit var config: MangaModeConfig

    // ── 引擎组合：单一数据源，所有标签/切换/初始化共用 ──
    private data class ComboDef(
        val key: String,
        val detEngine: DetEngine,
        val ocrEngine: OcrEngine,
        val labelRes: Int,
        val needsDownloadCheck: Boolean = false
    )

    /** 切换顺序：mlkit → ppocr → ppocrv6 → manga → mlkit */
    private val engineCombos = listOf(
        ComboDef("mlkit", DetEngine.MLKIT, OcrEngine.MLKit, R.string.manga_model_mlkit),
        ComboDef("ppocr", DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5, R.string.manga_model_ppocr, needsDownloadCheck = true),
        ComboDef("ppocrv6", DetEngine.PP_OCR_V6, OcrEngine.PPOcrV6, R.string.manga_model_ppocrv6),
        ComboDef("manga", DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, R.string.manga_model_manga_ocr, needsDownloadCheck = true),
    )

    /** 当前 config 匹配的组合（兜底 mlkit） */
    private fun currentCombo(): ComboDef = engineCombos.firstOrNull {
        it.detEngine == config.detEngine && it.ocrEngine == config.ocrEngine
    } ?: engineCombos[0]

    /** combo key → 显示标签 */
    private fun comboLabel(combo: ComboDef): String = getString(combo.labelRes)

    /** 检查需下载的组合是否可用 */
    private fun isComboAvailable(combo: ComboDef): Boolean = when {
        !combo.needsDownloadCheck -> true
        combo.key == "ppocr" -> PPOcrModelFiles.isV5DetDownloaded(this) && PPOcrModelFiles.isV5RecZhDownloaded(this)
        else -> RTDetrModelFiles.isModelAvailable(this) && MangaOcrModelFiles.isModelDownloaded(this)
    }

    /** 应用组合：更新 config + 持久化 + 初始化引擎 */
    private fun applyCombo(combo: ComboDef) {
        config = config.copy(detEngine = combo.detEngine, ocrEngine = combo.ocrEngine)
        val group = OcrEngineGroup.entries.firstOrNull {
            it.mangaDet == combo.detEngine && it.mangaOcr == combo.ocrEngine
        } ?: OcrEngineGroup.MLKIT
        OcrEngineManager.setOcrEngineGroup(prefs.getSharedPreferences(), group)
        showToast(getString(combo.labelRes), true)
        when (combo.key) {
            "ppocr" -> lifecycleScope.launch { engineManager.initPPOcrV5("检测器+识别器") }
            "ppocrv6" -> lifecycleScope.launch { engineManager.initPPOcrV6("检测器+识别器") }
            "manga" -> lifecycleScope.launch { engineManager.initRTDetrV2(); engineManager.ensureMangaOcrInitialized() }
            else -> {} // MLKit 无需初始化
        }
    }
    private var translatorText: TranslationTextAPI? = null

    // 截图提供者
    private var screenshotProvider: ScreenshotProvider? = null

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager
    private var forceRefresh = false
    private var isForceRefreshActive = false  // 保存 forceRefresh 状态，用于保存缓存时判断
    private var lastCachedHistoryId: Long = 0  // 缓存命中的 historyId，用于强制刷新时删除旧记录
    private var lastCachedPHash: Long = 0      // 缓存命中的 pHash，用于验证 historyId 有效性

    // 复制模式
    private var isCopyMode = false
    private var copyOriginalMode = false  // false=译文, true=原文
    private var copyClickLayer: android.widget.FrameLayout? = null
    private var copyBubbleViews: MutableList<View> = mutableListOf()
    private var copyButtonsContainer: android.widget.LinearLayout? = null
    private var currentShowBubbles: List<TranslatedBubble> = emptyList()  // 当前显示的翻译气泡（非缓存）
    private var renderToggleJob: kotlinx.coroutines.Job? = null  // toggle 渲染协程，避免重复渲染
    @Volatile private var currentOriginalBitmap: Bitmap? = null  // 原始截图（用于原文模式重新渲染）
    private var currentOverlayBitmapW: Int = 0  // 当前 overlay 对应的 bitmap 宽度（用于坐标映射）
    private var currentOverlayBitmapH: Int = 0  // 当前 overlay 对应的 bitmap 高度（用于坐标映射）
    private var lastCacheBubbleRects: String? = null  // 缓存命中的气泡 rect JSON
    private var cachedOriginalTextList: List<String> = emptyList()  // 缓存结果解析后的原文列表
    private var cachedTranslatedTextList: List<String> = emptyList()  // 缓存结果解析后的译文列表

    // 翻译会话 ID（每次服务启动生成新的）
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var currentPHash = 0L
    private var currentExtHashes: LongArray? = null  // 256-bit 扩展哈希（用于缓存匹配和存储）
    private var cacheOverlayContainer: android.widget.FrameLayout? = null
    private var cacheOverlayImage: ImageView? = null  // cache overlay 中的 ImageView 引用

    // 无障碍重截图：干净截图到达后拦截处理
    private var pendingCleanScreenshot = false
    private var pendingDetectionPHash = 0L
    private var pendingDetectionExtHashes: LongArray? = null

    // 当前翻译的原始全屏截图（未裁剪），用于缓存 originalBitmap
    // 注意：MediaProjectionProvider 和 AccessibilityProvider 的 data.fullBitmap 均为全屏截图，
    // 不会被裁剪。如果以后有截图提供者在 emit 前裁剪 fullBitmap，此处需同步更新。
    private var pendingFullBitmap: Bitmap? = null

    private lateinit var debugPanel: MangaDebugPanelController

    private sealed class GestureType {
        object Click : GestureType()
        object LongPress : GestureType()
        object Drag : GestureType()
    }

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        prefs = CustomPreference.getInstance(this)

        // 注册悬浮球图标变更广播（Personalization 设置页改图标时实时刷新）
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val key = intent?.getStringExtra("extra_icon_key") ?: return
                if (key == "Icon_Comic") {
                    // 重读状态机的 Idle 图标路径
                    ballStateManager?.setState(BallStateManager.State.Idle)
                }
            }
        }
        iconChangeReceiver = receiver
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(receiver, android.content.IntentFilter("action_floating_ball_icon_changed"))

        statusOverlay = TranslationStatusOverlay.getInstance(this)
        cacheManager = TranslationCacheManager(this)
        // 读取手势动作配置
        singleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Single_Click", "0").toIntOrNull() ?: 0)
        doubleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Double_Click", "2").toIntOrNull() ?: 2)
        longPressAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Long_Press", "1").toIntOrNull() ?: 1)
        config = loadConfig()
        // 自动翻译状态机（副作用经回调注入服务）
        autoTranslateEngine = MangaAutoTranslateEngine(
            context = this,
            isResultOrMenuShowing = { isResultShowing || isMenuShowing },
            isProcessing = { isProcessing },
            onShowProgress = { showProgressOverlay(it) },
            onDismissProgress = { dismissProgressOverlay() },
            onTriggerTranslation = { triggerTranslation() },
            onClearRegionCache = { regionCache.clear() },
            onShowToast = { showToast(it) }
        )
        // OCR 引擎初始化/释放管理器（引擎为静态单例，UI 副作用经回调）
        engineManager = MangaEngineManager(
            context = this,
            scope = lifecycleScope,
            onShowToast = { showToast(it) },
            loadConfig = { loadConfig() },
            onMangaOcrError = { msg ->
                statusOverlay.showError(msg)
                ballStateManager?.setState(BallStateManager.State.Error)
            },
            onMangaOcrDownloadRequired = { msg ->
                statusOverlay.showImmediate(msg)
                ballStateManager?.setState(BallStateManager.State.Idle)
            }
        )
        checkLanguageHints()
        initTranslator()

        // 读取 AI 上下文设置
        contextEnabled = prefs.getBoolean("game_context_enabled", false)
        contextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 监听源语言、引擎、结果样式变化，实时检查语言/模型提示并刷新 config
        val watchedKeys = setOf(
            "Ocr_Engine_Group",
            "Source_Language",
            "Manga_Det_Model",
            "Manga_Rec_Model",
            "Manga_Keep_Text_Free",
            "Manga_Text_Color",
            "Manga_BG_Color",
            "Manga_Text_Direction"
        )
        prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                // 翻译模型切换：重建 translator（共享 Hy-MT2 实例由 Holder 换出/重建，无需重启服务）
                key == "Text_API" || key == "Text_AI" -> initTranslator()
                key in watchedKeys -> {
                    config = loadConfig()
                    checkLanguageHints()
                }
            }
        }
        prefs.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefChangeListener)

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.starflow.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            LogCollector.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        // 先用 specialUse 启动（兼容无障碍模式），需要 MediaProjection 时再升级
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // 先初始化截图提供者，权限检查在 UI 初始化之前
        initScreenshotProvider()

        if (screenshotProvider is MediaProjectionProvider) {
            updateForegroundTypeForMediaProjection()
            if (!(screenshotProvider as MediaProjectionProvider).ensureInitialized()) {
                LogCollector.d(TAG, "MediaProjection needs permission, deferring UI init")
                ScreenCapturePermissionActivity.start(this, "manga")
                // 不创建 UI，等授权后再初始化
                return
            }
        }

        // 权限就绪，正常初始化 UI
        initializeViews()
        setupScreenshotCollector()

        // 初始化 OCR 引擎（识别器）
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}  // MLKit 无需初始化
            OcrEngine.MangaOcr -> lifecycleScope.launch { engineManager.ensureMangaOcrInitialized() }
            OcrEngine.PPOcrV5 -> lifecycleScope.launch { engineManager.initPPOcrV5("识别器") }
            OcrEngine.PPOcrV6 -> lifecycleScope.launch { engineManager.initPPOcrV6("识别器") }
        }

        // 初始化检测引擎（检测器）
        when (config.detEngine) {
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> lifecycleScope.launch { engineManager.initRTDetrV2() }
            DetEngine.PP_OCR_V5 -> lifecycleScope.launch { engineManager.initPPOcrV5("检测器") }
            DetEngine.PP_OCR_V6 -> lifecycleScope.launch { engineManager.initPPOcrV6("检测器") }
        }

        LogCollector.d(TAG, "MangaFloatingService created")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 清除后台时停止服务
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("PERMISSION_RESULT", false) == true) {
            LogCollector.d(TAG, "Permission granted, initializing Shooter")
            updateForegroundTypeForMediaProjection()
            val initialized = (screenshotProvider as? MediaProjectionProvider)?.ensureInitialized() ?: false
            LogCollector.d(TAG, "Shooter init result: $initialized")
            if (pendingAutoStart && initialized) {
                pendingAutoStart = false
                LogCollector.d(TAG, "Starting pending auto-translate")
                startAutoTranslate()
            } else if (autoTranslateEngine.isAutoTranslating && initialized) {
                LogCollector.d(TAG, "Resuming auto-translate")
                autoTranslateEngine.scheduleNextDetection(0L)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销悬浮球图标变更广播
        iconChangeReceiver?.let {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        iconChangeReceiver = null
        // 释放截图提供者
        screenshotProvider?.release()
        stopForegroundForScreenshot()
        // 注销 SharedPreferences listener
        prefChangeListener?.let {
            prefs.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(it)
        }
        prefChangeListener = null
        // 先取消协程，防止 View 清理后又由协程回调添加新 View
        lifecycleScope.cancel()
        removeAllViews()
        statusOverlay.release()
        ballStateManager?.release()
        ballStateManager = null
        translatorText?.release()
        autoTranslateEngine.clearScheduled()
        regionCache.clear()

        // 等待正在执行的 ONNX 推理完成后再释放资源
        // 防止 session.close() 和 session.run() 并发导致 native 内存损坏
        runBlocking(Dispatchers.IO) {
            coroutineContext[Job]?.children?.forEach { it.join() }
        }

        // 释放 OCR 引擎资源
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}
            OcrEngine.MangaOcr -> engineManager.releaseMangaOcr()
            OcrEngine.PPOcrV5 -> engineManager.releasePPOcrV5()
            OcrEngine.PPOcrV6 -> engineManager.releasePPOcrV6()
        }

        // 释放检测引擎资源
        when (config.detEngine) {
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> engineManager.releaseRTDetrV2()
            DetEngine.PP_OCR_V5 -> engineManager.releasePPOcrV5()
            DetEngine.PP_OCR_V6 -> engineManager.releasePPOcrV6()
        }

        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent(com.moe.starflow.translate.BroadcastAction.ACTION_MANGA_SERVICE_STOPPED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        LogCollector.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    // 初始化截图提供者
    private fun initScreenshotProvider() {
        val method = prefs.getString("Screenshot_Method", "0").toIntOrNull() ?: 0
        screenshotProvider = when (method) {
            0 -> MediaProjectionProvider(this)
            1 -> AccessibilityProvider()
            else -> MediaProjectionProvider(this)
        }
        LogCollector.d(TAG, "Screenshot provider initialized: ${screenshotProvider?.javaClass?.simpleName}")
    }

    private fun reloadConfig() {
        config = loadConfig()
        translatorText?.release()
        initTranslator()
    }

    private fun initTranslator() {
        LogCollector.d(TAG, "initTranslator: Text_API=${prefs.getInt("Text_API", Constants.TextApi.BING.id)}")
        try {
            translatorText = TranslatorFactory.create(this, prefs, TranslatorFactory.Mode.MANGA)
            if (translatorText == null) {
                LogCollector.e(TAG, "initTranslator: 引擎创建失败")
                showToast(getString(R.string.toast_engine_init_failed))
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "initTranslator: Exception", e)
            showToast("Initialize Error: ${e.message}")
        }

        // 显示翻译 API 初始化成功的消息
        if (translatorText != null) {
            val apiName = translatorText!!::class.simpleName ?: "Translation API"
            showToast(getString(R.string.toast_engine_init_ok, apiName))
        }

        LogCollector.d(TAG, "initTranslator: result translatorText=${translatorText?.javaClass?.simpleName}")
    }


    private fun loadConfig(): MangaModeConfig {
        // det/ocr 统一从 Ocr_Engine_Group 读（旧 Manga_Det_Model/Manga_Rec_Model 仅作为一次迁移输入，见 OcrEngineManager）
        val group = OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences())
        val detEngine = group.mangaDet
        // RT-DETR-V2 检测器已输出气泡/区域级结果，不需要 BubbleDetector 再次聚类
        val autoDetectBubble = if (detEngine == DetEngine.RT_DETR_V2) {
            false
        } else {
            prefs.getBoolean("Manga_Auto_Detect_Bubble", true)
        }
        return MangaModeConfig(
            enabled = true,
            textDirection = if (prefs.getString("Manga_Text_Direction", "0") == "1") TextDirection.VERTICAL_LR else TextDirection.VERTICAL_RL,
            smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
            autoDetectBubble = autoDetectBubble,
            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
            autoFontSize = prefs.getBoolean("Manga_Auto_Font_Size", true),
            sourceLang = prefs.getString("Source_Language", "ja"),
            targetLang = prefs.getString("Target_Language", "zh"),
            textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
            bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255)),
            ocrEngine = group.mangaOcr,
            detEngine = detEngine,
            keepTextFree = prefs.getBoolean("Manga_Keep_Text_Free", true)
        )
    }

    @SuppressLint("InflateParams")
    private fun initializeViews() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        debugPanel = MangaDebugPanelController(
            this,
            windowManager,
            onDismissAll = { dismissResultOverlay() },
            onBringFront = { bringFloatingBallToFront() }
        )

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

        ballStateManager = BallStateManager(this, floatingBallView, BallStateManager.Mode.Comic)
        ballStateManager?.setState(BallStateManager.State.Idle)

        // 加载自定义悬浮球图标
        // 一次性迁移：旧 Custom_Floating_Pic 首次遇到时复制到 Icon_Comic
        if (!prefs.contains("Icon_Comic")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Comic", legacy)
        }

        val iconName = prefs.getString("Icon_Comic", "comic-1.准备识别-打开漫画页面.png")
        val iconView = floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
        if (iconName.isEmpty()) {
            iconView.setImageResource(R.mipmap.icon_comic_default)
        } else {
            val iconFile = java.io.File(getExternalFilesDir(null), "icon/$iconName")
            try {
                if (iconFile.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath)
                    iconView.setImageBitmap(bitmap)
                } else {
                    iconView.setImageResource(R.mipmap.icon_comic_default)
                }
            } catch (e: Exception) {
                LogCollector.w(TAG, "Failed to load custom icon", e)
                iconView.setImageResource(R.mipmap.icon_comic_default)
            }
        }

        // 加载长按判定时间
        longPressDelay = prefs.getLong("Custom_Long_Press_Delay", 300L)

        setupTouchListener()

        // Result overlay (initially not added) — FrameLayout 包含 ImageView，按钮可加入同一窗口
        resultOverlayImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
        }
        resultOverlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            addView(resultOverlayImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
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
        // Crop view (initially not added)
        cropView = CropView(this)
        // 必须用屏幕真实尺寸，MATCH_PARENT 会被系统栏截断
        val cropScreenSize = getScreenSize()
        cropViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = cropScreenSize.width
            height = cropScreenSize.height
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
    }

    /**
     * 获取屏幕真实物理像素尺寸（横屏/竖屏都正确，包含系统栏区域）
     * resources.displayMetrics 在 Service 上下文中可能返回竖屏尺寸
     */
    @Suppress("DEPRECATION")
    private fun getScreenSize(): android.util.Size {
        val defaultDisplay = windowManager.defaultDisplay
        val realSize = android.graphics.Point()
        defaultDisplay.getRealSize(realSize)
        return android.util.Size(realSize.x, realSize.y)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
                            // 双击检测
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < DOUBLE_CLICK_DELAY) {
                                // 双击：取消单击计时器，执行双击动作
                                handler.removeCallbacks(singleClickRunnable)
                                lastClickTime = 0L
                                // 双击反馈动画（快速双脉冲）
                                floatingBallView.animate()
                                    .scaleX(0.85f).scaleY(0.85f)
                                    .setDuration(60)
                                    .withEndAction {
                                        floatingBallView.animate()
                                            .scaleX(1.1f).scaleY(1.1f)
                                            .setDuration(60)
                                            .withEndAction {
                                                floatingBallView.animate()
                                                    .scaleX(1f).scaleY(1f)
                                                    .setDuration(60)
                                                    .start()
                                            }
                                            .start()
                                    }
                                    .start()
                                executeAction(doubleClickAction)
                            } else {
                                // 可能是单击，延迟等待第二次点击
                                lastClickTime = now
                                handler.removeCallbacks(singleClickRunnable)
                                handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_DELAY)
                            }
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
        executeAction(longPressAction)
    }

    private fun showMenu() {
        val cropLabel = if (cropRect != null) {
            getString(R.string.manga_mode_crop)
        } else {
            getString(R.string.manga_mode_fullscreen)
        }

        isMenuShowing = true

        // 普通模式：固定搭配，3 个合法组合循环（高级模式菜单已删除）
        showMenuSimple(cropLabel)
    }

    /**
     * 普通模式菜单：从 engineCombos 读取当前标签
     */
    private fun showMenuSimple(cropLabel: String) {
        val modelLabel = comboLabel(currentCombo())

        val langName = getCurrentSourceLangName()
        val (dialog, listView) = Dialogs.mangaMenuDialogSimple(
            this, autoTranslateEngine.isAutoTranslating, cropLabel, modelLabel, langName
        )

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    // 切换全屏/框选
                    if (cropRect != null) {
                        cropRect = null
                        showToast(getString(R.string.manga_mode_fullscreen), true)
                        val adapter = listView.adapter as com.moe.starflow.translate.widget.MenuDialogAdapter
                        adapter.updateLabel(0, "${getString(R.string.manga_crop_toggle)}：${getString(R.string.manga_mode_fullscreen)}")
                    } else {
                        dialog.dismiss()
                        handler.postDelayed({ startCropSelection() }, 200)
                    }
                }
                1 -> {
                    if (autoTranslateEngine.isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        showFontSizeDialog()
                    }
                }
                2 -> {
                    if (autoTranslateEngine.isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        // 切换模型（固定搭配）
                        toggleModelSimple(dialog, listView)
                    }
                }
                3 -> {
                    if (autoTranslateEngine.isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_no_switch), true)
                    } else {
                        // 循环切换源语言，不关闭菜单
                        cycleSourceLang()
                        val adapter = listView.adapter as com.moe.starflow.translate.widget.MenuDialogAdapter
                        adapter.updateLabel(3, "${getString(R.string.game_switch_language)}：${getCurrentSourceLangName()}")
                    }
                }
                4 -> {
                    // 自动翻译
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.starflow.translate.widget.MenuDialogAdapter
                    if (autoTranslateEngine.isAutoTranslating) {
                        adapter.updateLabel(4, getString(R.string.manga_menu_stop_auto))
                        adapter.updateIcon(4, R.drawable.stop_auto)
                    } else {
                        adapter.updateLabel(4, getString(R.string.manga_menu_auto_translate))
                        adapter.updateIcon(4, R.drawable.start_auto)
                    }
                }
                5 -> {
                    dialog.dismiss()
                    stopSelf()
                }
                6 -> {
                    dialog.dismiss()
                    backToMainActivity()
                }
            }
        }

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        // 竖屏宽度限制，横屏保持原有比例
        val screenSize = getScreenSize()
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val maxW = (screenSize.width * 0.4).toInt()
            val maxH = (screenSize.height * 0.7).toInt()
            dialog.window?.setLayout(maxW, maxH)
        } else {
            val maxW = (screenSize.width * 0.80f).toInt()
            dialog.window?.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { isMenuShowing = false }
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

    /**
     * 语言/模型可用性提示（系统 Toast）
     * 触发点：onCreate、toggleModelSimple、SharedPreferences listener
     *
     * 场景：
     * 1. 漫画翻译运行中 + 非日文 → 提示
     * 2. 韩文 + PP引擎 + KO未下载 → 提示下载
     * 3. 俄文 + 非PP引擎 → 提示切换到PP
     * 4. 俄文 + PP引擎 + RU未下载 → 提示下载
     */
    private fun checkLanguageHints() {
        val isPPv5 = config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5
        val isMangaOcr = config.ocrEngine == OcrEngine.MangaOcr
        val src = config.sourceLang

        // 俄文：仅 PP-OCRv5 支持（v6 / ML Kit / manga-ocr 均不支持西里尔文）
        if (src == "ru" && !isPPv5) {
            showSystemToast(getString(R.string.ru_need_ppocrv5_engine))
            return
        }
        // PP-OCRv5：检查 KO/RU 等需要下载独立模型的语言
        if (isPPv5) {
            val (_, hint) = PPOcrV5Engine.resolveRecLang(this, src)
            if (hint != null) {
                showSystemToast(hint)
                return
            }
        }
        // PP-OCRv6：多语言模型内置，无需额外下载检查

        // manga-ocr 模型 + 非日文 → 提示
        if (isMangaOcr && src != "ja") {
            showSystemToast(getString(R.string.manga_ocr_non_ja_hint))
        }
    }

    private fun showSystemToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 循环切换源语言：仅中(繁)/日/英/韩等常用语言（与主页共用 Source_Language pref）。
     * 只循环当前 OCR 组支持的语言，跳过不支持的。逻辑统一收敛到 OcrEngineManager.cycleFloatingSourceLang。
     */
    private fun cycleSourceLang() {
        val next = com.moe.starflow.utils.OcrEngineManager.cycleFloatingSourceLang(prefs.getSharedPreferences())
            ?: run { showToast(getString(R.string.no_available_ocr_model), true); return }
        config = loadConfig()  // 写 pref 之后再读，刷新内存 config.sourceLang
        val langName = com.moe.starflow.translate.CustomLocale.getInstance(next).getDisplayName()
        showToast(getString(R.string.language_switched_to, langName), true)
        checkLanguageHints()
    }

    /**
     * 获取当前源语言的显示名称
     */
    private fun getCurrentSourceLangName(): String {
        val lang = prefs.getString("Source_Language", "ja")
        return com.moe.starflow.translate.CustomLocale.getInstance(lang).getDisplayName()
    }

    /**
     * 切换模型组合（循环遍历 engineCombos，跳过不可用的）
     */
    private fun toggleModelSimple(@Suppress("UNUSED_PARAMETER") dialog: AlertDialog, listView: android.widget.ListView) {
        val cur = currentCombo()
        val curIdx = engineCombos.indexOf(cur).coerceAtLeast(0)

        // 找下一个可用组合（向前循环，最多绕过一圈）
        var nextIdx = curIdx
        var next: ComboDef
        do {
            nextIdx = (nextIdx + 1) % engineCombos.size
            next = engineCombos[nextIdx]
        } while (!isComboAvailable(next) && nextIdx != curIdx)

        // 释放所有旧引擎
        engineManager.releaseMangaOcr()
        engineManager.releasePPOcrV5()
        engineManager.releasePPOcrV6()
        engineManager.releaseRTDetrV2()

        applyCombo(next)

        // 更新菜单标签
        val adapter = listView.adapter as com.moe.starflow.translate.widget.MenuDialogAdapter
        adapter.updateLabel(2, "${getString(R.string.manga_model_toggle)}：${comboLabel(next)}")
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf(
            getString(R.string.manga_font_size_auto),
            "8", "10", "12", "14", "16", "18", "20", "24", "28", "32", "40", "48"
        )
        val currentIndex = if (config.autoFontSize) {
            0
        } else {
            val idx = sizes.indexOf(config.fontSize.toInt().toString())
            // 自定义值（不在列表）时指向默认 16
            if (idx < 0) sizes.indexOf("16").coerceAtLeast(0) else idx
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.manga_font_size_title))
            .setSingleChoiceItems(sizes, currentIndex) { d, which ->
                if (which == 0) {
                    config = config.copy(autoFontSize = true)
                    prefs.setBoolean("Manga_Auto_Font_Size", true)
                    showToast(getString(R.string.manga_font_size_auto), true)
                } else {
                    val newSize = sizes[which].toFloat()
                    config = config.copy(fontSize = newSize, autoFontSize = false)
                    prefs.setFloat("Manga_Font_Size", newSize)
                    prefs.setBoolean("Manga_Auto_Font_Size", false)
                    showToast("${sizes[which]}sp", true)
                }
                d.dismiss()
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    // ---------- Auto-translate — 智能状态机 ----------

    private fun toggleAutoTranslate() {
        if (autoTranslateEngine.isAutoTranslating) {
            stopAutoTranslate()
        } else {
            startAutoTranslate()
        }
    }

    private fun startAutoTranslate() {
        // 只在 AccessibilityService 模式下检查无障碍服务
        val isMediaProjection = screenshotProvider is MediaProjectionProvider
        if (!isMediaProjection && AccessibilityServiceManager.getService() == null) {
            showToast(getString(R.string.accessibility_recycle), true)
            return
        }
        // MediaProjection 模式：检查权限，未授权则请求并等待回调
        if (isMediaProjection && !(screenshotProvider as MediaProjectionProvider).ensureInitialized()) {
            LogCollector.d(TAG, "startAutoTranslate: MediaProjection not ready, requesting permission")
            pendingAutoStart = true
            ScreenCapturePermissionActivity.start(this, "manga")
            return
        }
        pendingAutoStart = false
        consecutiveEmptyCount = 0
        // 状态初始化/首检调度/region 缓存清空/toast 由状态机处理
        autoTranslateEngine.start()
    }

    private fun stopAutoTranslate() {
        consecutiveEmptyCount = 0
        autoTranslateEngine.stop()
        // 自动翻译中强制关闭：若有翻译在途，同样丢弃部分结果不保存
        if (isProcessing) {
            cancelInFlightTranslation(showMessage = false)
        }
    }


    // ---------- Crop selection ----------

    private fun startCropSelection() {
        if (isCropActive) {
            showToast(getString(R.string.manga_crop_active), true)
            return
        }

        // 暂停自动翻译
        if (autoTranslateEngine.isAutoTranslating) {
            wasAutoTranslatingBeforeCrop = true
            stopAutoTranslate()
            LogCollector.d(TAG, "框选模式：暂停自动翻译")
        }

        val screenSize = getScreenSize()

        if (cropRect != null && resources.configuration.orientation == 1) {
            cropView.setRect(cropRect!!)
        } else {
            // 等布局完成后用 view 自身尺寸计算居中框选区域
            cropView.setRectCentered(0.8f, 0.6f)
        }

        cropView.onConfirmCrop = { confirmCrop() }
        // 每次显示时更新 overlay 尺寸，防止旋转后过期
        cropViewParams?.apply {
            width = screenSize.width
            height = screenSize.height
            x = 0
            y = 0
        }
        windowManager.addView(cropView, cropViewParams)
        isCropActive = true

        bringFloatingBallToFront()
    }

    private fun confirmCrop() {
        cropRect = RectF(cropView.mRect)
        isCropActive = false

        try {
            windowManager.removeView(cropView)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error removing crop view", e)
        }

        bringFloatingBallToFront()

        showToast(getString(R.string.manga_crop_confirm), true)

        // 恢复自动翻译
        if (wasAutoTranslatingBeforeCrop) {
            wasAutoTranslatingBeforeCrop = false
            startAutoTranslate()
            LogCollector.d(TAG, "框选完成：恢复自动翻译")
        }
    }

    // ---------- Click handler ----------

    private fun executeAction(action: Constants.BallAction) {
        when (action) {
            Constants.BallAction.TRANSLATE -> doTranslate()
            Constants.BallAction.MENU -> showMenu()
            Constants.BallAction.AUTO_TRANSLATE -> toggleAutoTranslate()
            Constants.BallAction.CLOSE_FLOATING -> stop(this)
        }
    }

    private fun doTranslate() {
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
        // 自动翻译中点击 → 手动翻译，暂停自动检测
        if (autoTranslateEngine.isAutoTranslating) {
            autoTranslateEngine.isManualTranslating = true
        }
        triggerTranslation()
    }

    private fun triggerTranslation() {
        LogCollector.d(TAG, "========== triggerTranslation START ==========")
        if (isProcessing) {
            LogCollector.d(TAG, "triggerTranslation: already processing, skipping")
            if (autoTranslateEngine.isAutoTranslating) {
                // 自动翻译中：只提示（取消翻译请双击悬浮球关闭自动翻译）
                showToast(getString(R.string.is_translating_auto), true)
            } else if (partialRenderShown) {
                // 手动翻译中且已有部分结果上屏（分批渲染首批 / 本地流式出字）→ 确认后停止，避免误丢已出结果
                showStopTranslationDialog()
            } else {
                // 手动翻译中且尚无任何结果上屏 → 直接终止 + 提示，不弹确认
                stopTranslationNow()
            }
            return
        }
        if (isCropActive) {
            LogCollector.d(TAG, "triggerTranslation: crop is active, skipping")
            return
        }

        // 只在 AccessibilityService 模式下检查无障碍服务
        val isMediaProjection = screenshotProvider is MediaProjectionProvider
        if (!isMediaProjection) {
            val service = AccessibilityServiceManager.getService()
            LogCollector.d(TAG, "triggerTranslation: accessibilityService=$service")
            if (service == null) {
                showToast(getString(R.string.accessibility_recycle), true)
                return
            }
        } else {
            LogCollector.d(TAG, "triggerTranslation: MediaProjection mode, skipping accessibility check")
        }

        // 只重新加载视觉配置（文字方向、字体等），不重新初始化翻译API
        config = loadConfig()

        isProcessing = true
        translationCancelled = false  // 每次翻译开始重置取消标志
        partialRenderShown = false    // 每次翻译开始重置部分结果标志

        // 先关闭所有overlay再截图，避免截到进度条/翻译结果
        dismissResultOverlay()
        dismissProgressOverlay()

        // 延迟截图，确保 overlay 从屏幕上完全消失（需要等下一帧渲染）
        lifecycleScope.launch {
            kotlinx.coroutines.delay(150)
            LogCollector.d(TAG, "triggerTranslation: translatorText=${translatorText?.javaClass?.simpleName}")
            LogCollector.d(TAG, "triggerTranslation: cropRect=$cropRect")
            if (cropRect != null) {
                LogCollector.d(TAG, "triggerTranslation: taking cropped screenshot")
            } else {
                LogCollector.d(TAG, "triggerTranslation: taking full screenshot")
            }
            val screenshotStarted = takeScreenshotWithProvider(cropRect, cropView.absolutePointOffset)
            if (!screenshotStarted) {
                LogCollector.w(TAG, "Screenshot not started (permission needed?), resetting isProcessing")
                isProcessing = false
            }
            LogCollector.d(TAG, "========== triggerTranslation END ==========")
        }
    }

    // 使用 ScreenshotProvider 截图
    // @return true 截图已启动，false 截图未启动（需要权限等）
    private fun takeScreenshotWithProvider(cropRect: RectF?, offset: Point): Boolean {
        val provider = screenshotProvider ?: return false
        LogCollector.d(TAG, "takeScreenshotWithProvider: provider=${provider.javaClass.simpleName}, cropRect=$cropRect")

        var ballWasShowing = false

        if (provider is MediaProjectionProvider) {
            // MediaProjection 模式：需要已初始化（权限在服务启动时请求）
            if (!provider.ensureInitialized()) {
                LogCollector.w(TAG, "MediaProjection not initialized, permission not granted yet")
                showToast(getString(R.string.toast_screen_capture_permission), true)
                return false
            }
            // 手动模式：截图前隐藏悬浮球，避免遮挡页面内容影响 pHash
            // 自动模式：不隐藏（由 collector 中 STABLE 后的重截图逻辑获取干净截图）
            if (!autoTranslateEngine.isAutoTranslating) {
                ballWasShowing = ::floatingBallView.isInitialized &&
                    isViewAdded(floatingBallView) &&
                    floatingBallView.visibility == View.VISIBLE
            }
            // 异步截图：先获取全屏，再由服务层裁剪（保留全屏 bitmap 供缓存使用）
            lifecycleScope.launch {
                if (!autoTranslateEngine.isAutoTranslating && ballWasShowing) {
                    floatingBallView.visibility = View.GONE
                    LogCollector.d(TAG, "takeScreenshotWithProvider: 手动模式隐藏悬浮球")
                    // 等至少一个 VSYNC 周期，确保 VD 产出无球的新帧
                    delay(50)
                }
                LogCollector.d(TAG, "Taking MediaProjection screenshot")
                try {
                    val fullBitmap = provider.takeScreenshot(null, offset)
                    if (fullBitmap != null) {
                        LogCollector.d(TAG, "Full screenshot: ${fullBitmap.width}x${fullBitmap.height}")
                        val croppedBitmap = if (cropRect != null) {
                            val cropped = ScreenshotManager.cropBitmap(fullBitmap, cropRect, offset)
                            LogCollector.d(TAG, "Cropped screenshot: ${cropped.width}x${cropped.height}")
                            cropped
                        } else null
                        ScreenshotManager.emitScreenshot(ScreenshotData(fullBitmap, croppedBitmap))
                    } else {
                        LogCollector.w(TAG, "Screenshot returned null")
                        isProcessing = false
                        if (autoTranslateEngine.isAutoTranslating) {
                            stopAutoTranslate()
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot exception", e)
                    isProcessing = false
                    if (autoTranslateEngine.isAutoTranslating) {
                        stopAutoTranslate()
                    }
                } finally {
                    if (ballWasShowing) {
                        floatingBallView.visibility = View.VISIBLE
                        LogCollector.d(TAG, "takeScreenshotWithProvider: 恢复悬浮球")
                    }
                }
            }
            return true
        } else {
            // AccessibilityService 模式：手动模式截图前隐藏悬浮球
            // 自动模式：不隐藏（由 collector 中 STABLE 后的重截图逻辑获取干净截图）
            if (!autoTranslateEngine.isAutoTranslating) {
                ballWasShowing = ::floatingBallView.isInitialized &&
                    isViewAdded(floatingBallView) &&
                    floatingBallView.visibility == View.VISIBLE
                if (ballWasShowing) {
                    floatingBallView.visibility = View.GONE
                }
            }
            lifecycleScope.launch {
                if (ballWasShowing) delay(50)
                LogCollector.d(TAG, "Taking AccessibilityService screenshot")
                try {
                    provider.takeScreenshot(cropRect, offset)
                } finally {
                    if (ballWasShowing) {
                        floatingBallView.visibility = View.VISIBLE
                    }
                }
            }
            return true
        }
    }

    // 启动前台服务（MediaProjection 模式需要）
    private fun startForegroundForScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SCREEN_CAPTURE_CHANNEL_ID,
                getString(R.string.foreground_service_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_service_notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, SCREEN_CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(getString(R.string.foreground_service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    // 停止前台服务
    private fun stopForegroundForScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * 更新前台服务类型为 MEDIA_PROJECTION
     * 服务在 onCreate 中以默认类型启动，使用 MediaProjection 前需要更新类型
     */
    private fun updateForegroundTypeForMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            LogCollector.d(TAG, "Updated foreground service type to MEDIA_PROJECTION")
        }
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        LogCollector.d(TAG, "setupScreenshotCollector: starting collector coroutine")
        ScreenshotManager.setEventMode(AccessibilityEventHandler.Mode.MANGA)
        lifecycleScope.launch {
            LogCollector.d(TAG, "Screenshot collector: coroutine started, waiting for screenshots...")
            ScreenshotManager.screenshotFlow.collect { data ->
                // ocrBitmap: 用于 OCR 和翻译流程的 bitmap（裁剪后或全屏）
                val ocrBitmap = data.croppedBitmap ?: data.fullBitmap
                LogCollector.d(TAG, "Screenshot collector: RECEIVED! full=${data.fullBitmap.width}x${data.fullBitmap.height}, ocr=${ocrBitmap.width}x${ocrBitmap.height}")

                try {
                    // 无障碍重截图拦截：干净截图到达后直接用保存的检测 hash 处理
                    if (pendingCleanScreenshot) {
                        pendingCleanScreenshot = false
                        val detectionPHash = pendingDetectionPHash
                        val detectionExtHashes = pendingDetectionExtHashes
                        pendingDetectionPHash = 0L
                        pendingDetectionExtHashes = null
                        pendingFullBitmap = data.fullBitmap
                        showProgressOverlay(getString(R.string.manga_translating))
                        processMangaScreenshot(ocrBitmap, detectionPHash, detectionExtHashes)
                        return@collect
                    }

                    // 自动翻译模式：pHash 门控（手动翻译时跳过）
                    if (autoTranslateEngine.isAutoTranslating && !autoTranslateEngine.isManualTranslating) {
                        // 40s 超时省电检测：同一页面超过 40s 未变化，自动停止翻译
                        if (autoTranslateEngine.lastTranslatedTime > 0 && System.currentTimeMillis() - autoTranslateEngine.lastTranslatedTime > 40_000) {
                            LogCollector.d(TAG, "Screenshot collector: 40s 超时，页面未变化，停止自动翻译")
                            ocrBitmap.recycle()
                            pendingFullBitmap?.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            stopAutoTranslate()
                            AlertDialog.Builder(this@MangaFloatingService)
                                .setTitle("自动翻译超时")
                                .setMessage("页面超过 40 秒未变化，已自动停止翻译服务以节省电量。")
                                .setCancelable(false)
                                .setPositiveButton("确定", null)
                                .create()
                                .apply {
                                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                                    show()
                                }
                            return@collect
                        }
                        // 用全屏截图计算稳定的 pHash（不受框选偏移影响）
                        val pHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 256-bit 扩展哈希（用于缓存精确匹配）
                        val extHashes = PerceptualHash.computeExtended(data.fullBitmap, centerCrop = true)
                        // 保存全屏 bitmap 引用用于缓存（不要在翻译前释放）
                        pendingFullBitmap = data.fullBitmap
                        val shouldTranslate = autoTranslateEngine.processAutoDetectPHash(pHash)
                        if (!shouldTranslate) {
                            ocrBitmap.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            // 不关闭进度条，保持"自动检测中"显示
                            return@collect
                        }
                        // pHash 通过门控 → 确认翻译，重截干净图（无悬浮球、无进度条）
                        if (screenshotProvider is MediaProjectionProvider) {
                            val mpProvider = screenshotProvider as MediaProjectionProvider
                            val offset = cropView.absolutePointOffset

                            dismissProgressOverlay()
                            val ballWasShowing = ::floatingBallView.isInitialized &&
                                isViewAdded(floatingBallView) &&
                                floatingBallView.visibility == View.VISIBLE
                            if (ballWasShowing) {
                                floatingBallView.visibility = View.GONE
                                LogCollector.d(TAG, "Auto STABLE: 隐藏悬浮球准备重截干净图")
                            }
                            // 等 VD 产新帧（无球的画面），50ms 通常足够 SurfaceFlinger 刷新
                            delay(50)

                            // 截图拿干净图
                            val cleanFull = mpProvider.takeScreenshot(null, offset)
                            if (ballWasShowing) floatingBallView.visibility = View.VISIBLE

                            if (cleanFull != null) {
                                showProgressOverlay(getString(R.string.manga_translating))
                                ocrBitmap.recycle()
                                if (data.croppedBitmap != null) data.fullBitmap.recycle()
                                pendingFullBitmap = cleanFull
                                val rect = cropRect
                                val cleanOcr = if (rect != null) {
                                    ScreenshotManager.cropBitmap(cleanFull, rect, offset)
                                } else cleanFull
                                val cleanExtHashes = PerceptualHash.computeExtended(cleanFull, centerCrop = true)
                                processMangaScreenshot(cleanOcr, pHash, cleanExtHashes)
                            } else {
                                LogCollector.w(TAG, "Auto STABLE: 干净截图返回 null，降级到检测截图")
                                if (ballWasShowing) floatingBallView.visibility = View.VISIBLE
                                showProgressOverlay(getString(R.string.manga_translating))
                                processMangaScreenshot(ocrBitmap, pHash, extHashes)
                            }
                        } else {
                            // 无障碍异步重截：等冷却 → 隐藏球 → 触发截图 → 设标志等下一张 flow
                            // 无障碍 takeScreenshot API 需至少 350ms 冷却，否则系统限流返回失败
                            lifecycleScope.launch {
                                delay(350) // 无障碍截图 API 冷却期（Android 12+ 后台截图频率限制）
                                dismissProgressOverlay()  // 关掉 MOTION 阶段的"检测中..."，避免被截入
                                val ballWasShowing = ::floatingBallView.isInitialized &&
                                    isViewAdded(floatingBallView) &&
                                    floatingBallView.visibility == View.VISIBLE
                                if (ballWasShowing) {
                                    floatingBallView.visibility = View.GONE
                                    LogCollector.d(TAG, "Auto STABLE: 无障碍隐藏悬浮球准备重截")
                                }
                                delay(50)
                                screenshotProvider!!.takeScreenshot(cropRect, cropView.absolutePointOffset)
                                pendingDetectionPHash = pHash
                                pendingDetectionExtHashes = extHashes
                                pendingCleanScreenshot = true
                                if (ballWasShowing) {
                                    floatingBallView.visibility = View.VISIBLE
                                    LogCollector.d(TAG, "Auto STABLE: 无障碍恢复悬浮球")
                                }
                            }
                            // 回收当前带球截图，等干净截图到达
                            ocrBitmap.recycle()
                            pendingFullBitmap?.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                        }
                    } else {
                        // 手动模式：用全屏截图计算稳定的缓存 pHash
                        val cachePHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 256-bit 扩展哈希（用于缓存精确匹配）
                        val extHashes = PerceptualHash.computeExtended(data.fullBitmap, centerCrop = true)
                        // 保存全屏 bitmap 引用用于缓存（不要在翻译前释放）
                        pendingFullBitmap = data.fullBitmap
                        showProgressOverlay("检测中...")
                        try {
                            processMangaScreenshot(ocrBitmap, cachePHash, extHashes)
                        } finally {
                            autoTranslateEngine.isManualTranslating = false  // 无论成功失败，恢复自动检测
                        }
                    }
                    LogCollector.d(TAG, "Screenshot collector: processMangaScreenshot completed normally")
                } catch (e: TranslationCancelledException) {
                    // 用户主动停止翻译：用专用异常可靠识别（不依赖 translationCancelled，
                    // 避免被下一次翻译抢先重置导致竞态误报「翻译失败」）
                    isProcessing = false
                    dismissProgressOverlay()
                    LogCollector.d(TAG, "Screenshot collector: 翻译已被用户停止")
                } catch (e: java.io.FileNotFoundException) {
                    LogCollector.e(TAG, "Screenshot collector: 模型文件缺失", e)
                    isProcessing = false
                    dismissProgressOverlay()
                    statusOverlay.showError("识别模型文件缺失：${e.message}")
                    ballStateManager?.setState(BallStateManager.State.Error)
                    if (autoTranslateEngine.isAutoTranslating) {
                        stopAutoTranslate()
                    }
                } catch (e: Exception) {
                    isProcessing = false
                    // 先 dismiss 进度条，再显示错误（错误会保持显示直到用户点击复制）
                    dismissProgressOverlay()
                    if (translationCancelled) {
                        // 用户主动停止翻译：不按失败处理（已由 stopTranslationNow 显示"已停止翻译"）
                        LogCollector.d(TAG, "Screenshot collector: 翻译已被用户停止（${e.message}）")
                    } else {
                        LogCollector.e(TAG, "Screenshot collector: CAUGHT EXCEPTION", e)
                        statusOverlay.showError("翻译失败：${e.message ?: "Unknown error"}")
                        ballStateManager?.setState(BallStateManager.State.Error)
                        if (autoTranslateEngine.isAutoTranslating) {
                            stopAutoTranslate()
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            LogCollector.e(TAG, "Screenshot collector: collect() returned unexpectedly", null)
        }

        // 无障碍事件辅助：滚动/内容变化时加速检测（事件经 EventHandler 去抖后到达）
        lifecycleScope.launch {
            ScreenshotManager.eventTriggerFlow.collect { eventType ->
                if (autoTranslateEngine.isAutoTranslating && autoTranslateEngine.detectState == DetectState.IDLE && !isProcessing) {
                    LogCollector.d(TAG, "事件触发 [$eventType]: 立即检测")
                    autoTranslateEngine.scheduleNextDetection(500L)
                }
            }
        }
    }

    // ---------- Manga translation pipeline ----------

    // ========== 翻译流水线 ==========

    /**
     * 保存翻译缓存（不渲染 overlay）。
     * 用于分批渲染场景：用户关闭 overlay 后仍保存完整缓存。
     */
    private suspend fun saveCacheEntry(original: Bitmap, allBubbles: List<TranslatedBubble>) {
        try {
            val translatorName = TranslateUtils.buildTranslatorDisplayName(translatorText, config.detEngine, config.ocrEngine, prefs.getSharedPreferences())
            val ocrTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
            val transTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
            LogCollector.d(TAG, "saveCacheEntry: ${allBubbles.size} 个气泡")
            // 使用实际裁剪坐标（如果有 cropRect）或全屏尺寸
            val fullWidth = pendingFullBitmap?.width ?: original.width
            val fullHeight = pendingFullBitmap?.height ?: original.height
            val useCrop = cropRect != null
            val entry = CacheEntry(
                type = TranslationCacheManager.MODE_MANGA,
                sourceText = ocrTexts.ifEmpty { null },
                translatedText = transTexts.ifEmpty { null },
                resultBitmap = null,
                sourceLang = config.sourceLang,
                targetLang = config.targetLang,
                translatorName = translatorName,
                pHash = (currentExtHashes?.getOrElse(0) { currentPHash }) ?: currentPHash,
                pHash2 = currentExtHashes?.getOrElse(1) { 0L } ?: 0L,
                pHash3 = currentExtHashes?.getOrElse(2) { 0L } ?: 0L,
                pHash4 = currentExtHashes?.getOrElse(3) { 0L } ?: 0L,
                sessionId = sessionId,
                lastSessionId = sessionId,
                cropLeft = if (useCrop) cropRect!!.left.toInt() else 0,
                cropTop = if (useCrop) cropRect!!.top.toInt() else 0,
                cropRight = if (useCrop) cropRect!!.right.toInt() else fullWidth,
                cropBottom = if (useCrop) cropRect!!.bottom.toInt() else fullHeight,
                bubbleRects = if (allBubbles.isNotEmpty()) {
                    TranslationCacheUtils.serializeBubbleRects(allBubbles)
                } else null
            )
            if (isForceRefreshActive) {
                // 只删除同页面的缓存（pHash 匹配），避免误删其他页面
                val historyIdToDelete = if (currentPHash == lastCachedPHash) lastCachedHistoryId else 0L
                cacheManager.refreshCache(historyIdToDelete, entry, originalBitmap = pendingFullBitmap)
                lastCachedHistoryId = 0
                lastCachedPHash = 0
                isForceRefreshActive = false
            } else {
                cacheManager.saveToCache(entry, originalBitmap = pendingFullBitmap)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "saveCacheEntry 失败", e)
        }
    }

    /**
     * 分批渲染流程：检测+裁剪 → 分两批 OCR+翻译+渲染。
     * 支持 RT-DETR-V2 + MangaOcr 和 PP-OCRv5 独立两种组合。
     *
     * @return true 如果执行了分批流程，false 如果不满足条件（应回退到原有流程）
     */
    private suspend fun incrementalTranslateFlow(bitmap: Bitmap): Boolean {
        // Hy-MT2 本地引擎：不走分批渲染（合并一次翻译 + 流式逐个显示，避免多次 prefill 拖慢）
        if (translatorText is HyMT2Translation) {
            LogCollector.d(TAG, "incrementalTranslateFlow: Hy-MT2 禁用分批渲染，走普通一次翻译+流式")
            return false
        }
        val isIncrementalEnabled = prefs.getBoolean("Incremental_Render", true)
        if (!isIncrementalEnabled) return false

        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        val isPPOcrV6Standalone = config.detEngine == DetEngine.PP_OCR_V6 && config.ocrEngine == OcrEngine.PPOcrV6
        if (!isRTDetrMangaOcr && !isPPOcrV5Standalone && !isPPOcrV6Standalone) return false

        return if (isRTDetrMangaOcr) {
            incrementalRTDetrMangaOcr(bitmap)
        } else if (isPPOcrV6Standalone) {
            incrementalPPOcrV6(bitmap)
        } else {
            incrementalPPOcrV5(bitmap)
        }
    }

    /**
     * 两批并行 OCR + 翻译 + 合并 + 上下文回滚 公共骨架（4c-2 提取，incrementalRTDetrMangaOcr/V5/V6 共用）。
     * 调用方负责：第一批 OCR（firstBubbleRegions）、第二批 OCR 异步任务（secondOcrJob）的启动与取消。
     * 本方法：第一批翻译（并行 await 第二批 OCR）→ 合并第二批 → 回滚分批渲染添加的 AI 上下文。
     */
    private suspend fun translateFirstThenSecondBatch(
        bitmap: Bitmap,
        firstBubbleRegions: List<BubbleRegion>,
        secondOcrJob: kotlinx.coroutines.Deferred<List<TextBlockInfo>>
    ): List<TranslatedBubble> {
        // 保存上下文历史大小，分批翻译完后回滚，避免污染后续页面的上下文
        val contextSnapshotSize = contextHistory.size
        val firstTranslated = if (firstBubbleRegions.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Main) {
                showProgressOverlay("翻译进行中，请勿点击屏幕...")
                ballStateManager?.setState(BallStateManager.State.Translating)
            }

            val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true) { partialBubbles ->
                if (partialBubbles.isNotEmpty()) {
                    launchPartialRender { renderStreamingOverlay(bitmap, partialBubbles) }
                }
            }
            if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false, showCopyButton = false)

            val secondTextBlocks = secondOcrJob.await()
            LogCollector.d(TAG, "第二批 OCR ${secondTextBlocks.size} 个文字块")
            if (secondTextBlocks.isNotEmpty()) {
                val secondBubbleRegions = MangaSpatialGrouping.textBlocksToBubbleRegions(secondTextBlocks, config.textDirection)
                result + incrementalTranslateBubbles(secondBubbleRegions, forceContext = true) { partialBubbles ->
                    if (partialBubbles.isNotEmpty()) {
                        lifecycleScope.launch {
                            renderStreamingOverlay(bitmap, partialBubbles)
                        }
                    }
                }
            } else {
                result
            }
        }

        // 回滚分批渲染添加的上下文，只保留翻译前的历史
        while (contextHistory.size > contextSnapshotSize) {
            contextHistory.removeLast()
        }
        return firstTranslated
    }

    /**
     * RT-DETR-V2 + MangaOcr 增量渲染。
     * 检测气泡 → 分批 MangaOcr encoder+decoder → 翻译+渲染。
     */
    private suspend fun incrementalRTDetrMangaOcr(bitmap: Bitmap): Boolean {
        engineManager.initRTDetrV2IfNeeded()
        engineManager.ensureMangaOcrInitialized()

        LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 开始检测+裁剪, keepTextFree=${config.keepTextFree}")
        val croppedBubbles = DetectionBridge.detectAndCropRTDetrV2(bitmap, config.keepTextFree)
        if (croppedBubbles.isEmpty()) {
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 未检测到气泡")
            if (!autoTranslateEngine.isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (croppedBubbles.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: ${croppedBubbles.size} <= $INCREMENTAL_THRESHOLD，不触发")
            croppedBubbles.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val sorted = MangaSpatialGrouping.sortByMangaReadingOrder(croppedBubbles)
        val groups = MangaSpatialGrouping.groupByProximity(sorted, { it.rect }, "RT-DETR")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第一批 ${firstBatch.size}，第二批 ${secondBatch.size}")

        try {
            showProgressOverlay("识别中（1/2）...")
            val firstTextBlocks = DetectionBridge.recognizeCroppedBubbles(
                firstBatch, config.sourceLang
            )
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.textDirection)
                val ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    DetectionBridge.recognizeCroppedBubbles(secondBatch, config.sourceLang)
                }
                translateFirstThenSecondBatch(bitmap, firstBubbleRegions, ocrJob)
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: TranslationCancelledException) {
            // 用户停止翻译：重抛让 collector 识别为取消，绝不回退重新 OCR
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalRTDetrMangaOcr: 失败", e)
            return false
        }
    }

    /**
     * PP-OCRv5 独立增量渲染。
     * det 检测全部文字行 → 逐行裁剪 → 分批 OCR + TextLineMerger 合并 → 翻译+渲染。
     */
    private suspend fun incrementalPPOcrV5(bitmap: Bitmap): Boolean {
        engineManager.initPPOcrV5IfNeeded()

        val (ppRecLang, hint) = PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
        if (hint != null) withContext(Dispatchers.Main) { showToast(hint, true) }
        // 非默认模型时提示
        if (ppRecLang != null && ppRecLang != PPOcrV5Engine.RecLang.ZH && ppRecLang != PPOcrV5Engine.RecLang.JA) {
            withContext(Dispatchers.Main) { showToast(getString(R.string.toast_using_dedicated_model, ppRecLang.code), false) }
        }
        if (ppRecLang == null) return false

        LogCollector.d(TAG, "incrementalPPOcrV5: 开始检测")
        val textLines = DetectionBridge.detectAndCropPPOcrV5Lines(this@MangaFloatingService, bitmap)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "incrementalPPOcrV5: 未检测到文字")
            if (!autoTranslateEngine.isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalPPOcrV5: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val groups = MangaSpatialGrouping.groupByProximity(textLines, { it.rect }, "PP-OCRv5")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalPPOcrV5: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        // 识别单批：OCR → TextLineMerger 合并 → TextBlockInfo
        suspend fun recognizeBatch(batch: List<CroppedTextLine>): List<TextBlockInfo> {
            val crops = batch.map { it.croppedBitmap }
            val rects = batch.map { it.rect }
            val angles = batch.map { it.angle }
            val centers = batch.map { android.graphics.PointF(it.centerX, it.centerY) }
            val recResults = try {
                withContext(Dispatchers.IO) {
                    PPOcrV5Engine.recognizeBatchWithCls(this@MangaFloatingService, crops, ppRecLang)
                }
            } catch (e: java.io.FileNotFoundException) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型加载失败：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消（用户停止翻译 / 新任务取代第二批 OCR）：不是模型错误，直接重抛不显示错误
                crops.forEach { it.recycle() }
                throw e
            } catch (e: Exception) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型异常：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            }
            // 释放裁剪图片
            crops.forEach { it.recycle() }
            // TextLineMerger 识别后合并
            val mergedInput = PPOcrV5Engine.recResultsToTextLines(recResults, rects, angles, centers)
            TextRegionMerger.refreshParams(this@MangaFloatingService)
            val allMerged = TextRegionMerger.merge(mergedInput.map { it.toTextRegion() }, verticalDirection = config.textDirection)
            // 合并后内容过滤
            val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
            LogCollector.d(TAG, "recognizeBatch TextLineMerger: ${mergedInput.size} 行 → ${allMerged.size} 合并 → 内容丢弃${contentDiscarded.size} → ${mergedRegions.size} 输出")
            return mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR,
                    angle = region.angle,
                    centerX = region.center.x,
                    centerY = region.center.y
                )
            }.filter { it.text.isNotBlank() }
        }

        var ocrJob: kotlinx.coroutines.Deferred<List<TextBlockInfo>>? = null
        try {
            showProgressOverlay("识别中（1/2）...")
            val firstTextBlocks = recognizeBatch(firstBatch)
            LogCollector.d(TAG, "incrementalPPOcrV5: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.textDirection)
                ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }
                translateFirstThenSecondBatch(bitmap, firstBubbleRegions, ocrJob!!)
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: TranslationCancelledException) {
            // 用户停止翻译：重抛让 collector 识别为取消，绝不回退重新 OCR
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalPPOcrV5: 失败", e)
            // 取消正在运行的 OCR 任务，避免 use-after-recycle
            ocrJob?.cancel()
            // 回收未处理的裁剪图片（firstBatch 已在 recognizeBatch 内部回收，跳过）
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return false
        }
    }

    private suspend fun incrementalPPOcrV6(bitmap: Bitmap): Boolean {
        engineManager.initPPOcrV6IfNeeded()

        LogCollector.d(TAG, "incrementalPPOcrV6: 开始检测")
        val textLines = DetectionBridge.detectAndCropPPOcrV6Lines(this@MangaFloatingService, bitmap)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "incrementalPPOcrV6: 未检测到文字")
            if (!autoTranslateEngine.isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalPPOcrV6: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val groups = MangaSpatialGrouping.groupByProximity(textLines, { it.rect }, "PP-OCRv6")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalPPOcrV6: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        suspend fun recognizeBatch(batch: List<CroppedTextLine>): List<TextBlockInfo> {
            val crops = batch.map { it.croppedBitmap }
            val rects = batch.map { it.rect }
            val angles = batch.map { it.angle }
            val centers = batch.map { android.graphics.PointF(it.centerX, it.centerY) }
            val recResults = try {
                withContext(Dispatchers.IO) {
                    PPOcrV6Engine.recognizeBatchWithCls(this@MangaFloatingService, crops)
                }
            } catch (e: java.io.FileNotFoundException) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型加载失败：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消（用户停止翻译 / 新任务取代第二批 OCR）：不是模型错误，直接重抛不显示错误
                crops.forEach { it.recycle() }
                throw e
            } catch (e: Exception) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型异常：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            }
            crops.forEach { it.recycle() }
            val mergedInput = PPOcrV6Engine.recResultsToTextLines(recResults, rects, angles, centers)
            TextRegionMerger.refreshParams(this@MangaFloatingService)
            val allMerged = TextRegionMerger.merge(mergedInput.map { it.toTextRegion() }, verticalDirection = config.textDirection)
            val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
            LogCollector.d(TAG, "recognizeBatch TextLineMerger: ${mergedInput.size} 行 → ${allMerged.size} 合并 → 内容丢弃${contentDiscarded.size} → ${mergedRegions.size} 输出")
            return mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR,
                    angle = region.angle,
                    centerX = region.center.x,
                    centerY = region.center.y
                )
            }.filter { it.text.isNotBlank() }
        }

        var ocrJob: kotlinx.coroutines.Deferred<List<TextBlockInfo>>? = null
        try {
            showProgressOverlay("识别中（1/2）...")
            val firstTextBlocks = recognizeBatch(firstBatch)
            LogCollector.d(TAG, "incrementalPPOcrV6: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.textDirection)
                ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }
                translateFirstThenSecondBatch(bitmap, firstBubbleRegions, ocrJob!!)
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: TranslationCancelledException) {
            // 用户停止翻译：重抛让 collector 识别为取消，绝不回退重新 OCR
            ocrJob?.cancel()
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalPPOcrV6: 失败", e)
            ocrJob?.cancel()
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return false
        }
    }

    /**
     */
    private suspend fun finalizeIncremental(bitmap: Bitmap, allTranslated: List<TranslatedBubble>) {
        if (translationCancelled) {
            LogCollector.d(TAG, "finalizeIncremental: 翻译已取消，跳过保存")
            return
        }
        if (allTranslated.isNotEmpty()) {
            renderAndShowMergedOverlay(bitmap, allTranslated, saveCache = false)
            LogCollector.d(TAG, "finalizeIncremental: 最终渲染完成，共 ${allTranslated.size} 个气泡")
            // Translating 状态已在分批翻译入口处设置（第一批翻译开始时），
            // 此处不再重复设置，避免图标在翻译完成后才短暂闪过。
            // 统一保存完整缓存
            LogCollector.d(TAG, "finalizeIncremental: 保存完整缓存，共 ${allTranslated.size} 个气泡")
            saveCacheEntry(bitmap, allTranslated)
            ballStateManager?.setState(BallStateManager.State.Completed)
        }
        statusOverlay.showImmediate("翻译完成")
        ballStateManager?.setState(BallStateManager.State.Completed)
        autoTranslateEngine.lastTranslatedHash = currentPHash
        autoTranslateEngine.lastTranslatedTime = System.currentTimeMillis()
        if (autoTranslateEngine.isAutoTranslating) autoTranslateEngine.scheduleNextDetection(MangaAutoTranslateEngine.DETECT_INTERVAL_MS)
    }

    private suspend fun processMangaScreenshot(bitmap: Bitmap, precomputedPHash: Long? = null, precomputedExtHashes: LongArray? = null) {
        try {
            LogCollector.d(TAG, "processMangaScreenshot: START")
            // 整个翻译流程开始（OCR 阶段），立刻标 Processing。
            // 单页路径会一路保持 Processing 直到 Step 3 调翻译；分批路径同理保持到 finalizeIncremental。
            ballStateManager?.setState(BallStateManager.State.Processing)

            // 清理过期的区域缓存
            if (autoTranslateEngine.isAutoTranslating) {
                val beforeSize = regionCache.size()
                regionCache.evictExpiredRegions()
                if (beforeSize != regionCache.size()) {
                    LogCollector.d(TAG, "evictExpiredRegions: ${beforeSize} → ${regionCache.size()} (removed ${beforeSize - regionCache.size()})")
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: cacheSize at start=${regionCache.size()}")

            // 使用全屏截图计算的稳定 pHash（不受框选偏移影响）
            // collector 已传入全屏 pHash，fallback 到 bitmap 计算（理论上不会走到）
            currentPHash = precomputedPHash ?: PerceptualHash.compute(bitmap, centerCrop = true)
            // 256-bit 扩展哈希（用于缓存精确匹配和存储）
            currentExtHashes = precomputedExtHashes
                ?: PerceptualHash.computeExtended(pendingFullBitmap ?: bitmap, centerCrop = true)

            // 纯色/均匀页面精确检测：dHash 全零说明中心区域无任何纹理结构（纯白/纯黑/纯色）
            // 全零 dHash 会假命中低纹理缓存页面（curBits=0/256 匹配 entryBits=7/256, sim=0.973）
            // 检测到无内容 → 跳过缓存+OCR+翻译，回到 IDLE
            if (currentExtHashes != null && currentExtHashes!!.all { it == 0L }) {
                LogCollector.d(TAG, "processMangaScreenshot: 画面无内容（dHash全零），跳过翻译")
                statusOverlay.showImmediate("未检测到文字")
                ballStateManager?.setState(BallStateManager.State.Completed)
                showToast(getString(R.string.toast_no_text_detected), false)
                autoTranslateEngine.lastTranslatedHash = currentPHash
        autoTranslateEngine.lastTranslatedTime = System.currentTimeMillis()
                if (autoTranslateEngine.isAutoTranslating) {
                    autoTranslateEngine.scheduleNextDetection(MangaAutoTranslateEngine.DETECT_INTERVAL_MS)
                }
                return
            }

            // 调试模式：最高优先级，跳过缓存直接检测
            val isDebugMode = when (config.detEngine) {
                DetEngine.RT_DETR_V2 -> prefs.getBoolean("RTDetrV2_Debug_View", false)
                DetEngine.MLKIT -> prefs.getBoolean("MLKit_Debug_View", false)
                DetEngine.PP_OCR_V5 -> prefs.getBoolean("PPOcrV5_Debug_View", false)
                DetEngine.PP_OCR_V6 -> prefs.getBoolean("PPOcrV6_Debug_View", false)
            }

            if (isDebugMode) {
                LogCollector.d(TAG, "processMangaScreenshot: Debug mode enabled, skip cache")
                when (config.detEngine) {
                    DetEngine.RT_DETR_V2 -> {
                        LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: 开始检测")
                        engineManager.initRTDetrV2IfNeeded()
                        val debugResult = withContext(Dispatchers.IO) {
                            DetectionBridge.detectWithRTDetrV2Debug(bitmap, config.keepTextFree)
                        }
                        LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: total=${debugResult.allBubbles.size}, text_bubble=${debugResult.textBubbles.size}, text_free=${debugResult.textFree.size}, bubble=${debugResult.emptyBubbles.size}")
                        showRTDetrV2DebugView(bitmap, debugResult)
                    }
                    DetEngine.MLKIT -> {
                        LogCollector.d(TAG, "ML Kit Debug Mode: 开始识别")
                        val mlKitResult = withContext(Dispatchers.IO) {
                            detectWithMLKitDebug(bitmap, config.sourceLang)
                        }
                        LogCollector.d(TAG, "ML Kit Debug Mode: blocks=${mlKitResult.textBlocks.size}, totalLines=${mlKitResult.totalLines}, totalElements=${mlKitResult.totalElements}")
                        showMLKitDebugView(bitmap, mlKitResult)
                    }
                    DetEngine.PP_OCR_V6 -> {
                        LogCollector.d(TAG, "PP-OCRv6 Debug Mode: 开始检测+识别")
                        engineManager.initPPOcrV6IfNeeded()
                        val ocrResult = withContext(Dispatchers.IO) {
                            PPOcrV6Engine.runOCR(this@MangaFloatingService, bitmap, useDet = true)
                        }
                        val debugDet = withContext(Dispatchers.IO) {
                            PPOcrV6Engine.runDetForDebug(this@MangaFloatingService, bitmap)
                        }
                        val recDisc = ocrResult.recDebug
                        val scoreDisc = recDisc?.discardedReasons?.count { it == "score" } ?: 0
                        val contentDisc = recDisc?.discardedReasons?.count { it != "score" } ?: 0
                        LogCollector.d(TAG, "PP-OCRv6 Debug: det=${ocrResult.boxes.size}, rec=${ocrResult.texts.size}, det丢弃=${debugDet.discardedBoxes.size}, 识别丢弃=$scoreDisc, 内容丢弃=$contentDisc")
                        val allMerged = try {
                            TextRegionMerger.enableDebugLogging(true)  // 合并判定详细日志（debug 模式）
                            PPOcrPostProcessing.runTextLineMerge(this, ocrResult, bitmap.width, bitmap.height, isV6 = true, textDirection = config.textDirection)
                        } finally {
                            TextRegionMerger.enableDebugLogging(false)
                        }
                        val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
                        LogCollector.d(TAG, "PP-OCRv6 Debug: merged=${allMerged.size}, 内容丢弃=${contentDiscarded.size}, 输出=${mergedRegions.size}")
                        showPPOcrV6DebugView(bitmap, ocrResult, mergedRegions, debugDet)
                    }
                    DetEngine.PP_OCR_V5 -> {
                        LogCollector.d(TAG, "PP-OCRv5 Debug Mode: 开始检测+识别")
                        engineManager.initPPOcrV5IfNeeded()
                        val (recLang, hint) = PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
                        if (hint != null) {
                            showToast(hint, true)
                        }
                        // 非默认模型时提示
                        if (recLang != null && recLang != PPOcrV5Engine.RecLang.ZH && recLang != PPOcrV5Engine.RecLang.JA) {
                            showToast(getString(R.string.toast_using_dedicated_model, recLang.code), false)
                        }
                        if (recLang != null) {
                            val ocrResult = withContext(Dispatchers.IO) {
                                PPOcrV5Engine.runOCR(this@MangaFloatingService, bitmap, recLang, useDet = true)
                            }
                            // 调试检测：获取被丢弃的选区
                            val debugDet = withContext(Dispatchers.IO) {
                                PPOcrV5Engine.runDetForDebug(this@MangaFloatingService, bitmap)
                            }
                            val recDisc = ocrResult.recDebug
                            val scoreDisc = recDisc?.discardedReasons?.count { it == "score" } ?: 0
                            val contentDisc = recDisc?.discardedReasons?.count { it != "score" } ?: 0
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: det=${ocrResult.boxes.size}, rec=${ocrResult.texts.size}, det丢弃=${debugDet.discardedBoxes.size}, 识别丢弃=$scoreDisc, 内容丢弃=$contentDisc")
                            // 原始识别详情
                            for (i in ocrResult.texts.indices) {
                                val text = ocrResult.texts[i]
                                val score = ocrResult.scores.getOrElse(i) { 0f }
                                val box = ocrResult.boxes.getOrNull(i)
                                if (box != null && box.size >= 8) {
                                    val topDx = box[2] - box[0]
                                    val topDy = box[3] - box[1]
                                    val leftDx = box[6] - box[0]
                                    val leftDy = box[7] - box[1]
                                    val topLen = kotlin.math.sqrt((topDx * topDx + topDy * topDy).toDouble()).toFloat()
                                    val leftLen = kotlin.math.sqrt((leftDx * leftDx + leftDy * leftDy).toDouble()).toFloat()
                                    var angle = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                                    if (abs(angle) <= 3f) angle = 0f
                                    val isVertical = leftLen > topLen * 1.5f
                                    val fontSize = if (isVertical) topLen else leftLen
                                    val dirLabel = if (isVertical) "V" else "H"
                                    val angleStr = if (abs(angle) > 0.5f) "∠${String.format("%.1f°", angle)}" else "∠0°"
                                    val quadStr = "TL(${box[0].toInt()},${box[1].toInt()}) TR(${box[2].toInt()},${box[3].toInt()}) BR(${box[4].toInt()},${box[5].toInt()}) BL(${box[6].toInt()},${box[7].toInt()})"
                                    LogCollector.d(TAG, "PP-OCRv5 RAW[$i]: ${String.format("%.2f", score)} $dirLabel fs=${String.format("%.0f", fontSize)} $angleStr $quadStr \"$text\"")
                                }
                            }
                            // 被丢弃选区详情
                            for (i in debugDet.discardedBoxes.indices) {
                                val box = debugDet.discardedBoxes[i]
                                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                                LogCollector.d(TAG, "PP-OCRv5 DISCARDED[$i]: ${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] $reason")
                            }
                            // 识别/内容丢弃详情
                            if (recDisc != null) {
                                for (i in recDisc.discardedBoxes.indices) {
                                    val box = recDisc.discardedBoxes[i]
                                    val score = recDisc.discardedScores.getOrElse(i) { 0f }
                                    val text = recDisc.discardedTexts.getOrElse(i) { "" }
                                    val reason = recDisc.discardedReasons.getOrElse(i) { "score" }
                                    val boxStr = "[${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}]"
                                    if (reason == "score") {
                                        LogCollector.d(TAG, "PP-OCRv5 REC_DISCARD[$i]: ${String.format("%.2f", score)}<thresh $boxStr \"${text.take(20)}\"")
                                    } else {
                                        LogCollector.d(TAG, "PP-OCRv5 CONTENT_DISCARD[$i]: $reason $boxStr \"${text.take(20)}\"")
                                    }
                                }
                            }
                            // 运行 TextLineMerger 合并（debug 模式开详细判定日志）
                            val allMerged = try {
                                TextRegionMerger.enableDebugLogging(true)
                                PPOcrPostProcessing.runTextLineMerge(this, ocrResult, bitmap.width, bitmap.height, textDirection = config.textDirection)
                            } finally {
                                TextRegionMerger.enableDebugLogging(false)
                            }
                            // 合并后内容过滤
                            val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: merged=${allMerged.size}, 内容丢弃=${contentDiscarded.size}, 输出=${mergedRegions.size}")
                            // 合并区域详情
                            for ((idx, region) in mergedRegions.withIndex()) {
                                val dirLabel = if (region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR) "竖排" else "横排"
                                val r = region.rect
                                val merged = region.texts.joinToString("｜")
                                val angleStr = if (abs(region.angle) > 0.5f) " ∠${String.format("%.1f°", region.angle)}" else ""
                                LogCollector.d(TAG, "PP-OCRv5 MERGED[$idx]: $dirLabel ×${region.texts.size}$angleStr fs=${String.format("%.0f", region.fontSize)} [${r.left},${r.top},${r.right},${r.bottom}] \"$merged\"")
                            }
                            // 内容丢弃详情
                            for ((region, reason) in contentDiscarded) {
                                val text = region.texts.joinToString("")
                                val r = region.rect
                                LogCollector.d(TAG, "PP-OCRv5 CONTENT_DISCARD: $reason [${r.left},${r.top},${r.right},${r.bottom}] \"${text.take(20)}\"")
                            }
                            showPPOcrV5DebugView(bitmap, ocrResult, mergedRegions, debugDet)
                        } else {
                            LogCollector.w(TAG, "PP-OCRv5 Debug Mode: 不支持的语言 ${config.sourceLang}")
                            showToast(getString(R.string.toast_lang_unsupported_v5, config.sourceLang), true)
                        }
                    }
                }
                return
            }

            // 全局缓存检查（使用 256-bit 扩展哈希）
            isForceRefreshActive = forceRefresh
            if (!isForceRefreshActive) {
                val extHashesForCache = currentExtHashes
                    ?: PerceptualHash.computeExtended(pendingFullBitmap ?: bitmap, centerCrop = true)
                val (curLeft, curTop) = cropRect?.let { Pair(it.left.toInt(), it.top.toInt()) } ?: Pair(-1, -1)
                val cached = cacheManager.findCacheExt(
                    extHashesForCache,
                    TranslationCacheManager.MODE_MANGA,
                    bitmap.width, bitmap.height,
                    curLeft, curTop,
                    sessionId
                )
                if (cached != null && cached.resultBitmap != null) {
                    LogCollector.d(TAG, "processMangaScreenshot: 缓存命中, historyId=${cached.historyId}")
                    lastCachedHistoryId = cached.historyId
                    lastCachedPHash = currentPHash
                    lastCacheBubbleRects = cached.bubbleRects
                    // 解析缓存的原文/译文列表供复制模式使用
                    cachedOriginalTextList = TranslationCacheUtils.parseIndexedTextList(cached.originalText)
                    cachedTranslatedTextList = TranslationCacheUtils.parseIndexedTextList(cached.translatedText)
                    // 从缓存数据重建 TranslatedBubble 列表（供原文模式 overlay 渲染）
                    currentShowBubbles = TranslationCacheUtils.rebuildBubblesFromCache(
                        cachedOriginalTextList, cachedTranslatedTextList, lastCacheBubbleRects, config.fontSize, config.bgColor)
                    // 通过共享层渲染裁剪区域的译文 overlay（替代加载预渲染 JPEG）
                    // 仅新数据（有 bubbleRects）走实时渲染；旧数据回退到 imagePath
                    val rendered = if (cached.historyEntity != null && cached.pageCache != null
                        && !cached.bubbleRects.isNullOrBlank()) {
                        cacheManager.renderOverlay(
                            history = cached.historyEntity,
                            pageCache = cached.pageCache,
                            mode = TranslationCacheManager.OverlayMode.TRANSLATED,
                            forFullImage = false,
                            config = TranslationCacheManager.OverlayConfig(config.fontSize, config.autoFontSize, config.textColor, config.bgColor, config.textDirection)
                        )
                    } else null
                    statusOverlay.showImmediate("缓存命中")
                    ballStateManager?.setState(BallStateManager.State.Completed)
                    autoTranslateEngine.lastTranslatedHash = currentPHash
        autoTranslateEngine.lastTranslatedTime = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        currentOriginalBitmap?.recycle()
                        currentOriginalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        // 若实时渲染成功，释放 buildCacheResult 加载的原图（不再需要）
                        if (rendered != null) cached.resultBitmap?.recycle()
                        showResultOverlay(rendered ?: cached.resultBitmap, fromCache = true)
                    }
                    return
                }
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: 强制刷新，跳过缓存")
                forceRefresh = false
            }

            // OcrLock: 保护 ONNX 模型的多线程访问（PP-OCRv5/manga-ocr 等单例引擎）
            if (!com.moe.starflow.manga.OcrLock.tryAcquire()) {
                autoTranslateEngine.scheduleNextDetection(MangaAutoTranslateEngine.DETECT_INTERVAL_MS)
                isProcessing = false
                return
            }
            var ocrTextBlocks: List<TextBlockInfo>
            try {
                // 分批渲染：在检测之前尝试分批流程。
                // BUGFIX (2026-07-06): 之前这里设 Translating，但分批翻译入口还在 OCR 阶段（可能包含多批 OCR），
                // 切到 Translating 让图标闪。现在保持 Processing，由 finalizeIncremental 在所有气泡 OCR 完成后切 Translating。
                ballStateManager?.setState(BallStateManager.State.Processing)
                if (incrementalTranslateFlow(bitmap)) {
                    LogCollector.d(TAG, "processMangaScreenshot: 分批渲染完成，跳过原有流程")
                    return
                }
                LogCollector.d(TAG, "processMangaScreenshot: 分批渲染未触发，走原有流程")
                // 确保选中的模型已初始化
                when (config.detEngine) {
                    DetEngine.MLKIT -> {}
                    DetEngine.RT_DETR_V2 -> engineManager.initRTDetrV2IfNeeded()
                    DetEngine.PP_OCR_V5 -> engineManager.initPPOcrV5IfNeeded()
                    DetEngine.PP_OCR_V6 -> engineManager.initPPOcrV6IfNeeded()
                }
                when (config.ocrEngine) {
                    OcrEngine.MLKit -> {}
                    OcrEngine.MangaOcr -> engineManager.ensureMangaOcrInitialized()
                    OcrEngine.PPOcrV5 -> engineManager.initPPOcrV5IfNeeded()
                    OcrEngine.PPOcrV6 -> engineManager.initPPOcrV6IfNeeded()
                }

                // Step 1: 文字检测 + 识别
                showProgressOverlay("文字识别中...")
                val (ppRecLang, ppHint) = if (config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5) {
                    PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
                } else {
                    // 非 PP-OCRv5 引擎：没有「专用 rec 模型」，不计算也不提示。
                    // ⚠️ 之前用 Pair(getRecLang(sourceLang), null) 导致 ML Kit/V6 也在源语言为 en/ko/ru 时
                    // 误弹出「使用专用识别模型: rec_en」（该提示仅 PP-OCRv5 特有）。
                    Pair(null, null)
                }
                if (ppHint != null) {
                    showToast(ppHint, true)
                }
                // 非默认模型时提示
                if (ppRecLang != null && ppRecLang != PPOcrV5Engine.RecLang.ZH && ppRecLang != PPOcrV5Engine.RecLang.JA) {
                    showToast(getString(R.string.toast_using_dedicated_model, ppRecLang.code), false)
                }
                LogCollector.d(TAG, "Step 1 配置: detEngine=${config.detEngine}, ocrEngine=${config.ocrEngine}, sourceLang=${config.sourceLang}" +
                    if (config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5) ", PP-recModel=${ppRecLang?.code ?: "不支持"}" else "")
                ocrTextBlocks = withContext(Dispatchers.IO) {
                    when (config.detEngine) {
                        DetEngine.MLKIT -> {
                            LogCollector.d(TAG, "使用 ML Kit(检测+识别), lang=${config.sourceLang}")
                            OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                        }
                        DetEngine.RT_DETR_V2 -> {
                            LogCollector.d(TAG, "使用 RT-DETR-V2(检测) + MangaOcr(识别), lang=${config.sourceLang}")
                            DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, this@MangaFloatingService, config.keepTextFree)
                        }
                        DetEngine.PP_OCR_V5 -> {
                            LogCollector.d(TAG, "使用 PP-OCRv5(独立det+cls+rec), lang=${config.sourceLang}, rec=${ppRecLang?.code}")
                            DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                        }
                        DetEngine.PP_OCR_V6 -> {
                            LogCollector.d(TAG, "使用 PP-OCRv6(独立det+cls+rec), lang=${config.sourceLang}")
                            DetectionBridge.detectWithPPOcrV6(bitmap, config.sourceLang, this@MangaFloatingService)
                        }
                    }
                }
                LogCollector.d(TAG, "processMangaScreenshot: Step 1 - OCR done, found ${ocrTextBlocks.size} text blocks")
            } finally {
                com.moe.starflow.manga.OcrLock.release()
            }

            // No text handling — outside OcrLock (early return if empty)
            if (ocrTextBlocks.isEmpty()) {
                LogCollector.d(TAG, "processMangaScreenshot: No text found, returning early")
                if (autoTranslateEngine.isAutoTranslating) {
                    consecutiveEmptyCount++
                    if (consecutiveEmptyCount >= 3) {
                        LogCollector.d(TAG, "processMangaScreenshot: ${consecutiveEmptyCount} consecutive empty OCR — possible protected area")
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.no_text_found_protected), false)
                        }
                        consecutiveEmptyCount = 0  // 重置，避免反复弹
                    }
                } else {
                    showToast(getString(R.string.no_text_found), true)
                }
                return
            }
            // 有文字时重置连续空计数
            consecutiveEmptyCount = 0

            // Step 2: 气泡合并（自动/手动共用）
            // RT-DETR-V2 检测器直接输出气泡级结果，跳过后合并
            // PP-OCRv5 已在 detectWithPPOcrV5 内部用 TextLineMerger 合并，跳过后合并
            // MLKit 需要 BubbleDetector 把行级结果合并成气泡
            val needsPostMerge = config.detEngine == DetEngine.MLKIT
            val allBubbles = if (needsPostMerge) {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - BubbleDetector 后合并")
                BubbleDetector.detectBubbles(ocrTextBlocks, config)
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - 已前合并，跳过后合并")
                ocrTextBlocks.filter { it.boundingBox != null }.map { block ->
                    val rect = block.boundingBox!!
                    val isVertical = block.inferredVertical()  // 真实边长推断（抗旋转），缺失才 AABB
                    if (kotlin.math.abs(block.angle) > 0.5f) {
                        LogCollector.d(TAG, "BubbleRegion: angle=${block.angle}, cx=${block.centerX}, cy=${block.centerY}, text='${block.text.take(15)}'")
                    }
                    BubbleRegion(
                        rect = rect,
                        texts = listOf(block.text),
                        fontSize = if (isVertical) rect.width().toFloat() else rect.height().toFloat(),
                        direction = if (isVertical) config.textDirection else TextDirection.HORIZONTAL,
                        angle = block.angle,
                        centerX = block.centerX,
                        centerY = block.centerY
                    )
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 2 - Detected ${allBubbles.size} bubbles")

            // Step 3: 翻译（走文本缓存匹配，自动/手动均适用）
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Translate ${allBubbles.size} bubbles")
            // BUGFIX (2026-07-06): 调翻译函数之前立刻切 Translating 图标（之前遗漏，自动模式从 Idle 直接到翻译完成）。
            ballStateManager?.setState(BallStateManager.State.Translating)
            val newTranslatedBubbles = incrementalTranslateBubbles(allBubbles) { partialBubbles ->
                if (partialBubbles.isNotEmpty()) {
                    launchPartialRender { renderAndShowMergedOverlay(bitmap, partialBubbles, saveCache = false, showCopyButton = false) }
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - done, got ${newTranslatedBubbles.size} results")

            // Step 4: 合并已缓存翻译 + 新翻译，渲染 overlay
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - Rendering merged overlay")
            renderAndShowMergedOverlay(bitmap, newTranslatedBubbles)
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - DONE")
            statusOverlay.showImmediate("翻译完成")
            ballStateManager?.setState(BallStateManager.State.Completed)

            // 更新区域缓存和 pHash
            autoTranslateEngine.lastTranslatedHash = currentPHash
        autoTranslateEngine.lastTranslatedTime = System.currentTimeMillis()
            if (autoTranslateEngine.isAutoTranslating) {
                // 翻译完成后用短间隔快速重新检测，响应翻页
                autoTranslateEngine.scheduleNextDetection(MangaAutoTranslateEngine.DETECT_INTERVAL_MS)
            }

        } finally {
            // 等所有流式渲染协程完成，避免它们读取已 recycle 的截图 bitmap（在 Default 上 join，避免阻塞 Main 造成死锁）
            withContext(Dispatchers.Default) {
                synchronized(streamingRenderJobs) { streamingRenderJobs.toList() }.forEach { it.join() }
            }
            streamingRenderJobs.clear()
            bitmap.recycle()
            // 如果有独立的全屏 bitmap（不同于 OCR bitmap），一并释放
            if (pendingFullBitmap != null && pendingFullBitmap !== bitmap) {
                pendingFullBitmap!!.recycle()
            }
            pendingFullBitmap = null
            // 自动翻译模式：确保 autoTranslateEngine.lastTranslatedHash 被更新，避免异常后状态机卡住
            if (autoTranslateEngine.isAutoTranslating && currentPHash != 0L) {
                autoTranslateEngine.lastTranslatedHash = currentPHash
        autoTranslateEngine.lastTranslatedTime = System.currentTimeMillis()
            }
            LogCollector.d(TAG, "processMangaScreenshot: FINALLY - dismissing progress, isProcessing=false")
            isProcessing = false
            dismissProgressOverlay()
        }
    }

    /**
     * 增量翻译：基于合并后的气泡，先查文本缓存，再翻译未命中的。
     * 翻译完成后将结果加入 translatedRegions 缓存。
     */
    private suspend fun incrementalTranslateBubbles(
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false,
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {}
    ): List<TranslatedBubble> {
        if (bubbles.isEmpty()) return emptyList()
        // 用户已停止翻译：OCR 等耗时段结束后立即终止，避免继续走翻译/渲染残留进度条
        if (translationCancelled) throw TranslationCancelledException()

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${bubbles.size} bubbles, forceContext=$forceContext, cacheSize=${regionCache.size()}")

        // 文本级缓存：先精确匹配（快速路径），再模糊匹配（编辑距离）
        val fromCache = mutableListOf<TranslatedBubble>()
        val needTranslation = mutableListOf<BubbleRegion>()

        for (bubble in bubbles) {
            val combinedText = bubble.texts.map { TranslateUtils.cleanOcrText(it) }.filter { it.isNotBlank() }.joinToString("")
            if (combinedText.isBlank()) continue

            // 精确匹配
            val exactMatch = regionCache.findExact(combinedText, combinedText.hashCode())
            if (exactMatch != null) {
                fromCache.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = exactMatch.translation,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction,
                    angle = bubble.angle,
                    centerX = bubble.centerX,
                    centerY = bubble.centerY,
                    isInMemoryCache = true
                ))
                // 更新时间
                regionCache.remove(exactMatch)
                regionCache.add(exactMatch.copy(
                    translatedAt = System.currentTimeMillis()
                ))
                LogCollector.d(TAG, "Text cache hit (exact): '${combinedText.take(20)}' → '${exactMatch.translation.take(20)}'")
            } else {
                // 模糊匹配：编辑距离自适应阈值
                val fuzzyMatch = regionCache.findFuzzyMatch(combinedText)
                if (fuzzyMatch != null) {
                    fromCache.add(TranslatedBubble(
                        rect = bubble.rect,
                        originalText = combinedText,
                        translatedText = fuzzyMatch.translation,
                        backgroundColor = Color.TRANSPARENT,
                        fontSize = bubble.fontSize,
                        direction = bubble.direction,
                        angle = bubble.angle,
                        centerX = bubble.centerX,
                        centerY = bubble.centerY,
                        fromCache = true
                    ))
                    regionCache.remove(fuzzyMatch)
                    regionCache.add(fuzzyMatch.copy(
                        translatedAt = System.currentTimeMillis()
                    ))
                    LogCollector.d(TAG, "Text cache hit (fuzzy): '${combinedText.take(20)}' ~ '${fuzzyMatch.ocrText.take(20)}' → '${fuzzyMatch.translation.take(20)}'")
                } else {
                    needTranslation.add(bubble)
                }
            }
        }

        if (needTranslation.isEmpty()) {
            LogCollector.d(TAG, "incrementalTranslateBubbles: all ${bubbles.size} from text cache")
            return fromCache
        }

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${fromCache.size} cached + ${needTranslation.size} need API")
        if (needTranslation.isNotEmpty()) {
            // 用户已停止翻译：不重新显示「正在翻译」进度（避免取消后进度条残留/跳动）
            if (translationCancelled) throw TranslationCancelledException()
            showProgressOverlay(getString(R.string.manga_translating))
        }

        // 用 translateBubbles 走和手动翻译完全相同的路径
        val results = translateBubbles(needTranslation, forceContext, onPartialBubbles)

        // 缓存翻译结果
        for (result in results) {
            val textHash = result.originalText.hashCode()
            regionCache.add(RegionCacheManager.TranslatedRegion(
                ocrText = result.originalText,
                ocrTextHash = textHash,
                translation = result.translatedText
            ))
            LogCollector.d(TAG, "Cached bubble: '${result.originalText.take(20)}' → '${result.translatedText.take(20)}'")
        }
        return fromCache + results
    }


    /**
     * 淘汰过旧的缓存区域。
     */

    /**
     * 合并已缓存翻译 + 新翻译，渲染并显示 overlay。
     * 跳开与新翻译重叠的缓存区域，避免重复覆盖。
     */
    /**
     * 流式渲染：渲染「当前已完成的」气泡并更新 overlay。
     * 窗口已在 → showResultOverlay 原地换图（不闪烁）；窗口未在 → 首次显示。
     */
    private suspend fun renderStreamingOverlay(original: Bitmap, newBubbles: List<TranslatedBubble>) {
        if (newBubbles.isEmpty()) return
        val resultBitmap = withContext(Dispatchers.Default) {
            OverlayRenderer.renderOverlay(
                original = original,
                regions = newBubbles,
                fontSize = config.fontSize,
                autoFit = config.autoFontSize,
                textColor = config.textColor,
                bgColor = config.bgColor,
                verticalDirection = config.textDirection,
                fontTypeface = OverlayRenderer.loadResultTypeface(this@MangaFloatingService, prefs),
                showCacheMarker = prefs.getBoolean(com.moe.starflow.data.TranslationCacheManager.KEY_CACHE_MARKER, false)
            )
        }
        withContext(Dispatchers.Main) {
            showResultOverlay(resultBitmap, showCopyButton = false)
        }
    }

    /** 流式局部渲染：跟踪 Job，processMangaScreenshot 收尾 join，避免读取已 recycle 的截图 bitmap */
    private fun launchPartialRender(block: suspend () -> Unit) {
        val job = lifecycleScope.launch { block() }
        streamingRenderJobs.add(job)
        job.invokeOnCompletion { streamingRenderJobs.remove(job) }
    }

    private suspend fun renderAndShowMergedOverlay(
        original: Bitmap,
        newBubbles: List<TranslatedBubble>,
        saveCache: Boolean = true,
        isRetranslate: Boolean = false,
        historyIdToDelete: Long = 0,
        originalBitmap: Bitmap? = null,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = 0,
        cropBottom: Int = 0,
        showCopyButton: Boolean = true  // 分批中间结果不显示复制按钮
    ) {
        if (translationCancelled) {
            LogCollector.d(TAG, "renderAndShowMergedOverlay: 翻译已取消，跳过渲染")
            return
        }
        if (newBubbles.isEmpty()) {
            LogCollector.d(TAG, "renderAndShowMergedOverlay: no content to render")
            return
        }

        // 更新进度
        if (autoTranslateEngine.isAutoTranslating) {
            showProgressOverlay(getString(R.string.manga_translating))
        }

        // 渲染
        val resultBitmap = withContext(Dispatchers.Default) {
            OverlayRenderer.renderOverlay(
                original = original,
                regions = newBubbles,
                fontSize = config.fontSize,
                autoFit = config.autoFontSize,
                textColor = config.textColor,
                bgColor = config.bgColor,
                verticalDirection = config.textDirection,
                fontTypeface = OverlayRenderer.loadResultTypeface(this@MangaFloatingService, prefs),
                showCacheMarker = prefs.getBoolean(com.moe.starflow.data.TranslationCacheManager.KEY_CACHE_MARKER, false)
            )
        }

        // 显示
        withContext(Dispatchers.Main) {
            // 有任何气泡渲染上屏（首批/流式）即标记部分结果已显示 → 之后再单击悬浮球/浮层走确认弹窗
            partialRenderShown = true
            showResultOverlay(resultBitmap, showCopyButton = showCopyButton)
            // 必须在 showResultOverlay 之后赋值，因为 dismissResultOverlay 会清空 currentShowBubbles
            currentShowBubbles = newBubbles
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = original.copy(Bitmap.Config.ARGB_8888, false)
            currentOverlayBitmapW = resultBitmap.width
            currentOverlayBitmapH = resultBitmap.height
        }

        // 保存到缓存和历史（分批翻译时由 finalizeIncremental 统一保存，避免保存中间结果）
        if (saveCache) {
            try {
                val translatorName = TranslateUtils.buildTranslatorDisplayName(translatorText, config.detEngine, config.ocrEngine, prefs.getSharedPreferences())
                val ocrTexts = newBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
                val transTexts = newBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
                LogCollector.d(TAG, "保存缓存: ${newBubbles.size} 个气泡")
                // 使用实际裁剪坐标（如果有 cropRect 或重翻）或全屏尺寸
                val fullWidth = pendingFullBitmap?.width ?: original.width
                val fullHeight = pendingFullBitmap?.height ?: original.height
                val saveOrigBmp = if (isRetranslate) originalBitmap else pendingFullBitmap
                val entryCropLeft: Int
                val entryCropTop: Int
                val entryCropRight: Int
                val entryCropBottom: Int
                if (isRetranslate) {
                    entryCropLeft = cropLeft
                    entryCropTop = cropTop
                    entryCropRight = cropRight
                    entryCropBottom = cropBottom
                } else if (cropRect != null) {
                    entryCropLeft = cropRect!!.left.toInt()
                    entryCropTop = cropRect!!.top.toInt()
                    entryCropRight = cropRect!!.right.toInt()
                    entryCropBottom = cropRect!!.bottom.toInt()
                } else {
                    entryCropLeft = 0
                    entryCropTop = 0
                    entryCropRight = fullWidth
                    entryCropBottom = fullHeight
                }
                val entry = CacheEntry(
                    type = TranslationCacheManager.MODE_MANGA,
                    sourceText = ocrTexts.ifEmpty { null },
                    translatedText = transTexts.ifEmpty { null },
                    resultBitmap = resultBitmap.copy(resultBitmap.config ?: Bitmap.Config.ARGB_8888, false),
                    sourceLang = config.sourceLang,
                    targetLang = config.targetLang,
                    translatorName = translatorName,
                    pHash = (currentExtHashes?.getOrElse(0) { currentPHash }) ?: currentPHash,
                    pHash2 = currentExtHashes?.getOrElse(1) { 0L } ?: 0L,
                    pHash3 = currentExtHashes?.getOrElse(2) { 0L } ?: 0L,
                    pHash4 = currentExtHashes?.getOrElse(3) { 0L } ?: 0L,
                    sessionId = sessionId,
                    lastSessionId = sessionId,
                    isRetranslated = isRetranslate,
                    cropLeft = entryCropLeft,
                    cropTop = entryCropTop,
                    cropRight = entryCropRight,
                    cropBottom = entryCropBottom,
                    bubbleRects = if (newBubbles.isNotEmpty()) {
                        TranslationCacheUtils.serializeBubbleRects(newBubbles)
                    } else null
                )
                if (isRetranslate && historyIdToDelete > 0) {
                    cacheManager.refreshCache(historyIdToDelete, entry, originalBitmap = saveOrigBmp)
                    LogCollector.d(TAG, "重翻：替换旧缓存, historyId=$historyIdToDelete")
                } else if (isForceRefreshActive) {
                    val refreshId = if (currentPHash == lastCachedPHash) lastCachedHistoryId else 0L
                    cacheManager.refreshCache(refreshId, entry, originalBitmap = saveOrigBmp)
                    LogCollector.d(TAG, "强制刷新：替换旧缓存和历史, historyId=$refreshId")
                    lastCachedHistoryId = 0
                    lastCachedPHash = 0
                    isForceRefreshActive = false
                } else {
                    cacheManager.saveToCache(entry, originalBitmap = saveOrigBmp)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "保存缓存失败", e)
            }
        }
    }
    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false,
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {}
    ): List<TranslatedBubble> {
        if (translatorText == null) throw RuntimeException("Translation API not initialized")
        return TranslateUtils.translateBubbles(
            translatorText!!, bubbles, config.sourceLang, config.targetLang, prefs, contextHistory, forceContext,
            onPhase = { phase ->
                when (phase) {
                    "prefill" -> showProgressOverlay("读取原文中…")
                    "generate" -> showProgressOverlay(getString(R.string.manga_translating))
                }
            },
            onPartialBubbles = onPartialBubbles,
            isCancelled = { translationCancelled }  // 用户停止翻译 → waitForResult 立即解除等待，不再卡 35s
        )
    }

    // ---------- Result overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(bitmap: Bitmap, fromCache: Boolean = false, showCopyButton: Boolean = true) {
        if (fromCache) {
            if (isResultShowing) dismissResultOverlay()
            showCacheOverlay(bitmap)
            return
        }

        if (isResultShowing && resultOverlayView.isAttachedToWindow) {
            // 原地更新：窗口已在，直接换图，避免 dismiss+重新 add 造成的闪烁
            val old = (resultOverlayImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            resultOverlayImage.setImageBitmap(bitmap)
            currentOverlayBitmapW = bitmap.width
            currentOverlayBitmapH = bitmap.height
            if (old != null && old !== bitmap) old.recycle()
            return
        }

        if (isResultShowing) {
            dismissResultOverlay()
        }

        currentOverlayBitmapW = bitmap.width
        currentOverlayBitmapH = bitmap.height
        resultOverlayImage.setImageBitmap(bitmap)
        // touch listener 放在 ImageView 上（FrameLayout 最底层子 View），
        // 按钮作为更上层子 View 先收到触摸，不会被打断
        resultOverlayImage.setOnTouchListener { _, event ->
            if (isCopyMode) {
                false  // 复制模式穿透
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (isProcessing && !translationCancelled) {
                        // 翻译进行中点击 overlay → 询问是否停止
                        showStopTranslationDialog()
                    } else {
                        dismissResultOverlay()
                    }
                }
                true
            }
        }
        // 确保 FrameLayout 本身不拦截触摸（使用默认行为）

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
            resultOverlayImage.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        } else {
            // 全屏模式：获取屏幕真实像素尺寸，overlay 精确覆盖全屏
            val screenSize = getScreenSize()
            val screenW = screenSize.width
            val screenH = screenSize.height
            LogCollector.d(TAG, "showResultOverlay: bitmap=${bitmap.width}x${bitmap.height}, screen=${screenW}x${screenH}")
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = screenW
                height = screenH
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
            resultOverlayImage.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        }
        isResultShowing = true

        bringFloatingBallToFront()
        if (showCopyButton) {
            showCopyButtons()
        }
    }

    private fun dismissResultOverlay() {
        if (cacheOverlayContainer != null) {
            dismissCacheOverlay()
            return
        }
        if (isCopyMode) {
            isCopyMode = false
            copyOriginalMode = false  // 退出复制模式时重置为译文，避免下次进入状态错乱
            removeCopyClickLayer()
        }
        removeCopyButtons()
        debugPanel.dismiss()
        if (isResultShowing) {
            try {
                // 先 removeView 再清 drawable，避免 FrameLayout 半透明黑色背景在清 bitmap 后、removeView 前那一帧暴露给用户（曾短暂闪烁黑色图层）
                if (resultOverlayView.isAttachedToWindow) {
                    windowManager.removeView(resultOverlayView)
                }
                // 先清除引用再回收，避免 Choreographer 待处理帧使用已回收的 bitmap
                val oldBitmap = (resultOverlayImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                resultOverlayImage.setImageBitmap(null)
                oldBitmap?.recycle()
                resultOverlayView.setOnTouchListener(null)
                resultOverlayImage.setOnTouchListener(null)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false
            currentShowBubbles = emptyList()
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = null

            // 重置自动翻译状态，但不清楚文本缓存（文本缓存跨页面有效）
            if (autoTranslateEngine.isAutoTranslating) {
                autoTranslateEngine.resetToIdle()
            }
        }
    }

    /**
     * 显示缓存结果 overlay — 带"缓存"标签和刷新按钮
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showCacheOverlay(bitmap: Bitmap) {
        dismissProgressOverlay()

        currentOverlayBitmapW = bitmap.width
        currentOverlayBitmapH = bitmap.height

        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height

        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }

        // 结果图片
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        cacheOverlayImage = imageView
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // "⚡ 缓存" 标签（左上角）
        val cacheTag = android.widget.TextView(this).apply {
            text = "⚡ 缓存"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(180, 255, 152, 0))
            setPadding(24, 12, 24, 12)
        }
        container.addView(cacheTag, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(24, 24, 0, 0)
        })

        // 刷新按钮（右上角）
        val refreshBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                dismissCacheOverlay()
                forceRefresh = true
                autoTranslateEngine.lastTranslatedHash = 0L
        autoTranslateEngine.lastTranslatedTime = 0L
                regionCache.clear()  // 清空内存缓存，避免 ⚡ 标志
                triggerTranslation()
            }
        }
        container.addView(refreshBtn, android.widget.FrameLayout.LayoutParams(
            120, 120
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, 24, 24, 0)
        })

        // 点击其他区域关闭（复制模式下不拦截，让气泡窗口和按钮处理）
        container.setOnTouchListener { _, event ->
            val refreshRight = screenW - 24
            val refreshLeft = refreshRight - 120
            val refreshTop = 24
            val refreshBottom = refreshTop + 120
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()
            if (touchX in refreshLeft..refreshRight && touchY in refreshTop..refreshBottom) {
                false  // 让刷新按钮处理
            } else if (isCopyMode) {
                false  // 复制模式下不拦截触摸
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    dismissCacheOverlay()
                }
                true
            }
        }

        val params = if (cropRect != null) {
            val crop = cropRect!!
            WindowManager.LayoutParams().apply {
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
        } else {
            WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = screenW
                height = screenH
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
        }

        windowManager.addView(container, params)
        cacheOverlayContainer = container
        isResultShowing = true

        bringFloatingBallToFront()
        showCopyButtons()
    }

    /**
     * 关闭缓存 overlay
     */
    private fun dismissCacheOverlay() {
        cacheOverlayContainer?.let { container ->
            try {
                // 回收 bitmap
                val imageView = container.getChildAt(0) as? ImageView
                val bitmap = imageView?.drawable?.let { drawable ->
                    if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap else null
                }
                windowManager.removeView(container)
                imageView?.setImageDrawable(null)
                // 延迟回收，等 View 绘制完成
                container.post { bitmap?.recycle() }
            } catch (e: Exception) {
                LogCollector.e(TAG, "dismissCacheOverlay: 错误", e)
            }
            cacheOverlayContainer = null
            cacheOverlayImage = null
            isResultShowing = false
            renderToggleJob?.cancel()  // 取消正在进行的渲染，避免 recycled bitmap 被使用
            currentShowBubbles = emptyList()
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = null
            lastCacheBubbleRects = null
            cachedOriginalTextList = emptyList()
            cachedTranslatedTextList = emptyList()

            // 清理复制模式
            if (isCopyMode) {
                isCopyMode = false
                copyOriginalMode = false  // 退出时重置
                removeCopyClickLayer()
            }
            removeCopyButtons()

            // 重置自动翻译：清除区域缓存，立刻恢复检测
            if (autoTranslateEngine.isAutoTranslating) {
                regionCache.clear()
                autoTranslateEngine.resetToIdle()
            }
        }
    }

    // ---------- 复制模式 ----------

    private fun parseBubbleRectsJson(json: String?): List<android.graphics.Rect> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val result = mutableListOf<android.graphics.Rect>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(android.graphics.Rect(
                    obj.getInt("l"), obj.getInt("t"),
                    obj.getInt("r"), obj.getInt("b")
                ))
            }
            result
        } catch (e: Exception) {
            LogCollector.e(TAG, "parseBubbleRectsJson failed", e)
            emptyList()
        }
    }

    // parseBubbleEntriesJson / parseIndexedTextList / rebuildBubblesFromCache / BubbleJsonEntry
    // 已搬迁至 TranslationCacheManager，调用时加 TranslationCacheManager. 前缀

    // ---------- 按钮工具方法 ----------

    /** 分段切换控件的两个 TextView，用于更新激活状态 */
    private var toggleSegOriginal: android.widget.TextView? = null
    private var toggleSegTranslation: android.widget.TextView? = null

    /**
     * 创建操作按钮（全部复制、退出等执行动作的按钮）
     */
    private fun createCopyActionBtn(text: String, textSize: Float = 12f): android.widget.TextView {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 30, 30, 30))
            cornerRadius = dpToPx(6).toFloat()
            setStroke(dpToPx(1), Color.argb(80, 255, 255, 255))
        }
        return android.widget.TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = bg
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                }
                false
            }
        }
    }

    /**
     * 创建分段切换控件 [ 原文 | 译文 ]
     * 激活段白底带圆角，与外层容器边缘对齐
     */
    private fun createSegmentedToggle(): android.widget.LinearLayout {
        val r = dpToPx(6).toFloat()
        val padH = dpToPx(12)
        val padV = dpToPx(6)

        // 左段激活背景：左侧圆角
        val activeBgLeft = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        // 右段激活背景：右侧圆角
        val activeBgRight = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }

        toggleSegOriginal = android.widget.TextView(this).apply {
            text = getString(R.string.copy_original)
            textSize = 11f
            setTextColor(if (copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = if (copyOriginalMode) activeBgLeft else null
            setOnClickListener {
                if (!copyOriginalMode) {
                    copyOriginalMode = true
                    updateToggleSegments()
                }
            }
        }

        toggleSegTranslation = android.widget.TextView(this).apply {
            text = getString(R.string.copy_translation)
            textSize = 11f
            setTextColor(if (!copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = if (!copyOriginalMode) activeBgRight else null
            setOnClickListener {
                if (copyOriginalMode) {
                    copyOriginalMode = false
                    updateToggleSegments()
                }
            }
        }

        val outerBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 30, 30, 30))
            cornerRadius = r
            setStroke(dpToPx(1), Color.argb(80, 255, 255, 255))
        }

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = outerBg
            addView(toggleSegOriginal)
            addView(toggleSegTranslation)
        }
    }

    /** 更新分段切换的激活状态 + 切换 overlay 显示原文/译文 */
    private fun updateToggleSegments() {
        val r = dpToPx(6).toFloat()
        val activeBgLeft = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        val activeBgRight = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        toggleSegOriginal?.apply {
            background = if (copyOriginalMode) activeBgLeft else null
            setTextColor(if (copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
        }
        toggleSegTranslation?.apply {
            background = if (!copyOriginalMode) activeBgRight else null
            setTextColor(if (!copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
        }
        // 切换 overlay 图片：原文/译文都实时渲染（不再依赖预渲染的 currentTranslatedOverlay）
        renderToggleJob?.cancel()
        renderToggleJob = lifecycleScope.launch {
            val original = currentOriginalBitmap ?: return@launch
            val bubbles = currentShowBubbles
            if (bubbles.isEmpty()) return@launch
            val overlay = withContext(Dispatchers.Default) {
                // 防止竞态：用户可能在 suspension point 期间关闭 overlay 导致 bitmap 被回收
                if (original.isRecycled) return@withContext null
                OverlayRenderer.renderOverlay(
                    original = original,
                    regions = bubbles,
                    fontSize = config.fontSize,
                    autoFit = config.autoFontSize,
                    textColor = config.textColor,
                    bgColor = config.bgColor,
                    useOriginalText = copyOriginalMode,
                    verticalDirection = config.textDirection,
                    fontTypeface = OverlayRenderer.loadResultTypeface(this@MangaFloatingService, prefs),
                    showCacheMarker = prefs.getBoolean(com.moe.starflow.data.TranslationCacheManager.KEY_CACHE_MARKER, false)
                )
            }
            withContext(Dispatchers.Main) {
                if (!isResultShowing || overlay == null) return@withContext
                val target = cacheOverlayImage ?: resultOverlayImage
                // 先回收旧 bitmap，避免全屏 overlay 每次切换都泄漏一张 ~13MB（OOM 风险）
                val oldBitmap = (target.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                target.setImageBitmap(overlay)
                if (oldBitmap !== overlay) oldBitmap?.recycle()
            }
        }
    }

    private fun showCopyButtons() {
        if (copyButtonsContainer != null) return
        buildCopyButtonsLayout()
    }

    /**
     * 重建按钮面板。独立 WindowManager 窗口，始终在屏幕右下角，
     * enterCopyMode 保证在气泡窗口之后添加，所以 z 层在气泡之上。
     */
    private fun buildCopyButtonsLayout() {
        val oldContainer = copyButtonsContainer
        copyButtonsContainer = null
        toggleSegOriginal = null
        toggleSegTranslation = null

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        if (isCopyMode) {
            val gap = dpToPx(4)
            container.addView(createSegmentedToggle(), android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                bottomMargin = gap
            })

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            row.addView(createCopyActionBtn(getString(R.string.copy_all), 11f).apply {
                setOnClickListener { copyAllBubbles() }
            })
            row.addView(createCopyActionBtn(getString(R.string.copy_exit), 12f).apply {
                setOnClickListener { toggleCopyMode() }
            })
            container.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            })
        } else {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            row.addView(createCopyActionBtn(getString(R.string.copy_text), 12f).apply {
                setOnClickListener { toggleCopyMode() }
            })
            container.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            })
        }

        copyButtonsContainer = container
        // 独立窗口，屏幕右下角固定位置，不受 crop 影响
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.END
            x = dpToPx(8)
            y = dpToPx(8)
        }
        windowManager.addView(container, params)

        oldContainer?.let {
            try { if (it.isAttachedToWindow) windowManager.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun removeCopyButtons() {
        toggleSegOriginal = null
        toggleSegTranslation = null
        if (copyButtonsContainer != null) {
            try {
                if (copyButtonsContainer!!.isAttachedToWindow) windowManager.removeView(copyButtonsContainer)
            } catch (_: Exception) {}
            copyButtonsContainer = null
        }
    }

    private fun toggleCopyMode() {
        isCopyMode = !isCopyMode
        if (isCopyMode) {
            enterCopyMode()
        } else {
            exitCopyMode()
        }
    }

    private fun enterCopyMode() {
        createCopyClickLayer()
        buildCopyButtonsLayout()
    }

    private fun exitCopyMode() {
        copyOriginalMode = false  // 退出时重置为译文模式
        removeCopyClickLayer()
        buildCopyButtonsLayout()
    }

    /**
     * 创建复制模式的可点击气泡覆盖层。
     *
     * 使用与 showResultOverlay / showCacheOverlay 完全相同的坐标映射规则：
     * - 全屏模式：bitmap == screen → 1:1 映射
     * - 框选模式：overlay 窗口在 (cropOffset + cropRect.left, cropOffset + cropRect.top)，
     *   气泡 rect 在裁剪后 bitmap 坐标系中 → 需加上窗口偏移量映射到屏幕坐标
     */
    private fun createCopyClickLayer() {
        removeCopyClickLayer()

        val container = android.widget.FrameLayout(this)
        copyBubbleViews.clear()

        // 获取气泡 rect 列表：优先用 currentShowBubbles（新翻译），否则用缓存数据
        val bubbles: List<android.graphics.Rect> = if (currentShowBubbles.isNotEmpty()) {
            currentShowBubbles.map { it.rect }
        } else {
            parseBubbleRectsJson(lastCacheBubbleRects)
        }

        if (bubbles.isEmpty()) {
            LogCollector.d(TAG, "createCopyClickLayer: 无气泡数据，仅支持复制全部")
            return
        }

        // 计算坐标映射参数：与 showResultOverlay / showCacheOverlay 一致
        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height
        val bitmapW = if (currentOverlayBitmapW > 0) currentOverlayBitmapW else screenW
        val bitmapH = if (currentOverlayBitmapH > 0) currentOverlayBitmapH else screenH

        // overlay 窗口的屏幕坐标偏移和尺寸
        val overlayScreenX: Int
        val overlayScreenY: Int
        val overlayWidth: Int
        val overlayHeight: Int

        if (cropRect != null) {
            val crop = cropRect!!
            val offset = cropView.absolutePointOffset
            overlayScreenX = offset.x + crop.left.toInt()
            overlayScreenY = offset.y + crop.top.toInt()
            overlayWidth = crop.width().toInt()
            overlayHeight = crop.height().toInt()
        } else {
            overlayScreenX = 0
            overlayScreenY = 0
            overlayWidth = screenW
            overlayHeight = screenH
        }

        // bitmap → overlay 缩放比例
        val scaleX = if (bitmapW > 0) overlayWidth.toFloat() / bitmapW else 1f
        val scaleY = if (bitmapH > 0) overlayHeight.toFloat() / bitmapH else 1f

        LogCollector.d(TAG, "createCopyClickLayer: ${bubbles.size} bubbles, " +
            "bitmap=${bitmapW}x${bitmapH}, overlay=${overlayWidth}x${overlayHeight}@($overlayScreenX,$overlayScreenY), " +
            "cropRect=${cropRect != null}, scale=(${scaleX},${scaleY})")

        // 每个气泡一个独立小窗口，精确覆盖气泡区域，不阻挡按钮和悬浮球
        for ((idx, rect) in bubbles.withIndex()) {
            // bitmap 坐标 → 屏幕坐标
            val screenLeft = (rect.left * scaleX + overlayScreenX).toInt()
            val screenTop = (rect.top * scaleY + overlayScreenY).toInt()
            val bubbleW = (rect.width() * scaleX).toInt()
            val bubbleH = (rect.height() * scaleY).toInt()

            val overlay = View(this).apply {
                setBackgroundColor(Color.argb(40, 100, 200, 255))
                setOnClickListener {
                    copyBubbleText(idx)
                    // 高亮反馈 200ms
                    setBackgroundColor(Color.argb(120, 100, 200, 255))
                    postDelayed({
                        setBackgroundColor(Color.argb(40, 100, 200, 255))
                    }, 200)
                }
            }
            copyBubbleViews.add(overlay)

            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = bubbleW
                height = bubbleH
                gravity = Gravity.START or Gravity.TOP
                x = screenLeft
                y = screenTop
            }
            windowManager.addView(overlay, params)
        }
    }

    private fun removeCopyClickLayer() {
        for (v in copyBubbleViews) {
            try {
                if (v.isAttachedToWindow) {
                    windowManager.removeView(v)
                }
            } catch (_: Exception) {}
        }
        copyBubbleViews.clear()
        // 兼容旧版单容器模式
        if (copyClickLayer != null) {
            try {
                if (copyClickLayer!!.isAttachedToWindow) {
                    windowManager.removeView(copyClickLayer)
                }
            } catch (_: Exception) {}
            copyClickLayer = null
        }
    }

    private fun copyBubbleText(idx: Int) {
        val text = if (currentShowBubbles.isNotEmpty()) {
            val bubble = currentShowBubbles.getOrNull(idx) ?: return
            if (copyOriginalMode) bubble.originalText else bubble.translatedText
        } else {
            // 缓存命中：从解析的文本列表中获取
            val list = if (copyOriginalMode) cachedOriginalTextList else cachedTranslatedTextList
            list.getOrNull(idx) ?: return
        }
        copyToClipboard(text)
    }

    private fun copyAllBubbles() {
        if (currentShowBubbles.isNotEmpty()) {
            val regions = currentShowBubbles
            val text = regions.mapIndexed { idx, r ->
                val content = if (copyOriginalMode) r.originalText else r.translatedText
                "[${idx + 1}] $content"
            }.joinToString("\n")
            copyToClipboard(text)
        } else {
            // 缓存命中：使用解析的文本列表，保留原有 [N] 格式
            val list = if (copyOriginalMode) cachedOriginalTextList else cachedTranslatedTextList
            if (list.isEmpty()) return
            val text = list.mapIndexed { idx, content ->
                "[${idx + 1}] $content"
            }.joinToString("\n")
            copyToClipboard(text)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied_text", text))
        // 用 statusOverlay 显示反馈（Service 上下文下系统 Toast 可能被 overlay 遮挡）
        statusOverlay.showImmediate(getString(R.string.text_copied))
        LogCollector.d(TAG, "copyToClipboard: ${text.take(50)}...")
    }

    /**
     * RT-DETR-V2 调试模式：渲染检测结果到图片上并显示
     */

    private fun showRTDetrV2DebugView(bitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        val debugBitmap = MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap, debugResult)
        debugPanel.showDebugOverlay(
            debugBitmap, cropRect, getScreenSize(), initialCollapsed = false, errorTag = "RT-DETR-V2 Debug"
        ) { container ->
            val infoPanel = MangaDebugOverlays.createInfoPanelView(this, MangaDebugOverlays.buildRTDetrInfoLines(debugResult, config.keepTextFree))
            container.addView(infoPanel, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM })
            val toggle = MangaDebugOverlays.createToggleButton(this, onToggle = { debugPanel.toggleCollapse() })
            container.addView(toggle, android.widget.FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = dpToPx(16)
                bottomMargin = dpToPx(16)
            })
            debugPanel.setToggleButton(toggle)
            infoPanel
        }
    }

    private fun showProgressOverlay(text: String = getString(R.string.manga_translating)) {
        LogCollector.d(TAG, "showProgressOverlay called: $text")
        statusOverlay.showImmediate(text, autoDismiss = false)
    }

    /**
     * 翻译进行中点击 overlay 时弹窗：询问是否停止。停止 → 终止翻译且不保存结果。
     */
    private fun showStopTranslationDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("翻译未完成")
            .setMessage("当前翻译尚未完成，是否停止？停止后将不保存本次翻译结果。")
            .setPositiveButton("停止") { _, _ -> stopTranslationNow() }
            .setNegativeButton("继续", null)
            .create()
        // ⚠️ Service 无 Activity token：必须先把对话框窗口类型设为 OVERLAY，否则 show() 抛 BadTokenException
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun stopTranslationNow() = cancelInFlightTranslation()

    /**
     * 取消在途翻译并丢弃部分结果（不保存到数据库）。
     * showMessage=false 用于强制关闭自动翻译时（由「自动翻译已停止」提示代替）。
     */
    private fun cancelInFlightTranslation(showMessage: Boolean = true) {
        translationCancelled = true
        partialRenderShown = false  // 已停止：再点悬浮球不再弹确认，直接幂等终止
        translatorText?.cancelTranslation()
        dismissProgressOverlay()
        dismissResultOverlay()
        ballStateManager?.setState(BallStateManager.State.Idle)
        if (showMessage) statusOverlay.showImmediate("已停止翻译", autoDismiss = true)
    }

    private fun dismissProgressOverlay() {
        statusOverlay.dismiss()
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

    /** 将悬浮球重新添加到窗口栈顶，确保不被其他 overlay 遮挡 */
    private fun bringFloatingBallToFront() {
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    }

    private fun removeAllViews() {
        try {
            if (isViewAdded(floatingBallView)) {
                windowManager.removeView(floatingBallView)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error removing floating ball", e)
        }
        // 清理复制模式状态
        isCopyMode = false
        removeCopyClickLayer()
        removeCopyButtons()
        dismissCacheOverlay()
        debugPanel.dismiss()
        dismissResultOverlay()
        dismissProgressOverlay()
        dismissToastOverlay()
        handler.removeCallbacks(longPressRunnable)
        autoTranslateEngine.clearScheduled()
        // Remove crop view if active
        if (isCropActive) {
            try {
                windowManager.removeView(cropView)
            } catch (e: Exception) { /* ignore */ }
            isCropActive = false
        }
    }

    /**
     * 显示提示消息
     * @param message 消息内容
     * @param immediate true=覆盖显示（状态进度、模型切换），false=队列显示（初始化、启停提示）
     */
    private fun showToast(message: String, immediate: Boolean = false) {
        if (immediate) {
            statusOverlay.showImmediate(message)
        } else {
            statusOverlay.show(message)
        }
    }

    private fun dismissToastOverlay() {
        statusOverlay.dismiss()
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

    // ========== ML Kit 调试模式 ==========

    private suspend fun detectWithMLKitDebug(bitmap: Bitmap, language: String): MLKitDebugResult {
        return DetectionBridge.detectWithMLKitDebug(bitmap, language)
    }

    /**
     * ML Kit 调试模式：渲染所有识别数据到图片上
     */
    private fun showMLKitDebugView(bitmap: Bitmap, result: MLKitDebugResult) {
        val debugBitmap = MangaDebugOverlays.renderMLKitDebugOverlay(bitmap, result)
        debugPanel.showDebugOverlay(
            debugBitmap, cropRect, getScreenSize(), initialCollapsed = false, errorTag = "ML Kit Debug"
        ) { container ->
            val infoPanel = MangaDebugOverlays.createInfoPanelView(
                this, MangaDebugOverlays.buildMLKitInfoLines(result),
                scrollable = true, maxHeight = getScreenSize().height / 2
            )
            container.addView(infoPanel, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM })
            val toggle = MangaDebugOverlays.createToggleButton(this, onToggle = { debugPanel.toggleCollapse() })
            container.addView(toggle, android.widget.FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = dpToPx(16)
                bottomMargin = dpToPx(16)
            })
            debugPanel.setToggleButton(toggle)
            infoPanel
        }
    }

    /**
     * 从 OcrResult 构建 TextRegionMerger 输入并执行合并
     */
    /**
     * PP-OCRv5 调试模式：渲染检测+识别+合并结果并显示
     */
    private fun showPPOcrV5DebugView(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV5Engine.DebugDetResult? = null) {
        val debugBitmap = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocr_text_score_thresh", 0.5f))
        debugPanel.showDebugOverlay(
            debugBitmap, cropRect, getScreenSize(), initialCollapsed = true, errorTag = "PP-OCRv5 Debug"
        ) { container ->
            val disc = debugDet
            val recDebug = ocrResult.recDebug
            val discCount = disc?.discardedBoxes?.size ?: 0
            val scoreDisc = recDebug?.discardedReasons?.count { it == "score" } ?: 0
            val contentDisc = recDebug?.discardedReasons?.count { it != "score" } ?: 0
            val infoLines = MangaDebugOverlays.buildPPOcrInfoLines(
                headerLines = listOf(
                    "PP-OCRv5 调试模式 | 检测: ${ocrResult.boxes.size}  检测丢弃: $discCount  识别丢弃: $scoreDisc  内容丢弃: $contentDisc  输出: ${ocrResult.texts.size}  合并: ${mergedRegions.size}区域"
                ),
                ocrResult = ocrResult,
                mergedRegions = mergedRegions,
                detDiscardBoxes = disc?.discardedBoxes ?: emptyList(),
                detDiscardScores = disc?.discardedScores ?: emptyList(),
                detDiscardReasons = disc?.discardedReasons ?: emptyList(),
                prefs = prefs,
                detBoxKey = "ppocr_det_box_thresh", detBoxDefault = 0.3f,
                unclipKey = "ppocr_det_unclip_ratio",
                textKey = "ppocr_text_score_thresh", textDefault = 0.5f
            )
            val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)
            val slidersView = MangaDebugSliders.createPPOcrParamSlidersView(prefs, this)
            val foldableContent = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            foldableContent.addView(slidersView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            foldableContent.addView(infoPanel, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            foldableContent.visibility = android.view.View.GONE  // 默认折叠
            container.addView(foldableContent, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM })
            val toggle = MangaDebugOverlays.createToggleButton(this, onToggle = { debugPanel.toggleCollapse() })
            toggle.text = "▲"  // 初始折叠
            container.addView(toggle, android.widget.FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = dpToPx(16)
                bottomMargin = dpToPx(16)
            })
            debugPanel.setToggleButton(toggle)
            foldableContent
        }
    }

    private fun showPPOcrV6DebugView(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV6Engine.DebugDetResult? = null) {
        val debugBitmap = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocrv6_text_score", 0.5f))
        debugPanel.showDebugOverlay(
            debugBitmap, cropRect, getScreenSize(), initialCollapsed = true, errorTag = "PP-OCRv6 Debug"
        ) { container ->
            val disc = debugDet
            val recDebug = ocrResult.recDebug
            val discCount = disc?.discardedBoxes?.size ?: 0
            val scoreDisc = recDebug?.discardedReasons?.count { it == "score" } ?: 0
            val contentDisc = recDebug?.discardedReasons?.count { it != "score" } ?: 0
            val infoLines = MangaDebugOverlays.buildPPOcrInfoLines(
                headerLines = listOf(
                    "PP-OCRv6 调试模式 | det尺寸: ${PPOcrV6Engine.lastDetSize}",
                    "检测: ${ocrResult.boxes.size}  丢弃: $discCount  识别丢: $scoreDisc  内容丢: $contentDisc  输出: ${ocrResult.texts.size}  合并: ${mergedRegions.size}"
                ),
                ocrResult = ocrResult,
                mergedRegions = mergedRegions,
                detDiscardBoxes = disc?.discardedBoxes ?: emptyList(),
                detDiscardScores = disc?.discardedScores ?: emptyList(),
                detDiscardReasons = disc?.discardedReasons ?: emptyList(),
                prefs = prefs,
                detBoxKey = "ppocrv6_det_box_thresh", detBoxDefault = 0.5f,
                unclipKey = "ppocrv6_det_unclip_ratio",
                textKey = "ppocrv6_text_score", textDefault = 0.5f
            )
            val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)
            val slidersView = MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs, this)
            slidersView.visibility = android.view.View.GONE  // 参数面板默认隐藏
            container.addView(slidersView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                bottomMargin = dpToPx(80)  // 给 info 面板留空间
            })
            container.addView(infoPanel, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM })
            // 两个折叠按钮：📊信息 / ⚙参数（右下角，左右排列，无背景）
            val btnSize = dpToPx(36)
            val margin = dpToPx(12)
            val toggleButton = android.widget.TextView(this).apply {
                text = "📊"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    infoPanel.visibility = if (infoPanel.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                    text = "📊"
                }
            }
            container.addView(toggleButton, android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = margin
                bottomMargin = margin
            })
            val paramsToggle = android.widget.TextView(this).apply {
                text = "⚙"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    slidersView.visibility = if (slidersView.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
            container.addView(paramsToggle, android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = margin + btnSize + 2
                bottomMargin = margin
            })
            toggleButton.text = "▼"  // 初始显示（原实现 addView 后设置）
            debugPanel.setToggleButton(toggleButton)
            infoPanel
        }
    }

    /** 限制最大高度的 ScrollView，用于调试面板半屏约束 */
    private suspend fun runOcrOnBitmap(bitmap: android.graphics.Bitmap): List<TextBlockInfo> {
        return withContext(Dispatchers.IO) {
            when (config.detEngine) {
                DetEngine.MLKIT -> {
                    OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                }
                DetEngine.RT_DETR_V2 -> {
                    DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, this@MangaFloatingService, config.keepTextFree)
                }
                DetEngine.PP_OCR_V6 -> {
                    DetectionBridge.detectWithPPOcrV6(bitmap, config.sourceLang, this@MangaFloatingService)
                }
                DetEngine.PP_OCR_V5 -> {
                    DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                }
            }
        }
    }
}
