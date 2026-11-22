#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Gradle 产出的 JVM jar 转成 Android 能加载的 dex jar。

为什么必须有这一步：
  Gradle 的 `jar` 任务产出的是 **JVM 字节码**（.class）。Android 的 DexClassLoader
  只认 dex —— 它读的是 zip 里名为 `classes.dex` 的条目。直接下发 .class 的 jar，
  装载时必然抛 `DexClassLoader: no classes.dex`，插件一个都跑不起来。

本脚本做的事：
  1. 用 Android build-tools 里的 d8 把 jar 转成 classes.dex；
  2. 重新打包成 `<id>-<ver>.jar`，里面只有两样东西：
       classes.dex
       META-INF/autotapper/plugin.json   （主程序"从本地文件安装"时读它确认 id/入口类）
  3. 用 --min-api 26 与主程序 minSdk 对齐，避免 desugar 出宿主不支持的语法。

用法（本地）：
    python tools/dex-plugins.py --dist dist --d8 "$ANDROID_HOME/build-tools/35.0.0/d8.bat"
CI 里 build-tools 由 workflow 装好，脚本从 ANDROID_BUILD_TOOLS 或 PATH 里找 d8。
"""

from __future__ import annotations

import argparse
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

META_PATH = "META-INF/autotapper/plugin.json"


def find_d8(explicit: str | None) -> str:
    if explicit:
        return explicit
    env_dir = os.environ.get("ANDROID_BUILD_TOOLS")
    if env_dir:
        for name in ("d8", "d8.bat"):
            candidate = os.path.join(env_dir, name)
            if os.path.isfile(candidate):
                return candidate
    found = shutil.which("d8") or shutil.which("d8.bat")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "d8.bat")), reverse=True)
        if not candidates:
            candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "d8")), reverse=True)
        if candidates:
            return candidates[0]
    raise SystemExit("找不到 d8：请用 --d8 指定，或设置 ANDROID_BUILD_TOOLS / ANDROID_HOME")


def read_meta(jar_path: str) -> bytes:
    with zipfile.ZipFile(jar_path) as zf:
        try:
            return zf.read(META_PATH)
        except KeyError:
            raise SystemExit("{} 里没有 {}（plugin.json 没被 jar 任务打进包）".format(jar_path, META_PATH))


def run_d8(d8: str, jar_path: str, out_dir: str, min_api: int, libs: list[str]) -> None:
    cmd = [d8, "--min-api", str(min_api), "--output", out_dir]
    for lib in libs:
        cmd.extend(["--lib", lib])
    cmd.append(jar_path)
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("d8 失败：{}\n{}\n{}".format(jar_path, result.stdout, result.stderr))


def repack(jar_path: str, dex_bytes: bytes, meta_bytes: bytes) -> None:
    tmp_path = jar_path + ".dex-tmp"
    with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo("classes.dex", date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info, dex_bytes)
        info2 = zipfile.ZipInfo(META_PATH, date_time=(2020, 1, 1, 0, 0, 0))
        info2.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info2, meta_bytes)
    os.replace(tmp_path, jar_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="把插件 jar 转成 Android 可加载的 dex jar")
    parser.add_argument("--dist", default="dist", help="jar 目录，默认 dist/")
    parser.add_argument("--d8", default=None, help="d8 可执行文件路径")
    parser.add_argument("--min-api", type=int, default=26, help="与主程序 minSdk 对齐，默认 26")
    parser.add_argument("--lib", action="append", default=[], help="额外 library jar（kotlin-stdlib 等），可重复")
    args = parser.parse_args()

    d8 = find_d8(args.d8)
    jars = sorted(glob.glob(os.path.join(args.dist, "*.jar")))
    if not jars:
        print("{} 里没有 jar".format(args.dist), file=sys.stderr)
        return 2

    print("d8: {}".format(d8))
    print("min-api: {}".format(args.min_api))

    for jar_path in jars:
        meta_bytes = read_meta(jar_path)
        with tempfile.TemporaryDirectory() as work:
            out_dir = os.path.join(work, "dex")
            os.makedirs(out_dir, exist_ok=True)
            run_d8(d8, jar_path, out_dir, args.min_api, args.lib)
            dex_path = os.path.join(out_dir, "classes.dex")
            if not os.path.isfile(dex_path):
                raise SystemExit("d8 没有产出 classes.dex：{}".format(jar_path))
            with open(dex_path, "rb") as handle:
                dex_bytes = handle.read()
        repack(jar_path, dex_bytes, meta_bytes)
        print("已转为 dex：{}（{} 字节）".format(os.path.basename(jar_path), os.path.getsize(jar_path)))

    return 0


if __name__ == "__main__":
    sys.exit(main())
