package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页序迁移的纯逻辑守卫（见 [MangaPageOrder]）。
 *
 * 背景（2026-09 `/review` 数据迁移审查的 CRITICAL）：章节系统把「最外层散图」排到第 0 章最前，
 * 而旧版是 `sortNaturally(完整路径)` —— 升级后同一 `pageIndex` 指向另一张图，
 * 而译文/气泡按下标存 → 译文挂错页。这里锁住「旧序 → 新序」的置换算得对。
 */
class MangaPageOrderTest {

    @Test
    fun identicalOrder_needsNoMigration() {
        val keys = listOf("ch1/1.jpg", "ch1/2.jpg", "ch2/1.jpg")
        // 纯子目录包：旧序（完整路径自然排序）与新序一致 → 不迁移
        assertNull(MangaPageOrder.legacyToNewPlan(keys, listOf("ch1/1.jpg", "ch1/2.jpg", "ch2/1.jpg")))
    }

    @Test
    fun rootScatteredPages_planRemapsByKey() {
        // 旧版：z.jpg 排在 ch1/* 之后；新版：最外层散图 = 第 0 章，排最前
        val raw = listOf("z.jpg", "ch1/1.jpg", "ch1/2.jpg")
        val new = listOf("z.jpg", "ch1/1.jpg", "ch1/2.jpg")   // 新序（第0章 = z.jpg）
        // 旧序 = sortNaturally(raw) = ["ch1/1.jpg", "ch1/2.jpg", "z.jpg"]
        val plan = MangaPageOrder.legacyToNewPlan(raw, new)!!
        // 旧下标 0 = ch1/1.jpg → 新下标 1；旧 1 → 新 2；旧 2 = z.jpg → 新 0
        assertArrayEquals(intArrayOf(1, 2, 0), plan)
        // 旧下标 0（ch1/1.jpg）→ 新下标 1；旧下标 2（z.jpg）→ 新下标 0
        assertEquals(1, MangaPageOrder.mapPage(plan, 0))
        assertEquals(0, MangaPageOrder.mapPage(plan, 2))
    }

    @Test
    fun pageSetMismatch_isNotMigrated() {
        // 文件被换过（增删页）→ 映射没有意义，宁可不动
        assertNull(MangaPageOrder.legacyToNewPlan(listOf("a/1.jpg"), listOf("a/1.jpg", "a/2.jpg")))
        assertNull(MangaPageOrder.legacyToNewPlan(listOf("a/1.jpg", "b/1.jpg"), listOf("a/1.jpg", "c/1.jpg")))
        assertNull(MangaPageOrder.legacyToNewPlan(emptyList(), emptyList()))
    }

    @Test
    fun mapPage_outOfRangeIsIdentity() {
        val plan = intArrayOf(1, 0)
        assertEquals("越界原样返回", 5, MangaPageOrder.mapPage(plan, 5))
        assertEquals("没有计划就是恒等", 3, MangaPageOrder.mapPage(null, 3))
    }
}
