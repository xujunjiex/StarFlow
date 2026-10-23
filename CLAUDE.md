# StarFlow 项目开发指南

> 范围：本文记录 StarFlow 的跨模块架构、构建流程与共享实现约束。通用协作和仓库规则见 [AGENTS.md](AGENTS.md)；Claude Code 通过以下导入读取这些规则。

@AGENTS.md

## 项目概述

星译（StarFlow）— Android 翻译应用，支持 Android 10+（API 29+）。四大功能块：游戏/视频翻译（截图 OCR + 翻译 API）、漫画翻译（气泡检测 + OCR + 翻译 + 竖排渲染）、漫画书架与阅读器（导入 zip/目录/rar/7z 即读，含章节导航，翻译在阅读器内完成）、小说导入与阅读（txt/epub/zip-html/文件夹，左右/上下/滚动三种阅读模式 + 按批翻译 + 导出译文/原文/双语）。

## 模块分册索引（细节按目录下沉）

根文件只留**跨模块**的东西（命令 / 架构 / 硬约束 / 高频踩坑）。下面这些细节各自在模块目录里，
Claude 只在动那个目录时才读：

| 模块 | 分册 |
|---|---|
| 漫画渲染 / 引擎 / 管线 / 状态机 / 自动翻页 | `app/src/main/java/com/moe/starflow/manga/CLAUDE.md` |
| 漫画导入书架 + 阅读器 | `app/src/main/java/com/moe/starflow/mangaimport/CLAUDE.md` |
| 书库会话、File/SAF 存储与目录持久化 | `app/src/main/java/com/moe/starflow/library/CLAUDE.md` |
| 小说导入 + 阅读器 + 翻译 | `app/src/main/java/com/moe/starflow/novel/CLAUDE.md` |
| 翻译服务 / 悬浮球 / 截图链路 | `app/src/main/java/com/moe/starflow/translate/CLAUDE.md` |
| 数据、缓存、历史、DB | `app/src/main/java/com/moe/starflow/data/CLAUDE.md` |
| 模型下载与模型管理 | `app/src/main/java/com/moe/starflow/download/CLAUDE.md` |
| 图片超分（引擎 / 大图压缩 / 逐页按钮 / 记录系统 + 超分本章） | `app/src/main/java/com/moe/starflow/sr/CLAUDE.md` |

这些分册随仓库维护，clone 下来即可使用。

## 目录与远端边界

远端与本地文件边界、模型备份位置和禁止提交的内容统一见 [AGENTS.md](AGENTS.md)。用户指南写在 [README.md](README.md)，本机设计记录放在 Git 忽略的 `docs/`。

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
1. 创建 `local.properties`（已 gitignore），将 `sdk.dir` 设为本机 Android SDK 安装目录；该文件不要提交。
2. 如果网络环境需要代理，在本机 Git 配置中填写可用的代理地址；不要把个人代理端点写进项目文档。
3. 如果 GitHub 域名无法解析或连接，检查本机 hosts 和代理配置，确保没有错误的本地重定向。
4. **准备原生依赖源码**：运行 `pwsh sr/setup-ncnn.ps1` 和 `pwsh llamacpp/setup-submodule.ps1`。
   `llama.cpp` 在仓库中以固定提交的 gitlink 记录；项目脚本在该路径准备独立的稀疏检出，并启用长路径。
   使用脚本初始化；不要先运行 `git submodule update`，否则会生成与脚本预期不同的 `.git` 布局。
5. `./gradlew assembleDebug` 验证构建

## 构建与验证

构建使用 Gradle Wrapper，复用 `gradle.properties` 已启用的 daemon、构建缓存与增量编译。日常不加 `clean`、`--rerun-tasks`、`--no-daemon`；只有确认缓存或进程故障时才针对处理。Windows 从 PowerShell 调用 Wrapper；先核对实际错误，不把历史 Git Bash 的 PATH 问题当作每次重启 JVM 的理由。

```powershell
# 需要安装包时才打包；不附带单元测试或完整 lint
.\gradlew.bat :app:assembleDebug

# 用户授权安装后，用安全脚本覆盖更新；多设备时必须指定目标
python guard/install_safe.py app/build/outputs/apk/debug/app-debug.apk -s <serial> --confirm
```

可安装构建需要既有共享签名配置，见 [安装与数据保护](guard/README.md)。原生依赖准备见上一节；不要通过卸载、清数据或临时更换签名处理安装失败。

### 日常验证范围

- 文档、文案、局部样式：核对变更，不自动启动 Gradle 测试或完整 lint。
- 行为修复：有必要且用户未要求跳过时，默认选 1–3 个直接覆盖失败场景的行为测试。明确它能验证什么；JVM 模拟不代表真机推理或视觉效果已经验证。
- 新增双语字符串：核对 `values/` 与 `values-zh/` 对应 key 和格式参数，不因为改一条文案就启动完整 lint。`MissingTranslation` 的发布构建门槛保留。
- 完整测试套件仅在用户明确要求时运行；完整 lint 和源码守卫为按需显式选项。检查已通过且没有新变更或新风险时，不追加重复检查。用户明确要求不测试时，不运行这些检查。

### 精确选择测试

普通 `--tests` 只过滤运行阶段，仍编译整个测试目录。使用 `testFocused` 和 `testClasses` 可同时裁剪 Kotlin/kapt 编译输入，保留共享夹具；无关测试的编译错误不会阻塞该选集。

```powershell
# 只查看计划与实际编译输入；不会编译或执行测试
.\gradlew.bat :app:verificationPlan '-PtestClasses=com.moe.starflow.manga.OcrCancellationLifecycleTest'

# 确实需要时，只编译并运行指定类；多个完整类名以逗号分隔，不接受包通配符
.\gradlew.bat :app:testFocused '-PtestClasses=com.moe.starflow.manga.OcrCancellationLifecycleTest,com.moe.starflow.translate.LocalTranslationLifecycleTest'

# 以下仅在明确需要相应范围时使用
.\gradlew.bat :app:testSourceGuards
.\gradlew.bat :app:testAllUnit
.\gradlew.bat :app:lintDebug
```

`gradle/source-guards.txt` 只列纯源码文本断言；它们在普通单元测试中默认不编译、不运行。混合行为与源码检查的测试不因名称带 Guard/Wiring 就被排除。旧守卫失败应先判断是否只是格式或实现重构，不要为迁就字符串修改正确的产品代码，也不要顺势扩大全部回归。

测试入口与限制见 [验证配置](gradle/README.md)。Robolectric 使用 `app/src/test/resources/robolectric.properties` 的 SDK 34；设备测试仍遵循 [AGENTS.md](AGENTS.md) 的授权与数据保护规则。

## 架构

StarFlow 是 Kotlin Android 应用，界面使用 Android Views 和 ViewBinding，导航使用 AndroidX Navigation Component。主要功能包：

- `manga/` — 漫画 OCR、检测、翻译和覆盖层渲染。
- `translate/` — 游戏翻译、截图链路和共享批量翻译设施。
- `mangaimport/` — 漫画书架与阅读器，含页翻译和超分。
- `novel/` — 小说导入、阅读和段落翻译。
- `data/` — Room 数据库、翻译历史和缓存。
- `download/` — 模型清单、下载流程和模型管理界面。
- `sr/` — 图片超分处理与 ncnn 原生集成。
- `chat/`、`ui/`、`me/`、`launch/`、`utils/` — 其他界面和共享服务。

模块实现细节见本文开头的模块索引。根文档只保留跨模块的约定。

### 跨模块执行与状态

- `LanguageManager` 创建语言 Context 时只能覆盖 locale；不能把完整 `Configuration` 快照传给 `createConfigurationContext`，否则方向、窗口尺寸和密度会被持续覆盖，转屏与分屏无法更新。
- 启动页、首次引导和主页共用 `StartupOrientation`：手机保留竖屏，最小宽度 600dp 及以上跟随系统方向。启动页重建取消延迟跳转属于正常生命周期，不显示初始化错误。

- `translate/batch/` 是漫画和小说阅读器共用的章节任务调度器；具体 UI 与数据持久化由各模块宿主负责。
- `OcrLock` 串行保护原生 OCR 与超分引擎。`TranslationBusyRegistry` 和 `TranslationJobRegistry` 管理不同生命周期的任务，修改前查阅对应模块说明。
- `TranslationExecutionMode` 控制本地 OCR/文本翻译流程的翻译或仅 OCR 模式；图片直传翻译不走这条路径。切换模式不能与正在运行的 OCR/翻译任务竞争。
- `LocalTranslationCoordinator` 串行化 NLLB/LlamaCpp 本地翻译；远端 API 不使用这把锁。
- Room schema 版本以 `TranslationHistoryDatabase` 为准。新增实体字段时同步更新迁移和 schema 检查；迁移约束见 data 模块说明。

### 原生模块

- `:app` 包含 Android 应用和剩余的 app 本地原生代码。
- `:llamacpp` 构建固定版本的上游 llama.cpp 检出。使用 `llamacpp/setup-submodule.ps1` 准备源码；不要对此目录运行 `git submodule update --init`。
- `:sr` 构建基于 ncnn 的超分引擎。使用 `sr/setup-ncnn.ps1` 准备被忽略的 ncnn 源码目录。
- `patches/0001-ggml-stq1_0-1.25bit.patch` 是兼容备用补丁，不参与常规构建。当前固定的 llama.cpp 版本已支持 STQ1_0；只有更换版本且上游不再包含该支持时，才重新评估补丁是否适用。
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
- 用户获取：关于页 → 查看日志 → 导出。`LogExporter` 在后台把点击时的 `starflow.log` 快照写入下载目录的 `StarFlow/`；文件不可读时记录原因并改用当前内存日志。创建、写入或发布失败均通过 `LogCollector` 保存完整异常、阶段和目标位置，并清理未完成的导出；日志窗口即时刷新错误，复制读取最新内容。
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
tag:OCRBridge | tag:DetectionBridge | tag:BubbleDetector | tag:MangaFloatingService | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer | tag:TranslationCacheManager | tag:AutoTranslateEngine | tag:FloatingBallService | tag:GameOcrEngine | tag:Screenshot | tag:Shooter | tag:OpenAITranslation | tag:TranslateUtils | tag:ExitReason

阅读器内嵌翻译（导入功能）日志 tag：`ReaderTranslate`（控制器：逐气泡 rect/原文/译文/来源、缓存命中统计、翻译开始时的引擎与语言）、`IncrementalBatch`（分批管线：`translateWithCache` 命中数）。
```

## 手机安装与数据保护

设备操作授权和仓库内的通用安全边界见 [AGENTS.md](AGENTS.md)；安装器、签名与备份流程见 [guard/README.md](guard/README.md)。

## 双模式截图

> 本节已下沉到模块分册：`app/src/main/java/com/moe/starflow/translate/CLAUDE.md`
> 动那一块之前先读它；跨模块的约定仍以本文件为准。

## 框选与结果浮层坐标

截图、OCR 与漫画结果使用物理像素。不能用补偿偏移或拉伸图片来掩盖不同坐标空间。

### 显示几何与截图

- 显示几何统一由 `utils/DisplaySize.kt` 提供。Android 11+ 使用独立 display 窗口 Context 的整屏边界，不让已移除框选窗的历史尺寸覆盖它；Android 10 保留带时刻的窗口／朝向兼容解析。
- `ScreenshotGeometry.kt` 的 `DisplayGeometry` 包含宽、高、真实旋转角度和密度。180° 翻转也必须使旧帧失效，不能只比较横屏／竖屏或宽高。
- `Shooter` 按完整显示几何维护 ImageReader；几何变更后清空旧帧，API 34+ 同时核对投影实际宽高。尺寸调整失败不返回旧缓冲帧。
- 截图请求使用全局唯一编号，游戏与漫画分开投递。异步结果只能交给对应服务的当前请求，不能重新解释为新的框选任务。

### 框选与上屏

- 每帧绑定不可变 `ScreenshotMapping`，保存截图时的实际整数裁剪边界。裁剪和窗口宽高必须由同一组左右／上下边界相减得到。
- 越界、退化裁剪拒绝处理；不夹小位图后拉回原框，也不把裁剪失败的全屏图放进框选窗口。
- 漫画普通结果、缓存回显、原文／译文切换、复制气泡均使用该帧映射。不能在翻译结束时读取可变的当前 `cropRect` 重新定位。
- 框选与结果窗口共用 `OverlayWindow.fullDisplayParams`：整屏由 WMS 布局，裁剪窗口再指定映射矩形；API 30+ 不按系统栏内缩。挖孔模式 API 30+ 用 ALWAYS，API 29 用 SHORT_EDGES。
- `OverlayWindow.setBitmapInPixels` 清除截图密度并使用 CENTER，保持物理像素 1:1；不使用 FIT_XY。
- `AlignedResultGuard` 绘制前校验完整显示几何、窗口宽高和屏幕原点。不匹配则拒绝绘制并撤下结果；`DisplayGeometryMonitor` 同时监听旋转、分辨率和密度变化，取消旧任务并清除旧框选。
- 截图的旧坐标缓存仅在源像素和裁剪边界都完全相同时复用；pHash 相同不足以证明位置相同。否则重新检测，文本译文缓存仍可使用。

### 生命周期与提示浮层

结果切换的后台渲染持有独立原图副本；结束后校验仍是原窗口和映射，避免转屏／关闭后把旧图贴进新窗口。停止、转屏及服务重建都会作废待返回的截图请求。

`TranslationStatusOverlay` 是独立提示窗口。阅读器传入位置时仍用 `getLocationOnScreen` 的屏幕坐标；窗口系统栏内缩由其校准方法实测，不按系统栏高度猜测。

针对性回归见 `ScreenshotGeometryTest`、`AlignedResultGuardTest` 和 `ShooterGeometryTest`。这些测试验证坐标契约与生命周期，不代表所有厂商窗口管理器已实机验证。

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
   - 例：`game_context_enabled` / `ctx_token_budget` → 由 `FloatingBallService` 的偏好监听更新 `contextEnabled` / `contextTokenBudget`

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

- ⚠️⚠️ **改完文件先看 `git show --stat` 有没有"整文件重写"**（2026-10-04 踩到）：
  本仓库的工作区里**大量文件是 CRLF、而索引里存的是 LF**（`git ls-files --eol` 会显示
  `i/lf w/crlf`，998 个文件如此），因为 git 靠 stat 缓存判断"没改过"，`git status` 一声不吭。
  **一旦某个文件被重新写盘（编辑器/AI 工具整文件写回），它的字节就会被原样存进索引 —— CRLF 也存**，
  于是那个文件的 diff 变成"整文件重写"（实测 `NovelImporter.kt` 540 行 CRLF → 919 行改动、
  `NovelShelfFragment.kt` 473 行 → 945 行改动），真正的改动被埋在里面**完全 review 不动**。
  两条处置：
  1. 提交前扫一眼 `git show --stat HEAD`：某文件改动行数 ≈ 全文件行数 = 中招了；
  2. 归一化再 `commit --amend`：
     ```powershell
     $p = (Resolve-Path <file>).Path
     $t = [System.IO.File]::ReadAllText($p).Replace("`r`n", "`n")
     [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
     ```
  ⚠️ 别用 `Get-Content`/`Set-Content` 做这件事（会把中文读成 mojibake，见下一条）。
  ⚠️ 也别想着"反正 git 会归一化"：本仓库 `core.autocrlf=false` + `core.eol=lf`，
  **git 不会替你把 CRLF 转成 LF**（`.gitattributes` 只对 `novel-fixtures/**` 标了 `-text`）。

- **读文件一律用 `read` 工具，不要用 `Get-Content`**（2026-10-04 踩到）：无 BOM 的 UTF-8 文件会被
  PowerShell 5.1 按系统 ANSI（GBK）解码 —— 中文全变 mojibake（`灏忚鏂囦欢鏍煎紡`），
  看着像文件坏了、其实只是读错了。要按字节核对（换行/BOM）就用
  `[System.IO.File]::ReadAllBytes()`。

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

- GitHub、Hugging Face 等站点的可达性取决于本机网络环境。按需在本机配置代理或 VPN。
- 排查连接失败时检查 Git 配置、`HTTP_PROXY`/`HTTPS_PROXY` 环境变量和 hosts 文件；不要在共享文档里记录具体代理端点或个人网络拓扑。
- 可用 `curl -I https://api.github.com` 检查 HTTPS 连通性；下载中断时使用支持断点续传的客户端。

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
# 后台持续监控；先确认已获准操作设备
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$logPath = Join-Path $env:TEMP 'starflow-app.log'
$appPid = & $adb shell pidof com.moe.starflow
& $adb logcat --pid=$appPid | Select-String -Pattern "关键词" | Out-File -FilePath $logPath -Encoding UTF8
```

用后台进程持续监控，将日志保存到 `$logPath`。不要先清空设备日志。

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
# 确认 sdk.dir 指向本机 Android SDK 安装目录
```

### 安装失败

**不要通过卸载解决安装错误。** 常见原因：
- **签名不一致**：使用与设备上现有应用匹配的 keystore；无法确认签名时停止安装
- **INSTALL_FAILED_UPDATE_INCOMPATIBLE**：同上
- **设备空间不足**：清理设备存储

### 日志无输出
- PID 过期：app 重启后 PID 变化，需重新获取
- 日志 tag 未匹配：检查 `LogCollector` tag 是否在过滤列表中
