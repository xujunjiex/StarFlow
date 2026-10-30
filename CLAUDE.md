# StarFlow 项目开发指南

本文记录跨模块架构、构建入口与共享实现约束。任务范围、验证策略、设备授权及仓库边界统一见 [协作规则](AGENTS.md)。源码和测试是当前实现的事实来源。

@AGENTS.md

## 项目与模块

星译（StarFlow）是 Android 10+ 的翻译应用，支持游戏／视频截图翻译、实时漫画翻译、漫画导入阅读和小说导入阅读。界面使用 Android Views、ViewBinding 与 AndroidX Navigation Component。

Gradle 包含 `:app`、`:sr` 和 `:llamacpp`；业务按功能包组织。修改功能前阅读对应指南，跨包调用先核对已有入口。

| 功能 | 指南 |
|---|---|
| 漫画检测、OCR、管线与渲染 | [漫画模块](app/src/main/java/com/moe/starflow/manga/CLAUDE.md) |
| 漫画导入、阅读器与处理任务 | [漫画阅读器](app/src/main/java/com/moe/starflow/mangaimport/CLAUDE.md) |
| 书库身份、File／SAF 存储与迁移 | [书库存储](app/src/main/java/com/moe/starflow/library/CLAUDE.md) |
| 小说导入、分页、阅读与翻译 | [小说模块](app/src/main/java/com/moe/starflow/novel/CLAUDE.md) |
| 翻译服务、截图、提示与共享章节任务 | [翻译模块](app/src/main/java/com/moe/starflow/translate/CLAUDE.md) |
| 数据库、缓存与历史 | [数据模块](app/src/main/java/com/moe/starflow/data/CLAUDE.md) |
| 模型清单、下载与管理 | [下载模块](app/src/main/java/com/moe/starflow/download/CLAUDE.md) |
| 图片超分与原生引擎 | [超分模块](app/src/main/java/com/moe/starflow/sr/CLAUDE.md) |

用户可见说明维护在 [README.md](README.md)；本地设计记录保存在 Git 忽略的 `docs/`，不作为实现契约。

## 环境与构建

使用 JDK 17、compileSdk／targetSdk 35、minSdk 29、NDK 25.2.9519653、CMake 3.22.1；仅支持 `arm64-v8a`。以 Gradle 配置为准。

首次克隆后准备本地配置和原生依赖：

1. 在被忽略的 `local.properties` 配置本机 `sdk.dir`，路径使用正斜杠。
2. 运行 `pwsh sr/setup-ncnn.ps1` 准备被忽略的 ncnn 源码。
3. 运行 `pwsh llamacpp/setup-submodule.ps1` 准备固定提交的 llama.cpp 稀疏检出。脚本使用独立 Git 布局，不先运行 `git submodule update`。
4. 需要安装包时使用 Gradle Wrapper；签名沿用 [安全安装说明](guard/README.md) 中的共享配置。

```powershell
.\gradlew.bat :app:assembleDebug
```

Gradle 已启用 daemon、缓存和增量构建。测试范围与设备操作遵循 [协作规则](AGENTS.md)，聚焦测试入口见 [按需验证](gradle/README.md)。

```powershell
.\gradlew.bat :app:verificationPlan '-PtestClasses=com.moe.starflow.manga.OcrCancellationLifecycleTest'
.\gradlew.bat :app:testFocused '-PtestClasses=com.moe.starflow.manga.OcrCancellationLifecycleTest'
```

`verificationPlan` 不编译或执行测试；`testFocused` 同时裁剪测试编译输入和运行选集。Robolectric 使用 `app/src/test/resources/robolectric.properties` 中的 SDK 配置，不能替代原生推理和视觉验证。

`patches/0001-ggml-stq1_0-1.25bit.patch` 为兼容备用补丁，不参与常规构建；当前固定的 llama.cpp 已包含 STQ1_0 支持。更换上游版本时再核对补丁、ABI 和模型兼容性。

## 跨模块执行与生命周期

- `translate/batch/ChapterJobRunner` 是漫画、小说共用的章节调度入口；宿主负责数据读写和 UI，`TranslationJobRegistry` 的快照是共享前台服务与通知的数据源。
- 阅读器页面任务随前台状态暂停或取消；章节任务由应用级宿主持有，UI 解绑后继续。宿主只有在无 UI、无活动及收尾任务时才能回收，删除书籍先等待任务退出。
- 书库运行时身份包含位置、作品类型和 UUID，不能依赖数字 ID 或标题。捕获的 `LibrarySession` 固定数据库与后端，迟到任务仍需校验身份、代次和删除状态，详见 [书库存储](app/src/main/java/com/moe/starflow/library/CLAUDE.md)。
- `OcrLock` 保护原生 OCR 与超分；调用实际退出前保持互斥。取消协程不等于原生已经停止，不能提前释放锁。获取时登记执行 Job、来源和心跳。
- `LocalTranslationCoordinator` 串行化本地 NLLB／llama.cpp 文本翻译；远端 API 不使用这把锁。成功、错误、取消和同步异常都必须释放本次持有资源。
- `TranslationExecutionMode` 控制三种全局执行模式：文本翻译、仅 OCR、图片直传。图片直传把截图／阅读器页图交给百度、腾讯云或自定义图片接口，不获取 `OcrLock`、不加载本地 OCR；接口带坐标时画回原图，仅返回文本时走文本面板。文本翻译、仅 OCR 和小说正文始终走原文本链路，`TranslateTools` 文本调用方显式传 `forImage = false`。`TranslationBusyRegistry` 的登记覆盖实际异步请求，而不只覆盖截屏函数调用。
- OCR 已完成与译文可用使用显式状态区分。普通翻译复用有效 OCR；不能用 `translatedText == null` 猜测处理状态。
- 修改 Room 实体同步核对版本、迁移和旧载荷兼容。全局历史库与位置隔离的活动书库职责不同，见 [数据模块](app/src/main/java/com/moe/starflow/data/CLAUDE.md)。
- 本次新解码、尚未交付的位图由执行方释放；缓存或 View 仍可能持有的位图不能在运行期淘汰时主动回收。取消与异常路径同样遵守资源所有权。

### 截图与显示几何

- `utils/DisplaySize.kt` 提供显示几何；`ScreenshotGeometry.kt` 记录宽高、真实旋转角度和密度。180° 翻转也要作废旧帧，不能只看横竖屏或宽高。
- 每次请求携带全局唯一编号，游戏和漫画分别投递。每帧绑定不可变 `ScreenshotMapping`，裁剪图、窗口与复制气泡使用同一组实际整数边界。
- 越界、退化裁剪和过期结果直接拒绝；不能通过补偿偏移、拉伸或裁剪失败后回退全屏掩盖坐标错误。
- 框选与结果共用 `OverlayWindow.fullDisplayParams`，图片清除密度并以物理像素 1:1 显示。`AlignedResultGuard` 绘制前核对几何、窗口尺寸和原点；显示变化时取消旧任务并撤下结果。
- 坐标缓存复用同时要求源像素与裁剪边界一致，pHash 相同不足以证明位置相同。底层截图和窗口约束见 [翻译模块](app/src/main/java/com/moe/starflow/translate/CLAUDE.md)，结果生命周期见 [漫画模块](app/src/main/java/com/moe/starflow/manga/CLAUDE.md)。

## 设置、主题与界面

### 设置与国际化

- 跨文件共享偏好键使用共享常量。漫画字号／颜色等键保持既有 PascalCase；SR／Anime4K 使用默认偏好文件，阅读器自身模式使用 `manga_reader`，不可混传。
- 新设置明确何时生效：每次调用重读、监听后刷新缓存，或运行中拒绝修改。字段只在启动时读取时，必须检查对应监听器与缓存失效路径。
- `LanguageManager` 创建语言 Context 仅覆盖 locale，不能复制完整 `Configuration`，否则方向、尺寸和密度会被冻结。Activity 与 Service 均接入语言 Context；切换语言后按现有流程重启。
- 产品文案进入 `values/strings.xml`（英文）和 `values-zh/strings.xml`（中文），同步 key 与格式参数；不保留会覆盖默认值的陈旧 `values-en/` 副本。
- 启动页、首次引导和主页共用 `StartupOrientation`；手机竖屏，最小宽度 600dp 及以上跟随系统。重建引起的延迟跳转取消属于正常生命周期。

### 主题与字体

- 全局明暗主题由 `ThemeManager` 和 DayNight 主题管理；布局引用 `values/` 与 `values-night/` 的语义色，新增布局不硬编码 hex。
- 漫画阅读器自身面板主题与全局主题隔离，按 `darkPanel` 使用专属资源；不将 `mangaimport/reader/` 的固定浅色图标和背景统一替换为全局语义色。
- UI 字体同步由 `utils/FontSync.kt` 统一处理：Activity 在 `super.onCreate()` 前安装，Service inflate 后遍历，程序化或适配器控件显式应用。译图结果字体使用独立设置。
- 固定字号控件不同时启用 autosize。长标题优先处理可用宽度和省略；单行可滚标题使用 `UiUtils.marqueeTitle()`。
- Preference 页面使用共享 `preferenceTheme` 和 `preference_item_fit.xml`；修改设置页前确认实际宿主是否为 `PreferenceFragmentCompat`。

### 弹窗、提示与控件

- 弹窗和选择器使用应用样式：`android.app.AlertDialog` 配合应用布局／主题，底部面板使用 `BottomSheetDialogFragment`；下拉使用 Material 或自定义实现。当前主题不兼容 `MaterialAlertDialogBuilder`，不替换构造器。
- 翻译进度和状态通过 `TranslationStatusOverlay`；一次性保存、复制等反馈使用 `UiUtils.showToast`；需要选择和确认时使用应用弹窗。提示方法、sticky 与并行任务句柄语义见 [翻译模块](app/src/main/java/com/moe/starflow/translate/CLAUDE.md#全局状态浮层-translationstatusoverlay)。
- 常驻浮层使用既有 `TYPE_APPLICATION_OVERLAY` 路径；后台任务不得保留 Activity 回调或向其他应用上方显示阅读器 UI。必要的系统授权仍走平台流程。
- 自定义对话框从 inflate 的根 View 持有控件；内容可滚动并保留按钮空间，不用固定窗口高度挤掉按钮。`DialogPreference` 的展示入口为 `onDisplayPreferenceDialog`。
- `FrameLayout` 子项显式设置 `layout_gravity`；横向 `wrap_content` 行内不使用无约束的 `match_parent` 高度。`ScrollView` 限高通过实际测量实现。
- 会调用 `ArrayAdapter.clear()` 时传列表副本，并用 `getItem()` 读取过滤结果；`SeekBar.setProgress(value, true)` 的第二个参数表示动画，不能模拟真实用户拖动。
- 使用 `app:tint` 时声明 `xmlns:app`。自建 HTTP API 依赖 `network_security_config.xml` 的明文放行配置，修改前核对其兼容需求。

## 日志与故障定位

普通 Kotlin／Java 日志统一通过 `LogCollector`。`starflow.log` 保存最近 300 行，并保护最近原生崩溃块；闪退后优先读取持久日志或崩溃缓冲区，不能用当前内存缓冲代替。

- `StarFlowApplication` 先初始化日志，再安装 Java 异常处理和原生崩溃处理，记录上次进程退出原因；不等待本地模型初始化才安装崩溃处理器。
- 原生普通日志走 logcat。信号处理器仅使用适合信号上下文的操作，追加崩溃信息后恢复 libc 原处理器、解除信号屏蔽并重新触发信号；保留备用栈，不用 `_exit` 替代系统崩溃链。
- 原生诊断保留时间、线程、内存、推理阶段和上下文信息。复现后用日志中的 `.so` BuildId 对照当前产物，避免将旧崩溃归到新构建。
- JNI 按名称反查的回调接口必须由 R8 keep 规则保护，例如 `LlamaCppStreamCallback`。仅保留 native 方法不能保护普通 Java 回调。
- 本地推理在 decode 前检查 prompt 与上下文容量，超长请求返回明确错误，不能进入会 abort 的原生路径。
- 日志导出在后台使用点击时的快照，失败记录具体阶段并清理半成品；文件不可读时记录原因并回退当前内存日志。

设备日志读取遵循 [协作规则](AGENTS.md)。持续复现使用后台过滤并写入本地日志文件；一次性崩溃读取可用只读转储：

```powershell
adb logcat -d -b crash
```

保留现场日志，不先执行 `logcat -c`。通过时间、进程和 BuildId 区分旧记录；重启后 PID 会变化。不得用剪贴板内容代替崩溃缓冲。

## 更新与公告

`utils/UpdateChecker.kt` 读取 GitHub Releases，按 `parseVersionCode` 比较版本并从 assets 查找 APK。发布同步核对版本字段、APK 和应用内下载解析；具体发布流程使用仓库已有发布技能。

Release body 的网盘下载行需要对应中文名称与链接；不可用时标明“暂无”：

```text
**下载说明**：
- 百度网盘：<下载链接>
- 夸克网盘：暂无
```

`utils/NotificationChecker.kt` 从固定 Gist raw 地址取公告，从首个 `{` 开始解析 JSON。该 raw 地址依赖单文件约定；避免多个文件返回非预期内容。公告 `content` 使用 HTML 标记，展示端通过 `Html.fromHtml` 渲染；JSON 换行按标准转义，不使用 Markdown 星号充当 HTML。

## 编辑与并行工作

显式按 UTF-8 读写文本，避免系统默认编码；修改文件保持索引中的 LF 换行，用 `git diff --stat` 和实际 diff 核对意外整文件改写。Kotlin 块注释会嵌套，KDoc 正文不插入未闭合的注释标记。

多个写入任务使用独立工作目录；同目录换分支不提供隔离。提交获授权后仍核对当前分支与暂存范围，避免混入其他任务改动。仅在任务需要时使用隔离工作区，不将并行代理作为默认步骤。
