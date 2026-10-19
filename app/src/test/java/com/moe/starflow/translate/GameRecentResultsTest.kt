package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「悬浮窗显示最近 N 条译文」的纯 JVM 守卫（无需 Robolectric）：
 * 条数解析的兜底口径，以及最近译文队列的顺序/上限/收缩行为。
 *
 * 改 [GameRecentResults] 或 [GameRecentResultQueue] 之前先看这里锁的是什么：
 * 队列只保存译文、按完成时间从旧到新、收缩时丢最旧的、空白译文不占位。
 */
class GameRecentResultsTest {

    // ───────────────────── 条数解析 ─────────────────────

    @Test
    fun parseCount_defaultsToThree() {
        assertEquals(3, GameRecentResults.DEFAULT_COUNT)
        assertEquals(GameRecentResults.DEFAULT_COUNT, GameRecentResults.parseCount(null))
        assertEquals(GameRecentResults.DEFAULT_COUNT, GameRecentResults.parseCount(""))
        assertEquals(GameRecentResults.DEFAULT_COUNT, GameRecentResults.parseCount("abc"))
        assertEquals(GameRecentResults.DEFAULT_COUNT, GameRecentResults.parseCount("   "))
    }

    @Test
    fun parseCount_isClampedToDeclaredRange() {
        // 手改 prefs / 越界值不能把浮窗撑爆
        assertEquals(GameRecentResults.MIN_COUNT, GameRecentResults.parseCount("0"))
        assertEquals(GameRecentResults.MIN_COUNT, GameRecentResults.parseCount("-3"))
        assertEquals(GameRecentResults.MAX_COUNT, GameRecentResults.parseCount("99"))
        assertEquals(2, GameRecentResults.parseCount(" 2 "))
    }

    // ───────────────────── 不在游戏画面显示结果浮窗（游戏分屏翻译） ─────────────────────

    @Test
    fun hideOverlay_defaultsToOff() {
        // 默认值必须是 false：开成 true 等于让所有人默认看不到结果浮窗（静默毁功能）
        assertFalse(GameRecentResults.DEFAULT_HIDE)
        assertFalse(GameRecentResults.isHidden(null))
    }

    @Test
    fun hideOverlay_readsStoredValue() {
        assertFalse(GameRecentResults.isHidden(false))
        assertTrue(GameRecentResults.isHidden(true))
    }

    // ───────────────────── 最近译文队列 ─────────────────────

    @Test
    fun add_keepsNewestAndDropsOldest() {
        val queue = GameRecentResultQueue(limit = 3)
        queue.add("一")
        queue.add("二")
        queue.add("三")
        queue.add("四")

        assertEquals(listOf("二", "三", "四"), queue.snapshot())
        assertEquals("四", queue.newest())
        assertEquals(3, queue.size)
    }

    @Test
    fun add_ignoresBlankTranslations() {
        val queue = GameRecentResultQueue(limit = 3)
        assertFalse(queue.add(""))
        assertFalse(queue.add("   "))
        assertFalse(queue.add("\n"))
        assertTrue(queue.isEmpty())

        assertTrue(queue.add("译文"))
        assertEquals(listOf("译文"), queue.snapshot())
    }

    @Test
    fun add_keepsDuplicates() {
        // 同一译文重复出现是两次独立翻译，不去重（去重会让「最近 N 条」少给用户一条）
        val queue = GameRecentResultQueue(limit = 3)
        queue.add("相同")
        queue.add("相同")
        assertEquals(listOf("相同", "相同"), queue.snapshot())
    }

    @Test
    fun setLimit_shrinksFromTheOldestEnd() {
        val queue = GameRecentResultQueue(limit = 5)
        listOf("1", "2", "3", "4", "5").forEach { queue.add(it) }

        queue.setLimit(2)
        assertEquals(listOf("4", "5"), queue.snapshot())

        // 放大上限不会凭空补历史，后续新译文继续入队
        queue.setLimit(4)
        queue.add("6")
        assertEquals(listOf("4", "5", "6"), queue.snapshot())
    }

    @Test
    fun setLimit_isClamped() {
        val queue = GameRecentResultQueue(limit = 3)
        queue.setLimit(0)
        assertEquals(GameRecentResults.MIN_COUNT, queue.limit)
        queue.setLimit(99)
        assertEquals(GameRecentResults.MAX_COUNT, queue.limit)
    }

    @Test
    fun clear_emptiesTheQueue() {
        val queue = GameRecentResultQueue(limit = 3)
        queue.add("译文")
        queue.clear()
        assertTrue(queue.isEmpty())
        assertEquals(null, queue.newest())
        assertEquals(emptyList<String>(), queue.snapshot())
    }
}
