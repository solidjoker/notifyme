# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""server/extract_common.py 的单元测试。

共享内核原来藏在两个脚本里的两份逐字重复代码中，从来没有独立测试：
这两个脚本在 CI 里只做语法检查，改一处忘另一处的口径分叉也没人拦。
这里把内核按「与业务无关」的纯逻辑钉住 —— 用临时目录与假宿主模块，
不依赖真实微信库、不写 server/data。
"""

import os
import sqlite3
import sys
import types

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import extract_common as common      # noqa: E402


@pytest.fixture
def host(monkeypatch):
    """构造一个假宿主脚本模块（两个脚本 bind 的对象就是这种东西）。"""
    module = types.SimpleNamespace(
        MAIN_DB=os.path.join(os.getcwd(), "nowhere", "messages.db"),
        PKG="com.example.app",
        SOURCE_TAG="fixture-source",
        COMMIT_BATCH=3,
        COMMIT_INTERVAL=0.0,
        CROSS_SOURCE_SKEW_MS=500,
        JSONL_MAX_BYTES=1024 * 1024,
        LOCK_TIMEOUT=0.3,
        LOCK_STALE_SECONDS=60.0,
    )
    common.activate(module)
    yield module
    common.activate(None)      # 复位：别让配置漏到下一条用例


def _db(path):
    """建一张与主库同形的最小 messages 表。"""
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE messages ("
                "sender TEXT NOT NULL, text TEXT NOT NULL, "
                "timestamp INTEGER NOT NULL, conversation TEXT NOT NULL, "
                "is_group INTEGER NOT NULL, received_at INTEGER NOT NULL, "
                "source TEXT NOT NULL, pkg TEXT NOT NULL)")
    con.commit()
    return con


def _msg(**kw):
    m = {"pkg": "com.tencent.mm", "conversation": "甲项目群", "sender": "李四",
         "timestamp": 1_700_000_000_000, "text": "明天交付"}
    m.update(kw)
    return m


# ------------------------------------------------------------------
# 配置注入
# ------------------------------------------------------------------

def test_setting_falls_back_to_defaults_without_host():
    common.activate(None)
    assert common._setting("PKG") == "com.tencent.mm"
    assert common._setting("COMMIT_BATCH") == 500
    assert common._setting("JSONL_MAX_BYTES") == 64 * 1024 * 1024


def test_setting_prefers_host_module(host):
    assert common._setting("PKG") == "com.example.app"
    assert common._setting("COMMIT_BATCH") == 3
    assert common._setting("CROSS_SOURCE_SKEW_MS") == 500


def test_setting_honours_zero_values(host):
    """0 是合法配置（COMMIT_INTERVAL=0 表示只按条数提交），不能当缺失。"""
    assert common._setting("COMMIT_INTERVAL") == 0.0
    host.COMMIT_BATCH = 0
    assert common._setting("COMMIT_BATCH") == 0


def test_setting_ignores_missing_attribute(host):
    """宿主没定义某项时回退默认（不是 AttributeError）。"""
    del host.MAIN_DB
    assert common._setting("MAIN_DB") == ""


def test_activate_switches_source():
    """两个脚本先后 activate，配置来源跟着切换（同进程 ade + pde 共存）。"""
    first = types.SimpleNamespace(PKG="a.b.c")
    second = types.SimpleNamespace(PKG="x.y.z")
    try:
        common.activate(first)
        assert common._setting("PKG") == "a.b.c"
        common.activate(second)
        assert common._setting("PKG") == "x.y.z"
    finally:
        common.activate(None)


def test_archive_and_lock_path_follow_main_db(host, tmp_path):
    """存档 / 锁文件与主库同目录：主库被指到临时目录时不能写进正式存档。"""
    host.MAIN_DB = str(tmp_path / "messages.db")
    assert common._archive_path() == str(tmp_path / "messages.jsonl")
    assert common._lock_path() == str(tmp_path / "extract.lock")


def test_archive_path_resolves_at_call_time(host, tmp_path):
    """运行时解析：改宿主 MAIN_DB 后立刻生效（不是 import 时定死）。"""
    host.MAIN_DB = str(tmp_path / "one" / "messages.db")
    first = common._archive_path()
    host.MAIN_DB = str(tmp_path / "two" / "messages.db")
    assert common._archive_path() != first


# ------------------------------------------------------------------
# 结构化日志
# ------------------------------------------------------------------

def test_log_event_emits_key_value_pairs(capsys):
    common._log_event("probe", count=3, name="测试", skipped=None)
    err = capsys.readouterr().err
    assert "event=probe" in err
    assert "count=3" in err
    assert "name=测试" in err
    assert "skipped" not in err      # None 字段整体省略，不写 skipped=None


def test_log_event_flattens_newlines(capsys):
    common._log_event("probe", text="第一行\n第二行\r第三行")
    err = capsys.readouterr().err.strip()
    assert "\n" not in err[len("[extract] event=probe text="):]
    assert "第一行 第二行 第三行" in err


def test_log_warn_goes_to_stderr_only(capsys):
    common._log_warn("磁盘快满了")
    out = capsys.readouterr()
    assert "磁盘快满了" in out.err
    assert out.out == ""


def test_ro_uri_is_read_only_file_uri(tmp_path):
    db = tmp_path / "a b.db"
    uri = common._ro_uri(db)
    assert uri.startswith("file:")
    assert uri.endswith("?mode=ro")
    assert " " not in uri           # 空格要转义成 %20


# ------------------------------------------------------------------
# 原文存档
# ------------------------------------------------------------------

def test_archive_item_defaults_from_host(host):
    item = common._archive_item(_msg())
    assert item["pkg"] == "com.tencent.mm"      # 消息自带 pkg 优先
    assert item["source"] == "fixture-source"   # 缺省取宿主 SOURCE_TAG
    assert item["timestamp"] == 1_700_000_000_000
    assert item["is_group"] is False


def test_archive_item_explicit_args_win():
    item = common._archive_item({"sender": "王五", "text": "好", "timestamp": "12"},
                                source="pc-db", pkg="com.tencent.mm")
    assert item["source"] == "pc-db"
    assert item["timestamp"] == 12       # 字符串时间戳按 int 落
    assert item["conversation"] == ""    # 缺字段补空串，不是 None


def test_archive_item_is_group_coerced_to_bool():
    assert common._archive_item(_msg(is_group=1))["is_group"] is True
    assert common._archive_item(_msg(is_group=0))["is_group"] is False


def test_archive_line_keeps_chinese_and_adds_newline():
    line = common._archive_line({"sender": "李四", "text": "明天交付"})
    assert line.endswith("\n")
    assert "李四" in line                 # ensure_ascii=False，不是 \u674e


def test_archiver_writes_and_counts(host, tmp_path):
    host.MAIN_DB = str(tmp_path / "messages.db")
    archiver = common._JsonlArchiver()
    assert archiver.path == str(tmp_path / "messages.jsonl")
    archiver.add(common._archive_item(_msg()))
    archiver.skip(2)
    assert archiver.flush() == 1
    assert archiver.archived == 1
    assert archiver.skipped == 2
    lines = (tmp_path / "messages.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1 and "明天交付" in lines[0]


def test_archiver_flush_without_pending_is_noop(host, tmp_path):
    host.MAIN_DB = str(tmp_path / "messages.db")
    archiver = common._JsonlArchiver()
    assert archiver.flush() == 0
    assert not (tmp_path / "messages.jsonl").exists()   # 不因空提交产生空文件


def test_archiver_rotates_only_above_limit(tmp_path):
    """轮转判的是「本批追加前」的文件字节数：超限才转，转完把本批写进新文件。"""
    path = tmp_path / "messages.jsonl"
    rotated = tmp_path / "messages.jsonl.1"
    path.write_text("x" * 50, encoding="utf-8")     # 写前 50 字节
    archiver = common._JsonlArchiver(path=str(path), max_bytes=30)
    archiver.add("[图片]")
    archiver.flush()
    assert os.path.getsize(rotated) == 50            # 旧档整份挪到 .1
    assert "x" not in path.read_text(encoding="utf-8")
    assert "\u56fe\u7247" in path.read_text(encoding="utf-8") or "[图片]" in path.read_text(encoding="utf-8")

def test_archiver_equal_to_limit_does_not_rotate(tmp_path):
    path = tmp_path / "messages.jsonl"
    path.write_text("12345", encoding="utf-8")
    archiver = common._JsonlArchiver(path=str(path), max_bytes=5)
    archiver.add("z")
    archiver.flush()
    assert not (tmp_path / "messages.jsonl.1").exists()   # 等于阈值不轮转


def test_archiver_write_failure_is_counted_not_raised(tmp_path):
    """存档写失败不该让整轮提取崩掉：只计数 + 告警。"""
    archiver = common._JsonlArchiver(path=str(tmp_path))   # 目录当文件写
    archiver.add("x")
    assert archiver.flush() == 0
    assert archiver.errors == 1
    assert archiver.archived == 0


# ------------------------------------------------------------------
# 跨进程锁
# ------------------------------------------------------------------

def test_file_lock_acquire_creates_file_and_release_cleans(tmp_path):
    path = tmp_path / "extract.lock"
    lock = common._FileLock(path=str(path), timeout=1.0)
    assert lock.acquire() is lock
    assert path.exists()
    lock.release()
    assert lock._fh is None and lock._excl is None


def test_file_lock_times_out_with_readable_error(tmp_path, monkeypatch):
    lock = common._FileLock(path=str(tmp_path / "extract.lock"), timeout=0.2)
    monkeypatch.setattr(lock, "_try_lock", lambda: False)
    with pytest.raises(common.ExtractError) as excinfo:
        lock.acquire()
    assert "拿不到提取写锁" in str(excinfo.value)
    assert str(tmp_path / "extract.lock") in str(excinfo.value)


def test_file_lock_release_without_acquire_is_safe(tmp_path):
    common._FileLock(path=str(tmp_path / "extract.lock")).release()


# ------------------------------------------------------------------
# 去重
# ------------------------------------------------------------------

def test_dedup_key_contains_pkg_and_millisecond(tmp_path):
    con = _db(tmp_path / "m.db")
    dedup = common._Dedup(con, "android-db", "com.tencent.mm")
    assert common._Dedup._key(_msg()) == (
        "com.tencent.mm", "甲项目群", "李四", 1_700_000_000_000, "明天交付")
    con.close()


def test_dedup_same_second_different_ms_is_not_duplicate(tmp_path):
    con = _db(tmp_path / "m.db")
    dedup = common._Dedup(con, "android-db", "com.tencent.mm", skew_ms=0)
    first = _msg(timestamp=1_700_000_000_000)
    assert dedup.is_duplicate(first) is False
    con.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?)",
                ("李四", "明天交付", 1_700_000_000_000, "甲项目群", 0, 0,
                 "android-db", "com.tencent.mm"))
    dedup.reset_batch()
    assert dedup.is_duplicate(_msg(timestamp=1_700_000_000_001)) is False
    assert dedup.is_duplicate(_msg(timestamp=1_700_000_000_000)) is True
    con.close()


def test_dedup_skips_within_batch_without_query(tmp_path):
    """同批次里重复的同一条消息命中内存键，不必每行都查库。"""
    con = _db(tmp_path / "m.db")
    dedup = common._Dedup(con, "android-db", "com.tencent.mm")
    assert dedup.is_duplicate(_msg()) is False
    assert dedup.is_duplicate(_msg()) is True
    con.close()


def test_dedup_cross_source_skew_only_for_other_source(tmp_path):
    """跨来源近重复只在「来源不同」时放宽到时间窗内，同来源不放宽。"""
    con = _db(tmp_path / "m.db")
    # 通知渠道先入库一条，时间戳比数据库渠道早 400ms
    con.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?)",
                ("李四", "明天交付", 1_699_999_999_600, "甲项目群", 0, 0,
                 "android-notification", "com.tencent.mm"))
    con.commit()
    assert common._Dedup(con, "android-db", "com.tencent.mm",
                         skew_ms=0).is_duplicate(_msg()) is False
    assert common._Dedup(con, "android-db", "com.tencent.mm",
                         skew_ms=1000).is_duplicate(_msg()) is True
    con.close()


def test_dedup_skew_does_not_apply_to_same_source(tmp_path):
    """同来源 + 400ms 偏差：精确键不同、跨来源分支也不认 -> 不算重复。"""
    con = _db(tmp_path / "m.db")
    con.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?)",
                ("李四", "明天交付", 1_699_999_999_600, "甲项目群", 0, 0,
                 "android-db", "com.tencent.mm"))
    con.commit()
    assert common._Dedup(con, "android-db", "com.tencent.mm",
                         skew_ms=1000).is_duplicate(_msg()) is False
    con.close()

def test_dedup_skew_uses_host_setting_when_omitted(host, tmp_path):
    """skew_ms 缺省时去宿主取：测试 monkeypatch 的常量必须生效。"""
    con = _db(tmp_path / "m.db")
    dedup = common._Dedup(con, "android-db", "com.tencent.mm")
    assert dedup.skew_ms == 500
    host.CROSS_SOURCE_SKEW_MS = 42
    assert common._Dedup(con, "android-db", "com.tencent.mm").skew_ms == 42
    con.close()


# ------------------------------------------------------------------
# 分批提交
# ------------------------------------------------------------------

def _insert(writer, msg, ts=0):
    writer.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?)",
                   (msg["sender"], msg["text"], msg["timestamp"],
                    msg["conversation"], 0, ts, "android-db", msg["pkg"]))


def test_batch_writer_commits_per_batch_and_calls_hook(tmp_path):
    con = _db(tmp_path / "m.db")
    calls = []
    writer = common._BatchWriter(con, batch=2, interval=0, on_commit=lambda: calls.append(1))
    for i in range(5):
        _insert(writer, _msg(text="第%d条" % i))
        writer.maybe_commit()
    writer.flush()
    assert writer.commits == 3          # 2 + 2 + 1
    assert writer.rows == 0
    assert len(calls) == 3              # 存档写盘只在提交成功后触发
    assert con.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 5
    con.close()


def test_batch_writer_interval_zero_never_fires_on_time(tmp_path):
    """interval=0 表示只按条数提交（时间分支根本不判）。"""
    con = _db(tmp_path / "m.db")
    writer = common._BatchWriter(con, batch=100, interval=0)
    _insert(writer, _msg())
    writer.maybe_commit()
    assert writer.commits == 0
    con.close()


def test_batch_writer_batch_from_host_setting(host, tmp_path):
    """batch 缺省取宿主 COMMIT_BATCH（monkeypatch 的调参必须生效）。"""
    con = _db(tmp_path / "m.db")
    writer = common._BatchWriter(con, interval=0)
    assert writer.batch == 3
    host.COMMIT_BATCH = 7
    assert common._BatchWriter(con, interval=0).batch == 7
    con.close()


def test_batch_writer_flush_ignores_empty(tmp_path):
    con = _db(tmp_path / "m.db")
    writer = common._BatchWriter(con, batch=10, interval=0)
    writer.flush()
    assert writer.commits == 0
    con.close()


# ------------------------------------------------------------------
# 主库结构兜底
# ------------------------------------------------------------------

def test_ensure_main_schema_adds_missing_columns(tmp_path):
    con = sqlite3.connect(tmp_path / "m.db")
    con.execute("CREATE TABLE messages (sender TEXT, text TEXT, timestamp INTEGER, "
                "conversation TEXT, is_group INTEGER, received_at INTEGER)")
    con.execute("INSERT INTO messages VALUES ('李四', '旧行', 1, '甲项目群', 0, 0)")
    con.commit()
    common._ensure_main_schema(con)
    cols = [r[1] for r in con.execute("PRAGMA table_info(messages)")]
    assert "source" in cols and "pkg" in cols
    row = con.execute("SELECT source, pkg FROM messages").fetchone()
    assert row[0] == "android-notification"    # 历史行回填成「通知来源」
    assert row[1] == "com.tencent.mm"
    con.close()


def test_ensure_main_schema_is_idempotent(tmp_path):
    con = _db(tmp_path / "m.db")
    common._ensure_main_schema(con)
    common._ensure_main_schema(con)      # 已齐全时不得重复 ALTER（会抛 duplicate）
    con.close()


def test_ensure_main_schema_creates_dedup_index(tmp_path):
    con = _db(tmp_path / "m.db")
    common._ensure_main_schema(con)
    names = [r[1] for r in con.execute("PRAGMA index_list(messages)")]
    assert "idx_extract_dedup_lookup" in names
    common._ensure_main_schema(con)
    names2 = [r[1] for r in con.execute("PRAGMA index_list(messages)")]
    assert names2.count("idx_extract_dedup_lookup") == 1
    con.close()


def test_ensure_main_schema_reuses_equivalent_index(tmp_path):
    """已有同列唯一索引（app.py 建的）就不再补第二个。"""
    con = _db(tmp_path / "m.db")
    con.execute("CREATE UNIQUE INDEX idx_messages_dedup ON messages "
                "(pkg, conversation, sender, timestamp, text)")
    con.commit()
    common._ensure_main_schema(con)
    names = [r[1] for r in con.execute("PRAGMA index_list(messages)")]
    assert "idx_extract_dedup_lookup" not in names
    con.close()


# ------------------------------------------------------------------
# 游标值解析
# ------------------------------------------------------------------

@pytest.mark.parametrize("value, expected", [
    (0, 0),
    (17, 17),
    ("42", 42),
    (" 42 ", 42),
    ("+42", 42),
    (7.0, 7),
    (True, None),          # bool 是 int 子类，必须先排除
    (False, None),
    (-1, None),
    (7.5, None),
    ("", None),
    ("abc", None),
    (None, None),
    ([], None),
    ({"a": 1}, None),
])
def test_cursor_int(value, expected):
    assert common._cursor_int(value) == expected


def test_type_placeholder_covers_known_types():
    assert common.TYPE_PLACEHOLDER[3] == "[图片]"
    assert common.TYPE_PLACEHOLDER[10000] == "[系统消息]"
    assert common.TYPE_PLACEHOLDER.get(999, "[类型999消息]") == "[类型999消息]"

