# 漫画导入书架 + 阅读器（`mangaimport/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 漫画导入书架 + 阅读器（`mangaimport/`）

**书架（`ImportMangaFragment`）**：
- 存储固定 `getExternalFilesDir()/manga_import/<id>/`（zip 放原文件 / 目录放散图；封面在 `getExternalFilesDir()/covers/`），**在 Android/data/<pkg>/files 下，用户可经文件管理器/数据线访问——绝不允许存进不可访问的 `filesDir`（/data/data）**；**无自定义目录可切**；根目录由 `StorageDirStore.root()` 提供，封面目录 `StorageDirStore.coversDir()`
- 一次性迁移 `StorageDirStore.migrate()`：把旧版存于 `filesDir` 的漫画/封面搬到外部专属目录并重写清单路径（Fragment 启动时后台执行，幂等）；Manifest 已移除废弃的 READ/WRITE/MANAGE_EXTERNAL_STORAGE（漫画导入走 SAF，无需存储权限）
- 导入即复制（SAF 源 → 复制进 app 内部存储）；封面**唯一命名** `covers/<id>_<ts>.jpg`（每次导入/换封面都新路径，防 Glide 按路径缓存导致删除重导后显示旧图）；重导前清理该 id 旧封面
- `ImportedManga` 字段：id/title/localRoot/isArchive/coverPath/pageCount/addedAt/sizeBytes/description(简介)/lastReadPage/lost(文件丢失标记)；JSON 存 SP `manga_import`
- 长按**多选管理**：已读/未读（改 `lastReadPage`）/删除（连同 `filesDir` 副本+封面）/重命名/换封面（photo picker 复制进 covers）/编辑简介；顶部「全选」图标按钮 + 返回键退出多选
- 下拉刷新 `SwipeRefreshLayout`；显示选项**即时生效**（详细信息列表 = 默认 / 网格，列数 = 网格尺寸；紧凑网格与纯列表已删除）
- ⚠️ 简介/重命名确认后再 `refresh()`（曾在弹窗打开时就 refresh 导致改了不显示）
- **文件丢失标记 `lost`**：书架刷新时按 `localRoot` 是否存在**实时推导**（瞬态，`toJson` 不持久化、加载回恒 false）；卡片/行显示「文件丢失」徽章 + 封面压暗（`alpha 0.35`）；书架/阅读器入口拦截丢失条目（`reader_file_lost` 提示），`ReaderPageSource` zip 缺失/损坏不崩溃（回退空列表由阅读器提示）

**阅读器（`MangaReaderActivity`）**：
- 存储统一走 `ReaderPageSource`（zip=`ZipFile`/目录=文件枚举；解析页 + 全图 + 缩略图 `LruCache`），`ReaderAdapters` 两种渲染：单页 `ViewPager2`(横/竖/竖排)、**Webtoon 连续滚动**（竖 `RecyclerView`）。⚠️ **横屏双页已移除**（`DoublePageAdapter` + `item_reader_double_page.xml` 已删）：横屏与竖屏同为单页，翻译逻辑不再受朝向影响
- Webtoon 图片按屏宽**采样解码**（`loadWebtoon` + `computeSample` 限高比 ~12:1 / 限单幅像素 ~48MB）+ 像素预算 `LruCache`（~64MB，防大漫画滚动卡死/OOM）；zip **复用单例 `ZipFile`**（免每页重读中央目录，数百页漫画关键开销）；`peekWebtoon` 主线程安全同步解码兜底下一页（翻起区不黑屏）
- **阅读模式**（prefs `manga_reader` 的 `reader_mode`）：0=LTR 1=RTL（页码不变，页面/滑动手势/点击分区反转）2=竖排 3=Webtoon
- 竖排模式 = **竖向整页 `ViewPager2`**（`ORIENTATION_VERTICAL`），`handleTap` 点上半=上一页/下半=下一页
- **翻页动画**（`reader_animation`）：0无=`NoneTransformer`（**全程静止钉住当前页**，点击/滑动/自动一律无过渡、落整瞬间换页）1默认=null(ViewPager2 滑动) 2高级=`CoverTransformer`(封面叠放) 3仿真=`SimulationTransformer`+`ReaderCurl.kt` 的 `CurlPageView`(页脚卷曲、镜像纸背)；`configChanges` 声明旋转不重建
- **单击分区**（`MangaReaderActivity.handleTap` / `webtoonTapToPage` 共用 `consumeChromeOrMenuTap`）：**屏幕正中间一格 = 显隐上下 UI** / 右上角（y<22% 且 x>72%）= 菜单 / 其余左右（竖排模式为上/下）半屏 = 翻页。中间格的边界是 **Koto 九宫格的中格**（横竖各三等分：`w/3 ≤ x < 2w/3` 且 `h/3 ≤ y < 2h/3`）。⚠️ 只有 `GestureDetector.onSingleTapConfirmed` 才走这套判定——**滑动翻页触发 ACTION_CANCEL、双击缩放走 `onDoubleTap`，都不会误触**；翻页区也都在中格之外
- **显隐上下 UI**（`chromeHidden` + `applyChromeVisibility`）：顶部返回/菜单/页码 + 底部进度条 + 翻译浮层组，**淡入淡出**（`CHROME_FADE_MS=160`，逐控件 alpha 动画，跟随系统动画时长缩放）。**状态一直保持**（无自动恢复计时，再点中间才恢复），只在本次会话内有效、不持久化。⚠️ 淡出**必须在动画结束后才置 GONE**：只把 alpha 打到 0 的控件**仍然可点**（会误触到看不见的返回/菜单按钮）；同理 `withEndAction` 要加 `v.alpha == 0f` 兜底（动画被打断时 end action 也会跑）。⚠️ 翻译浮层组的可见性另有 Webtoon/未译页约束 → 显示前先调 `refreshTranslationChrome()` 定到位，**只把 `visibility == VISIBLE` 的组一起淡入**（否则会把 Webtoon 下本该隐藏的组强行淡出来）；`refreshTranslationChrome()` 刷新时继续尊重 `chromeHidden`
- **背景**（`reader_background`）：0默认 1浅 2深 3白 4黑 5自动（0/5 都跟随系统黑白，5 是显式「自动」项）；切背景时**面板/进度条/页面指示文字即时深浅联动**
- **颜色矫正**（`ReaderColorFilter` ColorMatrix）：反色/灰度/书本开关 + 亮度/对比度滑块，调色面板顶部「**原图 vs 处理后**」对比预览实时刷新；持久化两滑块+三开关（`reader_color_*`）
  - ⚠️ 三个开关**行内上下 padding 只有 2dp**（用户口径 2026-10-01 两次要求「间隔再小一点」：
    先 12dp→6dp、再 6dp→2dp，实际开关间距 40px→13px）。改大之前先确认是不是又要返工
  - ⚠️ **护眼（开关组最后一个）与亮度（滑杆组第一个）之间有一条 1dp 分割线**
    （`#11000000` + 左右 16dp 缩进，用户口径 2026-10-01）：两组都是"改画面"但控件形态不同（开关 ⇄ 滑杆）
- 底部**透明胶囊进度条**（`ReaderProgressBar`）：左◀右▶ + 中间细线（**拖拽寻页 / 长按 → 3 列缩略图预览网格跳页**）
- 底部工具栏四图标 Tab：**翻页 / 翻译 / 调色 / 更多**；更多=旋转(竖/横/自动)+自动翻页(触摸、失焦自动暂停)+原文下载(zip→`MediaStore.Downloads`)+设置(跳 `SettingPageActivity` 个性化)；右下角**翻译浮层组**（翻译/重翻按钮 + 三态切换，见「阅读器内嵌翻译」）；右上角 ⋮ 菜单键（白图标）
- **三种打包下载**（更多 → 下载；全部走 `MediaStore.Downloads`，临时 zip 在 `cacheDir`；三态共用一个 `exportJob` 槽位，导出中再点提示「正在导出中」）：
  | 入口 | 内容 | 包内命名 |
  |------|------|---------|
  | 原文（已有） | 全部页 | archive 直接复制原 zip；目录包扁平名 |
  | 译文 | **只有已成功翻译的页**（失败/未译不导出） | `<原目录>/<原名主干>.jpg`（统一 JPEG 95） |
  | 双语 | 每个已翻译页**两个条目**：原文原文 + 译文 | 原文用**原名原扩展名**，译文 `<主干>_译文.jpg`，同层混放 |
  - ⚠️ **命名一律沿用原压缩包的序号**，绝不按「第几个已翻译页」重编号：原包 1..10 只翻了 1/2/5/6 → 导出就是 `001/002/005/006`，不是 `1/2/3/4`。规则收在 `ExportNaming`（纯函数，`ExportNamingTest` 12 例覆盖）
  - ⚠️ **条目名必须保留原目录前缀**（`ch1/005.jpg` → `ch1/005.jpg`）：zip 里重名会让 `ZipOutputStream.putNextEntry` 抛 `ZipException: duplicate entry`，**整个导出直接失败**（子目录漫画 `ch1/005` 与 `ch2/005` 会撞）
  - ⚠️ 双语包的原文条目走 `ReaderPageSource.openEntry` **原样搬运字节**，不能 `loadFull` + 重新编码 —— 原图多是 JPEG，解成 Bitmap 再压回去是二次有损压缩（掉画质、体积还可能更大）
  - ⚠️ 译文图渲染用 `ReaderTranslationController.renderForExport`，**不写 `renderLru`**：导出动辄上百页，走 `visualBitmap` 会把 100MB 渲染缓存一路冲干净，连用户正在看的那页译图一起挤掉；`renderOverlay` 开头就 `copy()` 出独立副本 → 源图渲染完 `finally` 里立刻 `recycle()`（逐页进行，别攒内存）
  - 进度走 `TranslationStatusOverlay.showImmediate`「正在导出 %1$d/%2$d…」（受 `status_overlay_enabled` 开关控制，关闭时静默），结束 `dismiss()` + toast 落盘结果
  - ⚠️ **文件名后缀走 strings**（`reader_export_suffix_translated` / `reader_export_zip_suffix_*`），不硬编码中文：英文界面下是 `_translated` / `-translated.zip`
  - ⚠️ **条目名必须去重**（`ExportNaming.uniqueEntryName`，撞名加 `_2` 后缀）：「谁先写谁保名」。撞名不是假想 —— 同目录 `005.png` 与 `005.jpg` 译文都映射成 `005.jpg`；双语包**再导入当新书读**时原图那半 `005_译文.jpg` 会和本次生成的译文名撞上。撞了就是 `ZipException: duplicate entry` → **整包导出失败**
  - ⚠️ **先渲染译文再写任何条目**：反了的话，渲染失败会在双语包里留下一张没有 `_译文` 配对的孤儿原图（用户以为这页翻译丢了）；跳过的页要计数并如实告知（`ExportOutcome.Done(written, skipped)`），一页都没成功按「导出失败」处理，不能把空包当成功交付
  - ⚠️ 导出异常必须 `LogCollector.e` 落盘（重名/磁盘满/渲染异常现在长得一模一样），且 **`CancellationException` 要重抛**（协程取消不是「导出失败」）
  - ⚠️ 临时 zip 的 `delete()` 必须在 `finally`：导出中途退出阅读器会取消 `lifecycleScope`，否则几百 MB 残留 `cacheDir` 无人清
  - ⚠️ **导出进度只在「前台」显示**（`lifecycle.currentState.isAtLeast(STARTED)`）：状态浮层是进程级 `TYPE_APPLICATION_OVERLAY` 窗口，而导出是**跨 onStop 继续跑**的任务（翻译队列 onStop 会暂停，导出不会）→ 后台再 `showImmediate` 会把「正在导出」芯片贴到别的应用上挂到导出结束
  - ⚠️ `overlay.dismiss()` **只在真的显示过时才调**：共享单例的 `dismiss()` 清空**全部**堆叠消息，开关关闭时只会误伤同时在显示的翻译状态芯片
  - ⚠️ `writeToDownloads` 的 `contentResolver.insert` **必须在 try 内**（MediaProvider 不可用会抛 SecurityException/IllegalArgumentException，协程里抛出去 = 进程崩溃），写失败还要 `delete` 掉那条空行，否则下载目录留 0 字节幽灵文件
  - ⚠️ 原文导出别把 key 扁平化成文件名（`substringAfterLast('/')`）：目录导入的子目录漫画 `ch1/001.jpg` 与 `ch2/001.jpg` 会撞名 → 整包失败；用原 key
  - ⚠️ **`pendingRealignPage` 必须在 `applyPager()` / `goToPage()` 清空**：Webtoon 下 `viewPager` 是 `GONE` → 它的布局回调**永不触发**，没人清；等用户切回左右/竖排时首次布局就命中「有 old 尺寸」分支 → 把阅读位置拽回**旋转那一刻**的页，还会写回 `lastReadPage`
  - ⚠️ **淡出的 160ms 内控件仍在屏上（alpha 渐到 0）且仍可点**：`fadeOutChrome` 一开始就 `setChromeInteractive(v, false)`（递归 `isEnabled`）——不能等动画结束才 `GONE`，否则这段窗口点到的是看不见的返回/菜单/翻译按钮。⚠️ 用 `isEnabled` 而不是 `isClickable`：恢复时把 clickable 一律置 true 会让原本**不可点**的层开始吞事件，反而挡住底下翻页器手势
  - ⚠️ MediaStore 的 `DISPLAY_NAME` 不能含控制字符、也不能为空（`safeTitle()` 过滤 `\p{Cntrl}` + 兜底名），否则 insert 直接失败表现为「导出失败」
- Manifest 已 `supportsRtl="true"`
- ⚠️ **仿真动画不要叠「蒙版/tint」**：CurlPageView 背页曾叠 `0x55` 半透明黑 + `26` 白 + `revealedShade` 整页灰罩，白底漫画翻页在空白区（上下页边）闪灰——已全部移除，只留镜像内容 + 薄折痕线。给翻页加阴影要用内容级处理，别加整页/整区 tint。
- ⚠️ **回翻判定 `isBackward` 必须用相对锚点**（`marker < animState.anchorPage`），不能用瞬时增量（`marker < prev`）：后者用户中途反向/松手即翻转 → 折叠镜像瞬间跳另一边；相对锚点只在 marker 越过 anchor（折叠归零、不可见）时才翻转 → 反向平滑。
- ⚠️ **换翻页动画前必须复位残留 transform**：`MangaReaderActivity.resetPageTransforms()` + `ReaderAdapters.resetItemTransform`（onBind 时），否则旧动画的 alpha/缩放/旋转/折叠粘在回收页上 → 堆叠/黑屏。

**阅读器内嵌翻译（`mangaimport/translate/`，feature/reader-translation）：**
- ⚠️ **在阅读器里跳「个性化设置」返回后必须 `recreate()`**：那一批参数（字号/颜色/字距行距/竖排方向/合并开关）是创建时读进缓存并被已渲染的译图固化的，就地刷新既漏项又容易只改一半。现场经 `onSaveInstanceState` 带过去（当前页 / 翻译模式 / 「这次是从设置返回」标记）。⚠️ 恢复模式的判据必须是 `restoredTranslateMode >= 0`（-1 = 不是从设置返回触发的重建）；写成 `!= MODE_MANUAL` 会把 -1 当模式塞进控制器 → **一进阅读器就自动开翻**（踩过，`ReaderMenuSheetTranslateTest` 有源码级守卫）
- ⚠️ **阅读器面板（`ReaderMenuSheet`）新增任何控件必须做两件事**：① 接 `applyPanelTheme` —— 面板**不随全局主题**，按 `darkPanel`（跟阅读背景）翻转；「标签」进 `labelColor` 组、「值」进 `subColor` 组，**两组都要登记**（漏登记的控件在深色面板下保持布局默认深色字 → 压在同色背景上看不见）；② 字号与同面板邻居一致（说明行 12sp、正文行 14sp），**谨慎挂浅色背景 drawable**（如 `bg_preview_cell`，深色面板下就是一块白底）。文案一律双语（`values/` + `values-zh/`）。回归守卫：`ReaderMenuSheetTranslateTest`（面板底色 vs 文字色亮度差、与 OCR模型行字号相等）
- **译文字号只有一个来源**：`utils/MangaFontSize.kt`（`PRESET_SIZES` 档位 / 自动 / 上下限都在它）。三个入口**都必须调它**：个性化设置页「字体大小」（**最初始的位置**）、悬浮窗菜单「字体大小」、阅读器翻译面板该行。直接写 `Manga_Font_Size`/`Manga_Auto_Font_Size` 的旧写法已收敛（游戏那套是独立的 `utils/CustomFontSize.kt`，两套**不要混用**——游戏是浮层 TextView 的 sp，漫画字号参与排版计算）。服务侧 `watchedKeys` 已含这两个键，改完实时生效
- **三种翻译模式**（`ReaderTranslationController`）：`translateMode` = 0 手动 / 1 自动 / 2 增量。**每次进阅读器默认手动**，不持久化。自动与增量**共用同一个串行队列引擎**，只有窗口大小不同（自动 = 1 页；增量 = `[当前页, 当前页+N)`，N=1-10）
- **队列引擎**：每轮 `delay(debounceMs)`（默认 500ms，面板可调 200-2000ms）→ 重读当前页 → 重算窗口 → 取窗口内第一个 `IDLE` 页 → 串行翻译。**翻页不重启队列**（窗口自动跟上）；正在翻的那页不被打断。⚠️ **跳过 `SUCCESS` 与 `FAILED`，只翻 `IDLE`** —— 不跳失败页会让内容性失败（空白页 OCR 为空）被每轮重挑 → 无限重试
- ⚠️ **队列启动前必须查 `OcrLock.isRunning`**：`runTranslate` 拿不到锁会直接 return（本页没翻），而下一轮又会重选到同一个仍是 `IDLE` 的页 → **每 500ms 空转、永远翻不动也永远不报"队列耗尽"**。现在改为原地等待并提示「等待其他翻译任务完成…」
- **只在"正在看的那页"渲染上屏**；后台队列页只写库 —— 否则预翻 100 页要塞 100 张全页图进 100MB 的 `renderLru`。判定用 `page == currentPageProvider()`
- **增量模式禁用分批与流式**（`incrementalEnabled = mode != MODE_AHEAD`）：翻的是用户没在看的页，"先出一部分"没有观众
- **按钮交互（三模式统一）**：**单击只提示，绝不打断**；**快速双击（300ms 内）**才强制取消并回退手动。提示文案按 `queuePage >= 0` 分两种：正在翻 → 「正在翻译 P%1$d 页，双击暂停翻译」；没在翻（队列耗尽/防抖等待）→ 「请双击退出增量翻译后重试」。⚠️ 判据是**有没有正在翻的页**，不是"模式 != 手动"，否则用户会看到一条永远不动的假进度
- **翻译面板开合暂停**：打开面板 → `setPanelOpen(true)` → **强制退出在途翻译**（取消协程，产物不入库、不等待）
  + **回退手动** + 提示「翻译进程已强制退出，回退到手动模式」；关闭面板 → 恢复队列。
  即"选了自动/增量也要等退出面板才开始翻"。`onDestroy` 同样停止并回退
- **队列耗尽**：窗口内无可翻页 → 发 `ReaderTranslatePhase.QUEUE_DRAINED` → 顶栏显示「翻译队列已耗尽，翻页后继续」后消失；翻页经 `onCurrentPageChanged` 自动重启队列
- **状态浮层带批次与页码**：分批时用管线传来的文案「识别中（1/2）…」「翻译中（2/2）…」（与截屏翻译对齐），后面拼 `· P%2$d`。批次判定靠 `onBatchResult` 置 `batchIndex=2`（管线回调只给 resId，不带批次）。队列页的**检测/翻译阶段照常上报**，只有成功/失败静音（否则连翻 10 页会弹 10 次"翻译完成"）
- **管线复用**：分批走 `manga/pipeline/IncrementalBatchPipeline`（与截屏翻译同一套）；非分批走 `TranslationEngineInit.ensureReady` → `DetectionBridge.runOCR → ocrToBubbleRegions` → `TranslateUtils.translateBubbles`
- **`ReaderTranslationController`**：三态、每页记录写库、`OcrLock` 互斥、渲染缓存 `renderLru`（~100MB，key=`page:idx:MODE`）
- ⚠️ **半成品必须用独立 key `page:<i>:PARTIAL`**：首批/流式结果上屏时本页状态还是 `TRANSLATING`，而 `cachedDisplayBitmap` 对 `TRANSLATING` **只查 PARTIAL**。写成 `renderKey` 会让首批结果**根本显示不出来**（分批形同虚设）；最终结果 `renderInto` 时 `remove(partialKey)`
- ⚠️ **`TRANSLATING` 会永久卡死该页**：该状态在翻译**开始时**写库，只有跑完才改 `SUCCESS`。退出阅读器/崩溃会让记录永久停在「翻译中」→ 该页既不显示译文也再也翻不了。三层治理：①进阅读器 `dao.resetTranslating()` 清残留；②取消时用 `withContext(NonCancellable)` 退回 `IDLE`（普通 catch 里写库会被取消打断而写不进）；③`runTranslate` 开翻时先 `renderLru.remove(partialKey)`
- ⚠️ **`rows` 必须用 `update{}` 原子更新，`fail()` 要 upsert 局部变量**：`rows.value = rows.value + x` 是读-改-写，与取消清理协程并发会丢更新；`dao.upsert(rows.value.getValue(i))` 还会因并发替换 map 而抛 `NoSuchElementException`
- **进度条四色三层**（`ReaderProgressBar`，2026-10 加了超分那一层）：
  ```
    ▓▓▓ 2dp 绿：已翻译（压在粗带**中间**）
    ███ 5dp 粗带：白=已读 / 灰=未读（黑白交界 = 当前阅读位置）
    ━━━ 3dp 紫：已超分（画在粗带**下方**）
  ```
  ⚠️ **两条细线必须分居粗带上下**，不能都压在中间：翻译与超分是**互相独立**的两个维度
  （一页可以"翻了没超"或"超了没翻"），挤在同一条水平线上必然互相遮盖；分居两侧之后
  即便分不清颜色（色弱 / 灰度截图）也能靠"在粗带上面还是下面"分辨。
  紫线的数据源是 `ReaderTranslationController.srPages()`（**只算 `STATE_SUCCESS`** ——
  RUNNING/FAILED 在进度条上留痕会让"失败后紫条不退"）。始终压在深色半透明胶囊上，颜色固定。
  守卫：`SrRecordSystemWiringTest.theProgressBarMarksUpscaledPagesSeparatelyFromTranslatedOnes`
- **失败页**：三态按钮位置改显红色感叹号（`ic_reader_warn`），点击弹状态浮层小气泡显示失败原因（**不弹窗**）
- ⚠️ **Webtoon 禁用翻译**：`isTranslateDisabledByMode() = mode == 3`。切过去时要 `setMode(MANUAL)` 停队列，否则队列会在用户看不到的地方继续翻而按钮已禁用、用户停不掉。**横屏不再禁用翻译**（双页已移除，横竖屏同为单页）
- **Webtoon 原图/译文两态（无三态按钮，默认译文）**：Webtoon 下右下角翻译浮层组**整组隐藏**（`translateGroup` GONE，含翻译/重翻、三态、失败感叹号）。原图↔译文改由阅读模式分段器上的「**连续滑动**」格子两态控制：无角标=原图，右上角 `绿色 译` 角标=译文（`tv_webtoon_translated_badge`）。**已在该模式时再点只切显示态**（`cb.onWebtoonTranslated`，不重走 `onMode` → 不重建适配器、不丢滚动位置）。状态持久化 `reader_webtoon_translated`，**默认 true（译文）**。控制器侧 `webtoonTranslated: StateFlow<Boolean>`（原 `webtoonVisual: OverlayMode` 三态已废）。⚠️ `setSegStyle` 取图标加了递归兜底：Webtoon 图标包了一层容器挂角标，`getChildAt(1)` 取不到 → 不染色
- ⚠️ **旋转后有三处尺寸/位置必须主动重算**（移除横屏双页时踩过，三处都是显式补的，且都靠 `viewPager` 布局回调在**新尺寸布局跑完之后**触发）：
  ① `ZoomableImageView.onSizeChanged` 复位 `isInitialized` —— 它只在 `!isInitialized` 时 `fitCenter()`，而 `isInitialized=false` **只发生在 `setImageDrawable()`**；旋转后 View 尺寸变了但没有新 drawable 进来 → `imageMatrix` 仍是按旧视口算的 → 图片偏位 + 上下被裁，要等翻页/重绑才自愈。
  ② `MangaReaderActivity.realignPagerAfterResize()` 重新吸附 + 重绑目标页 —— ViewPager2 内部 RecyclerView 把滚动位置**按像素**保留，旧视口的偏移量在新视口里不再落在页边界上 → **画面停在两页之间（各露半张），必须点一下/滑一下才吸附回来**。⚠️ **不能靠 `viewPager.setCurrentItem(当前页, false)`**：ViewPager2 在「已经是当前页 && 滚动空闲」时直接 return（源码里的早退分支），而旋转后正是这个状态；必须直接对内部 `RecyclerView` 调 `scrollToPosition(target)`（RecyclerView 1.1.0 的 `LinearLayoutManager.scrollToPosition` 会清 offset 走 anchor 重排，能把半页偏移吸回边界），再走 `applyPageVisual(target)` 重绑（不是裸 `notifyItemChanged`——那样译图被 LRU 淘汰时会退回原图）。⚠️ `target` 用**尺寸变化前**（`onConfigurationChanged` 里）记下的 `pendingRealignPage`，不是当时的 `currentPage`：resize 期间的布局会让 ViewPager2 把「吸附页」重算成别页并派发 `onPageSelected`，`currentPage` 已被改掉 → 照它对齐会停到错页。
  ③ `webtoonList` 的宽度变化监听重绑可见页 —— Webtoon 行高是按「绑定那一刻的 View 宽度」钉死的，旋转后失真（图片按错误宽高比 `fitCenter`、两侧出现留白带）。
  **原先这三条都由 `refreshPagerForOrientation() → applyPager()` 重建 adapter 隐式兜住**；删掉重建就要显式补回来。⚠️ 用 `addOnLayoutChangeListener` 而不是 `view.post`：配置变更回调跑在**新布局之前**，`post` 的 Runnable 会在下一次 vsync 的 traversal 之前执行 → 拿到的还是旧几何
- ⚠️ **Webtoon 切换显示态 = 一次性全量重绑，不做逐页增量**：`notifyItemRangeChanged(0, itemCount)`。逐页增量刷新是新旧图交替闪跳的来源。用 `notifyItemRangeChanged` 而非 `notifyDataSetChanged` —— 后者是 structure-changed 事件，会重置布局锚点把滚动位置弹掉
- ⚠️ **Webtoon 切换时不要 `evictAll` 译图缓存**：`webtoonLru` 里只有译图（原图由适配器直接采样解码，从不进这个缓存），切到原图时 `webtoonCachedBitmap` 按 flag 短路返回 null 即可。留着缓存 → 切回译文**秒回**；清掉 → 每次切换都得重渲附近几页，正是卡顿来源。（`clearWebtoonCache()` 只在**离开** Webtoon 时清，那是为了放内存）
- ⚠️ **Webtoon 行高必须按页图原始宽高比钉死**：`item_webtoon_page.xml` 的 ImageView 是 `wrap_content + adjustViewBounds`，`setImageDrawable(null)` 会让**行高塌成 0** → 列表连锁重排 → 用户看到「页面一直跳、旧图清不掉、新图不进来」。绑定开头用 `source.originalWidth/Height` 算出 `targetW × oh/ow` 写进 layoutParams（原图与译图宽高比一致，钉一次永久有效）。新增了 `ReaderPageSource.originalHeight()`
- ⚠️ **`webtoonList.itemAnimator = null` 必须关**（`applyPager` 的 webtoon 分支）：默认 `DefaultItemAnimator` 会给每次 `notifyItemChanged`（译图渲染完成）叠一层**淡出淡入** → 表现为「页面一直在变」。（`viewPager` 那边早就关了，Webtoon 这个漏了）
- ⚠️ **Webtoon 适配器三件套缺一不可**（`WebtoonAdapter`）：①**重绑前 cancel 上一次取图**（`VH.loadJob`）—— 切显示态时同页连续重绑两次（先慢速解码原图、后缓存命中秒回译图），不取消则慢的那次最后落地把译图覆盖回原图 → 「点了没反应/状态自己弹回去」；②**同一 holder 重绑同一页时不清图**（`VH.boundPage` 判定）—— 无条件清图会闪一帧白，只有 holder 被复用去显示**别页**才清图+转圈；③落地前 `!isActive` + `adapterPosition == position` 双校验
- ⚠️ **`refreshWebtoonRange` 必须并入当前可见页范围**：预热中心是首个可见页，一屏装得下 3 页以上时屏尾会落出 ±`WEBTOON_PREWARM_RADIUS`(=2) 之外，只按中心重绑会让那几页切「原图」后仍停在旧译图
- **每页记录** `ImportedPageTranslation`（`imported_page_translation` 表，key=`mangaId+pageIndex`）：`state`(0未译/1翻译中/2成功/3失败) + `failCode`(OCR_EMPTY/TRANSLATE_EMPTY/PROCESS_EXCEPTION/OCR_MODEL_MISSING/TRANSLATION_API_NOT_CONFIGURED) + `failMessage` + `sourceText/translatedText`(`[N]`编号串) + `bubbleRects` + `translatorName/sourceLang/targetLang` + `mangaKey`；**只存数据不存图**，显示时 `PageTranslationCodec.fromRow → renderOverlay` 实时渲
- **翻译面板**（工具栏「翻译」Tab = `ReaderMenuSheet.panel_translate`）：模式分段器（三选一可用）+ **启动延迟滑块**（200-2000ms）+ **向后翻译页数滑块**（1-10，仅增量模式显示）+ 汇总 + 筛选（全部/失败/OCR空/翻译空/异常）+ 每页状态徽章；**点「详情/收起」当前行内联展开**——**不弹窗**；点行跳页
- ⚠️ **滑块 progress 是绝对值**：设了 `android:min` 的 SeekBar，`progress` 就是绝对刻度，写 `progress = value - MIN` 会让拇指位置与右侧数值不符且取值范围整体偏移（曾导致「向后页数」取不到 1 且能到 11）
- **即显示**：右下角翻译浮层组（**圆角矩形**背景）：翻译/重翻按钮（成功态变 `ic_refresh`）+ 三态按钮（**并入同一背景组件**、有结果才出现）+ 失败感叹号。**显示经「页图提供者 + notifyItemChanged 重绑」按页槽位取图**（`ReaderAdapters.Shared.pageImage`），⚠️ **绝不写 `visibleImage`**（它指向最近绑定页 → 错图/串页）
- ⚠️ **id 复用串数据（重要根因）**：漫画 id=`书架最大id+1`，删除后再导入会**复用旧 id**；删除若不清 `imported_page_translation` → 旧孤儿行被 `forManga(旧id)` 捞回 → **已删除漫画的译图层串到新漫画**。三层治理：①记录带**身份指纹 `mangaKey=addedAt`**（⚠️ **绝不要拼 `title`**：书架「重命名」会改标题，含 title 的指纹一改名就全部失配 → 整本书译文读不出来且清不掉；`load()` 里有按 `|addedAt` 后缀匹配的一次性旧指纹迁移），`forManga(mangaId, mangaKey)` 指纹不匹配即忽略（旧行 NULL 也失效）；②删除漫画时 `deleteManga`；③书架打开 `purgeOrphanTranslations` 清孤儿
- ⚠️ **取消并行 OCR 后必须先 join 再回收裁剪图**（`IncrementalBatchPipeline.cancelAndJoinQuietly`）：`cancel()` 只置协程标志，而引擎的 `recognizeBatchWithCls` 是**同步 native 调用**，不会在调用中途响应取消 —— worker 可能仍在读像素缓冲，此时 `recycle()` 就是 use-after-recycle（native 崩溃，Java 层捕获不到）
- ⚠️ **状态浮层是进程级单例 + 系统窗口**：`TranslationStatusOverlay` 用 `TYPE_APPLICATION_OVERLAY`，**退出阅读器必须在 `onDestroy` 调 `dismiss()`**，否则「检测中…／翻译中…」会挂在桌面/其它页面上且无入口可消（直到下次翻译成功或失败）

### 导入编排 / 占位进度 / 删除清理（2026-09-19）

- ⚠️ **图片扩展名判定与自然排序只有一个来源：`mangaimport/data/ArchivedMangaReader`**（`IMAGE_EXTS` + `naturalComparator`）。
  导入侧（`MangaImporter`）与阅读侧（`ReaderPageSource`）都调它 —— 这是「导入顺序 = 阅读顺序」的唯一保证，
  改扩展名白名单或排序规则**必须改那一处**，别在两边各写一份。

- **导入跑在进程级 `ImportManager`（`mangaimport/data/`），不在 Fragment 的 lifecycleScope**：旧实现旋转/切页会取消导入 → 复制一半、条目没入库 = 孤儿目录。现在 View 只观察 `tasks`（占位卡片：图片位进度条 + 百分比 + ✕）与 `events`（结果弹窗，`drainEvents()` 取走）。
- ⚠️ **并发导入了 → 清单写入必须同步**：`importArchives` 每个 uri 一个并发任务，而 `ImportedMangaStore.add/remove/update` 是 `load→改→save` 三步。**全部方法已 `@Synchronized`**；去掉同步 = 两个任务同毫秒收尾互相覆盖 → **整条导入静默消失**（占位卡片没了、书架也没条目）。守卫：`ImportedMangaStoreConcurrencyTest`（8 线程 × 20 次 add，必须有 160 条）。
- ⚠️ **`ImportManager` 的 scope 挂了 `CoroutineExceptionHandler`，且 SAF 名字查询用 `runCatching` 兜住**：`launch` 里未捕获异常会走线程默认处理器 = **整个应用崩**，而 SAF 的 `DocumentFile.name`（ContentResolver 查询）在权限被撤销/存储卸载时会抛。
- ⚠️ **`addedAt` 必须单调递增**（`MangaImporter.freshAddedAt` = `max(now, 清单最大 addedAt+1)`）：它同时是翻译记录的**身份指纹**，同一毫秒内「删书 → 重导」会复用同一个 id + 同指纹 → 旧译图映射到新书。
- ⚠️ **删除翻译记录按 `(mangaId, mangaKey)` 成对删**（`deleteMangaScoped`）：删除是异步的，id 又会被复用 → 只按 id 删可能删掉刚导入的同 id 新书的记录。孤儿清理 `purgeOrphanTranslations` 每个 id 前也二次确认（清理期间用户可能刚好导入同 id 新书）。
- ⚠️ **取消/失败清理要连封面一起删**（`ImportManager.cleanup` 按 `<id>_` 前缀）：目录导入是「复制 → 生成封面 → 返回」，取消可能落在封面之后、入库之前。
- ⚠️ **扫描阶段也要可取消**（`collectImageDocs` 是 `suspend` + 每层 `ensureActive()`）：`DocumentFile.listFiles()` 逐层 IPC，万级条目扫描几十秒，不检查取消 = 点了 ✕ 像没反应。
- ⚠️ **导入结果弹窗要能扛住旋转**：MainActivity 没有 `configChanges`，Fragment 会重建，而事件已被 `drainEvents()` 取走 → 队列进 `onSaveInstanceState`，且 `onDestroyView` 把「正在展示的那条」放回队首（否则「空文件夹 / 导入失败」提示**永久丢失**）。
- ⚠️ **取消竞态文案**：`ImportManager.cancel()` 返回 false（任务恰好跑完）时提示「已经导入完成了」，不能报「已取消」。
- 入口只有两个：**导入文件（zip/cbz，可多选）** 与 **导入文件夹（整个夹 = 一部）**。第三个「导入多个漫画文件夹」是假的（同一个 `OpenDocumentTree`，SAF 不给多选目录），已删；文件夹模式**不认夹里的压缩包**，子目录递归且按「先目录名、再夹内页名」自然排序。

### 章节系统 + cbr/7z 导入（2026-10）

- **章节切分只有一个来源**：`mangaimport/data/MangaChapter.kt` 的 `MangaChapterSplitter`。
  它**同时**决定「章区间」与**权威页序**（按章分组 + 章内自然排序）—— 导入侧（`MangaImporter`）与
  阅读侧（`ReaderPageSource`）都调它。两边各算一份，章区间立刻错位（点第 3 章跳到别人的页）。
  ⚠️ 别退回「`sortNaturally(完整路径)`」：散图与子目录会交错（`ch1/a.jpg` 排在 `z.jpg` 前面），
  第0章的区间会裂成两段。
- **切分规则**：①先剥掉「所有页共有的最外层目录」（zip 常多包一层书名目录，剥到没有公共外层为止）；
  ②按**第一层子目录**分章（`ch1/part1` 与 `ch1/part2` 各一章）；③最外层散图单独成章、排在最前，
  章号 **0** —— ⚠️ 仅当它与子文件夹章**并存**时才叫「第0章」，整本只有一层时是「第1章」；
  ④章标题 = 文件夹名，**有标题就只显示标题**（`mangaChapterLabel`，与小说 `chapterDisplayTitle`
  同一约定），没标题才用「第N章」兜底。
- **封面 = 阅读顺序的第一页**：有第0章就是第0章的第一张，否则第1章的第一张（用户口径）。
- **cbr / 7z 走 libarchive，导入时解压成目录**：`ArchiveTypes` **按魔数**（不信扩展名）分流 ——
  zip/cbz 仍走 `ZipFile` 随机读；rar/cbr/7z 用 `LibArchiveExtractor` 解到
  `manga_import/<id>/pages/`，并**删掉原压缩包副本**（rar 没有中央目录，随机读一页要顺序扫整包；
  不删等于同一本书占两份空间）。解压后 `isArchive=false`，阅读链路与文件夹导入完全同一条
  （分章白拿：解压保留子目录结构）。⚠️ SAF 选择器的 mime 列表必须带上 rar/7z，否则 `.cbr`
  在选择器里是灰的选不中。
- **清单里的 `chapters` 只给书架显示用**：导入时算好并持久化（`ImportedMangaStore` 存 JSON 串，
  空串 = 旧条目）；老条目由 `MangaChapterMigrator` 在书架打开时**逐个 update** 回填（幂等）。
  ⚠️ 不许「读全表 → 改 → 整表 save」：那会与并发导入的收尾写入互相覆盖。阅读器打开时按**实际文件**
  重新推导，与清单不一致就回写（自愈）。
- ⚠️ **页序变了就必须迁移译文行（2026-09 审查新增）**：记录主键是 `(mangaId, pageIndex)`，而章节系统
  把「最外层散图」排到第 0 章最前（旧版是 `sortNaturally(完整路径)`）—— 对「根散图 + 子目录混放」的书，
  同一个下标指向的**是另一张图**：不处理就会译文挂错页、气泡坐标错位、删除/清除作用到别的页。
  现在由 `MangaPageOrder`（纯函数：算「旧下标 → 新下标」置换）+ `MangaPageOrderMigrator`
  （`@Transaction` 内读→删→按新下标重插，顺带搬 `lastReadPage`）在**阅读器 `controller.load()` 之前**
  跑一次，完成后写 `ImportedManga.pageOrderVersion` 标记。守卫：`MangaPageOrderTest`（纯函数）
  + `MangaPageOrderRekeyTest`（**动数据那一步**：置换方向、载荷跟行、越界保留、不碰别的书/指纹）。
  ⚠️ 以后再改权威页序，必须把 `MangaPageOrder.CURRENT_VERSION` +1，否则老书不会再迁移。
  ⚠️ 迁移**只做置换、绝不删行**（信息可完整保留）；页集合对不上（文件被换过）时返回 null 直接跳过。
  ⚠️⚠️ **`pageOrderVersion` 与 `pageOrderPlan` 必须都落盘**（2026-10 审查抓到的真 bug）：`toJson`
      漏写 `pageOrderVersion` 时读回恒为 0 → 「已迁过就跳过」判据永远为假 → **每次打开阅读器都
      再置换一次**，而置换**不幂等** → 译文被一次次挪到别的图上，用户完全无从察觉。
      单测必须用**非 0 的版本号**（全用默认 0 的话 `0 == 0` 恒真，等于没测 —— 这个 bug 就这么活了几个月）。
  ⚠️ `pageOrderPlan`（已施加过的置换）是**中断幂等**用的：`legacyToNewPlan` 是页文件的纯函数，
      每次算出来都一样，所以只能靠记录判断"施加过没有"。没有它的话，进程若死在
      「DB 事务已提交、清单还没落盘」之间，重开会把同一个置换再施加一次。
  ⚠️ 清单写入一律走 `ImportedMangaStore.setChapters`（锁内**只改一个字段**），
      **不要** `update(manga.copy(...))`：回填/自愈都是秒级长循环，拿着循环开始时的快照整条替换
      会把期间用户写的 `lastReadPage`、改过的书名回滚掉（表现是「进度自己退回去」）。

- **DB 零迁移**：记录主键仍是 `(mangaId, pageIndex)`，章只是页号区间。
  ⚠️ 但页序会变（原先按完整路径自然排序）：只有「根散图 + 子目录混放」的包顺序会变，
  纯子目录包与单章包与旧版逐页一致。
- **阅读器**：顶部胶囊 = `章标题 · 章内第几页/本章共几页`（`reader_page_indicator_chapter`），
  **点它打开章节目录**（`ReaderChapterDialog`，每章带「已译 x/y」徽章）；底部进度条左右箭头 =
  **切章**（到头 toast「当前已是第一/最后一章」；单章漫画也照此 —— 翻页靠滑动/点击分区）。
  进度条本体仍是**全书**口径（拖拽寻页、长按预览、绿条都是整本）。
  ⚠️ **提示条紧贴胶囊下沿，且必须用屏幕坐标推**（2026-10-01 定稿，此前三版都被用户打回）：
  - 胶囊 `layout_marginTop` = **38dp**（两个阅读器一致；用户口径：「不要紧贴屏幕顶部」，
    但不许为了给提示让位而下移 —— 「这个位置不要动」）
  - 提示条顶边 = `getLocationOnScreen(胶囊)[1] + 胶囊.height + 2dp`，经
    `TranslationStatusOverlay.setTopScreenY` 推给浮层；`onStop` 传 null 恢复
  - ⚠️ **不能用视图的 `pill.bottom`**：浮层窗口被 WMS 按系统栏内缩
    （真机 `Frames: parent=[0,138][1220,2660]`），阅读器窗口是整屏的（`frame=[0,0][1220,2712]`），
    两边差 138px（≈42dp）→ 表现就是「提示条永远比胶囊低一大截，怎么调 dp 都像原地没动」。
    详见根 `CLAUDE.md`「提示浮层」那节
  - ⚠️ 浮层是 `TYPE_APPLICATION_OVERLAY` **系统窗口**，会吞掉落在它矩形内的触摸 ——
    压住胶囊那段就点不动，而"点胶囊开目录"正是用户报「不知道为什么没有了」的那条路
  - 改一个必须改另一个（漫画 `activity_manga_reader.xml` / 小说 `activity_novel_reader.xml`），
    守卫 `ReaderTopPillInsetTest`（含"两处取值一致"与"旧 API `setTopOffsetPx` 已消失"）
  ⚠️ **「当前章」不存独立状态**：恒等于**当前页所在章**（`chapterIndexOf`）——
  胶囊、面板选中、批量翻译都取同一个值，翻页即跟随，不存在两套状态对不上的可能。
- **翻译面板**（`ReaderMenuSheet`）：
  - ⚠️ **面板已按用户要求改版（2026-10）：见下面「章节批量翻译：应用级后台任务 + 并发流水线」一节** ——
    「翻译本章 / 清除本章译文」已挪到**章节卡片**上、面板里的左右切章组件与「已译x/y 翻译中 失败」汇总行
    **都已删除**；下面是改版前后的共同约定（筛选/详情/删除/清除语义仍有效）。
  - **记录列表是两段式**（`ReaderPageStateAdapter` + `item_translate_chapter_row.xml`）：每章一个卡片
    （点行 = 选中并跳章，点箭头 = 展开），展开后**只列有记录的页 + 排队中的页**（未翻译的默认不进列表，用户口径）。
  - 筛选收敛成 **全部 / 完成 / 进行中 / 失败**（原来把失败细分成 OCR空/翻译空/异常，而具体原因
    本来就写在行内 `tv_fail_message`）。⚠️ 过滤必须交给 `pageAdapter.setFilter`（两段式列表里过滤
    只作用于页行）；在面板里先筛一遍会把没有记录的章表头也筛掉。
  - 行内「详情」**后面**的「删除」删的是**这一页的整行翻译数据**（原文+译文+气泡+状态），删完回落原图。
    ⚠️ 该按钮在**共用布局** `item_translate_page_state.xml` 里默认 `gone`（小说也用这个布局），
    只有 `ReaderPageStateAdapter` 显式置 VISIBLE。
  - **整章翻译**：默认**只翻未成功页**（未翻译 + 失败重试），确认弹窗里可「全部重翻」覆盖已成功的页
    （正按钮 = 只翻未完成 → 系统默认聚焦它 = 默认不覆盖，用户口径）。
  - **清除本章**：删该章区间内所有翻译行 + **三个渲染缓存**（`renderLru` 三态 / `PARTIAL` /
    `webtoonLru`）+ 内存行，带误删确认弹窗；**不做整本清理入口**（用户口径）。


### 章节批量翻译：应用级后台任务 + 并发流水线（2026-10 大改，改动前必读）

> 用户口径：翻译本章要像模型下载一样**能在后台跑、通知栏看进度、可暂停/取消**，
> 「翻译本章」按钮从面板挪到**章节卡片**上；漫画因为要 OCR，OCR 串行、翻译请求并发。

**分层（谁负责什么，别再混）**
| 组件 | 位置 | 职责 |
|---|---|---|
| `ChapterJobRunner<T>` | `translate/batch/`（**跨模块共用**，漫画/小说都用） | 纯协程调度：**OCR 串行 + 翻译并发 N + 按章暂停/取消/等待**。不依赖 Android，可纯 JVM 单测（`ChapterJobRunnerTest`） |
| `TranslationJobs.kt` | `translate/batch/` | `ChapterJobState`/`ChapterJob`(`label`,`startPage`)/`ActiveChapterJob`/`ChapterJobSource`/`TranslationJobRegistry`（通知栏与前台服务的唯一数据源） |
| `TranslationJobService` | `translate/batch/` | 前台服务（`dataSync`）：**每章一条通知**（进度/暂停/继续/取消，点通知回到对应阅读器）。任务状态**不在这里**，只镜像 registry；registry 空了就 `stopSelf` |
| `ReaderTranslationHub` | `mangaimport/translate/` | 漫画的**应用级宿主**：按 mangaId 缓存 `ReaderTranslationController`（应用级 scope）、实现 `ChapterJobSource`、无 UI 且无任务时 `releaseIfIdle` 回收 |
| `ReaderTranslationController` | 同上 | 控制器：两阶段单页翻译（见下）+ 章任务 API（`startChapterJob/pause/resume/cancel`）+ `chapterJobs`/`waitingPages` flow |

**两阶段翻译是并发的关键**（`ocrPhase` / `translatePhase`）：
- `ocrPhase(page)`：检测 + 识别，**持 `OcrLock`（全局串行）**，产出 `PreparedPage(bitmap, bubbles, det, ocr, langs)`
- `translatePhase(page, prep)`：查文本缓存 → 调翻译 API（**并发，不持 OcrLock**）→（当前页才）渲染 → 写库 → 回收 bitmap
- ⚠️ 旧的 `runTranslate` 是**从 OCR 一直持锁到翻译结束**，那样两页之间不可能重叠；拆分之后才能
  「第 1 页发出请求」与「第 2 页开始 OCR」并行（用户明确要的提速）
- ⚠️ 章节批量任务里**页内分批（`Incremental_Render`）关掉**（`incrementalEnabled=false`）：
  页内分批是"先出一部分给正在看的用户"，与跨页流水线互相拖慢；手动/自动/增量三条前台路径**不变**
- ⚠️⚠️ **章节路径的取消判据必须与手动/队列的 `cancelFlag` 解耦**（`ReaderBatchHost(isCancelled = …)`，
  见 2026-09-28 的事故）：`startChapterJob()` 会先 `cancelEverything()`（它 `cancelFlag.set(true)`），
  而章节路径从前没人复位它 → 管线第一句 `if (host.isCancelled()) throw` 把**每一页**都判成"已取消"，
  catch 分支还静默 → **整章一页都翻不出来、进度照涨、日志空白、取消后什么都没有**。
  现在：手动/队列传 `{ cancelFlag.get() }`，章节传 `{ false }`（章节靠 `ChapterJobRunner` 的协程取消），
  且 `startChapterJob` 里 `cancelFlag.set(false)`、`translatePhase` 进出各记一条日志。
  守卫：`ChapterTranslationCancelGuardTest`（源码级，防止接线改回去）。
- ⚠️ **进行中只有两个状态：识别中（OCR）/ 翻译中（翻译请求）**（用户口径）：
  `ChapterTaskStage` + `ChapterJobRunner.inFlightTasks`（flow）→ 控制器的 `ocrPages()/translatingPages()/
  panelWaitingPages()` → 面板行徽章（识别中=蓝、翻译中=琥珀、等待=灰）+ 状态浮层文案
  （`正在翻译本章 3/21 · 识别中 P8 · 翻译中 P5, P6`）。**并发数只作用于翻译段**。
  三态语义（2026-09-28 用户追问"为什么一启动同时 3 个识别中"后修正）：
  - `QUEUED`：已进流水线（在途表里）但**还没轮到识别**（在 OCR 通道排队 / 泵等着交班）→ 界面「等待」
  - `OCR`：**真正在 OCR 工人手里** → 「识别中」，**恒 ≤1**（OCR 单例、必须串行）
  - `TRANSLATE`：**识别一结束就切**（不是"工人接手时"）→ 「翻译中」，≤ N（并发）+ 至多 1 页刚识别完待交班
  ⚠️ 宿主给面板的「等待」必须是 `panelWaitingPages()` = 队列里的 + `QUEUED` 的在途页；
  `ReaderPageStateAdapter.childRowsOf` 会把这些页从"在途"里减掉（否则又被标成识别中/翻译中）。

**并发数**：`utils/TranslationConcurrency.mangaConcurrency()`（`manga_concurrent_requests`，2-5，**默认 3**，
面板「最大同时请求数」滑块可调）——**本地引擎强制 1**；文本侧是**另一个独立设置**（默认 5，1-10），
两处刻意不共用。
⚠️ **生效范围（用户口径 2026-09-28 修正）：整章任务 + 增量模式**。
- 整章：`ChapterJobRunner`（OCR 串行 + 翻译并发 N）
- **增量：`ReaderTranslationController.runWindow`（OCR 逐页串行 + 翻译并发 N）**，
  ⚠️ **先占槽位再 OCR**（`Semaphore(N)`）把流水线深度压在「1 页识别 + N 页翻译」，内存不随窗口膨胀；
  增量**不再**走单页 `runTranslate`（那是自动模式的路，它要保留页内分批 + 流式"边翻边出"）
- 手动/自动：并发无意义（同时只有 1 页），仍是单页路径

**暂停/取消语义（用户口径，别改错）**：
- **暂停**：只停"取下一页"，**队列原样留着**（＝面板上的「等待」），在途那一页照旧跑完
- **取消**：丢掉**还没开始翻**的页，**已翻好的译文保留**；**在途那一页强制结束**（见下）
- ⚠️⚠️ **取消 = 强制结束在途项（不入库、不等待）**（用户口径 2026-09-28：「和手动/自动/增量
  打开面板或退出阅读器逻辑一样」）：`ChapterJobRunner.cancel` 会标记并 `cancel()` 在途项**各自的协程**
  （`taskJobs` 里的 `Deferred`，所以只掐这一章、工人继续服务别的章），
  产物一律丢弃（`translatePhase` 的取消分支把行退回「未翻译」，**不写译文**），
  并**立刻派发收尾事件**（`dispatchFinished` + `Runtime.finishedDispatched` 闩：至多派发一次）。
  ⚠️ 阻塞中的 native OCR / 已发出的 HTTP 请求**无法从中途打断**——我们只是不再等它、也不采用它的结果。
  守卫：`ChapterJobRunnerTest.cancel_forceAbortsInFlight_immediatelyAndFinishesOnce`。
- ⚠️ **打断提示统一为两句**（`reader_translate_force_stopped` / `..._to_manual`）：
  打开面板 / 退出阅读器 / 双击取消 = 强制退出 + 真回退了手动 → 接「，回退到手动模式」；
  **取消整章任务**（本来就跑在手动模式下）→ 只报「翻译进程已强制退出」。
- **等待标签是纯内存态**（`waitingPages` + `queuedPages`），**不写库**：任务随进程消失，那些页就回到"未翻译"
- 多章**互不影响**：某一章暂停不挡别的章（`ChapterJobRunner` 按提交顺序取页、跳过暂停章）
- 每章一个任务（`chapterJobs: StateFlow<List<ChapterJob>>`）；`onJobFinished` 走**监听器列表**
  （`addJobFinishedListener/remove`）——单个 `var` 会被 hub 与阅读器互相覆盖
- ⚠️ **预取深度必须是「1 页在 OCR + N 页在翻译」，`preparedChannel` 容量 = 0（会合点）**：
  曾把缓冲写成 8，结果泵在毫秒内把**整章**的页从队列里抽干 —— 用户点暂停时队列已经空了，
  「等待」瞬间消失、暂停/取消都拦不住那些已预取的页（`ChapterJobRunnerTest` 抓到的）。
  容量 0 同时把内存（每页一张全尺寸 bitmap）压在 N+1 张以内。
  ⚠️⚠️ **通道容量只有一个来源**（`newOcrChannel()` / `newPreparedChannel()`）：通道是一次性的，
  收尾关掉后下次提交要重建 —— 重建那处曾手写 `Channel(8)`（与声明的 0 不一致）→
  **第一次任务正常、第二次起预取整章**（2026-09-28 复查发现）。重建通道必须调同一个工厂函数。
- ⚠️ **翻译工人在真正开翻前要再确认一次这一章还在 RUNNING**：从"预取"到"发出请求"之间用户
  完全可能点了暂停/取消 → 暂停则**把这一页 `addFirst` 退回队列**（重新成为「等待」，不计 done）、
  取消则直接丢（也不计 done）。少了这一步就是"暂停/取消点了没反应"。
- ⚠️ **在途表按 `Task.id`（内部自增唯一号）做 key，不能按页号**：小说每章都从 0/1 开始给批编号、
  漫画多章也有同页号，按页号做 key 会互相覆盖 → 结算错章、任务卡死。
- ⚠️ **并发数变更延迟生效**：`ensureStarted` 在有在途项时**不重建翻译工人**（重建会把工人手上
  那一项从通道里丢掉 → 那页永远不结算 → 任务永久停在「进行中」）。改设置等这一轮排空后生效。

**控制器生命周期（关键，勿回退）**：
- 控制器由 `ReaderTranslationHub.controllerFor(applicationContext, manga)` 提供（**应用级 scope**），
  阅读器 `onCreate` 取、`onDestroy` 只 `onReaderClosed()`（停前台队列 + 回退手动 + `unbindUi()`），
  **不销毁控制器** → 章节任务继续跑
- `unbindUi()` 必须把 `loadFull` 换回控制器**自持的 `ownSource`**（`ReaderPageSource(manga)`）并把
  回调清空：否则后台任务会继续调用已销毁 Activity 的页图加载器（泄漏 + 往桌面贴状态芯片）
- `uiAttached` 为 false 时：**不渲染上屏**（`renderInto` 只在 `uiAttached && page == currentPage` 时调）、
  `ReaderBatchHost.onPartialRender` 也直接 return
- 阅读器重进时是**同一个控制器实例**，所以任务、页记录、渲染缓存都在（结果实时上屏靠它）

**面板 UI（`ReaderMenuSheet` + `item_translate_chapter_row.xml`）**：
- **字号层级（用户口径 2026-09-27，改版时别再弄反）**：**筛选 tab 15sp > 章卡片标题 14sp > 页/段行 13sp**。
  「章节标题不许超过 tab」是硬约束；按钮 12sp；「自动」胶囊 12sp。
- 章卡片 = 章标题（14sp 加粗）+ 徽章（小药丸）+ 展开箭头 + **两个按钮**：
  主按钮「**翻译本章** ⇄ 暂停 ⇄ 继续」（`weight=1`，实心药丸）、次按钮「**清空译文** ⇄ 取消」（`wrap_content`，淡红药丸）
- ⚠️ **卡片要有卡片的样子、又不能"和整体 UI 不搭"**：`CardBackdrop`（`mangaimport/reader/CardBackdrop.kt`）
  统一给底：**浅色面板 = 白底 + 1dp 细描边**（面板本身是白的，灰块会显得突兀）、**深色面板 = 深灰块 + 亮描边**；
  圆角 10dp + 卡片间 4dp 留白 + `RippleDrawable` 点击水波（自绘底之后布局里的 `?selectableItemBackground` 就没了，必须自己补）
- ⚠️ **展开出来的页/段行必须"在章卡片组里面"**：用 `CardBackdrop.applyNested(...)` ——
  **方角 + 左侧 3dp 竖线 + 上下零间距 + 缩进 14dp**，底色与卡片同色（`Tone.PLAIN`）。
  只做"缩进 + 同色"是不够的（每行仍是独立圆角块，看着像另起一张卡，用户 2026-09-27 反馈过两次）
- 行底色按状态：`PLAIN`（普通）/ `WAITING`（等待）/ **`OCR`（识别中 → 蓝色高亮 + 蓝色左边框）** /
  **`ACTIVE`（翻译中：正在提交或等服务端返回 → 琥珀高亮 + 左边框变琥珀）** /
  `FAILED`；展开/收起走 `TransitionManager.beginDelayedTransition(itemView, AutoTransition)`，在飞行额外淡入一次。
  ⚠️ 行徽章也要分开：识别中 = `reader_translate_state_ocr` + `bg_state_ocr`，翻译中 = `reader_translate_state_translating` + `bg_state_translating`
- ⚠️ 章卡片**完成态**必须显示「翻译完成」标记 + 「清空译文」，**只有任务真的在跑/暂停时**才显示「暂停/取消」
- ⚠️ **整章做完 → 主按钮置灰**（用户口径 2026-10-01：「已经完成就按钮应该置灰，除非用户手动删除了
  某张的译文或者超分结果，这个按钮才应该实时刷新变成可点状态」）。
  判据是「**真的有页可做**」而不是"跑没跑过"：`success >= pageCount`（超分）/
  `success >= pageCount`（翻译，从 `ImportedPageTranslation` 的 SUCCESS 行数算）。
  删除路径必须补 `pushPanelNow()` —— `refreshProgressTranslation()` 那次推送有 300ms 节流，
  用户"点了删除 → 面板刚推过 → 这次被吞掉"就会看到**删了还是灰的**。
  - **失败页算"还可做"**（否则永远重试不了）；`pageCount`/`total` 拿不到时不能判成已完成
  - ⚠️ **跑着 / 暂停时绝不能置灰**：那时按钮是「暂停 / 继续 / 取消」，禁掉就停不下来了
  - 三处同一规则：`ReaderPageStateAdapter` / `ReaderSrStateAdapter` / 小说 `NovelChapterStateAdapter`
  - 守卫：`ChapterPrimaryButtonDisabledTest`（漫画 10 例）+ `NovelChapterPrimaryButtonDisabledTest`
    （小说 6 例），Robolectric 真绑断言 `isEnabled`（源码 grep 盖不住"看着灰但点得动"）
- **点卡片空白 = 跳到该章**；点箭头 = 展开/收起；点页行 = 跳页
- ⚠️ **面板里原来的「◀ 章 / 章 ▶」切换行与「已译x/y 翻译中 失败」汇总行已按用户要求删除**
- **字号改成滑块**（与文本阅读器同款排版）：标签 + 值 + `sb_font_size`（档位 = `MangaFontSize.PRESET_SIZES` 下标）+
  右侧「自动」胶囊（漫画独有：按气泡自适应）。
  ⚠️ **改字号必须 `controller.invalidateRenders()`**：字号不在 `renderLru` 的 key 里（key 只有 `page:idx:MODE`），
  不作废的话拖完滑块屏幕上还是旧字号（宿主回调 `onFontSizeChanged`）
- **启动延迟有说明行**（`tv_debounce_hint`）；**并发说明与下面的筛选（历史记录）栏之间留 10dp**
  （用户口径：说明不要紧挨着那一栏）
- **「翻译模型最大同时请求数」**：**整章翻译 + 增量模式**生效（自动/手动同时只有 1 页，无所谓），
  **不含本地翻译模型**（本地引擎恒为 1）；文案要短（用户口径 2026-09-28「抓住重点讲」）
- ⚠️ **字号行的「自动」胶囊必须紧跟标题**（`字体大小 [自动] …… 12sp`，用户口径 2026-09-28）：
  它是这个设置的开关，不是"又一个值"。守卫：`ReaderPanelLayoutTest`（读**真实布局**断言相邻）

### 阅读器提示：**唯一出口 `notifyUser`**（2026-10 统一，用户口径「阅读器所有的提示报错信息全部使用顶部系统弹窗」）

阅读器里所有**给人看的提示**（点击反馈 / 结果 / 报错）都从 `MangaReaderActivity.notifyUser(text, style)`
发出，样式四选一（`AppNotice.Style`，定义在 `utils/AppNotice.kt`）：

| 样式 | 浮层用法 | 用途 |
|---|---|---|
| `PROGRESS` | `showImmediate(text, autoDismiss)` | **替换**顶部芯片（翻译按钮 Hint） |
| `INFO` | `show(text)` | 堆叠一条、到点消失（已清除/已删除/开始导出…） |
| `ERROR` | `showError(text)` | 红底、可点击复制；**常驻**到被下一条替换（翻译失败） |
| `BRIEF_ERROR` | `notifyUser(text, AppNotice.Style.BRIEF_ERROR)` | **说完就关页面**的报错（本地文件丢失、导出失败）：限时消失 + 扛得住 `onDestroy` 那次 `dismiss()` 清屏 |

⚠️ **「进行中」的提示走的是另一条路：`AppNotice.showRunning` / `clearRunning`**（不入上表 —— 它不是
`notifyUser` 的样式，因为它**不替换任何东西**）。用户口径 2026-10：「超分执行的时候也要有提示信息，
而且**要和翻译中一起出现** —— 我们的通知系统本来就支持同时显示多个通知」。`Style.PROGRESS` 走的是
`showImmediate` = **替换**顶部那一条，用它就永远看不到"翻译中 + 正在超分"两条并存。
- 调用方 `val id = AppNotice.showRunning(...)` → 结束（含失败/取消）`AppNotice.clearRunning(this, id)`，
  **必须成对**。超分侧由 `ReaderTranslationController.onSrProgress`（`withSrRunningNotice` 保证
  true/false 成对，收尾那句走 `NonCancellable`）+ 宿主 `srRunningChipByPage`（**按页**记账，
  整章批量时几页会同时在跑）承接。
- ⚠️ **超分出结果时只收超分自己那类芯片**（`clearAllSrRunningChips()`），**不许**再写回
  `overlay.dismiss()` —— 那是清空全部堆叠消息，会把并排的「翻译中…」一起抹掉。
  守卫：`SrReaderWiringTest.everySrNoticeClearsOnlyItsOwnRunningChip`、
  `TranslationStatusOverlayTest`（追加/精确移除/槽位挤旧的同类芯片）。

- ⚠️ **两条兜底必须都判**：`status_overlay_enabled` 开关关掉、或**没有「显示在其他应用上层」权限**
  （浮层画不出来且**不报错**）→ 退回系统 Toast。少了这个判断，用户点了按钮就是"毫无反馈"
  （历史投诉「什么提示都没有」的根因）。`notifyUser` 是阅读器里**唯一**允许出现 `UiUtils.showToast` 的地方。
- ⚠️ **唯一例外**：翻译**进行中**的常驻芯片（`onTranslatePhase` 的 DETECTING/TRANSLATING）直接走
  `showImmediate`，故意**不做 Toast 兜底** —— 它是连续状态，浮层关掉时保持静默是那个设置本身的语义
  （否则自动/增量模式下每页刷两条 Toast）。结果类（SUCCESS/FAILED/QUEUE_DRAINED）仍走 `notifyUser`。
- 位置 / 时长不需要阅读器自己管：浮层读 `Status_Position` / `Status_Duration`（个性化设置页）。
- **暂停提示**：`showForceStoppedNotice(toManual)`（`showPausedToManualNotice()` = `toManual=true`）现已
  委托 `notifyUser`；**退出阅读器的提示改在 `onStop` 里判 `isFinishing` 发**（`onDestroy` 里弹的 Toast
  常被系统吞掉，这就是"没提示"的根因），`onDestroy` 只做兜底并靠 `pausedToManualNotified` 防重复。
- 守卫：**`ReaderNoticeOutletTest`**（源码级：Toast 只许出现在 `notifyUser` 兜底分支、`show`/`showError`
  只许从 `notifyUser` 发、`showImmediate` 只许出现在 `notifyUser` 与 `onTranslatePhase`、十来个既有提示
  必须逐条经 `notifyUser`）。新加提示**别绕开它**，否则守卫直接红。

**回归守卫**：`ChapterJobRunnerTest`（OCR 串行 / 翻译并发 / OCR 与请求重叠 / 等待页 / 按章暂停不影响别的章 /
取消丢等待留已完成 / 收尾后可再次提交 / **每页都要结算** / **阶段查询** / **取消=强制结束且只收尾一次** /
**通道重建后预取深度不失控**）、`ChapterTranslationCancelGuardTest`（章节取消判据不许读 cancelFlag）、
`ReaderIncrementalConcurrencyGuardTest`（增量窗口必须走并发信号量）、`ReaderPanelLayoutTest`（「自动」紧跟标题）、
`ReaderMenuSheetTranslateTest`、`MangaChapterSplitterTest`。

### 超分记录系统 + 超分本章（2026-10 新增）

**用户口径**：「给超分面板也设计一个类似翻译面板那样的记录系统（**直接复用相关 UI 和代码逻辑**）……
显示超分的完成/进行中/失败状态，支持超分本章、查看详情（超分模型、原始和超分后尺寸和大小、时间）、
提示系统」+「**两个批量功能只能同时开启一个**……要有互斥反馈和提示」。

**记录列表挂在「超分面板」里**（调色面板的 `sr_panel_group`，紧挨超分开关 / 超分模型行）：

- ⚠️ **第一版做成了翻译面板里的「翻译 / 超分」来源页签，被用户当场否掉**（「我说超分记录放到
  **超分面板**，不是让你放到翻译面板里面」）。那两个页签（`tab_records_translate` /
  `tab_records_sr`）连布局带 `recordsSource` / `refreshRecordsTabs` **已整体删除**，别再照旧名字找。
- 现在：`sr_panel_group` 里新增 `sr_filter_row`（筛选 chips）+ `rv_sr_records`（章卡片列表），
  形态与翻译面板**完全一致**（两段式 + 行内详情），复用同一批布局
  （`item_translate_chapter_row.xml` / `item_translate_page_state.xml`）与 `CardBackdrop` 底色。
- ⚠️ 记录列表**必须定高**（不能 `wrap_content`：那会让 RecyclerView 量完全部条目，几百章的书直接卡死）。
  高度 = **半屏**，由 `ReaderMenuSheet.onCreateView` 用 `sheetRecordsHeightPx` 设给
  `rv_sr_records` 与 `rv_translate_pages`（XML 里的 200dp 只是初始值，小说明书的屏才用得上）。
  用户口径 2026-10-01：「记录功能底部的空间太小了，面板上滑占满屏幕后应该可以继续上滑，
  让记录组件至少可以占据半个屏幕」—— 200dp 时**面板整体比视口还矮**，外层滚动容器根本没得滚，
  用户说的"继续上滑没反应"就是这个，不是手势问题。
  小说侧同款：`sheet_novel_menu.xml` 的 `rv_translate_chapters`（`NovelPanelSheet.onCreateView`）。
- ⚠️ 两个含记录列表的面板是 **`NestedScrollView`**（不是 `ScrollView`）：
  里面嵌了 RecyclerView，要靠 nested scroll 把「列表滚到底 → 继续滚面板」接起来，
  框架 `ScrollView` 不参与这一套。⚠️ 顺便：面板上那些 `android:maxHeight` **是死属性**
  （`maxHeight` 不是 View 的属性，写在 ScrollView 上静默失效），别再照它推算面板高度。
- ⚠️ 筛选键**独立**（`srFilterKey`）：超分那边选「失败」不该影响翻译那边的列表。
- ⚠️ 宿主推送是**唯一出口**：`pushToAdapter()` 必须**同时**喂两个适配器，`ChapterPanelState`
  新增 `srRecords` / `srJob` 也必须加进 `refreshChapterState` 的字段清单 ——
  漏一处的表现是"那处 UI 永远停在打开那一刻"（这个坑本文件上面记过很多次）。
- 超分记录的真值与写入在 `ReaderTranslationController`（表 `imported_page_sr`，DB v19）。
  详见 `sr/CLAUDE.md` 的「超分记录系统」一节。

**底部两个浮层组（超分 / 翻译）各自贴一侧、不共用容器**：
`sr_group` 贴左下（`bottom|start` + `marginStart`），`translate_group` 贴右下（`bottom|end` + `marginEnd`）。
⚠️ **组内按钮顺序逐位对称**：三态/切换在前、主按钮居中、**删除压最右**
（用户口径：「和翻译一样，**删除按钮**都在当前组件的最右边，**不是**把整个超分组件移到右边」——
曾误读成"整组挪到右边"，套了一层 `floating_groups` 容器，已撤）。
守卫：`SrReaderWiringTest.srGroupMirrorsTheTranslateGroupIncludingTheDeleteButtonPosition`。

⚠️ **`MangaReaderActivity.prefs` 是 `getSharedPreferences("manga_reader")`**（阅读器自己的
模式/动画/背景），与 `PreferenceManager.getDefaultSharedPreferences` 是**两个文件**。
超分 / Anime4K 的键都在**默认** prefs 里 —— 传错文件的表现是「永远读不到模型」：
点「超分本章」弹「请先选择并下载一个超分模型」，而**手动超分完全正常**（它走控制器、用的是
默认 prefs），正好把这个 bug 掩盖掉。同一个坑 `anime4kPreviewFor` 的注释里早就写着。
**给 SR/Anime4K 的任何一个 API 传 prefs 之前，先确认它是哪一份。**

**「超分本章」任务**：`startSrChapterJob(chapterIndex, pages)` —— 逐页 `runSr` + 回收原图，
进度进 `srChapterJob`（StateFlow），宿主 collect 它来推面板。
⚠️ **不走 `ChapterJobRunner`**（超分本身全局串行，套 runner 只有负担）；
⚠️ **离开阅读器必须 `cancelSrChapterJob()`**：它没有通知栏入口，而进行中提示是**系统级浮层**，
跑到后台会把芯片贴到别的应用上且无人能收（`unbindUi` 已把回调清空）。

**互斥**（都要抢 `OcrLock`，同时开就是互相拖死）：

判据**收敛成一个** `ReaderTranslationController.busySource()`（用户口径 2026-10-01）：
`CHAPTER_SR`（超分本章）＞ `CHAPTER_TRANSLATE`（翻译本章）＞ `PAGE_MODE`（自动/增量/手动在途）＞ `NONE`。
**顺序即优先级** —— 同一时刻可能同时成立（超分本章**不会**自动停掉页面模式），提示必须报最"重"的，
报轻的那个用户照着关完会发现按钮还是不能用。

**两枚单页按钮被"自动进程"挡住时：单击提示、双击关掉它**（与右下角翻译按钮**同一套语义**：
`Hint(text)` / `CancelledToManual` / `CancelledBatch(text)`）：

| 入口 | 被挡于 | 行为 |
|---|---|---|
| `btn_sr_page`（超分/重新超分） | 上述**任一种** | 单击 `busyHint()`、双击 `cancelBusy()` |
| `btnTranslate`（单页翻译） | 同上 | 同上（`PAGE_MODE` 仍走 `CancelledToManual` 老语义） |
| 「翻译本章」章卡片主按钮 | 超分本章 | 一次性拒绝 + `reader_translate_blocked_by_sr` |
| 「超分本章」章卡片主按钮 | 翻译本章 **或 页面模式** | 一次性拒绝 + `reader_sr_blocked_by_translate` |

- ⚠️ `cancelBusy` **只关挡路的那一个**，绝不"顺手全清" —— 超分本章在跑时双击翻译按钮，
  不该把另一个章正在跑的翻译也停掉。
- ⚠️ 被挡时 `btn_sr_page` **保持可点**（不能置灰）：置灰就没有"第一次提示"了，用户只会觉得按钮坏了。
  守卫 `ReaderBusyExclusionGuardTest` 用下标比较锁住「`isEnabled = false` 必须在 busy 分支之后」。
- ⚠️ 互斥判据**不许**引用 `SrSettings` / `sr_reader_auto`：用户口径原话是
  「**不管有没有开启翻译自动超分**，都不能点超分和重新超分按钮」。
- 「超分本章」也把**页面模式**算作被挡：自动/增量是**持续模式**、不会自己停，放进来就是两条链路一直抢锁
  （提示文案 `reader_sr_blocked_by_translate` 已相应改成「翻译正在跑（本章 / 自动 / 增量）」）。

守卫：`SrRecordSystemWiringTest`（记录落库 / 迁移 / 同模型判据唯一 / 互斥两道 + 提示 / 面板复用与切换 /
离开阅读器停任务 / 逐页回收）+ `ReaderBusyExclusionGuardTest`（优先级顺序 / 只关一个 / 入口接线 /
与自动超分开关无关）。

### ⚠️ 「翻译时自动超分」的作用范围（用户口径 2026-10-01）

> 「翻译本章或者超分本章都不受开启翻译自动超分开关的影响，这个开关**只影响手动/自动/增量**这三个模式」

- `sr_reader_auto`（`SrSettings.isAutoEnabledForReader`）**只**决定「页面模式」下翻译时要不要顺带超分
- ⚠️ **「翻译本章」不自动超分**：`translatePhase` 被章节批量与增量**共用**，靠 `allowAutoSr` 区分 ——
  **只有增量传 true**（`translatePhase(page, prep, label = "增量", allowAutoSr = true)`），
  章节走默认 false。以前无条件调 `maybeStartAutoSr`，于是开关开着时「翻译本章」会**偷偷把整章也超了**，
  与"这个开关只管三个页面模式"直接矛盾。要超分就再开一轮「超分本章」（两个批量已被上面的互斥串起来）。
- **「超分本章」和单页超分按钮**一直不受这个开关影响（`srActionOf` 只看总开关 `sr_reader_enabled`）。
- 守卫：`ReaderBusyExclusionGuardTest.onlyTranslateAheadAutoUpscales` + 既有的
  `ReaderIncrementalConcurrencyGuardTest`（后者的源码匹配只取到 `label = "增量"` 为止，别写全参数）。

### ⚠️ 弹窗 / 面板配色：**唯一入口**，新增控件不许漏登记（2026-09-28）

用户口径：「你加的页面或者弹窗一个都没有适配主题配色」。

- **阅读器深浅跟的是「阅读背景」**（`reader_background` → `MangaReaderActivity.isDarkBackground()` /
  `NovelPanelStyle.isDarkBackground`），**不是系统主题**。所以裸 `AlertDialog.Builder(this)` 在
  「系统浅色 + 阅读背景深色」下就是一块突兀的白底。
- **漫画与小说**统一走 `utils/ReaderDialogs.kt`（`object ReaderDialogs`，唯一入口）：
  `context(ctx, dark)` 强制 uiMode 造深/浅上下文、`style(dialog, dark)`、`show(...)` 一把建弹窗、
  `tintCustomView(root, dark)` 给自定义内容视图重着色。
  ⚠️ **不是"换底色 + 递归刷字色"那套**：面板底色来自主题、字色来自阅读背景，两者不同源会出**白底白字**，
  所以必须强制 uiMode（守卫 `ReaderThemeGuardTest`）。写法一致、判据一致，别再各处手写。
  ⚠️ 小说侧原有那份 `NovelDialogs.kt`（`applyNovelDialogTheme`）**已删除并合并到这里**，别照旧名字找。
  调用方：`MangaReaderActivity` / `ReaderChapterDialog` / `ReaderMenuSheet` / `ReaderPagePreviewDialog` /
  `ModelManagementFragment` / `NovelReaderActivity` / `NovelPanelSheet` / `NovelTocDialog`。
  `ReaderChapterDialog` / `ReaderPagePreviewDialog` / 下载弹窗 / 各类确认弹窗**全部**过它；
  `ReaderPagePreviewDialog` 与 `ReaderPagePreviewDialog.PreviewAdapter` 都要传 `dark`（格子底有深色变体
  `bg_preview_cell_dark` / `bg_preview_current_dark`）。
- **面板里的控件必须登记进 `applyPanelTheme`**（`ReaderMenuSheet` 的 label/sub 两个清单 + `NovelPanelSheet`
  同类清单）：漏登记的控件在深色面板下保持布局默认深色字 → 压在同色背景上看不见。
  **带 drawable 底的控件尤其要查**（浅色底在深色面板上是亮斑）：颜色矫正的「原图 vs 处理后」对比格子
  （`cell_orig_preview` / `cell_proc_preview`）就是这样补的。
- ⚠️ **一个控件只能出现在两个配色清单之一**（`panelLabelIds` / `panelSubIds`）：两段 `setTextColor`
  先后执行、**后写的赢** —— 同一个 id 两处都登记，它会**静默变成次要色（淡色）**。
  「翻译模型最大同时请求数」的标题就这样被弄淡过、修好后又被盖回去一次。
  现在两个清单是 **`intArrayOf` 字段 + `// PANEL_THEME_*_IDS_BEGIN/END` 标记**，
  `PanelThemeGuardTest` 只读标记之间的内容做机械校验（重叠 / 漏登记都会红）。
  ⚠️ 别把清单改回内联 `listOf(...)`：守卫依赖标记，也依赖"清单只有一处"这个事实。
- ⚠️ **弹窗一侧见 `mangaimport/CLAUDE.md` 的「弹窗 / 面板配色」一节 + `ReaderThemeGuardTest`**：
  弹窗必须用 `ReaderDialogs.context(context, dark)`（强制 uiMode）而不是"换底 + 刷字色"，
  否则会出现**白底白字**（面板底色来自主题、字色来自阅读背景，两者不同源）。
- **自查手法**（值得复用）：把布局里所有 `android:id` 抽出来，逐个在对应 Sheet 的 Kotlin 里搜
  `R.id.<id>` —— 搜不到的基本都是「没接主题/没接线」的嫌疑（容器与 `sel_*` 选中圈是例外，
  它们由 `reapplySegments` 统一处理）。



### 压缩包元数据 → 自动填简介（2026-10）

- **只做一件事**：导入时读包里的 `ComicInfo.xml`（ComicRack 标准）与 `meta.json`（nhentai 等下载器），
  生成简介写进 `ImportedManga.description`。⚠️ **不改书名**（书名仍取文件名）、不改阅读方向、
  不往书架行加字段、**不回填老条目**（用户口径：只管新导入，也没有「手改后被覆盖」的风险 ——
  生成只在导入那一次，之后用户照旧在「编辑简介」里改）。
- **两种格式都在时 ComicInfo 优先、meta.json 只补空**，且**同一格式内浅层目录优先**。
  ⚠️ 优先级**不能只按目录深度排**：两者常常都在根目录（深度都是 0），只比深度就成了
  「谁在列表里靠前谁说了算」—— 调用方换个遍历顺序，作者/系列就换了个来源（单测抓到过）。
- 字段映射：`Writer`/`artist 标签` → 作者；`Translator`/`group 标签` → 社团·译者；
  `Series`/`parody 标签` → 系列；`Tags`（csv）/`type=tag` → 标签；`Web` → 来源
  （meta.json 没有 Web 时用 `id` 拼 `https://nhentai.net/g/<id>/`）。
  ⚠️ meta.json 的 `language` 标签是 `japanese` 这种**名字**，不是 ISO 码，不能塞进 `languageIso`。
- ⚠️ **坏数据绝不能让导入失败**：解析异常/截断一律返回 null，只影响「少填一段简介」。
  特别地：被截断的 XML（`<ComicInfo><Title>oops`）在 KXmlParser 下**不一定抛**，
  会走完事件循环留下一个全 null 的对象 → 所以解析结果必须过 `isMeaningful()`
  （一个字段都没解析出来 = 没有元数据），否则会填出一段空简介（单测抓到过）。
- ⚠️ 简介里**标签要截断**（`MAX_TAGS_CHARS`）：有几十个标签的本子，全拼进去在只有两行的简介里
  等于什么都看不见。
- 读取路径按格式分两条，都收敛在 `MangaImporter`：
  - **zip/cbz**：`readZipMetadataTexts` 直接从 `ZipFile` 读条目（不解压）
  - **rar/cbr/7z**：解压时 filter 放行元数据文件（`ComicMetadataParser.isMetadataEntry`），
    再从 `pages/` 里读（`readDirMetadataTexts`）；解出来的元数据**留在 pages/ 不删**，
    阅读侧只枚举图片，不受影响
  - 都按 **basename** 匹配（忽略大小写与所在目录）：不同工具写的位置/大小写都不一样
  - 条目超过 `MAX_METADATA_BYTES`（256KB）直接跳过 —— 有条目自称 `meta.json` 其实是几百 MB 时
    不能整个读进内存
- 守卫：`ComicMetadataTest`（合成样本：两格式解析、优先级/补空、BOM、坏 XML、真实 zip 与解压目录的
  读取路径、超大条目跳过、简介截断）+ `ComicMetadataRealFileTest`（真实文件，靠
  `COMIC_METADATA_REAL_FILE=<路径>` 环境变量 opt-in，默认 skip）。


### 译文文本规则 + 底图/译文分层（2026-09-19）

- **分层存储是既有约定**（见 `data/CLAUDE.md`）：`originalImagePath` + `bubbleRects` + `sourceText/translatedText` 是唯一事实来源，渲染后的 `imagePath` 已停止写入（仅旧行回退）。**因此「改设置/改规则即时生效、不必重翻」在架构上是被这条保证的** —— 文案里也别写成"渲染图缓存"。
- **`TranslationTextRules`（`manga/config/`）有两件事**，别混：
  1. `normalizeEllipsis` —— **硬保证、无开关**：删掉「两侧都是点」的换行。根因是模型把一条编号译文写成多行，而 `NUMBERED_TRANSLATION_REGEX` 的 `[\s\S]*?` **跨行捕获** → 译文真的是 `".\n.\n."`（横排三行各一点；竖排 `\n` 还占一个字符格）。调用点：`parseNumberedTranslations` / `…Partial`（解析）+ `TranslateUtils.translateBubbles` 出口（顺序路径兜底）+ `OverlayRenderer`（覆盖修复前入库的旧数据）。**幂等，可重复调**。
  2. 用户**译文替换表** —— **在渲染时套用**（`OverlayRenderer.renderOverlay(..., replacementRules)` 里 `process()`），**不是**翻译时。⚠️ 别挪回 `translateBubbles`：既逼用户重翻，非幂等规则（`a`→`aa`）还会被套两次。规则只作用于**译文**，切「原文」态不套（否则用户看到的"原文"被改过）。
- ⚠️ **规则变更必须作废已渲染译图**：`ReaderTranslationController.refreshIfRulesChanged()`（比对原始 JSON 串，**不解析**）→ `renderGeneration++` + 取消在途 `prewarmWebtoon`/`showPartial` + 清 `renderLru`/`webtoonLru`；阅读器 `onStart` 拿到 true 后**分页重渲染当前页 / Webtoon 先 prewarm（不要先 rebind，会闪回原图）**。在途任务写缓存前要比对代次，否则旧规则位图会被预热按「缓存为空」过滤永久跳过。
- ⚠️ **`OverlayConfig` 只能整份从 `getOverlayConfig(prefs)` 取**：曾有一处手写位置参数只传 5 个字段 → 新加的 `replacementRules` 与合并/对齐/字距行距全落默认 → **悬浮窗缓存命中的页看不到用户规则**。加字段时检查所有构造点。
- ⚠️ **`BitmapLruCache`（截图查看器）的 key 要带规则指纹**（`MangaViewerActivity.overlayCacheKey`，且必须以 `${entryId}_` 开头——`retainEntries` 靠它认 id）；重翻失效用的也是同一个函数。
- **排版层**（`LayoutEngine`）：竖排直接去掉 `\r`/`\n`（竖排是连续竖流，换行只占一个空白格）+ 横排断行/竖排分列的**切点绝不落在点串中间**（`pullBackToDotRunStart`，点串比整行还长才硬切）。守卫：`LayoutEngineTest`（含竖排硬切兜底）、`TranslateUtilsNumberedParseTest`、`TranslationTextRulesTest`。

### 悬浮球大小 / 不透明度（2026-09-19）

- `FloatingBallStyle`（`utils/`）是**唯一来源**：`Floating_Ball_Size`（50–200%）/ `Floating_Ball_Alpha`（10–100%），**游戏与漫画共用一份**；两个 Service 建球时与 `prefChangeListener` 收到这两个 key 时都调 `apply`。
- ⚠️ **尺寸改子视图 `layoutParams`，不走 `scaleX/scaleY`**：点击脉冲/双击/长按反馈动画收尾都会把 scale 收回 `1f` → 走 scale 的话"点一下球，用户设的大小就没了"。同理**长按动画的收尾 alpha 必须用用户设置值**（`ballAlpha`），否则一次长按把不透明度抹成 1。
- ⚠️ **尺寸变了要把窗口夹回屏幕**（`FloatingBallStyle.clampIntoDisplay`，两个 Service 的 `applyBallStyle` 都调）：窗口 x/y 是左上角、球只向右下长 → 调大后贴边会出屏，转屏后甚至整球不可达。
- 错误态红圈只改自己那层的 alpha，不碰根视图；漫画藏球用 `visibility=GONE`（不动 alpha）→ 三者不冲突。
