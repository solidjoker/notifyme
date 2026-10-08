# -*- coding: utf-8 -*-
"""服务端接口冒烟测试。运行：pytest server/test_weixin.py -v"""
import os
import shutil
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import app as app_module

flask_app = app_module.app  # Flask 实例（不是模块）


@pytest.fixture
def client():
    """Flask test client + 隔离的临时数据目录。"""
    flask_app.config["TESTING"] = True
    tmp = tempfile.mkdtemp(prefix="notifyme_test_")
    flask_app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024
    app_module.DATA_DIR = tmp
    app_module.DB_PATH = os.path.join(tmp, "test.db")
    app_module.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    app_module.ADVISOR_PATH = os.path.join(tmp, "advisor.json")
    app_module.TOKEN = ""
    app_module.init_db()
    with flask_app.test_client() as c:
        yield c
    shutil.rmtree(tmp, ignore_errors=True)


def _msg(text="hello", ts=1700000000000, conv="test_conv", pkg="com.tencent.mm"):
    return {"sender": "u", "text": text, "timestamp": ts,
            "conversation": conv, "is_group": False, "pkg": pkg}


def test_valid_batch(client):
    r = client.post("/weixin", json=[_msg()])
    assert r.status_code == 200
    assert r.get_json()["received"] == 1


def test_duplicate(client):
    client.post("/weixin", json=[_msg()])
    r = client.post("/weixin", json=[_msg()])
    assert r.get_json()["duplicated"] == 1


def test_invalid_returns_207(client):
    r = client.post("/weixin", json=[_msg(), {"bad": "data"}])
    assert r.status_code == 207
    assert r.get_json()["rejected"] == [1]


def test_empty_body_400(client):
    assert client.post("/weixin", json="not-a-list").status_code == 400


def test_importance_normalized(client):
    ana = {"case_id": "c1", "conversation": "g", "s1": {"importance": 7},
           "analyzed_at": 1700000000000}
    assert client.post("/analysis", json=[ana]).status_code == 200


def test_empty_case_id_rejected(client):
    r = client.post("/analysis", json=[{"case_id": " ", "conversation": "g"}])
    assert r.status_code == 207


def test_auth_rejects_missing_token(client):
    app_module.TOKEN = "secret"
    assert client.get("/messages").status_code == 401
    app_module.TOKEN = ""
