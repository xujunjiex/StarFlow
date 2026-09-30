#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
设备 / app 状态快照 —— 给"操作前后对比"用。

存在的理由：数据被删掉这件事，**发现得越晚损失越大**。
事故当时我是过了一阵才发现目录没了的。快照让"东西少了"在一秒内可见。

跑法：
    python guard/snapshot_state.py                 # 打印并保存快照
    python guard/snapshot_state.py --diff          # 与上一次快照对比，列出变化

设备离线也能跑（会标 OFFLINE），这样"手机没连上"本身也是一种可对比的状态变化。
"""
import argparse
import json
import os
import subprocess
import sys
import time

DEFAULT_ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)
PKG = "com.moe.starflow"
HERE = os.path.dirname(os.path.abspath(__file__))
SNAP_DIR = os.path.join(HERE, "_snapshots")


def adb_path():
    return DEFAULT_ADB if os.path.exists(DEFAULT_ADB) else "adb"


def sh(adb, serial, cmd):
    args = [adb] + (["-s", serial] if serial else []) + ["shell", cmd]
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (r.stdout + r.stderr).strip()


def pick(adb):
    r = subprocess.run([adb, "devices"], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    for line in r.stdout.splitlines()[1:]:
        if "\t" in line:
            s, st = line.split("\t", 1)
            if st.strip() == "device":
                return s
    return None


def snapshot():
    adb = adb_path()
    serial = pick(adb)
    if not serial:
        return {"ts": time.strftime("%Y%m%d-%H%M%S"), "state": "OFFLINE"}

    installed = f"package:{PKG}" in sh(adb, serial, f"pm list packages | grep {PKG}")
    app = {
        "ts": time.strftime("%Y%m%d-%H%M%S"),
        "state": "ONLINE",
        "serial": serial,
        "installed": installed,
        "version": sh(adb, serial, f"dumpsys package {PKG} | grep versionName").strip() if installed else "",
    }
    if installed:
        app["pid"] = sh(adb, serial, f"pidof {PKG}") or ""
        ext = f"/sdcard/Android/data/{PKG}"
        app["extExists"] = sh(adb, serial, f"[ -d {ext} ] && echo yes || echo no") == "yes"
        if app["extExists"]:
            app["extEntries"] = sh(adb, serial, f"ls {ext} 2>/dev/null").split()
            raw = sh(adb, serial, f"du -sk {ext} 2>/dev/null | cut -f1")
            app["extKB"] = int(raw) if raw.isdigit() else 0
            sr = f"{ext}/files/sr"
            app["srFiles"] = sorted(sh(adb, serial, f"ls {sr} 2>/dev/null").split())
    return app


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--diff", action="store_true", help="与上一次快照对比")
    a = ap.parse_args()

    cur = snapshot()
    os.makedirs(SNAP_DIR, exist_ok=True)
    prevs = sorted(f for f in os.listdir(SNAP_DIR) if f.endswith(".json"))
    prev_file = os.path.join(SNAP_DIR, prevs[-1]) if prevs else None

    path = os.path.join(SNAP_DIR, f"{cur['ts']}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=2)

    print(json.dumps(cur, ensure_ascii=False, indent=2))

    if a.diff and prev_file:
        prev = json.load(open(prev_file, encoding="utf-8"))
        print(f"\n=== 与上一次（{os.path.basename(prev_file)}）对比 ===")
        keys = sorted(set(prev) | set(cur))
        for k in keys:
            if prev.get(k) != cur.get(k):
                print(f"  ⚠️  {k}:")
                print(f"        之前: {prev.get(k)}")
                print(f"        现在: {cur.get(k)}")
        if all(prev.get(k) == cur.get(k) for k in keys):
            print("  ✓ 无变化")
    return 0


if __name__ == "__main__":
    sys.exit(main())
