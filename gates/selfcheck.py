"""自检：CI 跑的就是这个。

把散在各处的规矩收成一个命令，本地和 CI 用完全一样的入口：

    python -m gates.selfcheck

查六件事：
  1. 所有 Python 文件能编译
  2. 单测全绿
  3. LESSONS.md 每条都有 Rule / Why / Evidence / Seen
  4. config/ci_rules.json 里启用的规则，逐条对着仓库查
  5. 配置能解析、提示词没缺占位符
  6. 有教训该晋升、有 CI 规则还是桩，报出来（不判红）
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import py_compile
import re
import subprocess
import sys
import tomllib
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
CI_RULES = REPO_ROOT / "config" / "ci_rules.json"


def section(title: str) -> None:
    print(f"\n── {title} " + "─" * max(0, 60 - len(title)))


def check_compile() -> list[str]:
    bad: list[str] = []
    for path in sorted(REPO_ROOT.rglob("*.py")):
        if ".venv" in path.parts or "__pycache__" in path.parts:
            continue
        try:
            py_compile.compile(str(path), doraise=True, cfile=str(REPO_ROOT / ".loopstate" / "pyc.tmp"))
        except py_compile.PyCompileError as exc:
            bad.append(f"{path.relative_to(REPO_ROOT)}: {exc.msg.splitlines()[-1]}")
    return bad


def check_tests() -> tuple[bool, str]:
    proc = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    tail = (proc.stderr or proc.stdout or "").strip().splitlines()[-3:]
    return proc.returncode == 0, "\n".join(tail)


def check_lessons() -> list[str]:
    sys.path.insert(0, str(REPO_ROOT))
    from gates.lessons_promote import load_lessons

    problems: list[str] = []
    _, lessons = load_lessons()
    for lesson in lessons:
        if not lesson.rule:
            problems.append(f"{lesson.id} 缺 Rule")
        if not lesson.why:
            problems.append(f"{lesson.id} 缺 Why（没写为什么，下一轮就会忘）")
        if not lesson.evidence:
            problems.append(f"{lesson.id} 缺 Evidence（没有 commit 或 issue 号，等于没法查）")
        if not lesson.seen:
            problems.append(f"{lesson.id} 缺 Seen 日期")
    return problems


def check_ci_rules() -> tuple[list[str], list[str]]:
    """返回 (违反项, 待实现项)。"""
    if not CI_RULES.exists():
        return [], []
    data = json.loads(CI_RULES.read_text(encoding="utf-8"))
    violations: list[str] = []
    pending: list[str] = []

    for rule in data.get("rules", []):
        pattern = rule.get("pattern")
        if not rule.get("enabled") or not pattern:
            pending.append(f"{rule.get('id')} {rule.get('title', '')}".strip())
            continue
        try:
            compiled = re.compile(pattern)
        except re.error as exc:
            violations.append(f"{rule.get('id')} 的正则写坏了：{exc}")
            continue
        files = rule.get("scope") or ["**/*.py"]
        for path in REPO_ROOT.rglob("*"):
            if not path.is_file() or ".venv" in path.parts or "__pycache__" in path.parts:
                continue
            rel = str(path.relative_to(REPO_ROOT)).replace("\\", "/")
            if not any(fnmatch.fnmatch(rel, glob) or fnmatch.fnmatch(rel, glob.lstrip("*/")) for glob in files):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for lineno, line in enumerate(text.splitlines(), 1):
                if compiled.search(line):
                    violations.append(f"{rule.get('id')} {rel}:{lineno} → {line.strip()[:80]}")
    return violations, pending


def check_config() -> tuple[list[str], list[str]]:
    problems: list[str] = []
    notes: list[str] = []
    cfg_path = REPO_ROOT / "config" / "workflow.toml"
    with open(cfg_path, "rb") as fh:
        cfg = tomllib.load(fh)
    for key in ("repo", "models", "agent", "gate"):
        if key not in cfg:
            problems.append(f"config/workflow.toml 缺 [{key}]")
    hazard = REPO_ROOT / cfg.get("gate", {}).get("hazard_policy", "config/hazard_rules.json")
    if not hazard.exists():
        problems.append(f"危害清单不存在：{hazard}")
    else:
        json.loads(hazard.read_text(encoding="utf-8"))

    sys.path.insert(0, str(REPO_ROOT))
    from loops.common import Prompts

    prompts = Prompts()
    samples = {
        "classify": {"product": "magpie", "item_body": "x"},
        "triage": {
            "loop_name": "loop1",
            "date": "2026-11-17",
            "item_source": "test",
            "item_title": "t",
            "item_body": "b",
            "lessons": "",
        },
        "fix": {
            "item_source": "test",
            "item_title": "t",
            "item_body": "b",
            "repro": "",
            "lessons": "",
            "code_standards": "",
            "item_id": "test",
        },
        "review": {
            "since": "-24h",
            "git_log": "",
            "acceptance_records": "",
        },
    }
    for name, vars in samples.items():
        try:
            prompts.render(name, **vars)
        except (ValueError, OSError) as exc:
            problems.append(f"prompts/{name}.md: {exc}")
    return problems, notes


def check_backlog() -> list[str]:
    sys.path.insert(0, str(REPO_ROOT))
    from gates.lessons_promote import load_lessons, plan

    _, lessons = load_lessons()
    return [f"{lesson.id} {lesson.title}（seen={len(lesson.seen)}）" for lesson in plan(lessons)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="仓库自检")
    parser.add_argument("--strict", action="store_true", help="待办项也当失败")
    args = parser.parse_args(argv)

    failures = 0

    section("编译")
    bad = check_compile()
    print("✓ 全部通过" if not bad else "\n".join(f"✗ {item}" for item in bad))
    failures += len(bad)

    section("单测")
    ok, tail = check_tests()
    print(("✓ " if ok else "✗ ") + (tail or "no tests"))
    failures += 0 if ok else 1

    section("错题本格式")
    problems = check_lessons()
    print("✓ 格式正确" if not problems else "\n".join(f"✗ {item}" for item in problems))
    failures += len(problems)

    section("CI 规则")
    violations, pending = check_ci_rules()
    if violations:
        print("\n".join(f"✗ {item}" for item in violations))
    else:
        print("✓ 已启用的规则全部通过")
    if pending:
        print(f"~ {len(pending)} 条还是桩（晋升了但没人写检查）：")
        for item in pending:
            print(f"    {item}")
    failures += len(violations)

    section("配置与提示词")
    problems, _ = check_config()
    print("✓ 能解析，占位符齐全" if not problems else "\n".join(f"✗ {item}" for item in problems))
    failures += len(problems)

    section("待办")
    backlog = check_backlog()
    print(f"{len(backlog)} 条教训够格晋升")
    for item in backlog:
        print(f"    {item}")
    if args.strict and backlog:
        failures += len(backlog)

    print()
    if failures:
        print(f"✗ {failures} 处不通过")
        return 1
    print("✓ 全过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
