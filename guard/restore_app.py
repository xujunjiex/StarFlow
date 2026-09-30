#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
app 数据还原 —— 配合 guard/backup_app.py 使用。

⚠️ 前提：app **已经装着**（还原不负责安装；卸载后 app 数据目录需要 app 自己创建）。
   若 app 已被卸载：先装回 app，启动一次让它建目录，再跑本脚本。

用法：
    python guard/restore_app.py guard/_backups/com.moe.starflow-20260930-013000
"""
import argparse
import json
import os
import subprocess
import sys

DEFAULT_ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)


def adb_path():
    return DEFAULT_ADB if os.path.exists(DEFAULT_ADB) else "adb"


def run(adb, args, serial=None):
    cmd = [adb] + (["-s", serial] if serial else []) + args
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (r.stdout + r.stderr).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("backup_dir")
    ap.add_argument("-s", "--serial")
    ap.add_argument("--skip-private", action="store_true", help="只还原外部数据")
    ap.add_argument("--skip-external", action="store_true", help="只还原私有数据")
    a = ap.parse_args()

    mf = os.path.join(a.backup_dir, "manifest.json")
    if not os.path.isfile(mf):
        raise SystemExit(f"找不到清单: {mf}")
    m = json.load(open(mf, encoding="utf-8"))
    pkg = m["package"]
    serial = a.serial or m.get("serial")
    adb = adb_path()

    print(f"备份   : {a.backup_dir}  ({m['createdAt']}, {m['versionName']})")
    print(f"目标   : {pkg} @ {serial}")

    if f"package:{pkg}" not in run(adb, ["shell", f"pm list packages | grep {pkg}"], serial):
        raise SystemExit(f"❌ 设备上没装 {pkg} —— 先装回 app 再还原")

    print("\n停止 app ...")
    print("  " + (run(adb, ["shell", f"am force-stop {pkg}"], serial) or "ok"))

    # 私有数据：tar 推到 app 自己的外部目录（app uid 可读），再 run-as 解包
    if not a.skip_private and m.get("privateTar"):
        tar = os.path.join(a.backup_dir, m["privateTar"])
        if not os.path.isfile(tar):
            print(f"⚠️  缺少 {tar}，跳过私有数据")
        else:
            remote = f"/sdcard/Android/data/{pkg}/_restore.tar"
            print("\n[1/2] 还原私有数据 ...")
            print("  push : " + run(adb, ["push", tar, remote], serial).splitlines()[-1])
            out = run(adb, ["shell", f"run-as {pkg} tar -xf {remote} -C /data/data/{pkg}"], serial)
            print("  解包 : " + (out or "ok"))
            run(adb, ["shell", f"rm -f {remote}"], serial)

    # 外部数据：整体推回
    if not a.skip_external:
        ext = os.path.join(a.backup_dir, "external", pkg)
        if not os.path.isdir(ext):
            print(f"⚠️  缺少 {ext}，跳过外部数据")
        else:
            print("\n[2/2] 还原外部数据 ...")
            print("  " + run(adb, ["push", ext + os.sep + ".", f"/sdcard/Android/data/{pkg}/"], serial)
                  .splitlines()[-1])

    print("\n完成。启动 app 验证：")
    print(f"  {adb} -s {serial} shell am start -n {pkg}/.MainActivity")
    return 0


if __name__ == "__main__":
    sys.exit(main())
