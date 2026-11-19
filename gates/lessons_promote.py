"""错题本的分级晋升。

一条教训三级台阶：

    记下来（LESSONS.md）
      └─ Seen 里有 3 个不同日期 → 挪进 docs/CODE_STANDARDS.md
           └─ 还标着 Machine-checkable: true → 再落一条 CI 规则

升级必须由这个脚本做，别手抄——手抄会漏掉 Seen 计数，也会把同一条教训记两遍。

用法：
    python -m gates.lessons_promote --check                    # 只看谁够格
    python -m gates.lessons_promote --apply                    # 真的晋升
    python -m gates.lessons_promote --add --rule "..." --why "..." --evidence "abc1234, #42"
    python -m gates.lessons_promote --seen L-001               # 今天又见了一次
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
LESSONS = REPO_ROOT / "LESSONS.md"
STANDARDS = REPO_ROOT / "docs" / "CODE_STANDARDS.md"
CI_RULES = REPO_ROOT / "config" / "ci_rules.json"

ZONE_START = "<!-- promoted-zone:start -->"
ZONE_END = "<!-- promoted-zone:end -->"
PROMOTE_AFTER = 3

ENTRY_RE = re.compile(r"^## (L-\d{3})\s+(.+?)\s*$")
FIELD_RE = re.compile(r"^-\s+([A-Za-z_-]+):\s*(.*)$")
DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")


# --------------------------------------------------------------------------- 解析


class Lesson:
    def __init__(self, lid: str, title: str, fields: dict[str, str], block: str) -> None:
        self.id = lid
        self.title = title
        self.fields = fields
        self.block = block

    @property
    def rule(self) -> str:
        return self.fields.get("rule", "")

    @property
    def why(self) -> str:
        return self.fields.get("why", "")

    @property
    def evidence(self) -> str:
        return self.fields.get("evidence", "")

    @property
    def seen(self) -> list[str]:
        return sorted(set(DATE_RE.findall(self.fields.get("seen", ""))))

    @property
    def machine_checkable(self) -> bool:
        return self.fields.get("machine-checkable", "false").strip().lower() in {"true", "yes", "1"}

    def __repr__(self) -> str:
        return f"<{self.id} seen={len(self.seen)} machine={self.machine_checkable}>"


def split_document(text: str) -> tuple[str, list[Lesson], str]:
    """拆成 (表头, 条目列表, 尾部非条目内容)。

    代码块里的示例条目不算数——LESSONS.md 开头就有一段格式示例，
    要是把它也当成真教训，第一次晋升就会把模板挪进规范。
    """
    lines = text.splitlines(keepends=True)
    starts: list[int] = []
    in_fence = False
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence and ENTRY_RE.match(line.rstrip()):
            starts.append(idx)

    if not starts:
        return text, [], ""

    head = "".join(lines[: starts[0]])
    lessons: list[Lesson] = []
    for idx, start in enumerate(starts):
        end = starts[idx + 1] if idx + 1 < len(starts) else len(lines)
        block = "".join(lines[start:end])
        match = ENTRY_RE.match(lines[start].rstrip())
        assert match
        fields: dict[str, str] = {}
        for line in block.splitlines()[1:]:
            field_match = FIELD_RE.match(line.strip())
            if field_match:
                fields[field_match.group(1).strip().lower()] = field_match.group(2).strip()
        lessons.append(Lesson(match.group(1), match.group(2), fields, block.rstrip() + "\n"))
    return head, lessons, ""


def load_lessons(path: Path | None = None) -> tuple[str, list[Lesson]]:
    """读错题本。

    path 缺省时在**调用时**取模块全局 LESSONS，而不是在函数定义时把默认值
    绑死 —— 绑死的话，调用方临时换掉 lp.LESSONS 也改不动这里（单测要往临时
    目录里写，绑死了就会写到真仓库的 LESSONS.md 上）。
    """
    path = path or LESSONS
    text = path.read_text(encoding="utf-8")
    head, lessons, _ = split_document(text)
    return head, lessons


def render(lessons: list[Lesson]) -> str:
    return "\n".join(lesson.block.rstrip() + "\n" for lesson in lessons)


# --------------------------------------------------------------------------- 判定


def ready(lesson: Lesson) -> bool:
    return len(lesson.seen) >= PROMOTE_AFTER and bool(lesson.rule)


def plan(lessons: list[Lesson]) -> list[Lesson]:
    return [lesson for lesson in lessons if ready(lesson)]


# --------------------------------------------------------------------------- 写入


def promote_block(lesson: Lesson, date: str, ci_ready: bool = False) -> str:
    """生成一条晋升块。

    ci_ready：这条教训对应的 CI 规则**是不是已经实现了**（enabled 为真且
    pattern 非空）。查表在调用方做（cmd_apply 读 config/ci_rules.json 后传
    进来）—— 本函数保持纯函数、不读盘；查不到表时传 False（=「待补正则」）。
    """
    lines = [
        f"<!-- promoted:{lesson.id} -->",
        f"### {lesson.id} {lesson.title}",
        "",
        f"- Rule: {lesson.rule}",
        f"- Why: {lesson.why}",
        f"- Evidence: {lesson.evidence}",
        f"- Seen: {', '.join(lesson.seen)}",
        f"- Promoted: {date}（第 {len(lesson.seen)} 次被抓到）",
    ]
    if lesson.machine_checkable:
        if ci_ready:
            lines.append(f"- CI: config/ci_rules.json#{lesson.id}（已实现：enabled + pattern 齐，机器会查）")
        else:
            lines.append(f"- CI: config/ci_rules.json#{lesson.id}（尚未实现，待补正则）")
    lines.append("")
    return "\n".join(lines)


def insert_into_zone(standards_text: str, blocks: list[str]) -> str:
    if ZONE_END not in standards_text:
        raise SystemExit(f"{STANDARDS} 里找不到 {ZONE_END}，晋升区被改坏了，先修好再跑")
    existing = standards_text.split(ZONE_START, 1)[-1].split(ZONE_END, 1)[0]
    new = existing.rstrip()
    if new:
        new += "\n\n"
    new += "\n".join(blocks)
    head, _, tail = standards_text.partition(ZONE_START)
    _, _, rest = tail.partition(ZONE_END)
    return f"{head}{ZONE_START}\n{new.rstrip()}\n{ZONE_END}{rest}"


def load_ci_rules(path: Path | None = None) -> dict[str, Any]:
    """读 CI 规则表；path 缺省时同样在调用时取模块全局 CI_RULES。"""
    path = path or CI_RULES
    if not path.exists():
        return {"version": 1, "_comment": "由 LESSONS 自动晋升的机器检查。pattern 为空 = 待人工实现。", "rules": []}
    return json.loads(path.read_text(encoding="utf-8"))


def rule_is_real(rule: dict[str, Any]) -> bool:
    """一条 CI 规则算不算「真规则」：enabled 为真且 pattern 非空。

    口径与 gates/selfcheck.check_ci_rules 一致 —— 那边把 enabled=false 或
    pattern 为空的算成「还是桩」。
    """
    pattern = rule.get("pattern")
    return bool(rule.get("enabled")) and bool(pattern and str(pattern).strip())


def ci_rule_for(lesson_id: str, path: Path | None = None) -> dict[str, Any] | None:
    """按 id 在 CI 规则表里找规则；表读不出来 / 没有这条 → None（不抛）。"""
    try:
        data = load_ci_rules(path)
    except (OSError, ValueError):
        return None
    for rule in data.get("rules") or []:
        if isinstance(rule, dict) and rule.get("id") == lesson_id:
            return rule
    return None


def ci_rule_ready(lesson_id: str, path: Path | None = None) -> bool:
    """该 id 的规则是不是已经「真规则」（enabled + pattern 齐）。

    读不到规则表（文件缺失 / JSON 坏了）时当「不是真规则」—— 查表失败
    不该把晋升搞挂。
    """
    rule = ci_rule_for(lesson_id, path)
    return rule is not None and rule_is_real(rule)


def append_ci_stub(lesson: Lesson) -> bool:
    data = load_ci_rules(CI_RULES)
    ids = {rule.get("id") for rule in data.get("rules", [])}
    if lesson.id in ids:
        return False
    data.setdefault("rules", []).append(
        {
            "id": lesson.id,
            "title": lesson.title,
            "why": lesson.why,
            "evidence": lesson.evidence,
            "enabled": False,
            "pattern": None,
            "scope": [],
            "note": "从 LESSONS 晋升。补上 pattern / scope 并把 enabled 改成 true，CI 才会真的查。",
        }
    )
    CI_RULES.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


# --------------------------------------------------------------------------- 命令


def cmd_check(lessons: list[Lesson], verbose: bool = True) -> int:
    pending = plan(lessons)
    if verbose:
        print(f"共 {len(lessons)} 条教训，Seen 门槛 = {PROMOTE_AFTER} 个不同日期\n")
        for lesson in lessons:
            mark = "→ 该晋升" if ready(lesson) else "  "
            print(f"  {mark}  {lesson.id}  seen={len(lesson.seen)}  machine={str(lesson.machine_checkable):5s}  {lesson.title}")
        print()
        if pending:
            print(f"{len(pending)} 条够格：{', '.join(l.id for l in pending)}")
        else:
            print("暂时没有够格的。继续记。")
    return len(pending)


def cmd_apply(lessons: list[Lesson], date: str, dry_run: bool = False) -> int:
    pending = plan(lessons)
    if not pending:
        print("没有够格晋升的教训。")
        return 0

    standards_text = STANDARDS.read_text(encoding="utf-8")
    blocks: list[str] = []
    for lesson in pending:
        if f"<!-- promoted:{lesson.id} -->" in standards_text:
            print(f"  {lesson.id} 已经在规范里了，跳过")
            continue
        if lesson.machine_checkable:
            if dry_run:
                if ci_rule_for(lesson.id) is None:
                    print(f"  {lesson.id} → 会写一条 CI 规则桩（待补 pattern）")
                else:
                    print(f"  {lesson.id} → CI 规则已存在，不覆盖")
            else:
                added = append_ci_stub(lesson)
                if added:
                    print(f"  {lesson.id} → CI 规则桩已加")
                else:
                    print(f"  {lesson.id} → CI 规则已存在，不覆盖")
        # 晋升块要如实反映规则表的真实状态：查表说「真规则」才写「已实现」；
        # 查不到 / 读表失败一律当「待补正则」。
        blocks.append(promote_block(lesson, date, ci_ready=ci_rule_ready(lesson.id)))

    if not blocks:
        return 0

    if dry_run:
        # 目标可能不在仓库里（单测指向临时目录）—— relative_to 会抛 ValueError。
        try:
            target = STANDARDS.relative_to(REPO_ROOT)
        except ValueError:
            target = STANDARDS
        print(f"\n[dry-run] 会把 {len(blocks)} 条挪进 {target}：")
        for block in blocks:
            print(block)
        return len(blocks)

    STANDARDS.write_text(insert_into_zone(standards_text, blocks), encoding="utf-8")
    remaining = [lesson for lesson in lessons if lesson not in pending]
    head, _, _ = split_document(LESSONS.read_text(encoding="utf-8"))
    LESSONS.write_text(head + render(remaining) if remaining else head, encoding="utf-8")

    print(f"\n晋升 {len(blocks)} 条，LESSONS 剩 {len(remaining)} 条。")
    print("记得跑一遍 python -m gates.selfcheck。")
    return len(blocks)


def cmd_add(rule: str, why: str, evidence: str, machine: bool, date: str) -> str:
    head, lessons = load_lessons()
    used = {int(lesson.id.split("-")[1]) for lesson in lessons}
    next_id = max(used, default=0) + 1
    lid = f"L-{next_id:03d}"
    block = (
        f"## {lid} {rule[:40]}\n\n"
        f"- Rule: {rule}\n"
        f"- Why: {why}\n"
        f"- Evidence: {evidence}\n"
        f"- Seen: {date}\n"
        f"- Machine-checkable: {'true' if machine else 'false'}\n"
    )
    text = LESSONS.read_text(encoding="utf-8").rstrip() + "\n\n" + block
    LESSONS.write_text(text, encoding="utf-8")
    print(f"记下 {lid}：{rule}")
    return lid


def cmd_seen(lesson_id: str, date: str, dry_run: bool = False) -> None:
    head, lessons = load_lessons()
    target = next((lesson for lesson in lessons if lesson.id == lesson_id), None)
    if target is None:
        raise SystemExit(f"LESSONS.md 里没有 {lesson_id}")

    dates = target.seen
    if date in dates:
        print(f"{lesson_id} 今天已经记过了（{date}），不重复计")
        return
    dates = sorted(dates + [date])

    if dry_run:
        print(f"[dry-run] {lesson_id} Seen → {', '.join(dates)}")
        return

    target.block = re.sub(
        r"^- Seen:.*$", f"- Seen: {', '.join(dates)}", target.block, count=1, flags=re.MULTILINE
    )
    LESSONS.write_text(head + render(lessons), encoding="utf-8")
    print(f"{lesson_id} Seen → {', '.join(dates)}")
    if len(dates) >= PROMOTE_AFTER:
        print(f"  够 {PROMOTE_AFTER} 天了，下次 --apply 会把它挪进规范。")


def main(argv: list[str] | None = None) -> int:
    from datetime import date as date_cls

    parser = argparse.ArgumentParser(description="错题本分级晋升")
    parser.add_argument("--check", action="store_true", help="列出谁够格")
    parser.add_argument("--apply", action="store_true", help="执行晋升")
    parser.add_argument("--add", action="store_true", help="记一条新教训")
    parser.add_argument("--seen", metavar="L-001", help="给某条教训加一个 Seen 日期")
    parser.add_argument("--rule")
    parser.add_argument("--why")
    parser.add_argument("--evidence")
    parser.add_argument("--machine-checkable", action="store_true")
    parser.add_argument("--date", default=date_cls.today().isoformat())
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    if args.add:
        if not (args.rule and args.why and args.evidence):
            parser.error("--add 需要 --rule / --why / --evidence")
        cmd_add(args.rule, args.why, args.evidence, args.machine_checkable, args.date)
        return 0
    if args.seen:
        cmd_seen(args.seen, args.date, args.dry_run)
        return 0

    _, lessons = load_lessons()
    if args.apply:
        return 0 if cmd_apply(lessons, args.date, args.dry_run) >= 0 else 1
    cmd_check(lessons)
    return 0 if not plan(lessons) else 1


if __name__ == "__main__":
    raise SystemExit(main())
