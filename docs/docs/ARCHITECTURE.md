# StarFlow（星译）项目架构

> 本文档是项目的架构总览，面向开发者。生成于 2026-09，与实际源码对照（205 个 Kotlin 文件 / ~50.5k 行 main 代码）。详细设计与踩坑记录见 `CLAUDE.md`，本文件只讲结构。

## 1. 技术栈

| 层 | 选型 |
|---|---|
| 平台 | Android 10+（minSdk 29），targetSdk 35，**仅 arm64-v8a** |
| UI | 传统 Views + ViewBinding + Navigation Component（**无 Jetpack Compose**） |
| 数据 | Room（`translation_history.db` v17，fallbackToDestructiveMigration） |
| OCR | Google ML Kit / PP-OCRv5 / PP-OCRv6（ONNX Runtime 1.19）/ manga-ocr / RT-DETR-V2 |
| 本地 LLM | Hy-MT2（llama.cpp 定制版，C++ 原生，~18 万行）、NLLB（sentencepiece） |
| 网络 | OkHttp 4.11，多厂商翻译 API |
| 构建 | 单模块 `:app`，NDK 25 + CMake，JDK 17 |

## 2. 源码总览

```
app/src/main/java/
├── com/moe/starflow/           ← 应用主包（13 个一级子包）
│   ├── manga/          (51) 漫画翻译引擎：主服务 / 分批管线 / 检测 OCR / 合并 / 渲染 / 状态 / 调试
│   ├── translate/      (28) 游戏翻译引擎：悬浮球服务 / 截图系统 / 自动翻译 / 结果组件
│   ├── mangaimport/    (24) 漫画导入书架 + Koto 式阅读器 + 阅读器内嵌翻译
│   ├── me/             (21) 设置与配置：个人化 / API 配置 / 模型管理 / 关于 / 开发者
│   ├── utils/          (18) 工具：Constants / CustomPreference / KeystoreManager / LogCollector / FontSync
│   ├── data/           (11) Room 数据库 / 三层缓存 / 历史实体
│   ├── chat/            (9) 文本聊天翻译模式（ChatEngine + Hy-MT2/OpenAI 引擎）
│   ├── ui/              (8) 历史记录 / 漫画查看器（MangaViewerActivity）
│   ├── download/        (7) 模型下载流水线（断点续传 / 状态机 / MD5 校验）
│   ├── launch/          (4) 首次启动引导
│   └── 根包             (3) StarFlowApplication / MainActivity / BaseActivity
└── translationapi/        (21) ← 历史遗留独立顶层包（见 §6 技术债）
    └── 12 个翻译引擎子包：openai / bing / nllb / niutrans / volc / deepl / baidu
        / tencentcloud / azure / custom / doubao / hymt2（Hy-MT2 本地引擎）
```

## 3. 核心业务链路

### 3.1 游戏翻译（像素驱动）

```
ScreenshotProvider（MediaProjection / Accessibility 双模式）
    → ScreenshotManager.screenshotFlow（SharedFlow）
    → FloatingBallService
        ├── AutoTranslateEngine（像素状态机：IDLE→CHANGED→STABLE_1→STABLE_2→OCR）
        ├── GameOcrEngine（MLKit / PP-OCRv5/v6 / manga-ocr）
        └── TranslationTextAPI（按 prefs 从 TranslatorFactory 创建）
    → TranslationResultView（结果容器，可拖动/锁定）
```

- 悬浮球手势（单击/双击/长按）通过 `BallStateManager` 驱动状态图标
- LRU 文本缓存（20 条）精确匹配复用译文

### 3.2 漫画翻译（气泡级）

```
截图 → MangaFloatingService.processMangaScreenshot
    → 三层缓存查找（§4）
    → 未命中 → DetectionBridge（det 引擎：RT-DETR-V2 / PP-OCRv5/6 / ML Kit）
    → OCR（rec 引擎，逐气泡）
    → TextRegionMerger（识别后合并，两阶段：canMergeRegion + splitTextRegion）
    → TranslateUtils.translateBubbles（每气泡并行 / 增量分批 / Hy-MT2 流式）
    → OverlayRenderer + VerticalTextRenderer（竖排/横排渲染）
    → saveToCache（三层）
```

- 增量渲染：>6 气泡分批，首批完成即渲染
- 竖排方向（RL/LR）运行时实时覆盖所有渲染入口
- 引擎统一选择层：`OcrEngineManager` + `OcrEngineGroup` 4 组固定组合，游戏/漫画/首页共享

**分批管线已抽出为共享层 `manga/pipeline/`**（原本内联在 `MangaFloatingService`，约 430 行）：

```
manga/pipeline/
├── IncrementalBatchPipeline.kt   编排：总闸路由 + 三条路线 + 两批编排 + 批次内翻译
├── BatchPipelineHost.kt          宿主钩子接口（进度/错误/结果上屏/取消/上下文/文本缓存）
├── BatchOcrOps.kt                引擎调用抽象（测试缝，可注入假引擎断言调用顺序）
├── BatchPipelineConfig.kt        一次任务的不变入参
└── BatchOutcome.kt               Handled / HandledEmpty / NotApplicable
```

- 两个宿主：**截屏翻译**（`MangaFloatingService`，有悬浮球/结果浮层/缓存表）与**阅读器**（`ReaderTranslationController`）
- ⚠️ `HandledEmpty` 与 `Handled` 必须分开：前者（未检测到文字）**不可调 `finalizeIncremental`**，否则 `lastTranslatedHash` 被写成空页 → 自动翻译永久跳过该页
- 分批触发条件（三者全满足）：非 Hy-MT2 + `Incremental_Render` 开关开 + 引擎组合属于 PP-OCRv5/v6 独立 或 RT-DETR+manga-ocr；且气泡/文字行 > 6

### 3.3 阅读器内嵌翻译（三种模式）

`ReaderTranslationController` 用一个**串行队列引擎**驱动三种模式，只有窗口大小不同：

| 模式 | 窗口 | 行为 |
|---|---|---|
| 手动 | — | 点按钮翻当前页；单击只提示，**双击**取消并回退手动 |
| 自动 | 1 页 | 翻页停留 `debounceMs`（默认 500ms）后自动翻当前页 |
| 增量 | N 页（1-10） | 翻 `[当前页, 当前页+N)`，跳过已成功/已失败的页 |

- 队列每轮**重读当前页并重算窗口**，翻页不重启队列；正在翻的那页不被打断
- **跳过 FAILED**：不跳会让内容性失败（空白页）被反复重挑 → 无限重试
- 增量模式**禁用分批与流式**（翻的是用户没在看的页，"先出一部分"没有观众）
- **只有"正在看的那页"才渲染上屏**；后台队列页只写库，不烧 CPU 和 100MB 渲染缓存
- 半成品走独立缓存 key `page:<i>:PARTIAL`（`state==TRANSLATING` 时取它），否则首批结果上不了屏
- 进度条三色双层：5dp 粗带（白=已读/灰=未读）+ 2dp 绿条（已翻译，居中叠加）
- Webtoon（连续滚动）：翻译按钮禁用（"当前页"语义不唯一），原图/译文整屏切换走阅读模式分段器上「连续滑动」按钮的两态角标
- 横屏与竖屏同为单页（双页显示已移除）→ 翻译逻辑与竖屏完全一致，不再按朝向禁用

### 3.4 阅读器打包下载（三种）

`ReaderExport`（IO 线程逐页渲染）+ `ExportNaming`（纯函数命名规则，单测覆盖）：

| 入口 | 内容 | 包内命名 |
|---|---|---|
| 原文 | 全部页 | archive 直接复制原 zip；目录导入用原 key（含子目录） |
| 译文 | 只有 `STATE_SUCCESS` 的页 | `<原目录>/<原名主干>.jpg`（统一 JPEG 95） |
| 双语 | 已翻译页的原文 + 译文两个条目，同层混放 | 原文原名原扩展名 + `<主干>_译文.jpg` |

- **命名沿用原压缩包序号**（原包 1..10 只翻 1/2/5/6 → 导出 `001/002/005/006`，不是 `1/2/3/4`）；后缀随界面语言（中文 `_译文` / 英文 `_translated`，走 strings）
- ⚠️ 条目名必须**去重**（`uniqueEntryName` 加 `_2`）：同目录 `005.png`/`005.jpg` 译文名会撞，双语包再导入当新书读时 `005_译文.jpg` 也会撞 → `ZipException: duplicate entry` **整包失败**
- ⚠️ **先渲染译文再写条目**，否则双语包会留孤儿原图；跳过页计数回传（`ExportOutcome`），0 页成功按失败处理
- 译文图渲染走 `renderForExport`（**不写 `renderLru`**，导出上百页会把 100MB 渲染缓存冲干净）
- 临时 zip 在 `cacheDir`，`finally` 里删；落盘走 `MediaStore.Downloads`

### 3.5 阅读器 UI 不变量

- **点屏幕正中一格**（Koto 九宫格中格）显隐上下 UI：淡入淡出 160ms，状态持续；淡出期间必须立刻关掉可交互（否则 160ms 内点到的是看不见的按钮）
- **旋转后重新对齐**：`ViewPager2` 内部按像素保留滚动位置 → 必须直接对内部 `RecyclerView` 调 `scrollToPosition`（`setCurrentItem(当前页,false)` 在"已是当前页且空闲"时直接 return）；目标页用尺寸变化**前**记下的页
- Webtoon 行高按页图原始宽高比钉死，宽度变化（旋转/分屏）时重绑可见页

## 4. 三层缓存与 pHash

```
截图
  ↓ IMAGE CACHE（findCacheExt，256-bit pHash）— ≈0.95 相似度 → 直接显示，省 OCR+翻译+渲染
  ↓ TEXT CACHE（translatedRegions / incrementalTranslateBubbles）— 加权编辑距离 → 省 API 调用
  ↓ 翻译 API
```

- 两种 pHash：`compute()`（9×8 dHash 64-bit，状态机用）、`computeExtended()`（17×16 256-bit，缓存用）
- 纯色页守卫：dHash 全零直接 `showImmediate("未检测到文字")`
- 历史分组：`groupMangaEntriesByPHash()`（0.85 阈值 + `MIN_INFO_BITS_HISTORY=16` 稀疏守卫）
- 实时渲染共享：DB 只存原图 + 气泡元数据，overlay 由 `renderOverlay()` 实时渲染

## 5. 关键机制卡片

| 机制 | 位置 | 要点 |
|---|---|---|
| 翻译引擎工厂 | `translationapi/TranslatorFactory.kt` | 按 Text_API / Text_AI 创建，Hy-MT2 切换时换出 440MB 内存 |
| Hy-MT2 共享实例 | `HyMT2SharedHolder` | 进程级单例热模型，`release()` 不释放、切走才换出 |
| 弹窗系统 | `TranslationStatusOverlay` | 全局共享单例，≤3 条堆叠，翻译状态专用（禁系统 Toast） |
| 设置实时生效 | service `watchedKeys` / 每次读 prefs | 三种实现方式分层 |
| 日志体系 | `LogCollector` | `starflow.log` 300 行滚动 + native crash 块 + dladdr 符号化 + re-raise |
| 内容安全 | `network_security_config` | 全局放行明文 http（自建局域网 API） |
| i18n | values/ + values-zh/ | 1130 key 全对齐，lint MissingTranslation fatal 硬门槛 |

## 6. 已知技术债（评估于 2026-09，未处理）

| 项 | 规模 | 说明 |
|---|---|---|
| `MangaFloatingService.kt` | 3820 行 / 90 方法 | 翻译管线编排聚合性强，已在渐进抽取状态机/缓存（当前为合理形态） |
| `translationapi/` 顶层包 | 21 文件 / 100+ 编辑点 | 脱离 `com.moe.starflow` 命名空间；**移动需同步改 14 个 JNI 符号 + proguard，风险高，决定保持现状**（`TranslatorFactory.kt` 顶部注释 + `CLAUDE.md` 已记录） |
| `fragment_model_management.xml` | 1056 行 | 静态 XML 逐模型堆行（ScrollView；仅 9 行，无虚拟化收益）；`modelRows` 已数据驱动，抽 `include` 模板或 RecyclerView 为可选优化 |
| 无 androidTest | — | 两个翻译服务靠真机回归；Robolectric（sdk=34 + includeAndroidResources）已配置可渐进补 |

## 7. 构建与质量门

- 构建：`./gradlew assembleDebug / assembleRelease`（首次要编译 C++，约 3-10 分钟；**仅 arm64-v8a**）；环境与命令细节见 `README.md` 的「构建」
- lint 硬门槛：`fatal += ['MissingTranslation']`（本地化防线，errors=0）
- 单元测试：44 文件 / 242 用例（Robolectric 需干净 PATH + PowerShell）
- release 开 `minifyEnabled` + `shrinkResources`：native 回调接口靠 proguard `-keep`，改名要同步改规则
- 无 CI（个人项目，本地 lint 门已够用）