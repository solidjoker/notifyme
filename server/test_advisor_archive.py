# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""顾问存档：跨段读取 + 统一容量收敛 + 裁剪留痕。

背景：老版本在主段超过字节上限时把主段轮转成 advisor_reports.jsonl.1，
但读取侧只读主段 —— 轮转后老报告在控制台整体消失、seen 去重集合漏掉轮转段
（同一个 report_id 会被重复入库）、GET /advisor 的 total 也偏小。

本文件钉五件事：
1. 两段存档一起读（老 → 新），轮转段里的老报告不能消失；
2. 同一 report_id 出现在两段时只算一份（保留最后写入的那条）；
3. 容量上限（条数 / 字节）统一由 _trim_advisor_jsonl 收敛，裁掉的份数写 meta 留痕；
4. 裁剪后存档合并回单文件，轮转段不再残留；
5. 存档损坏时仍然拒绝写入（既有语义不变）。
"""
import json
import os
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import app as flask_app  # noqa: E402


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_bool_readout.py 同款做法）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_advisor_")
    old = (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
           app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
           app_module._db_ready, app_module._queue_cleanup_at)
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "messages.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor_reports.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    app_module._queue_cleanup_at = 0
    app_module.init_db()
    app_module._auth_fail_events.clear()
    yield flask_app.test_client()
    (app_module.DATA_DIR, app_module.DB_PATH, app_module.JSONL_PATH,
     app_module.ADVISOR_PATH, app_module.TOKEN, app_module.ALLOW_NO_AUTH,
     app_module._db_ready, app_module._queue_cleanup_at) = old
    app_module._db_ready = False


def _report(rid, summary="摘要", created="2026-11-16 10:00:00"):
    return {
        "report_id": rid,
        "created_at": created,
        "period_days": 7,
        "case_count": 3,
        "summary": summary,
        "suggestions": [{"type": "watchlist", "title": "关注 " + rid, "detail": "细节"}],
        "model_used": "test-model",
    }


def _write_segment(path, records):
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def _main_path():
    return app_module._advisor_jsonl_path()


def _rotated_path():
    return _main_path() + app_module.ADVISOR_ROTATED_SUFFIX


def _ids(reports):
    return sorted(r.get("report_id") for r in reports)


def test_rotated_segment_is_read_back(api):
    """轮转段里的老报告必须一起读出来（老 → 新）。"""
    _write_segment(_rotated_path(), [_report("r1")])
    _write_segment(_main_path(), [_report("r2")])
    records = app_module._load_advisor_reports()
    assert [r["report_id"] for r in records] == ["r1", "r2"], records


def test_same_report_id_in_two_segments_counts_once(api):
    """同一个 report_id 落在两段时只算一份（保留最后写入的那条）。"""
    _write_segment(_rotated_path(), [_report("r1", "老")])
    _write_segment(_main_path(), [_report("r1", "新")])
    records = app_module._load_advisor_reports()
    assert len(records) == 1, records
    assert records[0]["summary"] == "新", records


def test_post_after_rotation_keeps_history(api):
    """轮转过之后再来新报告，老报告不能从控制台消失。"""
    _write_segment(_rotated_path(), [_report("r1")])
    _write_segment(_main_path(), [_report("r2")])
    resp = api.post("/advisor", json=[_report("r3")])
    assert resp.status_code == 200, resp.get_data(as_text=True)
    got = api.get("/advisor?limit=100").get_json()
    assert _ids(got["reports"]) == ["r1", "r2", "r3"], got
    assert got["total"] == 3, got


def test_repost_of_rotated_id_is_duplicated(api):
    """轮转段里的 report_id 再报一次必须算 duplicated（不能重复入库）。"""
    _write_segment(_rotated_path(), [_report("r1")])
    _write_segment(_main_path(), [_report("r2")])
    body = api.post("/advisor", json=[_report("r1")]).get_json()
    assert body["duplicated"] == 1, body
    assert body["inserted"] == 0, body
    got = api.get("/advisor?limit=100").get_json()
    assert got["total"] == 2, got


def test_count_cap_drops_oldest_and_leaves_trace(api, monkeypatch):
    """条数超上限：丢最旧的，并把裁掉的份数留痕（dropped / dropped_at）。"""
    monkeypatch.setattr(app_module, "ADVISOR_MAX_REPORTS", 3)
    for i in range(5):
        assert api.post("/advisor", json=[_report("r%d" % i)]).status_code == 200
    got = api.get("/advisor?limit=100").get_json()
    assert _ids(got["reports"]) == ["r2", "r3", "r4"], got
    assert got["dropped"] == 2, got
    assert got["dropped_at"] > 0, got
    meta = app_module._read_advisor_meta()
    assert meta.get("dropped") == 2, meta


def test_byte_cap_trims_and_keeps_at_least_one(api, monkeypatch):
    """字节上限也要收敛；单条就超限时至少保留一条（不把存档清空）。"""
    monkeypatch.setattr(app_module, "ADVISOR_MAX_BYTES", 400)
    api.post("/advisor", json=[_report("r1", "x" * 400)])
    api.post("/advisor", json=[_report("r2", "y" * 400)])
    got = api.get("/advisor?limit=100").get_json()
    assert got["total"] >= 1, got
    assert got["dropped"] >= 1, got


def test_trim_merges_rotated_segment_into_main(api):
    """裁剪时把两段合并回主段，轮转段不再残留（下次不会再读两段）。"""
    _write_segment(_rotated_path(), [_report("r1")])
    _write_segment(_main_path(), [_report("r2")])
    assert os.path.exists(_rotated_path())
    api.post("/advisor", json=[_report("r3")])
    assert not os.path.exists(_rotated_path()), "轮转段没有被合并掉"
    records = app_module._load_advisor_reports()
    assert [r["report_id"] for r in records] == ["r1", "r2", "r3"], records


def test_append_does_not_rotate(api, monkeypatch):
    """追加路径不再轮转（轮转就是那次静默丢历史的根源）。"""
    monkeypatch.setattr(app_module, "ADVISOR_MAX_BYTES", 10)
    api.post("/advisor", json=[_report("r1", "x" * 200)])
    assert not os.path.exists(_rotated_path()), "追加路径又写回了轮转段"


def test_no_rewrite_while_within_limits(api):
    """没超上限时不整份重写（追加保持 O(1)）。"""
    api.post("/advisor", json=[_report("r1")])
    size_before = os.path.getsize(_main_path())
    api.post("/advisor", json=[_report("r2")])
    assert os.path.getsize(_main_path()) > size_before
    assert app_module._advisor_needs_trim(app_module._load_advisor_reports()) is False


def test_limits_and_dropped_are_returned(api):
    """接口把容量上限与裁掉份数一起回给前端（前端不自己写死数字）。"""
    got = api.get("/advisor").get_json()
    assert got["limits"]["max_reports"] == app_module.ADVISOR_MAX_REPORTS, got
    assert got["limits"]["max_bytes"] == app_module.ADVISOR_MAX_BYTES, got
    assert got["dropped"] == 0, got
    assert got["dropped_at"] == 0, got


def test_corrupt_rotated_segment_refuses_write(api):
    """轮转段损坏时同样拒绝写入（不拿空列表整份覆盖）。"""
    with open(_rotated_path(), "w", encoding="utf-8") as f:
        f.write("{不是 json}\n")
    resp = api.post("/advisor", json=[_report("r1")])
    assert resp.status_code == 500, resp.get_data(as_text=True)
    assert not os.path.exists(_main_path()), "损坏时不该写出新的主段"


def test_dedupe_helper_keeps_last_and_keeps_idless():
    """去重：同 id 留最后写入的那条（并落在它最后写入的位置）；无 report_id 的记录原样保留。"""
    out = app_module._dedupe_advisor_records([
        {"report_id": "a", "v": 1},
        {"report_id": "b"},
        {"report_id": "a", "v": 2},
        {"x": 1},
    ])
    assert [r.get("report_id") for r in out] == ["b", "a", None], out
    assert out[1]["v"] == 2, out
    assert out[2] == {"x": 1}, out


def test_fit_advisor_bytes_keeps_at_least_one(monkeypatch):
    """单条就超字节上限时至少留一条（清空存档等于又一次静默丢历史）。"""
    monkeypatch.setattr(app_module, "ADVISOR_MAX_BYTES", 10)
    kept = app_module._fit_advisor_bytes([{"a": "x" * 100}, {"b": 2}])
    assert len(kept) == 1, kept


def test_needs_trim_triggers_on_count_and_rotation(api):
    """触发条件：条数超上限 / 存在轮转段。"""
    assert app_module._advisor_needs_trim([1, 2, 3]) is False
    _write_segment(_rotated_path(), [_report("r1")])
    assert app_module._advisor_needs_trim([1]) is True
