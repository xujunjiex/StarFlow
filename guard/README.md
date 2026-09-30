# 设备操作防护体系（`guard/`）

> 起因：**2026-09-30 真实事故** —— 在装着真实数据的手机上跑 `:app:connectedDebugAndroidTest`，
> AGP 的 connected 流程**先卸载被测 app**，安装被 MIUI 拦下后**没有回滚** →
> app 连同 `/data/data/` 与外部数据目录一起没了，且无备份可恢复。

这套东西存在的唯一目的：**让同一件事不可能再发生一次。**

---

## 四层防护（缺一层都不够）

| 层 | 机制 | 挡什么 | 在哪 |
|---|---|---|---|
| **L1 硬拦** | Gradle init script | 任务名含 `uninstall` / 以 `connected` 开头 / `managedDevice` —— **机器级，任何项目任何调用方都拦** | `~/.gradle/init.d/device-guard.gradle` |
| **L2 审查** | 命令分类器（可证伪） | 任何**命令串**里的删 app / 清数据 / 抹机行为，含名字里没有 uninstall 的隐藏开关 | `guard/device_guard.py` + 61 条用例 |
| **L3 可恢复** | 全量备份 / 还原 | 私有数据（数据库/设置/密钥）+ 外部数据（书架/模型/缓存） | `guard/backup_app.py` / `restore_app.py` |
| **L4 强制流程** | 项目指令（每次会话自动加载） | 让**人和 AI** 都按同一套判据做事 | 根 `CLAUDE.md` 的「设备操作防护」章 |

---

## 为什么需要 L1（而不是只写在文档里）

因为**光写规则挡不住人，也挡不住 AI**。事故当时项目文档里已经有「禁止未经确认操作设备」，
但那是一条**需要被读到、被想起来**的规则 —— 而 `connectedDebugAndroidTest` 这个名字里
**完全没有 uninstall 的痕迹**，靠人脑联想是必然漏的。

L1 把它变成**执行层面的硬失败**：只要任务图里有它，构建直接抛异常，连跑都跑不起来。
而且它装在 `~/.gradle/init.d/`（**项目外**）—— 换分支、清工作区、重新 clone 都甩不掉。

**脚本本体在仓库里**（`guard/gradle-init/device-guard.gradle`），新机器上装一次：
```powershell
pwsh guard/install-gradle-guard.ps1     # 装到 ~/.gradle/init.d/，已有的会先备份
```

```powershell
# 验证它真的在拦（--dry-run 不会真执行）
.\gradlew.bat :app:connectedDebugAndroidTest --dry-run
# → BLOCKED: this task uninstalls the app and deletes its data
```

确实需要在**没有真实数据**的设备上跑时，显式放行：
```powershell
$env:ALLOW_DEVICE_UNINSTALL='1'
.\gradlew.bat :app:connectedDebugAndroidTest      # 会打醒目警告，然后继续
Remove-Item Env:\ALLOW_DEVICE_UNINSTALL
```

---

## L2：任何命令先过审查器

```powershell
python guard/device_guard.py "adb uninstall com.moe.starflow"
# ⛔ BLOCKED  adb uninstall com.moe.starflow
#            adb uninstall —— 卸载 app,它的 /data/data 与外部数据目录会一起被删,且不可恢复

python guard/device_guard.py "adb shell dumpsys package com.moe.starflow"
# ✅ SAFE     只读查询（列设备/日志/dumpsys/pull/构建产物）
```

退出码即结论，可直接串在流程里：
```powershell
python guard/device_guard.py "$CMD" || throw "该命令已被拦截"
```

**它的判据是可证伪的** —— 改规则后必须跑：
```powershell
python guard/test_device_guard.py     # 61 条用例：29 BLOCKED / 11 CAUTION / 21 SAFE
```

### 判定规则的三条原则（血泪）

1. **按「副作用」判，不按「命令长什么样」判。**
   真正危险的是「先卸载后安装且不回滚」这种**组合行为** —— 所以 `connectedDebugAndroidTest`
   必须按「会卸载」判，尽管它名字里一个 uninstall 都没有。
2. **检测「名字没说但行为是清数据」的隐藏开关。**
   典型：AndroidJUnitRunner 的 `-e clearPackageData true` —— 一行参数就能清空 app 数据。
3. **通配符要升级风险等级。**
   `rm -rf /sdcard/Android/data/*` 比只删一个包严重得多（毁掉设备上**所有** app 的外部数据）。

---

## L3：备份 / 还原

```powershell
# 备份（任何可能触碰设备状态的操作之前）
python guard/backup_app.py
#   → guard/_backups/com.moe.starflow-<时间戳>/
#       private.tar      私有数据（Room 库 / SharedPreferences / files / 密钥）
#       external/        外部数据（书架 / 已下载模型 / 缓存）
#       manifest.json    版本、大小、sha256

# 还原（app 需先装着）
python guard/restore_app.py guard/_backups/com.moe.starflow-20260930-013000
```

**两个必须知道的限制：**

- 私有数据靠 `run-as`，**只对 debuggable 包有效**（`debug` 构建可以，`release` 不行）。
  要备份 release 包的数据，得先临时装一个 debug 包或改用其他手段。
- 还原**不负责安装**。app 被卸载后数据目录没了，必须先装回 app、启动一次建目录，再还原。

---

## L4：项目指令（`CLAUDE.md`）

`guard/` 再全，也需要**每次会话都读到的规则**。根 `CLAUDE.md` 里有「设备操作防护」章，
写明了分类判据、必做流程、以及"哪些命令默认不做"。

---

## 自检快照

```powershell
python guard/snapshot_state.py      # 记录 app 是否在线 / 数据目录条目数 / 备份列表
```
（用于"操作前后对比"，一秒看出有没有东西没了。设备离线时也能跑，会标 OFFLINE。）

---

## 文件清单

| 文件 | 作用 |
|---|---|
| `device_guard.py` | 命令审查器（L2） |
| `test_device_guard.py` | 审查器判定测试（61 条） |
| `backup_app.py` | 全量备份（L3） |
| `restore_app.py` | 还原（L3） |
| `snapshot_state.py` | 设备/app 状态快照（操作前后对比） |
| `DANGEROUS-COMMANDS.md` | **危险命令全清单 + 分类判据**（给人看的审查逻辑） |
| `_backups/` | 备份产物（gitignore，不入库） |
