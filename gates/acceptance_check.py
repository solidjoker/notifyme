"""验收门：一条改动算不算“做完”，机器说了算。

核心是那句原文：A check that wasn't run is listed as not run, never left out.

用法：
    python -m gates.acceptance_check --commit HEAD
    python -m gates.acceptance_check --message .git/COMMIT_EDITMSG
    python -m gates.acceptance_check --json .loopstate/acceptance/42.json
    python -m gates.acceptance_check --all .loopstate/acceptance

退出码 0 = 通过，1 = 不通过。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

REQUIRED = ("source", "cause", "change", "verified", "red_green", "not_run")

# 循环不许改自己的规矩。改这些是人（或者 loop3 写建议）的事。
PROTECTED_PATHS = (
    "AGENTS.md",
    "LESSONS.md",
    "docs/",
    ".github/",
    "config/",
    "prompts/",
)

GENERIC_EXCUSES = {
    "fix",
    "fix bug",
    "fixed",
    "update",
    "update code",
    "修复",
    "修复bug",
    "修复问题",
    "修复了一些问题",
    "修改",
    "优化代码",
    "一些改动",
    "misc",
    "wip",
}

# 短句以这些词开头，基本就是“我改了点什么”而不是“我改了什么”
GENERIC_PREFIXES = ("修复", "修改", "优化", "调整", "改了一下", "fix", "update", "refactor")

VERSION_RE = re.compile(r"(?:v|version\s*)\d+\.\d+", re.I)
ISSUE_RE = re.compile(r"#\d+|\b(?:issue|gh)[- ]?\d+", re.I)
URL_RE = re.compile(r"https?://\S+")
RESULT_OK = {"PASS", "FAIL", "SKIP"}


# --------------------------------------------------------------------------- 解析


def norm_key(key: str) -> str:
    key = str(key).strip().lower().replace("-", "_").replace(" ", "_")
    return key.replace("red/green", "red_green").replace("/", "_")


def parse_text(text: str) -> dict[str, Any]:
    """解析提交说明里的验收块。散文不算，只认 `Key:` 和下面的缩进列表。"""
    record: dict[str, Any] = {}
    current: str | None = None

    for raw_line in text.splitlines():
        stripped = raw_line.strip()
        if not stripped:
            continue
        if stripped.lower() in {"acceptance:", "acceptance record:"}:
            continue

        header = re.match(r"^([A-Za-z][A-Za-z /_-]{1,24}):\s*(.*)$", stripped)
        if header and not stripped.startswith(("-", "*")):
            key = norm_key(header.group(1))
            if key in REQUIRED:
                value = header.group(2).strip()
                if value:
                    record[key] = value
                    current = None
                else:
                    record[key] = []
                    current = key
                continue

        if current and stripped.startswith(("-", "*")):
            item = stripped[1:].strip()
            if item:
                record[current].append(item)
            continue

        # 验收块结束（签名行、循环标记、分隔线、普通段落）
        current = None

    return record


def parse_verified(items: Any, problems: list[str]) -> list[dict[str, str]]:
    parsed: list[dict[str, str]] = []
    for item in items or []:
        if isinstance(item, dict):
            where = str(item.get("where", "")).strip()
            check = str(item.get("check", "")).strip()
            result = str(item.get("result", "")).strip().upper()
        else:
            parts = [p.strip() for p in str(item).split("|")]
            if len(parts) < 3:
                problems.append(f"Verified 行格式不对（要 `位置 | 检查项 | 结果`）：{item}")
                continue
            where, check, result = parts[0], "|".join(parts[1:-1]).strip(), parts[-1].upper()
        if result not in RESULT_OK:
            problems.append(f"Verified 结果只能是 PASS/FAIL/SKIP，收到 {result!r}：{where} {check}")
            continue
        if not where:
            problems.append(f"Verified 缺“在哪跑”：{item}")
        parsed.append({"where": where, "check": check, "result": result})
    return parsed


def parse_red_green(items: Any) -> list[dict[str, str]]:
    parsed: list[dict[str, str]] = []
    for item in items or []:
        if isinstance(item, dict):
            parsed.append(
                {
                    "step": str(item.get("step", "")).strip(),
                    "result": str(item.get("result", "")).strip().upper(),
                    "note": str(item.get("note", "")).strip(),
                }
            )
        else:
            text = str(item).strip()
            match = re.search(r"\b(PASS|FAIL)\b", text, re.I)
            parsed.append(
                {"step": text, "result": match.group(1).upper() if match else "", "note": text}
            )
    return parsed


def parse_not_run(items: Any) -> list[dict[str, str]]:
    if isinstance(items, (str, dict)):
        items = [items]
    out: list[dict[str, str]] = []
    for item in items or []:
        if isinstance(item, dict):
            out.append(
                {"what": str(item.get("what", "")).strip(), "why": str(item.get("why", "")).strip()}
            )
        else:
            text = str(item).strip()
            why = ""
            match = re.search(r"[（(]([^)）]+)[)）]\s*$", text)
            if match:
                why = match.group(1).strip()
                text = text[: match.start()].strip()
            out.append({"what": text, "why": why})
    return out


def canonical(record: dict[str, Any], problems: list[str]) -> dict[str, Any]:
    """两种输入（文本 / JSON）先归一，再统一校验。"""
    raw = {norm_key(k): v for k, v in record.items()}
    return {
        "source": str(raw.get("source") or "").strip(),
        "cause": str(raw.get("cause") or "").strip(),
        "change": str(raw.get("change") or "").strip(),
        "verified": parse_verified(raw.get("verified"), problems),
        "red_green": parse_red_green(raw.get("red_green")),
        "not_run": parse_not_run(raw.get("not_run")) if "not_run" in raw else None,
        "files": [str(f) for f in (raw.get("files") or [])],
    }


# --------------------------------------------------------------------------- 校验


MIN_TEXT = 6
GENERIC_LEN = 12


def is_generic(value: str) -> bool:
    """是不是“我改了点什么”这种交差话。"""
    v = value.lower().strip("。.! ")
    if v in GENERIC_EXCUSES:
        return True
    return len(v) <= GENERIC_LEN and any(v.startswith(prefix) for prefix in GENERIC_PREFIXES)


def strip_dot(path: str) -> str:
    """去掉开头的 ./ 。不能用 lstrip('./')——会把 .github 的点也吃掉。"""
    p = str(path).replace("\\", "/")
    while p.startswith("./"):
        p = p[2:]
    return p


def validate(record: dict[str, Any]) -> tuple[list[str], list[str]]:
    problems: list[str] = []
    warnings: list[str] = []
    rec = canonical(record, problems)

    if not rec["source"]:
        problems.append("缺 Source：谁在哪报的，凭什么说这是真问题")
    elif not (VERSION_RE.search(rec["source"]) or ISSUE_RE.search(rec["source"]) or URL_RE.search(rec["source"])):
        warnings.append("Source 最好带版本号或 issue 号，方便 loop3 回头对账")

    for key, label in (("cause", "Cause"), ("change", "Change")):
        value = rec[key]
        if not value:
            problems.append(f"缺 {label}")
        elif is_generic(value):
            problems.append(f"{label} 是空话（{value!r}），换成具体描述")
        elif len(value) < MIN_TEXT:
            problems.append(f"{label} 太短，写清具体是什么，别一句话交差")

    verified = rec["verified"]
    if not verified:
        problems.append("Verified 至少要有 1 行：跑了什么、在哪跑、什么结果")
    else:
        results = {v["result"] for v in verified}
        if "PASS" not in results:
            problems.append("Verified 里没有一条 PASS，等于没验证过")
        if results == {"SKIP"}:
            problems.append("Verified 全是 SKIP，等于没跑")

    red_green = rec["red_green"]
    if not red_green:
        problems.append("缺 Red/Green：旧代码改回去必须真的 FAIL，换上新代码必须 PASS")
    else:
        results = {item["result"] for item in red_green}
        if "FAIL" not in results:
            problems.append("Red/Green 里没有 FAIL：说明没验证“没有这次改动会不会失败”")
        if "PASS" not in results:
            problems.append("Red/Green 里没有 PASS：改动之后没跑过绿")
        for item in red_green:
            if item["result"] not in {"PASS", "FAIL"}:
                problems.append(f"Red/Green 每行要标 PASS 或 FAIL：{item['step']}")

    not_run = rec["not_run"]
    if not_run is None:
        problems.append("缺 Not run：全跑了就写 none，不许不提")
    elif not not_run:
        problems.append("Not run 是空的：全跑了写 none，别留空")
    else:
        for item in not_run:
            if item["what"].lower() in {"none", "无", "n/a", "-", "无。"}:
                continue
            if not item["why"]:
                warnings.append(f"没跑的东西最好写个原因：{item['what']}")

    for path in rec["files"]:
        norm = strip_dot(path)
        for proto in PROTECTED_PATHS:
            if norm == proto.rstrip("/") or norm.startswith(proto):
                problems.append(f"改到了受保护路径 {norm}：循环不许改自己的规矩，这类改动得人来做")

    return problems, warnings


# --------------------------------------------------------------------------- 输入


def read_commit(rev: str) -> str:
    proc = subprocess.run(
        ["git", "show", "-s", "--format=%B", rev],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise SystemExit(f"读不到提交 {rev}：{proc.stderr.strip()}")
    return proc.stdout


def check_one(label: str, record: dict[str, Any], strict: bool) -> bool:
    problems, warnings = validate(record)
    if strict:
        problems, warnings = problems + warnings, []
    if problems:
        print(f"\n✗ {label}")
        for item in problems:
            print(f"    - {item}")
        for item in warnings:
            print(f"    ~ {item}")
        return False
    print(f"\n✓ {label}")
    for item in warnings:
        print(f"    ~ {item}")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="验收记录格式校验")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--commit", help="读某个提交的说明，例如 HEAD")
    group.add_argument("--message", help="读一个文本文件（- 表示 stdin）")
    group.add_argument("--json", help="读一份结构化验收记录")
    group.add_argument("--all", help="校验目录下所有 .json 验收记录")
    parser.add_argument("--strict", action="store_true", help="警告也当错误")
    args = parser.parse_args(argv)

    ok = True
    if args.commit:
        ok = check_one(f"commit {args.commit}", parse_text(read_commit(args.commit)), args.strict)
    elif args.message:
        text = sys.stdin.read() if args.message == "-" else Path(args.message).read_text(encoding="utf-8")
        ok = check_one(args.message, parse_text(text), args.strict)
    elif args.json:
        data = json.loads(Path(args.json).read_text(encoding="utf-8"))
        ok = check_one(args.json, data, args.strict)
    elif args.all:
        files = sorted(Path(args.all).glob("*.json"))
        if not files:
            print(f"{args.all} 下没有验收记录")
            return 1
        for path in files:
            data = json.loads(path.read_text(encoding="utf-8"))
            ok &= check_one(str(path), data, args.strict)

    print()
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
