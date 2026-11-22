#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据 dist/ 里构建好的 jar 回写 index.json。

只在 CI 里跑。本地想手工生成一份也用它。downloadUrl / sigUrl 有两种写法规矩：

1. release 模式（--url-mode release，默认，兼容旧流程）：
   downloadUrl 写 GitHub Release 资产直链

       python3 tools/update-index.py --dist dist --tag v20261122 \
           --repo YangZiyueZY/AutoTapperPlugin

2. relative 模式（--url-mode relative，CI 现在用这个）：
   downloadUrl 写仓库内相对路径 dist/<id>-<版本>.jar，不再需要 --tag

       python3 tools/update-index.py --dist dist --url-mode relative \
           --repo YangZiyueZY/AutoTapperPlugin --version 0.1.0

   为什么要后者：Release 资产只挂在 github.com / objects.githubusercontent.com 上，
   jsDelivr 这类静态镜像只镜像仓库文件、不镜像 Release 资产，国内经常下不动。
   jar 放进 dist/ 入库之后，主程序把相对路径按仓库 raw 根拼成完整地址
   （见主程序 runtime/PluginRepo.kt 的 RepoSource.resolve），换成镜像域名就能下载。

元数据（显示名、描述、能力、变更说明）集中写在本文件的 PLUGIN_META 里，
不散落到每个插件目录，避免 20 个目录各写一份还写歪。
生成的 index.json 是主程序插件面板唯一读取的文件。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

# 与主程序 PluginApi.VERSION 对齐；不一致时 verify-index.py 会报错
SCHEMA = 1
HOST_API_VERSION = 1

# 所有插件当前统一版本号；单个插件要单独走版本时，在这里加一列即可
PLUGIN_VERSION = "0.1.0"

# downloadUrl / sigUrl 的两种写法。CI 用 RELATIVE，Release 直链保留作备份渠道。
URL_MODE_RELEASE = "release"    # GitHub Release 资产直链，必须给 --tag 与 --repo
URL_MODE_RELATIVE = "relative"  # 仓库内相对路径 dist/<id>-<版本>.jar，可走镜像

# 目录名 -> 元数据。字段顺序固定，便于 diff。
# 包名后缀 = 插件 id 去掉连字符；入口类名 = PascalCase + Plugin。
PLUGIN_META = {
    "tap-styles": {
        "name": "高级点击方式",
        "package": "tapstyles",
        "entry": "TapStylesPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "长按、连击与频率模式三种点击方式。",
        "changelog": "0.1.0：长按 / 连击 / 频率模式三种步骤类型，区间随机按住时长与间隔。",
    },
    "swipe": {
        "name": "滑动",
        "package": "swipe",
        "entry": "SwipePlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "两点之间的滑动，快中慢三档时长预设。",
        "changelog": "0.1.0：滑动步骤，起点与终点各取一个区域，时长取区间随机值。",
    },
    "multi-touch": {
        "name": "多点手势",
        "package": "multitouch",
        "entry": "MultiTouchPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "多指同时按下、保持与释放，最多十指。",
        "changelog": "0.1.0：多点同时按下步骤，按区域个数决定指头数。",
    },
    "region-random": {
        "name": "区域随机",
        "package": "regionrandom",
        "entry": "RegionRandomPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "在已画好的区域里均匀随机取点。",
        "changelog": "0.1.0：区域点击，支持顺序 / 随机 / 总是从第一个。",
    },
    "flow-control": {
        "name": "流程控制",
        "package": "flowcontrol",
        "entry": "FlowControlPlugin",
        "requires": [],
        "description": "分支、跳步、延迟与超时（策略扩展点，支配全部步骤）。",
        "changelog": "0.1.0：策略插件（全局延迟/超时/重复/失败分支）+ 流程设置 / 延迟 / 跳转三个步骤。",
    },
    "color": {
        "name": "识色",
        "package": "color",
        "entry": "ColorPlugin",
        "requires": ["SCREENSHOT"],
        "description": "读取区域内像素颜色并做阈值比较。",
        "changelog": "0.1.0：识色步骤，支持多色、容差与最少命中像素数。",
    },
    "ocr-text": {
        "name": "识字",
        "package": "ocrtext",
        "entry": "OcrTextPlugin",
        "requires": ["SCREENSHOT", "NETWORK"],
        "description": "截图后调用宿主 OCR 拿到文字并判断。",
        "changelog": "0.1.0：识字步骤，支持全等 / 包含 / 包含全部。",
    },
    "number-compare": {
        "name": "数字对比",
        "package": "numbercompare",
        "entry": "NumberComparePlugin",
        "requires": ["SCREENSHOT", "NETWORK"],
        "description": "从 OCR 结果里取出数字再比较大小。",
        "changelog": "0.1.0：数字对比步骤，支持定位字符、忽略字符与五种比较。",
    },
    "time-compare": {
        "name": "时间对比",
        "package": "timecompare",
        "entry": "TimeComparePlugin",
        "requires": [],
        "description": "用校准过的宿主时间做时间窗口判断。",
        "changelog": "0.1.0：时间对比步骤，支持时刻前后、时间段与星期几。",
    },
    "view-id": {
        "name": "识ID",
        "package": "viewid",
        "entry": "ViewIdPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "按 viewId 或文本找到无障碍节点再点击。",
        "changelog": "0.1.0：识 ID 步骤，支持 viewId / 文本 / 描述查找，可配文本兜底。",
    },
    "counter": {
        "name": "计数",
        "package": "counter",
        "entry": "CounterPlugin",
        "requires": [],
        "description": "按执行次数计数，到达目标后执行动作。",
        "changelog": "0.1.0：计数步骤，到达目标后可继续 / 停止 / 暂停 / 跳步 / 只记日志。",
    },
    "text-input": {
        "name": "文本输入",
        "package": "textinput",
        "entry": "TextInputPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "向当前焦点输入文本，支持顺序列表与字符集随机。",
        "changelog": "0.1.0：文本输入步骤，固定 / 顺序 / 随机列表 / 字符集随机。",
    },
    "bell": {
        "name": "响铃",
        "package": "bell",
        "entry": "BellPlugin",
        "requires": [],
        "description": "任务完成或出错时响铃震动。",
        "changelog": "0.1.0：响铃步骤（时长 / 震动 / 之后继续·暂停·停止）。",
    },
    "keys": {
        "name": "按键",
        "package": "keys",
        "entry": "KeysPlugin",
        "requires": ["ACCESSIBILITY"],
        "description": "发送返回、桌面、菜单、回车、删除键。",
        "changelog": "0.1.0：按键步骤，菜单键在无障碍通道下会如实提示改用 Shizuku。",
    },
    "recorder": {
        "name": "录制回放",
        "package": "recorder",
        "entry": "RecorderPlugin",
        "requires": ["OVERLAY", "ACCESSIBILITY"],
        "description": "录下人工操作并转成步骤。",
        "changelog": "0.1.0：录制步骤。点按会录成基础点击；滑动没有对应内置步骤，如实跳过并提示。",
    },
    "scheduler": {
        "name": "定时",
        "package": "scheduler",
        "entry": "SchedulerPlugin",
        "requires": [],
        "description": "等到某个时刻或星期几再继续。",
        "changelog": "0.1.0：定时等待步骤（每天某时刻 / 指定星期 / 固定时长，北京时间）。",
    },
    "logs": {
        "name": "实时日志",
        "package": "logs",
        "entry": "LogsPlugin",
        "requires": [],
        "description": "往运行日志里写一条自定义内容。",
        "changelog": "0.1.0：写日志步骤（四级 + {count}/{time} 占位符）。",
    },
    "rules": {
        "name": "规则管理",
        "package": "rules",
        "entry": "RulesPlugin",
        "requires": [],
        "description": "规则管理（多开/回收站/导入导出）已在主程序内置。",
        "changelog": "0.1.0：本插件不提供步骤类型——规则列表与回收站写在主程序的「规则」页。",
    },
    "console-ui": {
        "name": "控制台自定义",
        "package": "consoleui",
        "entry": "ConsoleUiPlugin",
        "requires": ["OVERLAY"],
        "description": "控制台透明度与显隐已在主程序设置里。",
        "changelog": "0.1.0：本插件暂不提供控制台格子——透明度滑杆在主界面「点击配置」里。",
    },
    "keepalive": {
        "name": "后台保活",
        "package": "keepalive",
        "entry": "KeepalivePlugin",
        "requires": [],
        "description": "前台服务保活已在主程序里。",
        "changelog": "0.1.0：本插件暂不提供保活提供者——运行中的注入服务本身就是前台服务。",
    },
}


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entry_class(plugin_id: str, meta: dict) -> str:
    return "com.autotapper.plugin.{}.{}".format(meta["package"], meta["entry"])


def url_dir(dist_dir: str) -> str:
    """相对模式下的仓库目录名。

    索引里的路径是相对**仓库根**的，不是相对本机磁盘的。
    所以只取 --dist 的最后一段（CI 与本地都叫 dist），绝对路径和 ./ 前缀都不会混进去。
    """
    cleaned = os.path.normpath(dist_dir).replace("\\", "/").rstrip("/")
    name = cleaned.rsplit("/", 1)[-1]
    return name or "dist"


def build_urls(url_mode: str, args, jar_name: str):
    """返回 (downloadUrl, sigUrl)。sigUrl 有没有签名由调用方再判断。"""
    if url_mode == URL_MODE_RELEASE:
        download_url = "https://github.com/{}/releases/download/{}/{}".format(
            args.repo, args.tag, jar_name
        )
        return download_url, download_url + ".sig"
    # 相对路径统一正斜杠：URL 里不能出现 Windows 的反斜杠
    download_url = "{}/{}".format(url_dir(args.dist), jar_name)
    return download_url, download_url + ".sig"


def main() -> int:
    parser = argparse.ArgumentParser(description="根据 dist/ 里的 jar 生成 index.json")
    parser.add_argument("--dist", default="dist", help="jar 所在目录，默认 dist/")
    parser.add_argument("--out", default="index.json", help="输出路径，默认 index.json")
    parser.add_argument(
        "--url-mode",
        choices=(URL_MODE_RELEASE, URL_MODE_RELATIVE),
        default=URL_MODE_RELEASE,
        help="downloadUrl/sigUrl 写法：release=GitHub Release 直链（默认，兼容旧流程）；"
             "relative=仓库内相对路径 dist/<id>-<版本>.jar（可走镜像，CI 用这个）",
    )
    parser.add_argument("--tag", default=None, help="Release tag，例如 v20261122（release 模式必填）")
    parser.add_argument(
        "--repo",
        default=None,
        help="owner/repo，例如 YangZiyueZY/AutoTapperPlugin（release 模式必填）",
    )
    parser.add_argument("--version", default=PLUGIN_VERSION, help="插件版本号")
    args = parser.parse_args()

    # --repo 在 relative 模式下不参与拼地址，但索引本身还是要说明这些插件属于哪个仓库，
    # 所以保留该参数；缺了只影响 release 模式。
    if args.url_mode == URL_MODE_RELEASE:
        missing = []
        if not args.tag:
            missing.append("--tag")
        if not args.repo:
            missing.append("--repo")
        if missing:
            print(
                "release 模式必须提供 {}（想用相对路径请加 --url-mode relative）".format(
                    "、".join(missing)
                ),
                file=sys.stderr,
            )
            return 2

    if not os.path.isdir(args.dist):
        print("找不到 dist 目录：{}".format(args.dist), file=sys.stderr)
        return 2

    plugins = []
    for plugin_id in sorted(PLUGIN_META):
        meta = PLUGIN_META[plugin_id]
        jar_name = "{}-{}.jar".format(plugin_id, args.version)
        jar_path = os.path.join(args.dist, jar_name)
        if not os.path.isfile(jar_path):
            print("[跳过] {} 没有构建产物 {}".format(plugin_id, jar_path), file=sys.stderr)
            continue

        sig_path = jar_path + ".sig"
        download_url, sig_url = build_urls(args.url_mode, args, jar_name)
        plugins.append(
            {
                "id": plugin_id,
                "name": meta["name"],
                "version": args.version,
                "apiVersion": HOST_API_VERSION,
                "minHostVersionCode": 1,
                "entryClass": entry_class(plugin_id, meta),
                "requires": list(meta["requires"]),
                "sizeBytes": os.path.getsize(jar_path),
                "sha256": sha256_of(jar_path),
                "downloadUrl": download_url,
                # 没签名时留空字符串，主程序会显示「未签名」，不会假装已验签
                "sigUrl": sig_url if os.path.isfile(sig_path) else "",
                "description": meta["description"],
                "changelog": meta["changelog"],
            }
        )

    index = {
        "schema": SCHEMA,
        "hostApiVersion": HOST_API_VERSION,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "plugins": plugins,
    }

    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(index, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print("已写入 {}，{} 个插件（url-mode={}）".format(args.out, len(plugins), args.url_mode))
    if args.url_mode == URL_MODE_RELATIVE:
        print(
            "提示：相对模式要求 {} 里的 jar 与 {} 一起提交到仓库，"
            "否则主程序按仓库地址会 404（索引里现在指向 {}/*）。".format(
                url_dir(args.dist), args.out, url_dir(args.dist)
            )
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())