# 小说导入与阅读模块

> 范围：本文记录 `novel/` 的当前实现约束。跨模块规则见仓库根目录 `AGENTS.md` 和 `CLAUDE.md`。源码与测试为准；实现变化后同步更新本文。

## 导入、书架与阅读器

书目、阅读进度与段落／插图处理记录统一保存在按书库隔离的 App 内部数据库；目录保存原文件、索引和图片产物，不保存记录同步副本。作品数据页支持单本导出。

来源缺失的卡片显示独立记录导出按钮，点击卡片打开共享恢复弹窗；第四个“更多”面板和缺失弹窗均可进入 `library/BookDataActivity`，按捕获的书库与 UUID 查看数据，不为查看记录解析缺失原书。排版页数只报告当前章节，未知值明确标注。恢复、导出与删除互斥约束见 [书库存储指南](../library/CLAUDE.md)。

**格式判定先看内容再信扩展名**（`NovelFormatDetector`）：`.txt` 里塞 HTML 的、无扩展名的都要认出来；编码（UTF-8/GBK/Big5）同理按字节判。

- **来源扫描**：进入或恢复小说页签、切换书库及下拉均触发 `LibraryScanner`，也可手动启动。仅枚举当前 `novel_import/`，逐本提交；已收录作品保留身份，不重复解析章节。文件夹按一本识别，章节保留相对路径。存储约束见 [书库存储指南](../library/CLAUDE.md)。
- **简介元数据**：各格式导入与扫描共用 `library/BookMetadataReader`；支持包内或目录中的 `meta.json`/`ComicInfo.xml`、独立文件的同名元数据，以及单作品子目录的普通元数据。已有书库目录授权可用于复制文件来源的相邻元数据，单文件授权不推定兄弟文件可读。EPUB 读取 OPF 的 `dc:description` 与作者，HTML 读取 description/og:description，文件夹 EPUB 分卷提供缺失的作者、简介与封面。文件夹只枚举一次，按正文、插图和元数据分类复制。元数据有大小上限，坏数据不阻止正文导入；旧空简介按读取版本补齐，不覆盖非空用户修改。导入进度统一放在状态栏，书架仅显示实际书目。
- **手动放置格式**：TXT / EPUB / HTML / HTM / XHTML；ZIP 包需含 HTML / HTM / XHTML / TXT 章节。作品目录内最多 6 层子目录中的 TXT、HTML 和 EPUB/ZIP 小说卷按自然序作为章节；HTML 插图按 HTML 所在目录的相对路径读取。SAF 提供器可能只返回 MIME、不返回扩展名，目录导入须按扩展名与 MIME 两路识别；本地扫描与解析还要嗅探无扩展名、非标准扩展名和 TXT 内嵌 HTML。单 HTML 文件无法读取兄弟目录的图片，须以文件夹形式放置整本内容。
- **外部目录访问诊断**：`File.listFiles() == null` 时报告 `File.canRead()`、应用 UID、目录 UID/GID 与权限模式。`getExternalFilesDir()` 无需存储权限；若 MTP/ADB 创建的子目录归 `shell` 所有且没有应用遍历权限，应用无法读取其子项，不能把它误报成 SAF 授权失败。
- **导入目标创建**：目录和单文件导入共用 `FileAccessDiagnostics.ensureDirectory()`，保留底层创建异常，区分可读与可写；不能用重建书库记录替代权限修复。普通失败使用红色可复制详情和存储位置入口，批量失败全部汇总且保留未确认的弹窗内容。
- 封面缩略图写入选定书库 `.starflow/books/<UUID>/cover/`；SAF 新文件先在内部持久目录暂存，验证并提交后释放副本。
- 无内嵌封面的小说使用书名占位图，完整书名自适应字号、多行居中绘制。
- 小说插图与封面放入 `.starflow/books/<UUID>/`；删除由 `LibraryDeletion` 持久化意图后执行，失败保留占位，不按数字 id 推断来源归属。
- 小说内嵌封面和自选封面共用 `library/ImportCoverDecoder` 采样解码；封面保存成功或失败都释放输入位图，300 像素占位图也必须释放。文件夹复制进度按约 80ms 节流，结束时立即报告完整文件数。
- SAF 目录按 document ID 检查循环和重复访问；仅 UUID 名称按导入意图做定向查询，不逐目录读取全部书库元数据。
- 扫描结果通过 `NovelStore.mergeScanned()` 在存储锁内合并，只新增 id 和规范路径均未收录的条目，保留并发导入及最新书架编辑。
- SAF `DISPLAY_NAME` / `SIZE` 只用于文件名与进度展示，不作为可读性判据。查询失败由 `SafDocumentMetadata` 记录 provider authority、URI 和列/异常细节，合并投影不受支持时分别查询；树 URI 的目录名查询先转换为具体 document URI。内容流打开、读取、app 目标写入分别报告阶段与根异常。

### 小说书架排序

- 显示选项提供标题、添加时间和最近阅读三种排序。标题按小写后的标题排序；添加时间按 addedAt 倒序。
- 最近阅读按 lastReadAt 倒序；相同时间再按添加时间倒序，最后按标题排序。旧书目没有阅读时间时默认为 0，排在有阅读记录的书目之后。
- 阅读进度保存时同步更新 lastReadAt。从阅读器返回书架时刷新列表，使最近阅读排序立即生效。
- 最近阅读偏好单独存入 novel_shelf；标题/添加时间排序继续使用共享显示选项的既有设置。

### 分页与绘制共用同一份 `StaticLayout` 几何

分页（`NovelPaginator`）与绘制（`NovelPageView`）**读同一批数**：`PageSegment` 存的是**行区间**
（`lineStart..lineEnd`），高度一律取 `getLineBottom(i) - getLineTop(i)`，段高 = 各行之和。
两边各算一套的话底部文字就会被裁掉 —— 三条都踩过：

- 分页用**单字探针**量出「单一」行高再乘行数（估算）→ 某行更高（换字体/表情/全角标点落到别的字体）时每页多塞一行
- 绘制把 segment 的 substring 取出来**重新排版** → 重新断行可能多出一行
- 段间距按 `round(段距/行高)` 折算成**整行** → 不足一行被兜成一行，一页少放一段、底部一大片空白

⚠️ `NovelPageView` 里的 `clipRect` 只是**最后兜底**、不是修复手段；真被切到时打 `NovelDbg: PAGE_OVERFLOW`（含越界像素数）。

### Robolectric 排版测试的边界

Robolectric 的文本引擎是**桩**：`Paint.breakText` 不按宽度换行、行高恒为 `字号 × 行距倍率`。
所以分页刻意做成**纯核心 + Android 外壳**：`paginateByLines` 只收「行起点 + 每行高度」（测试自己合成度量），
`paginate` 才去建 `StaticLayout`。**「真机行高 ≈ 字号 × 1.15~1.5」这一半只能在设备上看日志确认**，
别用单测绿来宣称排版正确。长样本守卫见 `NovelPaginationRealismTest`（样本每章必须能分出多页 —— 早期样本
每章只有几百字节，一屏一页，"翻不动"其实是样本太短）。

### 阅读器状态与面板约束

- **并发加载按序号丢弃**（`loadToken`）：`loadChapter` 被 onCreate/译文到达/改排版/尺寸变化多处调用，不丢弃过期结果会把章节号与页码一起拽回去（现场表现是「翻页卡在第一页、点目录也没用」）
- **章节读取和分页等待态**：启动解析章节目录与 `loadChapter` 共用居中不确定进度动画；延迟 180ms 显示以避免短任务闪屏，只有对应的最新加载结束后才隐藏。
- **阅读模式取值迁移**（`novel_reader_mode_version`）：旧 `1`=滚动，新 `1`=上下翻页、滚动挪到 `2`
- **两个阅读器共用一套约定**：背景/翻页动画/旋转的 prefs 键与漫画共用（`manga_reader`），字号与排版是 `novel_*` 独立；`CurlPageView`、四个 transformer、`ReaderProgressBar` 直接复用 `mangaimport/reader`
- **面板是打开那一刻的快照**（`NovelPanelState` 全是 `val`），宿主改了值必须显式回推，两个方向都只留**一个出口**：
  - 面板内部派生 UI → `refreshDerivedUi`；排版参数 → `bindTypography`（初始填充与「恢复默认」后的回读共用）
  - 宿主 → 面板 → `NovelPanelSheet.notifyHostState(当前章 / 目录标签 / 章状态)`，挂在 `updateChapterTocLabel()` 上（换章、翻页、译文到达、重算统计都会走到）。
    ⚠️ **按字段开推送就是这类 bug 的成因**：早先只有 `notifyTranslateChanged(stats)`，于是「在面板里切章」不刷新；新增宿主字段时必须加进 `notifyHostState` 这一篇，否则漏掉是默认结果
  - 同理别在面板里留第二份数据：`updateSummary()` 曾读快照里的 `state.chapterStats`，而推送改的是 adapter 里那一份 —— 推了也白推

### 阅读外观与字体

- `NovelAppearance` 统一保存小说状态颜色及自定义背景/文字色，面板快照与宿主推送必须包含这份配色和当前字体名称。四种状态优先级不变；透明色参与优先级，不回退绘制其他状态。
- 前五项背景继续读写共享 `reader_background`，小说自定义选中态与色值用 `novel_*` 独立键；旧值 5 显示为默认项。浅/深预设及其图标只在小说侧改色，漫画不受影响。
- 自定义状态色同时作用于文本底色、插图边框和选中填充；未配置时保留原先半透明文本底色及不透明边框。调色复用 ColorPicker 组件，弹窗主题统一使用 `ReaderDialogs`，跟随当前阅读器深浅；草稿颜色仅作用于预览。右侧颜色样本使用 `NovelColorSwatch` 的 20dp 固定尺寸，不能使用会把长方形强制量成大圆的 ColorPanelView。
- 正文使用全局 `Custom_Result_Font`，与界面字体开关无关。`NovelReaderFont` 按文件身份缓存字体，`NovelTextStyle` 携带同一 Typeface 和字体身份，段级缓存及章级缓存都包含字体变化；缺失或无效字体回退系统。字体设置返回后重建阅读器并保存段号与段内比例。
- 字号面板提供漫画同款「自动」胶囊：开启时读取 EPUB XHTML 的内联/样式表 `font-size`，按原文范围应用；没有有效字号范围时直接用滑块固定字号。拖动字号滑块切回固定模式，重置排版恢复自动。CSS 字号范围必须同时进入分页 `StaticLayout` 与两种阅读模式的绘制布局及其缓存键；翻译文本使用固定字号，原文与双语中的原文部分保留 EPUB 字号。

### TXT 段落切分

`NovelParagraphSplitter.linesToParagraphs`（`TxtParser` / `FolderNovelParser` 调）：按 `\n` 切开、
逐行 trim、空行丢弃、再用空行重组 —— **一行就是一段**。

- **不做这一步，TXT 会被整章切成一个段落**：网文/公版 txt 几乎都是"一行一段、行间不留空行"，
  而 `split` 只认空行分段。整本公版《三国演义》实测：只按空行切 **240 块**（其中 120 块 >500 字、
  最大一块 7295 字），一行一段是 **1703 段**。不修的后果是一串连锁 —— 阅读器里是一堵墙、
  翻译按整章发一个请求（远超模型上下文）、章行分母恒为 1、长按多选只能选到整章、锚点全落同一段
  （用户报的「明明有很多段落却只有 2 段」就是这个）
- ⚠️ **只对 TXT / 文件夹里的 txt 用**：HTML/EPUB 那边单个换行是**段内**换行（`<br>`），
  `HtmlTextExtractor` 已经按空行分好段了，再归一化会把一整段按 `<br>` 切碎
- ⚠️ 代价：**硬换行**的 txt（一行几十字、句子被折断）会被切碎。中文小说几乎不这么排，
  而"一行一段"是压倒性的常态 —— 取舍明确选了它

### 切分版本升级与旧数据清理

段落切分规则一变，旧 `paraIndex` 就整体错位。⚠️ 译文表主键是
`(novelId, novelKey, chapterIndex, paraIndex)`、**不含 `splitVersion`** —— 旧版本的行占着同一批主键，
新版本的行 `insertIgnore` **静默写不进去**（"补齐分母"与"标记翻译中"全废，且不报错，看不出错）。

- 现存的清理路径：`NovelChapterTranslator.resetStale`（阅读器进入时跑）→ `dao.deleteOtherVersions`
- **当前值已经是 3**（2 → 3 是「图片标记前后各留一个空行」那次：`<p>文字<img/></p>` 从一段变成
  两段，含内嵌图片的章段号整体后移）。所以改切分规则照旧 +1 即可 ——
  别看到版本号已是 3 就以为"动完了不能再动"，也别把上面那句清理路径删了
- 另一条路是把 `splitVersion` 并进主键（要一次重建表的迁移），没做

### 支持格式与插图能力

`NovelFormat` 只有 **5 种**：`TXT` / `EPUB` / `ZIP_HTML` / `HTML` / `FOLDER`
（判定在 `NovelFormatDetector.detect`：先检查 ZIP/EPUB 结构，再嗅探 HTML 与无扩展名文本，最后回退扩展名；
TXT 内嵌 HTML 按 HTML 解析）。

| 格式 | 导入入口 | 章节 | **插图** |
|---|---|---|---|
| `TXT` | 文件 | 按章节标记切；无标记 = 整本一章 | —（纯文本，没有图片的概念） |
| `EPUB` | 文件 | 按 spine 原序保留全部条目；封面/导航标为辅助章，首次打开定位到第一条正文 | ✅ 包内图（整包复制进来） |
| `ZIP_HTML` | 文件（.zip） | 包内 html/htm/xhtml/txt 条目，自然序 | ✅ 包内图（`ZipImages.read`） |
| `HTML` | 文件（.html/.htm/.xhtml，或按内容识别） | 整篇 = 一章 | HTML 正文可读；外部图片须用文件夹导入 |
| `FOLDER` | 文件夹 | 根目录及最多 6 层子目录中的 TXT/HTML/EPUB/ZIP HTML（EPUB 与 ZIP 展开成卷） | ✅ HTML 按所在目录解析相对图片；EPUB/ZIP 卷的图 ✅；TXT 无图 |

MOBI/AZW、FB2、PDF、RAR/7z 小说包当前没有对应解析器，不应在界面宣称为可读小说格式。ZIP 中只有图片而没有 HTML/TXT 章节时按漫画包处理，不是小说。

- EPUB 可以把全书正文放在单个 XHTML，并用 EPUB3 `page-list` 锚点标记扫描页；这类文件按 spine 保留所有条目，辅助页不占默认正文起点。阅读器打开时若解析章节数与旧书架记录不同，要更新记录；只有保存位置无效或落在辅助章时才重置到第一条正文。
- 单个 XHTML 可能含数万段（例如 OCR 书籍）；分页逐段读取 `StaticLayout` 度量，只保留行起点/行高数组和有界布局缓存，不能在整章分页期间持有每一段的 `StaticLayout`。新章节或重排请求到来时取消过期加载，并在段落度量循环中响应取消。

- ⚠️ **小说侧不许复用漫画的导入文案**：`NovelShelfFragment.showImportDialog` 曾用
  `import_manga_option_files`（＝「导入文件（zip/cbz/cbr/7z）」）—— **cbr/cbz/7z 是漫画的格式**
  （走 libarchive 解压那套），小说一条都不认。用户 2026-10-04 直接指出了这条。
  小说的按钮/描述有自己的 key：`import_novel_option_files` / `import_desc_files_novel` /
  `import_desc_dir_novel`。漫画那边是 `import_manga_option_files` / `import_desc_files_manga`。
- ⚠️ **单 HTML 的图片是 SAF 的硬限制**（不是漏做）：`ACTION_OPEN_DOCUMENT` 的单文件授权
  只覆盖那一个文件，同目录的兄弟图片读不了（构造兄弟 URI 也会 `SecurityException`）。
  所以 `import_desc_files_novel` 的文案里**明说**了「有插图请改用导入文件夹」。
  要真做成，只能再加一次 `ACTION_OPEN_DOCUMENT_TREE`（让用户把父目录授权一遍）——没做。
- ⚠️ **夹内 html 的图片靠"导入时递归拷"**（`NovelImporter.collectDirectoryDocs`）：html 里的
  `images/a.jpg` 是**相对路径**，而 `resolveLocal` 的基准目录是书目录 —— 不把图拷进书目录，
  解析出来永远是 null。两条配套约束：
  1. **只有夹里有 html 才拷图片**（txt/epub 不需要外部图；纯 txt 夹不该被塞进一堆图）；
  2. 相对路径由遍历层级逐层累计经过消毒的目录和文件显示名，保留 `images/` 等路径。
     `documentId` 是不透明标识，只用于目录查询和内容打开，不能假设包含父目录前缀。目录枚举用
     `SafDocumentTreeReader` 一次读取子项的 documentId、displayName、mimeType，避免每个 child 再走
     `DocumentFile.name/isDirectory` 的额外查询。
- ⚠️ **拷图片必须过两道守卫**（与 `safeSegment` / `resolveInside` 同一套威胁模型）：
  `sanitizeRelPath`（拒 `..`、冒号/盘符、控制字符、超长、层级过深）+ `resolveInsideTree`
  （规范路径必须**严格**落在书目录内）。放行一条 `../../x.jpg` 就能覆盖别的书/封面/日志。
- 不设文件夹插图数量或总字节上限；必要文件复制失败时整本导入失败，清理本次半成品，保留外部来源。
- 单张必要插图复制失败也必须抛出具体原因，不能只记录日志后继续。
- 守卫：`NovelImporterDirectoryRulesTest`（扩展名分类 + 路径消毒 8 例）、
  `NovelImageExtractionTest`（含「文件夹里的 html 章节能读出插图」/「章节子目录里的 html 能成章」/
  「夹内 html 缺图返回 null」）、`FolderNovelParserTest`（12 例，既有约定一条没破）。

### EPUB 与 HTML 内嵌插图

小说阅读器**会显示 epub/zip 里的插图**（封面页、插图页）。整条链路是"标记 ←→ 字节 ←→ 文件"
三段，每一段的一一对应都是硬约束：

| 段 | 位置 | 约定 |
|---|---|---|
| 抽地址 | `HtmlTextExtractor.extractWithImages` | 图片标记 `📷 [图片]` **前后各留一个空行**（单独成段）；地址表（`src` / `xlink:href` / `href`）**按文档顺序**，标签没写地址的也占一个空位 |
| 读字节 | `NovelParser.loadChapterSource` + `ZipImages` | 一次开包读完整章；相对地址按**条目所在目录**解析（基准目录**必须带结尾 `/`**，`ZipImages.baseDirOf`）；`../`、百分号转义、`data:`、SVG 包装往里找一层 |
| 落盘+量尺寸 | `NovelChapterRepository.imagesOf` | 按**内容哈希**写进 `.starflow/books/<UUID>/images/`（删书一起清）；`inJustDecodeBounds` 量原始像素尺寸 |
| 分页+绘制 | `NovelPaginator` / `NovelPageView` / `NovelScrollAdapter` | 尺寸口径**只有一份**：`NovelImageMetrics`（等比、放大封顶 3×、分页时限一页高）；插图段没有 `StaticLayout`，高度伪装成"整段一行" |

- ⚠️ **图片段数是配对规则的锚**：`第 i 个 IMAGE 段` 配 `第 i 张图`。所以 `head` / `script` 里的
  图片**必须**在抽地址前就被剥掉（`stripClosedBlock` 先跑），标签没地址的也必须占位 ——
  少一项就是"第 3 章配了第 4 章的脸"，**看不出错**
- ⚠️ **放大到列宽、封顶 3×**（2026-10-04 从"只缩不放"改成这样）：电子书里 1×1 的占位/间距小图
  非常多，无上限放大就是**一整屏色块**；而完全不放又让真插图贴着原始像素显示（用户报的
  「图片感觉都太小了」）。详见下面「插图**翻译**」一节的尺寸条与 `NovelImageMetrics.MAX_UPSCALE`。
- ⚠️ 读不到的图写 `null` → 该段**回落成占位文本**：少一张图不能把正文也丢了
- ⚠️ **单 HTML 的图导不进来**（SAF 单文件授权拿不到兄弟文件，见上面那张表 + 那条硬约束）；
  夹内散章（txt）本来就没有图；**夹内 html 的图能显示**（2026-10-04 起，导入时递归拷贝）。
  `HtmlParser.loadChapterSource` 那条路径一直按规矩解析 —— 图片一旦在书目录里（夹内 html、
  或用户手工放进书目录），它立刻就能用
- 守卫：`NovelImageExtractionTest`（抽地址 / 配对 / epub 相对路径 / 百分号转义 / SVG 包装 /
  `data:` / 路径穿越 / 夹内 html 取图）、`NovelImageMetricsTest`（尺寸口径）、
  `NovelImagePaginationTest`（占位高度与换页）

### 插图翻译

用户口径：「长按图片翻译图片内容（OCR + 翻译 + overlay 渲染回去，图片右下角支持三态切换、
重新翻译、删除等功能，**相关逻辑和漫画翻译那边一样**）」+「翻译面板多一个开关『同时翻译图片』」
+「**一张图片算一个文本段落**，队列里有多张图片时依次 OCR 翻译每张」。

链路（每一段都能单独替换，别把它们揉在一起）：

| 段 | 位置 | 说明 |
|---|---|---|
| OCR | `NovelImagePipeline.ocrWithLock` | 复用**漫画同一条链路**：`OcrLock`（`beginOcrDemand` + 带 5s 心跳的等锁）→ `TranslationEngineInit.ensureReady`（引擎组来自全局设置，默认 PP-OCRv6 免下载）→ `DetectionBridge.runOCR` → `ocrToBubbleRegions`。⚠️ **翻译前一定放锁**（那是 OCR/超分引擎锁，不是翻译锁） |
| 翻译 | `TranslateUtils.translateBubbles` | 漫画那套编号批量 + prefill + 空译文回退原文 + 取消看门狗；本地引擎（NLLB/LlamaCpp）在 `TranslateUtils` 内统一取得 `LocalTranslationCoordinator.mutex`（判据 `isLocalHeavyEngine()`） |
| 载荷 | `NovelImageTranslation` + `NovelImagePayloadCodec` | 一个块 = 矩形 + OCR 原文 + 译文 + 竖排 + 字号；存 `novel_paragraph_translation.imagePayload`（JSON）。**同一行的 `translatedText` 存拼接译文**（导出/进度/「已翻」判据直接复用），所以图片段在库里就是一条普通成功行 |
| 渲染 | `NovelImageOverlay` | 三态 = 译文（`useOriginalText=false`）/ 识别原文（`true`）/ 纯图（**不调** `renderOverlay`）；按 `(图片路径, 态, 载荷 hashCode, 目标宽, 渲染倍率)` 缓存；`renderScale` 按"这张图最终画多大"给（**不再是 1f**，见下面「⑤ 覆盖层按最终画多大光栅化」）。"原文"是**把 OCR 原文画回原位**，不是裸原图 |
| 交互 | `NovelImageControls` + `NovelPageView`/`NovelScrollAdapter` | **长按只进"编辑模式"**（按钮亮出来，**不自动开翻**）→ 点右下角「翻译」按钮才 OCR+翻译；按钮**画在图上**（贴图片绘制矩形右下角内侧），**画与命中同源**（`controlsOf()` 一份几何） |

**硬约束与踩过的坑**：

- ⚠️ **插图不拆批**（`NovelBatchPlanner.planBatches` 就是普通 `chunked(每批段数)`）：一批里
  可以混着文本与插图，批内插图由 `NovelChapterTranslator` **串行** OCR（见下面那条）。
  真正的约束是"插图**绝不能**被送进文本引擎的请求里"——那由翻译器自己分流保证，
  **不是**靠成批规则拆批（老实现拆批，代价是用户报的「每批段数遇到图片就中断」）。
  ⚠️ 但**批 ≠ 一个请求**：批内怎么拆请求见下面「批内拆请求」那一条。
- ⚠️ **含插图的批之间串行**（`NovelTranslationQueue.imageBusy`，判据是"批内含图"而不是"首段是图"）：
  OCR 是单例引擎，两批含图的批同时起飞只会在锁上干等、还白占一个并发槽位。
- ⚠️ **`imagePayload` 必须在清译文时一起清**（`resetToIdleRows` 的 `imagePayload = NULL`）：
  它是"这张图已经翻过"的**另一份凭据**，留着的话用户点了「删除译文」屏幕上什么都没变。
- ⚠️ **DB v21 → v22**（`MIGRATION_21_22` 加列：可空、无 DEFAULT）。守卫
  `NovelParagraphTranslationDaoTest` —— 那条"迁移建出的表与 Entity 逐列一致"的老守卫会**当场拦下**
  忘记加迁移的情况（真值在 `TranslationHistoryDatabase` 的 `@Database(version=)`）。
- ⚠️ **分母**：开关开着时 `NovelChapterRepository.paragraphCountOf(includeImages = true)` 才算上图片段，
  否则会出现「已翻 6/5」（库里图片行算了成功数、分母里没有它们）。
- ⚠️ **单段 ensure 不能记"分母已补齐"**（`markEnsured = false`，长按单张图那条路）：只拿到一段就宣布
  补齐，后面整章翻译再也不补分母 → 章行显示「已翻 1/1」却还有几十段没翻。
- **三态与聚焦是会话级内存态**（不落库，与漫画"翻页重置为译文"同一取舍）：落库会让下次打开仍停在
  上次随手切的"原图"上，用户以为译文丢了。
- ⚠️ **长按只进「编辑模式」，不自动开翻**（用户口径 2026-10-04 明确纠正：「长按出现翻译按钮，
  点击翻译才会 OCR 翻译」）：长按只把按钮亮出来 + 一句提示 `novel_image_edit_hint`，
  **点那颗「翻译」按钮**才真的 OCR+翻译。之前那版长按即开跑，用户还没看清按钮，几秒的 OCR 与一次
  翻译请求就已经发出去了。
- ⚠️ **按钮个数跟着"有没有译文"变**（`NovelImageControls.buttonsFor`）：
  没译文 → **只有一颗「翻译」**；有译文 → `[重翻][三态][删除]`。
  **翻译与重翻合成一颗按钮**（图标随状态换：翻译用 `ic_reader_translate`、重翻用 `ic_refresh`）——
  拆成两颗会让第一次进编辑模式的人点到一颗"点了什么都不会发生"的按钮。
  删除永远在最右（离前两颗远一点，少误触）。
- ⚠️ **退出编辑模式的入口有三个**（都要真的把按钮收掉，不然会在图上留一排幽灵按钮）：
  点图上**别的位置**（点这张图本身留在编辑模式，方便用户补点按钮）、**翻页**（`turnPage`）、
  换章（`loadChapter` 里清 `focusedImagePara`）。⚠️ 点空白那一支会**吃掉这一下点击**，
  否则退出编辑模式的顺手一点就变成翻页了。
- ⚠️ **选中效果 = 外扩 3dp 的选中框**（`NovelImageControls.selectionRect` + 填充/描边两个函数）：
  填充画在图片**下面**（图片不透明，只留边缘一圈底色），描边画在图片**之后**
  —— ⚠️ 两者都在 `drawImageSegment` 的 `clipRect` 内外分开画：描边在图片矩形外侧，
  画进裁剪里会被切掉上/下两条边。**任何尺寸的图都看得见"已选中"**（描边不随图变小而消失）。
  用**蓝色**（与文本段选中同一色相），不用翻译的琥珀 —— 琥珀的语义是"这批正在飞"。
- ⚠️ **图太小就把按钮摆到图外，绝不缩按钮**（用户口径：「要考虑到多种图片尺寸，翻译按钮可能因为
  图片尺寸太小没法放在图片上面」）。`NovelImageControls.layout` 三步走：
  ① 图内右下角（`stripW + 2*inset ≤ 图宽` 且 `按钮高 + 2*inset ≤ 图高`）→
  ② **图片正下方**、与图右边缘对齐 → ③ 下方没空间就放**图片正上方**；
  最后统一夹进**正文框**（`boundsLeft/Top/Right/Bottom`）。只有正文框比按钮条还窄（极端分屏）
  才整体等比缩小。⚠️ 滚动模式条目高度就是图高，所以它的 `boundsBottom` 用 `height`（含段间距）。
- ⚠️ **退出编辑模式不许打断在途翻译**（用户口径：「翻译过程中也可以快速返回退出图片编辑模式，
  翻译**不会**因为退出编辑模式而暂停，退出阅读器会自动暂停」）：点别处 / 翻页只清 `focusedImagePara`，
  **不** `imageJob?.cancel()`（翻译继续跑，顶部浮层继续显示「正在翻译图片…」，完成后照样落库上屏）；
  `onStop`（离开阅读器 / 切后台）才取消。守卫：`NovelImageEditWiringTest`（源码级：
  长按不许开翻 / 按钮路由 / 清聚焦不取消 / onStop 取消 / 没聚焦不画不命中）。
- ⚠️ **`NovelImageControls` 的几何不许建在 `RectF` 上**：几何是"纯算术、普通 JVM 单测"的
  （`Paint` 都是 `by lazy`），而 `RectF` 在普通 JVM 单测里是**空实现**（构造完四个字段全是 0，
  守卫 `选中框外扩 3dp` 就是这么红过的）→ 用自己的 `Box` 数据类，只在画的时候 `toRectF()`。
- ⚠️⚠️ **含插图的批，外面绝不能再持 `OcrLock`**（2026-10-04 真机事故：「翻译过程中只要遇到
  图片就会卡住，等了很长时间显示 `IllegalStateException: OCR 引擎被占用（别的翻译/超分正在跑），
  稍后再试`，而我**没有任何别的占用**」）。根因是 `OcrLock` **不可重入**：队列在 `serial`
  （本地引擎，或 AUTO 被夹成 1 后再看引擎）时整批持锁，而插图流水线要**自己**拿这把锁做 OCR ——
  自己等自己 60s。现在 `NovelTranslationQueue.runInFlight` / `runBatch` 遇到**批内含图就跳过外锁**
  （批级串行改由 `imageBusy` 保证），并把等锁上限从 60s 收到 20s。
  守卫：`NovelTranslationQueueTest`（插图与文本同批 / 三张连续插图同一批 / 含图批之间串行）。
- ⚠️ **插图也要有"状态框"**：分页与滚动两处绘制都按「选中(蓝) > 失败(红) > 正在翻译(琥珀) > 刚翻完(绿)」
  给插图描边（`NovelImageControls.drawFrame`）—— 只高亮文本段的话，一整批都是图时屏幕上毫无反馈。
  ⚠️ **描边色必须用 `NovelTextRenderer.BORDER_*`（不透明），不能用底色那几个 `COLOR_*`**：
  底色是 18~24% alpha 的，当 3dp 描边画在图上几乎看不见（图不透明、没有"底色"可铺，
  框是它唯一的状态载体）—— 那是"改了但没生效"。
- ⚠️ **插图允许放大，但封顶 3×**（用户口径：「显示的图片感觉都太小了」）：`NovelImageMetrics`
  的 `MAX_UPSCALE = 3f`，同时仍受"列宽"与"一页高"两道限制。⚠️ 不能无上限：电子书里 1×1
  的占位/间距小图会被撑成一整屏色块（老实现因此写成"只缩不放"，代价是真插图贴着原始像素显示）。
- ⚠️ **绝不裁图**：绘制端按「本段记的高度」与「一页正文高度」取**较小值**再 `fit` 一次
  （`drawImageSegment`）—— 换排版参数/换屏尺寸的瞬间分页与绘制可能不同源，那时按分页记的高度
  画就会顶出这条带子（用户口径：「绝不允许图片内容被切割遮挡」）。
- ⚠️ **返回键先退编辑模式**（用户口径：「选中文本可以通过返回退出编辑，为什么选中图片返回直接就
  退出阅读器？」）：`OnBackPressedCallback` 里 `selecting` 与 `focusedImagePara != null` 同一套处理，
  都只退出编辑态、不 finish、**也不打断在途翻译**。
- ⚠️ **含图批串行有两条路**（缺一条就出现"因为排队而失败"）：
  前台队列 `NovelTranslationQueue.imageBusy`（判据=批内含图）；**整章任务**走
  `NovelChapterJobHost.imageGate`（一把 Mutex，只圈含图批，不影响纯文本批的并发）。
  两处都必须 try/finally 成对放 —— 取消/异常路径漏放就是"插图永远排队"。
- ⚠️ **插图进度要进通知栏**（阅读器关掉时也看得到）：`ActiveChapterJob.stageText`（默认 null，
  漫画侧零影响）+ `TranslationJobService` 文案优先级 **暂停 > stageText > 仅OCR > 进度**；
  ⚠️ 暂停必须优先，否则"暂停在插图 OCR 中间"仍显示「正在识别插图 2/3…」（骗人）；
  宿主按章存 `imageStage`（多章并行不串），并在 `forgetChapter`（收尾/取消）里清 —— 不清的话
  下一轮任务会把上一轮的"第 2/3 张"重新发到通知栏。
- ⚠️ **强制退出要清两样**（`pauseToManual`）：插图进度文案 + 库里残留的「翻译中」
  （`NovelChapterTranslator.resetTranslating`）—— 在途批被 cancel 时写 SUCCESS/FAILED 的代码不会执行，
  不复位的话面板会把那几张图永远显示成"翻译中"。
- ⚠️ **同一张图不许被两路同时翻**：`imageInFlightElsewhere()`（整章在飞 ∪ 队列在飞 ∪ 手动那张）
  → 翻译/重翻/删除都先拦一下并提示 `novel_image_already_translating`。
  删除尤其要拦：删了之后那一批落库会把它**写回来**（用户看到"删了又出现"）。
  手动翻的那张图同时要进队列的 `excludedParas`（库里还是 IDLE，不去重就是同一张图 OCR 两遍）。
- ⚠️ **浮层的"已翻/未翻"判据含插图载荷**（`isUnitTranslated`）：`translations` 装的是
  "这一段显示什么文字"，图片行虽然也有 `translatedText`，但**载荷才是插图真的翻过的凭据**
  （载荷丢了/被清时两者会不一致）。
- 守卫：`NovelImageConcurrencyGuardTest`（含图批不持外锁 / 两条串行路 / 强制退出清进度与复位 /
  通知栏优先插图进度 / 同一张图不被两路翻）。
- **长按单张图不受开关限制**（那是用户对这张图的显式指令）；开关只管自动/增量/整章/手动一批
  要不要把插图也带上。
- 图片视图的载荷走 **`setImageState(visuals, focused, translations)` 单独灌**，不读适配器手里那份
  `ChapterContent`：`submit` 会把阅读位置一起掀掉，不值得为一张图重提整章内容。
- ⚠️ **覆盖层坐标的基准是「原图像素」**（两处缩放必须成对，别再各写一套）：
  OCR 跑在**采样后**的位图上（`NovelImagePipeline.OCR_MAX_SIDE = 1600`），渲染又按**显示宽度**
  再采样解码一次 —— 所以 `toBlocks(bubbles, scaleToOriginal)` 要把矩形/字号乘回原图坐标系，
  而 `NovelImageOverlay.bubblesOf(..., scale = 底图宽/原图宽, boundsW, boundsH)` 再缩回底图坐标系
  **并夹进底图内**（越界矩形会让排版内核算出荒唐白块而抛异常 → 被 catch 吞掉 → 表现成"这张图永远
  不显示覆盖层"）。守卫：`NovelPageViewImageTest.载荷坐标按底图倍率缩放且夹在底图内`。
- ⚠️ **首次渲染在主线程**（`onDraw` → `NovelImageOverlay.of`，解码 + `renderOverlay`）：
  一页一张全宽插图约几十到一百多毫秒，靠 48MB 的 LRU 保证**每张图每个态只付一次**。
  真要消掉这一下，得在 `loadChapter` 的 IO 阶段按 `NovelImageMetrics.fit` 的宽度预渲染进缓存
  （注意缓存键含宽度，预渲染必须用**同一个宽度**）—— 目前没做，先记为已知取舍。
- 守卫：`NovelImagePayloadCodecTest`（编解码/坏数据不抛）、`NovelImageControlsTest`（贴右下角/命中/窄图）、
  `NovelBatchPlannerTest`（图片独占批 + 顺序不变）、`NovelChapterTranslatorTest`（图片走宿主 + 载荷落库 +
  失败写原因 + 没宿主时照旧跳过）、`NovelPanelSyncTest`（开关回推）。
- **未做**：`仅 OCR` 执行模式对插图不生效（插图始终 OCR + 翻译）—— 注意**整个小说阅读器都不看这个开关**
  （文本那条路也没接 `TranslationExecutionMode`，grep 全包无引用），所以这不是插图独有的缺口；
  章任务的批行文案对图片单位仍显示「第 x–y 段」（一张图时 x == y）。

### 插图翻译的请求、显示与状态

用户口径（四条一起提的）：

> 「当前多张图片的结果**一起出现**，应该是**先 ocr 完成返回的就先显示结果**，而不是非要等到整个批次
> 都完成再一起显示；……图片虽然属于某个批次的一个段落，但是他应该是**单独发送一个 api 请求**，
> 不要和前面的文本或者其他图片识别结果放到一起发送出去；例如一个批次：**文本，图片，文本**，
> 三个段落全部变黄，但是要**分三次 api 请求**；如果是**文本，文本，图片**的批次，则前两个作为一次请求，
> 图片识别的结果单独一个 api 请求（注意这里的 api 请求数受到最大同时请求数的影响）；然后长按图片是编辑，
> **点击图片应该要可以放大查看全图**（复用漫画阅读器那边的逻辑，底部右下角也要有翻译、切换和删除按钮）；
> 上一次翻译完成的段落（不管是对比还是原文模式），都要**换一个背景色**用来提示刚刚翻译的是哪一段，
> 4 种翻译模式都要有这个机制。」

**① 批内拆请求（`NovelBatchPlanner.requestRuns`）**

| 批内段型 | 请求数 |
|---|---|
| `[文本, 图片, 文本]` | **3**（文本 / 图片 / 文本）—— 顺序**就是文档顺序**，不重排 |
| `[文本, 文本, 图片]` | **2**（两段文本合并 / 图片） |
| `[图片, 图片, 图片]` | **3**（一张一次 OCR） |
| 一批纯文本 | **1**（老行为零回归） |

- ⚠️ **批 ≠ 请求**：批仍是进度 / 黄底 / 配额 / 每批段数的单位，拆的只是"这一批内部发几次"。
  拆**批**的代价早先踩过（用户报的「每批段数遇到图片就中断」），别用拆批去做这件事。
- ⚠️ **批内请求是串行的**：插图 OCR 是单例引擎（`OcrLock`），文本与插图也没必要抢带宽。
  **跨批的并发仍由队列的「最大同时请求数」管** —— 所以同时在飞的请求数**不会**因为拆请求而变多
  （上界仍是 N；批内 ≤1）。
- ⚠️ **一批的段一起标「翻译中」**（`prepareChapterForBatch` 喂整批，不再只喂文本）：三张图要同时
  显示黄色状态。⚠️ 但 `textDeclined`（超长预警里用户选了取消）时**只准备插图、且 `markEnsured=false`**
  —— 老行为是"取消 = 这一批一个字都不写库"，喂整章 `paragraphs` 会顺手把整章分母建出来。

**② 先完成先显示（`NovelChapterTranslator.onUnitSettled`）**

- 每**落库一个请求单位**就回调一次 `(chapterIndex, 段号)`；阅读器（`onUnitSettled`）立刻上屏。
  四条翻译路都在 `translateBatch` 里汇合 → **一处接线四种模式全生效**（手动 / 自动 / 增量 / 整章）。
  ⚠️ 整章那条路是**另一个 translator 实例**（在 `NovelChapterJobHost`），所以宿主有一个
  `@Volatile var onUnitSettled` 由阅读器 `onStart` 挂 / `onStop` 摘（与 `onImageStage` 同一套纪律：
  宿主是应用级的，挂着就是泄漏）。
- ⚠️ **上报顺序排过序**（`keys.sorted()`）：`result.translations` 是引擎按**回文顺序**建的
  （模型会逆序回包），拿它直接上报会让日志/断言随模型脾气变。
- ⚠️ **插图走轻量上屏**（`applyUnitResult`）：只 `loadImageTranslations` + `refreshImageChrome`
  + `refreshTranslationChrome`，**不重排**。插图段高度由图片自身尺寸定、与有没有译文无关 ——
  走 `refreshTranslations → loadChapter` 会白白重排整章（三张图就是三次）还会掀一下阅读位置。
  **文本段相反**：译文比原文长，必须重排并回到同一锚点（那条路仍走 `refreshTranslations`）。

**③ 点插图 = 全屏查看器（`NovelImageViewer` + `dialog_novel_image.xml`）**

- 手势复用**漫画那套** `com.moe.starflow.ui.viewer.ZoomableImageView`（双指缩放 / 双击缩放 / 拖动平移）；
  点一下（未放大时）关闭，放大后那一下留给拖动看图（`dispatchTapWhenZoomed = false` 的既有语义）。
- 右下角三颗基础按钮 = `NovelImageControls.buttonsFor` 同一套（没译文只有「翻译」，有译文才有
  「切换 / 删除」）；**它们不另写一份逻辑**，全部回调 Activity 里图上那排按钮同一条实现
  （`translateImageNow` / `cycleImageVisual` / `deleteImageTranslation`）—— 在途去重、落库、上屏、
  提示都只有一份。⚠️ 显示的是 `NovelImageOverlay` 渲染出来的那一份（按当前三态），
  不是裸原图 —— 否则"放大看全图"会把译文藏起来。
- ⚠️ **`handleTap` 的分区判定顺序 = 优先级**（2026-10-04 真机反馈后重排，**别再改回去**）：
  ① 编辑模式按钮 → ② `chromeHidden` 时**任何点击先唤回 UI** → ③ 菜单按钮那一小块 →
  ④ **点插图（整张图）** → ⑤ 九宫格（中格显隐 UI / 上下左右半屏翻页）。
  - **④ 必须在 ⑤ 之前**：中格是屏幕正中 1/9，会把落在里面的插图**整个吃掉** ——
    整张图正好在中格时**一个点都点不到**，边缘的图也只能点到露在中格外的上下两条。
    用户口径 2026-10-04：「**触发范围应该都是整张图片**才对，有的图片打不开，必须在很刁钻的
    位置才能打开」（滚动模式最明显，因为中格在屏幕正中、图会滚过去）。守卫：
    `NovelImageEditWiringTest.插图优先于九宫格中格`。
  - **③ 不是"右上角一大片"**：老实现是 `y < h*0.22f && x > w*0.72f`（按钮的十倍以上），
    在按钮**下方老远**点一下也弹面板（用户口径：「点击屏幕右上角（菜单按钮的下方很大一片区域）
    都可以打开底部菜单」）。现在 `inMenuZone` 按 `btn_menu` 的**真实屏幕矩形 + 6dp** 算，
    chrome 隐藏（按钮 GONE）时直接不认。守卫：`NovelImageEditWiringTest.菜单角贴着菜单按钮算`。
  - **② 是"点图开查看器"必然带来的口子**：整章都是插图时，用户把 UI 藏起来后就再也调不出
    面板/返回键了。所以隐藏态下**任何一次点击先唤回 UI**（Koto 式"点一下唤出 UI"），
    代价是"藏起 UI 后开查看器要多点一次"。守卫：`NovelImageEditWiringTest.UI 隐藏时先唤回 UI`。
- ⚠️ **漫画阅读器里是同一份老分区**（`MangaReaderActivity.consumeChromeOrMenuTap` 的
  0.22/0.72 + 中格）：那边没被反馈过、也没有"插图段落"这回事，**本分支没动它**；
  要统一时两边一起改（改了记得同步两边文档）。
- 与「同时翻译图片」开关**无关**：那开关管批量翻译要不要带上插图，而"点开看大图、对这张图下指令"
  是用户的显式动作（与长按同一口径）。

**⑤ 覆盖层按"最终画多大"光栅化（2026-10-04 真机反馈：小图译文糊）**

用户口径：「**渲染回图片的译文非常不清晰，图片尺寸小，但是你的翻译文本没必要也为了贴合原图
搞得那么模糊**」。根因与漫画那条**逐字同源**（见 `manga/CLAUDE.md`「低分辨率漫画译文模糊」）：

- 插图**允许放大到 3×**（`NovelImageMetrics.MAX_UPSCALE`）：一张 400px 宽的小图会被拉到 ~1000px 显示；
- 而覆盖层原先恒按底图分辨率光栅化（`renderScale = 1f`）→ **拉上去的字形是插值出来的像素** → 糊。

修法（一个字都不改排版、也不用超分）：`NovelImageOverlay.renderScaleFor(drawnWidth, baseWidth)`
= `drawnWidth / baseWidth` **夹到 `[1, 2]`**，交给 `OverlayRenderer.renderOverlay(renderScale=…)` ——
字形的清晰度由**落笔时的 CTM** 决定，在"已被放大的坐标系"里画就天然清晰。

- `drawnWidth <= baseWidth`（底图够大、画的时候是缩小）→ **1f**，老行为零内存增量；
- 只有"小图被放大显示"才超采样，且封顶 2×（×2 = 4 倍像素，输出还进 48MB 的 LRU）；
- 调用方传的 `targetWidth` **必须是这张图在屏幕上的实际绘制宽度**：分页/滚动传 `size.width`，
  查看器传 `fitCenter` 之后的宽度（竖长图在宽屏上占不到整屏宽，按屏幕宽渲染纯属白占内存）；
- 缓存键补上了 `renderScale`（底图宽取决于采样率，而解码缓存按文件路径存，可能被别的目标宽解过）。

守卫：`NovelImageOverlayTest`（够大不超采样 / 小图按比例 / 封顶 2× / 底图宽为 0 兜底）。
⚠️ **"文字变清晰"本身只能在真机上看**：Robolectric 的 Canvas 既不兑现 `canvas.scale()` 也不兑现
`drawBitmap` 的目标矩形（漫画那边实测过），所以单测锁的是**倍率取值**，清晰度由构造保证。

**④ 「刚翻完」的高亮（`NovelTextRenderer.COLOR_LAST_TRANSLATED` = 淡绿底）**

- 与琥珀（**正在飞**）是前后两个时刻：译文到达后琥珀立刻擦掉，那几段就再没有标记，
  而译文引起的重排会让人一时找不到刚才读到哪 —— 绿底就是补这一拍。
- ⚠️ **只留最后一次**（`recentPara` 是可替换的集合，不是累积）：语义是"刚刚翻的是哪几段"。
- ⚠️ **按段号高亮**，所以原文 / 双语 / 译文三种显示模式下都在（用户点名要求），
  4 种翻译模式共用 `onUnitSettled` 一条接线。
- ⚠️ **换章才清**：`loadChapter` 入口处判 `index != chapterIndex`（那时 `chapterIndex` 还是旧章）——
  同一章的译文到达重排也走 `loadChapter`，在那里清就是"刚点亮又被自己抹掉"。
  用户清除译文 / 删除插图译文那两条路也要把对应段号摘掉。
- 优先级：**选中(蓝) > 失败(红) > 正在翻译(琥珀) > 刚翻完(绿) > 阅读背景**（"还在飞"必须压过"刚翻完"）。
- 插图没有"底色"可铺 → 它用**绿框**表达（见上面那条 `BORDER_*` 的说明）。
- 守卫：`NovelImageEditWiringTest`（点图看全图 / 查看器按钮复用 / onStop 收窗口 / 落库即上报接线 /
  插图轻量上屏 / 两种阅读模式都收到高亮 + 换章才清）。

### 翻译执行语义

- 一批由连续段落组成，默认 2 段、可设为 1–10 段，且不跨章节。
- 手动模式从当前页首个未翻段开始执行一批。自动模式逐批翻译当前页，页内完成后等待翻页；每次最多执行一批。
- 增量模式使用滑动窗口，从当前页起点向后处理“向后批数 × 每批段数”的范围；窗口耗尽后等待翻页再前移。它和章节任务使用设置的并发数。
- 页面范围变化或在途任务全部结束后，调度器会等待页面稳定再领取任务。增量模式只在整条流水线空闲时补位；高亮只显示当前页实际在执行的段。
- 手动空闲时双击翻译按钮快捷开启自动模式；单击先等 300ms 双击窗口，超时后才翻一批。自动/增量队列及手动批次运行时，单击只提示，双击取消在途请求并返回手动模式；打开面板也停止前台任务并回退手动。长按翻译按钮在上方弹出手动 / 自动 / 增量快捷选项，选中后关闭；长按正文仍用于选择段落，选择模式下空闲点击直接翻选中段，在途选段任务同样由双击结束。取消手动任务时同时取消协程和队列引擎请求，旧协程不能清除新任务的状态；不提前释放 OCR 锁。退出阅读器或切到后台会停止页面队列、终止在途请求并返回手动模式；独立的章节任务由应用级宿主持有，可继续运行。
- 守卫：NovelBatchPlannerTest、NovelTranslationQueueTest（模式推进、防抖与清除译文后的处理）和 NovelTranslationConcurrencyTest（并发补位与自动模式串行）。
### 章节批量翻译任务与并发

> 用户口径：与漫画**同一套**形态 —— 「翻译本章 / 清除本章译文」从面板挪到**每个章节卡片**上、
> 翻译本章要能**后台跑 + 通知栏看进度 + 随时暂停/取消**、排队中的批要显示「等待」、
> 文本可以比漫画更激进（**最多 10 个同时在飞**、错峰 500ms）、单批内容过长得先问一声。

**共享基础设施在 `translate/batch/`（漫画共用，改它之前看 `translate/CLAUDE.md`）**：
`ChapterJobRunner`（准备阶段串行 + 翻译阶段并发 N + 按章暂停/取消/等待）、`TranslationJobRegistry`
（通知栏唯一数据源）、`TranslationJobService`（前台服务，**每章一条通知**）。小说侧的宿主是
`NovelChapterJobHost` + `object NovelTranslationHub`（仿 `ReaderTranslationHub`：应用级 scope、
按 `novelId` 缓存、实现 `ChapterJobSource` 并 `register`、**无 UI 且无任务时回收**并取消收集协程）。

同一本书多章按提交顺序排队，仅一章处于 `RUNNING`，其余为 `QUEUED`；当前章在途批次全部结束后才启动下一章。暂停保留队列、等在途批次结束后跳过；继续不抢占当前章。章节卡片、浮层及通知栏对排队章显示“等待”；章内批次仍使用既有请求并发设置。

章节任务锁屏和退出阅读器后继续，使用共享前台服务及 CPU 唤醒锁；宿主同时观察章节状态和在途项，暂停或取消后的收尾仍计入保活，完全结束后释放。阅读器进入后台后不再创建章节浮层，新的超长预警直接放行，避免无人可见的弹窗阻塞后台任务。保活与通知约束见[共享任务指南](../translate/CLAUDE.md)。

章节异步读取和分批前同步发布准备态，未知总量使用不确定进度，不能依赖服务的固定宽限期；准备期间允许暂停、取消和去重提交，取消后等读取清理完成再释放宿主，迟到结果不能入队。正式入队继承暂停状态。已经显示的超长预警在切后台／锁屏时同样放行并关闭弹窗。

准备任务等待提交时所有尚未退出的先前准备任务，取消中间等待章不能让后章越过仍在读取的前章。章节状态聚合使用宿主自有的子作用域，宿主关闭时取消并等待其退出，不取消进程级作用域。

**面板 UI（改了面板就必须跟着改这几处）**：
- **字号层级（用户口径 2026-09-27）**：**筛选 tab 15sp > 章卡片标题 14sp > 页/段行 13sp**；按钮 12sp
- 章行 = **卡片**（`item_novel_chapter_card.xml`）：章标题 14sp 加粗 +
  **两个按钮**（主：`翻译本章 ⇄ 暂停 ⇄ 继续`，实心药丸；次：`清空译文 ⇄ 取消`，淡红药丸）+
  第二行进度/失败/字数 + 展开箭头 + 失败明细面板。**点卡片空白 = 切到该章**（切章只从记录里点）
- 失败明细保留段号、失败时间、译者、源/目标语言、失败原因和截断后的原文；失败信息由 DAO 查询直接提供，增加字段时同步更新 DAO 投影与 UI。
- **卡片的样子**统一走 `CardBackdrop`（`mangaimport/reader/CardBackdrop.kt`）：
  **浅色面板 = 白底 + 1dp 细描边**（面板本身是白的，灰块会"和整体 UI 不搭"）、深色 = 深灰块 + 亮描边；
  圆角 10dp + 卡片间 4dp 留白 + `RippleDrawable` 水波（自绘底之后 `?selectableItemBackground` 就没了）
- ⚠️ **展开出来的行必须"在章卡片组里面"**：`CardBackdrop.applyNested(...)` —— **方角 + 左侧竖线 +
  上下零间距 + 缩进**（`item_translate_page_state.xml` 的 marginStart=14dp）。只做缩进+同色不够
- **在飞批也是行**：`activeBatches`（宿主 `activeByChapter()`，来自 **`runner.translatingPages()`**）在展开的章里
  逐批显示、**琥珀高亮 + 「翻译中」徽章**，排在「等待」行前面；宿主在**开翻前**也推一次 UI，
  否则 N 个批同时起飞要等第一个返回才看得见高亮。
  ⚠️ 判据是 **`translatingPages()`（真进翻译段）**，不是 `inFlightPages()`（含"已预取还没开始"，
  见 `ChapterTaskStage`）；`waitingBatches` 也相应改成 `waitingPages + queuedPages`
  （2026-09-28 与漫画面板「识别中 / 翻译中 / 等待」三段对齐）
- ⚠️ **`waiting` 属性必须自己 `rebuild()`**：面板推送顺序是「先 waiting、后 active」，而 `active` 为空时
  会提前 return —— 少了 rebuild，"刚排上队的批"要等下一次别的字段变化才出现（`NovelPanelSyncTest` 卡这条）
- ⚠️ **面板里原来的全局 `btn_translate_action` / `btn_translate_clear` 已按用户要求删除**，别再往面板加
- **翻译参数分两层**：**「等待页面稳定」（原「启动延迟」，2026-10-04 改名）单独一行**（常显）；
  **其余四个收进可折叠组「批次处理」**
  （`row_batch_group` + `btn_batch_group` + `batch_group_content`）：组内顺序 = **启动增量翻译向后 →
  每批段数 → 单批预警 → 翻译模型最大同时请求数**，**默认折叠**，展开态存 prefs
  （`novel_batch_group_expanded`，`NovelPanelSheet` 里那一份 prefs）。
  **取值排版统一成「段落」那套**（标签在左、取值贴右同一行，进度条另起一行占满整宽，说明再下一行），
  五项都要有说明文案；所有新控件（含 `tv_debounce_hint`/`tv_ahead_hint`/`tv_batch_hint`/
  `tv_batch_group_title`/`tv_batch_group_arrow`）都**必须**接 `applyPanelTheme`，否则深色面板下不可见
  （`NovelPanelThemeTest` 会拦）
- ⚠️ **「批次处理」组与下面的筛选（历史记录）栏之间留 10dp**（用户口径：说明别紧挨着那一栏）
- 新增宿主字段照旧**两个方向各一个出口**：`NovelPanelHostState`（+`NovelPanelState`）→
  `renderHostState`；面板 → 宿主走 `NovelPanelCallbacks`
  （`onChapterPrimary` / `onChapterSecondary` / `onChapterClear` / `onConcurrency` / `onBatchWarn`）。
  `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ 章卡片**完成态**显示「翻译完成」标记 + 「清空译文」；**只有任务真的在跑/暂停**时才显示「暂停/取消」
  （判据是 `running || paused`，别写成 `job != null`/`jobActive` —— 那会让已完成的章一直显示暂停与取消）

**并发（`NovelTranslationQueue`）**：
- **N = `TranslationConcurrency.novelConcurrency(context, prefs)`**（`novel_concurrent_requests`；
  **本地引擎恒 1**）。⚠️ 滑块必须读写**默认 prefs** —— 阅读器自己那份是 `novel_*`，写错了就是
  "滑块能拖、值也存了，但翻译永远用默认值"的静默失效
- ⚠️ **默认值 = 3**（原 5，用户口径 2026-10-03）：老用户里「还停在旧默认 5」的由
  `NovelPanelStyle.migrateConcurrency` 一次性挪到 3，**自己拖过滑块的不动**（与 `migrateLineSpacing` 同一套）
- 调度循环：满员（`inflight.size >= N`）时 60ms 轮询**补位**；
  ⚠️ **AUTO 下 `slots` 被夹成 1**（自动恒串行，见上「翻译口径」），N 只作用于增量；
  `N > 1` 且未满员时按 **500ms 错峰**启动下一批
- **等页面稳定**：见上「翻译口径」那条（翻屏 **或** 上一批收尾都排一次；
  `AtomicBoolean spanChanged` / `settled`，日志 `页面稳定等待 Nms`）
- `done = 已译 ∪ failedAnchors ∪ **在飞批段号**` —— 最后一项是并发的前提：
  不把在飞的批当"已完成"，同一个锚点会被反复挑中 → 同一瞬间发出 N 个一模一样的请求（白烧额度）。
  ⚠️ **没有 `userCleared` 这一项了**（2026-10-04 撤）：清译文 = 一次显式编辑，宿主当场停队列 +
  回退手动，之后那几段就是普通"没译文的段"（见「清除选中段的译文」那条）
- ⚠️ **账本 `failedAnchors` 用并发集合**（N>1 时有多个工人读写），
  且**在飞账本的一切读写都要持锁**：曾出现"调度侧无锁 `sorted()` 迭代 + 工人侧加锁 `removeAll`"
  → `ConcurrentModificationException` 抛在 `launch` 里（scope 无异常处理器 = 崩进程）
- 纯文本批次不持 `OcrLock`，本地模型由请求工作线程统一串行；插图只在 OCR 阶段持锁并登记执行任务。取消使用单个请求句柄，不能中止共享模型上其他书籍或截图任务。
- 暂停 = 只停取批、队列留着（=「等待」行）；取消 = 丢还没开始翻的批、**已翻好的保留**；
  **在途批「强制结束」**（`stop()` → `job.cancel()`：协程取消，译文只在成功后写库 ⇒ 取消不入库、也不等它
  —— 与漫画「打开面板/退出阅读器」的强制退出同一套，2026-09-28 核对过）。
  「等待」= `runner.waitingPages` + `queuedPages`（章任务）为唯一数据源，不要拿"提交数 − 完成数"推

**单批超长预警**：`NovelBatchWarning`（档位 `512/1024/2048/4096/8192/16384`，默认 **2048**
（2026-10-03 用户口径，原 4096；老用户里"还停在旧默认 4096"的由 `NovelPanelStyle.migrateBatchDefaults`
一次性挪到 2048，"每批段数 3 → 2"同一个迁移）；`utils/TokenEstimator` 粗估 token）+
`NovelBatchWarnGate` / `NovelOversizeConfirmer`（**同一轮只打扰一次**）。
⚠️ **档位区间是围绕默认值铺开的**（用户口径 2026-10-04：「滑动范围要根据当前默认值调整到合理的
范围」）：默认 2048 落在第 3 格，往下还能拖到 512/1024 —— 原档位从 2048 起、默认值顶在最左端，
滑块只能往右拖，"想更早被提醒"这条需求在界面上**根本走不通**。
判据收敛在 **`NovelChapterTranslator.translateBatch` 一处**（翻译本章 / 多选 / 自动 / 增量全汇到这里，
不会漏路径）；⚠️ **后台任务（阅读器已关）没有 UI 可弹窗时必须放行**，否则任务会静默停在一半。

⚠️ **弹窗的两个出口**（用户口径 2026-10-03）：
- **忽略并继续**（肯定）→ 照翻
- **取消本次翻译**（否定）→ **关闭当前翻译进程 + 回退手动**（不再是"只跳过这一批"）：
  前台队列走 `NovelReaderActivity.cancelCurrentTranslation()`（停队列 + 中止在途请求 + 模式回手动 +
  取消当前章的整章任务）；整章任务那条路在 `NovelChapterJobHost.translateUnit` 里收尾
  （`result.isEmpty && oversize.hasDeclined()` → `cancel(章)`），否则那一章会被一批批静默跳过
- 60s 不点 = 当作"继续"（提示 `novel_translate_oversize_timeout`），不能让后台任务无限挂着

**暂停提示（2026-10-04 更新：退出 与 切后台 同一套）**：**离开阅读器（返回键/关掉）**、
**切后台（home/息屏）**、**打开面板** 都是**强制退出在途翻译（含中止 HTTP）+ 回退手动** →
文案 `reader_translate_force_stopped_to_manual`（「翻译进程已强制退出，回退到手动模式」）；
**章节任务取消**（本来就手动）→ 只报 `reader_translate_force_stopped`（用户口径 2026-09-28）。
提示在 **`onStop`** 里发（`onDestroy` 里弹的 Toast 常被系统吞掉），`onDestroy` 只兜底 + 防重复
（兜底那条也把模式回退手动）。
⚠️ **提示必须走 [showSticky] 语义**（`showPausedNotice` 里那条注释）：提示发完紧接着就是 `onDestroy` 的
`TranslationStatusOverlay.dismiss()`，普通 `show()` 会被**当场抹掉**（只剩几十毫秒的一闪）——
用户报的「退出阅读器**没看到**回退提示」有一半来自这里。
⚠️ `pauseToManual` **不再动章节任务**（那是后台任务，暂停前台队列不该打断它）。
⚠️ `novel_translate_paused_background`（"已暂停，回来继续"）**已废弃**：切后台不再只暂停。

**两条"提示"通道别搞混（用户 2026-10-04 明确纠正过）**：
| 用户说的 | 实际指 | 实现 |
|---|---|---|
| 「**app 的通知系统**」 | 应用内的**状态浮层**（`TranslationStatusOverlay` 的芯片） | **等页面稳定那一拍 → 「等待页面稳定…」**（队列阶段 `WAITING_STABLE`，此刻还没发请求、也没有黄底）；自动/增量翻译中 → `showImmediate`「正在翻译 · 第 X/Y 页」；**整章 → `showRunning`「整章翻译中 · 第 N 章 x/y 批」**（`NovelReaderActivity.refreshChapterJobChips`，与别的提示并存、可按句柄移除） |
| 「通知」（系统通知栏） | Android 通知（**要下拉状态栏**才看得到） | 整章 = `TranslationJobService` 每章一条（id 10000+）；阅读器队列 = `ReaderTranslationNotify`（id 9997）。通道 `translation_progress`（DEFAULT 重要度） |

**通知栏（用户口径 2026-10-03：翻译过程的提示**必须进通知系统**，不只是面板徽章）**：
- **整章任务**：`TranslationJobService` 每章一条（注册表驱动），通道走
  **`TranslationNotifyChannel.ensure`**（唯一入口，`translation_progress` + **`IMPORTANCE_DEFAULT`**）
  ⚠️ 旧的 `chapter_translation` 建成 `IMPORTANCE_LOW`，而通道重要度**创建后 App 改不了** →
  MIUI 上不亮状态栏图标、还容易被折叠成"不重要通知"，用户报的「整章翻译没看到通知」就是它。
  换 id + 顺手删旧通道，守卫 `translate/batch/ReaderTranslationNotifyTest`
- **阅读器前台队列（手动一批 / 自动 / 增量）**：`translate/batch/ReaderTranslationNotify`
  （固定 id 9997，避开汇总 9998 与每章 10000+），由 `NovelReaderActivity.observeQueue` 逐次状态镜像
  ⚠️ **它也必须先 `TranslationNotifyChannel.ensure`**：Android 8+ 往**不存在**的通道发通知是
  **静默丢弃**，而这条通知不经过前台服务 —— 只用自动/增量、从没点过「翻译本章」的用户，
  通道根本没人建（这是"看不到通知"的第三种漏法）
  ⚠️ **只在 `TRANSLATING` / `WAITING_LOCK` 时挂着**，DRAINED/IDLE、面板打开、`onStop`/`onDestroy`
  立刻收掉 —— 一条写着"正在翻译"却什么都没在跑的通知比没有通知更糟
  ⚠️ 它**不进** `TranslationJobRegistry`、也**不需要前台服务**：队列活在阅读器的 `lifecycleScope` 里，
  离开阅读器就停（用户明确要求），没有"后台保活"的需求

**守卫**：`NovelTranslationConcurrencyTest`（错峰铺开 / 满员不发 / 结束补位 / N=1 全程单飞 /
并发下失败批不重挑）、`NovelBatchWarningTest`、`NovelChapterTranslatorTest`（预警三条路径）、
`NovelPanelSyncTest`、`NovelPanelThemeTest`、`NovelBatchPlannerTest`、`NovelTranslationQueueTest`。

**几条结构性约定（踩过才有的）**：

- **面板状态只有一个入口**：`NovelPanelHostState`（宿主能改、面板要显示的全部状态）+
  `NovelPanelSheet.renderHostState`。新增这类字段**必须同时加进类型和 render**，
  否则那处 UI 永远停在打开那一刻 —— `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ **一个控件只能出现在两个配色清单之一**（`panelLabelIds` / `panelSubIds`，`intArrayOf` + 标记注释）：
  两段 `setTextColor` 后写的赢 → 同一个 id 两处都登记会**静默变淡色**。`PanelThemeGuardTest` 机械守卫。
- **面板主题也只有一个入口**：`NovelPanelSheet.applyPanelTheme`（深色/浅色随**阅读背景**，不随全局主题）。
  它是手写控件清单，**清单外的控件不会跟着切背景变** —— Tab 图标、筛选 chip、两个弹窗都因此漏过，
  表现是「切了背景有几处颜色不动，关掉面板再打开才正常」。新增控件组必须接进去，
  `NovelPanelThemeTest` 会拦（把修复行注释掉它就会红）
- **报错必须带原始原因**：引擎不能吞 `TranslationResult.Error`，要带出异常类型 + message 的
  cause 链 / 模型返回了什么（`NovelBatchResult.error` → `failCode` → 章行展开里原样显示）
- **无章节标记的 TXT = 整本一章**（不再按 8000 字硬切）
- 翻译按钮旁的**三态切换按页显示**：当前页有译文才出现
- **「保持段落完整」默认关**（排版面板里的开关）：关掉 = 按间距填满页面，段落可被切断
- **右下翻译浮层组的判据全部在 `NovelTranslateChrome`**（纯逻辑 + 单测），别再散在 Activity 里：
  - **翻译按钮**：当前视野还有没翻的可翻译段才显示（翻页 = 本页；滚动 = 当前屏幕可见段）。
    ⚠️ 滚动模式**不要**按整章判 —— 那就成了"始终显示"，用户明确否掉了
  - **三态按钮**：当前页（滚动模式 = 当前屏幕**可见段**）有译文才显示 —— 没译文时点了看不出变化。
    ⚠️ 判据变了要记得**重算**：`onPaged` 与 `updateFromScroll` 都要调 `refreshTranslationChrome`
    （早先只在 `loadChapter` 里调，于是翻页后浮层停在上一次的状态）
  - **「可翻译段」= TEXT 且非空白**（`isTranslatable`）：图片与 <4 字的短行按设计永不翻译，
    算进「待翻译」会让含 `……` 的页永远判不出"翻完了"、翻译按钮常驻且点了没反应
  - **没有「缓存命中」这一套**（与漫画相反）：不允许凭「本章/本页已有译文」把按钮变成重翻图标，
    否则"点一下会发生什么"取决于看不见的状态
- **重翻 = 长按多选段落**（文本阅读器唯一的重翻入口）：长按进选择模式 → 点段落加减选 →
  点空白/返回键退出（切章、开面板、切阅读模式、离开阅读器也清；**翻页不清**）。
  按钮语义随选中集变：全未翻 = 翻译 / 全已翻 = 重新翻译 / 混合 = 翻译+重翻（左侧文案写明 + 段数）。
  翻译时按面板「每批段数」拆成多次请求、逐批上屏；队列走 `translateExact`（**不过规划器** ——
  规划器会把已有译文的段当已完成跳过，那样重翻永远翻不动）
- **清除选中段的译文**（与长按多选**共用同一份选中集**）：选中集里**只要有已翻的段**，右下角就出现
  回收站图标（判据收在 `NovelTranslateChrome.showClear(translatedSelected) = translatedSelected > 0`）→
  弹窗报「将清除选中的 N 段译文」（N = 选中**且已翻**的段数）→ 确定后删库 + 重载本章，
  那几段立刻回原文显示，三态/翻译按钮按新状态重算
  ⚠️ **"用户清过的段别再自动翻"的那份账已经取消**（2026-10-04 用户口径：「清译文后重翻只有
  手动生效，自动和增量即使当前页面刚才删除了译文，开启也没有任何翻译，不会翻译。你要考虑到
  自动/增量/本章翻译模式下编辑删除的情况」）→ 现在：**清译文 = 一次显式编辑**，
  `clearSelectedTranslations` 里当场 `pauseToManual()`（停前台队列 + 回退手动）——
  ① 刚清完**不会**立刻又翻回来（不会"清了个寂寞"）；② 用户之后**再开启**自动/增量时，
  那几段就是普通"没译文的段"，照常被翻。别再往队列里加"跳过账"。
  守卫：`NovelTranslationQueueTest` 的「清掉译文的段重新开启自动照常翻」
- **阅读位置是 `NovelAnchor(段号, 段内比例)`，不是段号**（`NovelAnchors`）：
  一段能跨好几页（只按空行分段，整章一段的样本就是），只按段号恢复会落到**段首那一页/段顶** ——
  译文到达必须重排，重排一次就把读者从段中间拽回段首（用户报的「翻译之后位置跳变」）。
  翻页模式锚点取本页第一个 segment 的段内比例、恢复时找**包含**该比例位置的那一页；
  滚动模式锚点记「首可见段已滚过多少」、恢复时 `scrollToPosition` 后再按新高度补偏移。
  ⚠️ `NovelChapterRepository.pageOfParagraph` 给的是「含该段的**第一页**」，**不要**拿来恢复阅读位置
  （断点续读落盘仍是段号，比例从段首起算）
- **锚点同时是「强制分页点」**（`NovelPaginator.paginate(anchor=…)` → `forceBreak`）：
  `pageOf` 只能给到"含锚点的那一页"，锚点可能落在**页尾** —— 译文比原文短时它被挤回**上一页**，
  读者看到的就是「翻译完回到前一页」（用户报的，`NovelAnchorPageFlowTest` 复现）。
  所以带位重排时必须让锚点那一行**另起一页**：重排前后屏幕第一行是同一行。
  锚点要进 `NovelChapterRepository.load` 的缓存键（不同锚点切出不同页表）；
  滚动模式与跳到章末不传。⚠️ 行号必须由**同一份 layout** 反查（`getLineForOffset`），不能自己除。
  ⚠️ **锚点之后正着填、锚点之前倒着填**（`paginateAround`）：只"提前收页"的话锚点前一页只剩小半页、
  底部一大片空白（用户报的「有的页面提前分页、底部预留大片空白」，双语↔译文/原文来回切最明显）。
  锚点本来就在页上半部分时**不强分**（`shouldForceBreak` 阈值）——那时强分没有收益，只白白扰动页表
- **带位重排期间锚点必须钉住**（`NovelReaderActivity.carryAnchor`）：
  `pageOf` 只能给到**页**（向下取整），落位后 `onPaged` 若把锚点重取成"这一页的页首"，
  页首永远比逻辑位置靠前 → **每批退最多一屏、误差逐批累积、单向往回跑**（实测 5 批丢一半位置）。
  只有用户真导航（滑动/点按翻页/拖进度条/切章/换模式）才允许重取；
  ⚠️ 不能"落地后清"——ViewPager2 同一帧末还会补派发一次 `onPageSelected`，那次会把锚点打回页首。
  `refreshTranslations` 取锚点要在**读完译文之后**（挂起前的锚点会因 IO 期间翻页而过期）。
  ⚠️ 附带纠正：ViewPager2 对 `notifyItemRangeChanged` 与 `notifyDataSetChanged` 一视同仁，
  都不会弹回第一页；真正的坑是**重排后页数缩到当前页号以下**会重置到第 0 页 → 刷新页表后
  必须紧跟 `setCurrentItem`（另有 `snapPagerToAnchor` 兜底）
- **「本章翻完了吗」的分母 = 整章可翻译段数**：SQL 那边是 `COUNT(*)`（数据库里这一章有几行），
  而行是**按批惰性写的** —— 只写这一批的话分母退化成"翻过的段数"，翻了几段就把整章标成
  「已翻译」（用户报的「没翻完却显示全部完成」）。所以 `NovelChapterTranslator.translateBatch`
  开翻前先 `ensureChapterRows` 把整章可翻译段落补成 IDLE 行（`insertIgnore` 幂等），
  章行/筛选再用宿主解析出的真分母（`NovelChapterStateAdapter.totalOf`）而不是库里行数
- **「本章多少字」同理，取整章可翻译段的字符和**（`NovelChapterRepository.charCountOf`，按
  `SPLIT_VERSION` 缓存）—— **不是库里已有的行数**（行是按批惰性写的，拿它当"本章多少字"会随翻的
  进度一起长）。`ensureChapterTotal` 里与分母**一次取回**（同一份段落缓存命中，不额外解析一遍），
  显示在面板章行第二行的「已翻 3/40 · 12,345字」里（`novel_chapter_progress_chars` 拼，中文无空格；
  分母或字数取回 0 时整行回落成只有进度）—— 用户要它是为了估翻译费用
- **增量 = 跟着当前页走的窗口**（不是一次性额度）：窗口 = 当前页第一段往后
  「向后批数 × 每批段数」段；用完停下，用户**翻页窗口自己前移**接着翻（`aheadLimitPara`）。
  面板增量行显示换算后的段数（"3 批 = 15 段"），改批数或每批段数都要重算（走 `refreshDerivedUi`）
  ⚠️ 窗口**起点**取「当前视野**全部**段」（`currentPageSpanIndexes`，含不可翻译的图片/短行），
  **不是**锚点用的那份可翻译段：本页一个可翻译段都没有时，用可翻译段定位会退化成「章首」→
  `remaining` 算成 0、只弹「已向后翻完 0 批 · 翻页继续」却一批都不翻（用户报的「当前页段数不足 →
  增量/自动始终 0 批」）。`NovelBatchPlanner.anchorForMode` 在**本页无可翻译段**时改用窗口起点
  继续向后（**自动模式不变**：本页翻完就停，等翻页）
- 状态芯片显示**翻到哪一页/哪一段**，不显示第几章（增量会一直往后翻，章号看不出进度）；
  且 DRAINED 不再喊「已翻译」—— 走到 DRAINED 有四种原因（翻完本页/翻完窗口/本章真翻完/一批失败被跳过）
- **滚动模式进度条按「像素比例」算，不按段序号**（`NovelScrollProgress`）：
  段落按空行切，一个几万字的章节可能**整章只有一段**（样例 `英文-单段超长-无空行.txt`），
  按「第几段/共几段」算的话比例恒为 0/1 → 进度条永远不动（用户报的「章节文字太多时进度条失效」）。
  位置取 `computeVerticalScrollRange/Offset/Extent`；绿条仍按段序号的**等分切片**（段高要测量才知道，
  滚动模式是懒加载的）。拖拽寻页也按比例滚（`seekScrollTo`），**循环补齐**：估算总长会漂，
  一次 `scrollBy` 未必吃到目标。⚠️ 程序化 `scrollToPosition` **不派发 onScrolled**，
  续读要靠 `novelScroll` 的 layout listener 补刷 —— 那个 listener 必须带「首可见段变了才刷」的闸门，
  否则 `setText → requestLayout → 布局 → 再补刷` 是每帧一次布局的死循环。
- **双语模式段间距 ×1.8**（`BILINGUAL_PARA_SPACING_FACTOR`，在 `NovelPanelStyle.textStyle` 里乘）：
  一段双语 = 原文行 + 译文行，行间只是一个普通行距；段间距不拉开就分不清"这行译文属于哪段原文"。
  ⚠️ 只作用于排版（分页与绘制共用同一份 style），**不改用户设置里那个 dp 数字**
- **琥珀高亮 = 「真的在飞的那几批」**（`NovelTextRenderer.COLOR_ACTIVE_BATCH`，与选中的蓝区分开）：
  只在 `TRANSLATING` / `WAITING_LOCK` 期间高亮在飞的段，**出结果（成功/失败）立刻擦**。
  ⚠️ 早先写的是「认领了就高亮、翻完也不擦」（本意是让你看得见"刚翻的是哪几段"），但一旦某批卡住/失败，
  那几段就**永远黄着** —— 用户看到的「首批一直黄着却不翻译」正是它（2026-10-03 改）。
  「哪几段失败了」改由面板失败明细 + 红条报，不靠底色记。高亮底两种模式都**铺满整行 + 8dp 圆角**
  （`HIGHLIGHT_CORNER_DP`）——只铺正文列会像被两侧裁了一刀
  ⚠️ **「刚翻完」是另一个色（淡绿，`COLOR_LAST_TRANSLATED`）**，2026-10-04 用户要求：
  琥珀只表达"正在飞"、出结果就擦，于是译文到达引起的重排之后用户找不到刚读的那几段 ——
  绿底补的就是这一拍（只留**最后一次**落库的那几段，换章才清；见「插图第二版」那节）。
  四色分工：**选中=蓝 / 正在翻译=琥珀 / 失败=红 / 刚翻完=绿**，插图用同名的不透明 `BORDER_*` 描边
- **三态图标是自绘的** `ic_display_{translated,original,bilingual}`：
  ⚠️ 不得与翻译按钮的 `ic_reader_translate` **同形**（同一浮层组并排显示，用户会直接说"图标重复"）；
  ⚠️ 不要去描汉字字形（描「文」在 22dp 下看着就是「六」，用户问过"这什么玩意"）。
  三枚用同一套「文字块」语言：原文=多行细行 / 译文=一行粗行+一行短行 / 双语=上下两块都画
- **默认行距 1.4×**（原 1.5×）：⚠️ 只改常量对已用过的人无效（prefs 里存着旧默认值），
  所以 `migrateLineSpacing` 会把"还停在旧默认值"的人一起挪过来，自己调过行距的不动
- **段距与字号/行距完全解耦**（用户明确要求）：`paragraphSpacingDp` 只夹在固定的
  `PARA_SPACING_ABS_MIN..PARA_SPACING_MAX`，面板的 `sb_para_spacing` 也**只设固定 min/max**。
  ⚠️ 别把「段距必须大于行距」的动态下限接回来 —— 那样面板要按字号/行距动态改 SeekBar 的 min/max，
  而 SeekBar 会把 progress **夹到新区间**，用户一调字号或行距、段距滑块自己就跳走了
  （用户报的「调字号或行间距，段间距的进度条也会一起变化」）。行距拉大时段距显得密是用户看得见、
  拖一下就能改的事，不该由系统偷偷改他设的值。守卫：`NovelPanelStyleTest` 的
  「改行距不动段距」「改字号不动段距」
- **导出落点 = 手机的 Download**（`MediaStore.Downloads` + `RELATIVE_PATH=DIRECTORY_DOWNLOADS`，
  与漫画 `MangaReaderActivity.writeToDownloads` 同一个落点、同一句提示 `toast_saved_to_download`）。
  ⚠️ 不要写回 `getExternalFilesDir()`：那在 `/Android/data/<包名>/files/` 下，Android 11+ 的文件管理器
  进不去、用户拿不到导出的书（而且卸载即删）。重名交给 MediaStore 去重（会写成 `名字 (1).txt`），
  写完回读**实际**文件名再提示。拼字节那一层拆在 `NovelExport.writeBook`（可单测），
  MediaStore 那层只能真机验（Robolectric 没有 MediaProvider，`insert` 返回 null）
- **模型切换要立刻生效**：队列把引擎**按值缓存**了，设置页只写 prefs —— 所以阅读器监听默认 prefs
  （`NovelEngineConfig` 判定哪些键算引擎配置），变了就丢掉缓存的队列，下次用到时重建。
  ⚠️ 别改成「每批现造引擎」：本地引擎（NLLB）每造一次都要重载模型。监听要注册在**阅读器**里而不是面板里
  （改模型要先离开面板去设置页，面板回来时重新 onStart 才注册，那一刻已经错过变化）

**没做**：真机上端到端跑一次翻译（我只跑到单测 + 装机，实际翻译效果要用户验）。

样本：`tools/gen_novel_fixtures.py --repo` 生成英/日/韩长样本（每章 100 段，固定种子可复现）。
⚠️ **样本必须是被翻译的那一侧语言** —— 中文样本测「翻译成中文」等于什么都没测，
`NovelPaginationRealismTest` 里有语言占比守卫卡这件事。
