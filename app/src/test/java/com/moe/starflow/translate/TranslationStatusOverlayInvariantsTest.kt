package com.moe.starflow.translate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `TranslationStatusOverlay` 的生命周期**不变量**测试。
 *
 * 这里刻意用源码断言而不是 Robolectric 跑窗口：本组件的坏法全是「调用顺序 / 语义错」，
 * 而不是「像素画错」。历史上同一类 bug 反复出现且都**无异常可查**：
 *
 * 1. `dismiss()` 清空**全部** chip —— 而 `processMangaScreenshot` / `processMangaScreenshot`
 *    的 `finally` 每轮翻译都会调它，于是「屏幕方向已变化…」这类提示刚发出几毫秒就被吃掉，
 *    用户只看到「检测中…」然后什么都没有（实测日志：发出 23:55:29.435 → 被清）。
 *    所以 `dismiss()` **必须**保留 sticky chip（`keepSticky = true`）。
 * 2. `showImmediate` 会**替换**顶部 chip —— 进度类消息用它是对的，但通知类消息
 *    （方向变化）一旦用它就必然被后续「检测中…」顶掉。
 */
class TranslationStatusOverlayInvariantsTest {

    private val source: String by lazy {
        File("src/main/java/com/moe/starflow/translate/TranslationStatusOverlay.kt")
            .readText()
    }

    /** ⚠️ 核心回归：`dismiss()` 必须保留 sticky，否则方向变化提示会被进度收尾清掉。 */
    @Test
    fun dismissKeepsStickyChips() {
        val dismissBody = source
            .substringAfter("fun dismiss()")
            .substringBefore("fun release()")
        assertTrue(
            "dismiss() 必须调用 removeAllChips(keepSticky = true) —— " +
                "否则每轮翻译收尾的清屏会把「屏幕方向已变化…」提示一起抹掉（实测 bug）",
            dismissBody.contains("keepSticky = true")
        )
    }

    /** sticky 必须真的被排除在清屏之外（不是只传了个参数）。 */
    @Test
    fun removeAllChipsHonoursKeepSticky() {
        val body = source.substringAfter("private fun removeAllChips(")
        assertTrue(
            "removeAllChips 必须在 keepSticky 时跳过 stickyChips，普通清屏才 removeAllViews()",
            body.contains("keepSticky && stickyChips.isNotEmpty()") &&
                body.contains("layout.removeAllViews()")
        )
    }

    /** 方向变化这类通知必须走 showSticky（不自动消失），不能用 showImmediate（会被顶掉）。 */
    @Test
    fun stickyApiExistsAndDoesNotAutoDismiss() {
        val body = source.substringAfter("fun showSticky(")
        assertTrue("showSticky 应登记到 stickyChips", body.contains("stickyChips.add("))
        // ⚠️ autoDismiss 必须为 true：false 时不排消失任务，配合「dismiss 保留 sticky」
        // 会让提示永久驻留（实测踩过）
        assertTrue(
            "showSticky 必须 autoDismiss = true（false 会永久驻留）",
            body.contains("autoDismiss = true")
        )
    }

    /**
     * ⚠️ `addChip` 必须**直接**调 `addToWindowIfNeeded()`，不能只靠 `layout.post {}`。
     *
     * `View.post()` 在 View 未 attach 时不会执行（排队等 attach），而新建容器必然未 attach
     * → 那个 runnable 永远不跑 → 窗口加不上 → 提示不可见，直到别的消息把它 attach 上去
     * （实测：框选后转屏的提示要等下次翻译才"延迟出现"）。
     */
    @Test
    fun addChipAttachesWindowDirectly() {
        val body = source.substringAfter("private fun addChip(").substringBefore("private fun rescheduleDismiss(")
        assertTrue(
            "addChip 必须直接调用 addToWindowIfNeeded()（仅靠 post 在未 attach 时不执行）",
            body.contains("addToWindowIfNeeded()")
        )
    }

    /**
     * ⚠️ sticky 提示**绝不能进队列**。
     *
     * 排队的消息要等前面的消失才显示，而中途任何一次 `dismiss()`（每轮翻译收尾都会调）
     * 会把队列一并清掉 —— 提示就此消失。实测：`检测到屏幕方向更改…` 于 00:31:43.406 发出，
     * 之后 10 秒内**没有任何** `Overlay added to window`，用户完全看不到。
     * 正确做法是槽位满时挤掉一个旧 chip、立即显示。
     */
    @Test
    fun stickyNeverEntersQueue() {
        val body = source.substringAfter("fun showSticky(").substringBefore("fun showError(")
        assertTrue(
            "showSticky 不得把消息加进 messageQueue（排队路径会被 dismiss 清掉）",
            !body.contains("messageQueue.add")
        )
        assertTrue("showSticky 应直接 addChip", body.contains("addChip("))
    }

    /**
     * `showImmediate` 的语义是「替换最顶部一条」，供进度使用。
     * 这条锁住「不要把它改成堆叠」——堆叠会让每轮进度都堆一条，屏幕瞬间刷满。
     */
    @Test
    fun showImmediateReplacesTopChipRatherThanStacking() {
        val body = source.substringAfter("fun showImmediate(")
        assertTrue(
            "showImmediate 应复用顶部 chip（topChip()），而非新增",
            body.contains("topChip()")
        )
    }
}
