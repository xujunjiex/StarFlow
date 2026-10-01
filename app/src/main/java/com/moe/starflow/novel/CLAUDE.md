# 小说模块（`novel/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 小说模块（`novel/`，feature/novel-text-import）

**格式判定先看内容再信扩展名**（`NovelFormatDetector`）：`.txt` 里塞 HTML 的、无扩展名的都要认出来；编码（UTF-8/GBK/Big5）同理按字节判。

### ⚠️ 分页与绘制必须共用同一份 `StaticLayout` 几何（改这一区之前必读）

分页（`NovelPaginator`）与绘制（`NovelPageView`）**读同一批数**：`PageSegment` 存的是**行区间**
（`lineStart..lineEnd`），高度一律取 `getLineBottom(i) - getLineTop(i)`，段高 = 各行之和。
两边各算一套的话底部文字就会被裁掉 —— 三条都踩过：

- 分页用**单字探针**量出「单一」行高再乘行数（估算）→ 某行更高（换字体/表情/全角标点落到别的字体）时每页多塞一行
- 绘制把 segment 的 substring 取出来**重新排版** → 重新断行可能多出一行
- 段间距按 `round(段距/行高)` 折算成**整行** → 不足一行被兜成一行，一页少放一段、底部一大片空白

⚠️ `NovelPageView` 里的 `clipRect` 只是**最后兜底**、不是修复手段；真被切到时打 `NovelDbg: PAGE_OVERFLOW`（含越界像素数）。

### ⚠️ 这一区的单测证明不了什么

Robolectric 的文本引擎是**桩**：`Paint.breakText` 不按宽度换行、行高恒为 `字号 × 行距倍率`。
所以分页刻意做成**纯核心 + Android 外壳**：`paginateByLines` 只收「行起点 + 每行高度」（测试自己合成度量），
`paginate` 才去建 `StaticLayout`。**「真机行高 ≈ 字号 × 1.15~1.5」这一半只能在设备上看日志确认**，
别用单测绿来宣称排版正确。长样本守卫见 `NovelPaginationRealismTest`（样本每章必须能分出多页 —— 早期样本
每章只有几百字节，一屏一页，"翻不动"其实是样本太短）。

### 其他踩过的坑

- **并发加载按序号丢弃**（`loadToken`）：`loadChapter` 被 onCreate/译文到达/改排版/尺寸变化多处调用，不丢弃过期结果会把章节号与页码一起拽回去（现场表现是「翻页卡在第一页、点目录也没用」）
- **阅读模式取值迁移**（`novel_reader_mode_version`）：旧 `1`=滚动，新 `1`=上下翻页、滚动挪到 `2`
- **两个阅读器共用一套约定**：背景/翻页动画/旋转的 prefs 键与漫画共用（`manga_reader`），字号与排版是 `novel_*` 独立；`CurlPageView`、四个 transformer、`ReaderProgressBar` 直接复用 `mangaimport/reader`
- **面板是打开那一刻的快照**（`NovelPanelState` 全是 `val`），宿主改了值必须显式回推，两个方向都只留**一个出口**：
  - 面板内部派生 UI → `refreshDerivedUi`；排版参数 → `bindTypography`（初始填充与「恢复默认」后的回读共用）
  - 宿主 → 面板 → `NovelPanelSheet.notifyHostState(当前章 / 目录标签 / 章状态)`，挂在 `updateChapterTocLabel()` 上（换章、翻页、译文到达、重算统计都会走到）。
    ⚠️ **按字段开推送就是这类 bug 的成因**：早先只有 `notifyTranslateChanged(stats)`，于是「在面板里切章」不刷新；新增宿主字段时必须加进 `notifyHostState` 这一篇，否则漏掉是默认结果
  - 同理别在面板里留第二份数据：`updateSummary()` 曾读快照里的 `state.chapterStats`，而推送改的是 adapter 里那一份 —— 推了也白推

### ⚠️ 段落切分：TXT 正文「一行一段」（`SPLIT_VERSION` 的来历）

`NovelParagraphSplitter.linesToParagraphs`（`TxtParser` / `FolderNovelParser` 调）：按 `\n` 切开、
逐行 trim、空行丢弃、再用空行重组 —— **一行就是一段**。

- **不做这一步，TXT 会被整章切成一个段落**：网文/公版 txt 几乎都是"一行一段、行间不留空行"，
  而 `split` 只认空行分段。整本公版《三国演义》实测：只按空行切 **240 块**（其中 120 块 >500 字、
  最大一块 7295 字），一行一段是 **1703 段**。不修的后果是一串连锁 —— 阅读器里是一堵墙、
  翻译按整章发一个请求（远超模型上下文）、章行分母恒为 1、长按多选只能选到整章、锚点全落同一段
  （用户报的「明明有很多段落却只有 2 段」就是这个）
- ⚠️ **只对 TXT / 文件夹里的 txt 用**：HTML/EPUB 那边单个换行是**段内**换行（`<br>`），
  `HtmlTextExtractor` 已经按空行分好段了，再归一化会把一整段按 `<br>` 切碎
- ⚠️ 代价：**硬换行**的 txt（一行几十字、句子被折断）会被切碎。中文小说几乎不这么排，
  而"一行一段"是压倒性的常态 —— 取舍明确选了它

### ⚠️ `SPLIT_VERSION` +1 之前，先确认清理路径还在

段落切分规则一变，旧 `paraIndex` 就整体错位。⚠️ 译文表主键是
`(novelId, novelKey, chapterIndex, paraIndex)`、**不含 `splitVersion`** —— 旧版本的行占着同一批主键，
新版本的行 `insertIgnore` **静默写不进去**（"补齐分母"与"标记翻译中"全废，且不报错，看不出错）。

- 现存的清理路径：`NovelChapterTranslator.resetStale`（阅读器进入时跑）→ `dao.deleteOtherVersions`
- **当前值已经是 2**（上面那次「一行一段」时 +1 的），所以改切分规则照旧 +1 即可 ——
  别看到版本号已是 2 就以为"动完了不能再动"，也别把上面那句清理路径删了
- 另一条路是把 `splitVersion` 并进主键（要一次重建表的迁移），没做

### ⚠️ 当前状态（2026-09-27）

**已完成**：导入（txt / epub / zip-html / 文件夹）、双阅读器（左右 / 上下 / 滚动）、四种翻页动画、
背景、底部面板、字号与段落参数、面板 ↔ 宿主状态同步、**按批翻译（手动 / 自动 / 增量）**、
**长按多选翻译 / 重翻 / 清除选中段的译文**、章行段数与字数与失败原因、清空本章（二次确认）、
导出译文 / 原文 / 双语、书架。

**翻译口径（别再改错，有测试钉着）**：

- **批 = 连续 N 段**（默认 3，范围 1–10），**不跨章**
- **手动**：当前页第一段没翻的段起，翻**一批**
- **自动**：盯着**当前页**一批批翻，本页翻完就停（等翻页）
- **增量**：自动 + 不受当前页限制，从**当前页第一段**往后翻「向后批数 × 每批段数」段（**滑动窗口**，不是一次性额度：窗口翻完停下，用户翻页窗口自己前移、接着翻；章末即停）
- 面板打开 / 双击翻译按钮 = 暂停并**回退手动**；**退出阅读器只暂停、不改模式**（回来按原模式接着翻）
- 守卫：`NovelBatchPlannerTest`、`NovelTranslationQueueTest`（三种模式怎么推进）

### 批量翻译：章节卡片 + 应用级后台任务 + 并发错峰（2026-10 大改，改动前必读）

> 用户口径：与漫画**同一套**形态 —— 「翻译本章 / 清除本章译文」从面板挪到**每个章节卡片**上、
> 翻译本章要能**后台跑 + 通知栏看进度 + 随时暂停/取消**、排队中的批要显示「等待」、
> 文本可以比漫画更激进（**最多 10 个同时在飞**、错峰 500ms）、单批内容过长得先问一声。

**共享基础设施在 `translate/batch/`（漫画共用，改它之前看 `translate/CLAUDE.md`）**：
`ChapterJobRunner`（准备阶段串行 + 翻译阶段并发 N + 按章暂停/取消/等待）、`TranslationJobRegistry`
（通知栏唯一数据源）、`TranslationJobService`（前台服务，**每章一条通知**）。小说侧的宿主是
`NovelChapterJobHost` + `object NovelTranslationHub`（仿 `ReaderTranslationHub`：应用级 scope、
按 `novelId` 缓存、实现 `ChapterJobSource` 并 `register`、**无 UI 且无任务时回收**并取消收集协程）。

**面板 UI（改了面板就必须跟着改这几处）**：
- **字号层级（用户口径 2026-09-27）**：**筛选 tab 15sp > 章卡片标题 14sp > 页/段行 13sp**；按钮 12sp
- 章行 = **卡片**（`item_novel_chapter_card.xml`）：章标题 14sp 加粗 +
  **两个按钮**（主：`翻译本章 ⇄ 暂停 ⇄ 继续`，实心药丸；次：`清空译文 ⇄ 取消`，淡红药丸）+
  第二行进度/失败/字数 + 展开箭头 + 失败明细面板。**点卡片空白 = 切到该章**（切章只从记录里点）
- **卡片的样子**统一走 `CardBackdrop`（`mangaimport/reader/CardBackdrop.kt`）：
  **浅色面板 = 白底 + 1dp 细描边**（面板本身是白的，灰块会"和整体 UI 不搭"）、深色 = 深灰块 + 亮描边；
  圆角 10dp + 卡片间 4dp 留白 + `RippleDrawable` 水波（自绘底之后 `?selectableItemBackground` 就没了）
- ⚠️ **展开出来的行必须"在章卡片组里面"**：`CardBackdrop.applyNested(...)` —— **方角 + 左侧竖线 +
  上下零间距 + 缩进**（`item_translate_page_state.xml` 的 marginStart=14dp）。只做缩进+同色不够
- **在飞批也是行**：`activeBatches`（宿主 `activeByChapter()`，来自 **`runner.translatingPages()`**）在展开的章里
  逐批显示、**琥珀高亮 + 「翻译中」徽章**，排在「等待」行前面；宿主在**开翻前**也推一次 UI，
  否则 N 个批同时起飞要等第一个返回才看得见高亮。
  ⚠️ 判据是 **`translatingPages()`（真进翻译段）**，不是 `inFlightPages()`（含"已预取还没开始"，
  见 `ChapterTaskStage`）；`waitingBatches` 也相应改成 `waitingPages + queuedPages`
  （2026-09-28 与漫画面板「识别中 / 翻译中 / 等待」三段对齐）
- ⚠️ **`waiting` 属性必须自己 `rebuild()`**：面板推送顺序是「先 waiting、后 active」，而 `active` 为空时
  会提前 return —— 少了 rebuild，"刚排上队的批"要等下一次别的字段变化才出现（`NovelPanelSyncTest` 卡这条）
- ⚠️ **面板里原来的全局 `btn_translate_action` / `btn_translate_clear` 已按用户要求删除**，别再往面板加
- **翻译参数分两层**：**「启动延迟」单独一行**（常显）；**其余四个收进可折叠组「批次处理」**
  （`row_batch_group` + `btn_batch_group` + `batch_group_content`）：组内顺序 = **启动增量翻译向后 →
  每批段数 → 单批预警 → 翻译模型最大同时请求数**，**默认折叠**，展开态存 prefs
  （`novel_batch_group_expanded`，`NovelPanelSheet` 里那一份 prefs）。
  **取值排版统一成「段落」那套**（标签在左、取值贴右同一行，进度条另起一行占满整宽，说明再下一行），
  五项都要有说明文案；所有新控件（含 `tv_debounce_hint`/`tv_ahead_hint`/`tv_batch_hint`/
  `tv_batch_group_title`/`tv_batch_group_arrow`）都**必须**接 `applyPanelTheme`，否则深色面板下不可见
  （`NovelPanelThemeTest` 会拦）
- ⚠️ **「批次处理」组与下面的筛选（历史记录）栏之间留 10dp**（用户口径：说明别紧挨着那一栏）
- 新增宿主字段照旧**两个方向各一个出口**：`NovelPanelHostState`（+`NovelPanelState`）→
  `renderHostState`；面板 → 宿主走 `NovelPanelCallbacks`
  （`onChapterPrimary` / `onChapterSecondary` / `onChapterClear` / `onConcurrency` / `onBatchWarn`）。
  `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ 章卡片**完成态**显示「翻译完成」标记 + 「清空译文」；**只有任务真的在跑/暂停**时才显示「暂停/取消」
  （判据是 `running || paused`，别写成 `job != null`/`jobActive` —— 那会让已完成的章一直显示暂停与取消）

**并发（`NovelTranslationQueue`）**：
- **N = `TranslationConcurrency.novelConcurrency(context, prefs)`**（`novel_concurrent_requests`；
  **本地引擎恒 1**）。⚠️ 滑块必须读写**默认 prefs** —— 阅读器自己那份是 `novel_*`，写错了就是
  "滑块能拖、值也存了，但翻译永远用默认值"的静默失效
- 调度循环：满员（`inflight.size >= N`）时 60ms 轮询**补位**；**防抖只在流水线空着时等一次**
  （每批都等防抖会把 N 个在飞又拖回串行）；`N > 1` 且未满员时按 **500ms 错峰**启动下一批
- `done = 已译 ∪ failedAnchors ∪ userCleared ∪ **在飞批段号**` —— 最后一项是并发的前提：
  不把在飞的批当"已完成"，同一个锚点会被反复挑中 → 同一瞬间发出 N 个一模一样的请求（白烧额度）
- ⚠️ **两份账本 `failedAnchors` / `userClearedParaIndexes` 用并发集合**（N>1 时有多个工人读写），
  且**在飞账本的一切读写都要持锁**：曾出现"调度侧无锁 `sorted()` 迭代 + 工人侧加锁 `removeAll`"
  → `ConcurrentModificationException` 抛在 `launch` 里（scope 无异常处理器 = 崩进程）
- **`OcrLock` 只在 N==1（本地引擎）时整批持有**；N>1（远端 API）**不持锁** —— 那把锁是**OCR 引擎锁**，
  小说没有 OCR，与漫画 `translatePhase` 同口径。N==1 的行为与改造前**逐字一致**（既有 14 条断言原样过）
  ⚠️ 但持锁期间**必须持续打心跳**（`withLockHeartbeat(token)`，5s 一次）：本地引擎一整批可以跑几分钟，
  远超 `OcrLock.STALE_TIMEOUT_MS`(30s)，不打心跳会被自愈机制判成"持有者已死"而**强制放锁** →
  另一个翻译任务（另一本书的章节任务 / 漫画阅读器 OCR / 超分）随即拿到锁，**两个线程同时用同一个本地引擎**。
  ⚠️ 心跳协程写 `launch { }` 继承调用方派发器，**不要写 `launch(Dispatchers.IO)`** —— 那会把真实线程池的
  非确定性带进用 `runTest` 虚拟时钟写的 `NovelTranslationQueueTest`（实测 14 例里挂 10 例）。
- 暂停 = 只停取批、队列留着（=「等待」行）；取消 = 丢还没开始翻的批、**已翻好的保留**；
  **在途批「强制结束」**（`stop()` → `job.cancel()`：协程取消，译文只在成功后写库 ⇒ 取消不入库、也不等它
  —— 与漫画「打开面板/退出阅读器」的强制退出同一套，2026-09-28 核对过）。
  「等待」= `runner.waitingPages` + `queuedPages`（章任务）为唯一数据源，不要拿"提交数 − 完成数"推

**单批超长预警**：`NovelBatchWarning`（档位 `2048/4096/8192/16384/32768`，默认 **4096**；
`utils/TokenEstimator` 粗估 token）+ `NovelBatchWarnGate` / `NovelOversizeConfirmer`
（**同一轮只打扰一次**：用户对一批说了"取消"之后，同一轮里后续超长批静默跳过）。
判据收敛在 **`NovelChapterTranslator.translateBatch` 一处**（翻译本章 / 多选 / 自动 / 增量全汇到这里，
不会漏路径）；⚠️ **后台任务（阅读器已关）没有 UI 可弹窗时必须放行**，否则任务会静默停在一半。

**暂停提示**：退出阅读器 / 打开面板 = **强制退出在途翻译 + 回退手动** → 文案
`reader_translate_force_stopped_to_manual`（「翻译进程已强制退出，回退到手动模式」）；**章节任务取消**
（本来就手动）→ 只报 `reader_translate_force_stopped`（用户口径 2026-09-28）。提示在
**`onStop` 判 `isFinishing`** 时发（`onDestroy` 里弹的 Toast 常被系统吞掉，这正是用户报"没有提示"的根因），
`onDestroy` 只兜底 + 防重复（与漫画同一套）。
⚠️ `pauseToManual` **不再动章节任务**（那是后台任务，暂停前台队列不该打断它）。

**守卫**：`NovelTranslationConcurrencyTest`（错峰铺开 / 满员不发 / 结束补位 / N=1 全程单飞 /
并发下失败批不重挑）、`NovelBatchWarningTest`、`NovelChapterTranslatorTest`（预警三条路径）、
`NovelPanelSyncTest`、`NovelPanelThemeTest`、`NovelBatchPlannerTest`、`NovelTranslationQueueTest`。

**几条结构性约定（踩过才有的）**：

- **面板状态只有一个入口**：`NovelPanelHostState`（宿主能改、面板要显示的全部状态）+
  `NovelPanelSheet.renderHostState`。新增这类字段**必须同时加进类型和 render**，
  否则那处 UI 永远停在打开那一刻 —— `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ **一个控件只能出现在两个配色清单之一**（`panelLabelIds` / `panelSubIds`，`intArrayOf` + 标记注释）：
  两段 `setTextColor` 后写的赢 → 同一个 id 两处都登记会**静默变淡色**。`PanelThemeGuardTest` 机械守卫。
- **面板主题也只有一个入口**：`NovelPanelSheet.applyPanelTheme`（深色/浅色随**阅读背景**，不随全局主题）。
  它是手写控件清单，**清单外的控件不会跟着切背景变** —— Tab 图标、筛选 chip、两个弹窗都因此漏过，
  表现是「切了背景有几处颜色不动，关掉面板再打开才正常」。新增控件组必须接进去，
  `NovelPanelThemeTest` 会拦（把修复行注释掉它就会红）
- **报错必须带原始原因**：引擎不能吞 `TranslationResult.Error`，要带出异常类型 + message 的
  cause 链 / 模型返回了什么（`NovelBatchResult.error` → `failCode` → 章行展开里原样显示）
- **无章节标记的 TXT = 整本一章**（不再按 8000 字硬切）
- 翻译按钮旁的**三态切换按页显示**：当前页有译文才出现
- **「保持段落完整」默认关**（排版面板里的开关）：关掉 = 按间距填满页面，段落可被切断
- **右下翻译浮层组的判据全部在 `NovelTranslateChrome`**（纯逻辑 + 单测），别再散在 Activity 里：
  - **翻译按钮**：当前视野还有没翻的可翻译段才显示（翻页 = 本页；滚动 = 当前屏幕可见段）。
    ⚠️ 滚动模式**不要**按整章判 —— 那就成了"始终显示"，用户明确否掉了
  - **三态按钮**：当前页（滚动模式 = 当前屏幕**可见段**）有译文才显示 —— 没译文时点了看不出变化。
    ⚠️ 判据变了要记得**重算**：`onPaged` 与 `updateFromScroll` 都要调 `refreshTranslationChrome`
    （早先只在 `loadChapter` 里调，于是翻页后浮层停在上一次的状态）
  - **「可翻译段」= TEXT 且非空白**（`isTranslatable`）：图片与 <4 字的短行按设计永不翻译，
    算进「待翻译」会让含 `……` 的页永远判不出"翻完了"、翻译按钮常驻且点了没反应
  - **没有「缓存命中」这一套**（与漫画相反）：不允许凭「本章/本页已有译文」把按钮变成重翻图标，
    否则"点一下会发生什么"取决于看不见的状态
- **重翻 = 长按多选段落**（文本阅读器唯一的重翻入口）：长按进选择模式 → 点段落加减选 →
  点空白/返回键退出（切章、开面板、切阅读模式、离开阅读器也清；**翻页不清**）。
  按钮语义随选中集变：全未翻 = 翻译 / 全已翻 = 重新翻译 / 混合 = 翻译+重翻（左侧文案写明 + 段数）。
  翻译时按面板「每批段数」拆成多次请求、逐批上屏；队列走 `translateExact`（**不过规划器** ——
  规划器会把已有译文的段当已完成跳过，那样重翻永远翻不动）
- **清除选中段的译文**（与长按多选**共用同一份选中集**）：选中集里**只要有已翻的段**，右下角就出现
  回收站图标（判据收在 `NovelTranslateChrome.showClear(translatedSelected) = translatedSelected > 0`）→
  弹窗报「将清除选中的 N 段译文」（N = 选中**且已翻**的段数）→ 确定后删库 + 重载本章，
  那几段立刻回原文显示，三态/翻译按钮按新状态重算
  ⚠️ 清过的段要进 `NovelTranslationQueue.userClearedParaIndexes`，而 **`start()` 绝不能清这份账**：
  自动/增量盯着「当前页有没有没翻的段」，不记账就是清完**立刻又翻回来**（用户看到的"清了个寂寞"）。
  它与 `failedAnchors` 的唯一区别就是清除时机（`start()` 清后者，只有用户明确要求重来
  —— 即「翻译本章」`translateWholeChapter` —— 才两份都清）→ **别把两个集合合并成一个**。
  显式操作（点翻译 / 翻译本章 / 选段重翻）不受这份账影响。守卫：`NovelTranslationQueueTest`
- **阅读位置是 `NovelAnchor(段号, 段内比例)`，不是段号**（`NovelAnchors`）：
  一段能跨好几页（只按空行分段，整章一段的样本就是），只按段号恢复会落到**段首那一页/段顶** ——
  译文到达必须重排，重排一次就把读者从段中间拽回段首（用户报的「翻译之后位置跳变」）。
  翻页模式锚点取本页第一个 segment 的段内比例、恢复时找**包含**该比例位置的那一页；
  滚动模式锚点记「首可见段已滚过多少」、恢复时 `scrollToPosition` 后再按新高度补偏移。
  ⚠️ `NovelChapterRepository.pageOfParagraph` 给的是「含该段的**第一页**」，**不要**拿来恢复阅读位置
  （断点续读落盘仍是段号，比例从段首起算）
- **锚点同时是「强制分页点」**（`NovelPaginator.paginate(anchor=…)` → `forceBreak`）：
  `pageOf` 只能给到"含锚点的那一页"，锚点可能落在**页尾** —— 译文比原文短时它被挤回**上一页**，
  读者看到的就是「翻译完回到前一页」（用户报的，`NovelAnchorPageFlowTest` 复现）。
  所以带位重排时必须让锚点那一行**另起一页**：重排前后屏幕第一行是同一行。
  锚点要进 `NovelChapterRepository.load` 的缓存键（不同锚点切出不同页表）；
  滚动模式与跳到章末不传。⚠️ 行号必须由**同一份 layout** 反查（`getLineForOffset`），不能自己除。
  ⚠️ **锚点之后正着填、锚点之前倒着填**（`paginateAround`）：只"提前收页"的话锚点前一页只剩小半页、
  底部一大片空白（用户报的「有的页面提前分页、底部预留大片空白」，双语↔译文/原文来回切最明显）。
  锚点本来就在页上半部分时**不强分**（`shouldForceBreak` 阈值）——那时强分没有收益，只白白扰动页表
- **带位重排期间锚点必须钉住**（`NovelReaderActivity.carryAnchor`）：
  `pageOf` 只能给到**页**（向下取整），落位后 `onPaged` 若把锚点重取成"这一页的页首"，
  页首永远比逻辑位置靠前 → **每批退最多一屏、误差逐批累积、单向往回跑**（实测 5 批丢一半位置）。
  只有用户真导航（滑动/点按翻页/拖进度条/切章/换模式）才允许重取；
  ⚠️ 不能"落地后清"——ViewPager2 同一帧末还会补派发一次 `onPageSelected`，那次会把锚点打回页首。
  `refreshTranslations` 取锚点要在**读完译文之后**（挂起前的锚点会因 IO 期间翻页而过期）。
  ⚠️ 附带纠正：ViewPager2 对 `notifyItemRangeChanged` 与 `notifyDataSetChanged` 一视同仁，
  都不会弹回第一页；真正的坑是**重排后页数缩到当前页号以下**会重置到第 0 页 → 刷新页表后
  必须紧跟 `setCurrentItem`（另有 `snapPagerToAnchor` 兜底）
- **「本章翻完了吗」的分母 = 整章可翻译段数**：SQL 那边是 `COUNT(*)`（数据库里这一章有几行），
  而行是**按批惰性写的** —— 只写这一批的话分母退化成"翻过的段数"，翻了几段就把整章标成
  「已翻译」（用户报的「没翻完却显示全部完成」）。所以 `NovelChapterTranslator.translateBatch`
  开翻前先 `ensureChapterRows` 把整章可翻译段落补成 IDLE 行（`insertIgnore` 幂等），
  章行/筛选再用宿主解析出的真分母（`NovelChapterStateAdapter.totalOf`）而不是库里行数
- **「本章多少字」同理，取整章可翻译段的字符和**（`NovelChapterRepository.charCountOf`，按
  `SPLIT_VERSION` 缓存）—— **不是库里已有的行数**（行是按批惰性写的，拿它当"本章多少字"会随翻的
  进度一起长）。`ensureChapterTotal` 里与分母**一次取回**（同一份段落缓存命中，不额外解析一遍），
  显示在面板章行第二行的「已翻 3/40 · 12,345字」里（`novel_chapter_progress_chars` 拼，中文无空格；
  分母或字数取回 0 时整行回落成只有进度）—— 用户要它是为了估翻译费用
- **增量 = 跟着当前页走的窗口**（不是一次性额度）：窗口 = 当前页第一段往后
  「向后批数 × 每批段数」段；用完停下，用户**翻页窗口自己前移**接着翻（`aheadLimitPara`）。
  面板增量行显示换算后的段数（"3 批 = 15 段"），改批数或每批段数都要重算（走 `refreshDerivedUi`）
- 状态芯片显示**翻到哪一页/哪一段**，不显示第几章（增量会一直往后翻，章号看不出进度）；
  且 DRAINED 不再喊「已翻译」—— 走到 DRAINED 有四种原因（翻完本页/翻完窗口/本章真翻完/一批失败被跳过）
- **滚动模式进度条按「像素比例」算，不按段序号**（`NovelScrollProgress`）：
  段落按空行切，一个几万字的章节可能**整章只有一段**（样例 `英文-单段超长-无空行.txt`），
  按「第几段/共几段」算的话比例恒为 0/1 → 进度条永远不动（用户报的「章节文字太多时进度条失效」）。
  位置取 `computeVerticalScrollRange/Offset/Extent`；绿条仍按段序号的**等分切片**（段高要测量才知道，
  滚动模式是懒加载的）。拖拽寻页也按比例滚（`seekScrollTo`），**循环补齐**：估算总长会漂，
  一次 `scrollBy` 未必吃到目标。⚠️ 程序化 `scrollToPosition` **不派发 onScrolled**，
  续读要靠 `novelScroll` 的 layout listener 补刷 —— 那个 listener 必须带「首可见段变了才刷」的闸门，
  否则 `setText → requestLayout → 布局 → 再补刷` 是每帧一次布局的死循环。
- **双语模式段间距 ×1.8**（`BILINGUAL_PARA_SPACING_FACTOR`，在 `NovelPanelStyle.textStyle` 里乘）：
  一段双语 = 原文行 + 译文行，行间只是一个普通行距；段间距不拉开就分不清"这行译文属于哪段原文"。
  ⚠️ 只作用于排版（分页与绘制共用同一份 style），**不改用户设置里那个 dp 数字**
- **「正在翻译 / 刚翻完」的段有琥珀高亮**（`NovelTextRenderer.COLOR_ACTIVE_BATCH`，与选中的蓝区分开）：
  译文到达必须重排、位置总有轻微偏移，高亮让用户偏移后还能找到刚翻的地方。一批翻完**不清**，
  只在换章 / 清空本章译文 / 换阅读模式时清。高亮底两种模式都**铺满整行 + 8dp 圆角**
  （`HIGHLIGHT_CORNER_DP`）——只铺正文列会像被两侧裁了一刀
- **三态图标是自绘的** `ic_display_{translated,original,bilingual}`：
  ⚠️ 不得与翻译按钮的 `ic_reader_translate` **同形**（同一浮层组并排显示，用户会直接说"图标重复"）；
  ⚠️ 不要去描汉字字形（描「文」在 22dp 下看着就是「六」，用户问过"这什么玩意"）。
  三枚用同一套「文字块」语言：原文=多行细行 / 译文=一行粗行+一行短行 / 双语=上下两块都画
- **默认行距 1.4×**（原 1.5×）：⚠️ 只改常量对已用过的人无效（prefs 里存着旧默认值），
  所以 `migrateLineSpacing` 会把"还停在旧默认值"的人一起挪过来，自己调过行距的不动
- **段距与字号/行距完全解耦**（用户明确要求）：`paragraphSpacingDp` 只夹在固定的
  `PARA_SPACING_ABS_MIN..PARA_SPACING_MAX`，面板的 `sb_para_spacing` 也**只设固定 min/max**。
  ⚠️ 别把「段距必须大于行距」的动态下限接回来 —— 那样面板要按字号/行距动态改 SeekBar 的 min/max，
  而 SeekBar 会把 progress **夹到新区间**，用户一调字号或行距、段距滑块自己就跳走了
  （用户报的「调字号或行间距，段间距的进度条也会一起变化」）。行距拉大时段距显得密是用户看得见、
  拖一下就能改的事，不该由系统偷偷改他设的值。守卫：`NovelPanelStyleTest` 的
  「改行距不动段距」「改字号不动段距」
- **导出落点 = 手机的 Download**（`MediaStore.Downloads` + `RELATIVE_PATH=DIRECTORY_DOWNLOADS`，
  与漫画 `MangaReaderActivity.writeToDownloads` 同一个落点、同一句提示 `toast_saved_to_download`）。
  ⚠️ 不要写回 `getExternalFilesDir()`：那在 `/Android/data/<包名>/files/` 下，Android 11+ 的文件管理器
  进不去、用户拿不到导出的书（而且卸载即删）。重名交给 MediaStore 去重（会写成 `名字 (1).txt`），
  写完回读**实际**文件名再提示。拼字节那一层拆在 `NovelExport.writeBook`（可单测），
  MediaStore 那层只能真机验（Robolectric 没有 MediaProvider，`insert` 返回 null）
- **模型切换要立刻生效**：队列把引擎**按值缓存**了，设置页只写 prefs —— 所以阅读器监听默认 prefs
  （`NovelEngineConfig` 判定哪些键算引擎配置），变了就丢掉缓存的队列，下次用到时重建。
  ⚠️ 别改成「每批现造引擎」：本地引擎（NLLB）每造一次都要重载模型。监听要注册在**阅读器**里而不是面板里
  （改模型要先离开面板去设置页，面板回来时重新 onStart 才注册，那一刻已经错过变化）

**没做**：真机上端到端跑一次翻译（我只跑到单测 + 装机，实际翻译效果要用户验）。

样本：`tools/gen_novel_fixtures.py --repo` 生成英/日/韩长样本（每章 100 段，固定种子可复现）。
⚠️ **样本必须是被翻译的那一侧语言** —— 中文样本测「翻译成中文」等于什么都没测，
`NovelPaginationRealismTest` 里有语言占比守卫卡这件事。
