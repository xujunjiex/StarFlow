# StarFlow（星译）

<p align="center">
  <img src="images/appicon.png" width="128" height="128" alt="StarFlow Logo">
</p>

<p align="center">
  <b>开源 Android OCR + AI 翻译 App</b><br>
  游戏翻译 · 视频翻译 · 漫画翻译 | 内置漫画书架与阅读器<br>
  完全免费 · 无广告 · 开源<br>
  支持 Android 10+（API 29+）| 仅 arm64-v8a
</p>

---

## 为什么选择 StarFlow

相较于传统 OCR 翻译 App：

1. **项目完全开源** — LGPL 协议，代码全部公开，可自行编译与审计
2. **专用日漫识别模型** — 集成 manga-ocr，大幅提高竖排日文识别精度
3. **全自动翻译模式** — 检测屏幕变化自动触发翻译，解放双手
4. **零成本接入 AI 大模型** — 内置火山/DeepSeek/通义千问/智谱等接口，填 Key 即用
5. **完善的翻译历史管理** — 双视图、进程分组、重新翻译、打包下载
6. **精确缓存命中机制** — pHash 相似度匹配，翻过的页面秒开，大幅降低 API 消耗
7. **部署最新端侧 AI 翻译模型** — 1.8B 设备端大模型（llama.cpp 推理，模型按需下载），翻译完全离线、不上传文本
8. **OCR 引擎统一选择层** — 模型管理页一键切换 OCR 引擎组合（ML Kit/PP-OCRv6/PP-OCRv5/RT-DETR+manga），游戏/漫画/首页全同步，源语言随引擎自动适配
9. **自带漫画书架与阅读器** — 导入 zip / 图片目录即读，4 种阅读模式 + 4 种翻页动画 + 调色矫正；翻译就在阅读器里做，译文包 / 双语包一键导出（文件名沿用原序号）

---

## 功能详解

### 多场景翻译

<p align="center">
  <img src="images/game_demo.gif" width="400" alt="游戏翻译"><br>
  <em>游戏翻译</em>
</p>

悬浮窗覆盖翻译，支持像素驱动自动翻译和手动框选两种模式。悬浮球单击/双击/长按可自由分配动作（翻译/菜单/自动翻译）。

<p align="center">
  <img src="images/video_demo.gif" width="400" alt="视频翻译"><br>
  <em>视频翻译</em>
</p>

同游戏翻译引擎，适用于视频字幕、直播弹幕等动态场景。关闭翻页稳定性检测后实现低延迟实时同步翻译。

<p align="center">
  <img src="images/manga_demo.gif" width="200" alt="漫画翻译">
  &nbsp;&nbsp;
  <img src="images/stability_demo.gif" width="200" alt="稳定性检测">
  <br>
  <em>漫画翻译 &nbsp;|&nbsp; 稳定性检测 &nbsp;|&nbsp; 缓存命中</em>
</p>

气泡检测 + OCR + 翻译 + 竖排渲染，4 组引擎组合可选（ML Kit / PP-OCRv6 / PP-OCRv5 / RT-DETR-V2 + manga-ocr）。超 6 个气泡增量渲染分批提速，pHash 检测页面变化自动翻页。实时渲染共享层：译文/原文/纯原图三态切换、气泡点击复制原文/译文、翻译缓存相似度匹配翻过的页秒开。竖排方向（右到左/左到右）对所有翻译结果实时生效。

### 漫画书架 & 阅读器

**书架**：导入 zip 或图片目录（SAF 选择，导入即复制进 app 专属目录），自动生成封面，支持简介 / 重命名 / 换封面 / 已读标记 / 多选批量删除、下拉刷新、列表与网格两种显示；文件被外部删掉会标「文件丢失」。

**阅读器**：

| 能力 | 说明 |
|------|------|
| 4 阅读模式 | 左到右 / 右到左 / 竖排（竖向整页）/ 连续滑动（Webtoon） |
| 4 翻页动画 | 无 / 默认滑动 / 高级（封面叠放）/ 仿真（页脚卷曲、镜像纸背） |
| 背景 & 调色 | 6 种背景（含跟随系统夜间）+ 反色/灰度/书本 + 亮度对比度；调色面板内「原图 vs 处理后」实时对比 |
| 进度胶囊 | 底部透明胶囊：拖拽寻页、长按弹 3 列缩略图网格跳页；三色双层——白=已读 / 灰=未读 / **绿=已翻译** |
| 自动翻页 | 间隔可设，触摸与失焦自动暂停 |
| **点中间显隐 UI** | 点屏幕正中一格整组淡入淡出上下 UI，状态一直保持；滑动翻页/双击缩放不会误触 |
| 旋转自适应 | 旋转后自动重新对齐到当前页，并按新视口重算页面尺寸与图片 |

**阅读器内嵌翻译**：手动 / 自动 / 增量三种模式共用一条串行队列（翻页不打断、只翻当前在看的页上屏），译文实时叠加在页面上，支持译文 / 原文 / 纯原图三态切换（连续滑动模式为原图 / 译文两态）；失败页显示感叹号，点开看失败原因。

**三种打包下载**（工具栏「更多」→ 下载），均落盘到系统「下载」目录：

| 入口 | 内容 | 包内命名 |
|------|------|---------|
| 原文 | 全部页 | 沿用原压缩包/目录里的名字 |
| 译文 | **只有已翻译的页** | `<原名主干>.jpg`（JPEG 95） |
| 双语 | 已翻译页的原文 + 译文 | 原名原文 + `<主干>_译文.jpg` |

命名**沿用原压缩包里的序号**：原包 1..10 页只翻了 1/2/5/6，导出的就是 `001/002/005/006`，不是 `1/2/3/4`（双语包的 `_译文` 后缀随界面语言，英文界面为 `_translated`）。

### 文本翻译 & 聊天

独立文本翻译页面：流式输出实时显示翻译进度，最近记录分页 + 快速复制，语言选择跨页面持久化。源语言不受 OCR 引擎限制（30 种全量可选），目标语言按翻译模型自动过滤。

页面内置**聊天模式**（Tab 切换）：基于端侧 AI 翻译模型（Hy-MT2）或 OpenAI 兼容 API 的对话式翻译，支持聊天模板、会话历史。端侧模型提供真正的离线对话能力。

---

### OCR 模型体系

针对不同场景提供多种专用 OCR 模型：

| 模型 | 用途 | 大小 | 来源 |
|------|------|------|------|
| **Google ML Kit** | 内置快速多语言 OCR | 内置 | Google（无需下载） |
| **PP-OCRv5** | 通用中日英检测+识别 | ~22MB | 下载（det + rec_zh + 可选多语言） |
| **PP-OCRv6** | 通用多语言检测+识别（v5 升级版） | small ~31MB 内置 / medium ~134MB 下载 | RapidAI/RapidOCR |
| **manga-ocr** | 日漫竖排文字专用 | ~135MB | HuggingFace（可选下载） |
| **RT-DETR-V2** | 文字/气泡检测 | ~11MB | HuggingFace（可选下载） |

**按需下载，优化包体积：**
- PP-OCRv6 small（det + rec）**内置**在 APK，开箱即用；medium 可选下载提升精度
- PP-OCRv5 全部模型由下载管理器下载（不再内置），支持多语言识别模型（en/ko/ru）可选下载
- 下载管理器支持断点续传、自动重试、进度回调

---

### 翻译引擎矩阵

**本地机器翻译：**
- **Hy-MT2 翻译** — 部署最新端侧 AI 翻译模型（1.8B 设备端大模型，llama.cpp 推理），翻译完全离线、不上传文本，进程级共享实例 + 前缀 KV 缓存加速，支持流式输出与聊天模式
- **NLLB 翻译** — 首次下载模型（~1GB）后可离线使用，自动检测设备 RAM（< 6GB 警告）

**在线翻译 API：**

| API | 免费额度 | 需要 Key |
|-----|---------|----------|
| 必应翻译 | 无限制 | 否 |
| 小牛翻译 | 20 万字符/天 | 是 |
| 火山引擎 | 200 万字符/月 | 是 |
| Azure AI 翻译 | 200 万字符/月 | 是 |
| DeepL 翻译 | 50 万字符/月 | 是 |
| 百度翻译 | 100 万字符/月 | 是 |
| 腾讯云 | 500 万字符/月 | 是 |

**AI 大模型接口：**
- 内置 DeepSeek、通义千问、豆包（火山）、智谱 GLM 等国内主流大模型接口
- 支持自定义 OpenAI 兼容 API（填地址 + Key + 模型名即可）
- 「获取模型列表」一键拉取账号可用模型（内置厂商同样支持；DeepSeek 与通义千问不预置模型，按需拉取）
- 游戏/漫画提示词独立配置，AI 上下文携带历史翻译对提升连贯性
- 友好的 API 配置页面，支持快速选择模型和填入 Key

---

### 自动翻译 + 稳定性检测

开启后系统自动检测屏幕变化：

- **像素驱动检测** — YIQ 感知色彩差异，跳过稳定页面重复 OCR
- **翻页稳定性检测** — 检测画面变化后等画面静止再触发翻译，适合游戏翻页
- **实时字幕模式** — 关闭稳定性检测，画面变化立即翻译，适合视频字幕
- **LRU 缓存** — 20 条缓存，相同文字直接返回翻译结果（⚡ 标识）
- **参数可调** — 像素变化阈值、检测间隔均可自定义
- **漫画自动翻页** — pHash 感知哈希检测页面变化，停稳 ~1s 自动触发翻译

---

### 翻译历史管理

<p align="center">
  <img src="images/history_page.gif" width="260" alt="历史记录页面">
  &nbsp;&nbsp;
  <img src="images/history_retranslate.gif" width="260" alt="历史记录重新翻译">
  <br>
  <em>历史记录页面 &nbsp;|&nbsp; 历史记录重新翻译</em>
</p>

- **双视图架构** — 默认视图（按修改时间排序）+ 管理视图（按进程 sessionId 分组）
- **全屏翻页浏览** — 原图 + 译文详情面板 + 原文/译文切换 + 尺寸变体切换
- **管理视图重翻** — 加载原始截图 → 重新 OCR → 翻译 → 渲染，替换原变体，无需启动翻译服务
- **进程组打包下载** — ZIP 直接存入系统「下载」目录（MediaStore），写失败时回退系统文件选择器
- **智能分组** — 同 pHash 页面自动分组，多尺寸变体可切换
- **缓存命中** — 历史页直接从缓存渲染 overlay，不重新识别与翻译

---

### 双模式截图

| 模式 | 特点 | 适用场景 |
|------|------|----------|
| **MediaProjection**（默认） | 弹窗授权，门槛低，每次启动需重新授权 | 日常使用 |
| **AccessibilityService** | 手动开启，永久有效，无需重复授权 | 频繁使用 |

通过 `ScreenshotProvider` 接口抽象，`ScreenshotManager` 单例解耦截图生产者与消费者，游戏和漫画模式共用同一套截图架构。

---

### 其他功能

- **首次启动引导** — 权限申请 + API 配置引导
- **深色模式** — 跟随系统 / 浅色 / 暗色三态切换（主页右上角），全 app 界面与弹窗配色适配；阅读器与翻译结果悬浮窗保持自身配色
- **悬浮窗个性化** — 手势自定义（单击/双击/长按）、可穿透性、长按延迟、字体大小背景、UI 同步字体
- **检查更新** — GitHub Releases 自动检测，支持直接下载 / 百度网盘 / 夸克网盘
- **应用内公告** — 开发者通过 Gist 推送公告，启动时自动检查
- **FAQ 页面** — 常见问题解答，含 PP-OCRv5 调试面板参数详解
- **开发者选项** — 各引擎调试浮窗（RT-DETR-V2/MLKit/PP-OCRv5/PP-OCRv6）+ 参数实时调节
- **日志系统** — 所有日志通过 LogCollector 统一管理，支持导出

---

## 下载

| 方式 | 链接 |
|------|------|
| GitHub Releases | [最新版本 v0.11.0](https://github.com/xujunjiex/StarFlow/releases/tag/v0.11.0) |
| 百度网盘 | https://pan.baidu.com/s/1Zi-o2mHhgJEqhk8UzxRoSA?pwd=star |
| 夸克网盘 | https://pan.quark.cn/s/cbac92882d82?pwd=E9P8 |

---

## 构建

### 环境要求

| 项 | 版本 | 说明 |
|----|------|------|
| JDK | **17** | Gradle 与 AGP 要求；任意 JDK 17 发行版（Temurin / Zulu / Microsoft 等）都可以 |
| Android SDK | compileSdk 35 / targetSdk 35（minSdk 29） | 路径写在 `local.properties` |
| **NDK** | **25.2.9519653** | 原生代码必需，**版本必须完全一致**（`app/build.gradle` 里写死） |
| CMake | 3.22.1 | 在 SDK Manager 里勾选安装 |
| 架构 | **仅 arm64-v8a** | 64 位 ARM 真机；x86 模拟器跑不起来 |

### 首次配置

1. **`local.properties`**（已 gitignore，指向你自己的 Android SDK，路径用正斜杠）：
   ```properties
   # Windows
   sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk
   # macOS
   sdk.dir=/Users/<username>/Library/Android/sdk
   # Linux
   sdk.dir=/home/<username>/Android/Sdk
   ```
2. **SDK Manager** 里装 `NDK 25.2.9519653` + `CMake 3.22.1`（版本不对会直接构建失败）
3. 验证：`./gradlew assembleDebug`

### 常用命令

```bash
./gradlew assembleDebug                    # debug APK（首次要编译 C++，明显偏慢，之后增量）
./gradlew assembleRelease                  # release APK（跑 lint，缺翻译直接失败）
./gradlew clean assembleDebug              # 清理重建
./gradlew :app:lintDebug                   # 单独跑 lint（本地化防线，当前 errors=0）

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat --pid=$(adb shell pidof com.moe.starflow)      # 实时日志
```

`adb` 未加入 PATH 时用完整路径，例如 Windows：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

### 单元测试

```bash
./gradlew test                                    # 全部单元测试（JUnit + Robolectric）
./gradlew :app:testDebugUnitTest --tests "com.moe.starflow.mangaimport.reader.ExportNamingTest"
```

Robolectric 的 SDK 统一配在 `app/src/test/resources/robolectric.properties`（`sdk=34`）。

### 原生代码与模型

- `app/src/main/cpp/`（约 49MB 源码）通过 CMake 编译：**llama.cpp**（Hy-MT2 设备端推理）+ ONNX / sentencepiece 桥接。首次构建慢，之后增量。
- **PP-OCRv6 small 内置**在 `assets/`，开箱即用；PP-OCRv5 全系、v6 medium、RT-DETR-V2、manga-ocr、Hy-MT2、NLLB 都是运行时按需下载（模型管理页）。
- release 构建开了 `minifyEnabled` + `shrinkResources`，JNI 回调接口由 `proguard-rules.pro` 的 `-keep` 保护 —— **改动 native 回调接口名要同步改 keep 规则**，否则 debug 正常、release 闪退。

### 发布

`app/build.gradle` 的 release 目前复用 debug 签名（无需额外 keystore），`./gradlew assembleRelease` 即可产出可安装包；正式发布走 GitHub Releases（app 内「检查更新」依赖 Release 的 APK 资产 + 说明里的网盘链接）。

---

## 项目结构

```
app/src/main/java/
├── com/moe/starflow/
│   ├── manga/            漫画翻译引擎（截屏路线）
│   │   ├── types/ config/   纯数据类 / 引擎组合与参数
│   │   ├── engine/          检测与 OCR 引擎（PP-OCRv5/v6、ML Kit、manga-ocr、RT-DETR-V2）
│   │   ├── pipeline/        分批翻译管线（与阅读器共用，含测试缝 BatchOcrOps）
│   │   ├── render/          译文渲染（OverlayRenderer / VerticalTextRenderer）
│   │   ├── merge/           文本区域合并与空间聚类
│   │   ├── state/           自动翻译状态机 / 引擎管理 / 区域缓存
│   │   ├── debug/           调试浮层渲染与参数面板
│   │   └── MangaFloatingService.kt   主服务（TranslateUtils / OcrLock / OnnxUtils / GeometryUtils）
│   ├── mangaimport/      漫画导入书架 + 阅读器
│   │   ├── ImportMangaFragment.kt   书架（导入 / 多选管理 / 显示选项）
│   │   ├── data/           导入存储、zip 读取、目录迁移
│   │   ├── reader/         阅读器（4 阅读模式 / 翻页动画 / 进度胶囊 / 打包下载 ReaderExport）
│   │   ├── translate/      阅读器内嵌翻译（三种模式控制器 + 每页记录编解码）
│   │   └── ui/             导入对话框 / 显示选项面板 / 网格适配器
│   ├── translate/        游戏与视频翻译引擎
│   │   ├── screenshot/     双模式截图（MediaProjection / 无障碍）
│   │   ├── autotranslate/  像素驱动自动翻译（状态机 / GameOcrEngine）
│   │   ├── widget/         悬浮窗组件（结果容器 / 悬浮球状态 / 框选 / 弹窗）
│   │   └── FloatingBallService.kt    主服务 + TranslationTextAPI/PicAPI 接口
│   ├── chat/             文本聊天翻译（ChatEngine / 模板 / 历史）
│   ├── ui/               历史记录列表 & 漫画查看器（history/ viewer/）
│   ├── me/               设置与配置（about / apiconfig / model / settings 子包）
│   ├── data/             Room 数据库（v17）与三层缓存
│   ├── download/         模型下载流水线（断点续传 / 状态机 / MD5 校验）
│   ├── utils/            工具（Constants / CustomPreference / LogCollector / FontSync 等）
│   └── launch/           首次启动引导
└── translationapi/       各厂商翻译 API 实现（含 Hy-MT2 / NLLB 本地引擎）
```

- `translationapi/` 是历史遗留的独立顶层包（不在 `com.moe.starflow` 下）：JNI 符号与 proguard keep 规则硬编码该包名，直接移动会导致 `UnsatisfiedLinkError`
- 原生代码：`app/src/main/cpp/`（CMake 构建）
- 测试：`app/src/test/java/`（JUnit + Robolectric，与主源码同包名子目录）

---

## 致谢

本项目 forked 自 [MoeTranslate](https://github.com/murangogo/MoeTranslate)。

### 参考项目

- [RapidOCR](https://github.com/RapidAI/RapidOCR) — 跨平台 OCR 推理
- [RT-DETR](https://github.com/lyuwenyu/RT-DETR) — 实时目标检测 Transformer
- [manga-ocr](https://github.com/kha-white/manga-ocr) — 日漫竖排文字 OCR
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — 漫画图片翻译
- [Kototoro](https://github.com/Kototoro-app/Kototoro) — 漫画阅读器（阅读模式 / 翻页动画 / 页脚卷曲 / 进度条 / 点击分区）
- [pixelmatch](https://github.com/mapbox/pixelmatch) — YIQ 感知像素比较
- [ONNX Runtime](https://onnxruntime.ai/) — 模型推理引擎
- [JTS](https://locationtech.github.io/jts/) — 多边形几何运算

---

## 许可证

LGPL — 详见 [licenses/](licenses/) 目录。第三方库许可证见各项目主页。
