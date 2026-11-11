# -*- coding: utf-8 -*-
import os
import sys
import tempfile

import pytest

# 把 server/ 加入 sys.path，让 test 文件能 import app
sys.path.insert(0, os.path.dirname(__file__))

from app import app as flask_app  # noqa: E402


@pytest.fixture
def client():
    """Flask test client + 隔离的临时数据目录。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_test_")
    flask_app.config["TESTING"] = True
    flask_app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

    # monkey-patch 路径到临时目录
    import app as m
    m.DATA_DIR = tmp
    m.DB_PATH = os.path.join(tmp, "test.db")
    m.JSONL_PATH = os.path.join(tmp, "messages.jsonl")
    m.ADVISOR_PATH = os.path.join(tmp, "advisor.json")
    m.TOKEN = ""
    m.ALLOW_NO_AUTH = "1"
    m._db_ready = False
    m.init_db()

    with flask_app.test_client() as c:
        yield c

    # 清理
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)


def test_health(client):
    """GET /health 返回 200，且 JSON 的 status 字段为 ok。"""
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.get_json()
    assert data is not None
    assert data.get("status") == "ok"


def test_health_without_token(client, monkeypatch):
    """服务端开启鉴权（TOKEN 非空）时，不带 X-Token 访问 /health 仍返回
    200，且 body 为 {"status": "ok"}——探活接口不校验令牌，见 app.py 注释。
    """
    import app as m
    monkeypatch.setattr(m, "TOKEN", "unit-test-token")

    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.get_json() == {"status": "ok"}


def test_health_content_type(client):
    """GET /health 响应的 Content-Type 是 application/json。"""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.headers["Content-Type"].startswith("application/json")
