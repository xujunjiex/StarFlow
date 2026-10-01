# 漫画模块（`manga/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 漫画模块

**核心文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接，engine/）、`ComicBubbleDetector.kt`（RT-DETR-V2 检测，engine/）、`PPOcrV5Engine.kt`（PP-OCRv5 det+rec，engine/）、`MangaOcrBridge.kt`（manga-ocr，engine/）、`TextRegionMerger.kt`（识别后合并，merge/）、`OverlayRenderer.kt`（渲染，render/）
**工具类：** `engine/GeometryUtils.kt`（凸包、点在多边形等几何算法）、`engine/OnnxUtils.kt`（ONNX 张量提取、资源拷贝）
**调试渲染子包 `manga/debug/`：** `MangaDebugOverlays.kt`（object：4 个 render*DebugOverlay 纯渲染函数 + applyCropDimming/createInfoPanelView/createToggleButton/MaxHeightScrollView 辅助）、`MangaDebugPanelController.kt`（全屏 debug overlay 窗口骨架 + 折叠状态机，4 个引擎共用的窗口管理）、`MangaDebugSliders.kt`（object：PP-OCRv5/v6 参数滑块面板构建器，注入 `CustomPreference` + `context`，无服务引用）。
**滑块面板的控件构造全部收在 8 个私有 helper 里**（`panelContainer`/`sliderRow`/`sectionTitle`/`addSlider`(竖排 标签在上)/`addInlineSlider`(横排 标签在左)/`addSwitch`/`addLimitTypeToggle`/`addResetButton`）——两个公开入口只保留「刻度 + prefs key」，每个参数一条 `SliderSpec`。改滑块样式/尺寸只动 helper，不要在入口里重写控件。⚠️ 两个面板的**布局形态不同**：v5 的 `merge_gap` 是竖排、v6 的是横排内联；`large_box_ratio` 两边都是横排。
⚠️ **滑块刻度一律用 `LinearScale(lo, step, max)`（位置 k ↔ 取值 `lo + k*step`），不要再写一对互逆函数。** 散写的公式出过一串问题：`min_height` 默认 30 落在位置 11、而位置 11 代表 31（加载显示 30、一碰变 31）；`max_candidates` 1000→1006；`rec_batch_num` 显示 5 而实际是 6；`box_thresh` 0.3→0.2991；`limit_side_len` 的 snap 写法**不是单射**（100 个位置里有两个代表同一个值，拖了没反应）。改成三元组后两条性质是构造性保证的：**默认值精确落在格点上** + **单射**。新增参数必须登记进 `SCALE_REGISTRY`。
⚠️ **UI 层无法用 `SeekBar.setProgress(k, true)` 模拟用户拖动**（第二个参数是 `animate` 不是 `fromUser`）—— 见根文件「高频踩坑」同名条目。
回归守卫 `MangaDebugSlidersTest`（15 例：控件结构、标签序列、刻度逐位置全量检查、恢复默认、开关/limit_type 接线、真实触摸拖动）。
这些函数从 `MangaFloatingService` 提取，无状态、依赖全部参数化；`show*DebugView` 等编排函数仍在主服务里，窗口骨架由 `MangaDebugPanelController` 承载。

**检测引擎（DetEngine）：**
- `MLKIT(0)` — ML Kit 检测+识别一体化
- `RT_DETR_V2(3)` — RT-DETR-V2 气泡/文字检测
- `PP_OCR_V5(4)` — PP-OCRv5 独立流水线
- `PP_OCR_V6(5)` — PP-OCRv6 独立流水线（**默认**）

**OCR 引擎（OcrEngine）：**
- `MLKit(0)`、`MangaOcr(1)`、`PPOcrV5(4)`、`PPOcrV6(5)`（**默认**）

**OCR 统一引擎选择层（OCR 统一计划的源头，重要）：**

游戏/漫画引擎不再是两套独立 prefs，统一到单一共享源 **`Ocr_Engine_Group`**：

- `OcrEngineManager`（utils/）— `getOcrEngineGroup()`/`setOcrEngineGroup()`：首次升级迁移（漫画 legacy 键优先、游戏兜底），新装默认 `PP_OCR_V6`
- `OcrEngineGroup`（manga/）— 4 组固定组合：`MLKIT`/`PP_OCR_V6`/`PP_OCR_V5`/`RT_MANGA`（RT-DETR+manga-ocr），每组合定义 `gameEngine`/`mangaDet`/`mangaOcr`/`sourceLangs`/`needsDownload`/`requiredModelsRes`
- 引擎读取统一：`MangaFloatingService.loadConfig()`、`GameOcrEngine.recognize()`、`FloatingBallService` 均从 `OcrEngineManager` 读；`applyCombo`/`cycleOcrEngine` 改动写回共享
- **模型管理页选择**：点 4 组标题选 OCR 引擎（当前组高亮「当前使用」；未下载模型组置灰，点击弹提示需要哪些模型）；与悬浮窗同步
- **首页 top_bar 双行状态栏**：🔔通知 与 ❓帮助 之间两行——`OCR模型：xxx` + `翻译模型：xxx`（`refreshEngineStatusBar()`，OCR 组/翻译引擎变化实时刷新）
- **源语言动态（首页/悬浮窗）**：30 种语言池按当前 OCR 组排序（支持在前），不支持的下移置灰，点击弹「该语言当前 OCR 模型不支持，请使用 X」；悬浮窗语言循环仅「常用 4 语言（`OcrEngineGroup.FLOATING_COMMON_LANGS`）∩ 当前组支持」
- **目标语言过滤（白名单）**：**预制 Hy-MT2** 仅 38 种目标语言（`LlamaCppLanguages.hyMt2SupportedCodes`），游戏页/文本页按这 38 种置灰（旧 9 种黑名单已废）；**导入的任意 GGUF**（`hyProfile=false`）与 NLLB/API 全支持。判定走 `LlamaCppModelStore.isHyMt2ActiveFromPrefs`（读 prefs 镜像 `LlamaCpp_Active_Is_Builtin`，所以切换模型时必须同步写镜像，且判据是模型自身 `hyProfile` 而不是某个写死的 id）
- **目标语言列表资源按引擎区分**：`getLanguagesList` 在 TextApi=AI 时，预制 Hy-MT2 用 `res/raw/hy_mt2_text_support_languages.xml`（38 种，含 zh-TW 中文台湾），NLLB 用 `nllb_text_support_languages.xml`（68 种）。勿让 Hy-MT2 复用 NLLB 资源——否则选不到 zh-TW
- **文本翻译源语言不受 OCR 影响**：`getLanguagesList(type=1, ocrGroup=null)` 全量 30 种

**引擎切换架构（重要）：**

游戏模式和漫画模式均使用"单一声源"模式避免分支遗漏：

- **游戏模式**（`FloatingBallService`）：`engineCycle` 数组定义切换顺序 `[V5, V6, MLKIT, MANGA]`，`engineLabel()` 统一值→标签映射，`cycleOcrEngine()` 用 `engineCycle.indexOf() + 1` 查找下一个
- **漫画模式**（`MangaFloatingService`）：`engineCombos` 列表定义 det+ocr 固定搭配，包含 `key`/`detEngine`/`ocrEngine`/`labelRes`/`needsDownloadCheck` 五元组。四个方法覆盖所有需求：
  - `currentCombo()` — config → 组合
  - `comboLabel()` — 组合 → 标签字符串
  - `isComboAvailable()` — 检查是否可用（自动处理 manga-ocr 下载检测）
  - `applyCombo()` — 应用组合（更新 config + 持久化 prefs + 启动引擎）

**添加新引擎只需：** 1) 枚举加值 2) `engineCombos`/`engineCycle` 加一项 3) `applyCombo`/`initEngineAsync` 加 `when` 分支。不需要同步多个分散的 `when` 表达式。

**⚠️ 引擎默认值只有一个真值：`OcrEngineGroup`。** `loadConfig()` 不再读 `Manga_Det_Model`/`Manga_Rec_Model`，而是取 `group.mangaDet`；那两个旧键现在只是 `OcrEngineManager.getOcrEngineGroup()` 里的一次性迁移输入，全新安装走 `OcrEngineGroup.PP_OCR_V6`。**改默认引擎 = 改 `OcrEngineGroup`**，别再去找 prefs 默认值。

**合并机制：**

| 检测器 + 识别器 | 前合并 (MangaSpatialGrouping) | 后合并 (BubbleDetector) | 说明 |
|---|---|---|---|
| RT-DETR-V2 + 任意 | ❌ | ❌ | 检测器直接输出气泡级结果 |
| MLKit 独立 | ❌ | ✅ | 行级文字块 → BubbleDetector 合并成气泡 |
| PP-OCRv5/v6 独立 | ❌ | ❌ | 检测框 → `detectWithPPOcrV5/V6` 内部 `TextRegionMerger` 识别后合并（增量路径同样走它） |

**单字符噪声过滤：** `textBlocksToBubbleRegions` 过滤单字符纯标点（OTHER_PUNCTUATION、DASH_PUNCTUATION、START/END_PUNCTUATION、MATH_SYMBOL、OTHER_SYMBOL），避免标点符号被当作独立气泡翻译。

**合并开关 `Manga_Text_Merge`**（个性化页，默认 true）：关闭时 `TextRegionMerger.merge` 每个 region 独立成组（表格/多栏场景），单测 `TextRegionMergerTest.mergeDisabled` 覆盖。调试合并问题时先确认此开关未关。

**识别后合并（`TextRegionMerger`，V5/V6 独立路径 OCR 后调用，两阶段）：**
- **阶段一 `canMergeRegion`（局部成对判定）**：字号比/方向/宽高比校验 → 三种对齐任一满足才连边 → 连通分量。判定参数可在调试面板滑块调（`merge_discard_gap` 粗筛 1.5×字号 / `merge_char_gap2`）
- **对齐判定（三阈值分工，勿再改回投影重叠）**：间隔 gapTol 宽松 2×字号（管相邻，容纳行距 68-75px）、中心对齐 centerTol 宽松 2×字号（管同一句/列）、边缘对齐 edgeTol 严格 1×字号（管起始位置对齐，错位即分）。优先级：中心对齐(2D) > 边缘对齐 > Tilted，**错位绝不兜底合并**
- **对齐轴**：横排（宽>高）多行上下堆叠 → 垂直行距小 &&（左对齐||右对齐||水平中心对齐）；竖排（高>宽）多段左右排列 → 水平列距小 &&（上边缘对齐||垂直中心对齐）。竖排无底部对齐
- **阶段二 `splitTextRegion`（排序 + 相邻间隙跳变断点）**：按阅读顺序排序后，只在**相邻行/列间隙显著跳变**处切分（间隙 > 邻居中位数×1.6 且 > 1.2×字号）。**不用全对全 MST**（曾在不相邻行间连边导致交叉合并）；**不用全局 mean+std 阈值**（大间隙会被均值吞掉，日志 gaps=[67,64,129,70,71] threshold=129.3 放过 129）。**2 元素组件直接信任 canMerge**
- **调试日志**：PP-OCRv5/v6 调试模式自动 `TextRegionMerger.enableDebugLogging(true)` → 逐对 `canMerge [i]"文本" + [j]"文本" → REJECT/ACCEPT 原因`（tag `TextRegionMerger`，`[i]` 对得上调试面板「原始识别」编号）。`enableDebugLogging` 默认关闭，只该在调试路径开启

**翻译流程：** 截图 → 检测 → OCR → 气泡合并（按需）→ 翻译（每气泡并行）→ 覆盖渲染

**竖排方向（可配置，`Manga_Text_Direction`）：**
- 个性化页「漫画翻译结果设置」→ 竖排方向：`0`=VERTICAL_RL（列从右到左，默认，传统日漫）/ `1`=VERTICAL_LR（列从左到右）
- `MangaModeConfig.textDirection` 从 prefs 读；扩展属性 `verticalTextDirection` 统一竖排方向判断（`MangaModeConfig.kt`）
- ⚠️ **这个设置只适配 PP-OCRv5 / PP-OCRv6**，其余引擎不接（2026-09 用户明确）：
  | 引擎组 | 是否适配 | 理由 |
  |---|---|---|
  | **PP-OCRv5 / v6** | ✅ | det 候选序 + 合并拼接序都按设置 |
  | **ML Kit** | ❌ 固定右→左 | 本就不适合复杂漫画场景，不为它做左右适配 |
  | **RT-DETR-V2 + manga-ocr** | ❌ 固定右→左 | 该模型只识别**日文**，右→左是唯一正确列序 |
  ⚠️ **`BubbleDetector.doDetect` 现在确实带方向参数，但语义被严格限定**：它只决定**组标签/渲染方向**
  （`detectBubbles` 传 `config.verticalFlow.toTextDirection()`），**组内读序刻意固定右→左**。
  别因为"它接了参数"就去改组内读序。RT-DETR（`MangaSpatialGrouping.sortByMangaReadingOrder`）仍然不接受方向参数。
- ⚠️ **RT-DETR + manga-ocr 的横竖方向：不做判断，读设置**（2026-09 用户要求，`Manga_RT_Text_Direction`）
  - 位置：个性化页「漫画翻译结果设置」→ **识别自由文字**正下方「RT-DETR 渲染方向」，两态
    `0`=竖排（右到左，默认）/ `1`=横排渲染；解析/单一来源 `manga/config/RtTextDirection.kt`
    （`fromPref` 未知值一律回退竖排；`resolve(detEngine, rtDirection, mangaTextDirection)`）
  - **为什么**：RT-DETR 只回传**矩形气泡框**（没有文字行 quad），旧实现用
    `BubbleOrientation.textVerticalFromBubbleAabb`（`h > w`）猜 —— 而漫画里 2~4 列竖排的对话框
    天生「宽 > 高」→ **必被猜反**，译文按行渲染。该函数已删除，不要再加回来。
  - 生效链：`RtTextDirection.KEY` 在 `MangaFloatingService.watchedKeys` 里（改完实时重读 config）；
    `MangaModeConfig.rtTextDirection` / `BatchPipelineConfig.rtTextDirection` +
    扩展 `renderTextDirection`（RT 用本设置，PP/ML Kit 用 `textDirection`）→
    `DetectionBridge.detectWithRTDetrV2 / recognizeCroppedBubbles(+Streaming)`（参数 `rtDirection`）
    → `TextBlockInfo.isVertical` → `BubbleRegion.direction`
  - ⚠️ 两条路径必须一起改：分批管线（`BatchOcrOps.recognizeCroppedBubbles(crops, lang, rtDirection)`）
    与普通路径（`DetectionBridge.runOCR(..., rtDirection)`）；阅读器 `translatePlain` 两条分支都传
    `cfg.rtTextDirection`，否则「同一页有时竖排有时横排」
  - ⚠️ 它与 `Manga_Text_Direction`（竖排**列序**）是两件事：RT 路径**不看**列序设置（日文恒右→左），
    PP 路径**不看**本设置（PP 自动判横竖）。渲染覆盖点（悬浮窗 3 处 + 阅读器 `renderBubbles`）传的是
    `config.renderTextDirection` / `RtTextDirection.resolve(runDet, …)`，不是裸 `textDirection`
  - 回归守卫：`RtTextDirectionTest`（解析/默认值/resolve/XML 位置与 key）+ `IncrementalBatchPipelineTest`
    的「rt-detr 渲染方向来自 rtTextDirection 而非竖排方向」「选择横排时气泡方向为横排」「pp 路线不看 rtTextDirection」
  | 层（仅 PP 路径） | 位置 | 响应 |
  |---|---|---|
  | det 候选序 | `PPOcrDetGeometry.sortDetCandidates`（`runDet` 末尾，filterDetRes 之后） | 竖排按设置取列序；横排恒上→下、行内左→右 |
  | 合并/拼接序 | `TextRegionMerger.splitTextRegion` + `merge()` 的 `sortedNodes` | 竖排 `centroidX` 按 RL 降序 / LR 升序 |
  | 行裁剪后排序 | `MangaSpatialGrouping.sortByReadingOrder(items, getRect, dir)` ← `DetectionBridge.detectAndCropPPOcrV5/V6Lines` | 返回值**直接**进 `groupByProximity`，分批边界靠它 |
  ⚠️ **横排恒左→右**：该设置的语义是「**竖排**文字的列排列方向」，反转横排会把送进翻译的
  横排句子倒过来（数据被改坏，不只是显示问题）。
  ⚠️ 阅读顺序排序收敛到 `MangaSpatialGrouping.sortByReadingOrder`（泛型，按 `getRect`）——
  排查时曾一次揪出 **3 处各写一份、其中 2 处写死右→左**（`DetectionBridge` 的两个 detectAndCrop）。
  回归守卫：`PPOcrDetGeometrySortTest`（12）+ `MangaReadingOrderFlowTest`（9）
  + `MangaTextDirectionMappingTest`（5）+ `IncrementalBatchPipelineTest` 的「检测阶段收到配置的竖排方向 V5/V6」
  + `PPOcrDetGeometryRotationInvarianceTest`（5，帧朝向不变性）
  ⚠️ 报告里提过的 ML Kit 方向适配（`OCRTextRecognizer` 方向重载 / 漫画 Kit 组内列序）
  已按用户要求于 2026-09-18 **整体删除**：ML Kit 的 block 是它自己的版式分析产物
  （官方文档原话是 *"a contiguous set of text lines, such as a paragraph or a **column**"*），
  一个竖排整列常常就是**一个 block**，块内读序封在它手里 —— 重排只能重排**块之间**，
  表现为「设置时灵时不灵」，比不支持更难解释。**不要再加回来。**
- ⚠️ **排序权威只有 `sortDetCandidates` 一处**：`findContours` **刻意保持栅格扫描、不接设置**（`PPOcrDetGeometrySortTest.findContours_scanDirectionIsFlowIndependent` 钉死此契约）。曾经给扫描方向也接上设置 —— 那是第二套排序机制，而 `runDet` 末尾的重排会把它完全覆盖，**没有用户可见效果**却让「列序谁说了算」变成两个答案
- `boxes` 与 `scores` 是**平行数组**，任何重排必须同步（`sortDetCandidates` 内部用同一个 `perm` 索引两者；长度不一致时只重排 boxes 并记 W 日志）
- `verticalScanFlowIsLr` 是 `PPOcrDetGeometry` 的 object 级 `@Volatile` 状态，由两个引擎的 `refreshParams()` 从 prefs 刷新 —— **所有 det 入口（`runOCR`/`runDetForBoxes`/`runDetForDebug`）都在 `runDet` 前调它**，新增 det 入口时必须一并调用，否则设置不生效
- ⚠️ **渲染时实时覆盖**：`OverlayRenderer.renderOverlay(verticalDirection)` 把所有竖排气泡（RL/LR）方向统一为当前配置，横排保持 HORIZONTAL——**历史/缓存命中数据的气泡 direction 存的是旧设置值，渲染时仍按当前设置显示**。所有渲染入口都传方向（MangaFloatingService 直接渲染 3 处 + `TranslationCacheManager.renderOverlay` 2 处，经 `OverlayConfig.textDirection`）
- ⚠️ 渲染层的「组内顺序」与 OCR 层的「拼接顺序」是**两件事**：前者读 `TranslatedBubble.direction`（渲染时被设置覆盖），后者读 OCR 时的 `TextRegionGroup.texts`（存库、不重排）。所以**只改设置不改 OCR 层**时，译文渲染对了但送进翻译的句子仍是旧序 —— 这正是之前「设置看着不生效」的来源
- ⚠️ `TextRegionGroup.members`（= `nodes.map{}`，`nodes = nodeSet.toList()`）是 **Set 迭代序、不保证阅读顺序**；排好序的是 `texts` 与 `memberIndices`。读组内顺序必须用后两者（测试里踩过：读 `members` 得到错序）
- `Manga_Text_Direction` 加入 `watchedKeys`，设置实时生效
- ⚠️ **方向判定与帧朝向无关**：`sortDetCandidates` 用「高 > 宽 + 2」判竖排，是**纯形状**判据，
  与帧的绝对宽高无关 —— 竖屏 1080x2400 与横屏 2400x1080 的同内容平移副本给出**完全相同**的列序。
  回归守卫：`PPOcrDetGeometryRotationInvarianceTest`（5 例）。
  排「横屏才出问题」时先看**喂进 det 的帧与前处理本身**，不要怀疑排序逻辑（已用日志证伪过一次）。
- ⚠️ **判定有三套判据、互不相同**（同一本书换引擎/换路径可能给出不同结论）：

  | 层 | 判据 | 容差类型 |
  |---|---|---|
  | `PPOcrDetGeometry.sortDetCandidates` | `h > w + 2` | **绝对像素** |
  | `MangaSpatialGrouping.sortByReadingOrder` | `h > w + 2` | **绝对像素** |
  | `TextRegionMerger` | `quad.aspectRatio >= ASPECT_RATIO_TOL` | 比例 |
  | `BubbleOrientation`（ML Kit） | quad 长短边比 ≥1.5，退化才 AABB | 比例 |

  两处 2px 绝对容差在缩放后会漂，比例判据不会。

**排版内核（`LayoutEngine`）—— 排版一次算完，绘制只落笔：**
`OverlayRenderer` 在渲染前调一次 `LayoutEngine.plan`（`OverlayRenderer.kt:209`）算出**每行/每列的位置、字号、字距、基线**，
`VerticalTextRenderer.draw(canvas, layout, textColor, typeface)` 只按算好的坐标落笔，**不再自己决定布局**。
（旧的 `verticalLayout()` / `computeHorizontalFillLayout` / `HorizontalFillLayout` / `HORIZONTAL_FIT_RATIO` / `drawHorizontalText`
/ `drawVerticalTextRL·LR(centered, columnSpacingOverride)` 那一整套已在「排版内核 LayoutEngine」重构里删除，别再照旧名字找代码。）

三条硬约定（都在 `LayoutEngine` 里，改排版必读）：
- **用户字距/行距参与字号二分预算**：间距调大 → 字号自动变小，**任何组合下都不溢出、不截断**
- **字号线 `MIN_FONT_SIZE = 1f`**：宁可字小，**不可丢字**
- **保底**：极小气泡塞不下时，**放弃用户间距再排一次**（而不是缩到看不见或溢出）
- **横排对齐（`manga_horizontal_align`，`0/1/2` = 左/中/右）**：
  - 自动字号模式：`LayoutEngine` 按 `align` 定每行起点（左=`rect.left+pad` / 中=`centerX-行宽/2` / 右=`rect.right-pad-行宽`），
    绘制端 `VerticalTextRenderer.draw` 只按 `line.x` 落笔、不做对齐决策。
  - **非自动字号模式也必须遵循它**：`calculateCompactRect(..., align)` 的白块水平位置按对齐摆放
    （白块装得进气泡时；装不下 = 文字比气泡还宽 → 仍对称生长，否则会朝一侧溢出盖住相邻画面）。
    改之前恒居中 ⇒ 这条路径上「横排对齐」等于失效（RT-DETR 选**横排渲染**时最明显：它的选区是
    宽矩形气泡框，文字远窄于框，对齐是唯一的水平位置控制）。
  - ⚠️ 竖排不吃对齐（恒居中）—— 设置页文案「竖排不受影响」即此。
  - 回归守卫：`LayoutEngineTest.horizontalAlign_wideBubble_anchorsLeftCenterRight`（绝对锚点，RT 宽气泡形态）
    + `LayoutEngineTest.verticalDirection_ignoresHorizontalAlign`（竖排不受影响）
    + `OverlayRendererTest.nonAuto_horizontalAlign_movesWhiteBlock`（白块位置像素级断言；**去掉上面的修复即红**，
    已用「临时还原旧代码」验证过非空转。⚠️ Robolectric 的 Canvas 能栅格化矩形但**画不出字形**，
    文字像素级断言写不出来，只能用白块位置 + `line.x`）
- **绘制函数参数**：`VerticalTextRenderer.drawVerticalTextRL/LR` 支持 `centered`（列组水平居中 + 单列短文字垂直居中）和 `columnSpacingOverride`（填满列距）
- **重叠合并**：非自动大字号扩展后 `neededRect` 重叠的相邻气泡合并成一个白块（union-find），组间用记号分隔（竖排 `◇` / 横排 `──`），顺序按阅读流（RL 右列先、LR 左列先）；异方向/倾斜/字号不一致 fallback 独立绘制 —— ⚠️ **「字号不一致」这一条只对非自动字号模式成立**（自动模式下字号本来就要被重算，`sameFontOk = autoFit || 组内字号差 < 2f`），别按它去改合并判据。开关 `Manga_Overlap_Merge`（个性化页，**默认关**）关掉即各自绘制、允许白块相叠
- ⚠️ **合并块的绘制区恒等于「成员气泡矩形（`region.rect`）的并集」，与合并后文字有多长无关** —— 硬约束。曾按**文本长度**反推块尺寸（`balancedVerticalSize(合并文本长度…)` 定宽 + `maxOf(高度, 文字块高)` 定高），而块内字号又由 `LayoutEngine.plan` 按**块尺寸**二分选号（区域越大字号上限越高）→「文本越长→块越大→字号越大」正反馈 → 真机上并出**盖住大半页、字同样巨大**的白块。断言「并块 = 成员气泡并集」是唯一可靠判据，**不要**给它加字号封顶/相交占比等启发式（试过，全无效且会误伤正常合并）

**增量渲染（分批 OCR+翻译）：**
超过 6 个气泡时自动分批处理，首批翻译完立即渲染，减少用户等待时间。
- 触发条件：`Incremental_Render` 开启 + 气泡数 > 6
- 支持组合：RT-DETR-V2/MangaOcr、PP-OCRv5 独立模式、**PP-OCRv6 独立模式**（三者之外才 NotApplicable）
- 流程：检测 → 分批(2/5+3/5) → OCR第一批 → 翻译第一批+OCR第二批并行 → 渲染第一批 → 翻译第二批 → 最终渲染
- 上下文仅批次间使用：`forceContext=true` 强制开启，第二批能看到第一批译文；两批翻译完后回滚 contextHistory，不污染后续页面
- 正常漫画翻译不使用上下文（`forceContext=false` 时直接关闭）
- MangaOcr encoder 是批处理瓶颈（~3s），分批可提前显示部分结果
- ⚠️ **LlamaCpp 例外**：`IncrementalBatchPipeline.run` 对 `LlamaCppTranslation` 直接返回 `BatchOutcome.NotApplicable`——本地引擎不分批，走「一次翻译全部气泡 + 流式逐个显示」（避免多次 prefill 拖慢）。改增量渲染逻辑时不要破坏这个早退分支

**Debug 系统：** 关于页面可开启 4 个独立 debug 开关（RT-DETR-V2 / MLKit / PP-OCRv5 / PP-OCRv6），按当前 `config.detEngine` 决定走哪条 debug 路径。调试菜单为二级结构（一级标题带图标，二级开关无图标）。

**PP-OCRv5 参数调节：**
用户可通过调试面板滑块实时调整 5 个参数（存 SharedPreferences，`PPOcrV5Engine.refreshParams()` 每次 OCR 前读取）：

| 参数 | 键名 | 默认值 | 范围 | 作用 |
|------|------|--------|------|------|
| 检测置信度 | `ppocr_det_box_thresh` | 0.3 | 0.01-0.5 | box_thresh，低于此值的检测框被丢弃 |
| 扩展比例 | `ppocr_det_unclip_ratio` | 1.6 | 1.0-3.0 | unclip 扩展，越大检测框越宽松 |
| 识别置信度 | `ppocr_text_score_thresh` | 0.5 | 0.1-0.9 | text_score_thresh，低于此值的识别结果被丢弃 |
| 大框过滤 | `ppocr_large_box_enabled` | false | 开/关 | 过滤占图片比例过大的检测框 |
| 丢弃比例 | `ppocr_large_box_ratio` | 0.6 | 0.3-0.8 | 大框过滤阈值（宽/高/面积占图片比例） |

- `DET_THRESH`（二值化阈值）= 0.1f，硬编码不可调，影响所有检测路径
- `DET_MAX_CANDIDATES` = 100（连通域上限）
- 调试面板默认折叠，含图例说明（绿=检测框、青=合并区、红虚线=检测丢弃、橙虚线=识别丢弃）
- ⚠️ **改这些默认值时必须确认它仍落在滑块刻度的格点上**（`MangaDebugSliders.SCALE_REGISTRY` 会影响 `MangaDebugSlidersTest` 报错）。滑块位置与取值走 `LinearScale(lo, step, max)`，规则见「调试渲染子包」一节

**⚠️ det 输入边长 32 对齐必须四舍五入（`PPOcrDetGeometry.alignTo32`，v5/v6 共用）：**
- 官方 RapidOCR 是 `int(round(x / 32) * 32)`（`.reference/RapidOCR-main/python/rapidocr/ch_ppocr_det/utils.py:100`）
- 曾写成 `(x / 32) * 32`（整数除法 = **向下截断**）→ **两轴各自最多白扔 31px**，且两轴独立、比例改变 → **图片被压扁**：

  | 框选 | 官方(round) | 截断（旧） | 后果 |
  |---|---|---|---|
  | 94x258 | 96x256 | **64**x256 | 横 −32% → `det=10` 碎框、乱码 |
  | 148x253 | 160x256 | **128**x224 | 各缩一成 → 「武部沙織」丢字 |

- **判别特征：框选范围放大就恢复正常** —— 尺寸越大，被截掉的固定份额占比越小。
  这既是定位手法，也是别把它误判成「坐标系偏移」的依据（实测日志里 框选 116x268 → 位图 116x268，逐像素吻合）
- 症状：竖排小字认不出、det 框数暴涨、每框只认出零散一两个字（「武部沙織」→「武框織」）
- 回归守卫：`PPOcrDetAlignTo32Test`（7 例，含日志实测的 4 组尺寸 + 全量扫描断言每轴偏差 ≤16px 且恒为 32 倍数）
- ⚠️ 这与 `limit_side_len` 无关：`maxSide ≤ sideLen` **不缩放**，畸变纯粹来自对齐那一步

**倾斜文字处理：**
PP-OCRv5 检测框可能倾斜（QuadBox 4 顶点非正交），全链路处理：
- **角度检测**：`atan2(topDy, topDx)` 计算顶部边与水平线夹角，±3° 内视为正交（angle=0）
- **方向判断**：用 QuadBox 真实边长（左高 vs 顶宽×1.5），不用 AABB（倾斜时 AABB 会误判）
- **fontSize**：用真实边长（横排=leftLen，竖排=topLen），不用 AABB 短边（倾斜时 AABB 会放大）
- **合并**：`TextRegionMerger.canMergeRegion` 两分支——**AA 分支**（正交框）中心对齐(2D) + 边缘对齐（三阈值分工）；**Tilted 分支**（倾斜框）角度差 < 15°、字号差 < 0.25、AABB 距离 < 1.5×字号（曾 3× 太宽误合远距倾斜气泡）
- **渲染**：`canvas.rotate(angle, centerX, centerY)` 旋转背景+文字，正常 overlay 和调试 overlay 均支持
- **增量路径**：`recResultsToTextLines` 只有 AABB（裁剪后），无角度信息，沿用 AABB 启发式

**方向判定统一入口（`TextBlockInfo.inferredVertical()`）：** 优先级 `isVertical` 显式值 → cornerPoints **顺序无关**主轴判定（找较长对边对，按主轴 |y|>|x| 判竖/横）→ 近方形/退化交 AABB 宽高比兜底。
- ⚠️ **ML Kit 的 cornerPoints 顺序与文档不符**：按 TL,TR,BR,BL 固定下标取边，竖排方块（高197宽28）的 pt0→pt1 恰是长边 → 被当"宽"误判横排（排查时日志 `detectBubbles: block='...' bbox=...h71 w24 → vertical=false` 里的全是要判竖排的列）。必须用顺序无关算法。
- 所有 ML Kit 路径统一走它（勿再各写一份 AABB 宽高比）：`BubbleDetector.detectBubbles`(两个重载) / `DetectionBridge.ocrToBubbleRegions` / `MangaSpatialGrouping.textBlocksToBubbleRegions` / `MangaFloatingService` inline block→BubbleRegion。

**屏幕尺寸获取（`DisplaySize.size()`，唯一入口）：**
- 两个服务的 `getScreenSize()` 都只是它的薄封装。**不要再直接调 Display API。**
- ⚠️ Service 进程的 Display 取值可能被**冻结在进程初始化方向**。2K 机（物理屏 1220x2712）实测：
  设备已横屏 2712x1220，而 `cfg.orientation` / `dm` / `mode` / `rotation` / `getRealMetrics` /
  `getRealSize` / `currentWindowMetrics` **七项全部返回竖屏 1220x2712**。
- ⚠️ **分机型核实，不要预设**：另一台 1080x2400 机上 `getRealSize` 横屏下**正确**（见 memory
  `android-frozen-display-service`）。判定前先 `adb shell wm size` + `dumpsys input`
  （看 `Viewport INTERNAL` 的 `orientation` / `deviceSize`）拿到不分歧的真值。
- ⚠️ **窗口读数也不等于屏幕**：`MATCH_PARENT` 的 overlay 窗口会被系统缩小以避开手势条/导航栏
  （实测竖屏 1080x2356 vs 屏幕 1080x2400），原点却仍是 0。

### ⚠️ 取值规则：按「谁更新」而不是「谁优先」

`resolve()` 的完整规则（纯函数，`DisplaySizeResolveTest` 11 条锁死）：

```
窗口读数不比朝向新（含无窗口读数）⇒ 只信 Display 读数 + 已知朝向配对
窗口读数更新                     ⇒ 它的宽高关系就是当前朝向，像素逐轴与 Display 取大
```

**为什么用时刻而不是优先级**（本病区反复返工的根因）：这份状态有两个写入方
（`onConfigurationChanged` / `CropView.onSizeChanged`），新鲜度不同，**谁对取决于谁更晚写**。
早期实现让其中之一无条件压过另一个，于是同一类 bug 换面貌反复出现：

| 规则 | 失效场景 | 后果 |
|---|---|---|
| 窗口优先 | 框选确认后 CropView 移除 → 窗口读数冻结 | 横屏框选→转竖屏，帧仍建横屏 → **译文位置全错** |
| 朝向优先 | 配置回调漏掉（多窗口/freeform 下会被抑制） | 陈旧朝向把新鲜窗口读数对调 → **框选后必被清** |

⇒ `reportOrientation` / `reportLaidOutSize` 各记 `SystemClock.elapsedRealtime()` 时间戳，
判断从「策略」变成「事实」。**不要再引入任何优先级仲裁。**

**overlay 窗口定位：**
- **框选窗 / 结果浮层 / 缓存浮层**：`MATCH_PARENT` + `FLAG_LAYOUT_IN_SCREEN` +
  `FLAG_LAYOUT_NO_LIMITS` + 挖孔模式（见下节），**不要用 `getScreenSize()` 的像素值钉死**
- 配合 `FIT_XY`
- 全屏模式窗口 `x=0,y=0` 即覆盖整屏；框选模式窗口尺寸 = crop 宽高，位置 = `crop.left/top`

**受限区域截图：**
- 系统 `takeScreenshot()` API 对受限区域（DRM/安全应用、相册/银行/支付）返回 `onSuccess` + 全黑 bitmap 或无文字图片
- 此检测已移除（之前用的 `isRestrictedScreenshot()` 因大面积白底/黑底误判严重已删除）
- 提示信息在 FAQ Q10 说明

**统一框选确认按钮：**
- 游戏翻译和漫画翻译共用 `CropView` 内置确认按钮
- 按钮绘制在框选框底部，跟随框选区域实时移动
- 框选初始位置：`CropView.setRectCentered()` 延迟到布局完成后用 view 自身尺寸计算居中（游戏 90%×35%，漫画 80%×60%）
- 框选触点响应区域：50px 半径（`POINT_RADIUS = 2500`）

## 漫画翻译（自动翻页）

**状态机（`manga/state/MangaAutoTranslateEngine.kt`，不是 `MangaFloatingService`）：**
```
IDLE（等变化）──sim<0.95──→ MOTION（等稳定）──连续2次sim≥0.95──→ STABLE（翻译）→ IDLE
```
- IDLE：比较 `currentHash vs lastTranslatedHash`，相同则跳过
- MOTION：比较连续两次截图 hash，用户停翻后 ~1s 内稳定
- 手动翻译标志 `isManualTranslating`：自动翻译中点击悬浮球 → 跳过 pHash 门控，强制翻译

**关键变量：**
- `lastTranslatedHash` / `previousScreenshotHash` / `isManualTranslating` — 都在 `MangaAutoTranslateEngine`
- `translatedRegions` — 区域级翻译缓存，已搬到 `manga/state/RegionCacheManager.kt`；⚠️ 判重**不是** IoU（`REGION_IOU_THRESHOLD` 早已零引用），实际走文本相似度 `findFuzzyMatch`；TTL 仍是 5 分钟

## ⚠️ 低分辨率漫画「译文模糊」（**已实施** 2026-10）

用户口径：「导入分辨率较低的漫画时，翻译后渲染回漫画的**文字**也会变模糊」。
完整分析见 `sr/CLAUDE.md` 同名一节；**这里放一份是因为改的是 `render/OverlayRenderer.kt`，
而 `sr/CLAUDE.md` 只在动 `sr/` 时才会被读到。**

**根因（两条代码事实）**：
1. `OverlayRenderer.kt:98` —— `val result = original.copy(ARGB_8888, true)`：输出位图**尺寸 == 原图**，
   气泡白块与译文按**源分辨率**栅格化。800px 宽的页，字形就按 800px 的尺度烧进像素。
2. `ui/viewer/ZoomableImageView` 用 `imageMatrix`（`postScale`/`fitCenter`）缩放**整张位图**
   → 低分辨率页拉到屏幕宽（800→1080 即 ×1.35）时，文字是被插值放大的像素。

⇒ **超分救不了这条**：超分跑在 OCR 之前，文字是超分**之后**才画的。底图清晰了，文字该糊还是糊。

**修法（已实现；关键事实：Canvas 绘制时字形按当前 CTM 的有效缩放栅格化）**：
让文字在"已被放大的坐标系"里落笔即天然清晰，**不需要**放大位图或超分。

- `OverlayRenderer.renderOverlay(..., renderScale: Float = 1f)`：
  输出位图改为 `w*s × h*s`；开头 `canvas.scale(s, s)`；所有绘制坐标沿用原坐标系
  （字号/字距自动跟着放大）；底图用 `drawBitmap(original, null, RectF(0,0,w*s,h*s), filter)` 画
- `renderScale` = 显示宽 / 页图宽，**夹到 `[1f, 2f]`**（×2 是 4 倍像素，防 OOM）
- ⚠️ **调用点必须分类**：阅读器上屏（`ReaderTranslationController` 的渲染）传实际显示比例；
  **导出（`renderForExport`）/ `MangaViewerActivity` / `HistoryFragment` 一律传 1f**
  —— 它们不吃屏幕分辨率，而且 `BitmapLruCache` 按 `entryId` 缓存、放大尺寸会白占内存
- 回归：`OverlayRendererTest`、`LayoutEngineTest`（**排版不能变**，只是栅格化分辨率变）

**不要选**「在 `ZoomableImageView` 上另叠一层 overlay View」：要把气泡 + `LayoutEngine` 计划搬到
View 层并与 `imageMatrix` 逐帧同步，改动横跨 `ReaderAdapters`/`ZoomableImageView`/新 View/控制器**四处**，
收益与上面这个单参数改动**完全相同**。

**实施结果（2026-10）**：`OverlayRenderer.renderOverlay` 新增末位参数 `renderScale: Float = 1f`
（**带默认值 → 13 个调用点零改动编译通过**），只让阅读器的渲染出口 `ReaderTranslationController.renderBubbles`
传 `READER_RENDER_SCALE = 2f`。夹取范围 `[1f, MAX_RENDER_SCALE=2f]`。
守卫：`OverlayRendererTest` 4 例（尺寸/夹取/2 倍不崩）。

⚠️ **环境限制（别再试）**：Robolectric 的 Canvas **既不兑现 `canvas.scale()`，也不兑现
`drawBitmap(src, srcRect, dstRect, paint)` 的目标矩形**（实测 2 倍输出右下角全透明、白块位置不放大）。
所以「文字变清晰」**只能真机验证**，单元测试锁不到它；「排版不变」由构造保证（该分支没动任何绘制坐标）。
