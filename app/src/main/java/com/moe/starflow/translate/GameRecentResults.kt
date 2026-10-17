package com.moe.starflow.translate

import com.moe.starflow.utils.CustomPreference

/**
 * 「结果浮窗一次显示最近 N 条译文」设置的唯一读取入口。
 *
 * 键都写在 `res/xml/personalization.xml` 的「游戏/视频翻译结果设置」分类里：
 * - [KEY_ENABLED] 多条显示开关，默认关闭（关闭时行为与旧版一致，只显示最新一条）
 * - [KEY_COUNT] 显示条数，值为字符串（`ListPreference` 的 entryValues），默认 3
 */
object GameRecentResults {

    const val KEY_ENABLED = "game_recent_results_enabled"
    const val KEY_COUNT = "game_recent_results_count"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_COUNT = 3

    /**
     * 条数上限取 5 而不是更大：结果浮窗是 `wrap_content` 且**不带滚动**，
     * 条数越多浮窗越高，越多条长译文越可能超出屏幕。5 条约等于 15 行文本，
     * 竖屏也放得下。
     */
    const val MIN_COUNT = 1
    const val MAX_COUNT = 5

    /** 多条显示时插在相邻两条之间的分隔线（纯文本，保持浮窗结构不变以便拖动）。 */
    const val SEPARATOR = "————————"

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
