package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「导入」tab 内切换的状态守卫。
 *
 * 存在意义：这个切换**替代了新增底部 tab**（`BottomNavigationView` 硬限 5 个 item，
 * 底部菜单已满）。若有人把回退值改错（比如坏值回退到小说），升级用户一进来会以为
 * 漫画书架丢了 —— 那是最高频的误报来源。
 */
class ImportTabTest {

    @Test
    fun `缺失或坏值一律回退漫画`() {
        assertEquals(ImportTab.MANGA, ImportTab.fromPref(null))
        assertEquals(ImportTab.MANGA, ImportTab.fromPref(""))
        assertEquals(ImportTab.MANGA, ImportTab.fromPref("MANGA"))
        assertEquals(ImportTab.MANGA, ImportTab.fromPref("2"))
        assertEquals(ImportTab.MANGA, ImportTab.fromPref("novels"))
    }

    @Test
    fun `小说可被选中`() {
        assertEquals(ImportTab.NOVEL, ImportTab.fromPref("novel"))
    }

    @Test
    fun `两个 tab 的持久化值互不相同`() {
        assertNotEquals(ImportTab.MANGA.prefValue, ImportTab.NOVEL.prefValue)
    }

    @Test
    fun `每个枚举值的持久化值都能反解回自身`() {
        for (t in ImportTab.entries) {
            assertEquals(t, ImportTab.fromPref(t.prefValue))
        }
    }
}
