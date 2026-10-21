# 数据与缓存模块

> 范围：本文记录 `data/` 的当前实现约束。跨模块规则见仓库根目录 `AGENTS.md` 和 `CLAUDE.md`。源码与测试为准；实现变化后同步更新本文。

## 书库工作数据库

漫画/小说使用 `library/LibraryDatabase`，按本机实际存储位置分库；当前 schema v1，无破坏性回退。作品键为目录身份、类型和 UUID；书目、阅读进度及处理记录直接以内部 Room 事务保存，不向作品目录镜像。待写队列仅处理原文件、索引和图片产物。全局 `translation_history.db` 仍保存截图历史；旧书库表只作为只读迁移输入，兼容的旧处理记录经私有归档写入新库，不再直接供阅读器查询。协议见 [书库存储指南](../library/CLAUDE.md)。

## 缓存与历史

`TranslationCacheManager` — 统一管理游戏/漫画翻译缓存
- 漫画新记录的 `bubbleRects` JSON 同时保存每个区域的完整 `originalText` / `translatedText` 与几何信息。渲染、详情和复制统一通过 `TranslationCacheUtils.readBubbleTexts` / `rebuildBubblesFromCache` 读取；编号形式的 `sourceText` / `translatedText` 用于展示和检索，不能用正文编号或换行重建区域边界。仅 OCR 的显示回填文本必须在序列化时排除，不能存成译文。
- 漫画截图命中：256-bit 扩展 pHash 完全相同后，再校验裁剪尺寸和位置；第二层相似度兜底保留实现但默认停用，避免相似版式页面误命中
- 游戏模式：仅精确匹配（相似度匹配会误判相似背景）
- Room 数据库 `translation_history.db`，当前 version 24，`fallbackToDestructiveMigration`
  - v9→v10 迁移：`ALTER TABLE ... ADD COLUMN pHash2/pHash3/pHash4 INTEGER NOT NULL DEFAULT 0`（256-bit 扩展 hash）
  - v10→v11 迁移：幂等修复漏加的 `last_session_id` 列 + `createdAt`→`created_at` 列名（先 `PRAGMA table_info` 检查再操作）
  - v14→v17：阅读器每页翻译表 `imported_page_translation` 新增 + `mangaKey`（身份指纹）+ `translatorName/sourceLang/targetLang`（详见 `mangaimport/CLAUDE.md` 的「阅读器内嵌翻译」）
  - v17→v18：小说逐段译文表 `novel_paragraph_translation`（纯新增，`CREATE TABLE IF NOT EXISTS`），
    主键 `(novelId, novelKey, chapterIndex, paraIndex)` —— ⚠️ **不含 `splitVersion`**：
    小说那边改切分规则时，旧版本的行会占着新版本同一批主键位、新行 `insertIgnore` 静默写不进去
    （这是 `novel/CLAUDE.md` 的 `SPLIT_VERSION` 那条坑的根源，改动前先读它）
  - v18→v19：**超分逐页记录表 `imported_page_sr`**（`ImportedPageSr` + `ImportedPageSrDao`，纯新增、
    幂等 `CREATE TABLE IF NOT EXISTS`）。主键 `(mangaId, pageIndex)` + 身份指纹 `mangaKey`，
    语义与 `imported_page_translation` **完全一致**（id 会被复用 → 读/删都必须带指纹）。
    两条硬约束与译文表同源：① **不写 DEFAULT**（Kotlin 默认值是语言层的，Room 建表语句里没有
    DEFAULT 子句，多写一个升级用户一打开库即抛 `IllegalStateException`）；② `rekeyPages`
    必须整体一个 `@Transaction`（逐行 `UPDATE pageIndex` 会在置换中撞主键，`REPLACE` 静默吃行）。
    守卫：`ImportedPageSrDaoTest` —— 除了 DAO 行为，还有一条**迁移建表语句 vs 实体 schema 逐列对比**
    （在另一个 scratch 库里只跑 `MIGRATION_18_19`，两边的 `PRAGMA table_info` 必须完全相同）。
- v19→v20：截图历史增加显式 OCR/翻译处理状态，历史译文载荷保留。
- v20→v21：每页翻译记录增加可空的 `ocrModel`；不能用 `translatorName` 替代，因为后者会被之后的翻译更新。
- v21→v22：小说段落记录增加可空的 `imagePayload`，保存插图 OCR/译文块。
- v22→v23：超分记录增加 `executionJson` 和稳定源页键 `sourcePageKey`。
- v23→v24：兼容不同开发分支留下的 v23 schema；按列检查并补齐超分记录字段，保留已有记录。不要改成破坏性迁移。
- ⚠️ **版本号真值以 `TranslationHistoryDatabase` 的 `@Database(...)` 为准**；修改后同步本节。
- ⚠️ **`rekeyPages`（页序迁移用）必须整体在一个 `@Transaction` 里**（读→删→按新下标重插）：
  中途失败会留下"一半新序一半旧序"的行；也不能逐行 `UPDATE pageIndex` —— 它是主键的一部分，
  置换过程中会与尚未搬迁的行撞键（`REPLACE` 会静默吃掉一行）。调用点在阅读器打开时（见 `mangaimport/CLAUDE.md`）。
- 历史 UI：`ui/history/HistoryFragment`，游戏和漫画均按时间+会话分组显示
- **历史变更通知**（游戏分屏翻译：主窗口历史页自动刷新，个性化页「历史记录自动刷新」开关，默认开）：
  `TranslationCacheManager.observeHistoryChanges()`（`Flow<Unit>`，底层是
  `TranslationHistoryDao.observeHistoryChange()` 的 `SELECT id ... ORDER BY updated_at DESC LIMIT 1`）。
  由 Room 的 InvalidationTracker 驱动，**任何**写入/更新/删除都会发一次，与写入方无关
  （游戏服务、阅读器批量翻译、删除、清空都覆盖）。
  - ⚠️ **别加 `distinctUntilChanged`**：`refreshGameCache` 替换或原地更新旧记录时最新 id 可能不变，
    但列表内容已经变了。
  - ⚠️ **也别拿它的返回值渲染列表** —— 它只是「变了」的信号。
  - ⚠️ 订阅时 Room 会**先发一次当前值**，收集方要 `drop(1)`（首屏数据由页面自己的 `loadHistory()` 负责）。
  - ⚠️ 收集方**必须单飞 + 去抖**（`HistoryFragment.scheduleRefresh`）：`getHistoryGrouped` 在 IO 线程跑，
    两轮并发时先发的那轮可能后返回 → **旧列表盖掉新列表**（`submitList` 不校验版本）。
    刷新期间**不要**逐 group/session 打日志：自动刷新是每次写库都跑，会把 `starflow.log`
    只留 300 行的缓冲冲掉，闪退时反而查不到崩溃块。
- 漫画图片浏览：`MangaViewerActivity` 全屏翻页 + 底部译文详情面板
- 重翻引擎选择：`history_retranslate_engine` 偏好（PP_OCR_V5/MANGA_OCR/MLKIT），通过 `mapEngineToDetOcr` 映射
  - ⚠️ **manga-ocr 必须配 `RT_DETR_V2`**（不可配 `PP_OCR_V5`，后者 `runOCR` 忽略 ocr 参数直接走独立管线）

**双 sessionId 架构：**
- `sessionId` — 原始创建会话 ID（首次翻译时分配，永不改变，用于**按创建排序**的进程组）
- `lastSessionId` — 最后修改会话 ID（任何修改时更新为当前会话，用于**按修改排序**的进程组）
- `createdAt` — 继承自同 pHash 旧记录（保证按创建排序位置不变）
- `updatedAt` — 每次翻译/缓存命中时更新

**翻译会话：**
- `FloatingBallService` / `MangaFloatingService` 每次启动生成 `UUID` 作为 `sessionId`
- `CacheEntry` 同时携带 `sessionId` 和 `lastSessionId`（初始值均为当前会话）
- `saveToCache`：`sessionId` 从同 pHash 旧记录继承（位置不变），`lastSessionId` 使用调用方当前会话
- `refreshCache` / `refreshGameCache`：`sessionId` 继承旧记录，`lastSessionId` 使用当前会话
- 缓存命中（`findCache` / `findMangaCacheByText`）：更新 `updatedAt` + `lastSessionId` 为当前会话
- `getHistoryGrouped(sortByUpdated=true)`：日期组 = `updatedAt`，进程组 = `lastSessionId`
- `getHistoryGrouped(sortByUpdated=false)`：日期组 = `createdAt`，进程组 = `sessionId`

**缓存标识（⚡ 严格区分来源）：**
- 游戏翻译：`FloatingBallService` **仅内存 LRU 命中**显示"⚡"前缀（`setText(it, fromCache = true)`）
- 游戏翻译：**数据库命中**不显示"⚡"（`setText(it)`，不带 fromCache）
- 漫画翻译：仅 `TranslatedBubble.isInMemoryCache = true` 时在 overlay 显示"⚡"
- 漫画翻译：**数据库反序列化的 bubbles 永远不显示"⚡"**（`fromCache=true` 但 `isInMemoryCache=false`）
- 底层规则：`OverlayRenderer.renderOverlay` 只检查 `region.isInMemoryCache`，不检查 `fromCache`。数据库反序列化走 `rebuildBubblesFromCache` 默认 `isInMemoryCache=false`
