# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""模块拆分回归测试（app.py → util.py / storage.py / routes.py）。

锁定三件事，防止以后再改回去或者改岔：
  1) app 模块对外暴露的名字一个都不能少（老用例直接读写这些属性）；
  2) 路由表（路径 + 方法 + endpoint 名）一个都不能变；
  3) 四个模块之间没有重复实现，运行期可变状态仍然只存在于 app 模块。
"""

import ast
import os
import sys
import threading

import pytest
from flask import Flask

sys.path.insert(0, os.path.dirname(__file__))

import app as app_module
from app import app as flask_app


SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
APP_PY = os.path.join(SERVER_DIR, "app.py")
UTIL_PY = os.path.join(SERVER_DIR, "util.py")
STORAGE_PY = os.path.join(SERVER_DIR, "storage.py")
ROUTES_PY = os.path.join(SERVER_DIR, "routes.py")
STATS_PY = os.path.join(SERVER_DIR, "stats_api.py")
SUGGESTS_PY = os.path.join(SERVER_DIR, "suggests.py")

# 参与「一份实现只在一个模块」比对的模块清单（新增只读模块也要登记进来）
ALL_MODULES = (APP_PY, UTIL_PY, STORAGE_PY, ROUTES_PY, STATS_PY, SUGGESTS_PY)

LOCK_TYPE = type(threading.Lock())

# app 模块必须继续暴露的名字 + 期望类型
EXPECTED_EXPORTS = [
    ("_auth_fail_events", dict),
    ("_connect", callable),
    ("_db_ready", bool),
    ("_esc_html", callable),
    ("_extract_lock", LOCK_TYPE),
    ("_extract_status", dict),
    ("_import_extract_module", callable),
    ("_importance_label", callable),
    ("_run_extract_bg", callable),
    ("_safe_bool", callable),
    ("ADVISOR_MAX_CASE_COUNT", int),
    ("ADVISOR_MAX_DETAIL_LEN", int),
    ("ADVISOR_MAX_MODEL_LEN", int),
    ("ADVISOR_MAX_PERIOD_DAYS", int),
    ("ADVISOR_MAX_SUMMARY_LEN", int),
    ("ADVISOR_MAX_TITLE_LEN", int),
    ("ADVISOR_PATH", str),
    ("ALLOW_NO_AUTH", (str, bool)),
    ("app", Flask),
    ("AUTH_FAIL_LIMIT", int),
    ("create_app", callable),
    ("DATA_DIR", str),
    ("DB_PATH", str),
    ("init_db", callable),
    ("JSONL_PATH", str),
    ("TOKEN", str),
]

# 拆分前的完整路由清单（路径, 方法, endpoint 名）
EXPECTED_RULES = {
    ("/", "GET", "index"),
    ("/admin/extract", "POST", "admin_extract"),
    ("/admin/extract/status", "GET", "admin_extract_status"),
    ("/advisor", "GET", "query_advisor"),
    ("/advisor", "POST", "receive_advisor"),
    ("/analysis", "GET", "query_analysis"),
    ("/analysis", "POST", "receive_analysis"),
    ("/console", "GET", "console_page"),
    ("/console/icons/<path:filename>", "GET", "console_icon"),
    ("/console/manifest.json", "GET", "console_manifest"),
    ("/health", "GET", "health"),
    ("/messages", "GET", "query_messages"),
    ("/queue", "GET", "query_queue"),
    ("/queue", "POST", "receive_queue"),
    ("/static/<path:filename>", "GET", "static"),
    ("/stats", "GET", "query_stats"),
    ("/suggests/next", "GET", "query_suggests_next"),
    ("/sw.js", "GET", "console_sw"),
    ("/weixin", "POST", "receive_weixin"),
}

# 每个实现应该且只应该存在于一个模块里
EXPECTED_OWNER = {
    # util.py：纯函数工具层
    "pkg_label": "util.py",
    "_importance_label": "util.py",
    "_safe_int": "util.py",
    "_clamp_int": "util.py",
    "_safe_bool": "util.py",
    "_advisor_ts": "util.py",
    "_parse_cursor": "util.py",
    "_parse_tz_offset": "util.py",
    "_fmt_tz_offset": "util.py",
    "_day_bounds_ms": "util.py",
    "_utc_ms": "util.py",
    "_local_offset_min": "util.py",
    "_local_ms": "util.py",
    "_fmt_ts": "util.py",
    "_safe_json": "util.py",
    "_like_escape": "util.py",
    "_prefix_upper": "util.py",
    "_esc_html": "util.py",
    "_analyzed_at_text": "util.py",
    "_validate_item": "util.py",
    # storage.py：存储层
    "_connect": "storage.py",
    "_ensure_index": "storage.py",
    "init_db": "storage.py",
    "_append_jsonl": "storage.py",
    "_advisor_jsonl_path": "storage.py",
    "AdvisorArchiveError": "storage.py",
    "_write_advisor_jsonl": "storage.py",
    "_append_advisor_reports": "storage.py",
    "_trim_advisor_jsonl": "storage.py",
    "_load_advisor_reports": "storage.py",
    "_write_advisor_snapshot": "storage.py",
    "_save_advisor_reports": "storage.py",
    "_normalize_advisor_item": "storage.py",
    "_import_extract_module": "storage.py",
    "_run_extract_bg": "storage.py",
    "_probe_main_db_writable": "storage.py",
    "cleanup_queue_tasks": "storage.py",
    # routes.py：数据面路由
    "receive_weixin": "routes.py",
    "query_messages": "routes.py",
    "receive_analysis": "routes.py",
    "query_analysis": "routes.py",
    "receive_advisor": "routes.py",
    "query_advisor": "routes.py",
    "admin_extract": "routes.py",
    "admin_extract_status": "routes.py",
    # stats_api.py / suggests.py：只读聚合与建议（各自模块自带 register）
    "query_stats": "stats_api.py",
    "_messages_block": "stats_api.py",
    "_analysis_block": "stats_api.py",
    "_queue_block": "stats_api.py",
    "query_suggests_next": "suggests.py",
    "_advisor_state": "suggests.py",
    "_queue_state": "suggests.py",
    "_todo_counts": "suggests.py",
    "_convs_without_analysis": "suggests.py",
    "_pick": "suggests.py",
    # app.py：Flask 壳 + 鉴权 + 控制台 / 落地页
    "create_app": "app.py",
    "check_token": "app.py",
    "health": "app.py",
    "index": "app.py",
    "console_page": "app.py",
    "console_manifest": "app.py",
    "console_icon": "app.py",
    "console_sw": "app.py",
    "_ensure_db_ready": "app.py",
    "_security_headers": "app.py",
    "_require_token_configured": "app.py",
    "_maybe_cleanup_queue": "app.py",
}

# 运行期可变状态：只允许在 app 模块里出现
RUNTIME_STATE = [
    "DATA_DIR",
    "DB_PATH",
    "JSONL_PATH",
    "ADVISOR_PATH",
    "TOKEN",
    "ALLOW_NO_AUTH",
    "_db_ready",
    "_queue_cleanup_at",
    "_extract_status",
    "_extract_lock",
    "_auth_fail_events",
]


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def _top_level_names(path):
    """返回该模块顶层定义的函数 / 类名与模块级赋值名。"""
    tree = ast.parse(_read(path))
    defs = []
    assigns = []
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            defs.append(node.name)
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    assigns.append(target.id)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            assigns.append(node.target.id)
    return defs, assigns


def _rule_map(application):
    rules = set()
    for rule in application.url_map.iter_rules():
        for method in sorted(rule.methods or ()):
            if method in ("HEAD", "OPTIONS"):
                continue
            rules.add((rule.rule, method, rule.endpoint))
    return rules


@pytest.mark.parametrize("name,expected", EXPECTED_EXPORTS, ids=[n for n, _ in EXPECTED_EXPORTS])
def test_app_module_still_exports_name(name, expected):
    """约束：老用例直接读写的 app 模块属性不能消失、类型不能变。"""
    assert hasattr(app_module, name), "app 模块缺少 %s" % name
    value = getattr(app_module, name)
    if expected is callable:
        assert callable(value), "%s 应该可调用" % name
    else:
        assert isinstance(value, expected), "%s 类型不对：%r" % (name, type(value))


def test_import_paths_still_work():
    """约束：from app import app 与 import app as app_module 都要继续可用。"""
    assert app_module.app is flask_app
    assert isinstance(flask_app, Flask)
    for mod_name in ("util", "storage", "routes", "stats_api", "suggests"):
        assert mod_name in sys.modules, "%s 没有被导入" % mod_name


def test_view_functions_contain_all_endpoints():
    """约束：endpoint 名一个都不能变（客户端与 App 都按名字/路径调用）。"""
    expected_endpoints = {rule[2] for rule in EXPECTED_RULES}
    actual = set(flask_app.view_functions)
    missing = expected_endpoints - actual
    assert not missing, "缺少 endpoint：%s" % sorted(missing)
    assert expected_endpoints <= actual


def test_url_rules_unchanged():
    """约束：路径 + 方法 + endpoint 三元组与拆分前逐条一致。"""
    assert _rule_map(flask_app) == EXPECTED_RULES


def test_app_py_is_shorter():
    """app.py 从 2294 行的上帝文件瘦下来。"""
    lines = _read(APP_PY).splitlines()
    assert len(lines) < 1200, "app.py 还有 %d 行" % len(lines)


@pytest.mark.parametrize("name,owner", sorted(EXPECTED_OWNER.items()),
                         ids=[n for n, _ in sorted(EXPECTED_OWNER.items())])
def test_no_duplicate_implementation(name, owner):
    """同一个实现只允许存在一份，且必须待在约定的模块里。"""
    homes = []
    for path in ALL_MODULES:
        defs, _ = _top_level_names(path)
        if name in defs:
            homes.append(os.path.basename(path))
    assert homes == [owner], "%s 的实现位置不对：%s" % (name, homes)


@pytest.mark.parametrize("state_name", RUNTIME_STATE, ids=RUNTIME_STATE)
def test_runtime_state_only_lives_in_app_module(state_name):
    """运行期可变状态只能有一份，且只能在 app 模块里定义。"""
    for path in (UTIL_PY, STORAGE_PY, ROUTES_PY, STATS_PY, SUGGESTS_PY):
        _, assigns = _top_level_names(path)
        assert state_name not in assigns, (
            "%s 在 %s 里被重新赋值了，运行期状态必须只有一份"
            % (state_name, os.path.basename(path)))
    assert hasattr(app_module, state_name)


def test_no_module_level_copy_of_runtime_state():
    """其它模块不能在 import 期拷贝路径 / 令牌等常量。"""
    for path in (UTIL_PY, STORAGE_PY, ROUTES_PY, STATS_PY, SUGGESTS_PY):
        src = _read(path)
        tree = ast.parse(src)
        for node in tree.body:
            if isinstance(node, ast.ImportFrom):
                continue
            if isinstance(node, ast.Import):
                continue
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                continue
            for name in RUNTIME_STATE:
                assert name not in src.split("\n")[node.lineno - 1], (
                    "%s 里出现了运行期状态的模块级拷贝" % os.path.basename(path))


def test_storage_reads_paths_at_call_time(tmp_path, monkeypatch):
    """路径必须在调用时动态取，否则 monkeypatch 写不到临时目录。"""
    db_path = str(tmp_path / "dyn.db")
    monkeypatch.setattr(app_module, "DB_PATH", db_path)
    conn = app_module._connect()
    try:
        conn.execute("CREATE TABLE IF NOT EXISTS t (x INTEGER)")
        conn.commit()
    finally:
        conn.close()
    assert os.path.exists(db_path)


def test_new_modules_exist_and_are_importable():
    for path in (UTIL_PY, STORAGE_PY, ROUTES_PY, STATS_PY, SUGGESTS_PY):
        assert os.path.exists(path), path
        assert len(_read(path).splitlines()) > 50, path


def test_no_forbidden_identifiers_in_new_modules():
    """仓库红线：不得出现旧母仓库命名或真实用户目录。"""
    # 用拼接构造，避免本文件自身命中 privacy_guard 的 G3 规则（与守卫文件同款写法）
    forbidden = [
        "qi" + "yeweixin",
        "we" + "ixin_android",
        "Wei" + "xinAndroid",
        "we" + "ixin-monitor",
    ]
    for path in ALL_MODULES:
        src = _read(path)
        for token in forbidden:
            assert token not in src, "%s 里出现了禁用标识 %s" % (path, token)

