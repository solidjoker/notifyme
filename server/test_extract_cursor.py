# -*- coding: utf-8 -*-
"""增量游标（state.json）单元测试。运行：pytest server/test_extract_cursor.py -v

覆盖评审 §10.5 的 3-4（提取脚本的增量游标）：
  * 安卓侧游标 = 源库 message.msgId 水位；PC 侧游标 = 每账号每会话
    最大 create_time（秒）；
  * 默认增量、--full 忽略并重置游标；
  * state.json 损坏 / 字段非法 / 版本不认识时自愈（退回全量 + stderr 告警），
    不抛异常终止整轮提取；
  * 去重走 SQLite 侧索引查询（不把整表读进 Python set），跨来源近重复
    （通知渠道 vs 数据库渠道时间戳略有偏差）仍按时间差窗口判重；
  * 中断 / 账号失败时游标不前进。
"""
import hashlib
import json
import os
import sqlite3
import sys

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import android_db_extract as ade

try:
    import pc_db_extract as pde
except Exception:          # pragma: no cover - 工具链目录非法时跳过
    pde = None


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


def _append_source_rows(path, rows):
    con = sqlite3.connect(path)
    con.executemany("INSERT INTO message VALUES (?,?,?,?,?,?,?)", rows)
    con.commit()
    con.close()


def _msg_row(msg_id, create_ms, content="hello", talker="wxid_a", is_send=0):
    return (msg_id, msg_id, 1, is_send, create_ms, talker, content)


def _read_state(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _rows(con):
    return con.execute(
        "SELECT sender, text, timestamp, conversation, is_group, source, pkg"
        " FROM messages ORDER BY id").fetchall()


@pytest.fixture
def env(tmp_path, monkeypatch):
    """把安卓提取脚本的路径常量指向临时目录。"""
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


# ------------------------------------------------------------------
# 安卓侧游标
# ------------------------------------------------------------------

def test_android_first_run_is_full_scan_and_writes_cursor(env):
    """首次运行：没有状态文件 = 全量扫，并把游标写进 state.json。"""
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
    ])
    stats = ade.merge_messages(full=False)
    assert stats["inserted"] == 2
    assert stats["scanned"] == 2
    assert stats["cursor_reset"] is True
    assert stats["cursor_reason"] == "state_missing"
    state = _read_state(env["state"])
    assert state["last_msgId"] == 2
    assert state["version"] == ade.CURSOR_STATE_VERSION
    assert state["source"] == "android-db"
    assert state["cursor_kind"] == "msgId"


def test_android_second_run_only_scans_new_ids(env):
    """第二次运行：只处理 msgId > 游标的新行。"""
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
    ])
    ade.merge_messages(full=False)
    _append_source_rows(env["src"], [_msg_row(3, 1700000002000, content="m3")])
    stats = ade.merge_messages(full=False)
    assert stats["scanned"] == 1          # 只扫到新的一行
    assert stats["inserted"] == 1
    assert stats["cursor_reset"] is False
    assert _read_state(env["state"])["last_msgId"] == 3


def test_android_full_scan_ignores_and_resets_cursor(env):
    """--full：忽略已有游标全量重扫，并把游标重置到实际最大 msgId。"""
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
    ])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "last_msgId": 9999}, f)
    stats = ade.merge_messages(full=True)
    assert stats["scanned"] == 2          # 9999 的假游标被忽略
    assert stats["inserted"] == 2
    assert stats["cursor_reset"] is True
    assert stats["cursor_reason"] == "full_scan"
    state = _read_state(env["state"])
    assert state["last_msgId"] == 2       # 游标被重置
    assert "last_full_run" in state


def test_android_corrupt_state_self_heals_to_full_scan(env, capsys):
    """state.json 损坏：自愈成全量重扫 + stderr 告警，不抛异常。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    with open(env["state"], "w", encoding="utf-8") as f:
        f.write("{ this is not json")
    stats = ade.merge_messages(full=False)
    assert stats["inserted"] == 1
    assert stats["cursor_reset"] is True
    assert "state_unreadable" in stats["cursor_reason"]
    assert "增量状态读取失败" in capsys.readouterr().err
    assert _read_state(env["state"])["last_msgId"] == 1


def test_android_invalid_cursor_field_self_heals(env, capsys):
    """游标字段非法（字符串/负数/布尔）：自愈成全量重扫。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "last_msgId": "abc"}, f)
    stats = ade.merge_messages(full=False)
    assert stats["inserted"] == 1
    assert stats["cursor_reset"] is True
    assert stats["cursor_reason"] == "state_cursor_invalid"
    assert "游标字段非法" in capsys.readouterr().err


def test_android_negative_cursor_self_heals(env):
    """负数游标 = 非法：退回全量重扫，不当成水位用。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "last_msgId": -5}, f)
    stats = ade.merge_messages(full=False)
    assert stats["scanned"] == 1
    assert stats["cursor_reset"] is True


def test_android_unknown_state_version_self_heals(env, capsys):
    """版本不认识（更高版本）：不猜语义，退回全量重扫。"""
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
    ])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 99, "last_msgId": 1}, f)
    stats = ade.merge_messages(full=False)
    assert stats["scanned"] == 2          # 游标被丢弃，整表重扫
    assert stats["cursor_reset"] is True
    assert stats["cursor_reason"] == "state_version_unknown"
    assert "版本不认识" in capsys.readouterr().err


def test_android_legacy_state_without_version_is_migrated(env):
    """老格式状态（没有 version）照常读，并在写回时升级到 version=2。"""
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
        _msg_row(3, 1700000002000, content="m3"),
    ])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"last_msgId": 2, "last_run": "2026-01-01 00:00:00"}, f)
    stats = ade.merge_messages(full=False)
    assert stats["scanned"] == 1          # 老游标 2 仍然生效
    assert stats["cursor_reset"] is False
    state = _read_state(env["state"])
    assert state["version"] == 2
    assert state["last_msgId"] == 3
    assert isinstance(state["last_run"], str) and state["last_run"]


def test_android_cursor_write_read_roundtrip(env):
    """游标写入 / 读取往返：值、版本、来源、游标口径都一致。"""
    ade._save_state({"last_msgId": 42, "last_run": "2026-11-11 00:00:00"})
    value, diag, raw = ade._load_cursor_state()
    assert value == 42
    assert diag["reset"] is False
    assert raw["last_msgId"] == 42
    state = _read_state(env["state"])
    assert state["version"] == ade.CURSOR_STATE_VERSION
    assert state["source"] == "android-db"
    assert state["cursor_kind"] == "msgId"
    assert not os.path.exists(env["state"] + ".tmp")


def test_android_state_keeps_top_level_last_run(env):
    """app.py 落地页读 state.json 顶层 last_run，这个键不能挪走。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    ade.merge_messages(full=False)
    state = _read_state(env["state"])
    assert isinstance(state.get("last_run"), str) and state["last_run"]


def test_android_failed_merge_does_not_advance_cursor(env, monkeypatch):
    """中途异常：已写的行提交掉，但游标不前进（状态文件保持原样）。"""
    # 游标停在 1：本轮要扫 msgId 2/3/4 三行，够触发第 3 次 execute
    _make_source_db(env["src"], [
        _msg_row(1, 1700000000000),
        _msg_row(2, 1700000001000, content="m2"),
        _msg_row(3, 1700000002000, content="m3"),
        _msg_row(4, 1700000003000, content="m4"),
    ])
    with open(env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "last_msgId": 1}, f)

    real_execute = ade._BatchWriter.execute
    calls = {"n": 0}

    def boom(self, sql, params):
        calls["n"] += 1
        if calls["n"] > 2:
            raise sqlite3.OperationalError("boom")
        return real_execute(self, sql, params)

    monkeypatch.setattr(ade._BatchWriter, "execute", boom)
    with pytest.raises(sqlite3.OperationalError):
        ade.merge_messages(full=False)
    # 游标没前进：状态文件仍是异常前的内容
    state = _read_state(env["state"])
    assert state["last_msgId"] == 1


def test_android_cross_source_near_duplicate_still_deduped(env):
    """跨来源近重复（时间戳差几毫秒）仍按时间差窗口判重。"""
    _make_main_db(env["main"], rows=[
        ("wxid_a", "hello", 1700000000000, "wxid_a", 0, 1700000000000,
         "android-notification", "com.tencent.mm"),
    ])
    _make_source_db(env["src"], [_msg_row(1, 1700000000400)])
    stats = ade.merge_messages(full=False)
    assert stats["inserted"] == 0
    assert stats["duplicated"] == 1
    con = sqlite3.connect(env["main"])
    try:
        assert len(_rows(con)) == 1
    finally:
        con.close()


def test_android_dedup_query_uses_sqlite_index(env):
    """去重走 SQLite 侧索引查询，不是把整表读进 Python set。"""
    _make_source_db(env["src"], [_msg_row(1, 1700000000000)])
    ade.merge_messages(full=False)
    con = sqlite3.connect(env["main"])
    try:
        plan = con.execute(
            "EXPLAIN QUERY PLAN SELECT 1 FROM messages WHERE pkg=? AND"
            " conversation=? AND sender=? AND timestamp=? AND text=? LIMIT 1",
            ("com.tencent.mm", "wxid_a", "wxid_a", 1700000000000, "hello"),
        ).fetchall()
    finally:
        con.close()
    detail = " ".join(str(r[-1]) for r in plan)
    assert "INDEX" in detail.upper(), detail
    assert "SCAN messages" not in detail, detail


# ------------------------------------------------------------------
# PC 侧游标
# ------------------------------------------------------------------

def _make_pc_contact_db(path):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE contact (username TEXT, alias TEXT,"
                " remark TEXT, nick_name TEXT)")
    con.executemany("INSERT INTO contact VALUES (?,?,?,?)", [
        ("wxid_me", "me", "我自己", "me"),
        ("wxid_friend", "friend_alias", "好友", "friend_nick"),
    ])
    con.commit()
    con.close()


def _pc_msg_table():
    return "Msg_" + hashlib.md5("wxid_friend".encode()).hexdigest()


def _make_pc_message_db(path, rows):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE Name2Id (rowid INTEGER PRIMARY KEY,"
                " user_name TEXT)")
    con.executemany("INSERT INTO Name2Id VALUES (?,?)", [
        (1, "wxid_me"), (2, "wxid_friend")])
    table = _pc_msg_table()
    con.execute(f'CREATE TABLE "{table}" (real_sender_id INTEGER,'
                " create_time INTEGER, local_type INTEGER,"
                " message_content TEXT)")
    con.executemany(f'INSERT INTO "{table}" VALUES (?,?,?,?)', rows)
    con.commit()
    con.close()


def _pc_add_rows(path, rows):
    con = sqlite3.connect(path)
    table = _pc_msg_table()
    con.executemany(f'INSERT INTO "{table}" VALUES (?,?,?,?)', rows)
    con.commit()
    con.close()


class _FakeWxAccounts:
    def __init__(self, accounts):
        self._accounts = accounts

    def list_accounts(self):
        return [dict(a) for a in self._accounts]

    def keys_file_for(self, acct, db_dir):
        return os.path.join(db_dir, "keys", f"{acct}.json")


def _fake_snapshot(contact_db, msg_db):
    def snapshot_and_decrypt(rel, workdir, account=None):
        path = contact_db if rel == pde.CONTACT_DB_REL else msg_db
        return path, 1, 1
    return snapshot_and_decrypt


def _fake_decompress(content):
    return content if isinstance(content, str) else ""


@pytest.fixture
def pc_env(tmp_path, monkeypatch):
    """把 PC 提取脚本的路径常量与解密工具链指向临时目录。"""
    tmp = str(tmp_path)
    main_db = os.path.join(tmp, "messages.db")
    contact_db = os.path.join(tmp, "contact.db")
    msg_db = os.path.join(tmp, "message_0.db")
    _make_main_db(main_db)
    _make_pc_contact_db(contact_db)
    _make_pc_message_db(msg_db, [(2, 1700000000, 1, "hello")])
    keys = os.path.join(tmp, "keys", "wxid_me.json")
    os.makedirs(os.path.dirname(keys), exist_ok=True)
    with open(keys, "w", encoding="utf-8") as f:
        f.write("{}")
    monkeypatch.setattr(pde, "MAIN_DB", main_db)
    monkeypatch.setattr(pde, "PC_DIR", tmp)
    monkeypatch.setattr(pde, "STATE_PATH", os.path.join(tmp, "state.json"))
    monkeypatch.setattr(pde, "COMMIT_BATCH", 2)
    monkeypatch.setattr(pde, "COMMIT_INTERVAL", 0)
    wx_accounts = _FakeWxAccounts([{"account": "wxid_me", "db_dir": tmp}])
    monkeypatch.setattr(pde, "_import_toolchain", lambda: (
        wx_accounts, _fake_snapshot(contact_db, msg_db), _fake_decompress))
    return {"main": main_db, "msg_db": msg_db, "state": os.path.join(
        tmp, "state.json"), "dir": tmp}


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_first_run_scans_all_and_writes_conversation_cursor(pc_env):
    """PC 首次运行：全量扫会话，游标按「账号 -> 会话 -> 最大 create_time」落盘。"""
    stats = pde.run_extract(full=False)
    assert stats["ok"] is True
    assert stats["inserted"] == 1
    assert stats["scanned"] == 1
    state = _read_state(pc_env["state"])
    assert state["version"] == pde.CURSOR_STATE_VERSION
    assert state["source"] == "pc-db"
    assert state["cursor_kind"] == "conversation_create_time"
    assert state["accounts"]["wxid_me"]["conversations"] == {
        "wxid_friend": 1700000000}


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_second_run_only_reads_new_rows(pc_env):
    """PC 第二次运行：只拉 create_time > 会话游标的新消息。"""
    pde.run_extract(full=False)
    _pc_add_rows(pc_env["msg_db"], [(2, 1700000100, 1, "hello2")])
    stats = pde.run_extract(full=False)
    assert stats["scanned"] == 1
    assert stats["inserted"] == 1
    state = _read_state(pc_env["state"])
    assert state["accounts"]["wxid_me"]["conversations"]["wxid_friend"] == 1700000100


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_full_scan_ignores_and_resets_cursor(pc_env):
    """PC --full：忽略已有会话游标全量重扫，并把游标重置到实际值。"""
    pde.run_extract(full=False)
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "accounts": {"wxid_me": {
            "conversations": {"wxid_friend": 999999999}}}}, f)
    stats = pde.run_extract(full=True)
    assert stats["scanned"] == 1
    assert stats["inserted"] == 0          # 库里已有，去重挡住
    state = _read_state(pc_env["state"])
    assert state["accounts"]["wxid_me"]["conversations"]["wxid_friend"] == 1700000000


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_corrupt_state_self_heals_to_full_scan(pc_env, capsys):
    """PC state.json 损坏：自愈成全量重扫 + stderr 告警，不抛异常。"""
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        f.write("{ broken json")
    stats = pde.run_extract(full=False)
    assert stats["inserted"] == 1
    assert stats["cursor_reset"] is True
    assert "state_unreadable" in stats["cursor_reason"]
    assert "增量状态读取失败" in capsys.readouterr().err


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_invalid_conversation_cursor_is_dropped(pc_env, capsys):
    """会话游标非法：只丢该会话的游标（退回从头扫），其余照常。"""
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "accounts": {"wxid_me": {
            "conversations": {"wxid_friend": "abc", "wxid_other": 5}}}}, f)
    stats = pde.run_extract(full=False)
    assert stats["scanned"] == 1
    assert stats["cursor_reset"] is False
    assert "会话游标非法" in capsys.readouterr().err
    state = _read_state(pc_env["state"])
    assert state["accounts"]["wxid_me"]["conversations"] == {
        "wxid_friend": 1700000000, "wxid_other": 5}


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_account_node_not_dict_is_dropped(pc_env, capsys):
    """账号节点不是 dict：丢弃该账号游标，不让整轮崩掉。"""
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "accounts": {"wxid_me": "oops"}}, f)
    stats = pde.run_extract(full=False)
    assert stats["inserted"] == 1
    assert "账号节点异常" in capsys.readouterr().err


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_unknown_state_version_self_heals(pc_env, capsys):
    """PC 状态版本不认识：退回全量重扫。"""
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 99, "accounts": {"wxid_me": {
            "conversations": {"wxid_friend": 1700000000}}}}, f)
    stats = pde.run_extract(full=False)
    assert stats["cursor_reset"] is True
    assert stats["cursor_reason"] == "state_version_unknown"
    assert "版本不认识" in capsys.readouterr().err


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_account_failure_keeps_old_cursor(pc_env, monkeypatch):
    """账号提取失败：旧游标保持不变（不前进），状态文件不写。"""
    with open(pc_env["state"], "w", encoding="utf-8") as f:
        json.dump({"version": 2, "accounts": {"wxid_me": {
            "conversations": {"wxid_friend": 1700000000}}}}, f)
    before = _read_state(pc_env["state"])

    def boom(rel, workdir, account=None):
        raise RuntimeError("解密炸了")

    wx_accounts = _FakeWxAccounts([{"account": "wxid_me",
                                    "db_dir": pc_env["dir"]}])
    monkeypatch.setattr(pde, "_import_toolchain", lambda: (
        wx_accounts, boom, _fake_decompress))
    with pytest.raises(pde.ExtractError):
        pde.run_extract(full=False)
    # 状态文件没被覆盖：旧游标还在
    assert _read_state(pc_env["state"]) == before


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_state_roundtrip_keeps_accounts_and_version(pc_env):
    """PC 游标写入 / 读取往返：accounts、version、来源、游标口径都一致。"""
    pde._save_state({"accounts": {"wxid_me": {
        "conversations": {"wxid_friend": 1700000000}}}})
    state = _read_state(pc_env["state"])
    assert state["accounts"]["wxid_me"]["conversations"]["wxid_friend"] == 1700000000
    assert state["version"] == pde.CURSOR_STATE_VERSION
    assert state["source"] == "pc-db"
    assert state["cursor_kind"] == "conversation_create_time"
    diag = {}
    loaded = pde._load_state(diag=diag)
    assert loaded["accounts"]["wxid_me"]["conversations"] == {
        "wxid_friend": 1700000000}
    assert diag["reset"] is False
    assert not os.path.exists(pc_env["state"] + ".tmp")


@pytest.mark.skipif(pde is None, reason="pc_db_extract 不可导入")
def test_pc_cross_source_near_duplicate_still_deduped(pc_env):
    """PC 跨来源近重复（通知渠道 vs 数据库渠道时间戳差几毫秒）仍判重。"""
    _make_main_db(pc_env["main"], rows=[
        ("好友", "hello", 1700000000400, "好友", 0, 1700000000400,
         "android-notification", "com.tencent.mm"),
    ])
    stats = pde.run_extract(full=False)
    assert stats["inserted"] == 0
    assert stats["duplicated"] == 1
