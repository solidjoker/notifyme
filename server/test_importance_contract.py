# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT
"""重要度口径的跨层契约守卫。

同一份重要度在三个地方各有一份实现：

  - server/util.py 的 _importance_label（写库 + /analysis 出参）
  - server/console_classify.js 的 importanceKey（控制台规则层：分区 / 排序 / chip）
  - server/console_ui.py 的 impLabel（控制台 UI：分区 / 排序 / warn 样式）

任一处漂移都会让「同一张卡片自相矛盾」：
chip 显示「重要度 高」（走规则层），待办分区却落进「未标重要度」（走 UI 副本）、
排序排到最后、样式不加 warn —— 同一张卡两句话。

本文件钉三件事：
1. 源码级：UI 不再自带第二份口径（impLabel 必须委托 CC.importanceKey），
   服务端与规则层共用同一条十进制正则；
2. 运行时：一张矩阵逐条比对「服务端 / 规则层 / UI（带规则层）/ UI（降级）」四处输出；
3. 端到端：POST /analysis 上报各种形态 -> GET /analysis 顶层 importance 与规则层一致。

与 server/test_label_contract.py（码值 <-> 中文）、server/test_report_contract.py（上报字段）、
server/test_queue_contract.py（排队字段）同族。
"""
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
import util as server_util  # noqa: E402
from app import app as flask_app  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSIFY_PATH = os.path.join(HERE, "console_classify.js")
CONSOLE_UI_PATH = os.path.join(HERE, "console_ui.py")
UTIL_PATH = os.path.join(HERE, "util.py")


def _read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read().replace("\r\n", "\n")


def _console_html():
    """取控制台页面源码（运行时真相：app.py 注入的就是这份 HTML）。"""
    spec = importlib.util.spec_from_file_location("console_ui_contract", CONSOLE_UI_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.CONSOLE_HTML


def _js_function(html, name):
    """抠出 JS 里 function NAME(...) {...} 的源码（按花括号深度配对）。"""
    marker = "function %s(" % name
    start = html.find(marker)
    assert start >= 0, "控制台页面里找不到 %s" % name
    brace = html.find("{", start)
    depth = 0
    for i in range(brace, len(html)):
        if html[i] == "{":
            depth += 1
        elif html[i] == "}":
            depth -= 1
            if depth == 0:
                return html[start:i + 1]
    raise AssertionError("%s 的花括号没闭合" % name)


def _node_exe():
    exe = shutil.which("node") or shutil.which("nodejs")
    if not exe:
        if os.environ.get("REQUIRE_NODE") == "1":
            pytest.fail("REQUIRE_NODE=1 但 PATH 里找不到 node：重要度契约必须能跑真逻辑。")
        pytest.skip("node 不可用，跳过重要度契约的运行时比对")
    return exe


@pytest.fixture(scope="session")
def node_exe():
    return _node_exe()


@pytest.fixture(scope="session")
def imp_label_source():
    return _js_function(_console_html(), "impLabel")


def _run_node(node_exe, program):
    """把一段 node 程序写到临时文件再跑（避免命令行长度限制）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_imp_")
    path = os.path.join(tmp, "probe.js")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(program)
    proc = subprocess.run([node_exe, path], capture_output=True, text=True,
                          encoding="utf-8", timeout=60)
    assert proc.returncode == 0, "node 程序失败：退出码=%s stderr=%r" % (proc.returncode, proc.stderr)
    return json.loads(proc.stdout)


def _program(inputs, imp_source, with_rules):
    """生成 node 程序：跑一遍矩阵。

    with_rules=True  -> 同时取规则层 importanceKey 与 UI impLabel（CC 正常加载）
    with_rules=False -> 只取 UI impLabel，且规则层缺失（CC = {}），验证降级路径
    """
    header = ("var CC = require(%s);" % json.dumps(CLASSIFY_PATH)) if with_rules else "var CC = {};"
    body = ("    return { key: CC.importanceKey(v), chip: CC.importanceChip(v), ui: impLabel(v) };"
            if with_rules else "    return { ui: impLabel(v) };")
    return "\n".join([
        header,
        "var impLabel = %s;" % imp_source,
        "var inputs = %s;" % json.dumps(inputs),
        "var out = inputs.map(function (v) {",
        body,
        "});",
        "process.stdout.write(JSON.stringify(out));",
    ])


# 矩阵：(输入, 期望档位)。期望值只认 high/mid/low/空串（空串 = 未标）。
MATRIX = [
    (None, ""),
    (True, ""),
    (False, ""),
    (6.3, "high"),
    (6, "high"),
    (3, "mid"),
    (2.9, "low"),
    (0, "low"),
    (-1, "low"),
    ("6.3", "high"),
    ("6", "high"),
    ("3", "mid"),
    ("2.9", "low"),
    ("0", "low"),
    ("-1", "low"),
    ("6.0", "high"),
    ("  6.0  ", "high"),
    ("high", "high"),
    ("HIGH", "high"),
    (" mid ", "mid"),
    ("low", "low"),
    ("+6", ""),
    (".5", ""),
    ("5.", ""),
    ("1e3", ""),
    ("nan", ""),
    ("inf", ""),
    ("-inf", ""),
    ("bogus", ""),
    ("urgent", ""),
    ("", ""),
    ("   ", ""),
]


def _server_key(value):
    """服务端口径：_importance_label 返回 None 表示「未标」，统一成空串便于比对。"""
    return server_util._importance_label(value) or ""


def test_ui_delegates_to_the_rules_layer(imp_label_source):
    """控制台 UI 不再自带第二份口径（旧的整数专用正则必须退场）。"""
    assert "CC.importanceKey" in imp_label_source, (
        "控制台 UI 的 impLabel 必须委托规则层 importanceKey，否则分区 / 排序 / 样式会与 chip 分家"
    )
    assert "/^[0-9]+$/" not in imp_label_source, (
        "旧的整数专用正则还在：字符串 '6.0' / '-1' 会走不通，chip 显示高/低、分区却落进未标"
    )


def test_server_and_rules_share_one_decimal_regex():
    """服务端与规则层必须共用同一条十进制正则（数值口径只有一份）。"""
    util_text = _read(UTIL_PATH)
    m = re.search(r'_IMPORTANCE_NUM_RE\s*=\s*re\.compile\(r"([^"]+)"\)', util_text)
    assert m, "util.py 里找不到 _IMPORTANCE_NUM_RE"
    py_re = m.group(1)
    cls_text = _read(CLASSIFY_PATH)
    start = cls_text.find("function importanceKey(")
    assert start >= 0, "console_classify.js 里找不到 importanceKey"
    end = cls_text.find("function importanceChip(", start)
    body = cls_text[start:end if end > 0 else len(cls_text)]
    m2 = re.search(r"/(\^[^\"/]*)/\.test\(", body)
    assert m2, "importanceKey 里找不到十进制正则"
    js_re = m2.group(1)
    assert py_re == js_re, (
        "服务端与规则层的十进制正则不一致：服务端=%r 规则层=%r" % (py_re, js_re)
    )


def test_matrix_expectations():
    """钉住具体档位（含旧实现判错的那几种形态）。"""
    for value, expected in MATRIX:
        assert _server_key(value) == expected, (
            "服务端重要度档位不对：%r -> %r，期望 %r" % (value, _server_key(value), expected)
        )


def test_all_implementations_agree(node_exe, imp_label_source):
    """服务端 / 规则层 / 控制台 UI 三处逐条一致。"""
    inputs = [v for v, _ in MATRIX]
    out = _run_node(node_exe, _program(inputs, imp_label_source, True))
    assert len(out) == len(inputs), "node 回来的条数不对"
    chips = {"high": "高", "mid": "中", "low": "低"}
    for (value, expected), got in zip(MATRIX, out):
        assert got["key"] == expected, (
            "规则层档位不对：%r -> %r，期望 %r" % (value, got["key"], expected)
        )
        assert got["ui"] == expected, (
            "控制台 UI 档位不对：%r -> %r，期望 %r" % (value, got["ui"], expected)
        )
        assert _server_key(value) == expected, (
            "服务端档位不对：%r -> %r，期望 %r" % (value, _server_key(value), expected)
        )
        if expected:
            assert got["chip"] == chips[expected], (
                "chip 文案与档位分家：%r 的档位是 %r，chip 却是 %r" % (value, expected, got["chip"])
            )


def test_ui_degraded_path_agrees(node_exe, imp_label_source):
    """规则脚本没加载时，UI 的降级实现也要同一口径。"""
    inputs = [v for v, _ in MATRIX]
    out = _run_node(node_exe, _program(inputs, imp_label_source, False))
    for (value, expected), got in zip(MATRIX, out):
        assert got["ui"] == expected, (
            "降级路径档位不对：%r -> %r，期望 %r" % (value, got["ui"], expected)
        )


def test_string_numbers_are_not_unknown():
    """字符串形态的数值必须落到具体档位（旧 UI 副本会判成未知）。"""
    assert _server_key("6.0") == "high", "'6.0' 应判 high"
    assert _server_key("-1") == "low", "'-1' 应判 low"
    assert _server_key("2.9") == "low", "'2.9' 应判 low"


def test_non_decimal_numeric_text_is_unknown():
    """非十进制写法的数值文本一律未标（宽松 float() 会把它们判成 low/high）。"""
    for text in ("+6", ".5", "5.", "1e3", "nan", "inf", "-inf"):
        assert _server_key(text) == "", "%r 应判未标，实际 %r" % (text, _server_key(text))


def test_unknown_text_is_not_echoed():
    """认不出的文本不再原样回显：顶层字段只能是 high/mid/low/未标。"""
    assert server_util._importance_label("bogus") is None, "未知文本应判未标（None），不是原样回显"
    assert _server_key("urgent") == ""


@pytest.fixture()
def api():
    """隔离数据目录的 Flask test client（与 test_bool_readout.py 同款做法）。"""
    tmp = tempfile.mkdtemp(prefix="notifyme_imp_")
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


ENDPOINT_SHAPES = [
    ("imp-num-high", 6.3),
    ("imp-num-mid", 3),
    ("imp-num-low", 2.9),
    ("imp-zero", 0),
    ("imp-str-decimal", "6.0"),
    ("imp-str-negative", "-1"),
    ("imp-str-label", "high"),
    ("imp-str-bogus", "bogus"),
    ("imp-str-exponent", "1e3"),
    ("imp-missing-s1", None),
]


def test_endpoint_importance_matches_the_canonical_key(api, node_exe, imp_label_source):
    """端到端：上报各种形态 -> /analysis 顶层 importance 与规则层一致。"""
    rows = []
    for case_id, imp in ENDPOINT_SHAPES:
        row = {"case_id": case_id, "conversation": "conv-" + case_id,
               "window_end": 1794748800000, "message_count": 2,
               "analyzed_at": 1794748800000, "protocol": "v2",
               "pkg": "com.tencent.mm", "escalated": False, "filtered": False}
        if imp is not None:
            row["s1"] = {"importance": imp, "need_action": {"value": True, "prob": 0.5},
                         "due_window": "today", "topic": "work_task"}
        rows.append(row)
    resp = api.post("/analysis", json=rows)
    assert resp.status_code == 200, "上报失败：%s" % resp.get_json()
    assert resp.get_json()["received"] == len(rows)
    data = api.get("/analysis?limit=100").get_json()
    by_case = {row.get("case_id"): row for row in data["analyses"]}
    raw_values = []
    expected_list = []
    for case_id, imp in ENDPOINT_SHAPES:
        row = by_case[case_id]
        expected = _server_key(imp)
        assert (row.get("importance") or "") == expected, (
            "%s 的顶层 importance 不对：%r，期望 %r" % (case_id, row.get("importance"), expected)
        )
        raw_values.append(imp)
        expected_list.append(expected)
    out = _run_node(node_exe, _program(raw_values, imp_label_source, True))
    for (case_id, imp), expected, got in zip(ENDPOINT_SHAPES, expected_list, out):
        assert got["key"] == expected, (
            "%s 的规则层档位与服务端不一致：%r vs %r" % (case_id, got["key"], expected)
        )


def test_endpoint_unknown_importance_is_not_stored_as_label(api):
    """未知文本不再进库当标签（顶层字段只能是 high/mid/low/未标）。"""
    resp = api.post("/analysis", json=[{
        "case_id": "imp-bogus", "conversation": "g", "analyzed_at": 1794748800000,
        "s1": {"importance": "bogus"}}])
    assert resp.status_code == 200
    row = api.get("/analysis?limit=10").get_json()["analyses"][0]
    assert row.get("importance") is None, "未知文本不该存成标签，实际 %r" % (row.get("importance"),)
