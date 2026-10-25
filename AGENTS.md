# 仓库协作规则

本文约定 StarFlow 的任务边界、源码与文档维护、验证及设备操作方式。

## 任务范围与交付

- 开工先核对当前改动和相关调用链，简述目标行为、预计影响范围及可复用实现；按任务复杂度控制说明长度。
- 一次完成一个可独立验收的改动。功能修复、结构重构和无关清理分别安排；扩大到其他职责或子系统时说明必要的因果关系，不顺手扩展任务。
- 新增或提取组件时明确状态、任务和资源的所有权：谁创建、谁更新、谁取消或释放。新增状态优先从已有事实派生；必须独立保存时明确同步入口和终结路径。
- 复用现有入口和共享实现。新增抽象应解决具体的职责或依赖问题；文件变短、文件数增加或代码行数减少本身不作为优化成果。
- 完成时说明实际行为与结构变化、验证结果及未验证部分；重命名、删除或迁移符号后同步核对相关指南。既有用户改动保留，不纳入无关整理。

## 项目与文档

- StarFlow 是 Android 翻译应用。仓库源码和测试是当前实现的事实来源；引用规则前先核对代码。
- 根目录 [CLAUDE.md](CLAUDE.md) 记录跨模块架构与构建约定。修改功能前，先阅读对应模块指南；指南中的技术约束适用于所有开发工具。
- 项目自有 Markdown 使用 UTF-8：一个描述清楚的一级标题、一段简短的用途或范围说明，以及清晰的标题层级（`##` 主章节，`###` 细节）。代码块标注语言；文档间导航使用相对链接；代码路径写成明确的仓库相对路径；列表和表格保持简洁。
- 文档内容应精简、现行且能对应到实现。删除过时规则和对话记录；解释不明显的不变式时，只保留必要的因果说明。避免本机路径、代理地址、设备标识、凭据和私有 keystore 信息。
- `README.md` 面向应用用户和贡献者；根目录 `AGENTS.md`、`CLAUDE.md` 提供共享开发规则；模块 `CLAUDE.md` 说明模块实现。`docs/` 中的本地记录不代表当前实现。
- 用户可见的产品说明写入 `README.md`。`docs/` 保存仅本地维护的设计笔记和计划，不提交到 Git。

## 本地文件与远端源码

- APK、设备截图、日志、临时测试样本和原始模型集合只保存在本地；不要提交 APK 或 QA 截图。
- 已适配模型的本地备份放在 `tools/models/app-adapted/`；待用户确认删除的文件集中在 `tools/待删除/`。这两个目录仅本地维护。
- 根目录 `models/` 保存下载的源权重，仅本地维护。只有应用运行时确实需要的文件才放入 `app/src/main/assets/`；这些资产不是个人模型备份。
- `tools/`、`.reference/` 和 `docs/` 仅本地维护。不要编辑第三方参考检出。
- `sr/`、`guard/` 和 `patches/` 是项目源码或维护中的构建支持文件，应纳入远端仓库。应用实际使用的教程插图保存在 `app/src/main/res/`，属于产品资源，不是 QA 截图。
- `llamacpp/src/main/cpp/llama.cpp/` 由上游维护。遵循其中的 `AGENTS.md` 和 `CONTRIBUTING.md`，不要把 StarFlow 规则写入上游文档。

## 构建、测试与设备

- 使用 Gradle Wrapper，复用项目已启用的 daemon 和增量缓存；日常不要加 `clean`、`--rerun-tasks` 或 `--no-daemon`。
- 不把单元测试、源码守卫或 lint 作为每次任务的固定步骤。文档、文案及局部样式修改优先静态核对；需要 APK 时只执行必要打包。用户要求不测试时，不追加测试或 lint。
- 行为修复需要验证时，先确定具体失败场景，默认只选 1–3 个能验证该行为的测试类，用 `:app:testFocused -PtestClasses=完整类名`；不要按包通配扩展到几百项。可先用 `:app:verificationPlan` 和同一属性检查选集，它不编译或执行测试。
- 只匹配源码字符串、函数名、格式或调用顺序的旧守卫默认不编译、不运行；清单见 `gradle/source-guards.txt`，确有接线审查需求才显式运行 `:app:testSourceGuards`。优先给真实输入、结果与生命周期写行为断言，不新增实现文本匹配断言。
- 完整测试套件（`:app:testAllUnit`）仅在用户明确要求时运行。整个模块/包的回归和完整 lint 仅在用户要求或明确需要排查相应问题时执行。已通过的检查不重复跑；出现旧断言误报时先判定其有效性，不进入“改字符串断言 → 扩大回归”的循环。
- 未获用户明确授权，不运行连接设备的 Android 测试，不卸载应用、不清除应用数据，也不执行其他会改变设备状态的操作。安装、启动和截屏等可见设备操作同样需要授权。
- 获准安装 APK 时，使用 `python guard/install_safe.py <apk> --confirm`。若包名、版本、设备或签名校验失败，立即停止，不绕过保护。
- 无线 ADB 安装时，若 `adb devices` 为空但 `adb mdns services` 能发现设备，不要立即判定设备不可达或要求用户重发地址：先检查广播端口的 TCP 连通性；若 `adb connect` 失败，重启本机 ADB 服务（`adb kill-server`、`adb start-server`），刷新 mDNS 后重试，并确认设备状态为 `device`。只对实际连通且用户指定的目标端口执行安装，最后仍使用安全安装脚本。
- 只有用户明确要求时才提交或推送。

## 模块指南

| 模块 | 指南 |
|---|---|
| 漫画引擎、渲染与处理流程 | [manga/CLAUDE.md](app/src/main/java/com/moe/starflow/manga/CLAUDE.md) |
| 漫画书架与阅读器 | [mangaimport/CLAUDE.md](app/src/main/java/com/moe/starflow/mangaimport/CLAUDE.md) |
| 小说导入、阅读与翻译 | [novel/CLAUDE.md](app/src/main/java/com/moe/starflow/novel/CLAUDE.md) |
| 翻译服务与截图流程 | [translate/CLAUDE.md](app/src/main/java/com/moe/starflow/translate/CLAUDE.md) |
| 书库身份、存储、扫描与迁移 | [library/CLAUDE.md](app/src/main/java/com/moe/starflow/library/CLAUDE.md) |
| 数据、缓存、历史与数据库 | [data/CLAUDE.md](app/src/main/java/com/moe/starflow/data/CLAUDE.md) |
| 模型下载与管理 | [download/CLAUDE.md](app/src/main/java/com/moe/starflow/download/CLAUDE.md) |
| 图片超分 | [sr/CLAUDE.md](app/src/main/java/com/moe/starflow/sr/CLAUDE.md) |
