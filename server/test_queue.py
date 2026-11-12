# -*- coding: utf-8 -*-
"""排队任务接口（/queue）测试。运行：pytest server/test_queue.py -v"""
import os
import shutil
import sqlite3
import sys
import tempfile
import time

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import app as app_module
import storage as storage_module

flask_app = app_module.app  # Flask 实例（不是模块）

DEVICE = "android-test-0001"


@pytest.fixture
def client():
    """Flask test client + 隔离的临时数据目录。"""
    flask_app.config["TESTING"] = True
    tmp = tempfile.mkdtemp(prefix="notifyme_queue_")
    saved = {
        "DATA_DIR": app_module.DATA_DIR,
        "DB_PATH": app_module.DB_PATH,
        "JSONL_PATH": app_module.JSONL_PATH,
        "ADVISOR_PATH": app_module.ADVISOR_PATH,
        "TOKEN": app_module.TOKEN,
        "ALLOW_NO_AUTH": app_module.ALLOW_NO_AUTH,
        "_db_ready": app_module._db_ready,
    }
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "test.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    app_module.init_db()
    with flask_app.test_client() as c:
        yield c
    for key, value in saved.items():
        setattr(app_module, key, value)
    # 复位成「未初始化」：下一个用例换了 DB_PATH 时要重新建表
    app_module._db_ready = False
    shutil.rmtree(tmp, ignore_errors=True)


def _task(task_id="t1", kind="analysis", title="群名", state="queued",
          detail="", queued_at=1768000000000):
    """一条合法的排队任务（不含 device_id，device_id 由请求体顶层给出）。"""
    return {
        "task_id": task_id,
        "kind": kind,
        "title": title,
        "state": state,
        "detail": detail,
        "queued_at": queued_at,
    }


def _post(client, tasks, device_id=DEVICE):
    return client.post("/queue", json={"device_id": device_id, "tasks": tasks})


def _rows():
    """直接读库看行（不依赖接口返回，确认真的落库了）。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        return conn.execute(
            "SELECT device_id, task_id, kind, title, state, detail,"
            " queued_at, updated_at FROM queue_tasks").fetchall()
    finally:
        conn.close()


def _row_count():
    return len(_rows())


def _insert_row(device_id, task_id, updated_at, state="queued", kind="analysis",
                title="", detail="", queued_at=1768000000000):
    """绕过接口直接塞一行（造过期 / 超期数据用）。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        with conn:
            conn.execute(
                "INSERT INTO queue_tasks (device_id, task_id, kind, title, state,"
                " detail, queued_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (device_id, task_id, kind, title, state, detail, queued_at,
                 updated_at))
    finally:
        conn.close()


def _insert_rows_bulk(device_id, count, updated_at):
    """一次插多行（测 limit 上限用，别一条一条开连接）。"""
    conn = sqlite3.connect(app_module.DB_PATH)
    try:
        with conn:
            conn.executemany(
                "INSERT INTO queue_tasks (device_id, task_id, kind, title, state,"
                " detail, queued_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                [(device_id, "bulk-%04d" % i, "analysis", "", "queued", "", 0,
                  updated_at - i) for i in range(count)])
    finally:
        conn.close()


# ---------------------------------------------------------------- 鉴权

def test_post_without_token_401(client):
    app_module.TOKEN = "secret-token"
    r = _post(client, [_task()])
    assert r.status_code == 401


def test_get_without_token_401(client):
    app_module.TOKEN = "secret-token"
    r = client.get("/queue")
    assert r.status_code == 401


# ---------------------------------------------------------------- 请求体校验

def test_post_body_not_object_400(client):
    r = client.post("/queue", json=["not-an-object"])
    assert r.status_code == 400
    assert r.get_json()["ok"] is False


def test_post_tasks_not_array_400(client):
    r = client.post("/queue", json={"device_id": DEVICE,
                                    "tasks": {"task_id": "t1"}})
    assert r.status_code == 400
    assert r.get_json()["ok"] is False


def test_post_missing_device_id_400(client):
    r = client.post("/queue", json={"tasks": [_task()]})
    assert r.status_code == 400
    assert r.get_json()["ok"] is False


def test_post_device_id_too_long_400(client):
    r = client.post("/queue", json={"device_id": "d" * 65, "tasks": [_task()]})
    assert r.status_code == 400


def test_post_empty_tasks_array_ok(client):
    r = _post(client, [])
    assert r.status_code == 200
    body = r.get_json()
    assert body["accepted"] == 0
    assert body["rejected"] == []


# ---------------------------------------------------------------- 逐条拒收

def test_invalid_kind_rejected_but_others_written(client):
    r = _post(client, [_task(task_id="ok-1"),
                        _task(task_id="bad-kind", kind="unknown")])
    assert r.status_code == 200
    body = r.get_json()
    assert body["accepted"] == 1
    assert len(body["rejected"]) == 1
    assert body["rejected"][0]["index"] == 1
    assert body["rejected"][0]["reason"]
    assert _row_count() == 1


def test_invalid_state_rejected_but_others_written(client):
    r = _post(client, [_task(task_id="ok-1"),
                        _task(task_id="bad-state", state="paused")])
    body = r.get_json()
    assert body["accepted"] == 1
    assert body["rejected"][0]["index"] == 1
    assert _row_count() == 1


def test_empty_task_id_rejected(client):
    r = _post(client, [_task(task_id="")])
    body = r.get_json()
    assert body["accepted"] == 0
    assert body["rejected"][0]["index"] == 0
    assert _row_count() == 0


def test_task_id_too_long_rejected(client):
    r = _post(client, [_task(task_id="x" * 129)])
    body = r.get_json()
    assert body["accepted"] == 0
    assert body["rejected"][0]["index"] == 0
    assert _row_count() == 0


def test_title_too_long_rejected_not_truncated(client):
    r = _post(client, [_task(title="x" * 257)])
    body = r.get_json()
    assert body["accepted"] == 0
    assert body["rejected"][0]["index"] == 0
    assert _row_count() == 0


def test_detail_too_long_rejected_not_truncated(client):
    r = _post(client, [_task(detail="x" * 513)])
    body = r.get_json()
    assert body["accepted"] == 0
    assert body["rejected"][0]["index"] == 0
    assert _row_count() == 0


def test_queued_at_out_of_range_rejected(client):
    r = _post(client, [_task(task_id="too-big", queued_at=4102444800001),
                        _task(task_id="negative", queued_at=-1)])
    body = r.get_json()
    assert body["accepted"] == 0
    assert [x["index"] for x in body["rejected"]] == [0, 1]
    assert _row_count() == 0


def test_non_object_task_entry_rejected_by_index(client):
    r = _post(client, ["not-an-object", _task(task_id="ok")])
    body = r.get_json()
    assert body["accepted"] == 1
    assert body["rejected"][0]["index"] == 0
    assert _row_count() == 1


# ---------------------------------------------------------------- 幂等与隔离

def test_duplicate_task_id_is_idempotent(client):
    _post(client, [_task(task_id="dup", state="queued", title="旧标题")])
    r = _post(client, [_task(task_id="dup", state="running", title="新标题")])
    assert r.get_json()["accepted"] == 1
    assert _row_count() == 1
    row = _rows()[0]
    assert row[3] == "新标题"
    assert row[4] == "running"


def test_different_device_ids_are_independent(client):
    _post(client, [_task(task_id="t1")], device_id="android-a")
    _post(client, [_task(task_id="t1")], device_id="android-b")
    assert _row_count() == 2
    tasks = client.get("/queue?device_id=android-a").get_json()["tasks"]
    assert len(tasks) == 1
    assert tasks[0]["device_id"] == "android-a"


# ---------------------------------------------------------------- 查询与过滤

def test_get_filters_by_device_id(client):
    _post(client, [_task(task_id="a1")], device_id="android-a")
    _post(client, [_task(task_id="b1")], device_id="android-b")
    tasks = client.get("/queue?device_id=android-b").get_json()["tasks"]
    assert [t["task_id"] for t in tasks] == ["b1"]


def test_get_filters_by_state(client):
    _post(client, [_task(task_id="q", state="queued"),
                   _task(task_id="r", state="running")])
    tasks = client.get("/queue?state=running").get_json()["tasks"]
    assert [t["task_id"] for t in tasks] == ["r"]


def test_get_invalid_state_returns_empty_list(client):
    _post(client, [_task()])
    r = client.get("/queue?state=bogus")
    assert r.status_code == 200
    body = r.get_json()
    assert body["ok"] is True
    assert body["tasks"] == []
    assert body["summary"]["total"] == 0


def test_get_limit_truncates(client):
    for i in range(5):
        _post(client, [_task(task_id="t%d" % i)])
    tasks = client.get("/queue?limit=2").get_json()["tasks"]
    assert len(tasks) == 2


def test_get_limit_clamped_to_500(client):
    now = int(time.time() * 1000)
    _insert_rows_bulk(DEVICE, 520, now)
    tasks = client.get("/queue?limit=9999").get_json()["tasks"]
    assert len(tasks) == 500


def test_storage_query_limit_clamped_to_500(client):
    now = int(time.time() * 1000)
    _insert_rows_bulk(DEVICE, 520, now)
    tasks = storage_module.query_queue_tasks(None, None, 9999)
    assert len(tasks) == 500


def test_get_empty_table_returns_zero_summary(client):
    r = client.get("/queue")
    assert r.status_code == 200
    body = r.get_json()
    assert body["ok"] is True
    assert body["tasks"] == []
    assert body["summary"] == {"queued": 0, "running": 0, "failed": 0, "done": 0,
                               "stale": 0, "total": 0}


# ---------------------------------------------------------------- summary / stale

def test_summary_counts_each_state(client):
    _post(client, [_task(task_id="q1", state="queued"),
                   _task(task_id="r1", state="running"),
                   _task(task_id="f1", state="failed"),
                   _task(task_id="d1", state="done")])
    summary = client.get("/queue").get_json()["summary"]
    assert summary["queued"] == 1
    assert summary["running"] == 1
    assert summary["failed"] == 1
    assert summary["done"] == 1
    assert summary["stale"] == 0
    assert summary["total"] == 4


def test_stale_task_excluded_from_state_counts(client):
    now = int(time.time() * 1000)
    _post(client, [_task(task_id="fresh", state="queued")])
    _insert_row(DEVICE, "old", now - storage_module.QUEUE_TTL_MS - 60000,
                state="running")
    body = client.get("/queue").get_json()
    assert body["summary"]["stale"] == 1
    assert body["summary"]["running"] == 0
    assert body["summary"]["queued"] == 1
    assert body["summary"]["total"] == 2
    by_id = {t["task_id"]: t for t in body["tasks"]}
    assert by_id["old"]["stale"] is True
    assert by_id["fresh"]["stale"] is False


def test_recent_task_is_not_stale(client):
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "recent", now - storage_module.QUEUE_TTL_MS + 60000,
                state="queued")
    body = client.get("/queue").get_json()
    assert body["tasks"][0]["stale"] is False
    assert body["summary"]["queued"] == 1
    assert body["summary"]["stale"] == 0


# ---------------------------------------------------------------- 保留期清理

def test_old_rows_cleaned_on_write(client):
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "ancient", now - 8 * 24 * 60 * 60 * 1000)
    assert _row_count() == 1
    _post(client, [_task(task_id="fresh")])
    rows = _rows()
    assert [r[1] for r in rows] == ["fresh"]


# ---------------------------------------------------------------- 字段回显

def test_task_fields_roundtrip(client):
    _post(client, [_task(task_id="rt", kind="sync", title="标题",
                          state="failed", detail="细节",
                          queued_at=1768000000000)])
    task = client.get("/queue").get_json()["tasks"][0]
    assert task["device_id"] == DEVICE
    assert task["task_id"] == "rt"
    assert task["kind"] == "sync"
    assert task["title"] == "标题"
    assert task["state"] == "failed"
    assert task["detail"] == "细节"
    assert task["queued_at"] == 1768000000000
    assert task["updated_at"] > 0
    assert task["stale"] is False


def test_storage_upsert_returns_accepted_and_rejected(client):
    now = int(time.time() * 1000)
    items = [
        {"device_id": "dev-1", "task_id": "ok", "kind": "model",
         "title": "t", "state": "done", "detail": "", "queued_at": 0},
        {"device_id": "dev-1", "task_id": "", "kind": "model",
         "title": "t", "state": "done", "detail": "", "queued_at": 0},
    ]
    accepted, rejected = storage_module.upsert_queue_tasks(items, now)
    assert accepted == 1
    assert rejected == [{"index": 1, "reason": rejected[0]["reason"]}]
