#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
app 数据全量备份 —— 在任何可能触碰设备状态的操作之前跑一次。

覆盖两部分（卸载 app 会把两部分都删掉，缺一不可）：
  1. **私有数据** `/data/data/<pkg>/`：Room 数据库（翻译历史/页面缓存/导入清单）、
     SharedPreferences（全部设置）、files/、加密存储的 API Key。
     用 `run-as` + tar 导出（**仅对 debuggable 包有效** —— debug 构建可用）。
  2. **外部数据** `/sdcard/Android/data/<pkg>/`：书架封面、导入的漫画/小说、
     下载的模型、日志。

⚠️ 为什么必须用 Python 而不是 PowerShell 重定向：`adb exec-out` 吐的是**二进制**，
   PowerShell 的 `>` 会按文本处理（加 BOM、翻译换行）→ tar 包损坏。
   这里用 subprocess 拿 bytes 直接落盘。

用法：
    python guard/backup_app.py                       # 自动选唯一设备
    python guard/backup_app.py -s <serial>
    python guard/backup_app.py -o D:\\backups\\starflow-20260930
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

DEFAULT_PKG = "com.moe.starflow"
DEFAULT_ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)


def adb_path() -> str:
    for cand in (DEFAULT_ADB, os.environ.get("ADB"), "adb"):
        if cand and (os.path.sep not in cand or os.path.exists(cand)):
            return cand
    return "adb"


def run(adb, args, serial=None, binary=False):
    cmd = [adb] + (["-s", serial] if serial else []) + args
    if binary:
        r = subprocess.run(cmd, capture_output=True)
        if r.returncode != 0:
            raise RuntimeError(f"命令失败 {cmd}: {r.stderr.decode('utf-8', 'replace')}")
        return r.stdout
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout + r.stderr


def pick_serial(adb, want=None):
    out = run(adb, ["devices"])
    serials = []
    for line in out.splitlines()[1:]:
        if "\t" in line:
            s, st = line.split("\t", 1)
            if st.strip() == "device":
                serials.append(s)
    if want:
        if want not in serials:
            raise SystemExit(f"指定设备不在线: {want}\n在线: {serials}")
        return want
    if not serials:
        raise SystemExit("没有在线设备")
    if len(serials) > 1:
        raise SystemExit(f"有多个设备，请用 -s 指定：{serials}")
    return serials[0]


def sha256_of(path, limit=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def tree_stats(root):
    n = 0
    size = 0
    for dirpath, _, files in os.walk(root):
        for f in files:
            fp = os.path.join(dirpath, f)
            n += 1
            try:
                size += os.path.getsize(fp)
            except OSError:
                pass
    return n, size


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial")
    ap.add_argument("-p", "--pkg", default=DEFAULT_PKG)
    ap.add_argument("-o", "--out")
    a = ap.parse_args()

    adb = adb_path()
    serial = pick_serial(adb, a.serial)
    pkg = a.pkg
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = a.out or os.path.join("guard", "_backups", f"{pkg}-{stamp}")
    os.makedirs(out, exist_ok=True)

    print(f"设备   : {serial}")
    print(f"包名   : {pkg}")
    print(f"输出   : {os.path.abspath(out)}")
    print()

    # 0) 装没装？
    if f"package:{pkg}" not in run(adb, ["shell", f"pm list packages | grep {pkg}"], serial):
        print(f"⚠️  设备上没有 {pkg} —— 没有数据可备份。")
        print("    （这本身说明 app 已被卸载，它的数据已经没了）")
        return 1

    ver = run(adb, ["shell", f"dumpsys package {pkg} | grep versionName"], serial).strip()
    print(f"版本   : {ver}")

    # 1) 私有数据：run-as + tar（二进制，直落盘）
    priv = os.path.join(out, "private.tar")
    print("\n[1/2] 导出私有数据 /data/data/%s ..." % pkg)
    try:
        data = run(adb, ["exec-out", "run-as", pkg, "tar", "-cf", "-", "."], serial, binary=True)
        with open(priv, "wb") as f:
            f.write(data)
        print(f"      {len(data)/1048576:.2f} MB  ->  private.tar")
    except RuntimeError as e:
        print(f"      ❌ 失败：{e}")
        print("      （release 包不允许 run-as；debug 包才可以）")
        priv = None

    # 2) 外部数据：整体 pull
    ext = os.path.join(out, "external")
    print("\n[2/2] 拉取外部数据 /sdcard/Android/data/%s ..." % pkg)
    os.makedirs(ext, exist_ok=True)
    r = subprocess.run(
        [adb] + (["-s", serial] if serial else []) + ["pull", f"/sdcard/Android/data/{pkg}", ext],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    print("      " + (r.stdout or r.stderr).strip().splitlines()[-1])

    # 3) manifest
    ext_root = os.path.join(ext, pkg)
    en, esz = tree_stats(ext_root) if os.path.isdir(ext_root) else (0, 0)
    manifest = {
        "package": pkg,
        "serial": serial,
        "versionName": ver,
        "createdAt": stamp,
        "privateTar": os.path.basename(priv) if priv else None,
        "privateTarBytes": os.path.getsize(priv) if priv and os.path.exists(priv) else 0,
        "privateTarSha256": sha256_of(priv) if priv and os.path.exists(priv) else None,
        "externalFiles": en,
        "externalBytes": esz,
        "note": "恢复用 guard/restore_app.py；私有数据依赖 run-as，仅 debuggable 包有效",
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    total = manifest["privateTarBytes"] + esz
    print("\n" + "=" * 60)
    print(f"备份完成：私有 {manifest['privateTarBytes']/1048576:.2f} MB + 外部 {esz/1048576:.2f} MB "
          f"= {total/1048576:.2f} MB")
    print(f"外部文件数：{en}")
    print(f"清单：{os.path.join(out, 'manifest.json')}")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
