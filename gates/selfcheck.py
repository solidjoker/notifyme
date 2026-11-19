"""自检：CI 跑的就是这个。

把散在各处的规矩收成一个命令，本地和 CI 用完全一样的入口：

    python -m gates.selfcheck

查六件事：
  1. 所有 Python 文件能编译（仓库自身的 .py；被剪枝的目录不进，见 SKIP_* 两个清单）
  2. 单测全绿
  3. LESSONS.md 每条都有 Rule / Why / Evidence / Seen
  4. config/ci_rules.json 里启用的规则，逐条对着仓库查
  5. 配置能解析、提示词没缺占位符
  6. 有教训该晋升、有 CI 规则还是桩，报出来（不判红）
  7. 哨兵：被剪掉的目录里不许有被 git 跟踪的文件（防止真代码被静默剪掉）
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import py_compile
import re
import subprocess
import sys
import tomllib
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
CI_RULES = REPO_ROOT / "config" / "ci_rules.json"

# 自检要扫全仓库，但有几类目录不该扫进去：
#   ① vendored 依赖 —— third_party/llama.cpp 是 CI clone 下来的源码，本机也有，几千个文件；
#   ② 构建产物与本地缓存 —— build/ dist/ tmp/ .gradle/ .gradle-home/ .loopstate/ 等；
#   ③ 版本库内部 —— .git/（对象与索引都不是源码）；
#   ④ 运行时数据与用户数据 —— server/data/（端侧消息原文）、files/、app/files/、captures/。
# 剪枝**只影响速度**，不影响对本仓库源码的覆盖 —— 前提是「被剪的目录里没有正规源码」，
# 这件事由 check_pruned_tracked() 这个哨兵盯着（见下）。
# 剪枝必须用 os.walk 的 in-place 剪枝：rglob 即使逐条过滤 parts，也已经把整棵树走了一遍
# （本机 5 万条目 1.5 秒 → 剪枝后 1900 个文件 0.04 秒）。
#
# 两级清单，刻意分开：
#   SKIP_DIR_NAMES —— 点目录名，**任何层级**下同名都剪（.git / .venv / __pycache__ 这类
#                     不可能是源码目录的名字）；
#   SKIP_REL_PATHS —— 锚定到仓库根的相对路径，**只剪这一个确切位置**。
#                     build / dist / tmp 用锚定而不是目录名：否则将来有人建一个叫
#                     `server/dist/` 的**源码**目录会被静默剪掉，真代码不进检查圈
#                     （现在的隐患是 build / dist / tmp 三个名字）。
SKIP_DIR_NAMES = frozenset(
    {
        ".git",
        ".gradle",
        ".gradle-home",
        ".idea",
        ".kotlin",
        ".loopstate",
        ".pytest_cache",
        ".venv",
        "__pycache__",
        "node_modules",
    }
)

SKIP_REL_PATHS = frozenset(
    {
        "build",
        "app/build",
        "app/.cxx",
        "captures",
        "dist",
        "files",
        "app/files",
        "server/data",
        "third_party",
        "tmp",
    }
)


def iter_repo_files():
    """遍历仓库里的文件，跳过 SKIP_DIR_NAMES / SKIP_REL_PATHS 里的目录（不进入它们）。

    产出与 REPO_ROOT.rglob("*") 同构（Path），但不会走进被跳过的目录。
    """
    for dirpath, dirnames, filenames in os.walk(REPO_ROOT):
        rel = Path(dirpath).relative_to(REPO_ROOT).as_posix()
        rel = "" if rel == "." else rel
        keep = []
        for name in dirnames:
            if name in SKIP_DIR_NAMES:
                continue
            child = f"{rel}/{name}" if rel else name
            if child in SKIP_REL_PATHS:
                continue
            keep.append(name)
        dirnames[:] = keep
        for name in filenames:
            yield Path(dirpath) / name


def pruned_tracked_violations(tracked):
    """哨兵（纯函数）：被剪掉的目录里若躺着**被 git 跟踪的文件**，报出来。

    没有这条哨兵，「剪枝」就是一个静默的口子：谁建一个叫 `build/` 的源码目录，
    代码就悄悄不进检查圈了。git 跟踪文件里一条都不该落在被剪目录下。
    """
    violations = []
    for path in tracked:
        parts = path.replace("\\", "/").split("/")
        for i, part in enumerate(parts[:-1]):
            if part in SKIP_DIR_NAMES:
                violations.append(f"{path}（落在被剪目录 {part}/）")
                break
            prefix = "/".join(parts[: i + 1])
            if prefix in SKIP_REL_PATHS:
                violations.append(f"{path}（落在被剪目录 {prefix}/）")
                break
    return violations


def check_pruned_tracked():
    """跑哨兵：返回 (违规列表, 说明)。git 不可用时跳过（不判红，但要说一句）。"""
    try:
        proc = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "ls-files", "-z"],
            capture_output=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return [], f"哨兵跳过（git 不可用：{exc.__class__.__name__}）"
    tracked = [p for p in proc.stdout.decode("utf-8", errors="replace").split("\0") if p]
    violations = pruned_tracked_violations(tracked)
    note = f"哨兵查了 {len(tracked)} 个被跟踪文件，被剪目录下 0 个"
    if violations:
        note = f"哨兵查了 {len(tracked)} 个被跟踪文件，其中 {len(violations)} 个落在被剪目录下"
    return violations, note


def section(title: str) -> None:
    print(f"\n── {title} " + "─" * max(0, 60 - len(title)))


def check_compile() -> list[str]:
    bad: list[str] = []
    for path in sorted(p for p in iter_repo_files() if p.suffix == ".py"):
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


def rule_prefilter(rule: dict) -> list[str]:
    """从规则的 pattern 里**派生**必要条件预筛（不手抄第二份真相）。

    预筛是一组字面子串：一行只有至少含其中一个 token，才可能是违规行。
    引擎据此先做一次廉价的 `in` 判断，再跑那条昂贵的正则 —— L-002 那条 426 字符的
    正则在 45k 行上要 2.4 秒，预筛后只要 0.13 秒。

    **token 从哪儿来**：从 pattern 里的**命名组**派生 —— 规则把「作为必要条件的字面量」
    放进命名组（如 `(?P<dirs>claude|config|...)`），引擎取各组的候选字面量做 token。
    这样 **pattern 与预筛是一份真相**：改了 pattern 里的字面量集合，token 跟着变；
    不存在「改了 pattern 忘了同步预筛 → 静默漏报」这条通路。

    **规则表里不要再写 `prefilter` 字段** —— 那是手抄的第二份真相，正是这条通路的来源，
    所以这里直接判它非法（报错，不静默忽略）。

    **它是必要条件，不是等价条件**：凡是能命中 pattern 的行，一定至少含有一个 token。
    「改 pattern 加了新分支、字面量却没放进命名组」这一种残余漏报，引擎层看不出来 ——
    由 `tests/test_gates.py` 的已知违规样本护栏兜着（见 KNOWN_VIOLATIONS）。
    """
    if "prefilter" in rule:
        raise ValueError(
            "规则表里不要再写 prefilter —— 预筛由 pattern 的命名组自动派生（手抄两份会 desync）"
        )
    pattern = rule.get("pattern")
    if not isinstance(pattern, str):
        return []
    tokens: list[str] = []
    for match in re.finditer(r"\(\?P<(\w+)>([^)]*)\)", pattern):
        for alt in match.group(2).split("|"):
            alt = alt.strip()
            if alt and alt not in tokens:
                tokens.append(alt)
    return tokens


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
        try:
            prefilter = rule_prefilter(rule)
        except ValueError as exc:
            violations.append(f"{rule.get('id')} 的 prefilter 写坏了：{exc}")
            continue
        files = rule.get("scope") or ["**/*.py"]
        # sorted：遍历顺序改成确定序（os.walk 是文件系统序，跨机器不一致），
        # 违规列表的顺序才可复现。1 千来个 Path 的排序开销可以忽略。
        for path in sorted(iter_repo_files()):
            if not path.is_file():
                continue
            rel = str(path.relative_to(REPO_ROOT)).replace("\\", "/")
            if not any(fnmatch.fnmatch(rel, glob) or fnmatch.fnmatch(rel, glob.lstrip("*/")) for glob in files):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for lineno, line in enumerate(text.splitlines(), 1):
                # 预筛：不含任何 token 的行不可能违规，先用廉价的 in 挡掉，
                # 再跑那条昂贵的正则。
                if prefilter and not any(token in line for token in prefilter):
                    continue
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

    section("剪枝哨兵")
    # 「没报」不等于「查过了」——把跳过了什么说清楚，别让人误以为扫全了。
    skipped = [p for p in sorted(SKIP_REL_PATHS) if (REPO_ROOT / p).exists()]
    skipped += [d for d in sorted(SKIP_DIR_NAMES) if (REPO_ROOT / d).exists()]
    if skipped:
        print("已跳过（不检查）：" + "、".join(skipped))
    else:
        print("没有需要跳过的目录")
    pruned_bad, note = check_pruned_tracked()
    if pruned_bad:
        print("\n".join(f"✗ {item}" for item in pruned_bad))
        print("  被剪掉的目录里不该有被 git 跟踪的文件 —— 换个目录名，或把它移出剪枝清单。")
    else:
        print("✓ " + note)
    failures += len(pruned_bad)

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
