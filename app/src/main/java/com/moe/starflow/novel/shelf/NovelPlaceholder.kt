package com.moe.starflow.novel.shelf

import com.moe.starflow.mangaimport.data.ImportTask
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat

/**
 * 导入任务 → 小说书架占位条目。
 *
 * ⚠️ 占位条目的 `localRoot` 为空、`importing = true`：书架靠它区分「正在导入」与「真实条目」，
 * 并让占位**不参与**「文件丢失」推导（占位本来就没有本地路径，不排除的话每张占位卡片都会
 * 挂上「文件丢失」的角标）。
 */
fun ImportTask.toNovelPlaceholder(): ImportedNovel = ImportedNovel(
    id = id,
    title = title,
    localRoot = "",
    // 占位阶段还不知道真实格式，只影响图标；真实格式在入库时确定
    format = NovelFormat.TXT,
    coverPath = null,
    chapterCount = 0,
    addedAt = addedAt,
    importing = true,
    importPhase = progress.phase,
    importPercent = percent,
)
