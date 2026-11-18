# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""`docs/scripts/static_check.py` 下载 ktlint jar 的健壮性守卫。

真实事故（2026-11-18）：CI 上 Maven Central 对 71 MB 的 ktlint 下载返回
HTTP 429 Too Many Requests，旧实现 `urllib.request.urlretrieve` 直接抛栈退出，
整条 CI 红 —— 而那不是代码质量问题，是网络抖动。于是：
  ① 显式带 User-Agent（Maven Central 对默认 python-urllib UA 会限流）；
  ② 限流与临时故障按退避重试；
  ③ sha256 不匹配也算可重试；
  ④ 不可重试的 HTTP 状态立即失败，不浪费时间。
"""

import hashlib
import io
import os
import sys
import urllib.error

import pytest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "docs", "scripts"))

import static_check  # noqa: E402

PAYLOAD = b"fake-ktlint-jar-bytes"
GOOD_SHA = hashlib.sha256(PAYLOAD).hexdigest()
OTHER_SHA = hashlib.sha256(b"other").hexdigest()


class _Resp:
    """最小响应对象：shutil.copyfileobj 只要 read() 与上下文管理器。"""

    def __init__(self, data):
        self._buf = io.BytesIO(data)

    def read(self, size=-1):
        return self._buf.read(size)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _http_error(code, reason="boom"):
    return urllib.error.HTTPError(
    "https://example.invalid/ktlint.jar", code, reason, {}, None
)


def _opener(script):
    """按脚本回放下载结果；脚本用完后重复最后一项。

    每一项要么是 bytes（成功响应体），要么是 Exception 实例（抛出的异常）。
    """
    calls = []

    def opener(req, timeout=None):
        calls.append(req)
        item = script[min(len(calls) - 1, len(script) - 1)]
        if isinstance(item, Exception):
            raise item
        return _Resp(item)

    return opener, calls


def _run(tmp_path, script, attempts=3, expected_sha=GOOD_SHA):
    """跑一次下载，返回 (结果, 调用次数, 等待秒数列表, 目标文件)。"""
    opener, calls = _opener(script)
    slept = []
    dest = os.path.join(str(tmp_path), "ktlint.jar")
    result = static_check.download_verified_jar(
    "https://example.invalid/ktlint.jar",
    dest,
    expected_sha,
    attempts=attempts,
    sleep_fn=slept.append,
    opener=opener,
    )
    return result, calls, slept, dest


def test_rate_limited_download_is_retried_then_succeeds(tmp_path):
    """429 属于可重试：第二次成功就算下载成功。"""
    result, calls, slept, dest = _run(tmp_path, [_http_error(429), PAYLOAD])
    assert result is True
    assert len(calls) == 2, "429 之后必须重试一次"
    assert slept == [static_check.DOWNLOAD_BACKOFF], slept
    with open(dest, "rb") as fh:
        assert fh.read() == PAYLOAD


def test_request_carries_an_explicit_user_agent(tmp_path):
    """Maven Central 对默认 python-urllib UA 会限流，必须显式带 UA。"""
    _, calls, _, _ = _run(tmp_path, [PAYLOAD])
    assert len(calls) == 1
    agent = calls[0].get_header("User-agent")
    assert agent, "请求必须带 User-Agent"
    assert "notifyme-static-check" in agent, agent


def test_non_retryable_status_fails_without_retrying(tmp_path):
    """404 不是瞬时故障：立刻失败，别把 CI 时间耗在重试上。"""
    opener, calls = _opener([_http_error(404, "Not Found")])
    dest = os.path.join(str(tmp_path), "ktlint.jar")
    with pytest.raises(SystemExit) as exc:
        static_check.download_verified_jar(
        "https://example.invalid/ktlint.jar",
        dest,
        GOOD_SHA,
        attempts=4,
        sleep_fn=lambda s: None,
        opener=opener,
        )
    assert len(calls) == 1, "不可重试的状态只试一次"
    assert "404" in str(exc.value)


def test_sha256_mismatch_is_retried_and_then_reported(tmp_path):
    """sha256 不匹配 = 内容被截断或篡改：换一次再试，仍不行就明确报错。"""
    with pytest.raises(SystemExit) as exc:
        _run(tmp_path, [b"corrupted"], attempts=2, expected_sha=GOOD_SHA)
    assert "sha256" in str(exc.value)


def test_attempts_are_capped_and_backoff_grows(tmp_path):
    """持续 429：最多试 N 次，等待时间逐次加长。"""
    opener, calls = _opener([_http_error(429)])
    slept = []
    dest = os.path.join(str(tmp_path), "ktlint.jar")
    with pytest.raises(SystemExit):
        static_check.download_verified_jar(
        "https://example.invalid/ktlint.jar",
        dest,
        GOOD_SHA,
        attempts=3,
        sleep_fn=slept.append,
        opener=opener,
        )
    assert len(calls) == 3
    assert slept == [
    static_check.DOWNLOAD_BACKOFF,
    static_check.DOWNLOAD_BACKOFF * 2,
    ], slept


def test_partial_file_is_removed_after_a_failed_attempt(tmp_path):
    """失败后不留半截 .part：下一次的下载从头写。"""
    opener, _ = _opener([_http_error(500, "Server Error")])
    dest = os.path.join(str(tmp_path), "ktlint.jar")
    with pytest.raises(SystemExit):
        static_check.download_verified_jar(
        "https://example.invalid/ktlint.jar",
        dest,
        GOOD_SHA,
        attempts=1,
        sleep_fn=lambda s: None,
        opener=opener,
        )
    assert not os.path.exists(dest)


def test_network_error_is_retried(tmp_path):
    """URLError（连接被重置 / DNS 抖动）也算瞬时故障。"""
    opener, calls = _opener([urllib.error.URLError("connection reset")])
    slept = []
    dest = os.path.join(str(tmp_path), "ktlint.jar")
    with pytest.raises(SystemExit):
        static_check.download_verified_jar(
        "https://example.invalid/ktlint.jar",
        dest,
        GOOD_SHA,
        attempts=2,
        sleep_fn=slept.append,
        opener=opener,
        )
    assert len(calls) == 2
    assert slept == [static_check.DOWNLOAD_BACKOFF]


def test_attempt_count_can_be_overridden_by_env(monkeypatch):
    """CI 被限流时可以调大重试次数，不用改代码。"""
    monkeypatch.setenv("NOTIFYME_KTLINT_ATTEMPTS", "9")
    assert static_check.download_attempts() == 9
    monkeypatch.setenv("NOTIFYME_KTLINT_ATTEMPTS", "not-a-number")
    assert static_check.download_attempts() == static_check.DOWNLOAD_ATTEMPTS
    monkeypatch.delenv("NOTIFYME_KTLINT_ATTEMPTS")
    assert static_check.download_attempts() == static_check.DOWNLOAD_ATTEMPTS


def test_retryable_status_covers_rate_limit_and_server_errors():
    """429 / 408 / 425 / 5xx 可重试，4xx（除上面三个）不可重试。"""
    for code in (408, 425, 429, 500, 502, 503, 504):
        assert code in static_check.RETRY_STATUS, code
    for code in (400, 401, 403, 404, 410, 501):
        assert code not in static_check.RETRY_STATUS, code
