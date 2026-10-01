# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

星译（StarFlow）— Android 翻译应用，支持 Android 10+（API 29+）。四大功能块：游戏/视频翻译（截图 OCR + 翻译 API）、漫画翻译（气泡检测 + OCR + 翻译 + 竖排渲染）、漫画书架与阅读器（导入 zip/目录/rar/7z 即读，含章节导航，翻译在阅读器内完成）、小说导入与阅读（txt/epub/zip-html/文件夹，左右/上下/滚动三种阅读模式 + 按批翻译 + 导出译文/原文/双语）。

## 模块分册索引（细节按目录下沉）

根文件只留**跨模块**的东西（命令 / 架构 / 硬约束 / 高频踩坑）。下面这些细节各自在模块目录里，
Claude 只在动那个目录时才读：

| 模块 | 分册 |
|---|---|
| 漫画渲染 / 引擎 / 管线 / 状态机 / 自动翻页 | `app/src/main/java/com/moe/starflow/manga/CLAUDE.md` |
| 漫画导入书架 + 阅读器 | `app/src/main/java/com/moe/starflow/mangaimport/CLAUDE.md` |
| 小说导入 + 阅读器 + 翻译 | `app/src/main/java/com/moe/starflow/novel/CLAUDE.md` |
| 翻译服务 / 悬浮球 / 截图链路 | `app/src/main/java/com/moe/starflow/translate/CLAUDE.md` |
| 数据、缓存、历史、DB | `app/src/main/java/com/moe/starflow/data/CLAUDE.md` |
| 模型下载与模型管理 | `app/src/main/java/com/moe/starflow/download/CLAUDE.md` |
| 图片超分（引擎 / 大图压缩 / 逐页按钮 / 记录系统 + 超分本章） | `app/src/main/java/com/moe/starflow/sr/CLAUDE.md` |

这些分册**随仓库提交**（2026-10-01 起）→ clone 下来就有，`.gitignore` 里那行裸的 `CLAUDE.md` 已删除。
⚠️ 但 `.claude/worktrees/*` 是**独立检出**：里面那份是**建工作树那一刻的快照**、不会跟着主检出更新，
早于这次改动建的工作树里根本没有它们。**改约定一律回主检出改**，别在工作树里改完以为生效了。

## 目录规范

- **`.reference/`** — 参考项目，只读，已 gitignore。用于克隆第三方开源项目作为代码参考。⚠️ `.reference/*/CLAUDE.md` 是**第三方参考项目自带**的文档（Kototoro/fby/fby2），Claude 探索该目录时会自动加载——不要把它们当本项目规范；`.claude/worktrees/*/CLAUDE.md` 同理（旧工作区快照，自动清理，可忽略）。
- **`tools/`** — 测试模型和脚本，已 gitignore。用于本地测试转换后的模型。
- **各模块目录下的 `CLAUDE.md`** — 本项目自己的模块分册（`manga/`、`mangaimport/`、`novel/`、`translate/`、`data/`、`download/`、`sr/` 各一份，索引见上）。**随仓库提交**（不再 gitignore）；改完约定记得**同时看根文件与对应分册**。
- **`models/`** — 模型源文件集中目录，已 gitignore（体积大不适合提交）。存放从各服务器下载的真实模型文件（manga-ocr / PP-OCRv5 / PP-OCRv6 medium / RT-DETR），用于计算 MD5 和核对大小。参见 memory `[[model-download-md5-not-size]]`。
- **`docs/`** — 文档**一律只留本地，不提交远端、不提交 git**（2026-09 用户明确要求）。含 `docs/docs/`（架构文档 `ARCHITECTURE.md`、带日期的设计/计划稿）与 `docs/superpowers/`（spec/plan 草稿）。`.gitignore` 已整目录忽略 `docs/`；原先 tracked 的 11 个文件已 `git rm --cached`，本地文件保留。
- **`docs/superpowers/`** — superpowers 技能的草稿：设计规格存 `docs/superpowers/specs/`，实现计划存 `docs/superpowers/plans/`。⚠️ 不要用 superpowers skill 默认的「save to docs/superpowers/specs/ and commit」——本项目 spec/plan 一律不 commit。
- 禁止在项目根目录散落模型文件；模型源文件统一放 `models/`。
- **本项目 `CLAUDE.md` 随仓库提交、会上远端**（2026-10-01 起，`.gitignore` 里那行已删）：它是给
  **Claude** 的项目上下文，clone 下来即生效，不需要任何额外同步步骤。给**用户**看的说明仍写
  `README.md`（也在远端）。⚠️ 仓库是**公开**的，新增内容前扫一眼有没有本机敏感信息
  （adb 序列号 / API key / 绝对路径）—— 现在这 8 份里只有本地代理端口和机型名，没有敏感项。

## 环境搭建

**必需：** JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

> ⚠️ **三步不做，构建必挂**（都不在 git 里，`git clone` 拿不到）：
> | 缺什么 | 症状 | 补法 |
> |---|---|---|
> | `sr/src/main/cpp/ncnn-src/` | **`:sr` 编不过**（`CMakeLists.txt` 有 `add_subdirectory(ncnn-src)`） | `pwsh sr/setup-ncnn.ps1` |
> | `llamacpp/src/main/cpp/llama.cpp` | **`:llamacpp` 编不过**（gitlink，clone 下来是个空目录） | `pwsh llamacpp/setup-submodule.ps1` |
> | `local.properties` | 找不到 SDK | 见下面第 1 步（**正斜杠**） |
>
> `models/`（模型源文件）、`tools/`、`docs/` 也都不入库，但**不影响构建**，需要时单独拷。

**首次克隆后：**
1. 创建 `local.properties`（已 gitignore）：`sdk.dir=C:/Users/%USERNAME%/AppData/Local/Android/Sdk`，路径用正斜杠 `/`
2. 配置 git 代理（国内环境，二选一）：Clash 时 `git config --global http.proxy http://127.0.0.1:7897`；
   用 Cloudflare WARP 时**不设代理**（清掉 env 后 git 走系统路由直连，见「网络配置」）
3. 确认 Windows hosts 无 `#S302` 条目将 github.com 指向 127.0.0.1
4. **`pwsh sr/setup-ncnn.ps1`** —— 必做：`sr/src/main/cpp/ncnn-src/` 不入库（`sr/.gitignore`），
   而 `sr/src/main/cpp/CMakeLists.txt` 里有 `add_subdirectory(ncnn-src)` → 不拉 **`:sr` 直接编不过**。
   `:llamacpp` 同理要 `pwsh llamacpp/setup-submodule.ps1`。
   ⚠️ **别跑 `git submodule update --init`**：Windows 上会因长路径失败（脚本头部已写明），
   而 `llama.cpp` 本来就不是一个「已注册的 submodule」，跑它反而会另开一份 `.git/modules` 存储、与现成的打架。
5. `./gradlew assembleDebug` 验证构建

## 构建命令

> ⚠️ **新 clone 先拉 submodule**：`:llamacpp` 是独立 Gradle 模块，它的 `CMakeLists.txt` 直接
> `add_subdirectory(llama.cpp …)`，而 `llama.cpp` 是 gitlink（空目录）。不拉会构建失败：
> `git submodule update --init --recursive`（或跑 `setup-submodule.ps1`；pin 见 `git submodule status`）。

```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease

# 安装 debug APK 到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 清理构建
./gradlew clean assembleDebug

# 运行单元测试（⚠️ 直接 ./gradlew test 会因 Git Bash 弄乱 PATH 使 test worker 崩溃
# ClassNotFoundException: Files\Git\mingw64\bin;...，需用 PowerShell + 干净 PATH + --no-daemon + 指定测试类）：
# powershell:
#   $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
#   $env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
#   .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.data.XxxTest

# Robolectric 测试：app/src/test/resources/robolectric.properties 全局 sdk=34（Robolectric 4.11
# 最高支持 targetSdk 34，项目是 35——不加会在初始化报 "targetSdkVersion=35 > maxSdkVersion=34"）；
# app/build.gradle 的 unitTests.includeAndroidResources=true 已开启（UI 交互测试需 inflate XML 布局）

# 耗时基线：全量 :app:testDebugUnitTest 约 1–2 分钟；:app:assembleRelease 约 4 分钟
#
# ⚠️ **默认只跑改动涉及的包，不要每次全量**（用户口径 2026-10-01：「不要每次都做全量单测，
#    太浪费时间了……在涉及到相关功能的情况下测试相关位置」）：
#      .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests 'com.moe.starflow.mangaimport.*'
#    `--tests` 收的是**类名/包名通配**，改哪块就点哪块（改 3 个包跑 3 个 --tests，秒级到十几秒）。
#    全量只在**用户明确要求**时跑（发布前那一次由用户说了算，别自作主张顺手跑）。
#    ⚠️ 但"相关"要按**真实依赖**算，不是按目录：改了共享布局/共享工具（如
#    `sheet_reader_menu.xml`、`utils/`、`TranslationStatusOverlay`）就要把**所有消费方**一起点进去，
#    跨模块的（novel 用 mangaimport 的布局/工具）尤其容易漏。
# ⚠️ 卡在 "Failed to delete some children"（连 build 都起不来）= 上一轮被打断的 Gradle test worker
#    还占着 app/build/test-results。先杀掉再跑（--no-daemon 也会留）。PowerShell：
#      Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
#        Where-Object { $_.CommandLine -match 'gradle|kotlin|GradleWorkerMain' } |
#        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
# ⚠️ 过滤 gradle 输出用 -Pattern '^e: |FAILED|BUILD '：只筛 '^e: ' 会漏掉**资源合并 / XML 解析**
#    那类失败（它们不走 Kotlin 编译器的 e: 前缀），表现为「没有任何报错但 build 挂了」

# 实时查看应用日志
adb logcat --pid=$(adb shell pidof com.moe.starflow)

# 指定设备安装（多设备时）
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

# 查看连接设备
adb devices
```

# lint（本地化防线）：app/build.gradle 已设 lint { fatal += ['MissingTranslation'] }
# 缺翻译（values/ 有、values-zh/ 没有）→ assembleRelease 直接失败；调试先跑下面这行看详情：
./gradlew :app:lintDebug   # 新增字符串 key 前先查重（曾两次撞重复 key 导致资源合并失败）
# ⚠️ 两个 lint 任务的 gate **不同**：lintDebug 任何 error 都中止构建；assembleRelease 只跑
#    lintVitalRelease（fatal 规则）→ **一方红不代表另一方红**。别照抄本行的数字，改完 UI/资源
#    实际跑一次。（2026-10-01 实测 lintDebug 有 4 个 error：UseAppTint×3 + ByteOrderMark×1，
#    文档里的 "errors=0" 当时已经过期 —— 规则是 UseAppTint / ByteOrderMark，不是 MissingTranslation。）

**Windows 注意：** `adb` 命令需要通过 PowerShell 调用完整路径：
```powershell
# 安装 APK
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk

# 监控日志（按 tag 过滤）
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat --pid=$(& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell pidof com.moe.starflow) | Select-String -Pattern "MangaFloatingService|OpenAITranslation|FloatingBallService"
```

## 架构

**包结构** (`app/src/main/java/com/moe/starflow/`):

- `manga/` — 漫画翻译引擎（按功能分 8 组）：
  - 根包：`MangaFloatingService`（主服务）、`TranslateUtils`（翻译管线公共层，**跨模块共享**：`StarFlowApplication`/`manga/pipeline`/`manga/types`/`mangaimport/translate`/`ui/viewer` 均引用）、`OcrLock`（引擎互斥锁，`mangaimport/translate`、`ui/viewer` 也用）
  - `types/` — 纯数据类（`TextLine`/`OcrResult`/`TranslatedBubble`/`BubbleRegion`/`TextBlockInfo`/`TextRegionGroup`/`QuadBox`/枚举等 14 文件）
  - `config/` — `MangaModeConfig`（data class + verticalTextDirection 扩展）、`OcrEngineGroup`（4 组引擎组合：MLKIT/PP_OCR_V6/PP_OCR_V5/RT_MANGA）、`PPOcrParams`（v5/v6 参数 key/默认值单一来源）
  - `engine/` — `PPOcrV5Engine`/`PPOcrV6Engine`（OCR 流水线）、`DetectionBridge`（检测桥接）、`OCRBridge`/`OCRTextRecognizer`（ML Kit，游戏漫画共用）、`MangaOcrBridge`/`MangaOcrRecognizer`/`MangaOcrTokenizer`（manga-ocr）、`ComicBubbleDetector`/`BubbleDetector`（检测器）、`PPOcrDetGeometry`（det 后处理共享）、`PPOcrModelFiles`/`MangaOcrModelFiles`/`RTDetrModelFiles`（模型文件检查）、`OnnxUtils`（ONNX 张量提取/资源拷贝）、`GeometryUtils`（凸包/叉积/点在多边形内等几何算法）
  - `render/` — `OverlayRenderer`（覆盖层渲染 + TranslatedBubble 类型）、`VerticalTextRenderer`（竖排/横排渲染）
  - `merge/` — `TextRegionMerger`（区域合并）、`PPOcrPostProcessing`（识别后合并）、`MangaSpatialGrouping`（空间聚类/分批切分）
  - `state/` — `MangaAutoTranslateEngine`（自动翻译状态机）、`MangaEngineManager`（引擎初始化/释放）、`RegionCacheManager`（区域缓存）
  - `pipeline/` — 分批翻译管线（与截屏翻译、阅读器内嵌翻译共用同一套）：`IncrementalBatchPipeline`（编排：分批 OCR → 分批翻译 → 增量渲染）、`BatchPipelineHost`（宿主钩子接口：2 属性 + 9 回调，Service/Controller 各实现一份）、`BatchPipelineConfig`（配置快照）、`BatchOcrOps`（6 个引擎单例的测试缝）、`BatchOutcome`（`Handled`/`HandledEmpty`/`NotApplicable`）
  - `debug/` — `MangaDebugOverlays`（渲染纯函数）、`MangaDebugPanelController`（全屏 overlay 窗口骨架 + 折叠状态机）、`MangaDebugSliders`（参数滑块面板）
- `translate/` — 游戏翻译引擎（3 个功能子包 + 根包）：
  - 根包：`FloatingBallService`（主服务）、`TranslationTextAPI`/`TranslationPicAPI`（接口层）、`TranslationStatusOverlay`（共享翻译状态浮层单例）、`TranslatorNames`（引擎显示名映射）、`TranslateFragment`/`TextTranslateFragment`/`TranslateTools`/`CustomLocale`/`LanguageSelectionDialog`（首页/文本 UI）
  - `screenshot/` — 截图系统：`ScreenshotProvider`（接口）/`MediaProjectionProvider`/`AccessibilityProvider`（双模式）、`ScreenshotManager`（截图总线单例）、`Shooter`（MediaProjection 帧捕获）、`ScreenCapturePermissionActivity`、`ScreenShotAccessibilityService`/`AccessibilityServiceManager`/`AccessibilityEventHandler`、`MediaProjectionIntentHolder`
  - `autotranslate/` — `AutoTranslateEngine`（像素状态机）、`GameOcrEngine`（游戏 OCR 封装）、`GameDebugOverlay`（调试浮窗）
  - `widget/` — `TranslationResultView`（翻译结果容器）、`BallStateManager`（悬浮球状态图标管理器）、`CropView`（框选视图）、`Dialogs`/`MenuDialogAdapter`（菜单/弹窗）
- `chat/` — 文本聊天翻译模式：`ChatEngine`/`ChatTabView`/`ChatTemplates`/`ChatHistoryViewModel`/`LlamaCppChatEngine`/`OpenAIChatEngine`
- `ui/` — 历史记录 & 漫画查看器：
  - `history/` — `HistoryFragment`（双视图：默认/管理）+ 4 个 Adapter（`HistoryGameAdapter`/`HistoryMangaAdapter`/`HistoryGroupAdapter`/`HistoryMangaGroupAdapter`）
  - `viewer/` — `MangaViewerActivity`（全屏图片浏览+译文详情+重翻操作）、`ZoomableImageView`（缩放控件）、`CropFragment`（重翻裁剪界面）
- `me/` — 设置和 API 配置界面：`PersonalizationConfig`（个性化设置）、`APIConfig`（API 配置）、`TranslationMode`（翻译模式）、`AboutMe`（关于页面）、`Developer`（开发者选项）、`FAQPage`（常见问题，12 条 FAQ），按 about/apiconfig/model/settings 4 子包组织
- `launch/` — 首次启动引导
- `utils/` — 工具类：`Constants`（枚举定义）、`CustomPreference`（配置封装）、`LogCollector`（日志收集）、`PixelCompare`（像素比较）、`UiUtils`（Toast 统一）、`ServiceUtils`（服务状态检测）、`UpdateChecker`（检查更新）、`TranslationExecutionMode`（翻译/仅 OCR）、`TranslationBusyRegistry`（执行模式切换忙碌守卫）、`LocalTranslationCoordinator`（本地翻译全局互斥）
- `data/` — Room 数据库、`TranslationCacheManager`、`TranslationCacheUtils`（缓存工具：256-bit hash 守卫 + 气泡 JSON 解析）、`HistoryEntity`/`PageCacheEntity`；历史处理状态含 `PROCESS_TRANSLATED/PROCESS_OCR/PROCESS_FAILED`
- `download/` — 模型下载流水线：`ModelDownloadManager`/`ModelDownloadRepository`/`ModelDownloadService`/`DownloadState`/`ModelInfo`/`ModelKey`/`ChecksumHelper`
- `mangaimport/` — 漫画导入书架 & 阅读器（feature/manga-import）：`ImportMangaFragment`（书架+多选管理+下拉刷新）、`data/`（`ImportedManga`/`ImportedMangaStore`/`MangaImporter`/`StorageDirStore`/`ArchivedMangaReader`/**`MangaChapter`+`MangaChapterSplitter`（章节切分：子文件夹=一章）+`MangaChapterMigrator`**/**`ArchiveTypes`+`LibArchiveExtractor`（cbr/7z 按魔数识别 + 导入时解压成目录）**）、`reader/`（`MangaReaderActivity` Koto 式阅读器：4 阅读模式/翻页动画/背景/颜色矫正对比预览/进度胶囊/三种打包下载/阅读器内嵌翻译/**章节导航（顶部胶囊带章节并可点开目录、底部左右按钮切章）**；配 `ReaderAdapters`/`ReaderPageSource`/`ReaderExport`/`ReaderProgressBar`/`ReaderMenuSheet`/`ReaderCurl`/`ReaderTransformers`/`ReaderColorFilter`/`ReaderPagePreviewDialog`/`ReaderPageStateAdapter`/**`ReaderChapterDialog`**）、`translate/`（`ReaderTranslationController`（**OCR 持 `OcrLock` 串行 → 翻译阶段按引擎并发；支持翻译/仅 OCR执行模式与 OCR 状态复用**）/`ReaderTranslationHub`（**应用级宿主**：按书缓存控制器、实现 `ChapterJobSource`）/`PageTranslationCodec`/`ReaderTranslationInfo`/`TranslationEngineInit`）、`ui/`（`ImportDialog`/`DisplayOptionsSheet`/`MangaGridAdapter`）。详见 `mangaimport/CLAUDE.md`。
- `translate/batch/` — **批量翻译的共享基础设施**（漫画+小说共用）：`ChapterJobRunner<T>`（OCR 串行 + 翻译并发 N + 按章暂停/取消/等待的纯协程调度器）、`TranslationJobs.kt`（`ChapterJobState`/`ActiveChapterJob`/`ChapterJobSource`/`TranslationJobRegistry`）、`TranslationJobService`（前台服务：**每章一条通知**，进度/暂停/继续/取消，点通知回到对应阅读器）。⚠️ **任务状态不在服务里**，服务只镜像 registry；漫画的宿主是 `ReaderTranslationHub`，小说的是小说侧宿主。详见 `mangaimport/CLAUDE.md`。
### OCR-only 执行模式与翻译流水线（2026-10）

- `TranslationExecutionMode` 是本地 OCR/文本翻译线路的全局执行模式：`TRANSLATE=0`（默认）/ `OCR_ONLY=1`；图片直传翻译不受此开关影响。
- 设置入口在 `me/settings/TranslationMode`；阅读器面板的执行模式标签只负责跳转设置。切换模式前必须确认没有 OCR/翻译忙碌任务：`TranslationBusyRegistry` 负责截屏与阅读器页面队列，`TranslationJobRegistry` 负责章节任务，`OcrLock` 负责 OCR/超分引擎锁。
- 截屏函数可能启动回调式异步翻译；busy 计数必须持续到终端回调、取消或异常，不能在截图函数返回时提前释放。
- `LocalTranslationCoordinator.mutex` 串行化本地 NLLB/LlamaCpp 翻译；每次 `lock/tryLock` 必须在成功、失败、取消、同步异常路径释放。远端 API 不占该锁。
- 阅读器每页状态：`STATE_IDLE`（无 OCR）、`STATE_OCR`（OCR 已保存但无译文）、`STATE_TRANSLATING`（真实进入翻译）、`STATE_SUCCESS`（OCR+译文完成）、`STATE_FAILED`（失败但保留载荷）。`STATE_OCR` 必须进入未完成/进行中筛选。
- 翻译本章按页码顺序：`IDLE → OCR+翻译`、`OCR → 复用 OCR 翻译`、`FAILED+OCR → 复用 OCR 重试`、`SUCCESS → 跳过`。仅 OCR 模式只执行 OCR，已有 OCR 页跳过 OCR；顶部浮层不能把 OCR-only 任务显示成“翻译中”。
- 游戏/视频新截图安全优先：先 OCR，再用 OCR 文本缓存命中；不要用不可靠的截图相似度直接套旧译文。游戏 OCR-only 历史仍保持查看/复制；漫画 OCR-only 历史进入管理视图并复用“重新翻译”入口。
- OCR-only 游戏历史不能用破坏性的 `refreshGameCache` 删除已有译文；已有译文时只更新处理状态并保留译文载荷。

- `sr/` — **图片超分**（feature/super-resolution）：`SrProcessor`（唯一执行入口：等锁 → 大图压缩 → 推理 → 落盘 → 写记录）/`SrDownscale`（大图预处理：短边 1080 + 引擎输入上限两个约束一起解）/`SuperResolutionEngines`（门面：选引擎 / 失败降级 / `resolveSteps`）/`NcnnSrEngine`（ncnn+Vulkan，主力）/`AnimeJaNaiEngine`（ONNX CPU，当前不可达）/`anime4k/`（GLES3 shader，不放大）/`SrStore`（webp + 标记的落盘）/`SrModelManager`/`SrSettings`/`SrDisplayBase`；原生在 **`:sr` 模块**（ncnn+Vulkan，源码自编）。详见 `sr/CLAUDE.md`。
- `novel/` — 小说导入 & 阅读（feature/novel-text-import）：`parser/`（格式判定与解析：`NovelParsers` 分发、`TxtParser`/`TxtChapterSplitter` 用**字符区间**定位章节、`EpubParser`、`ZipHtmlParser`/`HtmlParser`/`HtmlTextExtractor`、`FolderNovelParser`、`TextEncoding` 解 GBK/Big5）、`model/`+`data/`（`NovelFormat` 判定、`NovelImporter`/`NovelImportManager`/`NovelStore`/`NovelStorageDir`）、`shelf/`（`NovelShelfFragment`，宿主在 `mangaimport/ImportMangaFragment`）、`reader/`（`NovelReaderActivity` + `NovelChapterRepository` + `NovelPaginator` + `NovelTextRenderer`/`NovelPageView`/`NovelScrollAdapter`/`NovelTocDialog`/`NovelPanelSheet`/`NovelPanelStyle`）、`translate/`（`NovelParagraphSplitter`/`NovelTranslationBatch`/`NovelTranslationEngine`/`NovelChapterTranslator`/`NovelTranslationQueue`）。详见 `novel/CLAUDE.md`。

**翻译 API 实现** (`app/src/main/java/translationapi/`):
每个子目录实现 `TranslationTextAPI` 接口：`openaitranslation/`、`bingtranslation/`、`nllbtranslation/`、`niutrans/`、`volctranslation/`、`deepltranslation/`、`baidutranslation/`、`tencentcloud/`、`azuretranslation/`、`customtranslation/`、`doubaotranslation/`、`llamacpp/`
- `llamacpp/` — **LlamaCpp 本地引擎的 JNI 门面**，只有一个 `LlamaCppNative.kt`（`external` 方法 + `LlamaCppStreamCallback` 接口）；引擎实现（模型清单/导入/翻译/提示词）在 `com.moe.starflow.llamacpp/`，原生代码在 **`:llamacpp` 模块**。`nllbtranslation/` 同属本地引擎类
- ⚠️ **本包是历史遗留的独立顶层包，不在 `com.moe.starflow` 命名空间内，保持现状勿移动**：JNI 符号（`:llamacpp` 的 `llamacpp_bridge.cpp` → `Java_translationapi_llamacpp_LlamaCppNative_*` + `cpp/src/SentencePieceProcessorInterface.cpp` 的 SentencePieceProcessorJava 6 个）与 `proguard-rules.pro` 的 `LlamaCppStreamCallback` keep 规则都硬编码 `translationapi.*` 包名，移动即 UnsatisfiedLinkError。详见 `TranslatorFactory.kt` 顶部注释。

**LlamaCpp 本地引擎关键机制（`translationapi/llamacpp/` 门面 + `com.moe.starflow.llamacpp/` 实现 + `:llamacpp` 原生模块）：**

> **2026-09 大改造**：原来的 **Hy-MT2 专用引擎已整体改造成通用 LlamaCpp 引擎**——任意 llama.cpp 兼容的 GGUF（Qwen/Gemma/混元…）都能导入使用，Hy-MT2 退化成「预制模型」里的两个可下载量化档。改造在分支 `feature/llamacpp-engine`（**已合并 master**，见 `57eccdf`）。

- **原生模块 `:llamacpp`（独立 Gradle 模块，不是 `:app`）**：llama.cpp 当 submodule 一起编，产出 `libllamacpp.so`。**为什么必须独立成模块**：`:app` 顶层 CMake 是 sentencepiece，它的 `src/common.h` 与 llama.cpp 的 `common/common.h` 同名，合到一处必然冲突
- **llama.cpp 基线**：submodule 钉在 PR #22836 head `1e411d8f`（含 `STQ1_0=43`、`GGML_TYPE_COUNT=44`）+ 本地补丁 `patches/0001-ggml-stq1_0-1.25bit.patch`；`LLAMA_BUILD_COMMON=ON`（要 minja 的 Jinja chat 模板）。链接 llama + llama-common + ggml + log；`GGML_CPU_REPACK=ON`、`BUILD_SHARED_LIBS=OFF`（静态链进 `libllamacpp.so`，只导出 JNI）
- **流式契约**：`getTranslationStreaming` 回调 `onPhase`（`"prefill"` 读取原文 / `"generate"` 生成译文）+ `onPartial`（**累积到当前的完整译文**，非单片段）。其他翻译 API 的 `getTranslationStreaming` 是默认实现 = 一次性 `getTranslation`，不触发回调
- **两条 prompt 通道**（由模型的 `hyProfile` 决定，**不要写死某个模型 id**）：
  - **hy 通道**（预制 Hy-MT2）：`nativeTranslate*`，手搭 `[BOS]{指令}<hy_User>{原文}<hy_Assistant>` —— 指令放 system 段、原文放 user 段（官方 chat 模板结构），裸文本会退化输出垃圾
  - **通用通道**（导入的任意 GGUF）：`nativeTranslateRaw*` + `nativeFormatChat`，用 **GGUF 自带 chat 模板**（minja Jinja）渲染 system/user，模板缺失时回退 ChatML
- **前缀 KV 缓存**：固定翻译指令（模板 `{source_text}` 之前的部分）只 prefill 一次进 KV，跨翻译复用（`LlamaCppPrompt` + 桥接 `prefix_key`/`prefix_n`），跳过重复读指令（约省 1s/次）
- **模型管理页**（`me/model/LlamaCppModelFragment.kt`）：**预制模型**（Hy-MT2 1.25-bit / Q4_K_M，只是**提供下载**、不打进 APK，别叫「内置」也别叫「官方」）+ **导入的模型**（SAF 选本地 .gguf）。点卡片=设为当前（互斥；用不可点 RadioButton 显示，不能出现多选），「添加模型」按钮**固定在页面最底部**（页面 = `ScrollView(weight=1)` + `bottomBar`，按钮是 `bottomBar` 最后一个子 View）
- ⚠️ **模型行必须带父容器 inflate**（`inflateModelRow(inflater, parent)`）：`inflate(inflater, null, false)` 不解析 XML 的 `layout_*`，随后 `addView` 让 LinearLayout 补 margin=0 → 相邻卡片零间距、圆角贴在一起，看起来像卡片重叠（2026-09 用户反馈的「相邻卡片重叠」根因）
- **超时语义**：本地引擎**不设总时长超时**，改「30s 无任何新输出」卡死看门狗（`TranslateUtils` 的 `LOCAL_STALL_TIMEOUT_MS`）；网络 API 保持请求发出起 35s 总超时（`API_TIMEOUT_MS`）。勿改回 `withTimeoutOrNull(总时长)` 一刀切
- **漫画跳过分批**：`IncrementalBatchPipeline.run` 对 `LlamaCppTranslation` 直接返回 `BatchOutcome.NotApplicable`，走"一次翻译全部气泡 + 流式逐个显示"，不分两批
- **单气泡也走批量**：`translateBubbles` 对 `LlamaCppTranslation` 即使 1 个气泡也走编号批量流式路径（统一享受卡死看门狗）
- **手机实测性能**：读原文 ~17-20 tok/s、写译文 ~13-14 tok/s（8 核限频 ~1.5GHz）。生成是 CPU 硬上限；**2-bit 量化实测 3.92 tok/s 比 1.25-bit 慢 3 倍，勿再提供**
- **per-model 参数**（`LlamaCppParams`，每个模型一套，存清单 JSON，**不是全局一套**）：
  - **加载参数**（`fileName`/`contextSize`/`threads`/`batchThreads`）改动**下次加载模型**才生效，并进 `LlamaCppSharedHolder.keyOf` 指纹（指纹变了才重载）
  - **实时参数**（`promptTemplate`/`systemPrompt`/`temperature`/`topP`/`topK`/`repetitionPenalty`/`maxTokens`/`enableThinking`）每次推理现读，**立即生效**（不用重载）
  - 预制模型默认：temp 0.7 / top_p 0.8 / top_k 20 / rep 1.05 / ctx 2048 / max 4096（取自 GGUF 自带采样参数，两个量化档**完全一致**，避免「为什么两个配置不一样」）；导入模型默认 temp 0.6 / ctx 4096
- **线程分离**：prefill（读原文）与生成（写译文）线程数独立可配（`LlamaCppParams.threads`/`batchThreads`，默认 `availableProcessors().coerceIn(1,6)` / 全核），模型设置弹窗可调对比速度。实测 prefill 8 线程比 6 线程快 ~22%
- **进程级共享实例**：全 app（游戏/漫画/文本）共享**同一个热模型实例**（`LlamaCppSharedHolder`，`keepAlive=true`）。各页面/服务 `release()` 只取消在途任务、**不释放模型**；`MainActivity` 启动后台 `warmUp()` 预加载。引擎设置（Text_API/Text_AI）或**激活模型**变化时重建实例；**切到非 llamacpp 引擎（NLLB/API）时 `TranslatorFactory.create` 调 `releaseIfNotCurrent()` 释放旧模型、把几百 MB 换出内存**（get() 只在 llamacpp 分支被调，切走后不会自动触发）
- ⚠️ **换出旧实例一律走 `detachAndRelease()`（后台线程），绝不在调用线程上同步 `release()`**：`release()` 会 `join` 在途推理（最长 3s）+ 轮询 `inFlight` 等 native 调用退出（最长 3s），最后 `nativeRelease` 还要抢 native 的 `g_mutex` —— 而 `nativeInit` **整个加载期**都持着那把锁（含首次解码预热，15s 量级）。`get()` / `releaseIfNotCurrent()` 的调用方包含 `TranslatorFactory.create()` 与启动预热，都在主线程，同步做就是 ANR（2026-09-25 修）。代价是换模型期间旧、新两个模型短暂同时驻留内存，这是刻意的权衡。
- **量化兼容性（`GgufQuantCheck`，全项目唯一的 GGUF 解析器）**：分 `SUPPORTED`/`NEEDS_RETAG`/`UNSUPPORTED`/`UNKNOWN`，按**张量布局**（每 256 个值占多少字节）判定，**不能只看类型号**——腾讯私有量化号会撞上游新加的类型号：
  - 1.25-bit（type 42 = **42B/256**，私有 `block_stq1_0`）→ 设备端**重打标 42→43** 成上游 `STQ1_0`（`GgufTypeRetag`；写 `<file>.retagged` 标记、重打标后的 MD5 记进清单 `retaggedMd5`；标记丢失会自愈）
  - ⚠️ **标记只是「校验依据」，不是「判定依据」**（2026-09-25 修复，勿回退）：`ensureRetagged` 看的是**文件里还有没有 42 号张量**，不是标记在不在。删掉预设模型再重新下载时 gguf 被换成未重打标的官方原件，而 `<file>.retagged` 还留在磁盘上（下载侧只删 gguf 与 .part）——凭标记跳过就会把 42 号文件当成本引擎可读（加载失败，或按上游 Q2_0 解出垃圾）。三条配套规矩：
    1. `ModelDownloadRepository.deleteDownload` **必须连标记一起删**，否则重下后 `verifyFile` 拿旧标记校验刚下好的官方原件 → 判损坏 → 删 → 重下 → **死循环**；
    2. `verifyFile` 走 `ChecksumHelper.verifyChecksum(file, List<String>)`，**官方与重打标两个 MD5 都接受**（一次 MD5 对多候选，GB 级文件不算两遍；只认官方会把已重打标的文件当损坏删掉）；
    3. 残留标记只在**头部解析成功且给出确定结论**（`compat != UNKNOWN`）时才清——解析失败时 offsets 为空，与「解析成功且没有 42 号」长得一样，不能混为一谈
  - ⚠️ **一次扫描就够**：`GgufQuantCheck.analyze()` 同时给出 `compat` 与张量偏移，`LlamaCppTranslation.ensureLoaded` 与 `GgufTypeRetag.ensureRetagged(file, analysis)` 共用它。别再写成 `check()` + 自己再 `scan()`——头部要把元数据整段读出来（含 12 万个 vocab token），同一文件解析两遍纯属白花。
  - 2-bit（type 40 = **65B/256**，另一种私有量化）→ `UNSUPPORTED`，导入与激活都直接拒绝
  - Q4_K_M（Q4_K 12B / Q6_K 14B / F32）等标准量化 → `SUPPORTED`
- ⚠️ **GGUF 是小端**：解析必须 `Integer.reverseBytes`/`Long.reverseBytes`（`RandomAccessFile.readInt/readLong` 是**大端**）——曾因此差点把模型元数据写坏（单测抓出来的）
- **导入是应用级后台任务**（`LlamaCppImporter`，`CoroutineScope(SupervisorJob()+IO)`）：**离开页面不会中断**，只有显式点「取消」才终止（曾因页面销毁就取消 + 吞掉 `CancellationException` 而误报「导入失败」）；进度走 `StateFlow`，回到页面接着显示。导入前校验 `.gguf` 扩展名 + GGUF 魔数 + 量化兼容 + 剩余空间，按 **MD5 去重**（与清单里**所有**条目比，含预制模型），先写 `.part` 再改名
- **存储位置**：预制模型由下载流水线放 `getExternalFilesDir/models/`（`ModelDownloadRepository.baseDirFor`），导入的 gguf 放 `getExternalFilesDir/llamacpp/`（`LlamaCppPaths.DIR_NAME`）——两者差别由 `LlamaCppModel.dirName` 承载，**不要再写死路径**
- **回归守卫**（都在 `app/src/test/java/com/moe/starflow/llamacpp/`）：`GgufTypeRetagTest`（含 42/43/40 布局判定与端序，以及**标记残留但文件被换成未重打标原件时必须重新改写**、**上游 Q2_0 上的残留标记要清掉**）、`GgufTypeRetagRealFileTest`（真实文件，靠 `LLAMACPP_REAL_GGUF*` 环境变量 opt-in，默认 skip）、`LlamaCppPromptTest`、`LlamaCppLanguagesTest`、`LlamaCppModelJsonTest`（Robolectric——Android 的 `org.json` 在纯 JVM 单测里是桩；含 **`systemPrompt` 键缺失回落默认 / 显式写空要保留**）、`LlamaCppRowLayoutTest`（行按钮集合/右对齐/间距）、`LlamaCppRowButtonsTest`（**行内四个下载按钮的可见性组合**——行视图复用后每个状态都必须显式赋值，纯 JVM 无需 Robolectric）、`LlamaCppPageLayoutTest`（页面骨架 + 真实 measure/layout 的按钮贴底几何断言）、`LlamaCppBuiltinCatalogTest`（预制模型清单完整性 + 第二个预制模型不被当成通用模型）

**关键接口：**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — 文本翻译
- `TranslationTextAPI.getTranslationStreaming(text, sourceLanguage, targetLanguage, onPhase, onPartial, callback)` — 流式文本翻译（**目前只有本地 LlamaCpp 引擎真正实现**；onPartial 每次回调累积到当前的完整译文，在后台线程调用，UI 需自行切主线程）
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — 图片翻译

**截图流程：** `ScreenshotProvider`（双模式）→ `ScreenshotManager.screenshotFlow`（SharedFlow）→ `FloatingBallService` / `MangaFloatingService` 接收处理

**漫画翻译缓存机制：**

三层缓存结构，截图后按以下顺序查找：

```
截图
  ↓
IMAGE CACHE (findCacheExt) ─── 256-bit hash
  │ 精确匹配（4段全等）或相似度匹配（≥0.95）→ 直接显示
  │ 未命中 → OCR
  ↓
TEXT CACHE (RegionCacheManager + IncrementalBatchPipeline)
  │ 精确匹配（hashCode + 字符串==）或加权编辑距离模糊匹配 → 复用译文
  │ 未命中 → 调翻译 API
  ↓
翻译 API → 渲染 → saveToCache
```

| 缓存 | 触发函数 | 匹配算法 | 节省步骤 |
|------|---------|---------|---------|
| IMAGE | `TranslationCacheManager.findCacheExt` | 256-bit pHash（SQL + 遍历） | OCR + 翻译 + 渲染 |
| TEXT | `IncrementalBatchPipeline.translateWithCache` | `TextSimilarity.weightedLevenshtein` | 翻译 API 调用 |
| DB 历史 | `MangaViewerActivity` | 256-bit hash 相似度分组 (≥0.95) | 用户翻历史时复用 |

**两种 pHash 算法并存：**
- `PerceptualHash.compute()` — 9×8 dHash，64 位（1×Long），用于自动翻译状态机翻页判断（`PHASH_STABLE_THRESHOLD=0.95`、`PHASH_NEW_PAGE_THRESHOLD=0.60`）
- `PerceptualHash.computeExtended()` — 17×16 dHash，256 位（4×Long），用于缓存匹配（`SIMILARITY_THRESHOLD_MANGA=0.95`）
- **已删除的函数（不要调用）：** `isUniform()`（方差检测，已被 dHash 全零替换）、`quickSameCheck()`/`computeHist()`/`histogramDiff()`（直方图预筛，全项目无调用者）

**纯色/纯白页面检测：** `processMangaScreenshot` 入口处检查 `currentExtHashes.all { it == 0L }`（dHash 全零 = 中心区域无纹理结构）。检测到纯色页直接 `showImmediate("未检测到文字")` + toast → 跳过缓存+OCR+翻译 → IDLE。替换了不可靠的 `isUniform()` 方差检测（已删除）。

**历史分组一致性：** `TranslationCacheManager.groupMangaEntriesByPHash()` — 统一的 256-bit 相似度分组方法，供 `getHistoryGrouped`（历史列表）和 `MangaViewerActivity.buildPageGroups`（图片浏览器）共同调用，保证两处分组数量一致。

- **相似度阈值：0.95**（256-bit Hamming 距离 / 256，约容差 13 bit）——与「缓存与历史」章同一口径，改一处必须改两处
- **稀疏 hash 守卫：`MIN_INFO_BITS = 16`**（`TranslationCacheUtils.kt`，判定入口 `isSparseHash`） — 当 4 段 hash 总 bits < 16（≈6.25%）时视为「无判别力 hash」（纯色/几乎纯色页面 dHash 4 段几乎全 0），**单独成组**，不参与正常相似度合并。**这是必要的**，否则稀疏 hash 间 Hamming distance 绝对值极小（2-3 bits 不同），会被 `1 - 2/256 = 0.992` 相似度误判为同一页面（曾导致 id=237/159/152 三张不同纯色页被错误合并成一组）。

**缓存保存（`saveToCache`）** 写入 4 段 hash：
```kotlin
pHash  = currentExtHashes[0]   // 17×16 第 0 段（兼容旧版）
pHash2 = currentExtHashes[1]
pHash3 = currentExtHashes[2]
pHash4 = currentExtHashes[3]
```

**`processMangaScreenshot` 函数签名变化：**
```kotlin
private suspend fun processMangaScreenshot(
    bitmap: Bitmap,
    precomputedPHash: Long? = null,
    precomputedExtHashes: LongArray? = null  // ← 新增：从 collector 传入避免重复计算
)
```

**实时渲染共享层（2026-07 重构）：** 数据库不再存储渲染后的译文 overlay 图片（`imagePath` 停止写入），只存原始截图（`originalImagePath`）+ 气泡元数据（`bubbleRects` JSON）。所有 overlay 显示通过 `TranslationCacheManager.renderOverlay()` 实时渲染。

```kotlin
suspend fun renderOverlay(
    history: HistoryEntry,
    pageCache: PageCacheEntity,
    mode: OverlayMode,                 // TRANSLATED / ORIGINAL / PLAIN
    forFullImage: Boolean              // true=全屏（MangaViewerActivity），false=裁剪（overlay/下载）
): Bitmap?
```

| 调用方 | forFullImage | mode |
|--------|--------------|------|
| MangaFloatingService 缓存命中 | false | TRANSLATED |
| MangaFloatingService 复制模式切换 | false | 恒 `TRANSLATED`（原图/译文二态走的是 `OverlayRenderer.renderOverlay(useOriginalText=…)`，**不是** `OverlayMode`） |
| MangaViewerActivity 预览（首次加载/翻页/三态切换） | false（scope=crop，只渲染框选区域） | TRANSLATED→ORIGINAL→PLAIN |
| HistoryFragment 下载 | false | TRANSLATED |

**坐标映射规则：**
- `forFullImage=false`：从 `originalImagePath` 全屏原图按 `pageCache.cropLeft/Top/Right/Bottom` 裁剪，气泡坐标已在裁剪空间，**无需映射**
- `forFullImage=true`：渲染到全屏原图，气泡坐标需 +`(cropLeft, cropTop)` 映射

**`OverlayMode` 枚举：** `TRANSLATED`（译文）/ `ORIGINAL`（原文）/ `PLAIN`（纯原图，无 overlay）

**变体独立存储：** 每个变体各自保存 `bubbleRects` + crop 坐标，不同框选尺寸互不干扰。`groupMangaEntriesByPHash()` 把所有变体都加入 groups（之前只返回代表 entry，导致下载漏图）。

**三态循环：** MangaViewerActivity 切换按钮（btnToggleImage）三态循环：译文→原文→纯原图→译文。`OverlayMode.PLAIN` 也用在阅读器内嵌翻译的三态循环里（`ReaderTranslationController.cycleVisual`：PLAIN → ORIGINAL → TRANSLATED）。翻页自动重置为译文。

**自动翻译干净截图流程：**

状态机 STABLE 后重截干净图用于翻译和缓存。

| 模式 | 流程 | 延迟 |
|------|------|------|
| **MP** | `dismissProgressOverlay()` → 隐藏球 → `delay(50)` → `takeScreenshot()` → 恢复球 → `showProgressOverlay` | +~80ms |
| **无障碍** | `launch { delay(350)` 冷却 → 隐藏球 → `delay(50)` → `takeScreenshot()` 异步 → `pendingCleanScreenshot=true }` → 下一张 flow 拦截 | +~500-900ms |

**⚠️ frameSeq 行为（Shooter.kt）：** `frameSeq` 在 `ImageReader.OnImageAvailableListener` 回调中递增（`translate/screenshot/Shooter.kt:119`）。`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 在 Redmi/HyperOS 上仅画面内容变化时产帧 → 静态页面 frameSeq 停止递增，翻页/滑动时跳跃增长。其他 ROM 可能持续产帧 → frameSeq 不适用于跨设备屏幕变化检测。

**⚠️ VD 死亡（录屏冲突）处理：** `frameSeq` 不能用于 VD 死亡检测——已尝试并移除（commit `4628010`）。原因：框选模式下悬浮球在选定区域外，VD 可能不捕捉框外像素变化 → 藏球唤醒测试不可靠，频繁误报。当前方案：自动翻译用 40s pHash 超时兜底（同页面 40s 无变化 → 自动停止翻译 + 弹窗提示）；手动翻译无法检测，遇卡死症状（始终缓存命中同一页、自动翻页无变化）见 FAQ Q11。

**⚠️ 所有 overlay（球、进度条）必须在截图前隐藏。** 进度条文字被截入图会导致 pHash 污染和缓存误命中。

- `lastTranslatedHash` 保存检测截图 pHash，保证状态机下次比较有效
- 缓存保存干净截图 extHashes，保证跨模式命中
- 无障碍 `takeScreenshot` API 需至少 350ms 冷却（Android 12+ 后台截图频率限制），详见 [[accessibility-screenshot-cooldown]]

**手动模式隐藏球流程（`takeScreenshotWithProvider`）：** 手动模式截图前隐藏悬浮球，`finally` 块恢复。⚠️ 藏球后必须 `delay(50)` 等至少一个 VSYNC 周期，确保 VD 产出无球的新帧后再截图，否则球图标会被截入翻译结果。自动模式由上述重截图机制处理。

**悬浮球状态图标（`BallStateManager`）：** 管理悬浮球图标的状态机，5 种状态瞬时切换（无动画，仅 Error 红圈脉冲）：
- `Idle` — 用户自定义图标（从 `getExternalFilesDir/icon/` 加载）
- `Processing` — OCR 识别中图标（state2 mipmap）
- `Translating` — 翻译中图标（state3 mipmap）
- `Completed` — 翻译完成图标（state4 mipmap）
- `Error` — Processing 图标 + 红圈 1100ms 脉冲动画

游戏/漫画模式独立配置（`Mode.Game` / `Mode.Comic`），各有 4 套 mipmap。`MangaFloatingService` 在翻译流程入口设 `Processing`，非分批路径在调翻译 API 前切 `Translating`，分批路径在第一批翻译开始时切 `Translating`（保持到全部完成）。`finalizeIncremental` 结束后切 `Completed`。

**当前 DB 版本 20**（迁移链一路到 `MIGRATION_19_20`，见 `data/TranslationHistoryDatabase.kt`）。
v19→v20 新增 `translation_history.processing_state`（0=已翻译，1=OCR-only，2=失败保留 OCR），旧记录按载荷推断。
v18→v19 新增**超分逐页记录表** `imported_page_sr`（纯新增幂等迁移，详见 `data/CLAUDE.md`）。
⚠️ 真值就是 `@Database(...)` 里的 `version` —— **本行已经落后过两次**（17→18、18→19），
改表之后顺手对一眼，别照着文档里的数字推算下一个迁移号。

⚠️ **加实体/表必须同时提供迁移**：`fallbackToDestructiveMigration` 已启用 —— 漏写迁移的后果不是报错，而是**升级用户的整库被删**（译文与历史全没了）。新增表用「纯新增 + 幂等、绝不 ALTER 现有表」的写法。

**MIGRATION_9_10：** 增加 `pHash2`/`pHash3`/`pHash4` 三列存 256 位扩展 hash。

提示词仅对 OpenAI 兼容 API 生效（火山/智谱/DeepSeek/通义千问/用户自建），非 OpenAI API（Volc/DeepL/Baidu/Azure/腾讯/Bing/Niutrans/NLLB）为纯机器翻译，不接受提示词配置。

```
定义层（源头）
├── BuiltinProviders.kt    — 内置 API 默认提示词（DEFAULT_SYSTEM_PROMPT 等 6 个常量）
└── OpenAIText.kt          — 用户自建 API 默认提示词（defaultSystemPrompt + fallbackManga*）

存储层
└── CustomStorage.kt       — 内置 API: BuiltInProviderMod（只存差异）; 用户 API: OpenAIProviderConfig（全量）

读取层
└── ConfigurationStorage.loadAllProviders() → applyMod()
    内置: systemPrompt = mod.systemPrompt ?? builtin.defaultSystemPrompt
    用户: 直接读取存储值

使用层
├── 游戏模式 FloatingBallService → OpenAITranslation(systemPrompt=provider.systemPrompt)
└── 漫画模式 MangaFloatingService → OpenAITranslation(systemPrompt=provider.mangaSystemPrompt.ifEmpty{default})

发送层（OpenAITranslation.kt）
├── buildSystemPrompt()  — 上下文开启时前缀 "根据上下文剧情进行翻译..."
├── buildUserPrompt()    — 替换 usefromlang/usetolang/usesourcetext 占位符
└── buildRequestBody()   — messages: [system, (历史user/assistant对), user]
```

游戏/漫画提示词分离：`provider.systemPrompt`（游戏）和 `provider.mangaSystemPrompt`（漫画）独立存储、独立配置。漫画模式额外支持续写格式控制（见下文）。

**聚合 AI 翻译续写模式（漫画格式控制）：**
漫画翻译使用各厂商续写模式（assistant prefill）硬约束输出 `[1] 译文` 格式：
- 火山引擎：`CONTINUATION_STANDARD`，无额外参数
- 通义千问：`CONTINUATION_PARTIAL`，`partial: true`
- DeepSeek：`CONTINUATION_PREFIX`，`prefix: true` + **beta 端点** `/beta/chat/completions`（⚠️ beta 版本，后续可能变更）
- 智谱AI：`CONTINUATION_JSON`，`response_format: json_object`，返回 JSON 格式 `{"translations": [...]}`
- `OpenAITranslation` 根据 `continuationType` 参数处理不同续写方式

**⚠️ 编号解析对位（2026-09-25 漏翻事故，勿回退）：** prefill `"[1] "` 的内容**不出现在返回的 content 里**（火山/DeepSeek 实测 content 直接从译文开始，接着才是 `[2]..[N]`），于是「标记数 = 条数 − 1」是**常态**，`parseNumberedTranslations` 必然走降级分支。三条硬规矩：
1. **降级不能按行拆**：有标记但不满条数时，**第一个标记之前的正文就是第 1 条**，按编号对位重建；跳号只留空该号（调用方回退原文），绝不整批前移。
2. **行首编号前缀正则必须是 `^(?:\[\d+]?|\d+[.、])\s*`**，不许写回 `^\[?\d+]?[.、\s]*` —— 后者各段独立可选，能匹配「一个裸数字开头的行」，把页码正文 `330` 整行吃掉 → 少一条 → 补空串 → 整批译文前移一位、末条空白（`dst=''`）。实测三家同一页：火山/DS 稳定复现，智谱走 JSON 数组按下标取因而免疫。
3. **空译文不入结果、不入缓存**：`translateBubbles` 出口 `ifBlank { originalText }` 回退原文；`translateWithCache` 跳过空译文（否则同一阅读会话里相同/相似文本命中缓存、连 API 都不调 → 「换 API 也没用」）。
- 纯数字气泡（页码 `330`）在 `translateBubbles` 就被短路（`isNumericOnlyText`，原文回填、不进批次）—— 它正是上面的触发源。
- ⚠️ **触发条件比"批内有页码"更窄：必须是该批的第 1 条**。只有 prefill 那一行没有 `[N]` 标记，带标记的行（`[3] 330`）只被剥掉标记、数字留着，实测完全正常。所以「竖排页边页码」最危险：竖排小框（如 23×70）被 `h > w + 2` 判成竖排 → `sortByReadingOrder`「竖排优先于横排」把它排到**批首**（2026-09-25 实测那本《俩人的切换开关》每页页边竖排印页码，371/372 两页稳定中招；同库《间谍过家家 140 话》页面无印刷页码 → 从不触发）。
- 回归守卫：`TranslateUtilsNumberedParseTest`（含日志里的真实 Response 原文）、`TranslateUtilsPassthroughTest`。

**⚠️ 自定义 API prefill 守卫：** 用户自定义 provider 的 `continuationType` 默认为空字符串，会给不支持续写的 API 发送假的 assistant prefill → 服务端 hang → 30s 超时。三层防御：
1. `MangaFloatingService` / `MangaViewerActivity`：对 `!provider.isBuiltin` 强制 `CONTINUATION_NONE`
2. `buildRequestBody` 白名单：仅 `standard/partial/prefix` 启用 prefill，空字符串/未知值不放行
3. 自定义 API 漫画 prompt 为空时回退到内置 `DEFAULT_MANGA_SYSTEM_PROMPT`（漫画翻译引擎），避免空 prompt 导致模型返回聊天废话。UI 侧 `setupUserMode()` 也为自定义 API 显示重置按钮，漫画 tab 重置到 `fallbackMangaSystemPrompt` / `fallbackMangaUserPrompt`（和 `BuiltinProviders` 的 `DEFAULT_MANGA_*` 一致）

**内容安全审查：** 各 API 平台可能拦截敏感内容翻译（错误码 `data_inspection_failed`，HTTP 400）。不同平台审查阈值不同，被拦截时换平台或换模型。

**AI 上下文（游戏模式）：** `FloatingBallService` 维护 `LinkedList<Pair<String, String>>` 存储历史翻译对（原文, 译文）。开启后系统提示词追加"根据上下文剧情进行翻译"，messages 中插入历史 user/assistant 对。**按 token 预算裁剪**（不再是「轮数」）：`ContextBudget`（`utils/`）是**全项目唯一的裁剪实现** —— `budgetOf(prefs)` + `trim(history, budget)` 从最新往前累加 `TokenEstimator.estimatePair`，装不下的最旧轮直接丢，另有 `MAX_TURNS=200` 兜底；`trimInPlace` 供服务侧 `LinkedList` 用（原先两处各写一份的 `while (size > max) removeFirst()` 已删）。设置项：`game_context_enabled`（开关）、`ctx_token_budget`（上下文长度，ListPreference 4K/8K/16K/32K/64K/128K，默认 32768，存为 String；旧的 `game_context_count` 已废弃）。
**生效范围**：聚合 AI（OpenAI 兼容）按 `ctx_token_budget` 裁剪；**本地 LlamaCpp 也吃上下文**，但**不受该设置影响** —— 预算 = 模型 `contextSize − maxTokens`（取不到用 ctx/4）再扣掉本轮原文，历史作为 `[Context/前文]…[Current/当前]` 前缀拼进 **user 文本**（native 只有 system/user 两个字符串，没有多轮接口），拼接前按上面的预算裁剪；**NLLB 等纯机器翻译刻意不接**（没有提示词通道）。实现见 `translate/ContextAwareTranslation.kt`（接口）+ `ContextBudget.applyTo`（唯一写入入口）。
**重置时机**：`FloatingBallService` / `MangaFloatingService` 的 `clearContext()` 在**新会话开始**（`onCreate` 里翻译器建好后）与**服务停止**（`onDestroy`）时清空各自历史 + 引擎侧那份（LlamaCpp 是进程级共享实例，不清会把上个会话的历史接着用）。**不在「退出漫画阅读器」时清**：阅读器退出后仍可能有章节后台批量翻译在跑，此刻清会抽掉在途批次的上下文；阅读器自己的历史是 `ReaderTranslationController` 的实例字段，随 Activity 消失。

**AI 上下文（漫画模式）：** 正常漫画翻译不使用上下文。仅增量渲染的两批之间使用上下文（`forceContext=true`），翻译完后回滚，不污染后续页面的上下文历史。

**OpenAI 兼容 API 思考模式（thinkingMode 三态）：**
- `OpenAIProviderConfig.thinkingMode`（Int: 0=跟随模型默认不发参数 / 1=强制关闭 / 2=强制开启）；内置 `BuiltInProviderMod.thinkingMode` 存 diff（null=回退内置默认，`applyMod` 处理）
- **默认不发 thinking 参数**：硅基流动等不支持思考参数的模型会报 `enable_thinking` 400（`thinking:{type:disabled}` 被网关归一化成 enable_thinking）
- 内置 DeepSeek 默认 = 强制关闭（保持翻译速度）；火山/智谱/千问/自定义默认 = 跟随模型默认
- `OpenAITranslation.buildRequestBody` 按三态发 `thinking:{type:enabled/disabled}` 或完全省略；测试连接同样按当前选择
- ⚠️ 不要改回「总是发 thinking:disabled」——那会重新破坏硅基流动等严格校验参数的模型

**配置存储：** `CustomPreference` 单例封装 `SharedPreferences`。API 密钥通过 `KeystoreManager` 加密存储。

⚠️ **`BuiltinProviders` 里 `OpenAIProviderConfig.name`（火山引擎/智谱AI/通义千问…）是持久化身份 key**：`BuiltInProviderMod` 靠它匹配用户已存配置（API Key/提示词/模型）→ **永远不要改成英文或其他语言**，否则老用户全部配置静默失配。本地化显示走 `nameRes`（`@StringRes`）+ `displayName(context)`；新增厂商显示名先查 `volcapi_name` 这类旧 key 是否已有同名值（曾因此重复定义 key 撞资源合并）。

⚠️ **`modelName` / `models` / `selectedModelIndex` 三者必须永远一致（面板显示 == 引擎实际调用）。**
面板显示的是 `models[selectedModelIndex]`（`OpenAIText`），引擎调用的是 `modelName`（`TranslatorFactory`），但它们是**三个独立字段**。
- 内置厂商一律走 `BuiltinProviders.builtinProvider(...)` 工厂，由 `defaultModelIndex` 一处派生 `modelName` 与 `selectedModelIndex`，**不要改回手写 `OpenAIProviderConfig(...)`** —— 手写就可能对不上（智谱AI 曾把默认模型写成 `modelName="glm-4-flash-250414"` 而 `selectedModelIndex=0` 指向 `"glm-5"`，表现为面板显示 glm-5、实际调 glm-4-flash-250414）
- **展示列表 = `provider.models`（预设）+ `BuiltInProviderMod.customModels`（自定义）**，所以 `selectedModelIndex` 完全可能 ≥ `models.size`；断言一致性时要拼上自定义模型
- `applyMod` 对越界下标做归一，且 **`modelName` 与 `selectedModelIndex` 必须一起归一** —— 只归一一个的话弹窗里没有任何模型高亮，用户看不到自己在跑哪个模型（内置列表改版后老用户的下标就会越界）
- 内置模型列表的**下标是持久化语义**，改列表顺序/增删会让老用户的 `selectedModelIndex` 指向别的模型
- 弹窗里的模型标注（「免费·文本」「免费·视觉」「免费·思考」）走 `OpenAIProviderConfig.modelLabels`（在 `BuiltinProviders` 登记），**不要在 `OpenAIText` 里硬编码模型名**
- **四个内置厂商都支持「获取模型列表」**（`supportsModelFetch`，控制弹窗里那个按钮的显隐），其中 **DeepSeek / 通义千问不预置模型**：`models` 为空、`modelName` 为空串，用户进配置页时模型选择是空的，自己手动加或点「获取模型列表」从 `GET {baseUrl}/models` 拉（`OpenAITranslation.getSupportedModels`，用编辑框里**当前**的 key，不必先保存）。**火山引擎 / 智谱AI 保留预置列表当兵底**。不预置时三处取值必须空安全，否则崩：`CustomStorage.applyMod`（`modelName = ""`）、`OpenAIText` 的 `initialDisplay` 与 `displayModels`（`getOrNull` + 提示，不是 `[0]`）。保存时列表为空会被拦下（`model_pick_at_least_one`）
- `getSupportedModels` 的失败信息优先透出服务端自己的说明（OpenAI 兼容格式 `{"error":{"message":"..."}}`），不要直接把原始 JSON 丢给用户；401/403 会拼上「API Key 无效」前缀。它和 `translate` 一样有 `LogCollector` 日志（打 apiKey **长度**不打内容，401 排查时"key 是不是空的/被截断"是最常见原因）
- 回归守卫：`BuiltinProvidersTest`（name 稳定性、默认模型与下标一致、标注只指向存在的模型、四家都支持获取 / 仅两家不预置）、`CustomStorageTest`（越界归一、下标与名字同步、无模型时 modelName 为空）

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app` + **`:llamacpp`**（llama.cpp 源码随 submodule 一起编，产出 `libllamacpp.so`）+ **`:sr`**（ncnn + Vulkan 超分，源码自编，**不入库**，见「环境搭建」第 4 步）。`:app` 的原生代码仍走 CMake（`app/src/main/cpp/`，只剩 sentencepiece）；`:llamacpp` 走 `llamacpp/src/main/cpp/CMakeLists.txt`，首次编译前跑 `llamacpp/setup-submodule.ps1` 拉 submodule（脚本已配 `core.longpaths` + sparse-checkout 排除 `tools/ examples/ docs/ tests/`，否则 Windows 路径过长会 checkout 失败）。APK 里的 native 库现在是 `libllamacpp.so` + `libc++_shared.so`（旧的 `libhymt2.so` 预编译库与 `app/src/main/jniLibs/` 已删）。
⚠️ **那个 submodule 不是一个「已注册的 submodule」**：`llama.cpp` 是**官方仓库**（`github.com/ggml-org/llama.cpp`，MIT），pin 在官方提交 `1e411d8f`，工作区无本地改动；`patches/0001-*.patch` 只是留档（STQ1_0 上游已合入）。脚本做的是**在 `llamacpp/src/main/cpp/llama.cpp` 放一份独立 clone**（`git clone --depth 1 --no-checkout` + sparse-checkout + fetch 指定 SHA），所以：
- `git submodule status` 显示 `-1e411d8f...`（前缀 `-` = 未在 `.git/config` 注册）**是正常的**，不影响构建；
- **别跑 `git submodule update --init`**（脚本头部就写了它在 Windows 上会因长路径失败），也**别在主仓库跑 `submodule update`**——那会另开一份 `.git/modules` 存储、与现成的那份打架；
- 本机已有副本时（比如从 worktree 里拷）**直接拷目录即可零下载**，拷完用 `git -C llamacpp/src/main/cpp/llama.cpp status` 验证——它报「无缺失/无修改」就说明与 pin 的提交逐一对得上（**别用文件计数验证**，`cp` 拷歪成多一层嵌套时计数会看不出来）。

## 模型管理

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/download/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 漫画模块

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/manga/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 漫画导入书架 + 阅读器（`mangaimport/`）

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/mangaimport/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 小说模块（`novel/`，feature/novel-text-import）

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/novel/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 自动翻译

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/translate/CLAUDE.md`（游戏模式）+ `manga/CLAUDE.md`（漫画自动翻页）
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 缓存与历史

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/data/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 日志规范

**所有日志必须通过 `LogCollector` 写入**，不能直接用 `Log.d/i/e`。

**统一日志落盘（v0.10.x 新增，排查 native 闪退的关键）：**
- `starflow.log`（`getExternalFilesDir/logs/`）持久化最近 **300 条**日志（所有级别），追加写入、
  超 300 行自动换出最旧；**闪退/进程死亡后文件仍在**，重开 app 时 `init()` 载入缓冲，
  日志查看器能看到上次（含多次）崩溃的记录；用户可在日志查看器手动清空（`clear()` 清缓冲+文件）
- LlamaCpp bridge（`llamacpp_bridge.cpp`）的普通日志走 logcat（`bridge_log`，不写文件）；
  SIGSEGV/SIGABRT/SIGBUS 崩溃时信号处理器把 backtrace + abort message **追加**写入同一文件
  （不覆盖旧日志，多次崩溃都保留；300 行滚动由 Java 侧维护——crash_handler 只允许
  async-signal-safe 操作）后 **re-raise 信号**（绝不用 `_exit`——那会跳过 debuggerd，系统
  崩溃报告/tombstone/vivo·realme "服务与反馈"就抓不到崩溃，用户无法上报）；`SA_ONSTACK` +
  备用栈防栈溢出时处理器自身挂
- ⚠️ **崩溃后必须恢复 libc 原始信号处置再 re-raise**（`sigaction(sig, &g_old_sa[sig])`，安装时
  用 `sigaction(sig, &sa, &g_old_sa[sig])` 保存）：Android 的 debuggerd/tombstone 依赖 libc 的
  `debuggerd_signal_handler`（socket 通知 crash_dump），不是内核 core_pattern——直接 `SIG_DFL`
  + raise 实测不产生 tombstone；re-raise 前还须 `sigprocmask(SIG_UNBLOCK)` 解除该信号阻塞
  （处理器运行时被处理信号自动加入线程屏蔽集，否则 raise 后走 `_exit` 正常退出、无 tombstone）
- **崩溃块诊断（v0.10.3 增强，一次定位根因）**：crash_handler 崩溃块除 backtrace 外还写入——
  `time=`（epoch 秒）、`tid=/thread=`（崩溃线程，判断主线程还是翻译线程）、`mem:`（VmRSS）+
  `memavail:`（系统可用内存，判断是否 OOM 相关）、`diag:` 行（`in_translate/prompt_chars/
  prompt_tokens/n_ctx/max_tokens/gen_token/prefix_cache`，来自全局 `CrashDiag`，translate_impl
  入口/分词后/生成循环实时更新）、`prompt_preview`（prompt 开头 160 字符）。backtrace 用
  **`dladdr` 符号化**（async-signal-safe）：每帧 `pc=... 函数名+偏移 [.so 名]`，崩溃块直接可读、
  无需 PC 端 addr2line。排查本地模型（LlamaCpp）闪退看 `diag:` 的 `prompt_tokens` vs `n_ctx` 判断是否超
  context、`gen_token` 判断崩在解码还是生成
- **崩溃块滚动保护**：`LogCollector.trimFileToTail` 文件含 NATIVE CRASH 块时从块起始保留
  （崩溃块 + 其后日志），闪退后即使重开 app 又产生大量日志，崩溃块也不会被 300 行滚动挤出——
  重开 app 后日志查看器/导出一定能查到崩溃块
- **超长 prompt 防御（防 ggml_abort 闪退）**：`translate_impl` decode 前检查
  `n_tokens >= llama_n_ctx - 64` → 返回 `__PROMPT_TOO_LONG__`（Java 侧转友好错误，
  不喂给 llama）；漫画 34 气泡批量合并的 prompt 可能超 n_ctx=2048，宁可翻译失败也不闪退
- `StarFlowApplication.onCreate` 顺序：`LogCollector.init(this)` →
  `Thread.setDefaultUncaughtExceptionHandler`（Java 未捕获异常写入日志后交给原 handler）→
  **`installNativeCrashHandler()`（直接调 `LlamaCppNative.nativeSetLogFile`，不等 LlamaCpp 初始化，
  覆盖 PP-OCR/ONNX/RT-DETR/sentencepiece 等所有 native 库的崩溃）** →
  **`logPreviousExitReasons()`（Android 11+ `getHistoricalProcessExitReasons` 记录上次进程退出
  原因：崩溃/ANR/被杀/内存不足 写 E 级，主动退出写 I 级；只引用 API 30 常量，高版本常量
  统一走 else 防低版本 NoSuchFieldError）**；
  `LlamaCppTranslation.setupCrashDir()` 幂等调用 `nativeSetLogFile`（fd 已开则跳过，单测 JVM
  无 .so 时静默跳过）
- 用户获取：关于页 → 查看日志 → 导出，导出文件即 `starflow.log` 内容（最近 300 行）
- **崩溃日志测试（开发者选项页底部「崩溃日志测试」）**：测 Java 崩溃（后台线程抛异常 →
  `UncaughtException` 落盘 + 系统 FATAL）；测 Native 崩溃（`LlamaCppNative.nativeTriggerNativeCrash`
  触发 SIGSEGV → crash_handler 写崩溃块 + 恢复 libc handler re-raise → 系统 tombstone + 崩溃对话框）。
  小米 ROM 前台崩溃会 30ms 内自动重启 app（SmartPower），看到"没闪退"不代表崩溃没发生
- **缓存命中标记开关（开发者选项页「缓存命中标记」）**：控制漫画内存缓存命中译文前的 ⚡ 显示
  （`KEY_CACHE_MARKER`，默认关闭）。渲染统一走 `renderOverlay(showCacheMarker)`（OverlayConfig
  字段），MangaFloatingService 直接渲染处从 prefs 读
- ⚠️ 内存缓冲（查看器实时显示）在崩溃瞬间丢失，但文件持久化保留——排查闪退读文件/导出，
  或看 app 内查看器（启动时已载入文件内容）

logcat 过滤器：
```
tag:OCRBridge | tag:DetectionBridge | tag:BubbleDetector | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer | tag:TranslationCacheManager | tag:AutoTranslateEngine | tag:FloatingBallService | tag:GameOcrEngine | tag:Screenshot | tag:Shooter | tag:OpenAITranslation | tag:TranslateUtils | tag:ExitReason

阅读器内嵌翻译（导入功能）日志 tag：`ReaderTranslate`（控制器：逐气泡 rect/原文/译文/来源、缓存命中统计、翻译开始时的引擎与语言）、`IncrementalBatch`（分批管线：`translateWithCache` 命中数）。
```

## 安装规范（最高优先级）

**绝对禁止未经用户确认就执行 `adb uninstall`！**
安装失败时：只报告错误，询问用户是否需要卸载重装，等用户明确同意后才能执行。这条规则没有例外。

## 设备操作防护（最高优先级）

> 🩸 **2026-09-30 事故**：在装着真实数据的手机上跑 `:app:connectedDebugAndroidTest`。
> AGP 的 connected 测试走 UTP，**安装前先卸载被测 app**；MIUI 拦下了随后的安装，
> **卸载没有回滚** → app 连同 `/data/data/`、`/sdcard/Android/data/` 一起没了，
> 且无备份可恢复（书架 / 翻译历史 / 全部设置 / API Key）。
>
> 关键教训：**这条命令里一个 `uninstall` 字样都没有。**
> 所以判据必须是「**这个操作会不会删 app 或它的数据**」，而不是「名字里有没有 uninstall」。

### 四层防护（都在仓库里，改动前先读 `guard/README.md`）

| 层 | 机制 | 位置 |
|---|---|---|
| L1 硬拦 | Gradle init script：含 `uninstall` / 以 `connected` 开头 / `managedDevice` 的任务**直接抛异常** | `~/.gradle/init.d/device-guard.gradle`（机器级，不在仓库，甩不掉） |
| L2 审查 | 命令分类器 + 61 条判定用例（可证伪） | `guard/device_guard.py` / `guard/test_device_guard.py` |
| L3 可恢复 | 全量备份 / 还原（私有数据 + 外部数据） | `guard/backup_app.py` / `guard/restore_app.py` |
| L4 流程 | 本节 | 根 `CLAUDE.md` |

### ⚠️ `guard/backup_app.py` 的**私有数据对 release 包无效**（2026-10 实测）

它走 `adb exec-out run-as <pkg> tar …`，而 **release 包不是 debuggable** →
`run-as: package not debuggable: com.moe.starflow` → `private.tar` 只有 **49 字节**，
**而脚本照样打印「备份完成：私有 0.00 MB + 外部 xx MB」并返回 0**（只是少打一行提示）。
私有数据 = `/data/data/<pkg>`（API Key / 全部设置 / 数据库）。

- 要备份私有数据，**先装一次 debug 包**（同签名，`adb install -r` 原地升级、不丢数据），备份完再装 release；
- 或者至少知道：**看到「私有 0.00 MB」时，这一半的备份是空的**。
- 外部数据（`/sdcard/Android/data/<pkg>`，漫画/小说/模型/超分缓存）不受影响，照常能拉。

### ⛔ 默认绝对不做（BLOCKED）

| 类别 | 例 |
|---|---|
| 卸载 / 清数据 | `adb uninstall`、`pm uninstall`、`pm clear`、`cmd package clear` |
| **会卸载的构建任务**（★ 无 uninstall 字样） | `connectedDebugAndroidTest`、`connectedCheck`、`managedDevice*`、`uninstallAll/Debug/Release` |
| **隐藏的"清数据"开关** | `am instrument -e clearPackageData true`、`-Pandroid.testInstrumentationRunnerArguments.clearPackageData=true` |
| 直删 app 数据目录 | `run-as <pkg> rm ...`、`rm -rf /data/data/<pkg>`、`rm -rf /sdcard/Android/data/<pkg>` |
| 通配符 / 整机 | `rm -rf /sdcard/Android/data/*`、`fastboot -w`、`recovery --wipe_data` |

### 做任何设备操作前的**强制四步**

1. **分类**：`python guard/device_guard.py "<整条命令>"`
   —— 退出码 2 就是 BLOCKED，**不要执行**；1 是 CAUTION，先向用户说明影响
2. **快照**：`python guard/snapshot_state.py`（记录 app 在不在、数据目录条目数）
3. **备份**（仅当操作可能动到安装状态时）：`python guard/backup_app.py`
4. **执行后立刻复核**：`python guard/snapshot_state.py --diff`
   —— 出现 `installed: true → false` 或 `extEntries` 变少，**立即停手报告**

### ⚠️ 必须问用户（CAUTION）

`adb install`（签名不一致可能触发卸载重装）、`settings put`（会降安装校验强度）、
`adb push` 到 app 数据目录、`am force-stop`、`adb shell input/svc power`、`pm trim-caches`。
`adb install` 与 `adb uninstall` **一律先问**，不得自行决定。

### 允许的只读手段（SAFE，可直接用）

`adb devices`、`adb logcat -d`、`adb shell dumpsys/getprop/pidof/ls/stat/df/cat`、
`adb pull`、`gradlew assemble*/test*/compile*`、`guard/` 下所有脚本。

### 本机测试设备（2026-10 实测）

- **已无线配对**（小米 M2012K10C，Android 13）：`adb devices` 里是
  `adb-xxxx._adb-tls-connect._tcp` 条目，可直接用，**不需要再 `adb pair`**
- **release 与 debug 同签名**：`app/build.gradle:70` 是
  `release { signingConfig signingConfigs.debug }` → 设备上装的 release 与本地构建的包同 key，
  `adb install -r app-release.apk` **原地升级、保留数据**。
  （⚠️ 依然不要卸载重装：卸载会连 `/data/data` 与外部数据一起删，见上面那起事故）
- `python guard/backup_app.py` 约 **2 分钟 / 约 2GB**（私有 38MB + 外部 2GB），是装前必做的那一步
- 装完用 `python guard/snapshot_state.py --diff` 复核：**只该出现 `ts` 变化**；
  若 `installed: true → false` 或 `extEntries` 变少，立即停手报告

### 验证由用户自己做

修复后给清晰的验证步骤（观察什么、预期什么），用户自己构建、安装、点击测试。
需要设备状态时明确说「请你测试 X」。这条规则历史上被违反过多次，详见 memory `[[no-device-operations]]`。

## 双模式截图

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/translate/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 框选 / overlay 坐标系（硬约束，改动前必读）

**核心原则：坐标系错位一律让两套坐标「重合」，绝不用 offset 去补。**

补 offset 等于把「两个坐标系相差多少」硬编码进调用点 —— 差值随设备与朝向变化，
补错方向就换个符号再试，表现为「偏右 → 偏左」来回横跳，每轮都像新 bug。
实际反复踩的坑就是这么来的：**根子从来不是补偿量算错，是坐标系没统一。**

### ⚠️ 改这一区之前：先判断「这是不是同一个 bug 换了张脸」

本区曾连续 5 次修改都在**调「哪个来源优先」**，每次修好一处、另一处炸：
取 max → 闩锁校正 → 逐轴 max → 朝向压制 → 时刻判定（最终）。

**判据**：如果你正打算给两个来源**再定一条优先级规则**，停手 ——
那说明其中一个来源**缺少时间信息**，问题的形状是「谁更新」而不是「谁重要」。
先补齐时刻，再谈取值。（同理：`isReliable` 这类「可信度标志」若无人消费，
它就不是设计的一部分，别拿它当依据。）

**另一个高发的同形陷阱**：`onConfigurationChanged` 里 **先读后写**同一个变量 →
比较变成「新值 vs 新值」→ 条件恒假 → 整条链路静默失效。见下方专节。
**凡是「读旧值→写新值→比较」三段式，顺序错一次就全废，且没有任何异常可查。**

### 四套坐标，出问题只可能在「谁与谁不同源」

| 空间 | 原点 | 谁产生 |
|------|------|--------|
| 截图帧 Frame | 帧左上角 | `Shooter` 的 VirtualDisplay |
| 框选窗口 Window | 框选 overlay 的 (0,0) | WMS 按 flags + 挖孔模式布局 |
| 显示 Display | 物理屏左上角 | — |
| 视图 View | = 框选窗口原点 | `CropView.mRect` |

**帧的尺寸是从框选窗口学来的**（`DisplaySize` 由 `CropView.onSizeChanged` 上报），
所以**帧与 `cropRect` 永远同一坐标空间** —— 裁剪/OCR 位置在结构上不会错。
唯一可能错开的是**结果浮层的 x/y**（它按显示坐标解释）。

### ⚠️ 挖孔模式决定「窗口 frame 等不等于 display」

默认挖孔模式下 WMS 会**避开刘海**给一个安全矩形，于是产生第二套坐标系。横屏实测：

```
display    2712 x 1220
框选窗口   2574 x 1220   原点 (138,0)      ← 被挤掉一个刘海宽度（2712-2574=138）
```

而 `cropRect` 是**窗口局部**坐标、结果浮层 x/y 是**显示**坐标 → 差 138px，译文整体偏移。

**修法**（`OverlayWindow.applyFullDisplayCutout`）：让窗口覆盖整个 display，两套坐标重合。

```kotlin
// screenshot/OverlayWindow.kt —— 唯一入口，勿在调用点手写 flags
API ≥ 30 → LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
API 29   → LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES   // ALWAYS 是 API 30 常量，29 上引用即抛异常！
```

⚠️ `ALWAYS` 是 **API 30** 新增，在 API < 30 上**引用即抛 `IllegalArgumentException`**。
本项目 `minSdk 29` —— 写死它会让 Android 10 直接崩。必须分级（物理刘海都在短边上，
`SHORT_EDGES` 在 29 上语义最接近）。

### ⚠️ 竖屏会把这类问题完全掩盖

刘海在顶部时窗口横向铺满，两套坐标恰好重合 → **只坏横屏**。所以：
- 改坐标相关代码后**必须测横屏**，竖屏通过不等于对
- 「横屏才有问题」本身就是「两套坐标系」的强信号

### ⚠️ 同一个坑的第二处：提示浮层 `TranslationStatusOverlay`（2026-10-01）

**症状是「提示条永远比章节胶囊低一大截，怎么调 dp 都像原地没动」**——不是没调对，是竖向也差一整个系统栏：

| 窗口 | frame | `y` 的含义 |
|---|---|---|
| 阅读器 `MangaReaderActivity` | `[0,0][1220,2712]`（`layoutInDisplayCutoutMode=always`，整屏） | 视图坐标 == **屏幕坐标** |
| 提示浮层 `TYPE_APPLICATION_OVERLAY` | `parent=[0,138][1220,2660]`（WMS 按系统栏内缩） | 窗口坐标 = 屏幕坐标 **−138px** |

宿主传 `pill.bottom`（视图坐标）当窗口 `y` 用 → 整体低 138px（≈42dp）。**和上面框选那个是同一个病**：
两个来源不同源。修法同样不是补 offset，而是**统一坐标系** —— 宿主一律用 `getLocationOnScreen`
取屏幕坐标，浮层侧把屏幕坐标换算成窗口坐标。

⚠️ **内缩量只能"量"不能"算"**：WM 用的是 stable/override insets，沉浸全屏时
`WindowInsets.statusBars` 报 0 而 parent frame 仍是 138，也没有公开 API 拿得到。
`calibrateTopOffset` 用 `getLocationOnScreen()[1] − 上次写进 LayoutParams.y 的值` 反推（精确、无假设）。
**改这一区先想清楚：这个 y 是屏幕的，还是窗口的？** 混用只差 42dp、肉眼一眼看不出，只会表现成"有点偏低"。

### ⚠️ `CropView.absolutePointOffset` 的取值时机

它是「窗口原点在显示上的位置」。**必须用 `post{}` 取**（排在布局之后）——
在 `onSizeChanged` 里取 `getLocationOnScreen` 时窗口刚 resize、WMS 尚未定位完，
实测读到 `(0,0)`（真值 `(138,0)`），且此后再不更新 → 「本该补偿」实际是空的。
另外 `confirmCrop()` 移除视图后该字段仍被消费方读取，**要趁窗口还在时抓下来**。

### ⚠️ 「屏幕变了吗」的判据：`cropGeometryChanged()`

**两个模式必须同源**（游戏/漫画同名同义，游戏曾有弱判据 `mRectF != null` 已对齐）：

```
框选确认时        记下 getScreenSize()        ← 必须与下面比较的来源一致
触发前守卫         cropGeometryChanged() → 清框 + 停自动翻译 + 提示
collector 拿到帧   同一判据（帧也是按 getScreenSize 建的，同源）
```

⚠️ **基准与比较必须同源**。曾「框选时记 `cropView.width/height`（窗口）、之后比 `getScreenSize()`（屏幕）」——
两者不是同一个量（窗口 1080x2356 vs 屏幕 1080x2400），于是**每次框选都被判「几何变化」**（稳态必现）。

⚠️ 判据**不能用** `resources.configuration.orientation`（Service 进程里被冻结在初始化方向，
恒为「未变化」：游戏原来的三处比对是死代码，漫画的条件退化成 `cropRect != null`）。

两模式失效后行为**有意不同**：游戏清框选并要求重新框选；漫画**回退全屏** ——
`triggerTranslation` 是自动翻译循环每帧都会过的路径，在那儿弹框选界面会让自动翻译彻底停摆。
**两者都停自动翻译 + 提示**（文案按当时是否在自动翻译区分 `_auto` 后缀）。

### ⚠️ `onConfigurationChanged`：先读后写，且只在朝向翻转时清

```kotlin
val knownBefore = DisplaySize.currentOrientation()   // ① 先读旧值
DisplaySize.reportOrientation(nowLandscape)          // ② 再上报新值
if (knownBefore == null || knownBefore != nowLandscape) clearCropForScreenChange()
```

- ⚠️ **顺序反了 = 整条链路静默失效**：先写后读的话比较变成「新值 vs 新值」→ 恒假 →
  `clearCropForScreenChange()` 一次都不执行（实测踩过：两模式回退+提示**完全失效**）。
  `OrientationHandlerOrderingTest` 锁死这个顺序。
- ⚠️ 只判朝向翻转：本回调对改字号/语言/密度/uiMode **同样触发**，无条件清框会把用户
  正在用的会话平白毁掉。
- `knownBefore == null` 时**照清不误**（宁可多清，不可漏掉转屏导致按旧坐标出结果）。

### ⚠️ `CropSpace` 目前是**孤儿模块**（未接线）

`CropSpace` / `WinGeom` / `FrameGeom` 生产代码**零调用**，只有测试在用。
坐标正确性目前靠「窗口覆盖整屏 ⇒ 两套坐标重合」保证，**不是**靠它换算的。
它是为 **P3**（provider 停止裁剪、裁剪统一到一处）准备的基础设施，别误以为当前链路走它。

- **恒等分支**：帧与窗口**同尺寸**时原样返回、不做任何算术 → 正常设备零回归。
  判据**只看尺寸、不看窗口原点**（帧覆盖的就是窗口那块像素区，两者同为窗口相对坐标）。
- 用 `WinGeom`/`FrameGeom`/`IntRect`，**刻意不复用排版内核的 `Box`**（Float、另一种语义）。
- **帧语义尚未实测定标**（「整屏等比缩放+黑边」还是「1:1 取景」，两种公式不同），
  尺寸不同时保持原样并标 `calibrated=false`，**未定标前不得补比例公式**。

### 顺带查实的三个坑

- `ScreenshotManager.cropBitmap` 退化分支（`w<=0||h<=0`）曾**返回 `source` 本身** →
  `croppedBitmap === fullBitmap`，而消费端写着 `if (croppedBitmap != null) fullBitmap.recycle()`，
  前提被打破 → 已改为返回 null
- `emitScreenshot` 用 `tryEmit`（容量 1）静默丢弃 → 两张 bitmap 泄漏，自动翻译下**无界泄漏** → 已改为丢弃时回收
- 漫画 `cropViewParams` 初始化曾用 `getScreenSize()` 的像素值（横屏下被冻结成竖屏），
  与游戏模式的 `MATCH_PARENT` 口径不一致

### 待办：这条链路仍是「两份平行实现 + 三种裁剪分工」

坐标系已统一（上面那节），但**结构混乱还在**，改一处要同时改对三处且无机制保证一致：
| 问题 | 现状 |
|------|------|
| 两份平行实现 | `FloatingBallService.takeScreenshotWithProvider`（不藏球）与 `MangaFloatingService` 同名版（藏球、服务自己裁、190 行 collector vs 23 行）；`initScreenshotProvider`/`startForegroundForScreenshot`/`isViewAdded` 也是各一份 |
| 三种裁剪分工 | 同一个 `MediaProjectionProvider`：**MediaProjection 路径游戏与漫画都传 `null`、各自裁**；只有无障碍分支传 `cropRect` 交给系统回调裁 |
| 语义靠猜 | 消费端用 `croppedBitmap != null` 判断「裁过没有」，保不住语义 |

**方向已定，未实施：**

- **P3　provider 不再裁剪** —— provider 只产全屏帧，裁剪统一到一处，消除 `croppedBitmap != null` 判据。
  需同时改 MP 与无障碍两条路径，且**无障碍帧坐标系须先实测**（与 MP 不保证一致）。
- **P4　两服务接入统一 `ScreenshotPipeline`** —— 沿用本仓库既有范式
  （`manga/pipeline/IncrementalBatchPipeline` + `BatchPipelineHost`：管线负责编排、宿主提供副作用钩子），
  参数化差异（`keepFull` / `hideBall` / `cleanRecapture`）。

完整方案见 `~/.claude/plans/linear-zooming-church.md`。

## 设置项实时生效规范（重要）

**添加任何 prefs 时，必须确认 service 侧有对应监听器**，否则用户运行时修改不会生效，必须重启服务。

三种实现方式（按优先级）：

1. **每次调用重新读 prefs**（最简单，适合"每次翻译都查"的值）
   - 例：`Incremental_Render`（每次 incrementalTranslateFlow 重读 prefs）
   - 例：`Game_Pixel_Similar_Threshold`（AutoTranslateEngine 每帧读 prefs）
   - 例：`Status_Position` / `Status_Duration`（TranslationStatusOverlay.show 每次读 prefs）

2. **service 监听 prefs listener + 刷新内部缓存**（适合"启动时读一次到字段"的性能敏感场景）
   - 例：`Manga_Text_Color` / `Manga_BG_Color` → 加入 `MangaFloatingService.watchedKeys`，触发 `loadConfig()`
   - 例：`Custom_Result_Font_Size` / `Custom_Result_Font_Color` 等 → 加入 `FloatingBallService.styleKeys`，触发 `applyStyle()`
   - 例：`game_context_enabled` / `game_context_count` → 加入 `FloatingBallService.watchedKeys`，直接更新 `contextEnabled` / `contextMaxCount` 字段

3. **service-running 检查 + 拒绝运行时修改**（适合必须重启才能生效的关键配置）
   - 例：`Ball_Gesture_Single_Click` / `Double_Click` / `Long_Press`（手势冲突检测依赖所有手势值，必须停服务才能保证安全）

**debug checklist**：新加 prefs 后，自查：
- service 启动时把它读到哪里？（字段？每次读？）
- 字段被缓存了 → 是否加入 watcher？
- 每次调用重新读 → 验证调用路径确实每次读

## 偏好键名规范（防踩坑）

漫画翻译的字号/颜色 prefs 键名统一使用 **PascalCase**：
- `Manga_Font_Size`、`Manga_Auto_Font_Size`、`Manga_Text_Color`、`Manga_BG_Color`
- **禁止混用 snake_case**（如 `manga_font_size`）— 键名不一致会导致 set 写到一个 key、get 读到另一个 key → 用户设置永远不生效（永远返回默认值）
- `TranslationCacheManager.getOverlayConfig(prefs)` 已统一为 PascalCase，被 MangaFloatingService / MangaViewerActivity / HistoryFragment 三处调用
- **所有跨文件共享的 prefs key 必须在共享位置定义为常量**，避免硬编码字符串

## 全局主题 / 应用语言 / 字体规范（横切三件套，改动前必读）

### 全局主题（暗色模式）
- `Base.Theme.MT` 与 `settheme`（SettingPageActivity）均为 `DayNight`；三态切换 `ThemeManager`（pref `app_theme`=system/light/dark → `AppCompatDelegate.setDefaultNightMode`），主页右上角按钮循环，`StarFlowApplication.onCreate` 启动应用
- 语义色单一来源：`values/colors.xml`（白天）+ `values-night/colors.xml`（夜间）——`surface`/`surface_variant`/`surface_secondary`/`text_primary`/`text_secondary`/`text_hint`/`text_tertiary`/`divider`；`primary`/`red`/`success`/`white` 日夜间一致
- 新布局**禁止硬编码 hex**，用语义色；`@color/card_background` 亦有夜间值

### 阅读器主题隔离（硬约束）
- 阅读器背景/面板/卡片**不随全局主题**，走自身 darkPanel；「默认/自动」背景读 `Resources.getSystem()`（⚠️ 不是 Activity `resources.configuration`——会被 AppCompat 强制主题污染）
- `mangaimport/reader/` 的布局与代码**不迁移语义色**；阅读器浅色弹窗/面板用常白 drawable `bg_dialog_white`/`bg_bottom_sheet_light`
- ⚠️ `dialog_background`/`bg_bottom_sheet` 已是 `@color/surface`（随主题翻转）——**不可用于阅读器浅色场景**，否则暗色主题下阅读器弹窗/面板变深
- 阅读器专属 drawable（`ic_reader_back/next/prev`、`ic_tab_sel*`、`ic_sel_*`、`bg_preview_*`、`ic_bg_*`）保持**固定浅色**，不可引语义色

### 应用语言（i18n）
- `LanguageManager`（pref `App_Language`：system/en/zh）。**Activity 与 Service 都必须 `attachBaseContext` 应用语言**——`FloatingBallService`/`MangaFloatingService` 已加；Service 不走 Activity 的本地化钩子，漏了则悬浮窗永远系统默认语言（中文）
- 语言切换后需重启进程，由 `StarFlowApplication.onCreate` 统一应用
- **禁止硬编码中文**（布局 `android:text`、代码字符串、Toast）——一律进 `values/strings.xml`（英文默认）+ `values-zh/strings.xml`（中文）
- ⚠️ **不要保留 `values-en/`**：它**优先于默认 `values/`** 生效，陈旧副本会让英文模式显示中文且缺新 key（曾因此排查数轮）
- 代码 `setTextXxx()` 覆盖布局文本时也必须 `getString(R.string.x)`（例：`TranslateFragment.setMangaButtonState` 曾写死中文覆盖布局字符串）

### 字体 / 字号（防重叠）
- ⚠️ **`autoSizeTextType` 会覆盖 `textSize`**：加了 autosize 的 TextView 再改 `textSize` **不生效**；要固定字号必须先删 autoSize 属性（设置类布局已移除）
- ⚠️ **「文字压右侧图标」与字号无关**，是布局宽度问题：行标题必须 `layout_width=match_parent`（或 `0dp+weight=1`）+ `layout_marginEnd`（给右侧图标留位）+ `singleLine` + `ellipsize="end"`；`wrap_content` 标题 + 右侧 `layout_alignParentEnd` 图标 = 英文一长必钻到图标下（RelativeLayout 设置行常见）
- 悬浮球/漫画菜单条目（`MenuDialogAdapter`）：固定 **17sp，勿用 autosize**（条目 wrap 宽度会被压到极小/不可见）；带值的项用 `标签\n值` 两行，`dialog_listview` 行高 `wrap_content`
- **UI 同步字体（`ui_apply_custom_font`，收敛在 `utils/FontSync.kt`）三种接线点，新增 UI 按场景选：**
  - `FontSync.install(activity)` —— 必须在 `super.onCreate()` **之前**调（之后 `setFactory2` 抛 IllegalStateException 被空 catch 吞掉→字体静默失效，MangaViewer 曾踩）；BaseActivity/阅读器已前置接入
  - `FontSync.applyToTree(view)` —— Service（悬浮球等无 Factory2 的窗口）inflate 后树遍历套字体
  - `FontSync.apply(tv)` —— 程序化 TextView；适配器列表项 getView 惰性 inflate，Factory2 覆盖不到需在 bind 补
  - 译图结果字体（`Custom_Result_Font`）与 UI 同步字体是**两件事**：渲染恒用结果字体，与开关无关
- **单行标题统一规范 `UiUtils.marqueeTitle()`**：`singleLine + ellipsize=marquee + 无限重复`，未选中=省略号、点选(isSelected)=横向滚动看全标题；多行描述/正文保持完整换行不省略（⚠️ 不要退回 `maxLines=1 + autoSize`——会缩字号+截断，FAQ 就因此返工）

### Preference 设置页统一样式
- `preferenceTheme` 同时挂 `settheme` **和** `Base.Theme.MT`（覆盖不同宿主路径）
- 条目布局 `preference_item_fit.xml`：标题 `weight=1` + `singleLine + ellipsize=end` + 14sp → 杜绝标题压右侧 › 箭头
- ⚠️ `PersonalizationConfig`/`APIConfig` 是 `PreferenceFragmentCompat`——改 `fragment_*.xml` 布局对它们**无效**，必须改 `preference_item_fit.xml`/preferenceTheme

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 双模式截图：MediaProjection（默认）/ AccessibilityService
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）

## 高频踩坑（gotchas）

- **Kotlin 块注释会嵌套**：KDoc 里出现 `/*` 就开一层新注释 —— 例如中文强调写法
  `失败/**不属于这本书**` → 报 `Unclosed comment` + 后面一整片 `Missing '}'`，
  而**行号指在函数中间、完全看不出是注释的锅**。中文强调用「」，别在注释里打出 `/**`。

- **被 `runTest`（虚拟时钟）覆盖的代码里不许 `launch(Dispatchers.IO)`**：真实线程池会把非确定性
  带进单测 —— 实测 `NovelTranslationQueueTest` 从 14/14 绿变成 10 例挂（同一批被多挑一轮）。
  心跳/看门狗协程写 `launch { }` 继承调用方派发器即可（生产里那个 scope 本来就是 `Dispatchers.IO`）。

- **`app:tint` 要求根元素声明 `xmlns:app`**，漏了就是 XML 解析失败（unbound prefix），
  报错落在 `mergeDebugResources` 而**不是** Kotlin 编译；`fragment_novel_shelf.xml` 漏过一次。
  另外 `android:tint` → `app:tint` 要**整文件一把改完**，只改一半 `lintDebug` 仍然红。

- ⚠️ **推默认分支前先 `git rev-list --count origin/master..master`**：本项目本地 master 常年
  领先 origin 很多。Git **无法只推最新那一个提交** —— 分支指针必须带上全部祖先，
  所以「推 master」= 把这批**全部**连同新提交一起公开。**别信任何写在文档里的数字，先数**
  （2026-10-01 这一刻是 **7**；同一天早些时候量到过 **115**，因为中间有人推过 —— 数字会变）。

- ⚠️⚠️ **多个会话/工具开在同一个工作目录上会互相破坏**（2026-10-01 真实事故）：
  当时两个会话都开在 `MoeTranslate-Comics` 这一个目录里（一个以为在 `master`、另一个在
  `codex/pdf`），结果 —— 对方 `git checkout` 把**分支切走**了（我这边无感，因为那一刻两条分支
  指向同一个提交、文件一字节没变）；接着我的 `git commit` **落到了对方的分支上**；再后来对方
  `git add .` 把我**还没提交的改动整批卷进了它的提交**（提交信息里一个字都没提）。
  三次都没报任何错。

  **判据（照做就不会再中招）：**
  - `git branch` **不是目录**。分支只是指针，同一个目录里改的就是同一批磁盘文件 ——
    **换分支不隔离，换目录才隔离**（`git worktree add <路径>`，本仓库 `.claude/worktrees/` 就是这么用的）
  - ⚠️ **提交前必须跑一次 `git branch --show-current`**：会话开头拿到的分支名是**当时**的快照，
    几小时里完全可能被别的进程切走（这次就是）。看到 `[分支名 abc123]` 才发现已经晚了
  - **别在别人正在写的目录里构建/提交**：文件 mtime 一直在动就是有人在写
    （`ls -l --time-style=+%H:%M:%S <file>` 隔 15 秒采两次，变了就是活的）；
    这时构建会红在**别人的半成品**上，而你分不清是不是自己弄红的
  - 真要在一条目录上并跑，**约定好谁负责提交**，别两边都 `git add .`

> **元规则：重构删/改符号名后，`grep` 一遍所有 CLAUDE.md。** 文档里出现过一批「类名/常量名/函数名已经不存在」的条目
> （`DBNetModelFiles`、`incrementalTranslateBubbles`、`MIN_INFO_BITS_HISTORY`、`TranslateBridge`、`OverlayRenderer` 日志 tag…），
> 原因是重构扫了代码没扫文档（模块分册拆出来后更容易漏）。删类、改常量名、迁包之后花十秒
> `grep -rn 旧名 . --include=CLAUDE.md`，比让下一个人照着旧名字找半天便宜得多。

- **取消翻译管线（改动 `translateBubblesBatch` / 取消路径前必读）：**
  - `waitForResult` 用 suspendCancellableCoroutine 等翻译回调；**网络 API 取消后回调永不触发**（`OpenAITranslation.cancelTranslation` 直接 cancel 内部 job）→ 必须有 `isCancelled` 轮询看门狗主动 resume，否则 isProcessing 卡死（曾卡 API_TIMEOUT_MS=35s）
  - **专用异常 `TranslationCancelledException`** 识别用户取消：增量渲染（incrementalPPOcrV5/V6/RTDetr）catch 遇它必须**重抛**，不能当成分批失败返回 false —— 否则回退重跑 OCR（「文字识别中」残留）+ recycled source 二次错误
  - **`contRef.getAndSet(null)` 原子单次 resume**：看门狗 + 回调竞争同一 continuation，双 resume 抛 `Already resumed`；用 `getAndSet` 保证只有一个线程取到 continuation
  - `recognizeBatch` 的 catch 遇 `kotlinx.coroutines.CancellationException` 静默重抛，不当「识别模型异常」显示（取消时第二批 OCR 协程被 cancel 会走到这里）
  - `translationCancelled` 贯穿 `finalizeIncremental` / `renderAndShowMergedOverlay` / 游戏 translateByText/Pic 回调，取消后不写库

- **`Bitmap.createBitmap(src, x, y, w, h)` 是子 bitmap**，共享原图底层数据。原图 `recycle()` 后子 bitmap 失效，再调用 `.copy()` 抛 `Can't copy a recycled bitmap`。**正确顺序：先渲染（产生独立副本），再 try/finally 中 recycle 源 bitmap。**（cache 实时渲染 + 下载修复踩过）

- **`TranslationStatusOverlay` 显示入口与 sticky 语义**：入口有 `show`/`showImmediate`/`showError`/`showSticky`/`update` 五个；⚠️ `showSticky()` **必须 `autoDismiss=true`**（改成 false 就没有消失计时、永久驻留），且 `dismiss()` 只清非 sticky chip（别指望它清掉 sticky）。
- **`TranslationStatusOverlay` 窗口生命周期**：`TYPE_APPLICATION_OVERLAY` 窗口可能被系统移除而 `isShowing` 状态过期（MIUI 屏幕录制切换/窗口策略变化等）。**所有显示路径（`show`/`showImmediate`/`showError`/`update`）都必须确保窗口附着**——复用已有 chip 时也要调 `addToWindowIfNeeded()`；`updateViewLayout` 失败（窗口已脱）要自愈重新 `addView`。曾因 `showImmediate` 的"复用 chip"分支漏调 `addToWindowIfNeeded` 导致翻译过程状态条消失（初始化 `show()` 走 `addChip` 正常），根因是 `d7f4ce7` 重写弹窗时把旧版"每次 `displayMessage` 都确保窗口"的行为拆丢了。**给此组件加/改显示逻辑时，必须保证每个入口都触发窗口附着。**

- **自建 API 明文 http 被拦截（`CLEARTEXT not permitted`）**：Android 9+ 默认禁明文，`network_security_config.xml` 已全局 `cleartextTrafficPermitted="true"` 放行——用户自建本地/局域网 API（如 `http://192.168.x.x:8081`、`http://127.0.0.1:21357`）可连。勿改回 false，否则所有自建 http API 全被拦

- **「使用专用识别模型: rec_en」提示仅属 PP-OCRv5**：`processMangaScreenshot` 里只在 `ocrEngine==PPOcrV5 || detEngine==PP_OCR_V5` 分支才计算 `ppRecLang`；**else 分支必须给 `Pair(null, null)`**。曾用 `Pair(PPOcrV5Engine.getRecLang(sourceLang), null)` 兜底 → ML Kit/V6 引擎下源语言为 en/ko/ru 时也误弹 PP-OCRv5 的「使用专用识别模型」提示（`ppHint` 下载提示已正确限定 PP 分支，仅这条误报）。

- **debug 正常、release 闪退 = R8 混淆 JNI 回调接口**：`LlamaCppStreamCallback` 的 `onToken`/`onPhase` 被 native `GetMethodID` 硬编码查找，release 混淆改名后 ART abort（backtrace 见 `art::FindMethodJNIE` + `ThrowNewExceptionF`）。`proguard-rules.pro` 必须 `-keep` 该接口。凡是"Java 对象传给 native、native 按名反查方法"的回调接口都要 keep（`-keepclasseswithmembernames native <methods>` 只保护 native 方法名，不保护这类回调）

- **`libsentencepiece_train.so` 可安全排除**：NLLB 推理不需要 sentencepiece 训练库。在 `build.gradle` 的 `packaging { jniLibs { excludes += ['**/libsentencepiece_train.so'] } }` 中添加排除规则可节省 ~1.6MB APK 体积。不影响 NLLB 翻译功能。

- **`MangaFloatingService` 前台服务类型（无障碍模式兼容性）**：Android 14+（targetSdk 35）要求 `startForeground()` 的类型与 Manifest 声明的 `foregroundServiceType` 匹配。如果服务声明了 `mediaProjection` 但以无障碍模式启动（无需 MediaProjection 授权），直接 `SecurityException` 崩溃。**修复**：始终以 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 启动，仅在切换到 MediaProjection 模式时通过重新 `startForeground()` 升级到 `MEDIA_PROJECTION`。`FloatingBallService` 若后续添加无障碍截图模式也需同样处理。

- **Android 11+ Scoped Storage**：直接 `File` 写 `/storage/emulated/0/Download/...` 会 EACCES 被拒。下载等需要写入公共目录的场景，用 `MediaStore.Downloads.EXTERNAL_CONTENT_URI` 写入。**SAF `openOutputStream(uri).use { ... .copyTo(out) }` 在某些 Android 版本上会丢数据（zip 显示 0B）**，优先 MediaStore，失败 fallback SAF。

- **`groupMangaEntriesByPHash()` 必须返回所有变体**（不能只返回代表 entry）。否则下载/历史浏览只下载/看到代表那张，多尺寸变体丢失。

- **Manifest 没存声明过的权限，运行时 API 也会失败。** Android 13+ `WRITE_EXTERNAL_STORAGE` 是 legacy 权限，但 `MediaStore.Downloads` 不需要任何运行时权限就能写入。

- **`OverlayRenderer.renderOverlay` 第一个参数是源 bitmap**，函数内部会 `.copy()` 创建独立副本。如果传入的是子 bitmap（来自 `Bitmap.createBitmap(src, ...)`）且原图已 recycle，会崩溃。

- **复制模式按钮在独立 WindowManager 窗口**，没有 Window 系统焦点反馈。需要手动加 `setOnTouchListener` 实现 scale 0.92→1.0 动画（80ms down + 120ms up）。

- **`spinnerVariant` 显示"?"**：新条目 `imagePath=null`，但 `pageCacheMap[entry.id]?.cropRect` 有框选尺寸。**用 cropRect 宽高当 spinner 显示文本**，不要 fallback 到文件头尺寸（那是原图尺寸，不是用户框选的）。

- **`renderCache` bitmap 回收纪律（防静默 recycle 崩溃）**：`MangaViewerActivity.renderCache` 用 `utils/BitmapLruCache`（key = `"${entryId}_${mode.name}_crop_r${rulesFingerprint(context)}"`（译文替换表改了要重渲；且必须以 `"${entryId}_"` 开头，否则 `BitmapLruCache.retainEntries` 认不出 id））。**运行期绝不 recycle**：LRU 淘汰（`removeEldestEntry`）/ `set` 同 key 替换 / `remove` / `retainEntries` 全部只移除 cache 条目，旧 bitmap 交给 GC 回收 native buffer——被移除的 bitmap 可能仍被某个相邻未 detach 的 ViewHolder 的 ImageView 引用，静默 recycle 必触发 `Canvas: trying to use a recycled bitmap` 崩溃。**只有 `clear()`（onDestroy，ViewHolder 已全部释放）才 recycle**。`PageGroupAdapter.onViewDetachedFromWindow` 仅打 log，不做回收。`loadImage` 异步渲染完成后用 `ViewHolder.boundEntryId`/`boundMode` 校验 holder 是否仍属同一 entry+mode，过期则丢弃（`bitmap.recycle()`，此时 bitmap 未上屏，安全）。

- **`ZoomableImageView` 旋转用 imageMatrix 而非 View.rotation**：旋转按钮调 `rotateAndFit90()` 累加 `imgRotation`（0/90/180/270）并用 `imageMatrix` 旋转 + 按旋转后视觉外接尺寸 contain 铺满（消除黑边、不溢出）。**绝不用 `View.rotation`**——旋转后 View bounds 外接矩形变大，被父容器（ViewPager2 的 RecyclerView，默认 clipChildren=true）裁切出黑边。旋转态下 `constrainMatrix` 禁用边界限制（未旋转几何公式不适用，会错误回拉图像到屏外）。三态切换/翻页重绑定触发 `resetRotation()` 还原正常方向（imgRotation=0 + fitCenter）。

- **`updateToggleSegments` 协程必须 cancel**：每次 toggle 启动新协程前 `renderToggleJob?.cancel()`，避免用户快速点击产生并发渲染浪费 CPU。`dismissCacheOverlay` 回收 `currentOriginalBitmap` 前也要 cancel render job，否则协程回到 Main 时 bitmap 已回收 → `IllegalStateException`。

- **缓存命中路径必须检查 bubbleRects**：新数据有 `bubbleRects` → 走实时渲染。旧数据无 `bubbleRects` → 回退加载 `imagePath`（预渲染译文 overlay）。`buildCacheResult` 需根据 `bubbleRects.isNullOrBlank()` 选择加载路径。漏检查会导致旧数据用户看到原图而非译文 overlay。

- **MangaViewerActivity 重翻流程（`performRetranslate`）**：按当前条目**原有框选区域**（读 `pageCacheMap[entry.id]` 的 crop 坐标）原地重 OCR+翻译，调 `refreshCacheInPlace`（保 historyId 不变，替换 sourceText/translatedText/bubbleRects/translatorName）。完成后 `cacheManager.getHistoryById` + `updateInMemoryEntry` 覆盖 `pageGroups` 内存快照（否则 `expandPanel` 显示旧译文），清该 entry 三态 renderCache 后 `notifyItemChanged`，必要时调 `expandPanel` 刷新详细面板。**无「重新框选」对话框**（已删除 `view_manga_retranslate_recrop.xml`）。

- **稀疏 hash 误判合并 bug**：`groupMangaEntriesByPHash` 用 256-bit Hamming 距离相似度（阈值 0.95）。**纯色 / 几乎纯色页面 dHash 4 段几乎全 0**（每段 1-3 bits），两张低纹理页间 distance=2~3 bits → `similarity = 1 - 2/256 = 0.992` 远超阈值 → **错误合并**。**修复**：`MIN_INFO_BITS = 16`（~6.25%，`TranslationCacheUtils.isSparseHash`）守卫，infoBits < 16 的 entry 单独成组。**守卫已经加在两条路径上**（`findCacheExt` 与 `groupMangaEntriesByPHash` 复用同一个 `isSparseHash`，`TranslationCacheManager` 的 `:358` 与 `:913`），阈值也已统一成 0.95。新加 hash 相似度判定时照样要带守卫。详见 [[manga-history-group-sparse-hash]]。

- **pHash 显示格式必须用 `%016X`（完整 64 位）**：MangaViewerActivity 详情面板 `tvTranslationInfo` 显示 pHash 时**不要**用 `entry.pHash and 0xFFFFFFFFL` + `"%08X"`（只显示低 32 位）。曾经修复：`pHash = 0x800000000000`（高 51 位 bit）被显示成 `00000000`，用户看不到真实值。统一用 `String.format("%016X", entry.pHash)` 完整 64-bit hex，与 history 列表 `HistoryMangaAdapter` (`entry.pHash = "%016X"`) 保持一致。

- **DialogPreference（ColorPreferenceCompat 等）不能用 `setOnPreferenceClickListener` 拦截点击**：`DialogPreference` 通过 `PreferenceFragmentCompat.onDisplayPreferenceDialog()` 展示弹窗，普通 click listener 返回 `true` 也无法阻止弹窗。**正确方式**：重写 `onDisplayPreferenceDialog(pref)`，匹配 `pref.key` 后显示自定义弹窗，其余走 `super`。

- **AlertDialog 自定义布局 View 不用 `dialog.findViewById`**：`AlertDialog.Builder.setView(view)` 传入自定义布局后，通过 `dialog.findViewById(R.id.xxx)` 查找子 View 不可靠（可能返回 null，尤其是 dialog 未 show 时）。**正确做法**：inflate 布局后从 `view.findViewById(...)` 直接持有 View 引用，在 `create()` 前后操作该引用。

- **`AlertDialog.setView()` + `window.setLayout()` 按钮被推出屏幕**：`AlertDialog.Builder.setView(view)` 替换内容区域后，再通过 `dialog.window.setLayout(width, fixedHeight)` 约束高度，如果固定高度过小（<55% 屏幕高），底部的确定/取消按钮可能被推出屏幕外。**原因**：固定高度限制的是 content 区域而非 dialog 整体（title + content + button 区总高 > fixedHeight）。**修复**：用 `setMessage()`（内部自动绑定 ScrollView）替代 `setView()` 约束内容高度；或 `setView()` 时高度用 `WRAP_CONTENT`，只限制宽度百分比。

- **`AlertDialog.setMessage()` 公告内容必须 `Html.fromHtml()` 渲染**：`TranslateFragment.showNotificationDialog` 接收 Gist content 用 `android.text.Html.fromHtml(content, FROM_HTML_MODE_LEGACY)` 渲染。**直接 `setMessage` 纯文本会显示字面 `<br>` 标签**（v0.9.2 早期版本踩坑）。Gist content 已约定用 HTML 标记（见「Gist 公告格式」），不要换成纯文本。

- **`OpenAIText.testConnection` 在 `isNew=true` 时 `providerIndex` 越界**：`ManageActivity` 启动「新增自定义 API」fragment 时传 `custom_code = allProviders.size`（越界值，`me/apiconfig/APIConfig.kt:700`）。OpenAIText 接收后 `providerIndex = allProviders.size` 指向数组末尾的下一个位置。`testConnection`（`me/apiconfig/OpenAIText.kt:840`）无条件访问 `allProviders[providerIndex]` → `ArrayIndexOutOfBoundsException` → HTTP 请求根本没发出，弹"测试失败：Index N out of bounds for length N"（N 为当前 provider 数）。修复：`OpenAIText.kt:903` 用 `if (providerIndex in allProviders.indices)` 安全访问，越界时显示 `"custom[new]"`；`setupUserMode()` 在 `isNew=true` 时显式 `switchAutoAppendPath.isChecked = true`。

- **FrameLayout 的 `android:gravity` 不影响子 View 定位**——子 View 不设 `android:layout_gravity="center"` 就默认放左上角。曾导致阅读器「所有选中圆出现在图标右下角」（图标没设、选中圆设了 layout_gravity）。凡 FrameLayout 容器内要居中的子层，一律显式 `layout_gravity="center"`（分段选项、Tab、浮层图标都是）。

- **横向 LinearLayout 里子 View 用 `layout_height="match_parent"`、而父行是 `wrap_content` → 撑出一大片空白**：`getChildMeasureSpec` 把 `match_parent` 解析成**可用高度**（≈屏幕高），行内竖分隔线（`width="1dp"`）必踩 —— 曾让「获取模型列表」弹窗的按钮下方空出一整屏。给父行定死高度（如 `48dp`）。⚠️ 同理 **`android:maxHeight` 不是 View 的属性**，写在 `ScrollView`/`ListView` 上静默失效（`popup_model_selector.xml` 里就有一个无效的 `maxHeight="300dp"`）；要限高只能在代码里按行数算。

- **`ArrayAdapter(ctx, res, textViewId, objects)` 直接持有传入的 list**（源码 `mObjects = objects`，不拷贝）：过滤时 `adapter.clear()` 会**清空源数据本身**，之后列表恒为空 —— 曾表现为模型多选弹窗「始终显示没有匹配的模型」。传 `objects.toMutableList()` 拷贝，且 `getView`/点击一律用 `getItem(position)` + `adapter.count`，不要引用外部变量。

- **`SeekBar.setProgress(k, true)` 第二个参数是 `animate` 不是 `fromUser`**：监听器收到 `fromUser=false`，写 prefs 的分支根本不执行。用它模拟用户拖动会写出「新旧代码都通过、实际什么都没测」的假测试 —— 真实拖动只能 `dispatchTouchEvent`（见 `MangaDebugSlidersTest.dragTo`）。

## OCR-only / 本地翻译高频踩坑

- **OCR-only 状态不是“翻译成功”**：`STATE_OCR`/`PROCESS_OCR` 表示已有原文载荷、没有可用译文；不要用 `translatedText == null` 在各处自行猜状态，必须读显式状态。普通翻译模式遇到该状态要复用 OCR，不得重复 OCR。
- **异步 busy 生命周期**：截屏函数返回不代表翻译结束。`TranslationBusyRegistry.enter()` 必须与 API/本地翻译终端回调、取消、异常配对；否则执行模式可以在旧请求仍运行时切换。
- **本地翻译锁释放纪律**：`LocalTranslationCoordinator.mutex` 的每次 `lock/tryLock` 必须覆盖成功、错误、取消和同步抛异常路径；不能只在成功回调解锁。
- **数据库迁移版本同步**：新增 `HistoryEntity` 字段时同时更新 `TranslationHistoryDatabase.version`、迁移 SQL、旧载荷迁移规则和 Room schema 守卫；根文档中的 DB 版本也要同步。

## UI 规范

- **禁止使用系统原生弹窗和选择器**：所有弹窗、菜单、选择器必须使用应用自身的样式，禁止系统原生样式（白色背景、系统字体）
  - 弹窗：使用 `android.app.AlertDialog` + 自定义布局（⚠️ Material3 主题与 MaterialAlertDialogBuilder 不兼容，会崩溃）
  - 选择列表/选项：使用 `android.app.AlertDialog` + `setItems` / `setSingleChoiceItems` / 自定义 RadioGroup
  - 底部弹窗：使用 `BottomSheetDialogFragment`，禁止系统 `Dialog`
  - 下拉选择器：禁止系统 `Spinner`（白色下拉菜单），使用 Material `MaterialAutoCompleteTextView` + `ExposedDropdownMenu` 或自定义下拉
- **禁止使用系统级窗口**：所有 UI 必须在 Activity/Fragment 内实现，禁止 `TYPE_APPLICATION_OVERLAY` 以外的系统窗口
- 所有 UI 组件优先使用 Material Design 组件库（`com.google.android.material.*`）

## 弹窗系统（选型规则）

**翻译状态类消息 → 统一用 `TranslationStatusOverlay`，不要用系统 Toast。** 它是全局共享单例，最多 3 条消息纵向堆叠（第 2/3 行不重叠），可配置位置（上/中/下）和时长。

| 需求场景 | 用哪个 | 说明 |
|---------|--------|------|
| 翻译状态/初始化/进度/模型切换 | `TranslationStatusOverlay` | 共享单例、多消息堆叠、圆角动画 |
| 一次性操作反馈（保存成功、复制、下载完成） | `UiUtils.showToast` | 底部系统 Toast |
| 需要确认/操作/选择 | `android.app.AlertDialog` | 必须自定义布局，禁止原生样式 |
| 底部面板/菜单 | `BottomSheetDialogFragment` | 禁止系统 `Dialog` |
| 常驻可拖动浮层 | `TranslationResultView` / 悬浮球 | WindowManager |

**消息方法选择（`TranslationStatusOverlay`）：**
- `show(message)`：普通消息，堆叠显示，到时自动消失（初始化信息、启停提示）
- `showImmediate(message, autoDismiss = false)`：**进度/状态**消息，复用顶部第一条，下面堆叠的保留（检测中/翻译中/模型切换）
- `showError(message)`：错误，红色、可点击复制
- `update(message)`：更新顶部文字（进度实时刷新，不重置计时器）
- `dismiss()`：清空全部（进度结束）；`release()`：服务销毁时调用

**代码示例：**
```kotlin
// 1. 翻译状态浮层（共享单例）
val overlay = TranslationStatusOverlay.getInstance(this)
overlay.show("初始化中...")                                  // 第 1 行
overlay.show("翻译 API 初始化成功")                          // 第 2 行（堆叠）
overlay.showImmediate("检测中...", autoDismiss = false)       // 进度，复用顶部
overlay.showError("翻译失败: ${e.message}")                   // 红色可复制
overlay.dismiss()                                            // 清空

// 2. 一次性操作反馈（底部系统 Toast）
UiUtils.showToast(context, "已保存", isShort = true)

// 3. 确认/操作弹窗（AlertDialog + 自定义布局）
AlertDialog.Builder(requireContext())
    .setTitle(R.string.xxx)
    .setMessage(R.string.xxx)
    .setPositiveButton(R.string.confirm) { _, _ -> /* 操作 */ }
    .setNegativeButton(R.string.user_cancel, null)
    .show()
```

**个性化配置键**（`CustomPreference`）：`status_overlay_enabled`（总开关）、`Status_Position`（top/center/bottom）、`Status_Duration`（毫秒，默认 2000）。

⚠️ 详细设计与消息堆叠行为见本地参考文档 `tools/popup-system.md`（gitignore，不提交）。

## 网络配置

- GitHub/HuggingFace 下载需代理（国内环境）
- CLI 工具（gh/curl/npm）需设置 `http_proxy`/`https_proxy` 环境变量，或开启 TUN 模式
- Windows hosts 文件可能有 `#S302` 条目将 github.com 指向 127.0.0.1，需清理
- git 代理配置：`git config --global http.proxy http://127.0.0.1:7897`

**两种外网方案（用户随时切换，GitHub 不通时主动轮流试）：**
- **Clash TUN/代理**：本地 `127.0.0.1:7897`（HTTP/SOCKS），Clash Verge 提供
- **Cloudflare WARP**：虚拟网卡接管系统路由，无需配置代理——`unset` 所有 proxy env 后
  curl/gh/git 直接连公网 IP（如 api.github.com 的 `140.82.116.6`）即走 WARP 隧道
- ⚠️ **切换关键**：Claude Code `settings.json` 硬编码 `HTTPS_PROXY/HTTP_PROXY/ALL_PROXY=127.0.0.1:7897`
  注入所有子进程。Clash 失效时这些 env 会让 gh/curl/git 全卡 000——`unset` 后走 WARP 即通。
  每次换路用 `curl -s -o /dev/null -w "%{http_code}" https://api.github.com` 验证，200 再继续
- ⚠️ **下载速度低于 200 KB/s 且累计超 10 分钟 → 立刻停下向用户报告 + 自己换路**（大文件用
  `curl -C -` 断点续传 + 后台跑，不要前台 `sleep` 干等）。完整规则见全局记忆 `~/.claude/CLAUDE.md`

## 检查更新

`UpdateChecker.kt` 调用 GitHub Releases API 检查新版本，对比 versionCode（如 `v0.0.2` → `2`），有新版本时弹窗提供三种下载方式：
- **直接下载**：从 Release assets 解析 APK 下载链接，app 内直接下载安装
- **百度网盘**：从 Release body 解析百度网盘链接（包含"百度网盘"和"http"的行）
- **夸克网盘**：从 Release body 解析夸克网盘链接（包含"夸克网盘"和"http"的行）

### Release notes 格式规范

Release notes 中必须包含以下信息，否则 app 内检查更新功能无法正常工作：

```
**下载说明**：
- 百度网盘：https://pan.baidu.com/s/xxx?pwd=xxx
- 夸克网盘：https://pan.quark.cn/s/xxx（暂无则写"暂无"）
```

创建 Release 时必须上传 APK 文件到 assets：
```bash
gh release create vX.X.X app/build/outputs/apk/release/app-release.apk --title "vX.X.X" --notes "..."
```

## 日志监控

### 正确的日志监控方法

**⚠️ 不要手动查询日志，使用后台监控！**

```powershell
# 正确：后台持续监控
$adb = "C:\Users\xjj20\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c  # 先清空旧日志
$pid = & $adb shell pidof com.moe.starflow
& $adb logcat --pid=$pid | Select-String -Pattern "关键词" | Out-File -FilePath "C:\Users\xjj20\Desktop\app_log.txt" -Encoding UTF8
```

用 `run_in_background: true` 执行，让 app 运行后再查看日志文件。

### 获取闪退（crash）详细信息

**闪退时主 logcat 可能看不到栈，必须读崩溃专用缓冲区：**

```bash
# 最可靠：崩溃缓冲区（含 tombstone / backtrace / 信号 / 寄存器）
adb logcat -d -b crash
# 只看本 app 相关：grep 包名或崩溃特征
adb logcat -d -b crash | grep -A40 "com.moe.starflow"
```

主缓冲区搜索兜底（`-d` 只读转储，不阻塞）：
```bash
adb logcat -d | grep -E "FATAL|Abort message|SIGABRT|libllamacpp|AndroidRuntime|tombstone"
# 指定 pid（app 崩溃后 pid 失效，需要崩溃前抓；崩溃后搜进程名即可）
adb logcat -d --pid=<pid>
```

**读 backtrace 的要点：**
1. **Abort message / signal** 是根因入口（如 `std::length_error: basic_string`、`CheckJNI::NewStringUTF` abort、`SIGSEGV`）
2. **栈帧里的 `.so` 和函数名**定位代码：`base.apk!libllamacpp.so (Java_translationapi_llamacpp_LlamaCppNative_nativeTranslate+516)` 说明崩在桥的哪个函数；`DEBUG: backtrace:` 下从 #00 往上读
3. **Java 层崩溃**（`FATAL EXCEPTION`）在 main 缓冲区，栈在 `AndroidRuntime` tag 下

**核对崩溃是否来自当前构建（重要，避免被旧日志误导）：**
```bash
# 崩溃日志里的 .so BuildId 形如 8c851a1e...，与当前构建比对：
readelf -n app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libllamacpp.so | grep -i "build id"
# BuildId 不一致 = 崩溃日志是旧构建的，不是当前代码的问题
```

⚠️ 剪贴板里的"崩溃内容"（`adb shell dumpsys clipboard`）**不可靠**（Android 13+ 常返回空），优先用 `-b crash` 缓冲区。

### 常见错误

1. **PID 过期** — app 被 force-stop 后重启会获得新 PID，旧 PID 查不到日志
2. **语法混用** — Bash 工具用 Unix 语法，PowerShell 工具用 PS 语法，不要混用
3. **没有清空** — 旧日志会干扰，先 `logcat -c` 清空

**常用过滤：**
- 翻译相关：`Select-String "MangaFloating|OpenAITrans|翻译配置|翻译结果|上下文"`
- 错误：`Select-String "Error|Exception|FAILED|失败"`
- 全部：直接 `Get-Content`

## 通知系统

### 架构

- `NotificationChecker.kt` — 从 Gist 获取公告 JSON
- `UpdateChecker.kt` — 从 GitHub Releases API 检查更新
- 两者独立，都在 `TranslateFragment` 中调用

### Gist 公告格式

Gist 内容可能包含非 JSON 前缀（如 "星译公告"），解析时需提取 JSON 部分：
```kotlin
val jsonStart = body.indexOf('{')
if (jsonStart < 0) return NotificationResult.Error
val json = JSONObject(body.substring(jsonStart))
```

**单文件要求**：Gist 只能保留一个名为 `gist_v086_final.txt` 的文件。`NotificationChecker` 通过 `raw` URL 拉取（按文件名字母序返回第一个），多余文件（如 `gist_content.txt`）会让 raw URL 返回旧版本内容（v0.9.2 发布时踩坑）。

**Content 用 HTML 标记**：`content` 字段支持 `<b>...</b>`、`<br>`、`<ul><li>` 等简单标记，app 用 `Html.fromHtml(content, FROM_HTML_MODE_LEGACY)` 渲染。**不要用 Markdown `**bold**`**——app 不渲染星号。`\n` 在 JSON 字符串里用字面 `\\n`（JSONParser 解析为真换行），不要用真实换行（破坏 JSON 结构）。

### Android 13+ 通知权限

`POST_NOTIFICATIONS` 权限需运行时请求，已在 `MainActivity.onCreate()` 中添加。

## 调试面板架构

### FrameLayout 触摸事件

- `OnTouchListener` 返回 `true` 会吞掉所有触摸事件，子 View 收不到点击
- 正确做法：用 `setOnClickListener`，或在 container 上用坐标判断

### 折叠/展开机制

- `debugInfoPanelView` — 整个 container（imageView + infoPanel + toggleButton）
- `debugInfoPanelContentView` — 仅 infoPanel（可折叠部分）
- 折叠时只隐藏 infoPanel，不动 container

## Skill routing

当用户请求匹配可用 skill 时，通过 Skill 工具调用。不确定时也调用。

关键路由规则：
- 发布版本 → 调用 `/release-replace`
- 审查代码变更 → 调用 `/review`
- Bug/错误排查 → 先查日志，定位代码后修复
- UI 布局问题 → 直接对比代码找差异，不要求看日志
- 构建/安装 → 使用构建命令，安装前不卸载

## .claude/ 目录

```
.claude/
├── skills/                   # 项目级 skill（如 release-replace）
│   └── release-replace/      # 版本发布管理
├── plans/                    # 实现计划草稿（gitignored）
└── worktrees/                # git worktree 隔离工作区（自动清理）
```

## 常见问题排查

### 构建失败

```powershell
# 检查 local.properties 是否存在
Test-Path local.properties
# 内容应为：sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk
```

### 安装失败

**绝对禁止未经确认执行 `adb uninstall`。** 常见原因：
- **签名不一致**：debug/release 签名不同，需先卸载旧版本（用户确认后）
- **INSTALL_FAILED_UPDATE_INCOMPATIBLE**：同上
- **设备空间不足**：清理设备存储

### 日志无输出
- PID 过期：app 重启后 PID 变化，需重新获取
- 日志 tag 未匹配：检查 `LogCollector` tag 是否在过滤列表中
