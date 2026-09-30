package com.moe.starflow.translate

import androidx.preference.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `TranslationStatusOverlay` 的**堆叠语义**（用户口径 2026-10）：
 * 「超分执行的时候也要有提示信息，而且要和翻译中**一起出现** —— 我们的通知系统本来就支持
 * 同时显示多个通知」。
 *
 * 现成的三个入口都做不到这件事，[TranslationStatusOverlay.showRunning] 是为它新开的第四个：
 * - `showImmediate` 是**替换**顶部一条 → 超分一开就把「翻译中…」顶掉；
 * - `show` 会自动消失 → 超分单页可跑几分钟，提示先自己没了；
 * - `showSticky` 登记进 sticky → 翻译收尾那次清屏清不掉它，会赖在屏幕上。
 *
 * 这里钉两条不能靠读源码看出来的不变式：
 * ① `showRunning` **追加**、不碰已在场的那条；② `removeRunning` **只**摘句柄指向的那条
 * （整章批量时几页同时在跑，摘错就是把别人的提示抹掉）。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationStatusOverlayTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val overlay get() = TranslationStatusOverlay.getInstance(ctx)

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean("status_overlay_enabled", true)
            .putString("Status_Position", "top")
            .putString("Status_Duration", "2000")
            .commit()
    }

    @After
    fun tearDown() {
        overlay.release()
    }

    @Test
    fun runningChipCoexistsWithTheProgressChipInsteadOfReplacingIt() {
        overlay.showImmediate("翻译中 · P5", autoDismiss = false)
        val id = overlay.showRunning("正在超分第 5 页…")

        val texts = overlay.debugChipTexts()
        assertEquals("两条都要在场上（showRunning 是追加、不是替换）", 2, texts.size)
        assertTrue("原来的进度芯片不能被顶掉", texts.contains("翻译中 · P5"))
        assertTrue("超分那条也要在", texts.contains("正在超分第 5 页…"))

        overlay.removeRunning(id)
        assertEquals("超分结束后只剩「翻译中…」", listOf("翻译中 · P5"), overlay.debugChipTexts())
    }

    @Test
    fun removeRunningOnlyRemovesTheChipItsHandlePointsAt() {
        val a = overlay.showRunning("正在超分第 1 页…")
        val b = overlay.showRunning("正在超分第 2 页…")

        overlay.removeRunning(a)
        assertEquals(listOf("正在超分第 2 页…"), overlay.debugChipTexts())

        overlay.removeRunning(b)
        assertTrue("两条都收掉后一条不剩", overlay.debugChipTexts().isEmpty())
    }

    /**
     * 顶部芯片被**改作他用**（进度 / 报错复用最上面那一条）之后就不再是「进行中」芯片了 ——
     * 必须从句柄表里摘掉。
     *
     * 不摘的话：那一页超分结束时会 `removeRunning`，把这条**已经变成进度/报错**的芯片一起删掉。
     * 表现是"一条提示莫名其妙自己消失"，而且只在「超分 + 翻译同时跑、且翻译把顶部那条复用掉」
     * 时才出现 —— 正是这次改动新引入的组合。
     */
    @Test
    fun aRepurposedTopChipStopsBeingARunningChip() {
        val id = overlay.showRunning("正在超分第 5 页…")
        // `showImmediate` 的语义就是**复用最上面那一条** → 超分那条被改写成「翻译中…」
        overlay.showImmediate("翻译中 · P5", autoDismiss = false)
        assertEquals("复用后只剩一条，且文案已换", listOf("翻译中 · P5"), overlay.debugChipTexts())

        overlay.removeRunning(id)
        assertEquals(
            "那一页超分结束时，不该把这条已被复用的进度芯片一起删掉",
            listOf("翻译中 · P5"),
            overlay.debugChipTexts(),
        )
    }

    /** 句柄为 0（浮层被关掉）或句柄早已失效 → 空操作：不崩，也不误伤别的芯片。 */
    @Test
    fun removingAZeroOrUnknownHandleIsANoOp() {
        overlay.show("普通提示")
        overlay.removeRunning(0L)
        overlay.removeRunning(999_999L)
        assertEquals(listOf("普通提示"), overlay.debugChipTexts())
    }

    /**
     * 槽位满时**优先挤掉最旧的同类芯片**，绝不先动普通提示。
     *
     * 为什么较真：整章批量翻译时每页都会起一次超分（用户口径「每一页都提示」），
     * 若按「谁最旧挤谁」处理，并排的「翻译中…／正在翻译本章…」就会被一条条挤掉。
     */
    @Test
    fun aFullOverlayEvictsTheOldestRunningChipNotTheOthers() {
        overlay.show("普通提示")           // 先占一个普通槽位
        val a = overlay.showRunning("正在超分第 1 页…")
        overlay.showRunning("正在超分第 2 页…")
        // 此刻 3 个槽位已满（MAX_SLOTS = 3）→ 再进一条必须挤掉「正在超分第 1 页…」
        overlay.showRunning("正在超分第 3 页…")

        val texts = overlay.debugChipTexts()
        assertTrue("普通提示不许被挤掉", texts.contains("普通提示"))
        assertTrue("最旧的那条超分芯片应被挤掉", !texts.contains("正在超分第 1 页…"))
        assertTrue(texts.contains("正在超分第 2 页…"))
        assertTrue(texts.contains("正在超分第 3 页…"))

        // 被挤掉的那条，它的句柄再收也是空操作（不能因此误删别人）
        overlay.removeRunning(a)
        assertEquals("收一个已失效的句柄不该动到场上任何一条", texts, overlay.debugChipTexts())
    }
}
