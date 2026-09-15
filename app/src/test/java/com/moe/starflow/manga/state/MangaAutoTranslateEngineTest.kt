package com.moe.starflow.manga.state

import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaAutoTranslateEngineTest {

    private lateinit var engine: MangaAutoTranslateEngine
    private var lastToast = ""
    private var progressShown = ""
    private var progressDismissed = false
    private var regionCleared = 0
    private var triggers = 0

    @Before
    fun setUp() {
        lastToast = ""
        progressShown = ""
        progressDismissed = false
        regionCleared = 0
        triggers = 0
        engine = MangaAutoTranslateEngine(
            context = RuntimeEnvironment.getApplication(),
            isResultOrMenuShowing = { false },
            isProcessing = { false },
            onShowProgress = { progressShown = it },
            onDismissProgress = { progressDismissed = true },
            onTriggerTranslation = { triggers++ },
            onClearRegionCache = { regionCleared++ },
            onShowToast = { lastToast = it }
        )
    }

    @Test
    fun start_initializesStateAndClearsRegionCache() {
        engine.start()
        assertTrue(engine.isAutoTranslating)
        assertEquals(DetectState.IDLE, engine.detectState)
        assertEquals(1, regionCleared)
        assertTrue(lastToast.isNotEmpty())
    }

    @Test
    fun stop_resetsStateAndDismissesProgress() {
        engine.start()
        engine.stop()
        assertFalse(engine.isAutoTranslating)
        assertFalse(engine.isManualTranslating)
        assertEquals(DetectState.IDLE, engine.detectState)
        assertTrue(progressDismissed)
    }

    @Test
    fun processPHash_idleFirstScreenshot_translate() {
        engine.start()
        assertTrue(engine.processAutoDetectPHash(0x12345678L))  // lastTranslated=0 且 prev=0 → 首帧直接翻译
    }

    @Test
    fun processPHash_idleSameAsTranslated_skip() {
        engine.start()
        engine.lastTranslatedHash = 0xABCDL
        // 与已翻译页相同的 hash → sim=1.0 >= 0.95 → 跳过
        assertFalse(engine.processAutoDetectPHash(0xABCDL))
    }

    @Test
    fun processPHash_idleDifferent_motionAndProgress() {
        engine.start()
        engine.lastTranslatedHash = 0x0001L
        // 完全不同的 hash → sim≈0 < 0.95 → 进入 MOTION，显示"检测中"，本次跳过
        assertFalse(engine.processAutoDetectPHash(-1L))
        assertEquals(DetectState.MOTION, engine.detectState)
        // ⚠️ 断言资源字符串而非硬编码中文：文案已 i18n（2026-09-14 外提到 values/values-zh），
        // 测试环境下 locale 是默认英文，写死 "检测中..." 会永远失败
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.detecting), progressShown)
    }

    @Test
    fun processPHash_motionStabilizedAfterTwoConsecutive_translate() {
        engine.start()
        engine.lastTranslatedHash = 0x0001L
        // IDLE → MOTION（第一次不同）
        assertFalse(engine.processAutoDetectPHash(-1L))
        // MOTION 稳定第 1 次（与 prev 相同 sim=1.0）→ stableCount=1 < 2 → 继续等
        assertFalse(engine.processAutoDetectPHash(-1L))
        // MOTION 稳定第 2 次 → STABLE → onMotionStabilized：与已翻译页完全不同 → 翻译
        assertTrue(engine.processAutoDetectPHash(-1L))
        assertEquals(DetectState.STABLE, engine.detectState)
    }

    @Test
    fun processPHash_motionStillMoving_resetCount() {
        engine.start()
        engine.lastTranslatedHash = 0x0001L
        assertFalse(engine.processAutoDetectPHash(-1L))  // → MOTION
        // 还在动（与 prev 不同）→ 重置计数，继续等
        assertFalse(engine.processAutoDetectPHash(0x5555L))
        assertEquals(DetectState.MOTION, engine.detectState)
        // stableCount 未达标（0）
        assertFalse(engine.processAutoDetectPHash(0xAAAAL))  // 又不同，仍 MOTION
    }

    @Test
    fun runAutoDetect_triggersTranslationWhenIdle() {
        engine.start()
        // 手动触发调度回调不可直接调（private）；验证 triggerTranslation 回调只在状态机需截图时触发。
        // 这里通过 scheduleNextDetection 后 shadow idle 触发 runAutoDetect。
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(triggers > 0)
    }
}
