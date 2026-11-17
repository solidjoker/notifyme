"""发布门：一个提交要打成正式版，三件事缺一不可。

原文那句留着当尺子：

    被打版本的提交得有一次跑完的绿色测试，16 个安装包一个不少，标成 Latest，
    才算发布了一次。

少一样就不发。没配的东西一律记成 NOT RUN，不许当没这回事。

用法：
    python -m gates.release_check --tag v20261117.1
    python -m gates.release_check --tag v1 --skip-tests --json
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent


def load_release_cfg() -> dict[str, Any]:
    import tomllib

    with open(REPO_ROOT / "config" / "workflow.toml", "rb") as fh:
        return tomllib.load(fh).get("release", {})


def check_tests(command: list[str] | None, skip: bool) -> dict[str, str]:
    if skip:
        return {"check": "全量测试", "result": "SKIP", "detail": "跳过了"}
    cmd = command or [sys.executable, "-m", "gates.selfcheck"]
    proc = subprocess.run(
        cmd, cwd=str(REPO_ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if proc.returncode == 0:
        return {"check": "全量测试", "result": "PASS", "detail": " ".join(cmd)}
    tail = (proc.stdout or proc.stderr or "").strip().splitlines()[-3:]
    return {"check": "全量测试", "result": "FAIL", "detail": " / ".join(tail)[:200]}


def check_installers(cfg: dict[str, Any]) -> dict[str, str]:
    expected = int(cfg.get("installer_count", 0) or 0)
    directory = cfg.get("installers_dir")
    glob = cfg.get("installer_glob", "*")
    if not directory:
        return {
            "check": f"安装包 {expected} 个",
            "result": "NOT RUN",
            "detail": "没配 installers_dir，按规矩不能算打齐了",
        }
    path = REPO_ROOT / directory
    if not path.is_dir():
        return {"check": f"安装包 {expected} 个", "result": "NOT RUN", "detail": f"{directory} 不存在"}
    found = [p for p in path.glob(glob) if p.is_file()]
    if len(found) != expected:
        return {
            "check": f"安装包 {expected} 个",
            "result": "FAIL",
            "detail": f"只有 {len(found)} 个：{', '.join(p.name for p in found[:6])}",
        }
    return {"check": f"安装包 {expected} 个", "result": "PASS", "detail": f"{len(found)} 个齐了"}


def check_latest(cfg: dict[str, Any], tag: str, latest_file: str | None) -> dict[str, str]:
    if not cfg.get("mark_latest", True):
        return {"check": "标成 Latest", "result": "NOT RUN", "detail": "配置里没要求"}
    target = REPO_ROOT / (latest_file or cfg.get("latest_file", "dist/LATEST"))
    if not target.exists():
        return {"check": "标成 Latest", "result": "NOT RUN", "detail": f"{target.name} 不存在"}
    content = target.read_text(encoding="utf-8").strip()
    if content != tag:
        return {"check": "标成 Latest", "result": "FAIL", "detail": f"{target.name} 里是 {content!r}，不是 {tag!r}"}
    return {"check": "标成 Latest", "result": "PASS", "detail": tag}


def evaluate(tag: str, cfg: dict[str, Any], skip_tests: bool = False, latest_file: str | None = None) -> dict[str, Any]:
    items = [
        check_tests(cfg.get("test_command"), skip_tests),
        check_installers(cfg),
        check_latest(cfg, tag, latest_file),
    ]
    blocking = [item for item in items if item["result"] != "PASS"]
    return {"tag": tag, "verdict": "release" if not blocking else "refuse", "checks": items}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="发布门")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--latest-file")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    result = evaluate(args.tag, load_release_cfg(), args.skip_tests, args.latest_file)

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"发布 {args.tag}")
        for item in result["checks"]:
            print(f"  [{item['result']:7s}] {item['check']} — {item['detail']}")
        if result["verdict"] == "release":
            print("\n✓ 三条都齐了，可以发")
        else:
            print("\n✗ 不齐，不发。缺的那几条上面列着。")

    return 0 if result["verdict"] == "release" else 1


if __name__ == "__main__":
    raise SystemExit(main())
