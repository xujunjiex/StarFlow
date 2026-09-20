# StarFlow（星译）

<p align="center">
  <img src="images/appicon.png" width="128" height="128" alt="StarFlow Logo">
</p>

<p align="center">
  <b>开源 Android 截图翻译 App</b><br>
  游戏 / 视频翻译 · 漫画翻译 · 文本翻译，内置漫画书架与阅读器<br>
  完全免费 · 无广告 · 开源 · 端侧模型离线可用<br>
  支持 Android 10+（API 29+）| 仅 arm64-v8a
</p>

---

## 这是什么

StarFlow 把一条流水线做到底：**截图 → 检测（文字 / 气泡）→ OCR → 翻译 → 把译文渲染回原位置**。

三种场景共用同一套 OCR 引擎、翻译引擎、缓存与历史：

| 场景 | 怎么用 | 特点 |
|------|--------|------|
| **游戏 / 视频** | 悬浮球 → 全屏或框选翻译 | 译文覆盖在原文位置；可自动监听画面变化持续翻译 |
| **漫画** | 悬浮球 → 截取漫画页 | 气泡级检测 + 竖排识别 + 竖排渲染，自动翻页 |
| **文本 / 对话** | 应用内页面直接输入 | 流式输出，可切换成与翻译模型对话 |

此外还有**漫画书架 + 阅读器**：zip / 图片目录导入即读，翻译就在阅读器里完成。

---

## 核心亮点

1. **端侧离线翻译模型** — 内置腾讯混元 **Hy-MT2 的 1.25-bit 量化版本（约 440MB）**，llama.cpp 设备端推理，文本完全不出设备；进程级共享热实例 + 前缀 KV 缓存（固定的翻译指令只 prefill 一次）。另有 NLLB 离线模型可选。
2. **漫画翻译是投入最重的方向** — 4 组「检测 + 识别」引擎组合可选，识别后区域合并、竖排/横排自适应渲染、**超过 6 个气泡自动分批增量渲染（首批译文先上屏）**、渲染层三态切换与重叠白块合并。
3. **翻译缓存省时省额度** — 256 位感知哈希（pHash）整页匹配：翻过的页面直接复用结果，连 OCR 和翻译都省掉；整页未命中时，再用加权编辑距离逐气泡复用译文。
4. **底图与译文分层存储** — 数据库只存原图 + 每页的译文文本与气泡坐标，**不存渲染好的译图**：改字号、颜色、竖排方向、字距行距、替换规则后，已翻译页面直接按新设置重渲，不用重新识别或翻译。
5. **自带漫画书架与阅读器** — 导入 zip / 图片目录即读；4 种阅读模式、4 种翻页动画、背景与调色矫正；阅读器内嵌手动 / 自动 / 增量三种翻译模式，三种打包下载（原文 / 译文 / 双语）。
6. **引擎与接口覆盖面广** — 本地模型、传统翻译 API、AI 大模型三类可自由混搭；内置 DeepSeek / 通义千问 / 豆包（火山）/ 智谱，也支持任意 OpenAI 兼容接口。
7. **完全开源免费** — LGPL 协议，代码全部公开；无广告、无内购、无账号，可自行编译与审计。

---

## 功能详解

### 一、游戏 / 视频翻译

<p align="center">
  <img src="images/game_demo.gif" width="400" alt="游戏翻译"><br>
  <em>游戏翻译</em>
</p>

悬浮窗覆盖翻译，支持两种取词方式：

- **全屏 / 框选** — 手动全屏截图，或先拖出一个框选区域，只翻译这块区域（框选区域可重复使用）
- **自动翻译** — 像素驱动检测画面变化，稳定后自动截图翻译；翻页类场景开「稳定性检测」，字幕 / 弹幕类场景关掉它即变为低延迟实时翻译

悬浮球交互：

- **手势自定义** — 单击 / 双击 / 长按各自可分配「翻译 / 打开菜单 / 自动翻译开关 / 关闭悬浮窗」
- **长按菜单** — 框选、字体大小、切换 OCR 模型、切换源语言、历史记录、自动翻译开关、关闭、返回主页（改完不关菜单，可连续切换）
- **大小与不透明度** — 50%–200% 大小、10%–100% 不透明度，游戏与漫画共用一份设置，在屏的球实时跟随；球变大贴边或转屏后会按新尺寸夹回屏幕内，不会滑出可视区域
- **状态图标** — 处理中 / 翻译中 / 完成 / 出错四种状态实时切换（出错时红圈脉冲提示）

翻译结果框：

- 字体、字号、文字颜色、背景颜色、文字阴影均可配
- 「显示原文」三态：只显示译文 / 只显示原文 / 原文 + 译文对照
- 结果框可拖动摆放，四个角上有锁定（防止误拖）/ 复制 / 重新翻译 / 关闭按钮

<p align="center">
  <img src="images/video_demo.gif" width="400" alt="视频翻译"><br>
  <em>视频翻译</em>
</p>

视频字幕、直播弹幕与游戏共用同一引擎：关闭翻页稳定性检测后画面一变即翻，实现低延迟同步翻译。

### 二、漫画翻译

<p align="center">
  <img src="images/manga_demo.gif" width="200" alt="漫画翻译">
  &nbsp;&nbsp;
  <img src="images/stability_demo.gif" width="200" alt="稳定性检测">
  <br>
  <em>漫画翻译 &nbsp;|&nbsp; 稳定性检测 &nbsp;|&nbsp; 缓存命中</em>
</p>

漫画和游戏不是同一套逻辑：漫画需要**气泡级检测**、**竖排文字识别**、**按气泡区域排版回填**，还要处理同一句话被切成多行 / 多列的情况。

#### 1. 检测 + 识别引擎组合

四组固定搭配，模型管理页一键切换，游戏 / 漫画 / 首页状态栏同步：

| 组合 | 检测 | 识别 | 适用 |
|------|------|------|------|
| **PP-OCRv6**（默认） | PP-OCRv6 det | PP-OCRv6 rec | 通用、开箱即用（small 内置） |
| **PP-OCRv5** | PP-OCRv5 det | PP-OCRv5 rec | 通用，多语言识别模型可选下载 |
| **RT-DETR-V2 + manga-ocr** | RT-DETR-V2 | manga-ocr | 日漫竖排文字精度最高 |
| **ML Kit** | ML Kit（检测识别一体） | ML Kit | 无下载、最快，适合简单版式 |

源语言列表随所选组合自动排序，不支持的语种置灰并给出提示；目标语言按翻译引擎过滤（Hy-MT2 官方 38 种，NLLB / 在线 API 不限）。

#### 2. 识别后区域合并

PP-OCRv5 / v6 独立路径在识别后做两阶段合并：先按字号、方向、对齐方式把属于同一句话的框连成连通分量，再按阅读顺序排序、只在**相邻行 / 列间隙显著跳变**处切分——避免把同一个气泡里的两句话并成一句，也避免把一句话拆成两段分别翻译。表格、多栏等场景可在个性化设置里关闭合并。

#### 3. 增量分批渲染（先出结果，而不是等全部翻完）

一页气泡多的时候，等所有气泡 OCR + 翻译完再一次性上屏，用户要盯着空屏好几秒。StarFlow 的做法是**分批**：

- 气泡数 **> 6** 时自动分批（约 2/5 + 3/5），第一批 OCR 完成即开始翻译并渲染上屏，**第二批的 OCR 与第一批的翻译并行执行**，最后补渲染第二批
- 批次之间携带上下文：第二批能看到第一批的译文，保证同一页的称呼、语气连贯；翻完两批后上下文回滚，不污染后续页面
- **Hy-MT2 例外**：本地模型不走分批，而是「一次翻译全部气泡 + 逐句流式上屏」——本地推理每次都要重新读入原文，分批只会更慢

#### 4. 翻译缓存

三层结构，越靠前省得越多：

| 层 | 匹配方式 | 命中后省掉 |
|----|---------|-----------|
| **图片缓存** | 256 位 pHash，精确匹配或相似度 ≥ 0.95 | OCR + 翻译 + 渲染，直接显示上次结果 |
| **文本缓存** | 加权编辑距离模糊匹配 | 调用翻译 API |
| **数据库历史** | 256 位 hash 相似度分组（≥ 0.85） | 翻历史时复用，同页多尺寸变体可切换 |

纯色 / 无文字页面会在入口处被识别并直接跳过，不做无意义的 OCR 与 API 调用。命中缓存的译文会带 ⚡ 标记（开发者选项里可开关）。

#### 5. 渲染

- **竖排 / 横排自适应** — 竖排文字自动按列排版，列距拉伸填满气泡宽度；横排文字自动填充行距与字距，尽量撑满选区且绝不越界
- **竖排方向可配** — 列从右到左（传统日漫）/ 从左到右，对所有翻译结果**实时生效**（包括历史与缓存里的旧数据）
- 字号（自动 / 固定档位）、字间距、行间距、文字颜色、背景颜色、横排对齐均可调
- **重叠白块合并** — 译文白块互相重叠时并成一个块（组间用分隔记号），开关默认关闭
- **三态切换** — 译文 / 原文 / 纯原图随时切换；点气泡可复制原文或译文

#### 6. 自动翻页

pHash 感知哈希检测页面变化，画面停稳约 1 秒自动触发翻译；同一页面长时间无变化会自动停止并提示（避免录屏冲突导致的假死）。

### 三、漫画书架 & 阅读器

**书架**：导入 zip / cbz 或图片目录（SAF 选择，导入即复制进 app 专属目录），自动生成封面，支持简介、重命名、换封面、已读标记、多选批量删除、下拉刷新、列表与网格两种显示；外部删掉的文件会标记「文件丢失」。

- **导入进度可见** — 选完立刻出现占位卡片（图片位显示进度与百分比），可随时点 ✕ 取消；导入在后台进行，旋转 / 切页不会中断
- **导入整个文件夹 = 一部漫画** — 文件夹（含各级子文件夹）里的图片按「先目录名、再页名」的自然顺序拼成一整本
- **删除前提醒** — 选中的书里有译文时会提示「删除后无法恢复」，并提供「先去导出」入口

**阅读器**：

| 能力 | 说明 |
|------|------|
| 4 阅读模式 | 左到右 / 右到左 / 竖排（竖向整页）/ 连续滑动（Webtoon） |
| 4 翻页动画 | 无 / 默认滑动 / 高级（封面叠放）/ 仿真（页脚卷曲） |
| 背景 & 调色 | 6 种背景（含跟随系统夜间）+ 反色 / 灰度 / 书本 + 亮度对比度；调色面板内可实时对比原图与处理后 |
| 进度胶囊 | 底部透明胶囊：拖拽寻页、长按弹缩略图网格跳页；白=已读 / 灰=未读 / 绿=已翻译 |
| 自动翻页 | 间隔可设，触摸与失焦自动暂停 |
| 点中间显隐 UI | 点屏幕正中一格整组淡入淡出上下 UI，滑动翻页 / 双击缩放不会误触 |
| 旋转自适应 | 旋转后自动重新对齐到当前页，并按新视口重算页面尺寸 |

**阅读器内嵌翻译**：手动 / 自动 / 增量三种模式共用一条串行队列（翻页不打断、只翻当前正在看的页），译文实时叠加在页面上；失败页显示感叹号，点开可看失败原因。译文字号（自动 / 8–48 档）在个性化设置、悬浮窗菜单与翻译面板三处共用同一份设置。

- **底图与译文分层** — 翻译结果只以「文本 + 气泡坐标」入库，渲染好的译图不落盘；改字号、颜色、竖排方向、字距行距、重叠合并或译文替换规则时，已翻译页面直接按当前设置重渲（毫秒级，有内存缓存）
- **译文替换表** — 可自由增删多条「查找 → 替换」规则（例如把识别到的 `...` 统一换成 `.`），渲染译文时套用，改完回阅读器即生效，不必重翻
- **省略号不再被拆行** — 模型偶尔把译文写成 `.\n.\n.`，渲染前会把这些换行接回同一行，排版时点串整体不拆分

**三种打包下载**（工具栏「更多」→ 下载），均落盘到系统「下载」目录：

| 入口 | 内容 | 包内命名 |
|------|------|---------|
| 原文 | 全部页 | 沿用原压缩包 / 目录里的名字 |
| 译文 | **只有已翻译的页** | `<原名主干>.jpg`（JPEG 95） |
| 双语 | 已翻译页的原文 + 译文 | 原名原文 + `<主干>_译文.jpg` |

命名**沿用原压缩包里的序号**：原包 1..10 页只翻了 1/2/5/6，导出的就是 `001/002/005/006`，不是 `1/2/3/4`（双语包的 `_译文` 后缀随界面语言，英文界面为 `_translated`）。

### 四、文本翻译 & 聊天

独立文本翻译页面：流式输出实时显示翻译进度，最近记录分页 + 快速复制，语言选择跨页面持久化。源语言不受 OCR 引擎限制（30 种全量可选），目标语言按所选翻译模型自动过滤。

页面内置**聊天模式**（Tab 切换）：基于端侧 Hy-MT2 或 OpenAI 兼容 API 的对话式翻译，支持聊天模板与会话历史——端侧模型使对话也能完全离线。

---

## 翻译引擎

### 本地引擎（离线，文本不出设备）

| 引擎 | 说明 |
|------|------|
| **Hy-MT2** | 腾讯混元开源多语言翻译模型：**1.25-bit 量化版约 440MB**，llama.cpp 设备端推理。流式输出（译文逐字出现），进程内全局共享一个热实例，游戏 / 漫画 / 文本 / 阅读器共用；prefill 与生成线程数可分别调 |
| **NLLB** | 首次下载模型（约 950MB）后可离线使用，自动检测设备内存（小于 6GB 会提示） |

### 在线翻译 API

| API | 免费额度 | 需要 Key |
|-----|---------|----------|
| 必应翻译 | 无限制 | 否 |
| 小牛翻译 | 20 万字符/天 | 是 |
| 火山引擎 | 200 万字符/月 | 是 |
| Azure AI 翻译 | 200 万字符/月 | 是 |
| DeepL 翻译 | 50 万字符/月 | 是 |
| 百度翻译 | 100 万字符/月 | 是 |
| 腾讯云 | 500 万字符/月 | 是 |

图片翻译（整图交给厂商）支持百度 / 腾讯 / 自定义接口。

### AI 大模型

- 内置 **DeepSeek、通义千问、豆包（火山引擎）、智谱 GLM**，也可填地址 + Key + 模型名接入任意 OpenAI 兼容接口
- **模型列表一键拉取** — 从账号实际可用的模型里选（DeepSeek / 通义千问不预置模型，按需拉取）
- **提示词游戏 / 漫画独立配置** — 漫画另有「续写格式控制」：用各厂商的 assistant 续写能力硬约束模型输出 `[1] 译文` 的编号批量格式，一次请求翻译整页气泡，输出更规整、解析更稳定
- **AI 上下文** — 游戏模式携带最近若干轮（5–20，可配）的历史翻译对，提升剧情连贯性；漫画仅在增量渲染的两批之间使用
- 思考模式三态可配（跟随模型 / 强制关闭 / 强制开启），避免个别网关因不支持的参数直接报错

---

## OCR 模型体系

| 模型 | 用途 | 大小 | 来源 |
|------|------|------|------|
| **Google ML Kit** | 内置快速多语言 OCR | 内置 | Google（无需下载） |
| **PP-OCRv6 small** | 通用多语言检测 + 识别（**默认**） | det + rec ≈ 31MB | RapidAI / RapidOCR，**内置在 APK** |
| **PP-OCRv6 medium** | 高精度检测 + 识别 | det + rec ≈ 132MB | 下载 |
| **PP-OCRv5** | 通用中日英检测 + 识别 | det + rec_zh ≈ 21MB | 下载 |
| **PP-OCRv5 多语言** | 英 / 韩 / 俄专用识别模型 | 7.5 / 12.9 / 7.7MB | 下载（可选） |
| **manga-ocr** | 日漫竖排文字专用 | encoder + decoder ≈ 134MB | HuggingFace（可选下载） |
| **RT-DETR-V2** | 文字 / 气泡检测 | ≈ 11MB | HuggingFace（可选下载） |

- **按需下载，控制包体积** — 只内置 PP-OCRv6 small，其余模型在模型管理页按需下载；下载器支持断点续传、自动重试与 MD5 校验
- 下载页按「引擎组合」分组，未下载的组合会置灰并提示需要哪些模型
- PP-OCRv5 / v6 的检测参数（置信度阈值、扩展比例、大框过滤等）可在调试面板实时调节

---

## 自动翻译与稳定性检测

跨场景共用的自动触发逻辑：

- **像素驱动检测** — YIQ 感知色彩差异，跳过稳定页面重复 OCR
- **翻页稳定性检测** — 检测到画面变化后等画面静止再触发，适合游戏 / 漫画翻页
- **实时字幕模式** — 关闭稳定性检测，画面一变立即翻译，适合视频字幕
- **内存 LRU 缓存** — 最近 20 条译文直接返回，命中带 ⚡ 标记
- **参数可调** — 像素变化阈值、检测间隔均可自定义；漫画模式另有 pHash 自动翻页（停稳约 1 秒触发）

---

## 翻译历史

<p align="center">
  <img src="images/history_page.gif" width="260" alt="历史记录页面">
  &nbsp;&nbsp;
  <img src="images/history_retranslate.gif" width="260" alt="历史记录重新翻译">
  <br>
  <em>历史记录页面 &nbsp;|&nbsp; 历史记录重新翻译</em>
</p>

- **双视图** — 默认视图（按修改时间排序）+ 管理视图（按进程分组）
- **全屏翻页浏览** — 原图 + 译文详情面板 + 原文 / 译文切换 + 尺寸变体切换
- **重翻** — 加载原始截图 → 重新 OCR → 翻译 → 渲染，替换原变体，无需重新启动翻译服务
- **打包下载** — 按进程组导出 ZIP 到系统「下载」目录（写失败时回退系统文件选择器）
- **智能分组** — 同 pHash 页面自动归组，多尺寸变体可切换
- 缓存命中时直接从缓存渲染 overlay，不重新识别与翻译

---

## 截图架构

| 模式 | 特点 | 适用 |
|------|------|------|
| **MediaProjection**（默认） | 弹窗授权，门槛低，重启后需重新授权 | 日常使用 |
| **AccessibilityService** | 手动开启，授权长期有效 | 频繁使用 |

通过 `ScreenshotProvider` 接口抽象，`ScreenshotManager` 单例解耦生产者与消费者，游戏与漫画共用同一套截图架构；截图前会自动隐藏悬浮球与进度提示，避免把它们截进图里污染缓存。

---

## 其他功能

- **首次启动引导** — 权限申请 + API 配置引导
- **深色模式** — 跟随系统 / 浅色 / 深色三态切换，全 app 界面与弹窗适配（阅读器与译文悬浮窗保持自身配色）
- **开发者选项** — 4 个引擎的调试浮窗与参数实时调节、缓存命中标记开关
- **FAQ 页面** — 常见问题，含 PP-OCRv5 调试面板参数详解
- **检查更新** — GitHub Releases 自动检测，支持直接下载或走百度网盘 / 夸克网盘
- **应用内公告** — 启动时自动检查开发者公告
- **日志系统** — 统一日志收集（LogCollector），可一键导出反馈

---

## 下载

| 方式 | 链接 |
|------|------|
| GitHub Releases | [最新版本 v0.11.2](https://github.com/xujunjiex/StarFlow/releases/tag/v0.11.2) |
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

- `app/src/main/cpp/`（约 49MB 源码）通过 CMake 编译：**llama.cpp**（Hy-MT2 设备端推理）+ ONNX / sentencepiece 桥接。首次构建慢，之后增量
- **PP-OCRv6 small 内置**在 `assets/`，开箱即用；PP-OCRv5 全系、v6 medium、RT-DETR-V2、manga-ocr、Hy-MT2、NLLB 都是运行时按需下载（模型管理页）
- release 构建开了 `minifyEnabled` + `shrinkResources`，JNI 回调接口由 `proguard-rules.pro` 的 `-keep` 保护 —— **改动 native 回调接口名要同步改 keep 规则**，否则 debug 正常、release 闪退

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
