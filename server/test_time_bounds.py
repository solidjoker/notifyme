# -*- coding: utf-8 -*-
"""时间边界回归：date 参数与顾问时间戳遇到「1970 之前 / 远未来」时不许 500。

E-50：util._day_bounds_ms 与 util._advisor_ts 过去都走 naive
datetime.timestamp()，CPython 在 Windows 上对 1970 之前或 9999 边界直接抛
OSError: [Errno 22] Invalid argument —— 一条 /messages?date=1970-01-01 就能把
整页打成 500，而 POST /advisor 允许任意文本 created_at，写完 GET /advisor 与
GET /suggests/next 双双 500（控制台顾问 Tab 与建议块整块空白）。

口径：换算基准一律走 replace(tzinfo=utc)，本地偏移单独求（_local_offset_min）；
整日落在 [MIN_TS_MS, MAX_TS_MS] 之外回 400 date out of range，只部分重叠就夹边界。
"""
import os
import shutil
import sys
import tempfile
from datetime import datetime, timezone

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app as app_module  # noqa: E402

flask_app = app_module.app


@pytest.fixture
def client():
    flask_app.config["TESTING"] = True
    tmp = tempfile.mkdtemp(prefix="notifyme_timebounds_")
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
    shutil.rmtree(tmp, ignore_errors=True)


def _post_message(client, ts=1700000000000, conv="test_conv"):
    return client.post("/weixin", json=[{
        "sender": "u", "text": "hello", "timestamp": ts,
        "conversation": conv, "is_group": False, "pkg": "com.tencent.mm",
    }])


# --------------------------------------------------------------------------
# 内核：UTC 基准换算不吃本机时区，也不抛 OSError
# --------------------------------------------------------------------------

def test_utc_ms_matches_epoch_definition():
    """_utc_ms 把 naive datetime 当 UTC，纪元与两端都不受本机时区影响。"""
    assert app_module._utc_ms(datetime(1970, 1, 1)) == 0
    assert app_module._utc_ms(datetime(1970, 1, 2)) == 86400000
    assert app_module._utc_ms(datetime(1969, 12, 31)) == -86400000
    # 9999-12-31 是 CPython datetime 的上界；旧写法在 Windows 上到这里就抛 OSError
    assert app_module._utc_ms(datetime(9999, 12, 31)) == 253402214400000


def test_local_ms_agrees_with_naive_timestamp_for_modern_dates():
    """现代日期上 _local_ms 与旧写法 int(day.timestamp()*1000) 逐值相同。"""
    for args in ((2026, 11, 10), (2026, 1, 1), (2030, 6, 15)):
        day = datetime(*args)
        assert app_module._local_ms(day) == int(day.timestamp() * 1000)


def test_local_ms_never_raises_on_epoch_boundaries():
    """旧写法抛 OSError 的四个日期，_local_ms 都必须给出整数。"""
    for args in ((1970, 1, 1), (1969, 12, 31), (1900, 1, 1), (9999, 12, 31)):
        value = app_module._local_ms(datetime(*args))
        assert isinstance(value, int)


def test_day_bounds_uses_full_utc_shift_not_naive_timestamp():
    """显式偏移下的换算必须是 UTC 基准减偏移，与旧实现逐值一致。"""
    start, end = app_module._day_bounds_ms("2026-11-10", 480)
    assert start == app_module._utc_ms(datetime(2026, 11, 10)) - 480 * 60000
    assert end - start == 86400000


# --------------------------------------------------------------------------
# 端点：/messages 与 /analysis 的 date 分支
# --------------------------------------------------------------------------

@pytest.mark.parametrize("date_str", ["1970-01-01", "1970-01-02", "1970-02-01"])
def test_messages_date_near_epoch_is_not_500(client, date_str):
    """1970 年初的日期合法：整日与时间戳范围重叠 → 200（旧实现 500）。"""
    _post_message(client)
    resp = client.get("/messages?date=" + date_str + "&tz=+08:00")
    assert resp.status_code == 200
    assert resp.get_json()["ok"] is True


@pytest.mark.parametrize("date_str", ["1970-01-01", "1970-01-02", "1970-02-01"])
def test_analysis_date_near_epoch_is_not_500(client, date_str):
    """分析侧同口径：日期换算不再抛 OSError。"""
    resp = client.get("/analysis?date=" + date_str + "&tz=+08:00")
    assert resp.status_code == 200
    assert resp.get_json()["ok"] is True


@pytest.mark.parametrize("date_str", ["0001-01-01", "1900-01-01", "1969-12-31",
                                     "2999-12-31", "9999-12-31"])
def test_date_entirely_outside_timestamp_range_returns_400(client, date_str):
    """整日都在 [MIN_TS_MS, MAX_TS_MS] 之外 → 400，而不是永远为空的 200。"""
    for path in ("/messages", "/analysis"):
        resp = client.get(path + "?date=" + date_str + "&tz=+08:00")
        assert resp.status_code == 400, (path, date_str, resp.status_code)
        assert resp.get_json()["error"] == "date out of range"


@pytest.mark.parametrize("date_str", ["2026-02-30", "2026-13-01", "not-a-date",
                                       "2026-1-1x"])
def test_date_parse_failure_keeps_its_own_message(client, date_str):
    """解析失败仍然是 date must be YYYY-MM-DD（两种 400 文案别混成一句）。

    空串不算解析失败：routes 里 date_str 为空等于「不按日期过滤」，这是老口径
    （`?date=` 显式置空不报错），别在这里改成 400。
    """
    resp = client.get("/messages?date=" + date_str)
    assert resp.status_code == 400
    assert resp.get_json()["error"] == "date must be YYYY-MM-DD"


def test_messages_date_at_upper_bound_is_clamped_not_400(client):
    """2100-01-01 与范围只有一毫秒重叠：夹边界后仍是 200，不判越界。"""
    resp = client.get("/messages?date=2100-01-01&tz=+08:00")
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["ok"] is True and body["messages"] == []


def test_date_filter_still_selects_the_day(client):
    """收口后过滤语义不变：当天内的消息命中，次日不命中。"""
    ts = app_module._utc_ms(datetime(2026, 11, 10, 12, 0, 0))
    _post_message(client, ts=ts)
    hit = client.get("/messages?date=2026-11-10&tz=+00:00").get_json()
    miss = client.get("/messages?date=2026-11-11&tz=+00:00").get_json()
    assert len(hit["messages"]) == 1
    assert miss["messages"] == []


# --------------------------------------------------------------------------
# 顾问：任意文本 created_at 落库后，两个读接口都不许 500
# --------------------------------------------------------------------------

def _advisor_jsonl():
    return os.path.join(app_module.DATA_DIR, "advisor_reports.jsonl")


def _write_advisor_rows(rows):
    with open(_advisor_jsonl(), "w", encoding="utf-8") as f:
        for row in rows:
            f.write(__import__("json").dumps(row, ensure_ascii=False) + "\n")


@pytest.mark.parametrize("created_at", ["1969-06-01 00:00:00", "1960-01-01",
                                       "9999-12-31", "1900-01-01 08:00:00"])
def test_advisor_ts_tolerates_out_of_range_text(created_at):
    """_advisor_ts 对越界文本回落 0，绝不抛 OSError（排序键靠它）。"""
    assert app_module._advisor_ts(created_at) == 0


def test_advisor_endpoints_survive_out_of_range_created_at(client):
    """一条 1969 年的文本 created_at 不能让 /advisor 与 /suggests/next 500。"""
    _write_advisor_rows([
        {"report_id": "ancient", "created_at": "1969-06-01 00:00:00",
         "summary": "old", "suggestions": []},
        {"report_id": "future", "created_at": "9999-12-31",
         "summary": "far", "suggestions": []},
        {"report_id": "normal", "created_at": "1799999999000",
         "summary": "ok", "suggestions": []},
    ])
    resp = client.get("/advisor?limit=10")
    assert resp.status_code == 200
    ids = [r["report_id"] for r in resp.get_json()["reports"]]
    # 认不出的两个回落 0 垫底，正常的那个在前
    assert ids[0] == "normal"
    assert set(ids) == {"ancient", "future", "normal"}
    suggests = client.get("/suggests/next")
    assert suggests.status_code == 200
    assert suggests.get_json()["ok"] is True


def test_advisor_ts_still_parses_in_range_text(client):
    """收口没有放宽合法文本：范围内的本地时间照旧按本地时区换算。"""
    local = datetime(2026, 11, 10, 8, 0, 0)
    assert app_module._advisor_ts("2026-11-10 08:00:00") == app_module._local_ms(local)

