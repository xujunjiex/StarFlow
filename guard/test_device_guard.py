#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
device_guard 的判定测试 —— 审查逻辑必须**可证伪**。

表里每条都是真实会用到的命令形态（含 2026-09-30 那条事故命令）。
跑法：python guard/test_device_guard.py
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device_guard import classify, BLOCKED, CAUTION, SAFE  # noqa: E402

CASES = [
    # ── BLOCKED：会删 app 或它的数据 ────────────────────────────────────────
    ("adb uninstall com.moe.starflow", BLOCKED),
    ("adb -s KRY5LRJFGA9HAM59 uninstall com.moe.starflow", BLOCKED),
    ("adb shell pm uninstall com.moe.starflow", BLOCKED),
    ("adb shell pm uninstall --user 0 com.moe.starflow", BLOCKED),
    ("adb shell pm clear com.moe.starflow", BLOCKED),
    ("adb shell cmd package clear com.moe.starflow", BLOCKED),
    ("adb shell pm delete-package com.moe.starflow", BLOCKED),
    # ★ 事故原样命令（名字里没有 uninstall，但 AGP 会卸载被测包）
    ("./gradlew :app:connectedDebugAndroidTest", BLOCKED),
    (".\\gradlew.bat --no-daemon :app:connectedDebugAndroidTest", BLOCKED),
    ("./gradlew connectedAndroidTest", BLOCKED),
    ("./gradlew :app:connectedCheck", BLOCKED),
    ("./gradlew :app:uninstallAll", BLOCKED),
    ("./gradlew :app:uninstallDebug", BLOCKED),
    ("./gradlew :app:managedDeviceDebugAndroidTest", BLOCKED),
    # ★ 隐藏的数据清除开关
    ("./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.clearPackageData=true", BLOCKED),
    ("adb shell am instrument -e clearPackageData true com.moe.starflow.test/androidx.test.runner.AndroidJUnitRunner", BLOCKED),
    # 直接删数据
    ("adb shell run-as com.moe.starflow rm -rf databases", BLOCKED),
    ("adb shell rm -rf /data/data/com.moe.starflow", BLOCKED),
    ("adb shell rm -rf /sdcard/Android/data/com.moe.starflow", BLOCKED),
    ("rm -rf /sdcard/Android/data/*", BLOCKED),
    ("adb shell rm -rf /sdcard/Android/data/*", BLOCKED),
    # 整机级
    ("fastboot -w", BLOCKED),
    ("fastboot erase userdata", BLOCKED),
    ("emulator -avd Pixel -wipe-data", BLOCKED),
    ("./gradlew :app:connectedDebugAndroidTest --dry-run", BLOCKED),
    ("adb shell \"pm clear com.moe.starflow\"", BLOCKED),
    ("adb -s X shell pm clear com.moe.starflow", BLOCKED),
    ("gradlew :app:unInstallDebug", BLOCKED),
    ("adb shell run-as com.moe.starflow rm -rf shared_prefs", BLOCKED),

    # ── CAUTION：不删数据，但要人看一眼 ────────────────────────────────────
    ("adb install -r app/build/outputs/apk/debug/app-debug.apk", CAUTION),
    ("./gradlew :app:installDebug", CAUTION),
    ("adb shell settings put secure adb_install_need_confirm 0", CAUTION),
    ("adb shell settings put global package_verifier_enable 0", CAUTION),
    ("adb shell pm trim-caches 1073741824", CAUTION),
    ("adb shell pm reset-permissions", CAUTION),
    ("adb push out.png /sdcard/out.png", CAUTION),
    ("adb shell rm /sdcard/Download/x.apk", CAUTION),
    ("adb shell am force-stop com.moe.starflow", CAUTION),
    ("adb shell input tap 540 1200", CAUTION),
    ("adb shell svc power stayon true", CAUTION),

    # ── SAFE：只读 / 构建 / 备份本身 ───────────────────────────────────────
    ("adb devices", SAFE),
    ("adb devices -l", SAFE),
    ("adb logcat -d -s SrNcnn", SAFE),
    ("adb shell dumpsys package com.moe.starflow", SAFE),
    ("adb shell dumpsys power | grep mWakefulness=", SAFE),
    ("adb shell pm list packages | grep moe", SAFE),
    ("adb shell settings get global adb_security_debug", SAFE),
    ("adb shell ls -la /sdcard/Android/data/com.moe.starflow/files/", SAFE),
    ("adb shell cat /proc/meminfo", SAFE),
    ("adb shell df -h /data", SAFE),
    ("adb pull /sdcard/x.png", SAFE),
    ("adb shell pidof com.moe.starflow", SAFE),
    ("./gradlew :app:assembleDebug", SAFE),
    ("./gradlew :app:testDebugUnitTest", SAFE),
    ("./gradlew :app:compileDebugKotlin", SAFE),
    ("./gradlew --no-daemon :sr:assembleDebug", SAFE),
    ("git status --short", SAFE),
    ("python tools/sr-research/verify_sr_wiring.py", SAFE),
    # 备份工具本身必须是 SAFE，否则"先备份再操作"的流程走不通
    ("python guard/backup_app.py", SAFE),
    ("adb -s X exec-out run-as com.moe.starflow tar -cf - .", SAFE),
    ("adb pull /sdcard/Android/data/com.moe.starflow guard/_backups/latest/external", SAFE),
]


def main():
    bad = []
    for cmd, want in CASES:
        got, why = classify(cmd)
        mark = "✓" if got == want else "✗"
        if got != want:
            bad.append((cmd, want, got, why))
        name = {BLOCKED: "BLOCKED", CAUTION: "CAUTION", SAFE: "SAFE"}[want]
        print(f"  {mark} [{name:7s}] {cmd[:78]}")
    print()
    total = len(CASES)
    if bad:
        print(f"❌ {len(bad)}/{total} 判定错误：")
        for cmd, want, got, why in bad:
            print(f"   命令: {cmd}")
            print(f"   期望: {want}  实际: {got}")
            print(f"   理由: {why}")
        return 1
    print(f"✅ 全部 {total} 条判定正确"
          f"（BLOCKED {sum(1 for _, w in CASES if w == BLOCKED)} / "
          f"CAUTION {sum(1 for _, w in CASES if w == CAUTION)} / "
          f"SAFE {sum(1 for _, w in CASES if w == SAFE)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
