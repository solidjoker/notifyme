# -*- coding: utf-8 -*-
"""通知形状取证工具的契约用例：工具抽出来的结论必须与 docs/通知形状取证记录.md 的口径一致。

取证记录里的结论（微信没有 MessagingStyle、正文带 [N条] 未读前缀、发言人用半角冒号、
钉钉标题带「｜」、经小米推送代发）原来是一次性手抄的。这里用一份**脱敏后**的 dump 片段
把工具的行为钉死：解析器改了就红，取证结论变了也红。

夹具是手写的（不是真机原文），但字段名与真实 dumpsys --noredact 一致。
"""

import importlib.util
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TOOL_PATH = os.path.join(ROOT, "docs", "scripts", "notify_shape_capture.py")

FIXTURE = """
  NotificationRecord(0x12ab34c: pkg=com.tencent.mm user=UserHandle{0} id=42 tag=null key=0|com.tencent.mm|10001|null|10123: ...
    priority=0 flags=0x41
    channel=message_channel_new_id
    postTime=2026-11-12 10:22:33.000000+0800
    extras:
      android.title = String (项目攻坚群)
      android.text = String ([11条]李四:好的,谢谢)
      android.showWhen= Boolean (true)
    intents:
      action=ACTION_TEMPLATE
  NotificationRecord(0x22bd56d: pkg=com.tencent.mm user=UserHandle{0} id=43 tag=null key=0|com.tencent.mm|10001|null|10124: ...
    channel=message_channel_new_id
    postTime=2026-11-12 10:23:01.000000+0800
    extras:
      android.title = String (项目攻坚群)
      android.text = String ([12条]李四:好的,谢谢)
    intents:
      action=ACTION_TEMPLATE
  NotificationRecord(0x33ce78e: pkg=com.alibaba.android.rimet user=UserHandle{0} id=7 tag=null key=0|com.alibaba.android.rimet|101344|null|10123: ...
    channel=mipush|com.alibaba.android.rimet|101344
    opPkg=com.xiaomi.xmsf
    postTime=2026-11-12 11:02:00.000000+0800
    extras:
      android.title = String (钉钉｜某班群)
      android.text = String (王五(老师):「通知」确认情况)
      xmsf_target_package = String (com.alibaba.android.rimet)
    intents:
      action=ACTION_TEMPLATE
  NotificationRecord(0x44df90f: pkg=com.example.chat user=UserHandle{0} id=9 tag=null key=0|com.example.chat|1|null|2: ...
    channel=chat
    postTime=2026-11-12 12:00:00.000000+0800
    extras:
      android.title = String (张三)
      android.text = String (你好，明天见)
      android.messages = MessageRecord(0x1 sender=李四 subject=)
    intents:
      action=ACTION_TEMPLATE
"""


def _load_tool():
    spec = importlib.util.spec_from_file_location("notify_shape_capture", TOOL_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TOOL = _load_tool()


def test_tool_module_loads_from_docs_scripts():
    assert os.path.exists(TOOL_PATH)
    assert hasattr(TOOL, "parse_dump")
    assert hasattr(TOOL, "summarize")
    assert hasattr(TOOL, "body_shape")


def test_parse_dump_finds_every_record():
    records = TOOL.parse_dump(FIXTURE)
    assert len(records) == 4
    assert [r["pkg"] for r in records] == [
        "com.tencent.mm",
        "com.tencent.mm",
        "com.alibaba.android.rimet",
        "com.example.chat",
    ]


def test_wechat_shape_has_unread_prefix_and_half_colon():
    records = TOOL.parse_dump(FIXTURE)
    shape = TOOL.body_shape(records[0]["text"])
    assert shape["unread_prefix"] is True
    assert shape["sender"] == "李四"
    assert shape["colon"] == ":"
    assert shape["space_after_colon"] is False


def test_wechat_unread_count_change_keeps_same_body():
    """取证记录 §3.1 的核心：未读数变化不产生第二条（正文剥前缀后一致）。"""
    records = TOOL.parse_dump(FIXTURE)
    first, second = records[0], records[1]
    assert first["text"] != second["text"]
    strip = lambda t: t[t.index("]") + 1:]
    assert strip(first["text"]) == strip(second["text"])


def test_wechat_has_no_messaging_style():
    """取证记录 §3.1 结论一：真机微信不提供 MessagingStyle。"""
    summary = TOOL.summarize(TOOL.parse_dump(FIXTURE))
    assert summary["com.tencent.mm"]["has_messaging_style"] is False
    assert summary["com.tencent.mm"]["field_presence"]["messages"] == 0
    assert summary["com.tencent.mm"]["unread_prefix_count"] == 2


def test_dingtalk_title_carries_group_prefix():
    """取证记录 §3.2 结论一：标题形如「钉钉｜<群名>」。"""
    records = TOOL.parse_dump(FIXTURE)
    assert records[2]["title"] == "钉钉｜某班群"
    summary = TOOL.summarize(records)
    assert summary["com.alibaba.android.rimet"]["group_title_prefix_count"] == 1


def test_dingtalk_shape_is_sender_role_colon_body():
    records = TOOL.parse_dump(FIXTURE)
    shape = TOOL.body_shape(records[2]["text"])
    assert shape["sender"] == "王五(老师)"
    assert shape["colon"] == ":"
    assert shape["unread_prefix"] is False


def test_dingtalk_via_xiaomi_push_op_pkg():
    """取证记录 §3.2 结论三：经小米推送代发，opPkg 与 pkg 不同。"""
    records = TOOL.parse_dump(FIXTURE)
    assert records[2]["op_pkg"] == "com.xiaomi.xmsf"
    summary = TOOL.summarize(records)
    assert summary["com.alibaba.android.rimet"]["via_op_pkg"] == ["com.xiaomi.xmsf"]


def test_messaging_style_is_detected_when_present():
    records = TOOL.parse_dump(FIXTURE)
    assert records[3]["has_messages"] is True
    assert records[3]["message_count"] == 1
    summary = TOOL.summarize(records)
    assert summary["com.example.chat"]["has_messaging_style"] is True


def test_summary_counts_field_presence():
    summary = TOOL.summarize(TOOL.parse_dump(FIXTURE))
    assert summary["com.tencent.mm"]["count"] == 2
    assert summary["com.tencent.mm"]["field_presence"]["title"] == 2
    assert summary["com.tencent.mm"]["field_presence"]["text"] == 2
    assert summary["com.tencent.mm"]["field_presence"]["big_text"] == 0
    assert summary["com.tencent.mm"]["no_colon_count"] == 0


def test_body_shape_detects_full_width_colon():
    """飞书群通知形态：全角冒号。"""
    shape = TOOL.body_shape("李四：接口文档我更新好了")
    assert shape["colon"] == "："
    assert shape["sender"] == "李四"
    assert shape["unread_prefix"] is False


def test_body_shape_without_colon_keeps_whole_text():
    shape = TOOL.body_shape("明天 10:30 在三楼开会")
    assert shape["no_colon"] is True
    assert shape["sender"] is None
    assert shape["unread_prefix"] is False


def test_body_shape_of_missing_text_is_conservative():
    shape = TOOL.body_shape(None)
    assert shape["no_colon"] is True
    assert shape["sender"] is None
    assert shape["unread_prefix"] is False


def test_cli_writes_json_for_a_dump_file(tmp_path):
    dump = tmp_path / "dump.txt"
    dump.write_text(FIXTURE, encoding="utf-8")
    out = tmp_path / "shape.json"
    code = TOOL.main(["--input", str(dump), "--out", str(out)])
    assert code == 0
    data = json.loads(out.read_text(encoding="utf-8"))
    assert len(data["records"]) == 4
    assert data["summary"]["com.tencent.mm"]["unread_prefix_count"] == 2


def test_cli_can_filter_by_package(tmp_path):
    dump = tmp_path / "dump.txt"
    dump.write_text(FIXTURE, encoding="utf-8")
    out = tmp_path / "shape.json"
    TOOL.main(["--input", str(dump), "--pkg", "com.tencent.mm", "--out", str(out)])
    data = json.loads(out.read_text(encoding="utf-8"))
    assert set(data["summary"].keys()) == {"com.tencent.mm"}


def test_watch_returns_as_soon_as_target_pkg_appears(monkeypatch):
    """--watch：轮询到目标包出现就停（不必等满 timeout）。"""
    calls = []

    def fake_dump(serial):
        calls.append(serial)
        # 前两次只有微信，第三次才出现飞书
        if len(calls) < 3:
            return FIXTURE
        return FIXTURE + """
  NotificationRecord(0x44ce79f: pkg=com.ss.android.lark user=UserHandle{0} id=9 tag=null key=0|com.ss.android.lark|10002|null|10130: ...
    channel=message_channel
    postTime=2026-11-22 09:40:00.000000+0800
    extras:
      android.title = String (某群)
      android.text = String (张三:在吗)
"""

    monkeypatch.setattr(TOOL, "dump_from_adb", fake_dump)
    monkeypatch.setattr(TOOL.time, "sleep", lambda _s: None)
    text, waited = TOOL.watch("serial-x", "com.ss.android.lark", timeout_s=60, interval_s=1)
    assert waited >= 0
    assert "com.ss.android.lark" in text
    assert len(calls) == 3


def test_watch_times_out_and_returns_last_dump(monkeypatch):
    """--watch：等不到就超时退出，返回 -1（调用方据此说「没等到」）。"""
    monkeypatch.setattr(TOOL, "dump_from_adb", lambda serial: FIXTURE)
    monkeypatch.setattr(TOOL.time, "sleep", lambda _s: None)
    text, waited = TOOL.watch("serial-x", "com.ss.android.lark", timeout_s=0, interval_s=1)
    assert waited == -1
    assert "com.tencent.mm" in text


def test_watch_ignores_other_packages(monkeypatch):
    """--watch 只认目标包：通知栏里有别家的通知不算数。"""
    monkeypatch.setattr(TOOL, "dump_from_adb", lambda serial: FIXTURE)
    monkeypatch.setattr(TOOL.time, "sleep", lambda _s: None)
    _text, waited = TOOL.watch("serial-x", "com.ss.android.lark", timeout_s=0, interval_s=1)
    assert waited == -1


def test_cli_rejects_watch_with_input(tmp_path):
    """--watch 与 --input 互斥：--watch 是「现取并轮询」，给了文件就没法轮询。"""
    dump = tmp_path / "dump.txt"
    dump.write_text(FIXTURE, encoding="utf-8")
    try:
        TOOL.main(["--input", str(dump), "--watch", "--serial", "x"])
    except SystemExit as exc:
        assert "--watch" in str(exc)
    else:
        raise AssertionError("--watch 与 --input 同时给时应当退出")


def test_cli_rejects_watch_without_serial():
    try:
        TOOL.main(["--watch"])
    except SystemExit as exc:
        assert "--serial" in str(exc)
    else:
        raise AssertionError("--watch 没有 --serial 时应当退出")


def test_decode_text_reads_utf16_le_with_bom():
    """中文 Windows 的 PowerShell 重定向会把 dump 写成 UTF-16LE（带 BOM）。"""
    raw = b"\xff\xfe" + FIXTURE.encode("utf-16-le")
    assert TOOL.decode_text(raw) == FIXTURE


def test_cli_reads_a_utf16_dump_file(tmp_path):
    """真机跑一遍就踩到的坑：UTF-16 dump 按 UTF-8 读会一条记录都抽不到。"""
    dump = tmp_path / "dump_utf16.txt"
    dump.write_bytes(b"\xff\xfe" + FIXTURE.encode("utf-16-le"))
    out = tmp_path / "shape.json"
    code = TOOL.main(["--input", str(dump), "--out", str(out)])
    assert code == 0
    data = json.loads(out.read_text(encoding="utf-8"))
    assert len(data["records"]) == 4
    assert data["summary"]["com.tencent.mm"]["unread_prefix_count"] == 2


def test_utf16_and_utf8_dumps_give_the_same_conclusion():
    summary_utf8 = TOOL.summarize(TOOL.parse_dump(TOOL.decode_text(FIXTURE.encode("utf-8"))))
    summary_utf16 = TOOL.summarize(
        TOOL.parse_dump(TOOL.decode_text(b"\xff\xfe" + FIXTURE.encode("utf-16-le")))
    )
    assert summary_utf8 == summary_utf16
