"""loop3 —— 回头看。

它不接任何外部入口。提示词第一句就是“现在代码改动和 PR 合并完全是自动化的”，
因为它一旦开始接活、开始修东西，就会开始护自己的实现，审查就废了。

它只做四件事：
  1. 审前两个循环这一轮交出来的东西（验证够不够、有没有屎山、有没有夹带）
  2. 把新教训写进 LESSONS.md
  3. 今天又见到的老教训，给它记一个 Seen 日期（这是晋升的输入）
  4. 总交烂代码的人，写进 blocklist
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from gates import lessons_promote
from loops import common as C
from loops.pipeline import Outcome


def run_once(tick: C.Tick) -> list[Outcome]:
    cfg, journal = tick.cfg, tick.journal

    window = C.cfg_get(cfg, "loop3.nightly", "")
    if window and not C.cfg_get(cfg, "loop3.ignore_window", False) and not C.in_window(window):
        journal.write("outside_window", window=window)
        return []

    hours = int(C.cfg_get(cfg, "loop3.lookback_hours", 24))
    commits = _loop_commits(hours)
    journal.write("review_scope", commits=len(commits), hours=hours)
    if not commits:
        journal.write("nothing_to_review")
        return []

    limit = int(C.cfg_get(cfg, "loop3.max_audits_per_tick", 20))
    commits = commits[:limit]

    prompt = tick_prompts().render(
        "review",
        since=f"{hours} 小时前",
        git_log=_render_log(commits),
        acceptance_records=_render_acceptance(commits),
    )

    if tick.dry_run:
        journal.write("review_dry_run", prompt_chars=len(prompt))
        return []

    strong = C.LLM.from_cfg(cfg, "strong", offline=tick.offline)
    report = strong.json(
        prompt,
        default={"findings": [], "new_lessons": [], "seen_again": [], "blocklist": [], "notes": ["审查没给出结论"]},
    )

    _write_report(report, commits)
    outcomes = _apply(report, commits)
    tick.state.save(dry_run=tick.dry_run)
    return outcomes


def tick_prompts() -> C.Prompts:
    return C.Prompts()


# --------------------------------------------------------------------------- 取材


def _loop_commits(hours: int) -> list[dict[str, Any]]:
    out = C.git_out("log", f"--since={hours} hours ago", "--format=%H%x1f%s%x1f%b%x1e")
    commits: list[dict[str, Any]] = []
    for record in out.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        parts = record.split("\x1f")
        if len(parts) < 3:
            continue
        sha, subject, body = parts[0], parts[1], parts[2]
        if "Loop: loop1" not in body and "Loop: loop2" not in body:
            continue
        commits.append({"sha": sha, "short": sha[:7], "subject": subject, "body": body})
    return commits


def _render_log(commits: list[dict[str, Any]]) -> str:
    lines = []
    for commit in commits:
        lines.append(f"{commit['short']}  {commit['subject']}")
        for line in commit["body"].splitlines():
            if line.startswith(("Loop:", "Item:", "Source:", "Change:")):
                lines.append(f"        {line}")
    return "\n".join(lines)


def _render_acceptance(commits: list[dict[str, Any]]) -> str:
    blocks = []
    for commit in commits:
        if "Acceptance:" not in commit["body"]:
            blocks.append(f"{commit['short']}: 没有验收记录")
            continue
        block = commit["body"].split("Acceptance:", 1)[1]
        blocks.append(f"{commit['short']}:\nAcceptance:{block.strip()}")
    return "\n\n---\n\n".join(blocks) if blocks else "（没有验收记录）"


# --------------------------------------------------------------------------- 落地


def _write_report(report: dict[str, Any], commits: list[dict[str, Any]]) -> None:
    stamp = C.now().strftime("%Y-%m-%d-%H%M")
    path = C.STATE_DIR / "review" / f"{stamp}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"# loop3 复盘 {stamp}", "", f"审了 {len(commits)} 个提交", ""]
    findings = report.get("findings") or []
    if findings:
        lines.append("## 发现")
        for f in findings:
            lines.append(f"- **{f.get('severity', '?')}** / {f.get('kind', '?')} / `{f.get('commit', '')}` — {f.get('detail', '')}")
    else:
        lines.append("## 发现\n\n这一轮没挑出毛病。")
    notes = report.get("notes") or []
    if notes:
        lines += ["", "## 留给维护者", ""] + [f"- {n}" for n in notes]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _apply(report: dict[str, Any], commits: list[dict[str, Any]]) -> list[Outcome]:
    from loops.pipeline import Item

    outcomes: list[Outcome] = []
    date = C.today()

    # 1. 新教训
    for lesson in report.get("new_lessons") or []:
        rule = (lesson.get("rule") or "").strip()
        why = (lesson.get("why") or "").strip()
        evidence = (lesson.get("evidence") or "").strip()
        if not (rule and why and evidence):
            continue
        lid = lessons_promote.cmd_add(rule, why, evidence, bool(lesson.get("machine_checkable")), date)
        outcomes.append(Outcome(Item(id=lid, source="loop3", title=rule[:40], body=""), "shipped", f"新教训 {lid}"))

    # 2. 老教训又见了一次
    for lid in report.get("seen_again") or []:
        try:
            lessons_promote.cmd_seen(str(lid), date)
        except SystemExit:
            continue

    # 3. 拉黑
    for handle in report.get("blocklist") or []:
        _block(handle, date, report)

    return outcomes


def _block(handle: str, date: str, report: dict[str, Any]) -> None:
    path = C.REPO_ROOT / "config" / "blocklist.txt"
    reason = "loop3 判定验证不足或夹带无关改动"
    entry = f"github/{handle}"
    text = path.read_text(encoding="utf-8")
    if entry in text:
        return
    path.write_text(text.rstrip() + f"\n{entry:<28}# {reason} ({date})\n", encoding="utf-8")
