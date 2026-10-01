# 模型下载（`download/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 模型管理

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **PP-OCRv5 det** | 文字区域检测 | ~4.6MB | RapidAI/RapidOCR | getExternalFilesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec zh** | 中日英混合识别 | ~16MB | RapidAI/RapidOCR | getExternalFilesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec en** | 英文专用识别 | ~7.5MB | ModelScope | getExternalFilesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec ko** | 韩文专用识别 | ~12.9MB | ModelScope | getExternalFilesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec ru** | 俄文/西里尔文字识别 | ~7.7MB | ModelScope | getExternalFilesDir/ppocrv5/ 需下载 |
| **PP-OCRv6 det small** | 文字区域检测 | ~9.9MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv6 rec small** | 多语言混合识别 | ~21MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv6 det medium** | 文字区域检测（高精度） | ~60MB | ModelScope | getExternalFilesDir/ppocrv6/ 需下载 |
| **PP-OCRv6 rec medium** | 多语言混合识别（高精度） | ~74MB | ModelScope | getExternalFilesDir/ppocrv6/ 需下载 |
| **RT-DETR-V2** | 文字/气泡检测 | ~11MB | HuggingFace | getExternalFilesDir/ 下载 |
| **manga-ocr** | 竖排日文识别 | ~135MB | HuggingFace | getExternalFilesDir/ 下载 |
| **Hy-MT2 1.8B 1.25-bit**（LlamaCpp 预制模型） | 本地翻译 | ~440MB | ModelScope（腾讯官方） | **getExternalFilesDir/models/** 需下载 + 设备端重打标 |
| **Hy-MT2 1.8B Q4_K_M**（LlamaCpp 预制模型） | 本地翻译 | ~1.08GB | HuggingFace `tencent/Hy-MT2-1.8B-GGUF` | **getExternalFilesDir/models/** 需下载（标准量化，无需重打标） |
| **导入的任意 GGUF**（LlamaCpp） | 本地翻译 | 用户自定 | 用户本地文件（SAF） | **getExternalFilesDir/llamacpp/** |

**LlamaCpp 模型管理页**（设置 → LlamaCpp 模型，`ManageActivity.TYPE_FRAGMENT_MANAGE_LLAMACPP = 13`，原 Hy-MT2 详情页已删除、type 12 别名到本页）：分「预制模型 / 导入的模型」两组；点卡片=设为当前使用（互斥），「添加模型」= SAF 选本地 `.gguf`。预制模型走既有下载流水线（`ModelKey.HY_MT2_GROUP` / `ModelKey.HY_MT2_Q4_KM`，清单真值在 `assets/models/downloadinfo.json`），**只是提供下载、不打进 APK**（Q4_K_M 的 MD5 由整文件下载实测钉在 `LlamaCppBuiltinCatalogTest` 里）。⚠️ 这组模型的 UI 叫法固定为**「预制模型」**（不是「内置」也不是「官方」——它们既没打进 APK，也不都是我们自己的东西）。
⚠️ **该页的「状态 → 渲染」链（2026-09-25 重构，改动前必读）**：三个状态源（`LlamaCppModelStore.models` / `activeId` / `repo.observe()`）用 **`combine` 合成一次渲染**。分开收集（各自 `collectLatest`）时同一次状态变化会触发 2~3 次全量重绘，而下载期间 `repo` 每个进度回调都发射一次（`updateProgress` → `emitSnapshot`）→ 列表按进度刷新率反复重建、抖动掉帧。行视图**按 id 复用**（`syncRows` 只在 id 序列变化时重新 inflate，其余走 `ItemLlamacppModelRowBinding.bind` 只重绑，不解析 XML），由此带出两条硬约束：
- **四个下载按钮必须「每个状态都显式赋值」**，不能只写「要显示的那个」——复用会把上一轮的可见性留下来（典型症状：下载完成后 [暂停][取消] 仍挂在卡片上；重新 inflate 的实现天然不会暴露这个问题）。判定收在纯函数 `rowButtonsFor(state, missing)` 里，由 `LlamaCppRowButtonsTest` 钉死，改回「只写 VISIBLE」即红。
- 卡片间距取自**行模板的 `layout_marginBottom`**，最后一张置 0（区块间距交给下一段标题的 marginTop）；原值记在行根 View 的 `RowTag` 上，复用后据此还原。⚠️ `id` 列表比较**不要拼分隔符字符串**（导入模型的文件名可能带空格），直接比 `List<String>`。

### 超分（SR）模型（2026-10 新增，2026-10 底定为 5 族 13 档）

**全部走下载，不打进 APK**（用户硬要求）。清单在 `assets/models/downloadinfo.json`，落盘统一在
`getExternalFilesDir("sr")`，文件名取自清单（代码里不硬编码文件名）。

| 族 | 档位 | 大小/档 | Key 前缀 |
|------|------|------|------|
| **waifu2x upconv_7**（**动漫 + 照片两套权重，各两档 = 4 档**） | 动漫 不降噪 M1 / 强力降噪 N2；照片 不降噪 M1 / 强力降噪 N2 | ~1.1MB | `SR_W2X_UP7_{ANIME,PHOTO}_{M1,N2}` |
| **waifu2x cunet** | 不降噪 M1 / 中度降噪 N1 / 强力降噪 N2 | ~2.7MB | `SR_W2X_CUNET_{M1,N1,N2}` |
| **SRMD** | 带降噪 X2 / 不降噪 NF_X2 | ~2.9MB | `SR_SRMD_{X2,NF_X2}` |
| **Real-CUGAN** | 不降噪 / 保守 / 降噪 3x | ~2.5MB | `SR_REALCUGAN_{NODENOISE,CONSERVATIVE,DENOISE3X}` |
| **Real-ESRGAN** | 动漫 6B（**4x**，全场唯一 4 倍） | ~17MB | `SR_REALESRGAN_ANIME6B` |

- ⚠️ **照片族一度被误删，2026-10 底补回**（用户口径：「upconv 的照片放大模型丢了，需要加到
  upconv 的那个组里面，一共 **4** 个才对」）。删的时候 `LEGACY_KEY_MAP` 里留了
  「照片键 → 动漫键」的重定向 —— **键回归后必须删掉那两条**，否则用户选了照片模型会静默跑动漫模型
  （照片/彩页/网点重的图源用动漫权重明显更糊）。守卫：`SrModelKeyMigrationTest`
  的 `photoKeysAreRealTiersNotAliases`（**只允许 N3→N2 这种"键已不存在"的族内迁移**）。
  照片族的 N3 没有对应文件，就近迁到**同族**的 N2（不是动漫族）。
- ⚠️ **AnimeJaNai（ONNX，5 档）与 swin_unet 已删除**：前者 PSNR 28 上下、不如 ncnn 系且属"视频向"权重；
  后者 16.8MB/档、15-19 秒/页且转不了 ncnn。别再按旧文档去加回来。
- ⚠️ 现在**所有 SR 档位都是 ncnn（`.param` + `.bin` 两个文件，带 `family`/`scale`/`noise`/`prepad`）**。
- 运行时选择与文件解析收在 `com.moe.starflow.sr.SrModelManager`（prefs 键 `sr_active_model_key`），
  与 OCR 侧的 `OcrEngineManager` 同一套路：模型管理页只调它，引擎只调它，不各自读 prefs。
- ⚠️ **加 SR 模型要改 6 处**：`ModelKey` 枚举、`downloadinfo.json`、`SrModelManager.allKeys` + `nameResOf`、
  `ModelManagementFragment.srFamilies` + `srExpectedSize` + 布局 XML 行 + 中英字符串；以及
  `baseDirFor`（Repository 与 Service **各一份**）与 `ModelDownloadService.displayName` 这三个**穷尽 when**，
  否则直接编译不过。改完跑 `python tools/sr-research/verify_sr_wiring.py`（交叉自检，别靠人眼）。
- ⚠️ **`displayName`（下载通知里的名字）必须调 `SrModelManager.nameResOf`**：这里曾硬编码英文
  （中文界面下通知是英文），而且 upconv_7 动漫的 N3（极强降噪）被写成 "Light denoise"（错档）。

### 模型管理页顶部 Tab（2026-10）

`fragment_model_management.xml` 现在是 `LinearLayout(竖)` → `TabLayout(model_tabs)` + 两个 ScrollView：
`ocr_scroll`（原有 OCR 内容，全部 include 不变）与 `sr_scroll`/`sr_content`（超分）。

- ⚠️ **超分行是静态 XML `<include>`**（2026-10 改版后与 OCR Tab 完全同构）：`sr_content` 里逐行
  `<include layout="@layout/item_model_row_browser" android:id="@+id/sr_xxx_row"/>`，
  `ModelManagementFragment.srFamilies` 只引用这些 id。**不再有 `buildSrRows()` 动态 inflate** ——
  旧注释写的「sr_content 必须为空」已过期（照它改会把行删光）。守卫：`ModelManagementLayoutTest`
  （`superResolutionTabStructureIsSound` + `superResolutionGroupsAndRowsAreComplete`）。
- 超分行复用 `renderRowStateInto(...)`（由原 `renderModelBlock` 抽出）——**OCR 行与超分行共用同一套
  下载/暂停/取消/删除状态渲染**，改状态机时两边一起变。
- 选中超分模型 = 点「模型名标题」或行的空白处；选中态在标题上以绿色「· 当前使用」体现
  （`refreshSrSelection()`，**每行都要显式赋值**，否则切模型后旧高亮会留在屏幕上）。
- ⚠️ 页面进入与切到超分 Tab 时都要 `repo.refreshFromDisk(每个 SR key)`，否则刚下载完的模型显示为「未下载」。
- ⚠️ **`ModelManagementLayoutTest` 必须用「套了 app 主题的 context」inflate**：布局里有 Material 的
  `TabLayout`，裸 `RuntimeEnvironment.getApplication()` 的主题不是 Material 主题 → TabLayout 构造直接
  抛 `InflateException / UnsupportedOperationException`，**6 个用例会一起红**（不是只有新增那个）。
  ```kotlin
  val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MT)
  LayoutInflater.from(themed).inflate(R.layout.fragment_model_management, null, false)
  ```
  项目里 `fragment_history` / `fragment_openai_api` 也用 TabLayout，但只有本测试会 inflate 整个布局 ——
  加别的含 Material 控件的布局测试时同样要套主题。

**PP-OCRv5/v6 cls（方向分类）已删除** — v5 和 v6 的 cls 模型、代码、`runOCR(useCls)` 参数全部移除（commit 5d6e235 / 5a1e83e）。

PP-OCRv5 全部模型（det + rec_zh + 字典 + 可选 rec en/ko/ru）改为下载，**assets 中已无 v5 模型文件**。PP-OCRv6 small 仍内置，medium 必须从 ModelScope 下载。`PPOcrModelFiles` 提供所有 v5/v6 模型的文件查询、浏览器 URL、删除接口（下载走 `download/`）。

### PP-OCRv5 模型下载地址（ModelScope）

基础 URL: `https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/`

| 文件名 | ModelScope 文件名 | 大小 |
|--------|-------------------|------|
| rec_en.onnx | `en_PP-OCRv5_rec_mobile.onnx` | ~7.5MB |
| rec_ko.onnx | `korean_PP-OCRv5_rec_mobile.onnx` | ~12.9MB |
| rec_ru.onnx | `cyrillic_PP-OCRv5_rec_mobile.onnx` | ~7.7MB |

字典文件（rec_en_dict.txt / rec_ko_dict.txt / rec_ru_dict.txt）随 rec 模型一起从 ModelScope 下载（app 内串行下载 ONNX + 字典两个文件），不再从 assets 读取。`PPOcrV5Engine.loadDictionary()` 仅从 `getExternalFilesDir/ppocrv5/` 读取，不 fallback assets。

### PP-OCRv5 语言 fallback 逻辑

`PPOcrV5Engine.resolveRecLang()` 处理语言选择：
- **ZH/JA**：语言无条件可选中（`resolveRecLang`），**但模型一样要下载** —— assets 里已无 v5 模型，rec_zh 的下载条目在 `assets/models/downloadinfo.json`（`PP_OCR_V5_REC_ZH`）
- **EN**：已下载 rec_en → 用 EN 模型；未下载 → fallback 到 ZH 模型（ch 也支持英文）
- **KO**：已下载 rec_ko → 用 KO 模型；未下载 → 返回提示"请下载韩文模型"
- **RU**：已下载 rec_ru → 用 RU 模型；未下载 → 返回提示"请下载俄文模型"

### 下载管理器

下载流水线收拢在独立包 **`download/`**（`com.moe.starflow.download`）：

```
download/
├── ModelDownloadManager.kt      # 统一 HTTP 下载器（断点续传、重试、进度回调）
├── ModelDownloadRepository.kt   # 状态机 + 磁盘扫描 + JSON 解析
├── ModelDownloadService.kt      # 下载编排 + 前台服务 + 通知
├── DownloadState.kt / ModelInfo.kt / ModelKey.kt  # 状态/元数据/枚举定义
└── ChecksumHelper.kt            # MD5 校验（含 VerifyResult）
```

`manga/engine/` 下的 `RTDetrModelFiles`/`PPOcrModelFiles`/`MangaOcrModelFiles` 仅保留**模型文件检查**逻辑（`DBNetModelFiles` 已随死代码删除）（下载方法已迁到 download/，待后续清理）。

### 模型管理 UI 架构（v4 起）

`ModelManagementFragment` + `fragment_model_management.xml` 按「引擎组合」分组，不再按「检测器/识别器」分两大块。页面顺序固定（不要随意调整）：

| # | 组 | 类型 | 说明 |
|---|----|----|------|
| 1 | ML Kit | 内置 | Google 设备端 |
| 2 | PP-OCRv6 | small 内置 / medium 可选下载 | 两个内置相邻，v6 small 右侧绿色「内置」文本 |
| 3 | PP-OCRv5 | 全部下载 | 内部顺序：检测器（det）→ 识别器（多语言 → 4 个语言） |
| 4 | RT-DETR-V2 + manga-ocr | 全部下载 | 漫画组合，RT-DETR-V2 是检测器、manga-ocr 是识别器 |

每组条目统一 4 段 UI（**v4 起不再有「· 检测器」「· 识别器」灰色角标**——与标题同义重复，已删除）：
1. **标题**（粗体 13-14sp）
2. **状态文本**（12sp，含实际或预估文件大小，weight=1 占满剩余空间）
3. **🔗 浏览器按钮**（11sp 灰色，链接图标 + 文件名/类型：模型/字典/encoder/decoder/vocab/浏览器）
4. **下载/删除按钮**（绿色 btn_download 或红色 btn_delete，weight=0 固定宽度）

v6 medium 用 RadioButton 切档（det+rec 全部下载后才显示 medium RadioButton），RadioButton 与「medium」标题同行右侧，与 small RadioButton 互斥。删除任一 medium 文件 → 自动切回 small + Toast 提示。

**可扩展设计（v5 起）：** `ModelManagementFragment` 是**数据驱动**的——`modelRows` 列表驱动渲染、浏览器按钮接线、磁盘状态刷新，不再逐模型写方法。

**新增一个可下载模型只需 2 步：**
1. `downloadinfo.json` 加模型定义（`model_key` + `files` 数组：文件名/URL/大小/MD5）
2. `ModelManagementFragment.modelRows` 加一条 `ModelRow`，并在 `fragment_model_management.xml` 对应分组里加一行
   `<include layout="@layout/item_model_row_*" android:id="@+id/xxx_row"/>`

**⚠️ 行模板与 ID 作用域（改动前必读）：** 模型行不再逐个手写，统一走三个模板
`item_model_row_browser`（单文件：状态 + 1 浏览器 + 下载/删除 + 取消）、`item_model_row_files`
（onnx + 字典双浏览器）、`item_model_row_manga_ocr`（标题行 + encoder/decoder/vocab）。
- 模板内部的 ID（`row_status`/`row_action`/`row_cancel`/`row_browser*`）**在多次 include 之间是重复的**
- 因此 `ModelManagementFragment` 一律以「行根 View」为作用域查找（`rowRoot(row) = rootView.findViewById(row.rowRootId)`），**禁止全局 `rootView.findViewById`**——全局查找只会命中文档序第一行，拿它渲染别的行就串数据
- 每个 `<include>` **必须**带唯一的 `android:id`（行根 View），否则各行无法区分
- `ModelRow` 字段：`rowRootId` + `browserBtnIds`（模板内 ID → 开模型主页）/ `fileBrowserBtnIds`（模板内 ID，逐个对应 `files[i].download_url`）
- 回归守卫：`ModelManagementLayoutTest`（inflate 布局，锁死行根唯一、作用域查找、模板按钮归属）——改布局后跑它

**本地翻译模型下载页：** `NllbModelFragment` 已参数化——`NllbModelFragment.newInstance(ModelKey.X)` 可复用于任意本地翻译模型（默认 NLLB），`ManageActivity` 按 `EXTRA_FRAGMENT_TYPE` 传 modelKey 即可。

**下载系统关键约定（防踩坑）：**
- **校验走 MD5，不按 `file_size` 精确匹配**（JSON 的 file_size 常与服务器实际文件不符，曾导致"下载完成→误删→死循环"）；`downloadinfo.json` 各模型 checksum 从本地 `models/` 真实文件计算，NLLB 的 5 个 checksum 未验证
- **多文件 Done 必须所有文件都有完整 target**（`computeStateFromDisk`），不能只检查第一个文件（曾导致强退后 NLLB 误判已下载）
- **下载完成立即 `markDone`**，否则状态卡 Running，只有重启 `initialize()` 才识别
- 页面进入先调 `refreshFromDisk()` 让 UI 反映磁盘真实状态；取消/删除已入队的下载会从 `queuedKeys` 移除，不会被自动重启

**关键规则：**
- **所有日志用 `LogCollector`**，不用 `Log.d/i/e`
- **下载前检查文件是否存在**，避免重复下载
- **404/403 不重试**，其他错误最多重试 3 次
- **大文件用 `.part` 后缀**，下载完成再 rename
