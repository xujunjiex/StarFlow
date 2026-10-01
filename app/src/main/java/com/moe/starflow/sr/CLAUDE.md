# 超分（`sr/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑）
> 都在根文件里，**动这个目录前先保证读过根文件**。

## 这个包是什么

漫画阅读器 / 截图翻译链路里的"把图放大或变清晰"这一步。**两条互不依赖的路线**：

| 路线 | 实现 | 要不要下载 | 会放大吗 | 定位 |
|---|---|---|---|---|
| **超分模型 · ncnn（GPU）** | `NcnnSrEngine` → `:sr` 模块（ncnn + Vulkan，2x/4x） | ✅ 要（模型管理页「超分」Tab） | ✅ 2x / 4x | **主力**：waifu2x / SRMD / Real-CUGAN / Real-ESRGAN 四个引擎族 |
| **超分模型 · ONNX（CPU）** | `AnimeJaNaiEngine`（ONNX Runtime，2x） | ✅ 要 | ✅ 2x | AnimeJaNai 专用；纯 CPU，模型小所以仍然快 |
| **Anime4K 基础层** | `anime4k/Anime4kEngine`（GLES3 shader） | ❌ 不要（9 个 shader 随 APK，**398 KB**） | ❌ **不放大** | 同分辨率线条修复/锐化，零成本兜底 |

### 超分模型的两条路线（由 `downloadinfo.json` 的 `family` 字段决定）

`SuperResolutionEngines.createEngine` 只看清单里的 `family`：
- **有 `family`**（`waifu2x` / `srmd` / `realcugan` / `realesrgan`）→ `NcnnSrEngine`（**GPU**）
- **没有**（`null`）→ `AnimeJaNaiEngine`（ONNX Runtime，**CPU**）

⚠️ **引擎参数（`scale` / `noise` / `prepad`）一律从清单读，不许在 Kotlin 里写 `when(key)`** ——
同一个 key 换个档位就要改代码，必然漏改。语义各族不同：
- `waifu2x`：`noise` = 降噪档 **-1~3**；`prepad` = cunet **18** / upconv_7 **7**
- `srmd`：`noise` = 退化强度 **-1~10**；`prepad` = **12**
- `realcugan`：忽略 `noise`（引擎内 `syncgap=3`）；`prepad` = **18**
- `realesrgan`：忽略 `noise`；**`scale=4`**；`prepad` = **10**

⚠️ **ncnn 模型是 `.param` + `.bin` 两个文件**：`SrModelManager.isDownloaded` 要求**两个都在**，
`ncnnPair()` 按**扩展名**配对（不按下标 —— 清单顺序一变就会静默换错文件）。

⚠️ **绝不用 ONNX/CPU 路径跑 cunet**：实测 10–37 秒（fp32 激活把内存吃到 11 GB + swap）；
换 ncnn + Vulkan 只要 1.6–4.4 秒。**慢的是后端，不是模型。**

⚠️ **AnimeJaNai 与 realesr-animevideov3 是「视频向」模型**：它们是为视频帧设计的，
在静态漫画上保真度明显偏低（PSNR 低于双三次插值）。这是设计取向，不是 bug —— 但别把它们
当画质主力推荐给用户。详见 `tools/sr-research/超分模型选型报告.md`。

## 低分辨率漫画「译文模糊」——不是超分能解决的问题（根因已坐实）

用户口径：「导入分辨率较低的漫画时，翻译后渲染回漫画的**文字**也会变模糊」。

### 证据（两处代码事实，不是推测）

1. `manga/render/OverlayRenderer.kt:98`
   ```kotlin
   val result = original.copy(Bitmap.Config.ARGB_8888, true)   // 尺寸 == 原图
   val canvas = Canvas(result)
   ```
   → 气泡白块与译文**栅格化在「源分辨率」的位图上**。800px 宽的页，字形就按 800px 的尺度烧进像素。
2. `ui/viewer/ZoomableImageView` 通过 `imageMatrix`（`postScale` / `fitCenter`）缩放**整张位图**。
   → 低分辨率页被拉到屏幕宽（800 → 1080 就是 ×1.35）时，**文字是跟着插值放大的像素**，必然糊。

⇒ **超分救不了这一条**：超分跑在 OCR 之前，而文字是超分**之后**才画上去的；
即使把底图超分到 2x，文字仍按同一逻辑烧进位图、再被缩放。

### 正确修法：让文字在**显示分辨率**上栅格化

Canvas 绘制时**字形是按当前 CTM 的有效缩放栅格化的** —— 所以只要让文字在"已经被放大的坐标系"里落笔，
它天然就是清晰的，而**不需要**任何超分或位图放大。

推荐实现（改动收敛在渲染层）：

- `OverlayRenderer.renderOverlay(..., renderScale: Float = 1f)`
  - 输出位图尺寸改为 `w*renderScale × h*renderScale`
  - 开头 `canvas.scale(renderScale, renderScale)`，所有绘制坐标（气泡矩形、`LayoutEngine` 产出的行/列位置）
    沿用原坐标系 → **字号/字距全部自动跟着放大**，字形在最终分辨率上栅格化
  - 底图用 `canvas.drawBitmap(original, null, RectF(0,0,w*s,h*s), filterPaint)` 画（底图本身该多糊还多糊，
    那是图源决定的；**要救的是文字**）
- `renderScale` 取「显示宽度 / 页图宽度」（夹到 `[1f, 2f]` 防内存爆炸：×2 是 4 倍像素）
- 调用点：阅读器 `applyPageVisual` 传实际显示比例；导出/查看器传 1f（导出不吃屏幕分辨率）

⚠️ 备选方案（**不要选**）：「在 ZoomableImageView 上另叠一层 overlay View」看起来更"正统"，
但它要求把气泡 + `LayoutEngine` 计划搬到 View 层、并与 `imageMatrix` 逐帧同步 ——
改动横跨 `ReaderAdapters` / `ZoomableImageView` / 新 View / 控制器四处，而收益与上面这个
单参数改动**完全相同**（都只是让文字在显示分辨率上栅格化）。

### 注意与混淆项的区分

- 这与 `sr/` 的超分是**两件独立的事**：超分提升的是"喂给 OCR 的图"（`SrPageEnhancer`），
  这里要解决的是"**画出来的译文**的栅格化分辨率"。两条链路的产物不重叠。
- 上表实测的「缩回 PSNR」衡量的也不是这件事，别拿它论证文字清晰度。

## 截图 / 录屏链路：`SrPageEnhancer.enhanceForCapture(src, forGame)`

API 已就绪（`forGame=true` 走游戏模式开关、`false` 走漫画模式开关，两者都受总开关约束，
判据在 `SrSettings.isEnabledForGame/Comic`）。返回**与输入同尺寸**的增强图，失败/开关未开 → null。

### ⚠️ 接入位置：**裁剪之后、进 OCR 之前** —— 绝不能挂在截图入口

这是接入前必须先想清楚的一点。`MangaFloatingService.processMangaScreenshot(bitmap)` 收到的
**整屏截图同时参与 pHash 计算**（256-bit 扩展 hash，是图片缓存的键）。在那一层增强的后果：

```
入口处增强 → pHash 是在增强后的图上算的 → 缓存键整体变化
          → 用户已存的图片缓存/历史记录**全部失配**（表现为缓存再也命中不了、重复 OCR + 重复翻译）
```

而且它是**静默**的：功能看着都对，只是缓存全废、白白费电。所以调用点要找
「已经裁成 crop 区域、正要喂给检测/识别」的那一步。

也正因为本函数**保证不改尺寸**，挂在裁剪后的图上不会动 `bubbleRects` 的坐标空间 ——
同一张 crop 图既喂 OCR（裁后坐标）又参与后续渲染（同样裁后坐标），两边一致。

### 已接的落点（**都在"裁剪之后"**）

| 模式 | 位置 | 说明 |
|---|---|---|
| 漫画 | `manga/MangaFloatingService.kt` `takeScreenshotWithProvider()` 里 `cropBitmap` 之后 | 增强 `cropped` 后再 `ScreenshotData(fullBitmap, croppedBitmap)` |
| 游戏 | `translate/FloatingBallService.kt` `takeScreenshotWithProvider()` 里 `cropBitmap` 之后 | 同上，`forGame = true` |

两处都满足同一个前提，且**这是接在这里的唯一理由**：
- 裁剪图是**唯一的 OCR 输入**（下游 `ocrBitmap = data.croppedBitmap ?: data.fullBitmap`）
- **pHash（图片缓存的键）全部算在 `fullBitmap` 上**（`data.fullBitmap` 才是 `PerceptualHash.compute/
  computeExtended` 的入参，漫画服务 L1730/1732/1774/1813/1815）→ 增强裁剪图**完全不影响缓存键**
- 与 `fullBitmap` 的其它职责（几何判据、全屏原图、导出）**无交集**

顺带：增强失败/开关未开时返回原图，替换只发生在真的拿到新实例时（旧裁剪图随即 `recycle()`）。

## 阅读器「先超分再翻译」的落点：`SrPageEnhancer`

链路：`ReaderPageSource.loadFull()` → `SrPageEnhancer.enhanceForReader()` → 同一张图既喂 OCR 也用于渲染。

### ⚠️ 硬约束：增强后的图**必须与原图同尺寸**

`loadFull` 出来的 bitmap 同时喂给两个下游，这是必须先想清楚的一点：

| 下游 | 想要什么 |
|---|---|
| OCR / 检测 | 图越清晰越好（这就是上超分的目的） |
| 译文渲染 | `bubbleRects` 是**持久化坐标**，之后还会在**原图**上重渲染（`PageTranslationCodec.fromRow → renderOverlay`：`MangaViewerActivity`、导出、重开阅读器） |

**若让 OCR 跑在 2x 图上，检出的框就是 2x 坐标** → 以后按原图重渲染时整片译文**成倍错位**。
而这种错位**只有"翻回去/重开阅读器"才现形**，当页看着完全正常 —— 是最难查的一类问题。

所以 `SrPageEnhancer` 统一做 **超分 → 缩回原尺寸**：
- 收益没少：PP-OCR 本来就会把输入缩到自己的工作尺寸，决定识别率的是**信息质量**而不是喂进去的像素数；
  先放大再缩回 = 一次「去噪 + 锐化 + 去 JPEG 块效应」，低分辨率/高压缩的图源正好吃这一口
- 顺带把坐标空间、内存占用、渲染路径**全部保持不变**
- 口径与实测一致：`tools/sr-research` 的「缩回 PSNR」就是"超分结果缩回原尺寸再比对原图"

### 其它两条

- **缓存与失效**：`ReaderPageSource.enhanceCache`（按像素预算 24MB ≈ 6~8 张整页）+ **设置指纹**
  `SrPageEnhancer.signature()`（开关 | 选的模型 | Anime4K 档位）。指纹变了就 `evictAll()`。
  ⚠️ 新增任何影响输出的设置，**只要往 `signature()` 里加一项**，所有调用点自动失效 ——
  别在调用点各写一份比较逻辑（漏一处就是"改了设置画面不变"）。
- **应用级 Context**：`SrPageEnhancer` 持一个 `applicationContext`（`StarFlowApplication.onCreate` 里
  `init`）。之所以不去给 `ReaderPageSource` 加 Context 参数，是因为它的两个构造点分别在
  `MangaReaderActivity` 与 `ReaderTranslationController` —— 为了一个只读的 `getExternalFilesDir`
  去改两个大文件不划算。**只持 applicationContext，不泄漏 Activity。**

## 状态与选择的单一来源（三个东西别混）

| 关心什么 | 唯一来源 | 说明 |
|---|---|---|
| **开没开** | `SrSettings` | 总开关 / 游戏模式 / 漫画模式 / **阅读器独立**；判据走 `isEnabledFor*`，总开关与分项是 **AND** |
| **选了哪个模型 / 文件在哪** | `SrModelManager` | prefs `sr_active_model_key`；文件名取自 `downloadinfo.json`（**不在代码里硬编码**） |
| **怎么拿到能用的引擎** | `SuperResolutionEngines` | 组合上面两者 + 缓存 + 失败降级；**调用点只调它**，不要自己读 prefs |

**路线优先级（`SuperResolutionEngines.resolveRoute`，纯函数，`SrRouteTest` 钉死全 8 种组合）：**

```
下载的超分模型（2x）  >  Anime4K 基础层（1x 锐化）  >  什么都不做
```

- 两者是**替代关系，不是叠加关系**：模型可用时不会白跑一遍 Anime4K。
  （"先超分再锐化"的叠加是另一个可选策略，真要做得单独开开关，别在 `resolveRoute` 里偷偷叠。）
- `resolveRoute(srEnabled, srModelUsable, anime4kEnabled)` 的三个入参**都是已经算好的布尔量**：
  `srEnabled` 来自 `SrSettings`（游戏/漫画/阅读器各自那个），`srModelUsable` = 选了模型 **且** 文件在，
  `anime4kEnabled` 来自 `Anime4kMode.isEnabled`。之所以把决策抽成纯函数，就是为了能用一张真值表
  把它锁住 —— 这个判断散到各调用点必然出现「总开关关了还在超分」「Anime4K 和模型叠着跑两遍」
  这类**不崩不报错、只是又慢又不对**的静默错误。

⚠️ 三个开关的语义（用户口径 2026-10，别合并）：
- `SrSettings.isEnabledForGame/Comic` 用于**截图/录屏**链路，受总开关约束
- `SrSettings.isEnabledForReader` 用于**阅读器**链路，**刻意不 AND 总开关** —— 用户要求两者"各自独立生效"

## 大图预处理：先压到「短边 1080 + 引擎吃得下」再超分（2026-10 新增，`SrDownscale`）

**用户口径**：「对较大尺寸的图片进行超分，短边压缩对齐到 1080p 再超分……减轻超分模型的压力，
同时可以快速通过超分模型放大尺寸」+「**短边超过 1080 的压缩尺寸后给超分的图片**，体积不能超过
原来的像素和大小」。

### ⚠️ 只压**输入**，产物原样落盘

| 对象 | 约束 | 谁保证 |
|---|---|---|
| **喂进引擎的压缩图** | 像素/体积 **≤ 原图** | `SrDownscale.plan`（只做等比缩小 `k < 1`，构造性成立） |
| **超分产物** | **不做任何额外缩放**「超分后肯定比原图大啊」 | —— 空间由两道**既有**的闸管：引擎输出上限 10MP + `SrStore` 总容量 LRU（512MB） |

⚠️ **这里栽过两次，别再栽第三次**：
1. 第一版把产物收敛到「像素数 ≤ 原图」→ 把刚超分出来的细节又丢掉、2x 白跑；
2. 第二版改成「产物 ≤ 2× 原图像素」→ **自相矛盾**：小图（0.58MP → 2x 后 2.3MP = 4 倍）被砍掉一半，
   大图（6MP → 压缩 → 7MP = 1.17 倍）一刀不砍 —— 砍的全是**最需要放大**的低分辨率页。
根因是把「节约空间」当成了**单页尺寸**问题，而它其实是**总容量**问题。
守卫：`SrReaderWiringTest.theUpscaledProductIsStoredAsIs`（源码级：不许再出现 `clampTo` / `outputBudget`）。

### ⚠️ 只对齐短边是**不够的**（实现时实测出来的，最关键的一条）

`NcnnSrEngine.maxInputPixels = 10MP / scale²` —— **2x 档只有 2.5MP、Real-ESRGAN 4x 只有 0.625MP**，
超了引擎直接判「图太大」跳过。所以：

| 页 | 改这一版之前 | 现在 |
|---|---|---|
| 2000×3000（6MP，很常见） | **直接超限失败**，用户只看到一句「图太大」 | 压到 1080×1620 → 超分 → 收敛回 ≤6MP |
| 1080×2592（2.8MP） | 超限失败 | 按上限继续压到 ≤2.5MP |
| Real-ESRGAN 4x 上的任意页 | 0.625MP 上限下几乎必失败 | 按引擎上限压到 ≤0.625MP |

⇒ [SrDownscale.plan] **两个约束一起解**：先按用户口径把短边对齐 1080，再按引擎的
`maxInputPixels` 继续等比缩到吃得下为止；两个都不需要缩时返回 null（普通漫画页**零行为变化**）。
引擎上限由 `SuperResolutionEngines.inputPixelLimitForReader(context, prefs)` 给出
（取不到 → 0 → 跳过压缩，真正的原因仍由 `applySteps` 报）。

- ⚠️ **取整必须向下再兜一层**：`roundToInt` 会把 1020.6 抬成 1021，对 1080×2592 这种正好卡在
  上限边缘的尺寸就是「压完还差 429 像素」→ 引擎照样拒绝、白压一场（单测抓到的）。
- **不损失画质**：只在内存里用 `Canvas` + `FILTER_BITMAP_FLAG` 缩放，**不重新编码**（没有 JPEG 二次
  损失）；且**分步减半**再收到目标 —— 一步 2.8 倍下采样会漏采样，在网点/线条上出现摩尔纹。
- **压缩图 ≤ 原图** 由 [SrDownscale.plan] 构造性保证（k < 1）；**产物不做任何缩放** —— 见上面那张表。
- ⚠️ **产物尺寸允许 ≤ 原图** → `SrProcessor` 里那句「产物没放大就判失败」的判据必须比
  **喂给引擎的那张图**（`feed`），不能比原图 `src` —— 比 `src` 会把正常的超分结果判成"没放大"而白跑。
  Anime4K 的 1x 仍然被这条拦住（它的产物宽度 == feed 宽度）。
- ⚠️ `feed` 是本函数造的，用完**立刻回收**；`src` 归调用方，绝不能动（`feed !== src` 才回收）。

## 超分与 OCR 的锁：**OCR 优先**（2026-10 新增，回答"超分没结束下一个 OCR 就启动了怎么办"）

两者共用 `OcrLock`（超分推理是本地重计算，并行只会互相抢核）。问题是超分单页可以跑几秒到几十秒，
而 **OCR 侧等锁有超时**：章节批量路径的 `acquireOcrLockWithWait` 等满 60s 就把那一页记成失败
（`PROCESS_EXCEPTION`「OCR 引擎忙」）→ 表现为**成片地"什么都没翻、页却失败了"**。
更糟的是**饿死**：超分是轮询抢锁、离锁最近，一放锁就回头抢，排队的 OCR 永远轮不上。

修法：`OcrLock.beginOcrDemand()` / `endOcrDemand()` / `hasOcrDemand()`（**计数器**，多个 OCR 页可同时在等），
超分的等锁条件变成 `while (OcrLock.isRunning || OcrLock.hasOcrDemand())`：

- **正在跑的这一次超分不打断**（不等它跑完没有意义），但**下一次一定让给 OCR**；
- 需求窗口**只包 OCR 阶段、不包整章任务**（`ReaderTranslationController.ocrPhase` 用 `try/finally` 包住，
  里面十几处 `return` 靠这一层构造性保证配对）—— 包住整章会让"整章翻译期间一次超分都跑不了"，
  与用户要的「超分要和翻译中一起出现」直接矛盾；
- `endOcrDemand` **多减不变负**：变负会让后续 `hasOcrDemand()` 永远为 true → 超分**永久让路**。

守卫：`OcrLockTest`（计数器语义 / 不变负 / 与持有状态独立）。

## 超分记录系统（2026-10 新增）

**用户口径**：「给超分面板也设计一个类似翻译面板那样的记录系统……显示超分的完成/进行中/失败状态，
支持超分本章、查看详情（超分模型、原始和超分后尺寸和大小、时间）、提示系统」。

| 层 | 位置 | 说明 |
|---|---|---|
| 表 | `data/ImportedPageSr`（`imported_page_sr`，**DB v19**） | 状态 / 模型 / 原始与产物的尺寸与字节 / 时间 / 失败原因 / 身份指纹 |
| 写 | `ReaderTranslationController.runSr` | 开跑写 `RUNNING`，收尾写结果（**尺寸模型字节从磁盘产物读回**，不从内存猜） |
| 读 | `ReaderTranslationController.srRecords()` / `srSuccessCount()` / `srChapterJob` | 面板订阅 |
| UI | `ReaderSrStateAdapter` | **复用翻译面板的布局与交互** |

- ⚠️ **真值仍然是磁盘上的文件**（`SrStore.exists`）：这张表只记"过程与元数据"，
  所以文件被系统清掉不会让表变成谎言。**不要拿它的 state 决定"要不要渲染超分底图"**。
- ⚠️ 进阅读器要 `srDao.resetRunning()` 清残留的「超分中」（与译文侧 `resetTranslating` 同一条理由：
  退出/崩溃会让那一页永久卡在进行中，面板也再点不动）。
- ⚠️ 删除有**两种语义**，别混：面板行内「删除」`dropRecord = true`（连记录一起清）；
  右下角那枚删除按钮走默认 `false`（产物删了但记录退回「未超分」，面板上仍能看到"这页超过"）。
- **「超分本章」不走 `ChapterJobRunner`**：那个 runner 的价值是「准备串行 + 翻译并发 N」，
  而超分**本身就是全局串行**的，套上来只会多一层永远不起作用的调度 + 一个假的两阶段接口。
  一个 for 循环逐页跑，**逐页取图逐页回收**（2x 一页 40MB，整章必 OOM）。
- ⚠️ **离开阅读器必须 `cancelSrChapterJob()`**（`onReaderClosed`）：超分本章**没有通知栏入口**，
  而它的进行中提示是**系统级浮层窗口** —— 跟着跑到后台会把芯片贴到别的应用上，
  而 `unbindUi()` 刚把 `onSrProgress` 清成空 lambda，再没人能收走它们。
  （章节翻译相反：它有前台服务 + 通知栏，所以刻意允许后台继续跑。）
- **互斥**（用户口径：「不能出现两边都开启批量翻译某章和超分某章，要有互斥反馈和提示」，
  2026-10-01 扩到**四种状态**）：判据收敛成一个 `ReaderTranslationController.busySource()`
  （`CHAPTER_SR` ＞ `CHAPTER_TRANSLATE` ＞ `PAGE_MODE` ＞ `NONE`），控制器里两道（防别的调用点绕过）
  + 宿主各点击入口都**必须给提示**，不静默。详见 `mangaimport/CLAUDE.md` 的「互斥」一节。
- ⚠️ **「超分本章」不受「翻译时自动超分」开关（`sr_reader_auto`）影响**（用户口径 2026-10-01：
  「这个开关只影响手动/自动/增量这三个模式」）—— `startSrChapterJob` 与单页 `srActionOf`
  都只认总开关 `sr_reader_enabled`。反过来说，「翻译本章」也不再顺带自动超分（那条只在**增量**上）。
- ⚠️ **「同一模型已经超过就不重超」的判据只有一处**（`srAlreadyCurrent`）：以前只有手动/自动路径判，
  章节批量每页都无条件重跑一遍并覆盖落盘 —— 白算之外还要抢 `OcrLock`，把 OCR 串行一路卡住。
  守卫：`SrRecordSystemWiringTest`（断言两处调用点用的是同一个判据）。

## 硬约束（踩过或必须知道的）

- ⚠️ **超分持 `OcrLock` 必须用「带令牌的 acquire/release + 锁内心跳」**（2026-10 审查）：SR 推理
  （ncnn/Vulkan，重档位分钟级）远超 `OcrLock.STALE_TIMEOUT_MS`(30s)。无令牌 `release()` 无条件清零
  → 会放掉**别人**（自愈期间刚拿到锁的那位）的锁；不打心跳则被 `maybeRecoverStale()` 判成"持有者已死"
  强制释放 → OCR 与超分同时在跑（正是这把锁存在的唯一目的）。**别改回 `tryAcquire()` / `release()`**。
  小说侧 `NovelTranslationQueue` 的本地引擎整批同理（`withLockHeartbeat`）。
- ⚠️ **无 Vulkan 设备时 SRMD / Real-ESRGAN 必须判「不可用」**：只有 Waifu2x / RealCUGAN 有 CPU 路径
  （`gpuid = -1`）；另两族的 `vkdev` 会是空指针（srmd.cpp:180 / realesrgan.cpp:191），
  `process()` 直接 SIGSEGV 且 Kotlin 层捕获不到。`sr_jni.cpp` 的 `create()` 按 `familyHasCpuPath()`
  分流 —— 别再无条件 `gpuid = -1`（11 档里有 3 档属这两族）。
- ⚠️ **`create()` 里那道"模型文件健全性检查"不是多余的**：vendored 四个引擎的 `load()` 都忽略 ncnn
  的返回值、恒定 `return 0` → `if (e->load(...) != 0)` 是**死代码**，不查文件的话 `create()` 永远不
  返回 0，「0 表示失败」的契约形同虚设（模型损坏时每页白跑一遍 Vulkan 初始化再报错）。
- ⚠️ **上屏译图倍率按「显示宽 / 页图宽」算**（`ReaderTranslationController.renderScaleFor`，
  夹在 `[1f, MAX_RENDER_SCALE]`），**不要写死 2f**：输出是 `页宽 × renderScale`，×2 就是 4 倍像素，
  而多数漫画页本来就比屏幕宽（倍率会夹到 1）。**导出与 Webtoon 预热恒传 1f**
  （导出不上屏；Webtoon 源图已按屏宽采样解码）。
- ⚠️ **超分模型目前只有 ncnn 一条路可达**：11 个目录模型在 `downloadinfo.json` 里全带 `family`，
  `SuperResolutionEngines.createEngine` 必走 `createNcnnEngine` —— `createOnnxEngine` /
  `AnimeJaNaiEngine` 是**当前不可达**的（要加回不带 `family` 的 AnimeJaNai 档才会启用）。
  所以那边的 ONNX session UAF、`halfToFloat` 次正规数换算等问题属**潜伏项**，不是线上 bug。

- **超分失败一律静默降级**：引擎的 `upscale()` 返回 `Bitmap?`，任何一环不满足（没选模型/文件缺失/
  尺寸超限/推理异常/OOM）都返回 null，调用方直接用原图。**绝不因为超分失败让用户翻不了页或翻不了译**。
- **输入像素上限必须有**：`AnimeJaNaiEngine` 2.5 MP、`Anime4kEngine` 1.5 MP。2x 之后输出 float 是输入的
  4 倍（AnimeJaNai）／中间纹理是 RGBA16F 且链长最多 49 趟（Anime4K），不限幅就是 OOM。
- **AnimeJaNai 的 H/W 必须补到 16 的倍数**：SPAN 图里有 `Mod`/`Shape`/`Reshape` 做的内部分块（unshuffle），
  不是 16 倍数时最后一块读越界。补齐用**边缘像素复制**，不要补黑边（黑边会被放大成可见边框）。
- **模型是 fp16，ORT CPU EP 靠插 Cast 跑**：能跑但有额外开销。真机上若明显慢，才考虑转 fp32 / 量化
  （本机验证过可行，见 `tools/sr-research/quant_test.py`）。
- ⚠️ **Anime4K 在本管线里不放大**（输出尺寸 == 输入尺寸），根因是 `Anime4kCompiler` **刻意忽略 `//!WHEN`**
  ＋ `OUTPUT` 恒等于输入尺寸。**这是有意保持参考实现行为，不是漏写**；要让它真放大，得把 `WHEN` 求值与
  真正的 `OUTPUT`（显示目标尺寸）语义**一起**实现，只做一半会更怪。`Anime4kCompilerTest` 钉死了这个契约。
- ⚠️ **Anime4K 的 EGL 上下文绑定线程**：所有 GL 调用必须在同一线程。`Anime4kEngine` 用单线程 executor
  串行化，并且 **`initialize()` 的失败分支必须调 `releaseOnGl()` 而不是 `release()`** ——
  后者会再 submit 回同一个单线程 executor 并 `.get()`，**自己等自己 → 死锁**。
- ⚠️ **Anime4K 中间纹理必须及时释放**：`Anime4kEngine` 预计算「每个 pass 之后还会被谁 bind」
  （`laterUses`），名字被覆盖且后续不再用就立刻 `glDeleteTextures`。不做这步，mode A 的 49 趟会把
  每趟中间纹理全留在显存（一张 1280×1854 的 RGBA16F 就是 19 MB）→ 必 OOM。
- Anime4K 的**片元着色器契约**：主体必须定义 `vec4 hook()`（编译器固定追加 `outColor = hook();`），
  可用宏 `MAIN_*` / `<BIND>_*` / `HOOKED_*`。资产在 `assets/anime4k/`（MIT，bloc97/Anime4K）。
- **档位在阅读器调色面板**（`sheet_reader_menu.xml` 的 `btn_anime4k`），点一下循环切档。
  循环顺序收在 `Anime4kMode.nextAfter`：**关闭 → C → CA → B → BB → A → AA → 关闭**（由弱到强）。
  ⚠️ **「关闭」必须在循环内** —— 否则用户开了之后只能去个性化页关，等于关不掉。
  `Anime4kCompilerTest` 用一条不变式钉死它：从关闭出发一路 `nextAfter`，每个档**恰好经过一次**且最终回到 null。
- 切档后必须让宿主 `invalidateRenders()` 重渲染当前页（`ReaderMenuSheet` 的 `onAnime4kModeChanged`
  → `MangaReaderActivity.invalidateRenderInputsAndReRender`，与字号变更**共用**这一个函数）：
  档位改的是**渲染的输入（底图）**，作废增强缓存靠 `SrPageEnhancer.signature()` 自动完成。

## 加一个超分模型要改哪几处

1. `download/ModelKey.kt` 加 key（**stableId 不许重复**，SR 段用 3040~3080）
2. `assets/models/downloadinfo.json` 加条目（**`file_name` 可与 URL 文件名不同** ——
   waifu2x 的 cunet 与 upconv_7 上游**同名**，必须各自改名，否则**互相覆盖**；
   ncnn 模型还要带 `family` / `scale` / `noise` / `prepad` 四个字段）
3. `SrModelManager.allKeys` + `nameResOf`
4. `ModelManagementFragment.srFamilies` + `srExpectedSize`（+ 布局 XML 的 `<include>` 行 + 中英字符串）
5. **三个穷尽 `when` 补分支**：`ModelDownloadRepository.baseDirFor`、`ModelDownloadService.baseDirFor`
   与 `ModelDownloadService.displayName` —— 不补直接编译不过
   ⚠️ **`displayName` 的 SR 分支不许写死文案**：下载通知里的名字必须来自
   `SrModelManager.nameResOf(key)`（→ `R.string.sr_model_*`，中英各一份）。这里曾硬编码英文
   → 中文界面下下载通知是英文，而且 N3（极强降噪）被写成 "Light denoise"（**错档**，
   与设置页/管理页显示的档位名对不上）。自检脚本已加这条断言。
   ⚠️ 两个 `baseDirFor` 是**两份逐字一致的副本**（Repository / Service 各一份）：
   曾把 `SR_W2X_UP7_PHOTO_M1` 在其一里写了两遍、看着像"少了一档"。
6. 若是新引擎类型，在 `SuperResolutionEngines.createEngine` 加分支

⚠️ **改完务必跑接入一致性自检**（它会交叉校验上面 6 处 + 布局 id + 字符串，一次抓出漏改）：
```bash
python tools/sr-research/verify_sr_wiring.py
```
（同一份清单在**枚举 / JSON / allKeys / nameResOf / 布局 / Fragment / 两个 baseDirFor / displayName /
srExpectedSize / 中英字符串**里各有一份副本，靠人眼对是必错的。）
⚠️ 脚本里**不许写死档数**（曾经写 `== 28`，模型精简到 13 档后 4 项假失败 → "全绿"变"永远红"，
下一轮就会当噪声忽略）。真正的不变式是**跨来源一致**：枚举 == JSON == allKeys == 布局行 ==
两个 baseDirFor == nameResOf == srExpectedSize。

**超分页底部说明（用户口径 2026-10）**：模型管理页「超分」Tab 最下面必须写清**模型存放位置**与
**注意事项**，用现成两条字符串 `sr_low_res_tip`（只对真正低分辨率有效）+ `sr_storage_note`
（`files/sr/` 模型目录、`files/sr_cache/` 超分结果、超分只对漫画阅读器生效）。
⚠️ 这两条**曾写好却从未接进布局**：上一轮"改好了"改的是 `sr_group_*` 那批**没人引用的死键**
（布局真正引用的是 `sr_w2x_group_desc` / `sr_cunet_group_desc` / `sr_srmd_group_desc` /
`sr_cugan_group_desc` / `sr_rsrgan_group_desc`）。现在由
`ModelManagementLayoutTest.superResolutionGroupsAndRowsAreComplete` 断言它们**真在布局里**且文案含 `files/sr/`。
改目录（`SrModelManager.SR_DIR` / `SrStore.DIR_NAME`）要同步改这两条文案。

### `:sr` 原生模块（ncnn + Vulkan）

- 位置 `sr/`，与 `:llamacpp` 同构：**只放 JNI 门面**（`SrNcnnNative`），引擎选择/参数都在 :app
- 内含 ncnn（**源码自编**）+ glslang + 4 个引擎（`waifu2x.cpp` / `srmd.cpp` / `realcugan.cpp` / `realesrgan.cpp`）
- **为什么源码自编**：官方预编译 SDK 用新版 NDK 编，引用 `std::__ndk1::__libcpp_verbose_abort`，
  本项目 NDK 25.2 的 libc++_shared 不导出该符号 → 链接失败。自编还顺带关掉 OpenMP、体积 9→7.43 MB
- 首次 clone 后必须跑 `pwsh sr/setup-ncnn.ps1`（`ncnn-src/` 不入库）
- **⛔ 别在装着 app 的设备上随便跑 `connectedDebugAndroidTest`**：AGP 的 connected 流程会
  **卸载被测 app**（连带删掉 `/data/data/` 与外部数据目录）。2026-09-30 就这么把测试机上的
  书架与模型数据删干净过一次。

## 回归守卫

- `Anime4kCompilerTest`（11 例）：pass 解析 / `WHEN` 被忽略 / 片元着色器契约 / RPN 尺寸求值 /
  预设 id 稳定性 / **预设引用的 shader 资产必须真实存在** / **面板循环不变式（每档恰好一次 + 能回到关闭）**
  / 首次点开是最保守档 / 档位标签互不相同
- `SrRouteTest`（6 例）：路线优先级全 8 种组合真值表 + **阅读器开关与总开关各自独立**
- `ModelManagementLayoutTest`：超分 Tab 结构。**超分行是静态 XML `<include>`（不是动态 inflate）** ——
  文件头的旧注释说"行由代码 inflate"是**过期的**，别照它改；行根 id 在这里与 Fragment 逐一对齐
- `NcnnSrEngineDeviceTest`（`app/src/androidTest/`，**仪器化**）：真机校验原生库能加载、能看到 Vulkan、
  推理输出尺寸正确且**内容非常量**、释放后安全返回 null。JVM 单测覆盖不到 JNI/Vulkan，只能靠它。
  ⚠️ 跑 `connectedDebugAndroidTest` **会卸载被测 app**（见上文）
- 构建期：`:app:compileDebugKotlin` 会因上面第 5 条的 `when` 不穷尽而失败

## 实测数据（选型依据）

真实漫画页 4 张（nhentai-154107 第 1/6/12/18 页，632×912）的平均「缩回 PSNR」，
以及 960×1398 页在**本测试机**（Mali-G720）上的实测耗时：

| 方案 | 体积 | 均 PSNR | 手机耗时 | 引擎 |
|---|---|---|---|---|
| SRMD srmdnf_x2 | 2.89MB | **36.43** | 6.0 s | ncnn GPU |
| SRMD srmd_x2 (n=3) | 2.89MB | 36.21 | 5.3 s | ncnn GPU |
| **waifu2x cunet · n=2** | 2.65MB | **36.11** | 4.3 s | ncnn GPU |
| **waifu2x upconv_7 动漫 · 不降噪** | **1.06MB** | **36.05** | **2.1 s** | ncnn GPU |
| swin_unet n1（**已移除**） | 16.8MB | 34.15 | 15.7 s（CPU） | ONNX |
| 双三次插值（对照） | — | 31.64 | ~0 | — |
| AnimeJaNai Sharp1 Performance | 0.71MB | 28.18 | 0.66 s（CPU） | ONNX |
| Real-CUGAN 2x | 2.46MB | 29.27 | 4.6 s | ncnn GPU |
| RealESRGAN x4plus anime 6B | 17.09MB | 27.11 | — | ncnn GPU |

⚠️ **PSNR 衡量「像不像原图」，不等于「好不好看」** —— 它奖励保守、惩罚重建。
MangaJaNai 的 PSNR 全场最低（19.2）但锐度最高（网点重建能力最强）就是明证。
**不要只凭 PSNR 判优劣**，详见 `tools/sr-research/超分模型选型报告.md` 与
`D:\xjj20\Desktop\漫画超分对比_154107\`（含 9 模型并排对比图）。

**选型结论**：日常用 `upconv_7 动漫 · 不降噪`（最快且画质并列第一）；源图有噪点/JPEG 伪影用
`cunet · 中度降噪`；追求极限画质用 SRMD（慢 3 倍换 0.4 dB）。

### 降噪档到底有什么用（2026-10 实测，可复现）

官方语义（nihui 各 README 原文，离线副本在 `tools/sr-research/official_docs/`）：
`-n noise-level  denoise level`，说明统一是 **"noise level, large value means strong denoise effect,
-1 = no effect"**；但**默认值/范围三族不同**：waifu2x `-1/0/1/2/3, default=0`；
Real-CUGAN `-1/0/1/2/3, default=-1`；**SRMD `-1…10, default=3`（语义是"退化强度"，不是"抹噪点多少"）**。
⚠️ **Real-CUGAN 的 `-n` 在 nihui 的工具里只是"选哪个模型文件"**（`up2x-no-denoise` /
`up2x-conservative` / `up2x-denoise{1,2,3}x`），引擎类**忽略该字段** —— 所以我们那三档是**三个不同文件**，
靠换文件生效（见 `REPORT.md` §3.5：koto 那边把 noiseLevel 当参数传，结果是个死参数）。

实测方法（脚本 `tools/sr-research/denoise_ladder.py`，**本机复跑即可重现**）：
上面那 4 张真实漫画页取中心 512×512 作 GT → LANCZOS 缩到 256×256 作 LR（另一组再 JPEG q55 重编码
＝"脏"LR）→ 官方 `waifu2x-ncnn-vulkan.exe` 2x 放大回 512×512 → 与 GT 比 PSNR/SSIM（4 页平均）。

| 模型 | LR | −1 不降噪 | 0 | 1 | 2 | 3 极强 |
|---|---|---|---|---|---|---|
| cunet | 干净 | **25.53** | −0.16 | −0.18 | −0.26 | **−1.58** |
| cunet | JPEG55 | 21.24 | +0.85 | +1.69 | **+1.74** | +1.75 |
| upconv_7 动漫 | 干净 | **24.67** | −0.05 | −0.39 | −0.60 | **−1.30** |
| upconv_7 动漫 | JPEG55 | 21.37 | +0.39 | +1.03 | **+1.26** | +1.25 |

（干净列是绝对值，脏列是"较不降噪的 dB 增益"）

**结论（这就是"降噪有什么用"的答案）**：
1. **降噪只在源图真的脏时才有用**：JPEG 伪影/网点噪点被 2x 放大时会一起锐化，降噪先抹掉它 →
   脏源 **+0.4~+1.75 dB**；而干净源上加降噪**只会掉分**（−0.05~−1.58 dB，因为它在抹真细节）。
2. **收益在 n≈2 就饱和**：cunet 脏源 n=2 **+1.74** / n=3 +1.75（基本持平）；upconv_7 脏源
   n=2 **+1.26** / n=3 +1.25（**n=3 反而略差**），干净源 n=3 比 n=2 多掉 0.7 dB。
   ⇒ ⚠️ **upconv_7 现在只提供「极强降噪 N3」（清单 `noise=3`），实测它是两个场景下的最差点**；
   要动就改成 N2（用户当初的口径是"要么不降噪、要么最强降噪"，现在有数据可据此重议）。
3. **cunet 比 upconv_7 耐噪**：脏源上 cunet 的增益（+1.7）明显高于 upconv_7（+1.26）——
   与族描述「cunet 更耐噪点与 JPEG 伪影、峰值在强力降噪」一致（现已实测坐实）。

完整评测见 `tools/sr-research/REPORT.md`、`REPORT_models.md` 与选型报告。
