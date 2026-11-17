"""发布门的测试。

这三条就是原文那句“16 个安装包一个不少、标成 Latest、绿色测试才叫发布了一次”。
少一条必须拒，而且拒的理由要说清楚缺哪条。
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gates import release_check as rc


def cfg(**over) -> dict:
    base = {
        "installer_count": 3,
        "installers_dir": "dist",
        "installer_glob": "*.dmg",
        "mark_latest": True,
        "latest_file": "dist/LATEST",
        "test_command": [sys.executable, "-c", "raise SystemExit(0)"],
    }
    base.update(over)
    return base


class ReleaseGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self._orig = rc.REPO_ROOT
        rc.REPO_ROOT = self.root
        (self.root / "dist").mkdir()

    def tearDown(self) -> None:
        rc.REPO_ROOT = self._orig
        self.tmp.cleanup()

    def _fill(self, count: int, latest: str | None = "v1") -> None:
        for i in range(count):
            (self.root / "dist" / f"app-{i}.dmg").write_text("x", encoding="utf-8")
        if latest is not None:
            (self.root / "dist" / "LATEST").write_text(latest, encoding="utf-8")

    def test_all_three_present_releases(self) -> None:
        self._fill(3)
        result = rc.evaluate("v1", cfg())
        self.assertEqual("release", result["verdict"], result)

    def test_missing_installer_refuses(self) -> None:
        self._fill(2)
        result = rc.evaluate("v1", cfg())
        self.assertEqual("refuse", result["verdict"])
        self.assertTrue(any(c["check"].startswith("安装包") and c["result"] == "FAIL" for c in result["checks"]))

    def test_unconfigured_installers_is_not_run(self) -> None:
        result = rc.evaluate("v1", cfg(installers_dir=None))
        self.assertEqual("refuse", result["verdict"])
        self.assertTrue(any(c["result"] == "NOT RUN" for c in result["checks"]))

    def test_stale_latest_refuses(self) -> None:
        self._fill(3, latest="v0")
        result = rc.evaluate("v1", cfg())
        self.assertEqual("refuse", result["verdict"])

    def test_red_tests_refuse(self) -> None:
        self._fill(3)
        result = rc.evaluate("v1", cfg(test_command=[sys.executable, "-c", "raise SystemExit(1)"]))
        self.assertEqual("refuse", result["verdict"])
        self.assertTrue(any(c["check"] == "全量测试" and c["result"] == "FAIL" for c in result["checks"]))

    def test_skipped_tests_do_not_count_as_green(self) -> None:
        self._fill(3)
        result = rc.evaluate("v1", cfg(), skip_tests=True)
        self.assertEqual("refuse", result["verdict"])


if __name__ == "__main__":
    unittest.main()
