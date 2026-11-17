"""危害门：社区 PR 合之前，先查危害。

清单的人读版在 docs/COMMUNITY_RULES.md，机读版在 config/hazard_rules.json。
命中任何一条就是 block——停下等维护者，循环不许自己放行。

用法：
    python -m gates.hazard_check --base origin/main --head HEAD
    python -m gates.hazard_check --files a.py b.sh
    python -m gates.hazard_check --diff-file pr.diff --author some-user
    python -m gates.hazard_check --base origin/main --json

退出码 0 = 放行，1 = 有 block。
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import math
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RULES = REPO_ROOT / "config" / "hazard_rules.json"

DOC_EXTS = {".md", ".markdown", ".txt", ".rst", ".adoc"}
SKIP_BLOB_EXTS = DOC_EXTS | {".lock", ".sum", ".csv", ".svg", ".png", ".jpg", ".woff", ".woff2"}


# --------------------------------------------------------------------------- 规则


def load_rules(path: Path = DEFAULT_RULES) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def strip_dot(path: str) -> str:
    """去掉开头的 ./ 。注意不能用 lstrip('./')——那会把 .github 的点也吃掉。"""
    p = str(path).replace("\\", "/")
    while p.startswith("./"):
        p = p[2:]
    return p


def path_matches(path: str, pattern: str) -> bool:
    p = strip_dot(path)
    if fnmatch.fnmatch(p, pattern):
        return True
    if pattern.startswith("**/") and fnmatch.fnmatch(p, pattern[3:]):
        return True
    if fnmatch.fnmatch(Path(p).name, pattern):
        return True
    return False


def any_match(path: str, patterns: list[str]) -> str | None:
    for pattern in patterns:
        if path_matches(path, pattern):
            return pattern
    return None


# --------------------------------------------------------------------------- diff


def parse_diff(text: str) -> tuple[list[dict[str, Any]], list[str]]:
    """返回 (每个文件的 added 行, 变更文件列表)。"""
    files: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None

    for line in text.splitlines():
        if line.startswith("+++ b/"):
            current = {"path": line[6:].strip(), "added": [], "removed": []}
            files.append(current)
            continue
        if line.startswith("+++ /dev/null") or line.startswith("--- "):
            continue
        if current is None:
            continue
        if line.startswith("+") and not line.startswith("+++"):
            current["added"].append(line[1:])
        elif line.startswith("-") and not line.startswith("---"):
            current["removed"].append(line[1:])

    return files, [f["path"] for f in files]


def git_diff(base: str, head: str) -> str:
    proc = subprocess.run(
        ["git", "diff", f"{base}...{head}"],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise SystemExit(f"git diff 失败：{proc.stderr.strip()}")
    return proc.stdout


# --------------------------------------------------------------------------- 检查


def looks_opaque(line: str, cfg: dict[str, Any]) -> str | None:
    """行里有没有超长、字符种类极少的编码块 → 不可审阅的代码。

    看的是行里最长的那个 token，不是整行——`DATA = "AAAA…"` 这种写法里，
    引号和等号会让整行的字符集看起来很“丰富”，但真正的问题在引号里面。
    """
    min_len = int(cfg.get("min_len", 200))
    for token in re.findall(r"[A-Za-z0-9+/=_-]{%d,}" % min_len, line):
        ratio = len(token) / max(1, len(set(token)))
        if ratio < float(cfg.get("min_unique_ratio", 4.0)):
            continue
        if shannon(token) > 3.4:
            return token
    return None


def shannon(text: str) -> float:
    counts = Counter(text)
    total = len(text)
    return -sum((n / total) * math.log2(n / total) for n in counts.values())


def host_of(url: str) -> str:
    """接受完整 URL，也接受正则抓下来的裸 host/path。"""
    match = re.match(r"https?://([^/:?#]+)", url)
    if match:
        return match.group(1).lower()
    return url.split("/", 1)[0].lower()


def check(
    changed: list[str],
    diff_files: list[dict[str, Any]],
    rules: dict[str, Any],
    author: str | None = None,
    blocklist: set[str] | None = None,
) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []

    def add(rule: str, kind: str, detail: str) -> None:
        findings.append({"rule": rule, "kind": kind, "detail": detail})

    # 1. 路径类：CI / 发布 / 依赖 / 循环自己的开关
    for path in changed:
        for rule in rules.get("path_rules", []):
            hit = any_match(path, rule.get("globs", []))
            if hit:
                add(rule["id"], rule["kind"], f"{path}（命中 {hit}）—— {rule.get('why', '')}")
                break

    # 2. 凭据文件
    for path in changed:
        hit = any_match(path, rules.get("credential_globs", []))
        if hit:
            add("H4", "credential_access", f"{path} 是凭据相关文件（命中 {hit}）")

    # 3. 黑名单
    if author and blocklist:
        for entry in blocklist:
            if entry.lower().endswith("/" + author.lower()) or entry.lower() == author.lower():
                add("BLOCK", "blocklist", f"{author} 在人工复核名单里（{entry}）")

    cred_patterns = [re.compile(p) for p in rules.get("credential_content_patterns", [])]
    telemetry = [re.compile(p, re.I) for p in rules.get("telemetry_patterns", [])]
    promo = [re.compile(p, re.I) for p in rules.get("promo_patterns", [])]
    # 文字类关键词（"推广链接"、"返利"这些）单独出现不算问题——有人写文档解释这条规则
    # 就会被自己拦住。只有同一行还有个链接时，才真的是在塞推广。
    promo_ctx = [re.compile(p, re.I) for p in rules.get("promo_context_patterns", [])]
    network_re = re.compile(rules.get("network_pattern", r"https?://[^\s\"'<>)\]}]+"))
    allow_hosts = {h.lower() for h in rules.get("allow_hosts", [])}

    for item in diff_files:
        path = item["path"]
        suffix = Path(path).suffix.lower()
        for line in item["added"]:
            if not line.strip():
                continue

            for pattern in cred_patterns:
                if pattern.search(line):
                    add("H4", "credential_leak", f"{path} 新增了一行疑似凭据（{pattern.pattern[:24]}…）")
                    break

            if suffix not in DOC_EXTS:
                for pattern in telemetry:
                    if pattern.search(line):
                        add("H3", "telemetry_upload", f"{path} 新增了上报/遥测痕迹（{pattern.pattern}）")
                        break

                for url in network_re.findall(line):
                    host = host_of(url)
                    if not host:
                        continue
                    if host in allow_hosts or any(host.endswith("." + h) for h in allow_hosts):
                        continue
                    add("H2", "new_network_address", f"{path} 新增外发地址 {host}")

            hit = next((p.pattern for p in promo if p.search(line)), None)
            if hit:
                add("H6", "promotion_link", f"{path} 出现推广/中转参数（{hit}）")
            elif network_re.search(line):
                word = next((p.pattern for p in promo_ctx if p.search(line)), None)
                if word:
                    add("H6", "promotion_link", f"{path} 推广字眼和链接出现在同一行（{word}）")

            blob_cfg = rules.get("opaque_blob", {})
            if suffix not in SKIP_BLOB_EXTS:
                blob = looks_opaque(line, blob_cfg)
                if blob:
                    add("H5", "opaque_blob", f"{path} 有一行 {len(blob)} 字符的编码块，无法审阅")

    # 去重（同一个文件同一个规则只报一次）
    unique: dict[tuple[str, str, str], dict[str, str]] = {}
    for finding in findings:
        unique[(finding["rule"], finding["kind"], finding["detail"])] = finding
    return list(unique.values())


def read_blocklist(path: Path) -> set[str]:
    if not path.exists():
        return set()
    entries = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


# --------------------------------------------------------------------------- 入口


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PR 危害清单检查")
    parser.add_argument("--base", help="对比基线，例如 origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--files", nargs="*", help="只给文件列表（无 diff 内容）")
    parser.add_argument("--diff-file", help="从文件读 unified diff")
    parser.add_argument("--author", help="PR 作者，用于查黑名单")
    parser.add_argument("--rules", default=str(DEFAULT_RULES))
    parser.add_argument("--blocklist", default=str(REPO_ROOT / "config" / "blocklist.txt"))
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    args = parser.parse_args(argv)

    rules = load_rules(Path(args.rules))
    blocklist = read_blocklist(Path(args.blocklist))

    if args.diff_file:
        diff = Path(args.diff_file).read_text(encoding="utf-8", errors="replace")
        diff_files, changed = parse_diff(diff)
    elif args.base:
        diff = git_diff(args.base, args.head)
        diff_files, changed = parse_diff(diff)
    elif args.files is not None:
        changed = args.files
        diff_files = [{"path": f, "added": [], "removed": []} for f in changed]
    else:
        parser.error("要么给 --base，要么给 --files 或 --diff-file")

    findings = check(changed, diff_files, rules, args.author, blocklist)

    if args.json:
        print(json.dumps({"verdict": "block" if findings else "pass", "findings": findings}, ensure_ascii=False, indent=2))
    else:
        print(f"查了 {len(changed)} 个文件，{sum(len(f['added']) for f in diff_files)} 行新增")
        if not findings:
            print("✓ 没碰到危害清单，可以走正常审查")
        else:
            print(f"\n✗ 命中 {len(findings)} 条，停下等维护者：\n")
            for finding in findings:
                print(f"  [{finding['rule']}/{finding['kind']}] {finding['detail']}")
            print("\n  维护者放行之前，这个 PR 的代码不准在有真实账号的机器上跑。")

    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
