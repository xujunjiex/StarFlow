# 翻译服务（`translate/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 自动翻译

**翻译中单击悬浮球（终止/确认语义，游戏/漫画通用）：**
- 手动翻译中单击：**无部分结果上屏 → 直接终止 + 提示「已停止翻译」**（不弹确认）；**已有部分结果**（分批渲染首批 / 本地模型流式已出字）→ **弹「停止/继续」确认框**避免误丢
- 自动翻译开启 + 正在翻译：单击**只提示**「翻译中...终止翻译请关闭自动翻译」（终止=双击悬浮球关自动翻译）；自动开启 + 空闲时单击=强制翻译当前页
- 强制关闭自动翻译时翻译在途 → 同样丢弃部分结果不保存
- 标志：漫画 `partialRenderShown` / 游戏 `partialResultShown`（渲染上屏才置位）；`translationCancelled` 贯穿保存路径，取消后不写库

### 游戏翻译（像素驱动）

**核心文件：** `AutoTranslateEngine.kt`（状态机）、`FloatingBallService`（主服务）、`GameOcrEngine.kt`（OCR 封装）、`PixelCompare.kt`（像素比较）、`GameDebugOverlay.kt`（调试浮窗）

**悬浮窗语言切换：**
游戏和漫画模式的悬浮菜单都支持运行时切换源语言。
- **循环仅在常用的 4 个源语言间转：中(繁)zh-TW → ja → en → ko**（`OcrEngineGroup.FLOATING_COMMON_LANGS`）；完整源语言目录只在主页可选
- **逻辑统一收敛到 `OcrEngineManager.cycleFloatingSourceLang`**，游戏/漫画共用一份实现（原两个 `cycleSourceLang` 已删）
- 只循环「常用 ∩ 当前 OCR 组支持」的语言，跳过不支持的（V6 不支持 ko 则循环不含 ko；RT_MANGA 仅 ja → 无可切换返回 null 提示「无可用的 OCR 模型」）
- 悬浮窗与主页**读写同一个 `Source_Language` pref**，一一对应；悬浮窗切换后主页选中同步更新
- 自动翻译时禁用切换，显示提示
- 切换后不关闭菜单，显示新的语言名称
- 实时生效：切换后下次翻译使用新语言
- 主页语言切换限制已移除，翻译运行中也可在主页切换语言

**状态机：**
```
IDLE（跳过OCR）──像素变化──→ CHANGED ──稳定1帧──→ STABLE_1 ──稳定2帧──→ STABLE_2（触发OCR）→ IDLE
```
- IDLE：翻译完成后进入，像素不变则跳过 OCR（节省性能）
- CHANGED：像素和上帧不同，重新计数
- STABLE_1/STABLE_2：连续稳定帧，达到 2 帧触发 OCR
- 稳定性检测可关闭：关闭后像素变化立即触发 OCR（适合视频字幕）

**LRU 缓存：** `LruCache<String, String>(20)`，OCR 文本精确匹配，命中直接返回翻译结果

**像素比较（PixelCompare）：** YIQ 感知色彩差异（移植自 pixelmatch），`diffThreshold` 控制页面变化判定（默认 1%）

**设置：**
- `Game_Pixel_Similar_Threshold`：像素变化阈值（默认 1%）
- `Game_Pixel_Check_Interval`：检测间隔（最低 300ms）
- `pixel_stability_check`：翻页稳定性检测开关

**OCR 引擎（游戏模式）：**
- MLKit(0)、PP-OCRv5(1)、manga-ocr(2)、PP-OCRv6(3)
- 切换顺序：v5 → v6 → MLKit → manga（`FloatingBallService.engineCycle` 数组 + `engineLabel()` 统一标签映射 —— **两个符号都在 `translate/FloatingBallService.kt`，不在 `GameOcrEngine` 里**）
- MLKit 和 PP-OCRv5 固定使用直接合并（不保留换行）

**竖排读取方向（`Game_Text_Direction`）** —— 与漫画的 `Manga_Text_Direction` 是**独立两项**：
- 个性化页「翻译结果设置」；`FloatingBallService.readVerticalDirection()` 每次识别**现读**
- **只对 PP-OCRv5/v6 生效**；ML Kit / manga-ocr 不接（理由见漫画一节）
- 生效链：`GameOcrEngine.recognize(bitmap, dir)` → PP 路径 `OcrResult.toReadOrderText(dir)`
  → `PPOcrDetGeometry.sortDetCandidates(boxes, scores, dir == LR)`
- ⚠️ **两模式的「生效半边」不同，排查时最容易在这里走错路**：

  | 模式 | 谁把方向落进 `verticalScanFlowIsLr` | 结果 |
  |---|---|---|
  | 漫画 | `PPOcrV5/V6Engine.refreshParams()` 读 `Manga_Text_Direction` | det 序与最终序一致 |
  | 游戏 | **无人**（`refreshParams` 只读漫画 key） | det 内部用漫画方向，最终序由 `toReadOrderText` 按游戏方向**再排一次**覆盖 |

- ⚠️ **已知未修**：游戏模式下 `Game_Text_Direction` 进不到 `verticalScanFlowIsLr`。
  当前被 `toReadOrderText` 覆盖，所以**最终文本顺序是对的**，但 det 与最终是两套方向 ——
  两个设置相反时内部是打架的。修法是把方向**显式传进 `runOCR`**（而不是让它自己读 prefs），
  未做。排查时若出现「设置了却像按另一个方向读」，先查这里。

**调试浮窗（GameDebugOverlay）：** 关于页面开启，显示状态 + 像素差异 + 耗时，点击展开日志面板（最近 20 条，自动去重）

**翻译结果容器（TranslationResultView）：**
- 继承 `FrameLayout`，包含 TextView + 锁定按钮（左上角）+ 关闭按钮（右上角）
- 默认解锁状态可拖动，锁定后不可拖动
- 关闭后再次点击悬浮球：有缓存显示缓存，无缓存触发新翻译
- 自动翻译中临时关闭后，下次翻译自动恢复显示
- 自动翻译时不能关闭悬浮球
- 可穿透性：⚠️ 结果容器本身**没有 alpha 开关**（原来这条写的是"通过 alpha 控制"，代码里查无实现）。半透明是**悬浮球**自己的 `Floating_Ball_Alpha`；漫画侧的"穿透"是复制模式的触摸分支（`isCopyMode`）

**悬浮球长按延迟：** 默认 300ms（`FloatingBallConfig.LONG_PRESS_DELAY`）

**悬浮球手势自定义：**
三个手势（单击/双击/长按）分配不同动作，互斥配置（不能重复）。
- `Constants.BallAction`：TRANSLATE(0)、MENU(1)、AUTO_TRANSLATE(2)、**CLOSE_FLOATING(3)**
- 存储：`SharedPreferences` String 类型（`Ball_Gesture_Single_Click` 等）
- 读取：`prefs.getString(key, "0").toIntOrNull() ?: 0`
- 配置 UI：`PersonalizationConfig` → 悬浮球分类下 3 个 ListPreference
- 选择时自动互换冲突项（如单击=翻译改为菜单，原菜单的手势自动变为翻译）

**自动翻译框选前置：** 启动自动翻译前必须先框选翻译区域（`mRectF != null`），未框选时提示"请先框选翻译区域"。

## 状态浮层 `TranslationStatusOverlay`（`translate/`，**全应用共享**）

进程级单例 + `TYPE_APPLICATION_OVERLAY` 系统窗口，游戏/漫画/无障碍/阅读器/小说共用同一个浮窗
（多条消息在竖直容器里堆叠，最多 3 条，超出排队）。位置与时长读 prefs：`Status_Position`
（top/center/bottom）、`Status_Duration`（默认 2000ms），总开关 `status_overlay_enabled`。

| API | 语义 |
|---|---|
| `show(text)` | 追加一条（autoDismiss） |
| `showImmediate(text, autoDismiss)` | **替换**最顶部一条（进度/状态；`autoDismiss=false` = 常驻到被替换） |
| `showError(text, autoDismissMs = null, sticky = false)` | 红底、可点击复制。**默认常驻**（翻译失败等"等用户读完"）；`autoDismissMs` 到点自动消失（「说完就关页面」的报错，如阅读器本地文件丢失）；`sticky` 登记进 `stickyChips`，`dismiss()` 清屏时**保留**它（否则 `onDestroy` 那次清屏会把刚发的报错吃掉） |
| `showSticky(text)` | 普通提示，但存活期内不被 `dismiss()` 清掉（转屏这类通知） |
| `showRunning(text, toastIfUnavailable = false)` / `removeRunning(id)` | 追加一条**「进行中」芯片**（不替换顶部、不自动消失），返回句柄供**精确移除**。给「正在超分…」这类**必须与别的提示并存**的连续状态用。⚠️ `toastIfUnavailable` **只给用户主动点击那一路**传 true（一次点击一条，退化成 Toast 不刷屏）；批量/自动保持 false —— 那里退 Toast 会每页刷一条 |
| `setTopScreenY(screenY)` | 把提示条**顶边对齐到屏幕坐标**（像素）。**阅读器**用它在不改用户 `Status_Position` 的前提下让提示条紧贴章节胶囊下沿（`getLocationOnScreen` 实测胶囊下沿 + 2dp），`onStop` 传 null 恢复；`READER_TOP_OFFSET_DP = 66` 是**胶囊还没布局时的兜底估算**（同为屏幕坐标口径） |

- ⚠️ **参数是「屏幕坐标」，不是 `LayoutParams.y`**（2026-10-01 用户报「提示还是太靠下，一直在原地都没动」的根因）：
  `TYPE_APPLICATION_OVERLAY` 窗口会被 WMS 按系统栏/刘海**内缩**（真机 `Frames: parent=[0,138][1220,2660]`，
  即窗口 `y=0` 对应屏幕 138px），而阅读器窗口是 `layoutInDisplayCutoutMode=always` 的**整屏**窗口
  （`frame=[0,0][1220,2712]`，视图坐标 == 屏幕坐标）。宿主按 `pill.bottom` 推、浮层按窗口 y 用，
  中间差的 138px（≈42dp）就是"提示条永远比胶囊低一大截"。改这一区时**一律用 `getLocationOnScreen`**。
- ⚠️ **内缩量只能"量"，不能算**：WM 用的是 stable/override insets，阅读器沉浸全屏时
  `WindowInsets.statusBars` 会报 0 而 parent frame 仍是 138；也没有公开 API 能拿到。
  `TranslationStatusOverlay.calibrateTopOffset` 的做法是
  `内缩量 = getLocationOnScreen()[1] − 我们上次写进 LayoutParams.y 的值`（精确、跨版本、无假设），
  量到后重下发一次；每轮 `post` 一轮、上限 3 轮（`getLocationOnScreen` 可能读到尚未 relayout 的旧值）。
  守卫：`ReaderTopPillInsetTest`。
| `dismiss()` / `release()` | 清屏（保留 sticky）/ 释放容器 |

- ⚠️ **`showRunning` 存在的唯一理由是「并存」**（用户口径 2026-10：「超分执行时也要有提示，
  而且要和翻译中**一起出现**」）：现成的三条路都做不到 —— `showImmediate` 是**替换**顶部一条
  （超分一开就把「翻译中…」顶掉）、`show` 会自动消失（超分单页可跑几分钟）、`showSticky` 登记进
  sticky（翻译收尾那次清屏清不掉它，会赖在屏幕上）。所以新语义 = **追加 + 不自动消失 + 按句柄移除**。
  不进 `stickyChips`（任何一次 `dismiss()` 都会兜底收走它，防调用方漏移除）；槽位满时**优先挤掉
  最旧的同类芯片**，绝不先动普通/进度芯片（整章批量时每页都发一条，否则会把并排的进度芯片挤掉）。
  ⚠️ 芯片被 `showImmediate` / `showError` **复用改写**时会自动从句柄表摘掉 —— 否则那一页超分结束
  时会把这条已经变成进度/报错的芯片一起删掉。守卫：`TranslationStatusOverlayTest`。

- ⚠️ **`canDraw(context)` 必须由调用方判**：没给「显示在其他应用上层」权限时浮层**静默失败**
  （既不显示也不报错）→ 调用点要退回系统 Toast，否则用户"点了毫无反馈"。
  阅读器已把这条收敛成唯一出口 `MangaReaderActivity.notifyUser`（见 `mangaimport/CLAUDE.md`）。
- ⚠️ **`showImmediate` 是替换、`show` 是追加**：要换掉一条常驻的「正在…」芯片（`autoDismiss=false`）
  必须用 `showImmediate` 或先 `dismiss()`，否则新旧两条会一起挂着。
- ⚠️ 退出宿主（Service / Activity）时必须 `dismiss()`：它是**系统窗口**，宿主没了它仍会挂在别的应用上方。

## 双模式截图

截图方式通过 `Screenshot_Method` 偏好设置（关于页面选择）：
- **MediaProjection（默认）**：弹窗授权，门槛低，每次启动都要授权
- **AccessibilityService**：需要手动开启无障碍服务，永久有效

### 架构

```
ScreenshotProvider（接口）
├── MediaProjectionProvider — Shooter 截图
└── AccessibilityProvider — 无障碍服务截图

ScreenshotManager（单例）
├── screenshotFlow: SharedFlow<ScreenshotData>
└── eventTriggerFlow: MutableSharedFlow<String>
```

### 前台服务

MediaProjection 模式需要前台服务 + `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`，但**必须是「先按 SPECIAL_USE 起服务、切到 MP 才升级」**（Manifest 同时声明 `mediaProjection|specialUse`）——直接用 MEDIA_PROJECTION 起服务会 SecurityException 崩，见根文件「高频踩坑」。
FloatingBallService 和 MangaFloatingService 在 MediaProjection 模式下自动启动前台服务。

## 批量翻译的共享基础设施（`translate/batch/`，漫画 + 小说共用）

> 2026-10 新增。用户口径：阅读器里的「翻译本章」要像模型下载一样**能在后台跑、通知栏看进度、
> 可暂停/继续/取消**；漫画因为要 OCR，**OCR 串行、翻译请求并发**。

| 文件 | 职责 |
|---|---|
| `ChapterJobRunner.kt` | **纯协程调度器**（不依赖 Android，可纯 JVM 单测）：`ocr(page)` 阶段串行、`translate(page, prep)` 阶段并发 N、按章 `pause/resume/cancel`、`jobs`/`waitingPages` flow。调用方给「页号或批号」当唯一标识即可 |
| `TranslationJobs.kt` | `ChapterJobState` / `ActiveChapterJob`（通知栏用快照）/ `ChapterJobSource`（谁的快照）/ `TranslationJobRegistry`（**通知栏与前台服务的唯一数据源**） |
| `TranslationJobService.kt` | 前台服务（`dataSync`）：**每章一条通知**（进度 / 暂停 / 继续 / 取消，点通知回到对应阅读器）。⚠️ **任务状态不在服务里**，它只镜像 `TranslationJobRegistry.activeJobs`；registry 空了就 `stopSelf` |

**约定（改之前必读，漫画侧踩过的坑都在这）**
- **预取深度 = 「1 项在准备 + N 项在翻译」**：`preparedChannel` 容量必须是 **0（会合点）**。
  曾写 8 → 泵在毫秒内把**整章**从队列抽干 → 用户点暂停时队列已空、「等待」瞬间消失、暂停/取消
  拦不住已预取的项。容量 0 同时把内存（漫画每页一张全尺寸 bitmap）压在 N+1 张以内。
- **开翻前必须再确认该章仍是 RUNNING**：从预取到发出请求之间用户可能已暂停/取消 →
  暂停则把该项 **`addFirst` 退回队列**（重新成为「等待」，不计 done）、取消则丢弃（也不计 done）。
  少了这步就是"暂停/取消点了没反应"。
- **在途表按内部自增 `Task.id` 做 key，不按页号**：多章同页号/同批号会互相覆盖（任务卡死、结算错章）。
- **并发数变更延迟生效**：`ensureStarted` 在途不为空时不重建翻译工人（重建会丢掉工人手上那一项
  → 该页永不结算 → 任务永久停在「进行中」）。
- **暂停 / 取消语义**：暂停只停"取项"，队列留着（=面板「等待」）；取消丢"还没开始翻"的，**已翻好的保留**；
  **在途项「强制结束」（2026-09-28 用户口径，与手动/自动/增量打开面板/退出阅读器的强制退出同一套）**：
  `cancel()` 标记 `Task.cancelled` + `cancel()` 它**自己的 `Deferred`**（`taskJobs`，按 Task.id）——
  单项协程 ⇒ 只掐这一章、OCR/翻译工人继续服务别的章；产物一律 `discard`（宿主侧的完成回调把行退回未翻译，
  **不写译文**）；并**立刻派发收尾事件**（不等在途 unwind），`Runtime.finishedDispatched` 闩保证只派发一次。
  ⚠️ **阻塞中的 native OCR / 已发出的 HTTP 请求无法从中途打断** —— 我们只是不再等它、也不采用它的结果。
  等待态是**纯内存**的（`waitingPages` + `queuedPages`），不写库。
- **单项跑在自己的协程里**（`runTask` 用 `scope.async` + `await`，**不能**用 `launch`：单项异常会走
  未捕获处理器 = 崩进程）：取消分两种来源，必须分清 —— 这一项被单独取消（工人还活着 → `Aborted`，
  继续干别的）vs 整条流水线被取消（`shutdown`/scope 取消 → 原样抛出，循环退出）。
- **多章互不影响**：某章暂停/取消不挡别的章；每章一个 job，`onJobFinished` 走**监听器列表**
  （`addJobFinishedListener/remove`），不要用单个 `var`（会被多个宿主互相覆盖）。
- **提交顺序 = 阅读顺序**（用户口径：「即便是翻译整章，也要按照文章顺序提交请求」）：队列是 FIFO，
  并发只决定"同时在飞几个"，**不改变提交顺序**。调用方传进来的列表必须已按页/段序排好。
- **取消/收尾必须把在途项结算掉**（`onPageSettled`）：OCR 阶段被取消、`preparedChannel.send` 被取消、
  `shutdown()` 掐流水线，这三条路径都要么回报一次收尾、要么显式清在途表 ——
  漏一条那一项就永远留在 `inFlight` 里 → 任务的 done 到不了 total → **章卡片上的「暂停/取消」永远挂着**
  （用户 2026-09-27 报的正是这个症状）。`maybeFinish` 另有一层**安全网**：队列空、无在途却仍有未结算页时
  按"提前收尾"处理（记 W 级日志），宁可少报成功数也不能让任务悬着。
- **`discard` 回调**（构造参数，可选）：被退回/丢弃/关流水线时把 `product` 里的资源还回去
  （漫画每项一张全尺寸页图）。**在途项的清理只此一处**，别让宿主自己猜。
- **`inFlightPages()`**：正在 OCR/翻译中的项（页号/批号）。宿主用它做两件事 ——
  ① 面板高亮"正在提交/等待返回"的片段；② 清"残留的翻译中状态"时**排除**这些项（否则会把别人
  正在翻的那一页打回未翻译）。
- **`inFlightTasks`（flow）/ `ocrPages()` / `translatingPages()` / `queuedPages()`（2026-09-28）**：
  在途项**分三段** —— `QUEUED`（已进流水线但还没轮到识别：在 OCR 通道排队 / 泵等着交班，
  界面显示「等待」）/ `OCR`（真正在识别工人手里，**恒 ≤1**）/ `TRANSLATE`（识别一结束就切，
  界面「翻译中」，≤ 并发数）。`ChapterTaskStage` 挂在 `Task` 上，由泵（QUEUED）、OCR 工人（OCR）、
  OCR 结束（TRANSLATE）改写，`publish()` 里一起发。用户口径：「进行中的状态只包含识别中与翻译中
  两个，提示系统和历史记录要分清楚」+「只要 OCR 结束立刻就可以交给 api 显示翻译中」。
  ⚠️ **泵取页不能标「识别中」**：泵会预取，标早了屏幕上会同时出现 3 个「识别中」，
  与"OCR 串行"直接矛盾（用户 2026-09-28 追问的正是这个）。预取中的项必须算「等待」。
- ⚠️⚠️ **取消判据「谁的标志」不能共用**：`IncrementalBatchPipeline.translateWithCache` 第一句就是
  `if (host.isCancelled()) throw TranslationCancelledException()`，所以宿主（`ReaderBatchHost`）
  的 `isCancelled` 必须**由调用方注入** —— 手动/自动/增量传 `{ cancelFlag.get() }`（用户按下的停止），
  **章节批量传 `{ false }`**（章节任务靠 `ChapterJobRunner` 的协程取消来停）。
  曾经章节路径共用 `cancelFlag`，而 `startChapterJob()` 会先 `cancelEverything()` 把它置 true
  → **整章每一页都在管线第一句被判"已取消"、catch 还静默** → 进度照涨、一页都翻不出来、
  没有任何日志（2026-09-28 用户事故）。守卫：`ChapterTranslationCancelGuardTest`（源码级）。
  同理那个 catch 分支**必须记 W 日志**（静默是这次排查最大的障碍）。
- ⚠️ **`publish()` 必须在 `synchronized(lock)` 里调**（它遍历 runtimes/queue/inFlight，而别的线程正在锁里改它们）：
  泵里那次曾经在锁外 → CME/IOOBE 抛在**应用级 scope 的根协程**（无异常处理器）= 崩进程。
  同理**两条通道的 `send` 都要 catch 非取消异常**（通道被 shutdown/收尾关掉时抛 IllegalStateException）。
- ⚠️ **关闭一次性通道必须同时置 `channelsClosed = true`**（`shutdown()` 与 `finishIfIdle()` 两处都要）：
  `ensureStarted` 只认这个标志决定"要不要换新通道"，漏了它下次 `submit()` 会往**已关闭**的通道 send。
- **`inFlightPages()` / `runningOwnedPages()` 语义不同**：前者含暂停章的在途项（宿主清残留状态时排除用），
  后者只含**RUNNING** 章占住的页（宿主做"别重复翻同一页"的去重时用）。用错会让暂停章的页永远挑不到。
- **通道容量只有一个来源**（`newOcrChannel()` / `newPreparedChannel()`）：通道是一次性的，
  收尾关闭后下次提交要重建；重建处曾手写 `Channel(8)` 与声明的 0 不一致 → **第一次任务正常、
  第二次起预取整章**（「等待」页数不对、暂停拦不住）。重建必须调同一个工厂函数。
  `finishIfIdle()` 里还要 `workerJobs.forEach { it.cancel() }`：光丢引用，旧工人仍挂在**旧通道**上。
- **宿主职责**（漫画 `ReaderTranslationHub` / 小说 `NovelTranslationHub`）：应用级 scope、按书缓存、
  实现 `ChapterJobSource` 并 `register` 进 registry；**无 UI 且无任务时回收**（同时取消收集协程）。
  前台服务由宿主在任务开始时 `TranslationJobService.start(context)` 拉起。
- ⚠️ **前台服务的启动竞态**（`TranslationJobService.observeJobs`）：`start()` 与宿主的
  `notifyChanged()`（跑在 IO 线程）是并发的，服务可能先看到**空快照**就 `stopSelf()`
  → 整段任务都没有通知栏进度（2026-09-28 日志实证：任务开始 4 秒后"服务已停止"）。
  两道防线：`observeJobs()` 开头先同步 `TranslationJobRegistry.notifyChanged()`；
  且**没活动任务时先等 `REGISTRY_SYNC_GRACE_MS` 并复查**，不能没见过活动任务就自杀。
- **并发度设置是两套、不共用**：`utils/TranslationConcurrency`（漫画 2-5 默认 3；文本 1-10 默认 5）；
  **本地引擎（NLLB / LlamaCpp）一律强制 1**（LlamaCpp 是进程级共享实例，本地推理本来就不能并发）。
  ⚠️ **漫画侧的生效范围是「整章任务 + 增量模式」**（用户口径 2026-09-28）：整章走本 runner，
  增量走 `ReaderTranslationController.runWindow`（OCR 逐页串行 + `Semaphore(N)` 限并发翻译请求）；
  手动/自动同时只有 1 页，无所谓。
- **单批超长预警**（小说）：`NovelBatchWarning`（档位 2048/4096/8192/16384/32768，默认 4096）+
  `utils/TokenEstimator`；判据收敛在 `NovelChapterTranslator.translateBatch` 一处，
  后台任务没有 UI 可弹窗时**放行不拦**。
- **回归守卫**：`ChapterJobRunnerTest`（OCR 串行 / 翻译并发 / OCR 与请求重叠 / 等待项 /
  按章暂停不影响别的章 / 取消丢等待留已完成 / 收尾后可再提交 / **每页都要结算** /
  **阶段查询** / **通道重建后预取深度不失控**）、小说侧
  `NovelTranslationConcurrencyTest`、`NovelBatchWarningTest`。

### 权限请求

ScreenCapturePermissionActivity — 透明 Activity，弹出系统授权弹窗。
MediaProjectionIntentHolder — 存储授权 Intent。

### 响应时间差异

- 游戏模式：无差异（300ms 轮询）
- 漫画模式：MediaProjection 翻页后等轮询周期，AccessibilityService 翻页后 ~500ms 触发

### Shooter `convert()` 失败保护

`Shooter.kt` 的 `OnImageAvailableListener` 在 `convert(image)` 返回 null 时**必须设置 `imageAvailable = true`**，否则后续所有 `shot()` 调用永久超时，返回同一张缓存图 → 状态机永远 `simToTranslated=1.0` → 自动翻译卡死。此 bug 已在 listener 中添加保护。

## AI 上下文：按 token 预算裁剪（2026-09-27 改造）

> 原来是「保留最近 N 轮」（`game_context_count`，5-20 轮）。轮数与 token 完全不成比例：
> 一页 34 气泡的漫画批量**单轮**就几千 token，两句短对话只有几十 —— 档位对不上真实开销。现已整体替换。

**设置项**：`ctx_token_budget`（ListPreference，`res/xml/personalization.xml`，档位 **4K/8K/16K/32K/64K/128K** = 4096…131072，**默认 32768**，存 String；数组 `ctx_token_budget_entries`/`_values` 在 `values/` 与 `values-zh/` 各一份，文案「越大越慢、越费 token」双语）。
**旧键 `game_context_count` 已废弃**：preference 条目、监听、读取、数组、字符串全部删除，**不要在任何新代码里读它**（读取会拿到旧值或被默认值兜底，静默不生效）。

**裁剪只有一个实现**：`utils/ContextBudget.kt`（纯逻辑，单测 `app/src/test/java/com/moe/starflow/utils/ContextBudgetTest.kt`）
- `parseBudget(raw)` / `budgetOf(prefs)`：解析档位（非法值回默认档，越界向 4K~128K 收敛）
- `trim(history, budget)`：**从最新往前**累加 `TokenEstimator.estimatePair src+tgt`，装不下的最旧轮**丢掉**（连续最新窗口，不跳轮）；预算是硬上限——最新一轮就超预算时返回空表；另有 `MAX_TURNS=200` 条数兜底
- `trimInPlace(history, budget)`：服务侧 `LinkedList` 用（返回保留轮数）
- `applyTo(translator, history, enabled, prefs)`：**唯一的上下文写入入口**（内部 `translator as? ContextAwareTranslation`）

**调用点**（原先两处各写一份 `while (size > max) removeFirst()`，已删）
- `FloatingBallService.translateByText`：`ContextBudget.applyTo(...)` 推给引擎；翻译成功后 `addLast` + `trimInPlace(contextHistory, contextTokenBudget)`
- `TranslateUtils.translateBubblesBatch`：同样 `applyTo` + `trimInPlace`（漫画只在 `forceContext=true` 的批次间用）
- 设置实时生效：`FloatingBallService.prefChangeListener` 里 `key == ContextBudget.KEY_TOKEN_BUDGET → 重读`；`TranslateUtils` 每次批量翻译现读 `budgetOf(prefs)`。**`MangaFloatingService` 不需要登记**（它没有上下文状态，预算由 TranslateUtils 现读）

**引擎侧**：新增接口 `translate/ContextAwareTranslation.kt`（`updateContext(history, enabled, budgetTokens)`）
- **OpenAI 兼容**（`OpenAITranslation`）：按传进来的设置预算 `trim` 后存，`buildRequestBody` 照旧插 user/assistant 对；不加历史时系统提示词也不加「根据上下文剧情」前缀
- **本地 LlamaCpp**（`LlamaCppTranslation`）：**刻意忽略设置预算**，用模型自己的 ctx：`ContextBudget.localHistoryBudget(contextSize, maxTokens, 原文)` = `ctx − maxTokens`（maxTokens 不可用则 `ctx/4`）再减去本轮原文与标记开销。历史经 `localContextBlock` 拼成 `[Context/前文]\n原文\n译文…\n[Current/当前]\n` 作为 **user 段原文之前的前缀**（native 只有 system/user 两个字符串，没有多轮接口）。因为历史落在模板 `{source_text}` 位置，**前缀 KV 缓存仍只覆盖固定指令**，缓存不会被历史破坏
- **NLLB / 用户自建 API**：不实现该接口（没有提示词通道），`applyTo` 直接跳过；`NLLBTranslation` 类注释已写明

**重置时机（`clearContext()`，两个服务同名同义）**：**新会话开始**（`onCreate` 里翻译器建好后）与**服务停止**（`onDestroy`）各清一次 —— 清内存历史 + 引擎侧那份。
⚠️ **不在「退出漫画阅读器」时清**：阅读器退出后仍可能有章节后台批量翻译在跑，此刻清会把在途批次正在用的上下文抽走；阅读器自己的历史是 `ReaderTranslationController` 的实例字段、随 Activity 消失，不依赖 Service 侧清理。
⚠️ **引擎侧必须显式清**：`LlamaCppTranslation` 是**进程级共享实例**（`LlamaCppSharedHolder`），服务重建后还是同一个对象，不清就会把上个会话的历史接着当上下文用。
