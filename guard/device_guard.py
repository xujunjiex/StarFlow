#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
device_guard —— 命令审查器：识别「任何可能卸载 app 或删除 app 数据的指令」。

用途：**在执行任何设备操作之前**,把整条命令行喂进来,按三级判定放行/警告/拦截。
     python guard/device_guard.py "adb uninstall com.moe.starflow"
     echo 0  → SAFE      可以执行
     echo 1  → CAUTION   会改动设备状态/降安全等级,先想清楚
     echo 2  → BLOCKED   会删 app 或它的数据,默认不做

设计原则（血泪换来的）：
  1. **按「副作用」判定,不按「命令长什么样」** —— 真正危险的不是 `adb`,是
     「先卸载后安装且不回滚」这种**组合行为**。所以 Gradle 的 connected 测试
     必须按「会卸载」判,尽管它名字里一个 uninstall 都没有。
  2. **宁可误报** —— 拿不准就归 CAUTION,让人看一眼,而不是默默放过去。
  3. **检测「数据被清」的隐藏开关** —— AndroidJUnitRunner 有个
     `clearPackageData` 参数,一行 `-e clearPackageData true` 就能清空 app 数据,
     名字里同样没有 uninstall。这类"没写卸载但行为是清数据"的必须一起拦。
  4. **通配符升级风险** —— `rm -rf /sdcard/Android/data/*` 比只删一个包严重得多。

退出码即结论,便于脚本里 `&&` 串起来：
     python guard/device_guard.py "$CMD" || echo "已被拦截,不要执行"
"""
import re
import sys

BLOCKED = 2
CAUTION = 1
SAFE = 0

PKG = r"(?:com\.moe\.starflow|com\.moe\.[a-z0-9_.]+|\$PKG|<pkg>|包名)"

# ── 判定规则：(正则, 级别, 原因) ────────────────────────────────────────────
RULES = [
    # ══ BLOCKED：直接删 app 或它的数据 ══════════════════════════════════════
    (rf"\badb\b[^\n]*\buninstall\b", BLOCKED,
     "adb uninstall —— 卸载 app,它的 /data/data 与外部数据目录会一起被删,且不可恢复"),
    (rf"\bpm\s+uninstall\b", BLOCKED,
     "pm uninstall —— 同上,卸载即删数据"),
    (rf"\bpm\s+clear\b", BLOCKED,
     "pm clear —— **不卸载但清空 app 全部数据**(数据库/设置/缓存全没)"),
    (rf"\bcmd\s+package\s+clear\b", BLOCKED,
     "cmd package clear —— pm clear 的新写法,同样清空数据"),
    (rf"\bpm\s+delete-package\b", BLOCKED,
     "pm delete-package —— 删包"),
    (rf"\brun-as\s+\S+\s+rm\b", BLOCKED,
     "run-as <pkg> rm —— 直接删 app 私有数据"),
    (r"/data/(?:data|user/\d+)/" + PKG + r"", BLOCKED,
     "路径落在 app 私有数据目录 —— 任何写/删都可能毁数据"),
    (r"/sdcard/Android/data/" + PKG + r"", BLOCKED,
     "路径落在 app 外部数据目录 —— 删/覆盖会毁书架与已下载模型"),

    # Gradle / AGP：名字里没有 uninstall,但**行为**是卸载
    (r"\bconnected(?:Debug|Release|Android)?[A-Za-z]*Test\b", BLOCKED,
     "AGP connected 测试 —— UTP 安装前会卸载被测 app,安装失败也不回滚(2026-09-30 事故原样)"),
    (r"\bconnectedCheck\b", BLOCKED,
     "connectedCheck —— 同上,会跑 connected 测试并卸载 app"),
    (r"\bmanagedDevice[A-Za-z]*\b", BLOCKED,
     "managed device 测试 —— 同样的卸载语义"),
    (r"\buninstall(?:All|Debug|Release|For[A-Za-z]*)\b", BLOCKED,
     "Gradle uninstall 任务 —— 卸载 app"),
    (r"\bpm\s+install[A-Za-z-]*\b(?=[^\n]*\b(?:uninstall|-r\s+--user))", BLOCKED,
     "install 与 uninstall/换用户组合 —— 先卸后装,失败不回滚"),

    # 隐藏的数据清除开关（名字里没有 uninstall,但会清数据）
    (r"clearPackageData", BLOCKED,
     "AndroidJUnitRunner 的 clearPackageData —— **跑测试前/后清空 app 数据**,名字里没有 uninstall 但行为一样"),

    # 通配符：一次毁掉所有 app 的数据
    (r"rm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+[^\n]*Android/data/\*", BLOCKED,
     "带通配符删 Android/data/* —— 会毁掉设备上**所有** app 的外部数据"),
    (r"rm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+/\s*$", BLOCKED, "rm -rf / —— 毁整机"),

    # 整机级
    # ⚠️ 别写成 \b(?:-w|...)\b —— `-` 是非单词字符，\b 在它前面不成立，会漏判（踩过）
    (r"\bfastboot\b[^\n]*(?:\s-w(?:\s|$)|\berase\b|\bformat\b)", BLOCKED,
     "fastboot -w / erase / format —— 抹掉整机 userdata"),
    (r"\brecovery\b[^\n]*--wipe_data", BLOCKED, "recovery --wipe_data —— 抹掉整机数据"),
    (r"\bemulator\b[^\n]*-wipe-data", BLOCKED, "emulator -wipe-data —— 抹掉模拟器数据"),

    # ══ CAUTION：不删 app 数据,但会改设备状态 / 降安全等级 / 覆盖文件 ══════
    (r"\badb\b[^\n]*\binstall\b", CAUTION,
     "adb install —— 正常覆盖安装会保留数据;但**签名不一致时可能触发卸载重装**。装前先备份"),
    (r"\binstall(?:Debug|Release)\b", CAUTION,
     "Gradle install 任务 —— 同上"),
    (r"\bsettings\s+put\b", CAUTION,
     "改系统设置 —— 例如 adb_install_need_confirm / package_verifier_enable 会**降低安装校验强度**"),
    (r"\bpm\s+(?:reset-permissions|revoke|trim-caches)\b", CAUTION,
     "pm reset-permissions / revoke / trim-caches —— 改权限或清缓存"),
    (r"\badb\b[^\n]*\bpush\b", CAUTION,
     "adb push —— 会覆盖目标路径上的同名文件;推到 app 数据目录时尤其注意"),
    (r"\badb\b[^\n]*\bshell\s+rm\b", CAUTION,
     "adb shell rm —— 删设备上的文件;确认路径不在 app 数据目录内"),
    (r"\bam\s+force-stop\b", CAUTION,
     "force-stop —— 不删数据,但常与清数据操作配对出现,看到它先想清楚上下文"),
    (r"\bgradlew?\b[^\n]*\b(?:clean)\b", CAUTION,
     "gradle clean —— 只删构建产物,安全;列在这里是因为它常被误认为有风险"),
    (r"\badb\b[^\n]*\binput\b", CAUTION, "模拟输入 —— 会操作设备(点击/按键)"),
    (r"\bsvc\s+power\b", CAUTION, "电源控制 —— 会操作设备"),
]

# ── 只读白名单：命中即可直接放行（优先级高于规则,避免误报） ────────────────
READONLY = [
    r"\badb\b[^\n]*\bdevices\b",
    r"\badb\b[^\n]*\b(?:logcat|dumpsys|getprop|pidof|bugreport)\b",
    r"\badb\b[^\n]*\b(?:pull)\b",
    r"\badb\b[^\n]*\bshell\s+(?:ls|cat|stat|du|df|wc|grep|find|head|tail|md5sum|sha256sum|settings\s+get|pm\s+list|dumpsys)\b",
    r"\bgradlew?\b[^\n]*\b(?:assemble|test|compile|lint|tasks|dependencies|build)\b",
]


def classify(cmd: str):
    c = " ".join(cmd.split())
    low = c.lower()

    # 先看只读白名单：这些永远安全（但若同时含危险 token 则不放过）
    danger_tokens = ("uninstall", "clear", "rm ", "wipe", "erase", "format")
    if any(re.search(p, low) for p in READONLY) and not any(t in low for t in danger_tokens):
        return SAFE, "只读查询（列设备/日志/dumpsys/pull/构建产物）"

    hits = []
    for pat, lvl, why in RULES:
        if re.search(pat, c, re.IGNORECASE):
            hits.append((lvl, why))
    if not hits:
        return SAFE, "未命中任何危险模式"
    lvl = max(h[0] for h in hits)
    return lvl, "；".join(h[1] for h in hits if h[0] == lvl)


LABEL = {BLOCKED: "⛔ BLOCKED", CAUTION: "⚠️  CAUTION", SAFE: "✅ SAFE"}


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return SAFE
    cmd = " ".join(argv[1:])
    lvl, why = classify(cmd)
    print(f"{LABEL[lvl]}  {cmd}")
    print(f"         {why}")
    if lvl == BLOCKED:
        print()
        print("         这条命令会删掉 app 或它的数据。默认不执行。")
        print("         确实要做：先 python guard/backup_app.py，再显式确认。")
    return lvl


if __name__ == "__main__":
    sys.exit(main(sys.argv))
