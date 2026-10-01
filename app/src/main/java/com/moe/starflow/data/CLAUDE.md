# 数据与缓存（`data/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 缓存与历史

`TranslationCacheManager` — 统一管理游戏/漫画翻译缓存
- 漫画模式：pHash 精确匹配 + 相似度匹配（256-bit 阈值 0.95，约 13 bit 容差）
- 游戏模式：仅精确匹配（相似度匹配会误判相似背景）
- Room 数据库 `translation_history.db`，version 19，`fallbackToDestructiveMigration`
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
- ⚠️ **版本号只是上面这行，真值以 `TranslationHistoryDatabase` 的 `@Database(...)` 为准**（本行曾落后一版）
- ⚠️ **`rekeyPages`（页序迁移用）必须整体在一个 `@Transaction` 里**（读→删→按新下标重插）：
  中途失败会留下"一半新序一半旧序"的行；也不能逐行 `UPDATE pageIndex` —— 它是主键的一部分，
  置换过程中会与尚未搬迁的行撞键（`REPLACE` 会静默吃掉一行）。调用点在阅读器打开时（见 `mangaimport/CLAUDE.md`）。
- 历史 UI：`ui/history/HistoryFragment`，游戏和漫画均按时间+会话分组显示
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
