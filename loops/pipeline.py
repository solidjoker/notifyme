"""一条反馈从进来到交付的完整流水线。

三个循环共用这一条，差异只在三处：入口（谁把 item 递进来）、要不要预筛、
回复发到哪。骨架刻意做得一样——不一样的地方越多，规矩就越容易在某个循环里失守。

    预筛 → 分诊 → 做 → 验（三道门）→ 交付 → 记账
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from gates.hazard_check import check as hazard_check
from gates.hazard_check import load_rules, parse_diff, read_blocklist
from gates.acceptance_check import validate as validate_acceptance
from loops import common as C


@dataclass
class Item:
    """一条待处理的反馈。id 必须稳定，否则会重复回同一句话。"""

    id: str
    source: str
    title: str
    body: str
    author: str = ""
    url: str = ""
    kind: str = "issue"
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class Outcome:
    item: Item
    status: str  # shipped | declined | needs-info | skipped | failed | blocked
    detail: str = ""
    commit: str | None = None
    tag: str | None = None


Reply = Callable[[Item, str], None]


class Pipeline:
    def __init__(
        self,
        tick: C.Tick,
        *,
        cheap_gate: bool,
        reply: Reply | None = None,
        allow_release: bool = False,
    ) -> None:
        self.tick = tick
        self.cfg = tick.cfg
        self.journal = tick.journal
        self.cheap_gate = cheap_gate
        self.reply = reply
        self.allow_release = allow_release
        self.prompts = C.Prompts()
        self.cheap = C.LLM.from_cfg(self.cfg, "cheap", offline=tick.offline)
        self.strong = C.LLM.from_cfg(self.cfg, "strong", offline=tick.offline)
        self.blocklist = C.load_blocklist(self.cfg)
        self.hazard_rules = load_rules(C.REPO_ROOT / C.cfg_get(self.cfg, "gate.hazard_policy", "config/hazard_rules.json"))

    # ------------------------------------------------------------------ 主流程

    def handle(self, item: Item) -> Outcome:
        self.journal.write("item_in", id=item.id, source=item.source, title=item.title[:80])

        if self.cheap_gate and not self._relevant(item):
            self.tick.state.mark(self.tick.name, item.id, verdict="irrelevant")
            return Outcome(item, "skipped", "预筛判为不相关")

        verdict = self._triage(item)
        self.journal.write("triage", id=item.id, verdict=verdict.get("verdict"), reason=verdict.get("reason"))

        if verdict.get("verdict") != "fix":
            text = self._decline_text(item, verdict)
            self._say(item, text)
            self.tick.state.mark(self.tick.name, item.id, verdict=verdict.get("verdict"))
            return Outcome(item, verdict.get("verdict") or "declined", verdict.get("reason", ""))

        if not self.tick.budget_left():
            self.journal.write("defer", id=item.id, reason="本轮预算用完")
            return Outcome(item, "deferred", "留到下一轮")

        self.tick.spend()
        result = self._fix(item)
        if result.get("status") != "fixed":
            self.journal.write("fix_aborted", id=item.id, status=result.get("status"), note=result.get("note") or result.get("summary"))
            self._say(item, f"看到了，正在处理，但目前卡在「{result.get('status')}」：{result.get('summary') or result.get('note') or '需要更多信息'}")
            self.tick.state.mark(self.tick.name, item.id, verdict=result.get("status"))
            return Outcome(item, "needs-info", result.get("summary", ""))

        acceptance_path = Path(result.get("acceptance_path", ""))
        if not acceptance_path.is_absolute():
            acceptance_path = C.REPO_ROOT / acceptance_path
        record = self._load_acceptance(acceptance_path)
        if record is None:
            self.journal.write("gate_fail", id=item.id, gate="acceptance", why="没有验收记录")
            return Outcome(item, "failed", "agent 没写验收记录，按规矩不算做完")

        files = record.get("files") or ([] if self.tick.offline else C.working_files())
        record["files"] = files

        # ── 门 1：验收格式 ──
        problems, warnings = validate_acceptance(record)
        for warning in warnings:
            self.journal.write("gate_warn", id=item.id, gate="acceptance", detail=warning)
        if problems:
            self.journal.write("gate_fail", id=item.id, gate="acceptance", problems=problems)
            self._say(item, "修完了，但验收记录不过关：" + "；".join(problems[:3]))
            return Outcome(item, "failed", "验收记录不过关：" + problems[0])

        # ── 门 2：危害清单 ──
        diff = C.working_diff(files)
        diff_files, _ = parse_diff(diff)
        findings = hazard_check(files, diff_files, self.hazard_rules, item.author, self.blocklist)
        if findings:
            self.journal.write("gate_fail", id=item.id, gate="hazard", findings=findings)
            self._say(
                item,
                "这个改动碰到了需要人工确认的范围：" + "；".join(f["detail"] for f in findings[:3]) + "。等维护者看过再继续。",
            )
            return Outcome(item, "blocked", "危害清单：" + findings[0]["detail"])

        # ── 交付 ──
        commit = self._ship(item, record, files)
        if commit is None and not self.tick.dry_run:
            # agent 说修好了，但工作区没有可提交的改动。这不叫修好。
            self.journal.write("commit_failed", id=item.id, files=len(files))
            return Outcome(item, "failed", "没有可提交的改动，不能当成修好了")
        tag = self._release(commit, item) if self.allow_release else None
        self._say(item, self._done_text(record, commit, tag))
        self.tick.state.mark(self.tick.name, item.id, verdict="shipped", commit=commit, tag=tag)
        return Outcome(item, "shipped", record.get("change", ""), commit=commit, tag=tag)

    # ------------------------------------------------------------------ 预筛

    def _relevant(self, item: Item) -> bool:
        prompt = self.prompts.render(
            "classify",
            product=C.cfg_get(self.cfg, "repo.product", "magpie"),
            item_body=C.guard_untrusted(item.title + "\n" + item.body, 2000),
        )
        try:
            verdict = self.cheap.json(prompt, default={"relevant": False, "reason": "解析失败"})
        except C.LLMError as exc:
            # 便宜模型挂了不能挡路：宁可多花点钱交给贵模型，也不能漏反馈
            self.journal.write("cheap_gate_error", id=item.id, error=str(exc)[:120])
            return True
        self.journal.write("classify", id=item.id, relevant=verdict.get("relevant"), kind=verdict.get("kind"), reason=verdict.get("reason"))
        return bool(verdict.get("relevant"))

    # ------------------------------------------------------------------ 分诊

    def _triage(self, item: Item) -> dict[str, Any]:
        prompt = self.prompts.render(
            "triage",
            loop_name=self.tick.name,
            date=C.today(),
            item_source=item.source,
            item_title=item.title,
            item_body=C.guard_untrusted(item.body),
            lessons=C.read_text(C.REPO_ROOT / C.cfg_get(self.cfg, "gate.lessons", "LESSONS.md")),
        )
        return self.strong.json(prompt, default={"verdict": "needs-info", "reason": "分诊没给出结论"})

    # ------------------------------------------------------------------ 做

    def _fix(self, item: Item) -> dict[str, Any]:
        prompt = self.prompts.render(
            "fix",
            item_source=item.source,
            item_title=item.title,
            item_body=C.guard_untrusted(item.body),
            repro="- " + "\n- ".join(item.raw.get("repro") or ["（分诊没给复现步骤）"]),
            lessons=C.read_text(C.REPO_ROOT / C.cfg_get(self.cfg, "gate.lessons", "LESSONS.md")),
            code_standards=C.read_text(C.REPO_ROOT / C.cfg_get(self.cfg, "gate.standards", "docs/CODE_STANDARDS.md")),
            item_id=item.id,
        )
        self.journal.write("agent_start", id=item.id, model=C.cfg_get(self.cfg, "agent.command"))

        if self.tick.offline:
            # 离线演练：不调真 agent，用一份固定验收记录把后面的门跑通。
            # 这份记录只为了验证管道，不代表任何真实修复，日志里标 fixture。
            fixture = C.REPO_ROOT / "tests" / "fixtures" / "acceptance_ok.json"
            if not fixture.exists():
                return {"status": "cannot-reproduce", "summary": "离线模式且没有 fixture"}
            target = C.STATE_DIR / "acceptance" / f"{item.id}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            data = json.loads(fixture.read_text(encoding="utf-8"))
            data["source"] = f"{item.source}（离线演练）"
            data["files"] = []
            target.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            self.journal.write("agent_fixture", id=item.id, path=str(target))
            return {"status": "fixed", "summary": "离线演练", "acceptance_path": str(target)}

        run = C.run_agent(self.cfg, prompt, tag=item.id, dry_run=self.tick.dry_run)
        self.journal.write("agent_done", id=item.id, ok=run.get("ok"), code=run.get("code"), prompt_file=run.get("prompt_file"))
        if not run.get("ok"):
            return {"status": "needs-human", "summary": f"agent 退出码 {run.get('code')}：{(run.get('stderr') or '')[:200]}"}

        parsed = C.extract_json(run.get("stdout") or "")
        if parsed is None:
            return {"status": "needs-human", "summary": "agent 没有按格式返回 JSON"}
        return parsed

    # ------------------------------------------------------------------ 验收

    def _load_acceptance(self, path: Path) -> dict[str, Any] | None:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None

    # ------------------------------------------------------------------ 交付

    def _ship(self, item: Item, record: dict[str, Any], files: list[str]) -> str | None:
        message = "\n".join(
            [
                f"fix: {item.title.strip()[:66]}",
                "",
                f"来自 {item.source}",
                "",
                render_acceptance(record),
                "",
                f"Loop: {self.tick.name}",
                f"Item: {item.id}",
            ]
        )
        commit = C.create_commit(message, files, dry_run=self.tick.dry_run)
        self.journal.write("commit", id=item.id, sha=commit, files=len(files), dry_run=self.tick.dry_run)
        return commit

    def _release(self, commit: str | None, item: Item) -> str | None:
        """发布门先过，再谈打 tag。三条缺一条就不发。"""
        if not commit:
            return None
        from gates.release_check import evaluate

        tag = f"v{C.today().replace('-', '')}.{item.id}"[:40]
        gate = evaluate(tag, C.cfg_get(self.cfg, "release", {}) or {}, skip_tests=self.tick.dry_run)
        if gate["verdict"] != "release":
            self.journal.write("release_refused", id=item.id, tag=tag, checks=gate["checks"])
            return None

        command = C.cfg_get(self.cfg, "release.command", [])
        if isinstance(command, str):
            command = [command]
        resolved = [part.replace("{tag}", tag).replace("{commit}", commit or "") for part in command]
        self.journal.write("release", id=item.id, tag=tag, command=resolved, dry_run=self.tick.dry_run)
        if self.tick.dry_run:
            return tag
        import subprocess

        proc = subprocess.run(
            resolved, cwd=str(C.REPO_ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace"
        )
        if proc.returncode != 0:
            self.journal.write("release_fail", id=item.id, error=(proc.stderr or "")[:200])
            return None
        return tag

    # ------------------------------------------------------------------ 回话

    def _say(self, item: Item, text: str) -> None:
        if not text:
            return
        target = C.cfg_get(self.cfg, f"{self.tick.name}.reply", False)
        if self.reply is None:
            self.journal.write("reply_skipped", id=item.id, why="这个入口没有回复通道")
            return
        if not target:
            self.journal.write("reply_draft", id=item.id, text=text[:400])
            return
        if self.tick.dry_run:
            self.journal.write("reply_dry_run", id=item.id, text=text[:200])
            return
        self.reply(item, text)
        self.journal.write("reply_sent", id=item.id, chars=len(text))

    def _decline_text(self, item: Item, verdict: dict[str, Any]) -> str:
        reason = verdict.get("reason", "这个不在当前范围内")
        labels = verdict.get("labels") or []
        tail = f"（已标 {', '.join(labels)}）" if labels else ""
        return f"{reason}{tail}。如果判断有误，补充一下复现步骤，我们再看看。"

    def _done_text(self, record: dict[str, Any], commit: str | None, tag: str | None) -> str:
        lines = ["修好了。"]
        if commit:
            lines.append(f"提交：{commit}")
        if tag:
            lines.append(f"版本：{tag}")
        lines.append(f"改了什么：{record.get('change', '')}")
        verified = record.get("verified") or []
        if verified:
            where = "、".join(sorted({str(v.get("where", "")) for v in verified if isinstance(v, dict)}))
            lines.append(f"在哪验的：{where}")
        not_run = [n for n in (record.get("not_run") or []) if str(n.get("what", "")).lower() not in {"none", ""}]
        if not_run:
            lines.append("没验的：" + "、".join(str(n.get("what")) for n in not_run))
        return "\n".join(lines)


# --------------------------------------------------------------------------- 渲染


def render_acceptance(record: dict[str, Any]) -> str:
    """把结构化验收记录渲染成提交说明里的那段文本。机器一会儿还要把它解析回来。"""
    lines = ["Acceptance:", f"Source: {record.get('source', '')}", f"Cause: {record.get('cause', '')}", f"Change: {record.get('change', '')}"]
    lines.append("Verified:")
    for item in record.get("verified") or []:
        if isinstance(item, dict):
            lines.append(f"  - {item.get('where', '')} | {item.get('check', '')} | {item.get('result', '')}")
        else:
            lines.append(f"  - {item}")
    lines.append("Red/Green:")
    for item in record.get("red_green") or []:
        if isinstance(item, dict):
            note = f"（{item['note']}）" if item.get("note") else ""
            lines.append(f"  - {item.get('step', '')}: {item.get('result', '')}{note}")
        else:
            lines.append(f"  - {item}")
    lines.append("Not run:")
    not_run = record.get("not_run") or []
    if not not_run:
        lines.append("  - none")
    for item in not_run:
        if isinstance(item, dict):
            why = f"（{item['why']}）" if item.get("why") else ""
            lines.append(f"  - {item.get('what', '')}{why}")
        else:
            lines.append(f"  - {item}")
    return "\n".join(lines)
