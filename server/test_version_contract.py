# -*- coding: utf-8 -*-
"""版本号钉点守卫：gradle 是唯一出处，文档里的版本号必须跟着它走。

升版本号只改 app/build.gradle.kts 一处是不够的 —— 仓库里至少 7 个地方钉着「当前版本」：
  1. docs/ROADMAP.md 现状速查表的版本行
  2. docs/ROADMAP.md 现状速查表的发版现状行
  3. docs/architecture/README.md 的「代码基线」行
  4. docs/architecture/README.md 的 flavor 表（versionName 行）
  5. docs/BUILD.md 的 --expect-tag 示例
  6. AGENTS.md 的 --expect-tag 示例
  7. docs/scripts/version_check.py 的用法示例与 argparse 帮助
漏改一处，读者照着文档打错 tag（release.yml 第一步就红），
或者文档写 0.3.x、gradle 已经是 0.4.0，两边对不上。

这里逐个钉住：期望值从 gradle 取，用例里不写死版本号 —— 下次升版只改了 gradle、忘了改文档（或者反过来）就被这里抓到。

不在检查范围：docs/ROADMAP.md §0 的 M0 历史快照、docs/review-*.md 评审记录、progress.md / revise.md 流水 ——
那里写的是当时的事实，不是当前状态。
"""

from __future__ import annotations

import importlib.util
import os
import re
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
VERSION_CHECK = os.path.join(ROOT, "docs", "scripts", "version_check.py")
RELEASE_YML = os.path.join(ROOT, ".github", "workflows", "release.yml")
ROADMAP = os.path.join(ROOT, "docs", "ROADMAP.md")
ARCH_README = os.path.join(ROOT, "docs", "architecture", "README.md")
BUILD_DOC = os.path.join(ROOT, "docs", "BUILD.md")
AGENTS = os.path.join(ROOT, "AGENTS.md")

EXPECT_TAG_RE = re.compile(r"--expect-tag\s+(v[0-9][0-9A-Za-z.\-]*)")


def _load_module(path: str, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VC = _load_module(VERSION_CHECK, "notifyme_version_check")


def _read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def _lines(path: str):
    return _read(path).splitlines()


def current():
    """当前版本（唯一出处：app/build.gradle.kts）。"""
    name, code = VC.read_versions(Path(ROOT))
    assert name, "读不到 app/build.gradle.kts 的 versionName"
    assert code, "读不到 app/build.gradle.kts 的 versionCode"
    return name, int(code)


def _find(lines, needle: str, start: int = 0) -> int:
    for idx in range(start, len(lines)):
        if needle in lines[idx]:
            return idx
    raise AssertionError("找不到含 " + repr(needle) + " 的行")


def _roadmap_current_span(lines):
    """现状速查表的行区间 [start, end)：不含 §0 历史快照。"""
    start = _find(lines, "## 现状速查")
    end = _find(lines, "## 0.", start + 1)
    return start, end


def test_gradle_is_the_single_source_of_truth():
    name, code = current()
    assert VC.VERSION_NAME_FORMAT.match(name), "versionName 格式非法：" + name
    assert code > 0, "versionCode 必须为正整数"
    assert not name.endswith("-test"), "正式版 versionName 不该带 flavor 后缀：" + name
    raw = _read(GRADLE)
    assert 'versionName = "%s"' % name in raw
    assert "versionCode = %d" % code in raw


def test_roadmap_current_table_version_row_matches_gradle():
    name, code = current()
    lines = _lines(ROADMAP)
    start, end = _roadmap_current_span(lines)
    idx = _find(lines, "| 版本 | versionName", start)
    assert idx < end, "版本行必须在现状速查表里，不能落在 §0 历史快照"
    line = lines[idx]
    assert "`%s`" % name in line, "ROADMAP 版本行与 gradle 不一致：" + line
    assert "`%d`" % code in line, "ROADMAP 版本行 versionCode 与 gradle 不一致：" + line


def test_roadmap_release_status_row_matches_gradle():
    name, code = current()
    lines = _lines(ROADMAP)
    start, end = _roadmap_current_span(lines)
    idx = _find(lines, "| 发版现状 |", start)
    assert idx < end
    line = lines[idx]
    assert name in line, "ROADMAP 发版现状行的版本号与 gradle 不一致：" + line
    assert str(code) in line


def test_architecture_readme_baseline_line_matches_gradle():
    name, code = current()
    lines = _lines(ARCH_README)
    idx = _find(lines, "代码基线")
    line = lines[idx]
    assert "versionName %s" % name in line, "architecture README 代码基线行的 versionName 与 gradle 不一致"
    assert "versionCode %d" % code in line, "architecture README 代码基线行的 versionCode 与 gradle 不一致"


def test_architecture_readme_flavor_table_matches_gradle():
    name, _code = current()
    lines = _lines(ARCH_README)
    hits = [l for l in lines if l.strip().startswith("| versionName |")]
    assert len(hits) == 1, "flavor 表里的 versionName 行应唯一，实际 " + str(len(hits))
    line = hits[0]
    assert "| `%s` |" % name in line, "flavor 表的 open 版本与 gradle 不一致：" + line
    assert "| `%s-test` |" % name in line, "flavor 表的 beta 版本与 gradle 不一致：" + line
    gradle = _read(GRADLE)
    assert 'versionNameSuffix = "-test"' in gradle, "beta 后缀约定变了，flavor 表的 -test 要一起改"


def test_build_doc_expect_tag_matches_gradle():
    name, _code = current()
    text = _read(BUILD_DOC)
    tags = EXPECT_TAG_RE.findall(text)
    assert tags, "docs/BUILD.md 里找不到 --expect-tag 示例"
    for tag in tags:
        assert tag == "v" + name, "docs/BUILD.md 的 --expect-tag 指向旧版本：" + tag


def test_agents_expect_tag_matches_gradle():
    name, _code = current()
    text = _read(AGENTS)
    tags = EXPECT_TAG_RE.findall(text)
    assert tags, "AGENTS.md 里找不到 --expect-tag 示例"
    for tag in tags:
        assert tag == "v" + name, "AGENTS.md 的 --expect-tag 指向旧版本：" + tag


def test_version_check_examples_use_current_tag():
    name, _code = current()
    text = _read(VERSION_CHECK)
    assert "--expect-tag v" + name in text, "version_check.py 的用法示例还是旧版本"
    assert "--expect-version " + name in text, "version_check.py 的 --expect-version 示例还是旧版本"
    assert "如 v" + name in text, "argparse 帮助里的 tag 示例还是旧版本"


def test_release_workflow_delegates_to_version_check():
    text = _read(RELEASE_YML)
    assert "version_check.py" in text, "release.yml 必须走 version_check.py 校验 tag"
    assert "--expect-tag" in text
    assert "GITHUB_REF_NAME" in text, "release.yml 应该用实际 tag 校验，别写死版本号"
    assert not re.search(r"--expect-tag\s+v[0-9]", text), "release.yml 不该写死 tag 版本号"


def test_historical_snapshot_is_excluded_from_the_check():
    lines = _lines(ROADMAP)
    start, end = _roadmap_current_span(lines)
    hits = [i for i, l in enumerate(lines) if "| 版本 | versionName" in l]
    assert len(hits) >= 2, "ROADMAP 应同时保留当前事实表与 §0 历史快照两份版本行"
    assert hits[0] < end, "第一个版本行必须落在现状速查表里"
    assert any(i >= end for i in hits), "§0 历史快照里的版本行不应参与判定"


def test_no_pin_file_mentions_a_stale_expect_tag():
    name, _code = current()
    for path in (BUILD_DOC, AGENTS, VERSION_CHECK):
        for tag in EXPECT_TAG_RE.findall(_read(path)):
            assert tag == "v" + name, path + " 里的 --expect-tag 指向旧版本：" + tag


def test_version_check_cli_accepts_current_tag_and_rejects_others():
    name, _code = current()
    assert VC.main(["--expect-tag", "v" + name]) == 0
    assert VC.main(["--expect-version", name]) == 0
    assert VC.main(["--expect-tag", "v9.9.9"]) == 1
    assert VC.main(["--expect-version", "9.9.9"]) == 1
