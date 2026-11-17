"""入口适配器：把每个入口的原始数据，变成统一的 Item。

三个循环的差异几乎全在这一层。换入口只改这里，流水线不动。
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from loops import common as C
from loops.pipeline import Item


# --------------------------------------------------------------------------- GitHub

FIXTURES: Path | None = None


def use_fixtures(directory: str | Path | None) -> None:
    """演示 / 测试用：把入口换成固定数据，不碰网络。

    只影响“拉取”，不影响后面的分诊、修、验——管道本身还是真的。
    """
    global FIXTURES
    FIXTURES = Path(directory) if directory else None


def _fixture(name: str) -> list[dict[str, Any]]:
    if FIXTURES is None:
        return []
    path = FIXTURES / name
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def gh(args: list[str], timeout: int = 60) -> tuple[bool, str]:
    try:
        proc = subprocess.run(
            ["gh", *args],
            cwd=str(C.REPO_ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except FileNotFoundError:
        return False, "gh 没装（https://cli.github.com）"
    except subprocess.TimeoutExpired:
        return False, "gh 超时"
    if proc.returncode != 0:
        return False, (proc.stderr or proc.stdout or "").strip()[:400]
    return True, proc.stdout


def github_issues(limit: int = 30) -> list[Item]:
    if FIXTURES is not None:
        rows = _fixture("issues.json")[:limit]
    else:
        ok, out = gh(
            [
                "issue",
                "list",
                "--state",
                "open",
                "--limit",
                str(limit),
                "--json",
                "number,title,body,author,createdAt,url,labels",
            ]
        )
        if not ok:
            C.eprint(f"  拉 issue 失败：{out}")
            return []
        rows = json.loads(out or "[]")
    return [
        Item(
            id=f"gh-issue-{row['number']}",
            source=f"github issue #{row['number']} ({(row.get('author') or {}).get('login', '?')})",
            title=row.get("title") or "",
            body=row.get("body") or "",
            author=(row.get("author") or {}).get("login", ""),
            url=row.get("url", ""),
            kind="issue",
            raw={"number": row["number"], "labels": [l["name"] for l in row.get("labels") or []]},
        )
        for row in rows
    ]


def github_pulls(limit: int = 20) -> list[Item]:
    if FIXTURES is not None:
        rows = _fixture("pulls.json")[:limit]
    else:
        ok, out = gh(
            [
                "pr",
                "list",
                "--state",
                "open",
                "--limit",
                str(limit),
                "--json",
                "number,title,body,author,createdAt,url,isDraft,files",
            ]
        )
        if not ok:
            C.eprint(f"  拉 PR 失败：{out}")
            return []
        rows = json.loads(out or "[]")
    return [
        Item(
            id=f"gh-pr-{row['number']}",
            source=f"github PR #{row['number']} ({(row.get('author') or {}).get('login', '?')})",
            title=row.get("title") or "",
            body=row.get("body") or "",
            author=(row.get("author") or {}).get("login", ""),
            url=row.get("url", ""),
            kind="pr",
            raw={"number": row["number"], "is_draft": row.get("isDraft", False)},
        )
        for row in rows
    ]


def pr_diff(number: int) -> str:
    ok, out = gh(["pr", "diff", str(number)])
    return out if ok else ""


def comment_issue(number: int, text: str) -> bool:
    ok, out = gh(["issue", "comment", str(number), "--body", text])
    if not ok:
        C.eprint(f"  回 issue #{number} 失败：{out}")
    return ok


def comment_pr(number: int, text: str) -> bool:
    ok, out = gh(["pr", "comment", str(number), "--body", text])
    if not ok:
        C.eprint(f"  回 PR #{number} 失败：{out}")
    return ok


def label_issue(number: int, labels: list[str]) -> bool:
    if not labels:
        return True
    ok, _ = gh(["issue", "edit", str(number), "--add-label", ",".join(labels)])
    return ok


# --------------------------------------------------------------------------- 收件箱

INBOX_FIELDS = ("id", "source", "author", "title", "body", "url")


def read_inbox(path: str | Path) -> list[Item]:
    """X / Discord 都走这里：外部进程往 jsonl 里追加一行，循环只读。

    这样循环本身不需要持有社交平台的凭据——读消息的权限和发版的权限可以分开。
    """
    full = Path(path)
    if not full.is_absolute():
        full = C.REPO_ROOT / full
    items: list[Item] = []
    if not full.exists():
        return items
    seen_lines: set[str] = set()
    for line in full.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line in seen_lines:
            continue
        seen_lines.add(line)
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not row.get("id"):
            continue
        items.append(
            Item(
                id=str(row["id"]),
                source=row.get("source") or row["id"],
                title=row.get("title", ""),
                body=row.get("body", ""),
                author=row.get("author", ""),
                url=row.get("url", ""),
                kind=row.get("kind", "tweet"),
                raw=row,
            )
        )
    return items


def append_jsonl(path: str | Path, row: dict[str, Any]) -> None:
    full = Path(path)
    if not full.is_absolute():
        full = C.REPO_ROOT / full
    full.parent.mkdir(parents=True, exist_ok=True)
    with open(full, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def keyword_hit(text: str, keywords: list[str]) -> bool:
    if not keywords:
        return True
    low = (text or "").lower()
    return any(kw.lower() in low for kw in keywords)


# --------------------------------------------------------------------------- 时间窗


def since_iso(hours: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat(timespec="seconds")
