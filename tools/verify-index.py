#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 index.json 的结构，以及每个 sha256 / sizeBytes 是否与实际 jar 一致。

downloadUrl / sigUrl 两种写法都认：
  - GitHub Release 直链（https://github.com/.../releases/download/...）
  - 仓库内相对路径（dist/<id>-<版本>.jar），给 --dist 时换算成本地文件再比对哈希

用法：

    python3 tools/verify-index.py                    # 只校验结构
    python3 tools/verify-index.py --dist dist        # 结构 + 产物哈希
    python3 tools/verify-index.py --dist dist --strict   # 缺产物、多产物都算失败

CI 里在发布前跑一次 --dist dist --strict，避免把错的哈希发出去。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

REQUIRED_TOP_KEYS = ("schema", "hostApiVersion", "generatedAt", "plugins")
REQUIRED_PLUGIN_KEYS = (
    "id",
    "name",
    "version",
    "apiVersion",
    "minHostVersionCode",
    "entryClass",
    "requires",
    "sizeBytes",
    "sha256",
    "downloadUrl",
    "sigUrl",
    "description",
    "changelog",
)

ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+([-+][0-9A-Za-z.]+)?$")
ENTRY_RE = re.compile(r"^com\.autotapper\.plugin\.([a-z0-9]+)\.([A-Z][A-Za-z0-9]*)Plugin$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
ISO_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
API_VALUES = {"SCREENSHOT", "NETWORK", "ACCESSIBILITY", "SHIZUKU", "OVERLAY"}


def is_absolute_url(url: str) -> bool:
    """http(s) 开头才当绝对地址；其余一律当仓库内相对路径。"""
    lowered = url.lower()
    return lowered.startswith("http://") or lowered.startswith("https://")


def relative_url_reason(url: str) -> str:
    """相对路径写得不对时返回一句原因；没问题返回 None。"""
    if not url:
        return "是空串"
    if "://" in url:
        return "既不是 http(s) 地址也不是相对路径"
    if url.startswith("/"):
        return "以 / 开头（相对路径是相对仓库根，不能以 / 开头）"
    if "\\" in url:
        return "含反斜杠（URL 里必须是正斜杠）"
    if ".." in url:
        return "含 .."
    parts = url.split("/")
    if len(parts) != 2 or not parts[0]:
        return "不是 <目录>/<文件名> 两段，例如 dist/tap-styles-0.1.0.jar"
    name = parts[1]
    if not (name.endswith(".jar") or name.endswith(".jar.sig")):
        return "文件名不以 .jar 或 .jar.sig 结尾"
    return None


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.notes: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_plugin(index_entry: dict, report: Report, where: str) -> None:
    missing = [k for k in REQUIRED_PLUGIN_KEYS if k not in index_entry]
    if missing:
        report.error("{}：缺少字段 {}".format(where, ", ".join(missing)))
        return

    plugin_id = index_entry["id"]
    if not isinstance(plugin_id, str) or not ID_RE.match(plugin_id):
        report.error("{}：id 必须是小写字母/数字/连字符，实际 {!r}".format(where, plugin_id))
        return

    if not isinstance(index_entry["name"], str) or not index_entry["name"].strip():
        report.error("{}：name 不能为空".format(where))

    if not isinstance(index_entry["version"], str) or not SEMVER_RE.match(index_entry["version"]):
        report.error("{}：version 必须是 x.y.z，实际 {!r}".format(where, index_entry["version"]))

    if not isinstance(index_entry["apiVersion"], int):
        report.error("{}：apiVersion 必须是整数".format(where))

    if not isinstance(index_entry["minHostVersionCode"], int) or index_entry["minHostVersionCode"] < 1:
        report.error("{}：minHostVersionCode 必须是 >= 1 的整数".format(where))

    entry = index_entry["entryClass"]
    match = ENTRY_RE.match(entry) if isinstance(entry, str) else None
    if not match:
        report.error(
            "{}：entryClass 不符合 com.autotapper.plugin.<包名>.<PascalName>Plugin，实际 {!r}".format(
                where, entry
            )
        )
    else:
        expected_package = plugin_id.replace("-", "")
        if match.group(1) != expected_package:
            report.error(
                "{}：entryClass 的包名段应为 {}，实际 {}".format(where, expected_package, match.group(1))
            )

    requires = index_entry["requires"]
    if not isinstance(requires, list) or any(r not in API_VALUES for r in requires):
        report.error("{}：requires 只能是 {} 的子集，实际 {!r}".format(where, sorted(API_VALUES), requires))

    size = index_entry["sizeBytes"]
    if not isinstance(size, int) or size <= 0:
        report.error("{}：sizeBytes 必须是正整数".format(where))

    if not isinstance(index_entry["sha256"], str) or not SHA_RE.match(index_entry["sha256"]):
        report.error("{}：sha256 必须是 64 位小写十六进制".format(where))

    url = index_entry["downloadUrl"]
    if not isinstance(url, str):
        report.error("{}：downloadUrl 必须是字符串".format(where))
        url = ""
    elif is_absolute_url(url):
        # 绝对地址只认 GitHub Release 直链：主程序 PluginHttp 的白名单只放行 GitHub 域名，
        # 写别的绝对地址装的时候必然被拒，不如在这里先拦下来。
        if not url.startswith("https://github.com/"):
            report.error(
                "{}：绝对地址 downloadUrl 必须是 https://github.com/ 开头的 Release 直链，"
                "实际 {!r}（仓库内相对路径请写成 dist/<id>-<版本>.jar）".format(where, url)
            )
        elif "/releases/download/" not in url:
            report.error("{}：downloadUrl 必须指向 /releases/download/".format(where))
        elif not url.endswith(".jar"):
            report.error("{}：downloadUrl 必须以 .jar 结尾".format(where))
    else:
        reason = relative_url_reason(url)
        if reason:
            report.error("{}：downloadUrl 是相对路径，但{}（实际 {!r}）".format(where, reason, url))

    sig_url = index_entry["sigUrl"]
    if not isinstance(sig_url, str):
        report.error("{}：sigUrl 必须是字符串（没有签名时给空串）".format(where))
        sig_url = ""
    elif sig_url:
        if is_absolute_url(sig_url):
            if not sig_url.startswith("https://github.com/"):
                report.error(
                    "{}：绝对地址 sigUrl 也必须是 https://github.com/ 开头，实际 {!r}".format(where, sig_url)
                )
            elif not sig_url.endswith(".jar.sig"):
                report.error("{}：sigUrl 必须以 .jar.sig 结尾".format(where))
        else:
            reason = relative_url_reason(sig_url)
            if reason:
                report.error("{}：sigUrl 是相对路径，但{}（实际 {!r}）".format(where, reason, sig_url))
        # 签名必须挂在这个 jar 上，防止索引指到别的版本的签名文件
        if url and sig_url != url + ".sig":
            report.error(
                "{}：sigUrl 必须是 downloadUrl 加 .sig，实际 {!r}（downloadUrl 是 {!r}）".format(
                    where, sig_url, url
                )
            )

    for key in ("description", "changelog"):
        if not isinstance(index_entry[key], str):
            report.error("{}：{} 必须是字符串".format(where, key))


def main() -> int:
    parser = argparse.ArgumentParser(description="校验 index.json 与产物哈希")
    parser.add_argument("--index", default="index.json")
    parser.add_argument("--dist", default=None, help="给了就顺带校验文件大小与 sha256")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="dist 里缺文件、多文件、sigUrl 指向的签名不存在都算失败",
    )
    args = parser.parse_args()

    report = Report()

    try:
        with open(args.index, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        print("找不到 {}".format(args.index), file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print("{} 不是合法 JSON：{}".format(args.index, exc), file=sys.stderr)
        return 2

    if not isinstance(data, dict):
        print("index.json 顶层必须是对象", file=sys.stderr)
        return 2

    for key in REQUIRED_TOP_KEYS:
        if key not in data:
            report.error("顶层缺少字段 {}".format(key))

    if data.get("schema") != 1:
        report.error("schema 必须是 1，实际 {!r}".format(data.get("schema")))

    if not isinstance(data.get("hostApiVersion"), int):
        report.error("hostApiVersion 必须是整数")

    generated = data.get("generatedAt")
    if not isinstance(generated, str) or not ISO_RE.match(generated):
        report.error("generatedAt 必须是 2026-01-01T00:00:00Z 形式，实际 {!r}".format(generated))

    plugins = data.get("plugins")
    if not isinstance(plugins, list):
        report.error("plugins 必须是数组")
        plugins = []

    seen: set[str] = set()
    for i, item in enumerate(plugins):
        where = "plugins[{}]".format(i)
        if not isinstance(item, dict):
            report.error("{}：必须是对象".format(where))
            continue
        check_plugin(item, report, where)
        plugin_id = item.get("id")
        if isinstance(plugin_id, str):
            if plugin_id in seen:
                report.error("{}：id {} 重复".format(where, plugin_id))
            seen.add(plugin_id)
            if item.get("apiVersion") != data.get("hostApiVersion"):
                report.error(
                    "{}：apiVersion {} 与 hostApiVersion {} 不一致".format(
                        where, item.get("apiVersion"), data.get("hostApiVersion")
                    )
                )

    if args.dist:
        if not os.path.isdir(args.dist):
            report.error("dist 目录不存在：{}".format(args.dist))
        else:
            # 相对路径的目录段必须就是 --dist 这一层的名字（dist/），否则索引和产物对不上
            dist_dir_name = os.path.basename(os.path.normpath(args.dist)) or "dist"
            expected_files: set[str] = set()
            for i, item in enumerate(plugins):
                if not isinstance(item, dict):
                    continue
                where = "plugins[{}] ({})".format(i, item.get("id"))
                url = item.get("downloadUrl") or ""
                if not isinstance(url, str) or not url:
                    continue
                file_name = url.rsplit("/", 1)[-1]
                if not is_absolute_url(url):
                    parent = url.rsplit("/", 1)[0]
                    if parent != dist_dir_name:
                        report.error(
                            "{}：相对路径的目录段应为 {}/，实际 {!r}".format(where, dist_dir_name, url)
                        )
                        continue
                # 文件名应当就是 <id>-<版本>.jar：对不上说明索引和产物不是同一次构建
                expected_name = "{}-{}.jar".format(item.get("id"), item.get("version"))
                if file_name != expected_name:
                    report.error(
                        "{}：downloadUrl 的文件名应为 {}，实际 {}".format(where, expected_name, file_name)
                    )
                expected_files.add(file_name)

                path = os.path.join(args.dist, file_name)
                if not os.path.isfile(path):
                    if args.strict:
                        report.error("{}：dist 里找不到 {}".format(where, file_name))
                    else:
                        report.note("{}：跳过哈希校验，dist 里没有 {}".format(where, file_name))
                    continue
                real_size = os.path.getsize(path)
                real_sha = sha256_of(path)
                if real_size != item.get("sizeBytes"):
                    report.error(
                        "{}：sizeBytes {} 与实际 {} 不符".format(where, item.get("sizeBytes"), real_size)
                    )
                if real_sha != item.get("sha256"):
                    report.error("{}：sha256 与实际文件不符".format(where))

                # 有签名就必须真的有那个文件：sigUrl 指向不存在的文件，主程序只会一直报验签失败
                sig_url = item.get("sigUrl") or ""
                if isinstance(sig_url, str) and sig_url:
                    sig_name = sig_url.rsplit("/", 1)[-1]
                    expected_files.add(sig_name)
                    sig_path = os.path.join(args.dist, sig_name)
                    if not os.path.isfile(sig_path):
                        report.error("{}：sigUrl 指向 {}，但 dist 里没有它".format(where, sig_name))
                    elif os.path.getsize(sig_path) == 0:
                        report.error("{}：签名文件 {} 是空文件".format(where, sig_name))

            # dist/ 是要入库的目录：只允许 <id>-<版本>.jar 与 .jar.sig，
            # 多出来的东西要么是构建中间产物，要么是上一版残留，都会跟着提交进仓库。
            for entry in sorted(os.listdir(args.dist)):
                full = os.path.join(args.dist, entry)
                if os.path.isdir(full):
                    message = "dist 里不允许有子目录：{}".format(entry)
                elif entry in expected_files:
                    continue
                elif not (entry.endswith(".jar") or entry.endswith(".jar.sig")):
                    message = "dist 里有多余文件 {}（只应放 <id>-<版本>.jar 与 .jar.sig）".format(entry)
                else:
                    message = "dist 里的 {} 没有被任何插件条目引用（索引和产物不是同一次构建？）".format(entry)
                if args.strict:
                    report.error(message)
                else:
                    report.note(message)

    for note in report.notes:
        print("[提示] " + note)
    for error in report.errors:
        print("[错误] " + error, file=sys.stderr)

    if report.errors:
        print("校验失败：{} 个错误".format(len(report.errors)), file=sys.stderr)
        return 1

    print("校验通过：{} 个插件（schema={}，hostApiVersion={}）".format(
        len(plugins), data.get("schema"), data.get("hostApiVersion")
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
