"""loop1 —— 守 GitHub 和 Discord。

从“看到反馈”一路做到“发版 + 回复”，中间不交给别人。

顺序按原文：新 issue 先判断值不值得做，值得就修，修完发版、回复；
不值得做的也要回一句。PR 逐行看，拉到本地真跑一遍，没问题才合。
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

from gates.hazard_check import check as hazard_check
from gates.hazard_check import load_rules, parse_diff
from loops import common as C
from loops import sources
from loops.pipeline import Item, Outcome, Pipeline


def run_once(tick: C.Tick) -> list[Outcome]:
    cfg = tick.cfg
    journal = tick.journal
    outcomes: list[Outcome] = []

    pipe = Pipeline(
        tick,
        cheap_gate=bool(C.cfg_get(cfg, "loop1.cheap_gate", False)),
        reply=_reply,
        allow_release=bool(C.cfg_get(cfg, "loop1.release", False)),
    )

    if "github_issue" in (C.cfg_get(cfg, "loop1.sources", []) or []):
        max_items = int(C.cfg_get(cfg, "loop1.max_items_per_tick", 5))
        for item in sources.github_issues(limit=40)[:max_items]:
            if tick.state.seen(tick.name, item.id):
                continue
            if not tick.budget_left():
                break
            try:
                outcomes.append(pipe.handle(item))
            except C.LLMError as exc:
                journal.write("llm_error", id=item.id, error=str(exc)[:200])
                break

    if "github_pr" in (C.cfg_get(cfg, "loop1.sources", []) or []):
        for item in sources.github_pulls(limit=20):
            if tick.state.seen(tick.name, item.id):
                continue
            outcomes.append(_handle_pr(tick, pipe, item))

    if "discord" in (C.cfg_get(cfg, "loop1.sources", []) or []):
        inbox = C.cfg_get(cfg, "loop1.discord_inbox", ".loopstate/inbox/discord.jsonl")
        max_items = int(C.cfg_get(cfg, "loop1.max_items_per_tick", 5))
        for item in sources.read_inbox(inbox)[:max_items]:
            if tick.state.seen(tick.name, item.id):
                continue
            if not tick.budget_left():
                break
            try:
                outcomes.append(pipe.handle(item))
            except C.LLMError as exc:
                journal.write("llm_error", id=item.id, error=str(exc)[:200])
                break

    tick.state.save(dry_run=tick.dry_run)
    return outcomes


# --------------------------------------------------------------------------- PR


def _handle_pr(tick: C.Tick, pipe: Pipeline, item: Item) -> Outcome:
    cfg, journal = tick.cfg, tick.journal
    number = int(item.raw.get("number", 0))
    journal.write("pr_in", id=item.id, number=number, author=item.author)

    if item.raw.get("is_draft"):
        tick.state.mark(tick.name, item.id, verdict="draft")
        return Outcome(item, "skipped", "草稿 PR")

    if item.author and any(entry.lower().endswith("/" + item.author.lower()) for entry in pipe.blocklist):
        _say(item, "这个账号在人工复核名单里，PR 需要维护者先看过。")
        _label(number, ["needs-human"])
        tick.state.mark(tick.name, item.id, verdict="blocklisted")
        return Outcome(item, "blocked", "作者在人工复核名单")

    diff = sources.pr_diff(number)
    if not diff.strip():
        tick.state.mark(tick.name, item.id, verdict="empty-diff")
        return Outcome(item, "skipped", "拿不到 diff")

    # ── 门：危害清单，先查再花贵模型的钱 ──
    diff_files, _ = parse_diff(diff)
    rules = load_rules(C.REPO_ROOT / C.cfg_get(cfg, "gate.hazard_policy", "config/hazard_rules.json"))
    findings = hazard_check([f["path"] for f in diff_files], diff_files, rules, item.author, pipe.blocklist)
    if findings:
        journal.write("pr_hazard_block", id=item.id, findings=findings)
        body = "这个 PR 碰到了需要维护者先确认的范围：\n\n" + "\n".join(f"- {f['detail']}" for f in findings)
        body += "\n\n维护者放行之前，这份代码不会在有真实账号的机器上跑。"
        _say(item, body)
        _label(number, ["needs-human"])
        tick.state.mark(tick.name, item.id, verdict="hazard-blocked")
        return Outcome(item, "blocked", "危害清单：" + findings[0]["detail"])

    # ── 逐行看 ──
    try:
        review = pipe.strong.json(
            pipe.prompts.render(
                "pr_review",
                item_source=item.source,
                item_title=item.title,
                author=item.author,
                item_body=C.guard_untrusted(item.body, 3000),
                diff=diff[:30_000],
            ),
            default={"verdict": "request-changes", "reason": "审查没给出结论", "blocking": [], "unverified": []},
        )
    except C.LLMError as exc:
        journal.write("pr_review_error", id=item.id, error=str(exc)[:200])
        return Outcome(item, "failed", "审查模型不可用")

    journal.write(
        "pr_review",
        id=item.id,
        verdict=review.get("verdict"),
        blocking=len(review.get("blocking") or []),
        unverified=len(review.get("unverified") or []),
    )

    # ── 拉到本地真跑一遍 ──
    local_ok, local_note = _local_run(cfg, tick.dry_run, number)
    if not local_ok:
        body = _review_body(review, extra=local_note)
        _say(item, body)
        _label(number, ["needs-changes"])
        tick.state.mark(tick.name, item.id, verdict="changes-requested")
        return Outcome(item, "declined", local_note or "本地没跑过")

    if review.get("verdict") != "merge":
        _say(item, _review_body(review))
        _label(number, ["needs-changes"])
        tick.state.mark(tick.name, item.id, verdict="changes-requested")
        return Outcome(item, "declined", review.get("reason", ""))

    # ── 合 ──
    if not C.cfg_get(cfg, "loop1.merge_pr", False):
        _say(item, _review_body(review, extra="审过了，可以合；合并这一步等人点一下。"))
        _label(number, ["ready-to-merge"])
        tick.state.mark(tick.name, item.id, verdict="ready-to-merge")
        return Outcome(item, "declined", "审过，等人工合并")

    if tick.dry_run:
        journal.write("pr_merge_dry_run", id=item.id, number=number)
        tick.state.mark(tick.name, item.id, verdict="merge-dry-run")
        return Outcome(item, "shipped", "dry-run 合并")

    ok, out = sources.gh(["pr", "merge", str(number), "--squash", "--delete-branch"])
    journal.write("pr_merge", id=item.id, ok=ok, error=None if ok else out[:200])
    if not ok:
        return Outcome(item, "failed", f"合并失败：{out[:120]}")
    _say(item, "合了。谢谢。")
    tick.state.mark(tick.name, item.id, verdict="merged")
    return Outcome(item, "shipped", "已合并")


def _review_body(review: dict[str, Any], extra: str = "") -> str:
    lines = [review.get("note_to_author") or review.get("reason", "")]
    blocking = review.get("blocking") or []
    if blocking:
        lines.append("\n需要改：")
        lines += [f"- {item}" for item in blocking[:5]]
    unverified = review.get("unverified") or []
    if unverified:
        lines.append("\n需要真跑才能确认：")
        lines += [f"- {item}" for item in unverified[:5]]
    if extra:
        lines.append(f"\n{extra}")
    return "\n".join(lines).strip()


def _local_run(cfg: dict[str, Any], dry_run: bool, number: int) -> tuple[bool, str]:
    """原文那句话：拉到本地真跑一遍。没配命令就等于没跑，不许假装跑过。"""
    command = C.cfg_get(cfg, "loop1.local_run", [])
    if not command:
        return False, "本地验证命令没配（config 里的 [loop1].local_run），按规矩不能算跑过。"
    if isinstance(command, str):
        command = [command]
    resolved = [part.replace("{pr}", str(number)) for part in command]
    if dry_run:
        return True, f"[dry-run] 会跑：{' '.join(resolved)}"
    proc = subprocess.run(
        resolved, cwd=str(C.REPO_ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if proc.returncode != 0:
        tail = (proc.stdout or proc.stderr or "").strip().splitlines()[-6:]
        return False, "本地跑挂了：\n```\n" + "\n".join(tail) + "\n```"
    return True, "本地跑过，绿。"


def _label(number: int, labels: list[str]) -> None:
    sources.gh(["pr", "edit", str(number), "--add-label", ",".join(labels)])


def _reply(item: Item, text: str) -> None:
    if item.kind == "issue":
        sources.comment_issue(int(item.raw.get("number", 0)), text)
    elif item.kind == "pr":
        sources.comment_pr(int(item.raw.get("number", 0)), text)
    else:
        sources.append_jsonl(".loopstate/outbox/discord.jsonl", {"reply_to": item.id, "body": text})
