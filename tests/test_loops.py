"""流水线与运行时的测试。"""

from __future__ import annotations

import json
import sys
import unittest
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gates import acceptance_check as acc
from loops import common as C
from loops import loop3_review
from loops import sources
from loops.pipeline import Item, Pipeline, render_acceptance

FIXTURES = REPO_ROOT / "tests" / "fixtures"


class AcceptanceRenderingTests(unittest.TestCase):
    def test_render_then_parse_then_validate(self) -> None:
        """渲染出来的提交说明，必须能被自己的解析器读回去并通过校验。

        这条断了，说明“写”和“查”用了两套格式——那套格式就形同虚设。
        """
        record = json.loads((FIXTURES / "acceptance_ok.json").read_text(encoding="utf-8"))
        message = "fix: something\n\n" + render_acceptance(record) + "\n\nLoop: loop1\n"
        parsed = acc.parse_text(message)
        problems, _ = acc.validate(parsed)
        self.assertEqual([], problems, parsed)

    def test_render_empty_not_run_becomes_none(self) -> None:
        record = {
            "source": "issue #1",
            "cause": "空指针，取值前没判空",
            "change": "先判空，补测试",
            "verified": [{"where": "ci ubuntu", "check": "suite", "result": "PASS"}],
            "red_green": [
                {"step": "revert", "result": "FAIL"},
                {"step": "apply", "result": "PASS"},
            ],
            "not_run": [],
        }
        text = render_acceptance(record)
        self.assertIn("Not run:\n  - none", text)
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertEqual([], problems)


class StateTests(unittest.TestCase):
    def test_seen_is_per_loop(self) -> None:
        path = C.STATE_DIR / "test-state.json"
        if path.exists():
            path.unlink()
        state = C.State(path)
        self.assertFalse(state.seen("loop1", "x-1"))
        state.mark("loop1", "x-1")
        self.assertTrue(state.seen("loop1", "x-1"))
        self.assertFalse(state.seen("loop2", "x-1"))
        path.unlink(missing_ok=True)


class WindowTests(unittest.TestCase):
    def test_simple_window(self) -> None:
        when = datetime(2026, 11, 17, 3, 30)
        self.assertTrue(C.in_window("02:00-05:00", when))
        self.assertFalse(C.in_window("02:00-05:00", when.replace(hour=6)))

    def test_window_across_midnight(self) -> None:
        self.assertTrue(C.in_window("22:00-06:00", datetime(2026, 11, 17, 23, 0)))
        self.assertTrue(C.in_window("22:00-06:00", datetime(2026, 11, 17, 5, 0)))
        self.assertFalse(C.in_window("22:00-06:00", datetime(2026, 11, 17, 12, 0)))


class InboxTests(unittest.TestCase):
    def test_reads_fixture_feed(self) -> None:
        items = sources.read_inbox(FIXTURES / "x.jsonl")
        self.assertEqual(3, len(items))
        self.assertEqual("x-9001", items[0].id)

    def test_keyword_filter(self) -> None:
        self.assertTrue(sources.keyword_hit("magpie broke on my NAS", ["magpie"]))
        self.assertFalse(sources.keyword_hit("newcastle won", ["magpie"]))


class PipelineTests(unittest.TestCase):
    def test_offline_run_reaches_ship(self) -> None:
        cfg = C.load_config()
        cfg.setdefault("loop2", {})["inbox"] = str(FIXTURES / "x.jsonl")
        tick = C.Tick.make("loop2", cfg, dry_run=True, offline=True)

        pipe = Pipeline(tick, cheap_gate=True, reply=None, allow_release=False)
        item = Item(id="test-offline", source="x/@devon", title="nas syntax error", body="magpie /bin/sh is dash")
        outcome = pipe.handle(item)

        self.assertEqual("shipped", outcome.status, outcome.detail)
        self.assertIsNone(outcome.commit)  # dry-run 不落提交

    def test_noise_is_dropped_by_cheap_gate(self) -> None:
        cfg = C.load_config()
        tick = C.Tick.make("loop2", cfg, dry_run=True, offline=True)
        pipe = Pipeline(tick, cheap_gate=True, reply=None, allow_release=False)
        item = Item(id="test-noise", source="x/@toonfan", title="", body="Newcastle magpie fans at St James' Park")
        outcome = pipe.handle(item)
        self.assertEqual("skipped", outcome.status)


class ReviewMathTests(unittest.TestCase):
    def test_render_log_keeps_loop_markers(self) -> None:
        commits = [
            {"short": "abc1234", "subject": "fix: x", "body": "Loop: loop1\nItem: gh-issue-1\nAcceptance:\nSource: s"},
        ]
        log = loop3_review._render_log(commits)
        self.assertIn("abc1234", log)
        self.assertIn("Loop: loop1", log)

    def test_missing_acceptance_is_called_out(self) -> None:
        commits = [{"short": "abc1234", "subject": "x", "body": "Loop: loop2"}]
        rendered = loop3_review._render_acceptance(commits)
        self.assertIn("没有验收记录", rendered)


class ReviewRunTests(unittest.TestCase):
    def test_offline_review_writes_report_and_touches_nothing_else(self) -> None:
        """loop3 跑一轮：写报告，但不改 LESSONS / 不动代码。"""
        from unittest import mock

        cfg = C.load_config()
        cfg.setdefault("loop3", {})["ignore_window"] = True
        tick = C.Tick.make("loop3", cfg, dry_run=False, offline=True)

        fake = [
            {
                "sha": "f" * 40,
                "short": "fffffff",
                "subject": "fix: nas dash syntax error",
                "body": "Loop: loop1\nItem: gh-issue-412\nAcceptance:\nSource: issue #412\nCause: dash 不支持 function 写法\nChange: 改成 POSIX 写法\nVerified:\n  - macos | suite | PASS\nRed/Green:\n  - revert: FAIL\n  - apply: PASS\nNot run:\n  - none",
            }
        ]
        lessons_before = C.read_text(C.REPO_ROOT / "LESSONS.md")

        with mock.patch.object(loop3_review, "_loop_commits", return_value=fake):
            outcomes = loop3_review.run_once(tick)

        self.assertEqual([], outcomes)
        reports = sorted((C.STATE_DIR / "review").glob("*.md"))
        self.assertTrue(reports)
        latest = reports[-1].read_text(encoding="utf-8")
        self.assertIn("loop3 复盘", latest)
        self.assertEqual(lessons_before, C.read_text(C.REPO_ROOT / "LESSONS.md"))


if __name__ == "__main__":
    unittest.main()
