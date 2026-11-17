"""loop2 —— 守 X 的关键词流。

这个循环的开头多一步：**先用几乎不要钱的模型筛一遍**。

不是因为省钱，是因为产品名撞了常用词。magpie 的英文本意是喜鹊，也是英超纽卡斯尔联队的
外号。第一页全是球迷号，全丢给贵模型判断，钱就烧在球迷身上了。

安全上的取舍（原文没写，这里补上）：
读的是整个开放互联网，如果同时还握有发版权限，一句夹带的指令就能变成一个正式版本。
所以 loop2 默认**不许发版**，发版权限留在 loop1 或人手里。真要放开，得显式改
`allow_release_permission`，改了就是你自己承担。
"""

from __future__ import annotations

from loops import common as C
from loops import sources
from loops.pipeline import Item, Outcome, Pipeline


def run_once(tick: C.Tick) -> list[Outcome]:
    cfg, journal = tick.cfg, tick.journal
    outcomes: list[Outcome] = []

    allow_release = _release_allowed(tick)
    pipe = Pipeline(tick, cheap_gate=True, reply=_reply, allow_release=allow_release)

    inbox = C.cfg_get(cfg, "loop2.inbox", ".loopstate/inbox/x.jsonl")
    keywords = C.cfg_get(cfg, "loop2.keywords", []) or []
    max_items = int(C.cfg_get(cfg, "loop2.max_items_per_tick", 5))

    items = sources.read_inbox(inbox)
    journal.write("inbox", path=inbox, total=len(items), keywords=keywords)

    for item in items[: max_items * 4]:
        if tick.state.seen(tick.name, item.id):
            continue
        if not tick.budget_left():
            break
        if not sources.keyword_hit(item.title + " " + item.body, keywords):
            journal.write("keyword_miss", id=item.id)
            tick.state.mark(tick.name, item.id, verdict="keyword-miss")
            continue

        # 上游抓取可能重复投递同一个 id，这里再兜一次
        if _already_seen(tick, item):
            continue

        try:
            outcomes.append(pipe.handle(item))
        except C.LLMError as exc:
            journal.write("llm_error", id=item.id, error=str(exc)[:200])
            break

    tick.state.save(dry_run=tick.dry_run)
    return outcomes


def _release_allowed(tick: C.Tick) -> bool:
    """loop2 默认没有发版权。想给，得显式承认风险。"""
    wanted = bool(C.cfg_get(tick.cfg, "loop2.release", False))
    permit = bool(C.cfg_get(tick.cfg, "loop2.allow_release_permission", False))
    if wanted and not permit:
        tick.journal.write(
            "release_refused",
            why="loop2 读了开放互联网，默认不给发版权。要放开请设 allow_release_permission=true",
        )
        return False
    return wanted and permit


def _already_seen(tick: C.Tick, item: Item) -> bool:
    return tick.state.seen(tick.name, item.id)


def _reply(item: Item, text: str) -> None:
    """X 的回复走 outbox：外部发送器读它。循环自己不持有发帖凭据。"""
    sources.append_jsonl(
        ".loopstate/outbox/x.jsonl",
        {"reply_to": item.id, "url": item.url, "body": text},
    )
