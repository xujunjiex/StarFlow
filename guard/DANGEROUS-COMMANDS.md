# 危险命令全清单 —— 审查逻辑（人读版）

> 这份文件是 `guard/device_guard.py` 里规则的**人读对照**。两者必须一致：
> 改这里就要改那里，并跑 `python guard/test_device_guard.py`。
>
> 判据只有一条：**这条命令会不会删掉 app，或删掉 app 的数据？**
> 会 → ⛔ BLOCKED；不删但会改动设备状态/降安全等级 → ⚠️ CAUTION；其余 → ✅ SAFE。

---

## ⛔ BLOCKED —— 会删 app 或它的数据

### A. 显式卸载 / 清数据

| 命令 | 后果 |
|---|---|
| `adb uninstall <pkg>` | 卸载 app；`/data/data/<pkg>` 与 `/sdcard/Android/data/<pkg>` 一起被系统删掉 |
| `adb shell pm uninstall <pkg>` | 同上 |
| `adb shell pm uninstall --user 0 <pkg>` | 同上（指定用户也一样） |
| `adb shell pm clear <pkg>` | **不卸载但清空全部 app 数据** —— 数据库/设置/密钥全没 |
| `adb shell cmd package clear <pkg>` | `pm clear` 的新写法，同样清数据 |
| `adb shell pm delete-package <pkg>` | 删包 |
| `adb shell run-as <pkg> rm ...` | 直接删 app 私有数据（debug 包可用，绕过系统保护） |

### B. ⚠️ **名字里没有 uninstall，但行为就是卸载**（本次事故的类别）

| 命令 | 为什么危险 |
|---|---|
| `./gradlew :app:connectedDebugAndroidTest` | **AGP 的 connected 测试走 UTP，安装前会卸载被测 app**（保证签名/版本干净），安装失败**不回滚** |
| `./gradlew connectedAndroidTest` / `connectedCheck` | 同上 |
| `./gradlew :app:managedDeviceDebugAndroidTest` | 托管设备测试，同样语义 |
| `./gradlew :app:uninstallAll` / `uninstallDebug` | 显式卸载任务 |
| `adb shell am instrument -e clearPackageData true ...` | **AndroidJUnitRunner 的隐藏开关**：跑测试前后清空 app 数据 |
| `-Pandroid.testInstrumentationRunnerArguments.clearPackageData=true` | 同上，Gradle 侧入口 |

> 🩸 **2026-09-30 事故就是 `:app:connectedDebugAndroidTest`。**
> 这条命令**从头到尾没有出现过 uninstall 字样**，靠人脑联想必然漏。
> 所以这一类的识别规则是：「**凡是会驱动 AGP 安装/卸载设备包的任务**」，
> 而不是「名字里有 uninstall 的任务」。

### C. 路径落在 app 数据目录上

| 命令形态 | 后果 |
|---|---|
| `rm -rf /data/data/<pkg>`、`/data/user/0/<pkg>` | 删私有数据 |
| `rm -rf /sdcard/Android/data/<pkg>` | 删外部数据（书架、已下载模型、缓存） |
| 任何**写/删**这两个路径的命令 | 同上；`adb push` 覆盖也算（归 CAUTION） |

### D. 通配符（风险升级）

| 命令 | 后果 |
|---|---|
| `rm -rf /sdcard/Android/data/*` | 毁掉设备上**所有** app 的外部数据 |
| `rm -rf /` | 毁整机 |

### E. 整机级

| 命令 | 后果 |
|---|---|
| `fastboot -w` / `fastboot erase userdata` / `fastboot format` | 抹掉整机 userdata |
| `recovery --wipe_data` | 同上 |
| `emulator -wipe-data` | 抹掉模拟器数据 |

---

## ⚠️ CAUTION —— 不删 app 数据，但必须看一眼

| 命令 | 为什么要注意 |
|---|---|
| `adb install ...` / `gradlew installDebug` | 正常覆盖安装保留数据；但**签名不一致时可能触发卸载重装** → 装前先备份 |
| `adb shell settings put ...` | 改系统设置。例如 `adb_install_need_confirm=0`、`package_verifier_enable=0` 会**降低安装校验强度** |
| `adb shell pm reset-permissions` / `revoke` / `trim-caches` | 改权限或清缓存 |
| `adb push ...` | 覆盖目标路径同名文件；推到 app 数据目录时尤其注意 |
| `adb shell rm ...` | 删设备文件；先确认路径不在 app 数据目录内 |
| `adb shell am force-stop <pkg>` | 不删数据，但**常与清数据操作配对出现**，看到它先想清楚上下文 |
| `adb shell input ...` / `svc power ...` | 操作设备（点击/按键/电源） |
| `gradlew clean` | 只删构建产物，安全；列在这里是因为常被误以为有风险 |

---

## ✅ SAFE —— 只读 / 构建 / 备份本身

```
adb devices / adb logcat -d / adb shell dumpsys|getprop|pidof|cat|ls|stat|df|du
adb shell settings get ... / pm list packages ... / dumpsys ...
adb pull ...
gradlew assemble*/test*/compile*/lint*/tasks/build
git ... / python <任意工程脚本>
python guard/backup_app.py                        ← 备份本身必须放行
adb -s X exec-out run-as <pkg> tar -cf - .        ← 备份私有数据
```

> ⚠️ **只读白名单的优先级高于危险规则**，但有一个例外：命令行里同时出现
> `uninstall` / `clear` / `rm ` / `wipe` / `erase` / `format` 时，白名单失效、按危险判。
> （否则 `adb shell dumpsys ... ; pm clear <pkg>` 这种拼接会被放过去。）

---

## 判据为什么这么定（三条原则）

1. **按副作用判，不按长相判。**
   危险的本质是「**先卸载后安装、失败不回滚**」这个组合行为。所以
   `connectedDebugAndroidTest` 必须按"会卸载"处理 —— 尽管它名字里没有 uninstall。
2. **必须覆盖「名字没说但行为是清数据」的隐藏开关。**
   典型就是 AndroidJUnitRunner 的 `clearPackageData`。这类东西没有统一的命名规律，
   只能靠**逐个枚举已知会清数据的入口**。
3. **宁可误报。**
   拿不准就归 CAUTION，让人看一眼 —— 代价是多读一行字；漏报的代价是数据没了。

---

## 新增判据的流程（别凭感觉加）

1. 想清楚它属于「删 app / 删数据 / 改设备状态 / 只读」哪一类
2. 在 `guard/device_guard.py` 的 `RULES` 或 `READONLY` 里加正则
3. **在 `guard/test_device_guard.py` 里加至少一条正例**
4. 跑 `python guard/test_device_guard.py`，全绿才算加完
5. 同步更新本文件的表格

> 没有测试用例的规则等于没有规则 —— 正则写错一个 `\b` 就会静默漏判
> （踩过：`fastboot -w` 因为 `\b` 在 `-` 前不成立而被判成 SAFE）。
