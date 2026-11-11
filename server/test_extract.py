# -*- coding: utf-8 -*-
"""提取脚本单元测试。运行：pytest server/test_extract.py -v

覆盖评审发现的 N1（分批提交）、N4（有界去重）、E3（去重键含 pkg 且毫秒级）、
E7（解析失败计数）、P3（状态文件原子写）。
"""
import json
import os
import sqlite3
import sys

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import android_db_extract as ade

try:
    import pc_db_extract as pde
except Exception as _e:       # pragma: no cover - 工具链目录非法时跳过
    pde = None
    _PC_IMPORT_ERROR = _e


MAIN_DB_SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender TEXT, text TEXT, timestamp INTEGER, conversation TEXT,
    is_group INTEGER, received_at INTEGER, source TEXT, pkg TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedup
    ON messages (pkg, conversation, sender, timestamp, text);
"""


def _make_main_db(path, rows=()):
    con = sqlite3.connect(path)
    con.executescript(MAIN_DB_SCHEMA)
    for r in rows:
        con.execute(
            "INSERT INTO messages (sender, text, timestamp, conversation,"
            " is_group, received_at, source, pkg) VALUES (?,?,?,?,?,?,?,?)",
            r)
    con.commit()
    con.close()


def _make_source_db(path, rows):
    con = sqlite3.connect(path)
    con.execute(
        "CREATE TABLE message (msgId INTEGER PRIMARY KEY, msgSvrId INTEGER,"
        " type INTEGER, isSend INTEGER, createTime INTEGER, talker TEXT,"
        " content TEXT)")
    con.execute("CREATE TABLE rcontact (username TEXT, conRemark TEXT,"
                " nickname TEXT)")
    con.execute("CREATE TABLE chatroom (chatroomname TEXT, displayname TEXT)")
    con.executemany("INSERT INTO message VALUES (?,?,?,?,?,?,?)", rows)
    con.commit()
    con.close()


def _msg_row(msg_id, create_ms, content="hello", talker="wxid_a", is_send=0):
    return (msg_id, msg_id, 1, is_send, create_ms, talker, content)


@pytest.fixture
def env(tmp_path, monkeypatch):
    """把提取脚本的路径常量指向临时目录。"""
    tmp = str(tmp_path)
    main_db = os.path.join(tmp, "messages.db")
    src_db = os.path.join(tmp, "EnMicroMsg.decrypted.db")
    state = os.path.join(tmp, "state.json")
    _make_main_db(main_db)
    monkeypatch.setattr(ade, "MAIN_DB", main_db)
    monkeypatch.setattr(ade, "DECRYPTED_DB", src_db)
    monkeypatch.setattr(ade, "STATE_PATH", state)
    monkeypatch.setattr(ade, "ANDROID_DIR", tmp)
    monkeypatch.setattr(ade, "COMMIT_BATCH", 2)
    monkeypatch.setattr(ade, "COMMIT_INTERVAL", 0)
    return {"main": main_db, "src": src_db, "state": state, "dir": tmp}


def _rows(con):
    return con.execute(
        "SELECT sender, text, timestamp, conversation, is_group, source, pkg"
        " FROM messages ORDER BY id").fetchall()


# ------------------------------------------------------------------
# E3 / N4：去重键
# ------------------------------------------------------------------

def test_dedup_key_contains_pkg_and_millisecond():
    """E3：去重键含 pkg 且是毫秒级，同 App 同秒同文不再误判为重复。"""
    tmp = os.path.join(os.environ.get("TEMP", "."), "dedup_key_test.db")
    if os.path.exists(tmp):
        os.remove(tmp)
    _make_main_db(tmp, rows=[
        ("peer", "hello", 1700000000000, "conv", 0, 1700000000000,
         "android-db", "com.tencent.mm"),
    ])
    con = sqlite3.connect(tmp)
    try:
        dedup = ade._Dedup(con, "android-db", "com.tencent.mm")
        base = {"pkg": "com.tencent.mm", "conversation": "conv",
                "sender": "peer", "text": "hello"}
        # 完全一致 -> 重复
        assert dedup.is_duplicate(dict(base, timestamp=1700000000000)) is True
        # 同 App、同秒、毫秒不同 -> 不是重复（旧的秒级归一键会误判）
        assert dedup.is_duplicate(dict(base, timestamp=1700000000001)) is False
        # 不同 App -> 不是重复（旧的键缺 pkg 会误判）
        assert dedup.is_duplicate(
            dict(base, pkg="com.other.app", timestamp=1700000000000)) is False
    finally:
        con.close()
        if os.path.exists(tmp):
            os.remove(tmp)


def test_cross_source_skew_only_applies_to_other_sources():
    """跨来源近重复：来源不同且时间差在窗口内才算同一条消息。"""
    tmp = os.path.join(os.environ.get("TEMP", "."), "dedup_skew_test.db")
    if os.path.exists(tmp):
        os.remove(tmp)
    _make_main_db(tmp, rows=[
        ("peer", "hello", 1700000000000, "conv", 0, 1700000000000,
         "android-notification", "com.tencent.mm"),
    ])
    con = sqlite3.connect(tmp)
    try:
        m = {"pkg": "com.tencent.mm", "conversation": "conv", "sender": "peer",
             "text": "hello", "timestamp": 1700000000400}
        assert ade._Dedup(con, "android-db", "com.tencent.mm",
                          skew_ms=1000).is_duplicate(dict(m)) is True
        assert ade._Dedup(con, "android-db", "com.tencent.mm",
                          skew_ms=0).is_duplicate(dict(m)) is False
        # 同来源不适用宽松窗口（毫秒不同就当新消息）
        assert ade._Dedup(con, "android-notification", "com.tencent.mm",
                          skew_ms=1000).is_duplicate(dict(m)) is False
    finally:
        con.close()
        if os.path.exists(tmp):
            os.remove(tmp)


# ------------------------------------------------------------------
# N1：分批提交
# ------------------------------------------------------------------

def test_batch_writer_commits_per_batch():
    con = sqlite3.connect(":memory:")
    con.execute("CREATE TABLE t (x INTEGER)")
    writer = ade._BatchWriter(con, batch=2, interval=0)
    writer.execute("INSERT INTO t VALUES (?)", (1,))
    writer.maybe_commit()
    assert writer.commits == 0 and con.in_transaction
    writer.execute("INSERT INTO t VALUES (?)", (2,))
    writer.maybe_commit()
    assert writer.commits == 1
    assert not con.in_transaction        # 提交后写锁释放
    writer.execute("INSERT INTO t VALUES (?)", (3,))
    writer.flush()
    assert writer.commits == 2
    assert con.execute("SELECT COUNT(*) FROM t").fetchone()[0] == 3
    con.close()


def test_merge_messages_commits_in_batches(env):
    """N1：10 条消息按每批 2 条提交，提交次数 = 5（写锁释放 5 次）。"""
    rows = [_msg_row(i, 1700000000000 + i, content=f"m{i}")
            for i in range(1, 11)]
    _make_source_db(env["src"], rows)
    stats = ade.merge_messages(full=True)
    assert stats["inserted"] == 10
    assert stats["commits"] == 5
    con = sqlite3.connect(env["main"])
    try:
        assert len(_rows(con)) == 10
    finally:
        con.close()


def test_merge_messages_commits_partial_batch_on_error(env, monkeypatch):
    """N1：中途异常时已写批次不丢，且不留半截事务。"""
    rows = [_msg_row(i, 1700000000000 + i, content=f"m{i}") for i in range(1, 5)]
    _make_source_db(env["src"], rows)

    real_execute = ade._BatchWriter.execute
    calls = {"n": 0}

    def boom(self, sql, params):
        calls["n"] += 1
        if calls["n"] > 2:
            raise sqlite3.OperationalError("boom")
        return real_execute(self, sql, params)

    monkeypatch.setattr(ade._BatchWriter, "execute", boom)
    with pytest.raises(sqlite3.OperationalError):
        ade.merge_messages(full=True)

    con = sqlite3.connect(env["main"])
    try:
        assert len(_rows(con)) == 2      # 已写的两条被提交，没有丢
    finally:
        con.close()
    # 状态文件没写：下次会重扫，去重会挡住重复行
    assert not os.path.exists(env["state"])


# ------------------------------------------------------------------
# E3 / N4：端到端合并语义
# ------------------------------------------------------------------

def test_merge_messages_keeps_same_second_different_ms(env):
    """E3：同 App 同秒同文但毫秒不同的两条消息，两条都要入库。"""
    rows = [
        _msg_row(1, 1700000000000, content="hello"),
        _msg_row(2, 1700000000001, content="hello"),   # 同秒同文、毫秒不同
        _msg_row(3, 1700000000000, content="hello"),   # 完全重复
    ]
    _make_source_db(env["src"], rows)
    stats = ade.merge_messages(full=True)
    assert stats["inserted"] == 2
    assert stats["duplicated"] == 1
    con = sqlite3.connect(env["main"])
    try:
        ts = [r[2] for r in _rows(con)]
        assert ts == [1700000000000, 1700000000001]
    finally:
        con.close()

    # 再跑一次：全部是重复，不产生新行
    stats2 = ade.merge_messages(full=True)
    assert stats2["inserted"] == 0 and stats2["duplicated"] == 3
    con = sqlite3.connect(env["main"])
    try:
        assert len(_rows(con)) == 2
    finally:
        con.close()


def test_merge_messages_does_not_load_whole_table(env, monkeypatch):
    """N4：去重不再把整张 messages 表读进 Python set。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    seen = []
    real_connect = sqlite3.connect

    class _SpyConn:
        """只记录 SQL 的连接代理（sqlite3.Connection 类型不可 monkeypatch）。"""

        def __init__(self, con):
            self._con = con

        def execute(self, sql, *args, **kwargs):
            seen.append(sql)
            return self._con.execute(sql, *args, **kwargs)

        def __getattr__(self, name):
            return getattr(self._con, name)

    def spy_connect(*args, **kwargs):
        return _SpyConn(real_connect(*args, **kwargs))

    monkeypatch.setattr(ade.sqlite3, "connect", spy_connect)
    ade.merge_messages(full=True)
    assert not any("SELECT conversation, sender, timestamp, text FROM messages"
                   in s for s in seen), seen


def test_merge_messages_creates_dedup_index_if_missing(tmp_path, monkeypatch):
    """N4 补充：去重查询要有索引，否则每行一次全表扫描（时间 O(n²)）。

    app.py 的 init_db() 会建 idx_messages_dedup；独立跑提取脚本时主库可能
    还没建过它，脚本要自己补一个同列索引，否则去重查询退化成全表扫描。
    """
    tmp = str(tmp_path)
    main_db = os.path.join(tmp, "messages.db")
    src_db = os.path.join(tmp, "EnMicroMsg.decrypted.db")
    con = sqlite3.connect(main_db)
    con.execute(
        "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,"
        " sender TEXT, text TEXT, timestamp INTEGER, conversation TEXT,"
        " is_group INTEGER, received_at INTEGER, source TEXT, pkg TEXT)")
    con.commit()
    con.close()          # 故意不建 idx_messages_dedup
    _make_source_db(src_db, [_msg_row(1, 1700000000000)])
    monkeypatch.setattr(ade, "MAIN_DB", main_db)
    monkeypatch.setattr(ade, "DECRYPTED_DB", src_db)
    monkeypatch.setattr(ade, "STATE_PATH", os.path.join(tmp, "state.json"))
    monkeypatch.setattr(ade, "ANDROID_DIR", tmp)
    ade.merge_messages(full=True)

    db = sqlite3.connect(main_db)
    try:
        colsets = []
        for row in db.execute("PRAGMA index_list(messages)").fetchall():
            cols = [r[2] for r in db.execute(
                f'PRAGMA index_info("{row[1]}")').fetchall()]
            colsets.append(cols)
    finally:
        db.close()
    want = ["pkg", "conversation", "sender", "timestamp", "text"]
    assert any(c[:5] == want for c in colsets), colsets


# ------------------------------------------------------------------
# E7：解析失败计数
# ------------------------------------------------------------------

def test_parse_error_counted_and_logged(env, capsys):
    """E7：解析失败被计数并记日志，不再静默吞掉。"""
    rows = [_msg_row(1, 1700000000000), _msg_row(2, 1700000000001)]
    _make_source_db(env["src"], rows)

    def bad_parse(row, contact_name, chatroom_name):
        raise ValueError("解析炸了")

    env_mod = ade
    env_mod._parse_row = bad_parse
    try:
        stats = ade.merge_messages(full=True)
    finally:
        ade._parse_row = _ORIG_PARSE_ROW
    assert stats["skipped"] == 2
    assert stats["inserted"] == 0
    assert stats["parse_error_samples"], "应保留解析失败样例"
    err = capsys.readouterr().err
    assert "解析失败" in err


_ORIG_PARSE_ROW = ade._parse_row


# ------------------------------------------------------------------
# P3：状态文件原子写
# ------------------------------------------------------------------

def test_save_state_is_atomic(env):
    """P3：state.json 走临时文件 + os.replace，替换后一定是完整 JSON。"""
    ade._save_state({"last_msgId": 42, "last_run": "2026-11-11 00:00:00"})
    with open(env["state"], encoding="utf-8") as f:
        assert json.load(f)["last_msgId"] == 42
    assert not os.path.exists(env["state"] + ".tmp")


def test_merge_writes_state_atomically(env):
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    ade.merge_messages(full=True)
    with open(env["state"], encoding="utf-8") as f:
        state = json.load(f)
    assert state["last_msgId"] == 1
    assert "last_run" in state
    assert not os.path.exists(env["state"] + ".tmp")


def test_corrupt_state_is_reported_not_swallowed(env, capsys):
    """损坏的 state.json 要有告警，且按全量重扫处理。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    with open(env["state"], "w", encoding="utf-8") as f:
        f.write("{ this is not json")
    stats = ade.merge_messages(full=False)
    assert stats["inserted"] == 1
    assert "增量状态读取失败" in capsys.readouterr().err


# ------------------------------------------------------------------
# PC 侧脚本
# ------------------------------------------------------------------

@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_dedup_key_and_batch_writer(tmp_path):
    db = os.path.join(str(tmp_path), "m.db")
    _make_main_db(db, rows=[
        ("peer", "hello", 1700000000000, "conv", 0, 1700000000000,
         "pc-db", "com.tencent.mm"),
    ])
    con = sqlite3.connect(db)
    try:
        dedup = pde._Dedup(con, "pc-db", "com.tencent.mm")
        base = {"pkg": "com.tencent.mm", "conversation": "conv",
                "sender": "peer", "text": "hello"}
        assert dedup.is_duplicate(dict(base, timestamp=1700000000000)) is True
        assert dedup.is_duplicate(dict(base, timestamp=1700000000001)) is False
        writer = pde._BatchWriter(con, batch=2, interval=0)
        writer.execute("INSERT INTO messages (sender) VALUES (?)", ("x",))
        writer.maybe_commit()
        assert writer.commits == 0
        writer.execute("INSERT INTO messages (sender) VALUES (?)", ("y",))
        writer.maybe_commit()
        assert writer.commits == 1 and not con.in_transaction
    finally:
        con.close()


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_save_state_atomic(tmp_path):
    monkey = os.path.join(str(tmp_path), "state.json")
    old_dir, old_path = pde.PC_DIR, pde.STATE_PATH
    pde.PC_DIR = str(tmp_path)
    pde.STATE_PATH = monkey
    try:
        pde._save_state({"accounts": {}})
        with open(monkey, encoding="utf-8") as f:
            assert json.load(f)["accounts"] == {}
        assert not os.path.exists(monkey + ".tmp")
    finally:
        pde.PC_DIR, pde.STATE_PATH = old_dir, old_path


# ------------------------------------------------------------------
# 阶段 3 / E7：结构化日志（event=... key=value）
# ------------------------------------------------------------------

def test_log_event_emits_key_value_pairs(capsys):
    """结构化日志：一行一条 event=<name> key=value，便于 grep / 采集器解析。"""
    ade._log_event("probe", count=3, name="测试", skipped=None)
    err = capsys.readouterr().err
    line = [x for x in err.splitlines() if "event=probe" in x]
    assert line, err
    line = line[0]
    assert "count=3" in line
    assert "name=测试" in line
    assert "skipped" not in line, "None 字段应当被省略"


def test_log_event_flattens_newlines(capsys):
    """字段里的换行不能把一条日志拆成多行。"""
    ade._log_event("probe", detail="第一行\n第二行")
    err = capsys.readouterr().err
    assert len([x for x in err.splitlines() if "event=probe" in x]) == 1


def test_merge_emits_structured_parse_error_event(env, capsys):
    """E7 / 3-6：解析失败既有中文告警，也有可解析的结构化事件行。"""
    rows = [_msg_row(1, 1700000000000), _msg_row(2, 1700000000001)]
    _make_source_db(env["src"], rows)

    def bad_parse(row, contact_name, chatroom_name):
        raise ValueError("解析炸了")

    env_mod = ade
    env_mod._parse_row = bad_parse
    try:
        ade.merge_messages(full=True)
    finally:
        ade._parse_row = _ORIG_PARSE_ROW
    err = capsys.readouterr().err
    assert "解析失败" in err
    assert "event=parse_errors" in err
    assert "count=2" in err


def test_batch_commit_emits_structured_event(env, capsys):
    """分批提交也留结构化事件（阶段 3：提取脚本结构化日志）。"""
    db = env["main"]
    con = sqlite3.connect(db)
    try:
        writer = ade._BatchWriter(con, batch=1, interval=0)
        writer.execute("INSERT INTO messages (sender) VALUES (?)", ("x",))
        writer.maybe_commit()
    finally:
        con.close()
    err = capsys.readouterr().err
    assert "event=batch_committed" in err
    assert "rows=1" in err
