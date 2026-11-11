#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本号一致性检查 —— CI 与本地跑的是同一份实现。

用法：
    python docs/scripts/version_check.py                          # 打印 versionName / versionCode
    python docs/scripts/version_check.py --expect-tag v0.3.19     # 校验 tag 与 versionName 一致（release.yml 用）
    python docs/scripts/version_check.py --expect-version 0.3.19  # 校验版本号等于指定值
    python docs/scripts/version_check.py --github-output           # 额外写进 $GITHUB_OUTPUT

退出码：
    0 = 通过
    1 = 不一致（打印 ::error:: 供 GitHub Actions 直接高亮）

为什么不用 grep：
    release.yml 原来用 `grep -m1 'versionName' app/build.gradle.kts`，
    一旦注释里先出现 versionName 就会取错值。这里先剥掉注释再精确匹配。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Optional

GRADLE_REL = "app/build.gradle.kts"

VERSION_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')
VERSION_CODE_RE = re.compile(r'versionCode\s*=\s*(\d+)')
VERSION_NAME_FORMAT = re.compile(r'^\d+\.\d+\.\d+([.-][A-Za-z0-9.]+)?$')


def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释，但保留字符串字面量里的内容。"""
    out = []
    in_string = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if in_string:
            out.append(ch)
            if ch == chr(92) and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def read_versions(root: Path) -> tuple[Optional[str], Optional[int]]:
    path = root / GRADLE_REL
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except OSError:
        return None, None
    code = strip_comments(raw)
    name_m = VERSION_NAME_RE.search(code)
    code_m = VERSION_CODE_RE.search(code)
    name = name_m.group(1) if name_m else None
    version_code = int(code_m.group(1)) if code_m else None
    return name, version_code


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(description="notifyme 版本号一致性检查")
    parser.add_argument("--root", default=None, help="仓库根目录（默认取本脚本上两级）")
    parser.add_argument("--expect-tag", default=None, help="期望的 git tag，如 v0.3.19")
    parser.add_argument("--expect-version", default=None, help="期望的 versionName")
    parser.add_argument("--github-output", action="store_true", help="把结果写进 $GITHUB_OUTPUT")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[2]
    name, version_code = read_versions(root)
    errors: list[str] = []

    if not name:
        errors.append("读不到 " + GRADLE_REL + " 里的 versionName")
    elif not VERSION_NAME_FORMAT.match(name):
        errors.append("versionName 格式非法：" + name + "（应为 x.y.z）")
    if version_code is None:
        errors.append("读不到 " + GRADLE_REL + " 里的 versionCode")
    elif version_code <= 0:
        errors.append("versionCode 必须为正整数，实际 " + str(version_code))

    if name and args.expect_tag:
        expect = args.expect_tag.strip()
        tag_version = expect[1:] if expect.startswith("v") else expect
        if tag_version != name:
            errors.append("tag " + expect + " 与 versionName=" + name + " 不一致，请先升版本号再打 tag")

    if name and args.expect_version:
        if args.expect_version.strip() != name:
            errors.append("期望 versionName=" + args.expect_version.strip() + "，实际 " + name)

    print("[version] " + GRADLE_REL + ": versionName=" + str(name) + " versionCode=" + str(version_code))

    if args.github_output:
        out_path = os.environ.get("GITHUB_OUTPUT")
        if out_path:
            with open(out_path, "a", encoding="utf-8") as fh:
                fh.write("versionName=" + str(name) + "\n")
                fh.write("versionCode=" + str(version_code) + "\n")

    if errors:
        for e in errors:
            print("::error file=" + GRADLE_REL + ",title=version::版本校验失败：" + e)
        return 1

    print("[version] 通过。")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass
    sys.exit(main())
