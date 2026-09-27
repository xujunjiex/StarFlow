package com.moe.starflow.mangaimport.data

/**
 * 「导入」tab 内的两个书架：漫画 / 小说。
 *
 * ### 为什么小说不是底部导航的一个 tab
 * Material 的 `BottomNavigationView.getMaxItemCount()` **硬编码返回 5**（反编译
 * `material-1.12.0.aar` 确认：`NavigationBarMenu.addInternal` 里 `size + 1 > maxItemCount`
 * 时抛 `IllegalArgumentException`）。底部菜单**正好已经 5 个**，加第 6 个会在
 * `setContentView` 时直接崩溃。
 *
 * 所以小说并入现有的「导入」tab，顶部用分段切换 —— 语义上也更贴：
 * 两者都是「把本地文件装进书架」，只是内容类型不同。
 *
 * 选择**持久化**：用户上次看的是小说，下次进来还应该停在小说。
 */
enum class ImportTab(val prefValue: String) {
    MANGA("manga"),
    NOVEL("novel");

    companion object {
        const val KEY = "import_tab"

        /**
         * 未知 / 缺失值一律回退 [MANGA]：升级用户第一次进入看到的仍是熟悉的漫画书架，
         * 「突然变成小说」是更糟的默认。
         */
        fun fromPref(value: String?): ImportTab =
            entries.firstOrNull { it.prefValue == value } ?: MANGA
    }
}
