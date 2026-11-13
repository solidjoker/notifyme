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
        "_queue_cleanup_at": app_module._queue_cleanup_at,
    }
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "test.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor.json")
    app_module.TOKEN = ""
    app_module.ALLOW_NO_AUTH = "1"
    app_module._db_ready = False
    # 清理节流时间戳是进程级的：不复位的话上一条用例刚清过，这一条就不清了，
    # 「无写入也会清」的用例会随执行顺序飘。
    app_module._queue_cleanup_at = 0
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
    # summary 新增 devices（库里一共几台设备），其余字段与原来一字不差
    assert body["summary"] == {"queued": 0, "running": 0, "failed": 0, "done": 0,
                               "stale": 0, "total": 0, "devices": 0}


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

def test_old_rows_cleaned_without_any_write(client):
    """没有设备再上报时，读取路径也会按保留期清行。

    以前清理只写在 upsert_queue_tasks 里：所有设备停报（换机 / 卸载 /
    关掉排队上报）之后过期行永远是脏数据，devices 一直挂着僵尸设备。
    现在 before_request 按 QUEUE_CLEANUP_INTERVAL_MS 节流清一次。
    """
    now = int(time.time() * 1000)
    _insert_row("android-gone", "ancient", now - 8 * 24 * 60 * 60 * 1000,
                state="running")
    assert _row_count() == 1
    body = client.get("/queue").get_json()
    assert _row_count() == 0
    assert body["tasks"] == []
    assert body["devices"] == []
    assert body["summary"]["total"] == 0


def test_rows_within_retention_survive_cleanup(client):
    """保留期内的过期行不清：它是「端上大概停了」的证据，还要给用户看。"""
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "stale-but-kept",
                now - storage_module.QUEUE_TTL_MS - 60 * 1000,
                state="running")
    body = client.get("/queue").get_json()
    assert [t["task_id"] for t in body["tasks"]] == ["stale-but-kept"]
    assert body["tasks"][0]["stale"] is True
    assert body["summary"]["stale"] == 1


def test_cleanup_is_throttled_by_interval(client):
    """清理按小时节流：刚清过就不再扫全表（updated_at 上没有索引）。"""
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "ancient-1", now - 8 * 24 * 60 * 60 * 1000)
    client.get("/queue")
    assert _row_count() == 0
    # 刚清过：再请求一次不会重复扫，新插的老行这轮留着
    _insert_row(DEVICE, "ancient-2", now - 8 * 24 * 60 * 60 * 1000)
    client.get("/queue")
    assert _row_count() == 1
    # 节流过期后（模拟一小时过去）再请求就会清
    app_module._queue_cleanup_at = now - storage_module.QUEUE_CLEANUP_INTERVAL_MS - 1
    client.get("/queue")
    assert _row_count() == 0


def test_cleanup_failure_does_not_break_requests(client, monkeypatch):
    """清理抛异常只记日志，不能把整页请求打成 500。"""
    def boom(now_ms=None):
        raise RuntimeError("cleanup exploded")
    # app.py 在 import 时把函数绑进了自己的命名空间，要 patch app 模块那个名字。
    monkeypatch.setattr(app_module, "cleanup_queue_tasks", boom)
    app_module._queue_cleanup_at = 0
    r = client.get("/queue")
    assert r.status_code == 200, r.get_data(as_text=True)


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

# ---------------------------------------------------------------- 设备清单（devices）

def _devices_by_id(devices):
    """把 devices 数组转成 {device_id: {"total":.., "active":..}} 便于断言。"""
    return {d["device_id"]: {"total": d["total"], "active": d["active"]}
            for d in devices}


def test_devices_is_array_and_summary_counts_devices(client):
    """devices 必须是数组，且 summary.devices == len(devices)。"""
    _post(client, [_task(task_id="a1")], device_id="android-a")
    _post(client, [_task(task_id="b1")], device_id="android-b")
    body = client.get("/queue").get_json()
    assert isinstance(body["devices"], list)
    assert body["summary"]["devices"] == len(body["devices"]) == 2


def test_devices_lists_both_devices_with_correct_totals(client):
    """两台设备上报后都在清单里，total / active 各自算对。"""
    _post(client, [_task(task_id="q1", state="queued"),
                   _task(task_id="r1", state="running"),
                   _task(task_id="d1", state="done")], device_id="android-a")
    _post(client, [_task(task_id="q2", state="queued")], device_id="android-b")
    devices = client.get("/queue").get_json()["devices"]
    got = _devices_by_id(devices)
    assert set(got) == {"android-a", "android-b"}
    assert got["android-a"] == {"total": 3, "active": 2}
    assert got["android-b"] == {"total": 1, "active": 1}


def test_devices_active_counts_only_queued_and_running(client):
    """active 只算排队中 / 运行中；failed / done 不算。"""
    _post(client, [_task(task_id="q", state="queued"),
                   _task(task_id="r", state="running"),
                   _task(task_id="f", state="failed"),
                   _task(task_id="d", state="done")], device_id="android-a")
    devices = client.get("/queue").get_json()["devices"]
    assert _devices_by_id(devices)["android-a"] == {"total": 4, "active": 2}


def test_devices_reflect_all_devices_when_filtering_by_device_id(client):
    """按 device_id 过滤只影响 tasks；devices 仍反映库里全部设备。"""
    _post(client, [_task(task_id="a1")], device_id="android-a")
    _post(client, [_task(task_id="b1")], device_id="android-b")
    body = client.get("/queue?device_id=android-b").get_json()
    # tasks 只剩被筛的那台设备
    assert [t["device_id"] for t in body["tasks"]] == ["android-b"]
    # devices 仍是库里全部两台设备
    assert set(_devices_by_id(body["devices"])) == {"android-a", "android-b"}
    assert body["summary"]["devices"] == 2


def test_devices_unchanged_when_filtering_by_state(client):
    """按 state 过滤：devices 不变（设备维度与状态过滤解耦）。"""
    _post(client, [_task(task_id="q", state="queued"),
                   _task(task_id="r", state="running")], device_id="android-a")
    _post(client, [_task(task_id="q2", state="queued")], device_id="android-b")
    all_body = client.get("/queue").get_json()
    filtered = client.get("/queue?state=running").get_json()
    assert filtered["devices"] == all_body["devices"]
    assert filtered["summary"]["devices"] == 2


def test_devices_empty_on_empty_db(client):
    """空库：devices == []，summary.devices == 0。"""
    body = client.get("/queue").get_json()
    assert body["devices"] == []
    assert body["summary"]["devices"] == 0


def test_devices_ordering_is_deterministic(client):
    """排序：active 倒序 -> total 倒序 -> device_id 升序。"""
    _post(client, [_task(task_id="a1", state="queued"),
                   _task(task_id="a2", state="running")], device_id="android-a")
    _post(client, [_task(task_id="b1", state="queued"),
                   _task(task_id="b2", state="done"),
                   _task(task_id="b3", state="failed")], device_id="android-b")
    _post(client, [_task(task_id="c1", state="queued")], device_id="android-c")
    _post(client, [_task(task_id="d%d" % i, state="done") for i in range(5)],
          device_id="android-d")
    devices = client.get("/queue").get_json()["devices"]
    assert [d["device_id"] for d in devices] == [
        "android-a", "android-b", "android-c", "android-d"]
    assert [d["active"] for d in devices] == [2, 1, 1, 0]


def test_devices_ordering_stable_across_calls(client):
    """同样的库重复查，顺序完全一致（可复现，不受返回顺序抖动影响）。"""
    _post(client, [_task(task_id="a1")], device_id="android-b")
    _post(client, [_task(task_id="b1")], device_id="android-a")
    _post(client, [_task(task_id="c1")], device_id="android-c")
    first = client.get("/queue").get_json()["devices"]
    second = client.get("/queue").get_json()["devices"]
    assert first == second
    assert [d["device_id"] for d in first] == ["android-a", "android-b", "android-c"]


def test_devices_with_prototype_like_device_ids(client):
    """原型键风格设备名（__proto__ / constructor / toString）不炸库、不串数据。"""
    _post(client, [_task(task_id="p1")], device_id="__proto__")
    _post(client, [_task(task_id="p2")], device_id="constructor")
    _post(client, [_task(task_id="p3")], device_id="toString")
    _post(client, [_task(task_id="n1")], device_id="android-normal")
    body = client.get("/queue").get_json()
    got = _devices_by_id(body["devices"])
    assert set(got) == {"__proto__", "constructor", "toString", "android-normal"}
    assert got["__proto__"] == {"total": 1, "active": 1}
    # 按原型键设备名过滤：只回自己的任务，不串到别的设备
    filtered = client.get("/queue?device_id=__proto__").get_json()
    assert [t["task_id"] for t in filtered["tasks"]] == ["p1"]
    assert filtered["summary"]["devices"] == 4


def test_get_invalid_state_does_not_500_and_keeps_devices(client):
    """非法 state：不能 500；devices 依然反映库里的设备。"""
    _post(client, [_task(task_id="q1")], device_id="android-a")
    r = client.get("/queue?state=bogus")
    assert r.status_code == 200
    body = r.get_json()
    assert body["tasks"] == []
    assert body["summary"]["total"] == 0
    assert set(_devices_by_id(body["devices"])) == {"android-a"}


def test_limit_99999_clamped_to_max_limit(client):
    """limit=99999 被夹到 QUEUE_MAX_LIMIT；tasks 截断，summary 与 devices 不截断。"""
    now = int(time.time() * 1000)
    _insert_rows_bulk(DEVICE, 520, now)
    body = client.get("/queue?limit=99999").get_json()
    assert len(body["tasks"]) == storage_module.QUEUE_MAX_LIMIT
    # summary 是全库口径：不能跟着 limit 一起被截成 500（真机 260 条任务
    # 时列表页统计 200、统计 Tab 260，就是按页统计造成的）
    assert body["summary"]["total"] == 520
    assert body["summary"]["queued"] == 520
    # devices 统计的是全库，不被 limit 截断
    assert _devices_by_id(body["devices"])[DEVICE] == {"total": 520, "active": 520}


def test_summary_counts_whole_table_not_current_page(client):
    """summary 走全库口径：任务超一页时总数不能停在默认 limit（200）。

    真机复现：库里 260 条排队任务时，GET /queue（默认 limit=200）的
    summary.total 是 200，而同一响应的 devices[].total 与 /stats 的
    queue.total 都是 260 —— 控制台「排队」Tab 概览条与「统计」Tab 对不上。
    """
    now = int(time.time() * 1000)
    _insert_rows_bulk(DEVICE, 260, now)
    body = client.get("/queue").get_json()
    assert len(body["tasks"]) == storage_module.QUEUE_DEFAULT_LIMIT
    assert body["summary"]["total"] == 260
    assert body["summary"]["queued"] == 260
    # 与同一响应的设备维度、以及 /stats 的 queue 段三方一致
    assert _devices_by_id(body["devices"])[DEVICE] == {"total": 260, "active": 260}
    stats = client.get("/stats").get_json()["queue"]
    assert stats["total"] == body["summary"]["total"]
    assert stats["queued"] == body["summary"]["queued"]
    assert stats["active"] == 260


def test_summary_respects_filters_but_devices_does_not(client):
    """summary 跟着 device_id / state 过滤走，devices 恒为全库。"""
    now = int(time.time() * 1000)
    _post(client, [_task(task_id="a-q", state="queued"),
                   _task(task_id="a-d", state="done")], device_id="android-a")
    _post(client, [_task(task_id="b-r", state="running")], device_id="android-b")
    by_dev = client.get("/queue?device_id=android-a").get_json()
    assert by_dev["summary"]["total"] == 2
    assert by_dev["summary"]["queued"] == 1
    assert by_dev["summary"]["done"] == 1
    assert by_dev["summary"]["running"] == 0
    # 设备清单不受 device_id 过滤影响
    assert set(_devices_by_id(by_dev["devices"])) == {"android-a", "android-b"}
    assert by_dev["summary"]["devices"] == 2
    by_state = client.get("/queue?state=running").get_json()
    assert [t["task_id"] for t in by_state["tasks"]] == ["b-r"]
    assert by_state["summary"]["total"] == 1
    assert by_state["summary"]["running"] == 1
    assert by_state["summary"]["queued"] == 0
    assert by_state["summary"]["devices"] == 2


def test_summary_excludes_stale_from_state_counts_over_whole_table(client):
    """过期条目只计 stale，不计状态 —— 跨页时同样成立。"""
    now = int(time.time() * 1000)
    _insert_rows_bulk(DEVICE, 240, now)
    for i in range(20):
        _insert_row(DEVICE, "old-%02d" % i, now - storage_module.QUEUE_TTL_MS - 60000,
                    state="running")
    body = client.get("/queue?limit=100").get_json()
    assert len(body["tasks"]) == 100
    assert body["summary"]["total"] == 260
    assert body["summary"]["stale"] == 20
    assert body["summary"]["running"] == 0
    assert body["summary"]["queued"] == 240

def test_rejected_entries_do_not_enter_devices(client):
    """上报被拒的条目不进库，也不进 devices 清单。"""
    r = _post(client, [_task(task_id="bad-kind", kind="unknown"),
                       _task(task_id="bad-state", state="paused")],
              device_id="android-bad")
    body = r.get_json()
    assert body["accepted"] == 0
    assert len(body["rejected"]) == 2
    assert _row_count() == 0
    listed = client.get("/queue").get_json()
    assert listed["devices"] == []
    assert listed["summary"]["devices"] == 0


def test_devices_only_counts_accepted_rows_of_a_device(client):
    """同设备里既有合法条目也有被拒条目：devices 只算落库的那些。"""
    _post(client, [_task(task_id="ok-1"),
                   _task(task_id="bad-1", kind="unknown")],
          device_id="android-mixed")
    devices = client.get("/queue").get_json()["devices"]
    assert _devices_by_id(devices)["android-mixed"] == {"total": 1, "active": 1}


def test_storage_queue_devices_returns_list(client):
    """存储层直调：返回 [{"device_id","total","active","stale"}] 列表。"""
    _post(client, [_task(task_id="a1", state="queued"),
                   _task(task_id="a2", state="done")], device_id="android-a")
    devices = storage_module.queue_devices()
    assert isinstance(devices, list)
    assert devices == [{"device_id": "android-a", "total": 2, "active": 1,
                       "stale": 0}]
    for item in devices:
        assert set(item) == {"device_id", "total", "active", "stale"}
        assert isinstance(item["device_id"], str)
        assert isinstance(item["total"], int)
        assert isinstance(item["active"], int)
        assert isinstance(item["stale"], int)


def test_storage_queue_devices_without_table_returns_empty(client):
    """表还没建（老库没跑过 init_db）：返回空列表，不抛异常。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_queue_notable_")
    saved_db = app_module.DB_PATH
    app_module.DB_PATH = os.path.join(tmp, "no_table.db")
    try:
        assert storage_module.queue_devices() == []
    finally:
        app_module.DB_PATH = saved_db
        shutil.rmtree(tmp, ignore_errors=True)


def test_devices_counts_stale_rows_in_total(client):
    """过期行仍计入 total，但不计入 active —— 口径与 queue_summary 一致。

    以前设备维度的 active 不看 TTL：「2 活跃」在汇总里却可能是 running 0，
    两台设备都停报之后僵尸设备会一直挂着 active=2。total 仍是库里现存条数
    （不做过期剔除），过期条数单独落在 stale 里。
    """
    now = int(time.time() * 1000)
    _post(client, [_task(task_id="fresh", state="queued")])
    _insert_row(DEVICE, "old", now - storage_module.QUEUE_TTL_MS - 60000,
                state="running")
    body = client.get("/queue").get_json()
    devices = body["devices"]
    assert _devices_by_id(devices)[DEVICE] == {"total": 2, "active": 1}
    assert devices[0]["stale"] == 1
    # 设备维度的「在跑」与全库汇总必须对得上（同一次响应内不打架）：
    # summary 没有 active 键，活跃 = queued + running。
    assert body["summary"]["running"] == 0
    assert body["summary"]["queued"] + body["summary"]["running"] == 1


# ---------------------------------------------------------------- 脏数值行
#
# queue_tasks.updated_at / queued_at 也是 INTEGER 亲和：直连改库、老库迁移、
# 第三方脚本都能把它写成 TEXT，裸 int("zzz") 会把 /queue 整页打成 500。


def test_get_queue_survives_text_updated_at(client):
    """updated_at 被写成文本时 /queue 必须 200，且该行按「过期」保守处理。"""
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "good-1", now, state="queued")
    _insert_row(DEVICE, "dirty-1", "zzz", state="running")
    r = client.get("/queue")
    assert r.status_code == 200, r.get_data(as_text=True)
    body = r.get_json()
    ids = [t["task_id"] for t in body["tasks"]]
    assert set(ids) == {"good-1", "dirty-1"}, ids
    # 认不出的 updated_at 按 0 → 远超 TTL → 只计 stale，不冒充 running
    assert body["summary"]["total"] == 2
    assert body["summary"]["stale"] == 1
    assert body["summary"]["running"] == 0
    assert body["summary"]["queued"] == 1
    dirty = [t for t in body["tasks"] if t["task_id"] == "dirty-1"][0]
    assert dirty["stale"] is True
    # 设备维度同口径：脏 updated_at 按过期处理，不拿它冒充「在跑」
    dev = body["devices"][0]
    assert dev["total"] == 2
    assert dev["active"] == 1
    assert dev["stale"] == 1


def test_get_queue_survives_text_queued_at(client):
    """queued_at 同族：坏值归一成 0，不让整页挂掉。"""
    now = int(time.time() * 1000)
    _insert_row(DEVICE, "dirty-2", now, state="queued", queued_at="soon")
    r = client.get("/queue")
    assert r.status_code == 200, r.get_data(as_text=True)
    task = r.get_json()["tasks"][0]
    assert task["task_id"] == "dirty-2"
    assert task["queued_at"] == 0

