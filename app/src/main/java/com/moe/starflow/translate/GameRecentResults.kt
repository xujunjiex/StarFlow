package com.moe.starflow.translate

import com.moe.starflow.utils.CustomPreference

/**
 * 「游戏/视频翻译结果浮窗」相关设置的唯一读取入口。
 *
 * 键都写在 `res/xml/personalization.xml`：
 * - [KEY_ENABLED] 多条显示开关，默认关闭（关闭时行为与旧版一致，只显示最新一条）
 * - [KEY_COUNT] 显示条数，值为字符串（`ListPreference` 的 entryValues），默认 3
 *   —— 这两项在「游戏/视频翻译结果设置」分类
 * - [KEY_HIDE] 不在游戏画面显示结果浮窗，默认关闭 —— 在「游戏分屏翻译」分类
 *   （分屏场景下结果只从主窗口的历史页看，见 `ui/history/HistoryFragment` 的自动刷新）
 */
object GameRecentResults {

    const val KEY_ENABLED = "game_recent_results_enabled"
    const val KEY_COUNT = "game_recent_results_count"
    const val KEY_HIDE = "game_hide_result_overlay"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_COUNT = 3

    /**
     * 结果浮窗是否**永不显示**（不是「翻译中临时隐藏」）：开着时
     * `FloatingBallService.showResultView()` 第一句就返回，翻译结束后也不会弹出来。
     * 默认关闭 —— 开成 true 会让所有人默认看不到结果浮窗，属于会静默毁功能的默认值。
     */
    const val DEFAULT_HIDE = false

    const val MIN_COUNT = 1

    /**
     * 条数上限取 5 而不是更大：结果浮窗是 `wrap_content` 且**不带滚动**，
     * 条数越多浮窗越高，越多条长译文越可能超出屏幕。5 条约等于 15 行文本，
     * 竖屏也放得下。
     */
    const val MAX_COUNT = 5

    // 注：多条之间的分隔线**不是字符**。早先想用 `————————`，但字符数固定，框宽/字号一变
    // 就只剩前半段；现由 `TranslationResultView.setEntries` 用 LineBackgroundSpan 画满宽实线
    // （颜色直接取 TextView 当前文字色，跟着「字体颜色」设置走）。

    /** 解析 `ListPreference` 存下来的条数：缺省/非法值回默认档，越界向 1..5 收敛。 */
    fun parseCount(raw: String?): Int {
        val parsed = raw?.trim()?.toIntOrNull() ?: return DEFAULT_COUNT
        return parsed.coerceIn(MIN_COUNT, MAX_COUNT)
    }

    /** 多条显示开关。 */
    fun isEnabled(prefs: CustomPreference): Boolean =
        prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** 显示条数（已收敛到 [MIN_COUNT]..[MAX_COUNT]）。 */
    fun countOf(prefs: CustomPreference): Int =
        parseCount(prefs.getString(KEY_COUNT, DEFAULT_COUNT.toString()))

    /** 结果浮窗是否永不显示（分屏场景：只看主窗口的历史页）。 */
    fun isHidden(prefs: CustomPreference): Boolean = isHidden(prefs.getBoolean(KEY_HIDE, DEFAULT_HIDE))

    /** 纯函数重载，供 JVM 单测锁住默认值与取值口径。 */
    fun isHidden(raw: Boolean?): Boolean = raw ?: DEFAULT_HIDE
}

/**
 * 最近成功译文的有界队列（按完成时间从旧到新，最后一条即最新）。
 *
 * 只保存**译文原文**，不保存原文/缓存标志/时间戳：
 * - 多条显示只需要译文（小尺寸下信息越少越好）；
 * - ⚡（内存缓存命中）是**本次上屏**的临时标志，不属于历史条目，不入队。
 *
 * 队列只活在本次服务会话里（`FloatingBallService.onDestroy` 清空），不落库、不从数据库恢复：
 * 历史里同一原文会被 `refreshGameCache` 覆盖成一条，用它当「最近 N 条」会给出错误顺序。
 *
 * ⚠️ 非线程安全：`FloatingBallService` 只在主线程读写（渲染回调都在 `Dispatchers.Main` 上）。
 */
class GameRecentResultQueue(limit: Int = GameRecentResults.DEFAULT_COUNT) {

    private val items = ArrayDeque<String>()

    /** 当前上限，取值恒在 [GameRecentResults.MIN_COUNT]..[GameRecentResults.MAX_COUNT]。 */
    var limit: Int = normalize(limit)
        private set

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    /**
     * 记录一条成功译文。空白译文直接忽略（不占位）。
     * @return 是否真的入队
     */
    fun add(translatedText: String): Boolean {
        if (translatedText.isBlank()) return false
        items.addLast(translatedText)
        trimToLimit()
        return true
    }

    /** 改上限：收缩时丢掉最旧的（保留最新的 `limit` 条）。 */
    fun setLimit(newLimit: Int) {
        limit = normalize(newLimit)
        trimToLimit()
    }

    /** 从旧到新的快照，最后一条是最新译文。 */
    fun snapshot(): List<String> = items.toList()

    /** 最新一条译文，空队列返回 null。 */
    fun newest(): String? = items.lastOrNull()

    fun clear() = items.clear()

    private fun trimToLimit() {
        while (items.size > limit) items.removeFirst()
    }

    private fun normalize(value: Int): Int =
        value.coerceIn(GameRecentResults.MIN_COUNT, GameRecentResults.MAX_COUNT)
}
