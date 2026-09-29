package com.moe.starflow.mangaimport.translate

/**
 * 一次翻译的**文本缓存统计**。
 *
 * ## 为什么要有这个类（而不是控制器上的两个字段）
 * 原来 `ReaderTranslationController` 上有 `cacheCandidates`/`cacheHits` 两个**实例字段**：
 * - 阅读器前台两页并发时数字互串 → 「12 条里命中 3 条」说的根本不是这一页
 * - 章节批量路径**只写不读**（它从不显示这条提示）→ 纯粹在污染前台那一路的数字
 *
 * 改成"一次调用的产物"之后，统计天然跟着这次调用走，串号在结构上不可能发生。
 *
 * 分母口径（沿用既有约定，别改）：**有文字可判定的气泡数**，不是"气泡数" ——
 * 只含符号的气泡不走翻译、但也没调 API，漏掉它们分母会比用户数出来的少。
 */
internal class CacheOutcome(val candidates: Int, val hits: Int)
