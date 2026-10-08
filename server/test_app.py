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
    m.init_db()

    with flask_app.test_client() as c:
        yield c

    # 清理
    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
