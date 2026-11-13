# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""`docs/scripts/static_check.py` 的缓存目录落点守卫。

为什么放在 server/ 下：CI 与 local_check 的 pytest 根目录都是 server/，
放到别处等于没人跑。逻辑本身与被测代码都在 docs/scripts/ 与仓库根。

背景（真实事故）：cache_dir() 原来写 os.path.expanduser("~")，环境里缺
USERPROFILE 时 expanduser 会把 "~" 原样返回，拼出来就是相对路径
"~/.notifyme-tools/..."，71 MB 的 ktlint jar 于是被下载进仓库内的 "~/" 目录，
`git add -A` 一把带进提交（本次 PR 前已发生过一次，靠 reset --soft 才救回来）。
"""

import os
import sys

import pytest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "docs", "scripts"))

import static_check  # noqa: E402


@pytest.fixture()
def clean_env(monkeypatch):
    """清掉可能干扰的环境变量，测试结束后 pytest 自动还原。"""
    monkeypatch.delenv("NOTIFYME_TOOLS_DIR", raising=False)
    return monkeypatch


def _inside_repo(path):
    return static_check._inside(os.path.abspath(path), REPO_ROOT)


def test_cache_dir_is_absolute_and_outside_repo(clean_env):
    path = static_check.cache_dir()
    assert os.path.isabs(path)
    assert not _inside_repo(path), "缓存目录不能落在仓库内：" + path
    assert path.endswith(os.path.join("ktlint", static_check.KTLINT_VERSION))


def test_cache_dir_falls_back_to_temp_when_home_unresolved(clean_env):
    """expanduser 没展开（Windows 缺 USERPROFILE）时不能拼出相对路径。"""
    clean_env.setattr(static_check.os.path, "expanduser", lambda p: p)
    path = static_check.cache_dir()
    assert os.path.isabs(path), "必须自造绝对路径，别把 ~ 原样拼进去：" + path
    assert not _inside_repo(path)
    assert "~" not in path.split(os.sep), path


def test_cache_dir_rejects_explicit_dir_inside_repo(clean_env):
    """显式把 NOTIFYME_TOOLS_DIR 指进仓库也要改道：缓存是构建产物。"""
    clean_env.setenv("NOTIFYME_TOOLS_DIR", os.path.join(REPO_ROOT, ".tools"))
    path = static_check.cache_dir()
    assert not _inside_repo(path), path
    assert os.path.isabs(path)


def test_cache_dir_honours_explicit_dir_outside_repo(clean_env, tmp_path):
    clean_env.setenv("NOTIFYME_TOOLS_DIR", str(tmp_path))
    path = static_check.cache_dir()
    assert path == os.path.join(str(tmp_path), "ktlint", static_check.KTLINT_VERSION)


def test_inside_is_false_for_sibling_prefix(clean_env):
    """commonpath 判断，别用 startswith（"repo2" 会被 "repo" 前缀骗过）。"""
    assert static_check._inside(os.path.join(REPO_ROOT, "app"), REPO_ROOT)
    assert not static_check._inside(REPO_ROOT + "_other", REPO_ROOT)

