package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译窗口的纯逻辑守卫。
 *
 * 队列本身（抢锁、轮询、写库）要跑真协程 + Room，覆盖在 `NovelChapterTranslatorTest`；
 * 这里只钉「挑哪一章」的判定 —— 那是最容易写错、也最难从现象反推的部分。
 */
class NovelTranslationQueueTest {

    // ===== 窗口 =====

    @Test
    fun `手动模式窗口为空`() {
        assertTrue(NovelQueueWindow.forMode(mode = 0, current = 3, ahead = 5, chapterCount = 20).isEmpty())
    }

    @Test
    fun `自动当前章只含当前章`() {
        assertEquals(listOf(3), NovelQueueWindow.forMode(mode = 1, current = 3, ahead = 5, chapterCount = 20))
    }

    @Test
    fun `自动后续 N 章从当前章起算`() {
        assertEquals(
            listOf(3, 4, 5, 6, 7),
            NovelQueueWindow.forMode(mode = 2, current = 3, ahead = 5, chapterCount = 20),
        )
    }

    @Test
    fun `窗口在书末被截断`() {
        assertEquals(
            listOf(18, 19),
            NovelQueueWindow.forMode(mode = 2, current = 18, ahead = 5, chapterCount = 20),
        )
    }

    @Test
    fun `ahead 小于 1 时至少含当前章`() {
        assertEquals(listOf(3), NovelQueueWindow.forMode(mode = 2, current = 3, ahead = 0, chapterCount = 20))
    }

    @Test
    fun `当前章越界返回空窗口`() {
        assertTrue(NovelQueueWindow.forMode(mode = 2, current = -1, ahead = 5, chapterCount = 20).isEmpty())
        assertTrue(NovelQueueWindow.forMode(mode = 1, current = 20, ahead = 5, chapterCount = 20).isEmpty())
    }

    // ===== 挑下一个待翻章 =====

    @Test
    fun `挑窗口内第一个没翻过的章`() {
        assertEquals(
            4,
            NovelQueueWindow.nextPending(listOf(3, 4, 5), translated = setOf(3), failed = emptySet()),
        )
    }

    /**
     * 失败章也要跳过。只跳「已成功」的话，内容性失败（空章、模型返回空）会被每轮重新挑中 ——
     * 表现为「队列一直在转但什么都不发生」，而且因为「有章正在翻」不成立，连进度提示都没有。
     */
    @Test
    fun `失败章也被跳过`() {
        assertEquals(
            5,
            NovelQueueWindow.nextPending(listOf(3, 4, 5), translated = setOf(3), failed = setOf(4)),
        )
    }

    @Test
    fun `窗口内全部翻过或失败时返回 null`() {
        assertNull(NovelQueueWindow.nextPending(listOf(3, 4), setOf(3, 4), emptySet()))
        assertNull(NovelQueueWindow.nextPending(listOf(3, 4), emptySet(), setOf(3, 4)))
        assertNull(NovelQueueWindow.nextPending(emptyList(), emptySet(), emptySet()))
    }

    @Test
    fun `已翻与失败混合时挑剩下的那个`() {
        assertEquals(6, NovelQueueWindow.nextPending(listOf(3, 4, 5, 6), setOf(3, 4), setOf(5)))
    }

    /** 窗口外的章（用户切章后不再需要翻）不该被挑走。 */
    @Test
    fun `只挑窗口内的章`() {
        assertNull(NovelQueueWindow.nextPending(listOf(3, 4), setOf(3, 4), emptySet()))
    }
}
