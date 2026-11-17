"""统一入口。

    python -m loops.run loop1 --once               # 跑一轮
    python -m loops.run all --once                 # 三个各跑一轮
    python -m loops.run loop1 --interval 900       # 常驻
    python -m loops.run all --once --offline       # 不联网、不调模型，只为验证管道
    python -m loops.run status                     # 看状态和最近的日志

--dry-run 不改任何文件、不提交、不发版、不回复；--offline 不发网络请求。
两个都能开，跑起来最安全。
"""

from __future__ import annotations

import argparse
import sys

from loops import common as C
from loops import loop1_github, loop2_x, loop3_review
from loops.pipeline import Outcome

RUNNERS = {
    "loop1": loop1_github.run_once,
    "loop2": loop2_x.run_once,
    "loop3": loop3_review.run_once,
}

DESCRIPTION = {
    "loop1": "守 GitHub 和 Discord",
    "loop2": "守 X 的关键词流（先过便宜模型）",
    "loop3": "回头看前两个，写错题本",
}


def run_one(name: str, cfg: dict, dry_run: bool, offline: bool) -> list[Outcome]:
    if not C.cfg_get(cfg, f"{name}.enabled", True):
        print(f"[{name}] 配置里关着，跳过")
        return []
    tick = C.Tick.make(name, cfg, dry_run=dry_run, offline=offline)
    print(f"\n=== {name} · {DESCRIPTION[name]} · {C.stamp()} ===")
    outcomes = RUNNERS[name](tick)
    _summarize(name, outcomes, dry_run, offline)
    return outcomes


def _summarize(name: str, outcomes: list[Outcome], dry_run: bool, offline: bool) -> None:
    if not outcomes:
        print(f"[{name}] 这一轮没有要处理的")
        return
    print(f"\n[{name}] 处理 {len(outcomes)} 条：")
    for outcome in outcomes:
        bits = [outcome.status]
        if outcome.commit:
            bits.append(outcome.commit)
        if outcome.tag:
            bits.append(f"tag={outcome.tag}")
        print(f"  - {outcome.item.id:<18} {' / '.join(bits):<28} {outcome.detail[:60]}")
    if dry_run:
        print("  （dry-run：没有提交、没有发版、没有回复）")
    if offline:
        print("  （offline：模型和 agent 用的是固定应答，只为验证管道）")


def loop_forever(name: str, cfg: dict, dry_run: bool, offline: bool) -> None:
    interval = int(C.cfg_get(cfg, f"{name}.interval_sec", 900))
    print(f"[{name}] 常驻，每 {interval}s 一轮。Ctrl-C 退出。")
    while True:
        try:
            run_one(name, cfg, dry_run, offline)
        except Exception as exc:  # 一个循环炸了不许带走别的
            print(f"[{name}] 这一轮炸了：{type(exc).__name__}: {exc}", file=sys.stderr)
        C.sleep_until(interval)


def show_status(cfg: dict) -> int:
    state = C.State()
    print("状态文件：", state.path)
    print("\n处理过的反馈：")
    for loop, entries in sorted(state.data.get("seen", {}).items()):
        print(f"  {loop}: {len(entries)} 条")
    counters = state.data.get("counters", {})
    if counters:
        print("\n计数：")
        for key, value in sorted(counters.items()):
            print(f"  {key}: {value}")

    print("\n最近日志：")
    for loop in RUNNERS:
        journal = C.Journal(loop)
        rows = journal.recent(5)
        if not rows:
            continue
        print(f"  {loop}:")
        for row in rows:
            print(f"    {row['ts']}  {row['event']:<18} {row.get('id', '')}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="三个维护循环")
    parser.add_argument("target", choices=[*RUNNERS, "all", "status"])
    parser.add_argument("--once", action="store_true", help="只跑一轮")
    parser.add_argument("--interval", type=int, help="覆盖配置里的间隔（秒）")
    parser.add_argument("--dry-run", action="store_true", help="不写文件、不提交、不发版、不回复")
    parser.add_argument("--offline", action="store_true", help="不发网络请求，用固定应答验证管道")
    parser.add_argument("--ignore-window", action="store_true", help="忽略 loop3 的夜间时间窗")
    parser.add_argument("--config", help="配置文件路径")
    parser.add_argument(
        "--fixtures",
        nargs="?",
        const="tests/fixtures",
        help="用固定入口数据跑（演示 / 试跑用，不碰网络）",
    )
    args = parser.parse_args(argv)

    cfg = C.load_config(args.config)

    if args.fixtures:
        from loops import sources

        directory = C.REPO_ROOT / args.fixtures
        sources.use_fixtures(directory)
        cfg.setdefault("loop2", {})["inbox"] = str(directory / "x.jsonl")
        print(f"用固定入口数据：{directory}")

    if args.ignore_window:
        cfg.setdefault("loop3", {})["ignore_window"] = True

    if args.target == "status":
        return show_status(cfg)

    targets = list(RUNNERS) if args.target == "all" else [args.target]
    forever = bool(args.interval) and args.target != "all"

    if not forever:
        for name in targets:
            run_one(name, cfg, args.dry_run, args.offline)
        print()
        return 0

    cfg.setdefault(targets[0], {})["interval_sec"] = args.interval
    loop_forever(targets[0], cfg, args.dry_run, args.offline)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
