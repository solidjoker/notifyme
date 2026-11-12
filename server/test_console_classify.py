# -*- coding: utf-8 -*-
"""控制台前端「归类」纯函数核心（server/console_classify.js）的真逻辑单测。

为什么不在 Python 里重写一份规则：server/console_classify.js 是控制台归类规则的
单一事实源（浏览器内联 + node 直接 require 两端共用）。Python 复刻版必然与它漂移，
所以这里让 pytest 通过 subprocess 调 node 跑 server/test_console_classify_runner.js：

    pytest -> subprocess(node runner) -> require('./console_classify.js') -> 真实纯函数

断言全部落在 runner 吐出的 JSON 上（成功时 {"ok": true, "out": ...}），
所以测的是浏览器里跑的那份真逻辑，而不是一份镜像实现。

覆盖维度：来源 App 归一化与标签 / 会话复合身份 / 发送者归一化与分组 /
来源 App 分区 / 重要度档位与待办排序分区 / 顾问建议类型分组 / runner 协议本身。

环境：需要 node（或 nodejs）。找不到时默认 skip；环境变量 REQUIRE_NODE=1 时
必须 fail（让 CI 立刻暴露问题，而不是静默跳过）。
"""
import json
import os
import shutil
import subprocess

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
RUNNER_PATH = os.path.join(HERE, "test_console_classify_runner.js")

# 契约常量：测试侧只留「跨用例都要用」的几个，其余从被测模块读，避免两边漂移
PKG_WX = "com.tencent.mm"
SEP = "\u0001"


@pytest.fixture(scope="session")
def node_exe():
    """定位 node 可执行文件；找不到时按 REQUIRE_NODE 决定 skip 还是 fail。"""
    exe = shutil.which("node") or shutil.which("nodejs")
    if not exe:
        if os.environ.get("REQUIRE_NODE") == "1":
            pytest.fail(
                "环境变量 REQUIRE_NODE=1，但 PATH 里找不到 node/nodejs："
                "控制台归类纯函数单测必须能跑真逻辑（server/console_classify.js）。"
            )
        pytest.skip("node 不可用，跳过前端归类纯函数单测")
    return exe


@pytest.fixture(scope="session")
def call(node_exe):
    """返回调用器：call("fn", *args) -> 该纯函数的真实返回值（JSON 结构）。"""

    def _call(fn, *args):
        payload = {"fn": fn, "args": list(args)}
        proc = subprocess.run(
            [node_exe, RUNNER_PATH],
            input=json.dumps(payload, ensure_ascii=False),
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )
        assert proc.returncode == 0, (
            f"runner 调用失败：fn={fn} 退出码={proc.returncode} stderr={proc.stderr!r}"
        )
        data = json.loads(proc.stdout)
        assert data.get("ok") is True, f"runner 未返回 ok：fn={fn} stdout={proc.stdout!r}"
        return data.get("out")

    return _call


def _run_raw(node_exe, payload_text):
    """直接跑 runner（不走 ok 协议），返回 CompletedProcess。"""
    return subprocess.run(
        [node_exe, RUNNER_PATH],
        input=payload_text,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=30,
    )


# --------------------------------------------------------------------------
# 常量与协议
# --------------------------------------------------------------------------


def test_version_is_v1(call):
    """VERSION 常量为 "v1"（前端归类规则的版本号）。"""
    version = call("__get__", "VERSION")
    assert version == "v1", f"VERSION 应为 v1，实际 {version!r}"


def test_constants_exposed(call):
    """模块导出的常量齐全：默认包名、会话分隔符、两类优先序、重要度标签。"""
    assert call("__get__", "PKG_WX") == PKG_WX, f"PKG_WX 应为 {PKG_WX}"
    assert call("__get__", "SEP") == SEP, f"SEP 应为 U+0001，实际 {call('__get__', 'SEP')!r}"
    assert call("__get__", "ADVISOR_TYPE_ORDER") == [
        "watchlist",
        "threshold",
        "prompt",
        "other",
    ], "顾问建议类型优先序不对"
    assert call("__get__", "IMPORTANCE_ORDER") == ["high", "mid", "low", ""], (
        "重要度分区顺序不对：%r" % (call("__get__", "IMPORTANCE_ORDER"),)
    )
    labels = call("__get__", "IMPORTANCE_LABELS")
    assert labels == {
        "high": "高重要度",
        "mid": "中重要度",
        "low": "低重要度",
        "": "未标重要度",
    }, f"IMPORTANCE_LABELS 不对：{labels!r}"


def test_runner_rejects_unknown_function(node_exe):
    """runner 收到未知函数名 -> stdout {"error": "unknown_fn"}，退出码 2。"""
    proc = _run_raw(node_exe, json.dumps({"fn": "noSuchFn", "args": []}))
    assert proc.returncode == 2, f"未知函数名应退出码 2，实际 {proc.returncode}"
    data = json.loads(proc.stdout)
    assert data == {"error": "unknown_fn"}, f"未知函数名应返回 unknown_fn，实际 {data!r}"


def test_runner_rejects_bad_json(node_exe):
    """runner 收到非 JSON 输入 -> stdout {"error": "bad_json"}，退出码 2。"""
    proc = _run_raw(node_exe, "not-json")
    assert proc.returncode == 2, f"坏 JSON 应退出码 2，实际 {proc.returncode}"
    data = json.loads(proc.stdout)
    assert data == {"error": "bad_json"}, f"坏 JSON 应返回 bad_json，实际 {data!r}"


# --------------------------------------------------------------------------
# 来源 App 归一化 / 标签 / 会话复合身份
# --------------------------------------------------------------------------


def test_norm_pkg_falls_back_to_wechat(call):
    """normPkg：缺省 / 空值 -> 微信默认包名；有值则原样透传。"""
    assert call("normPkg", None) == PKG_WX, "normPkg(None) 应回落到微信包名"
    assert call("normPkg", "") == PKG_WX, "normPkg('') 应回落到微信包名"
    assert call("normPkg", "com.x") == "com.x", "normPkg 应原样透传 com.x"


def test_pkg_label_hit_and_miss(call):
    """pkgLabel：命中映射用映射值；未命中原样返回包名。"""
    labels = {PKG_WX: "微信", "com.x": "示例"}
    assert call("pkgLabel", PKG_WX, labels) == "微信", "命中映射应返回展示名"
    assert call("pkgLabel", "com.other", labels) == "com.other", "未命中应原样返回包名"
    assert call("pkgLabel", None, labels) == "微信", "缺省包名应按微信取展示名"


def test_ck_of_joins_pkg_and_conversation(call):
    """ckOf：会话复合身份 = 包名 + 分隔符 + 会话名。"""
    got = call("ckOf", {"pkg": "com.x", "conversation": "群A"})
    assert got == "com.x" + SEP + "群A", f"ckOf 拼接过：{got!r}"


def test_ck_of_null_conversation_is_empty_tail(call):
    """ckOf：会话名为 null 时尾部为空串（只留包名 + 分隔符）。"""
    got = call("ckOf", {"pkg": "com.x", "conversation": None})
    assert got == "com.x" + SEP, f"ckOf(null 会话) 应为 包名+分隔符，实际 {got!r}"


# --------------------------------------------------------------------------
# 发送者归一化
# --------------------------------------------------------------------------


def test_sender_key_trims_whitespace(call):
    """senderKey：去首尾空白。"""
    got = call("senderKey", "  李四  ")
    assert got == "李四", f"应去掉首尾空白，实际 {got!r}"


def test_sender_key_drops_zero_width(call):
    """senderKey：去零宽字符（U+200B 等不可见字符）。"""
    got = call("senderKey", "王\u200b五")
    assert got == "王五", f"应去掉零宽字符，实际 {got!r}"


def test_sender_key_drops_ascii_colon_prefix(call):
    """senderKey：去「名字:」半角冒号前缀（来源把发言人写在冒号前）。"""
    got = call("senderKey", "  张三: 你好 ")
    assert got == "张三", f"应去掉「张三:」前缀，实际 {got!r}"


def test_sender_key_drops_fullwidth_colon_prefix(call):
    """senderKey：去「名字：」全角冒号前缀。"""
    got = call("senderKey", "张三：你好")
    assert got == "张三", f"应去掉「张三：」前缀，实际 {got!r}"


def test_sender_key_blank_is_unknown(call):
    """senderKey：全空 -> 「未知发送者」。"""
    assert call("senderKey", "") == "未知发送者", "空串应归为未知发送者"
    assert call("senderKey", "   ") == "未知发送者", "纯空白应归为未知发送者"
    assert call("senderKey", None) == "未知发送者", "null 应归为未知发送者"


# --------------------------------------------------------------------------
# 按发送者分组
# --------------------------------------------------------------------------


def test_group_by_sender_counts_and_messages(call):
    """groupBySender：3 条消息 2 个发送者 -> 2 组，count 与 messages 长度正确。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300, "id": "m1"},
            {"sender": "李四", "timestamp": 200, "id": "m2"},
            {"sender": "张三", "timestamp": 100, "id": "m3"},
        ],
    )
    assert len(groups) == 2, f"应分 2 组，实际 {len(groups)} 组：{groups!r}"
    by_key = {g["key"]: g for g in groups}
    assert set(by_key) == {"张三", "李四"}, f"分组键不对：{sorted(by_key)}"
    assert by_key["张三"]["count"] == 2, f"张三 count 应为 2，实际 {by_key['张三']['count']}"
    assert len(by_key["张三"]["messages"]) == 2, "张三 messages 长度应为 2"
    assert by_key["李四"]["count"] == 1, f"李四 count 应为 1，实际 {by_key['李四']['count']}"
    assert len(by_key["李四"]["messages"]) == 1, "李四 messages 长度应为 1"


def test_group_by_sender_order_is_first_appearance(call):
    """groupBySender：分组顺序 = 首次出现顺序（入参时间倒序时最近说话的人在前面）。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300},
            {"sender": "李四", "timestamp": 200},
            {"sender": "张三", "timestamp": 100},
        ],
    )
    keys = [g["key"] for g in groups]
    assert keys == ["张三", "李四"], f"分组顺序应按首次出现，实际 {keys}"


def test_group_by_sender_last_ts_is_max(call):
    """groupBySender：lastTs = 组内最大 timestamp。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 100},
            {"sender": "张三", "timestamp": 900},
            {"sender": "张三", "timestamp": 300},
        ],
    )
    assert len(groups) == 1, f"应只分 1 组，实际 {len(groups)}"
    assert groups[0]["lastTs"] == 900, f"lastTs 应为组内最大 900，实际 {groups[0]['lastTs']}"


def test_group_by_sender_empty_list(call):
    """groupBySender：空数组 -> []。"""
    assert call("groupBySender", []) == [], "空数组应返回 []"


def test_group_by_sender_normalizes_same_sender(call):
    """groupBySender：同一个人名字带不同零宽字符 / 空白 -> 归到同一组。"""
    groups = call(
        "groupBySender",
        [
            {"sender": "张三", "timestamp": 300},
            {"sender": "张\u200b三", "timestamp": 200},
            {"sender": "  张三  ", "timestamp": 100},
        ],
    )
    assert len(groups) == 1, f"归一化后应只分 1 组，实际 {len(groups)} 组：{groups!r}"
    assert groups[0]["key"] == "张三", f"组键应为张三，实际 {groups[0]['key']!r}"
    assert groups[0]["count"] == 3, f"count 应为 3，实际 {groups[0]['count']}"


# --------------------------------------------------------------------------
# 按来源 App 分区
# --------------------------------------------------------------------------


def test_group_by_pkg_partitions_in_input_order(call):
    """groupByPkg：3 个 item 分 2 个包 -> 2 个分区，count / label 正确，items 保序。"""
    labels = {PKG_WX: "微信", "com.x": "示例"}
    groups = call(
        "groupByPkg",
        [
            {"pkg": PKG_WX, "id": 1},
            {"pkg": "com.x", "id": 2},
            {"pkg": PKG_WX, "id": 3},
        ],
        labels,
    )
    assert len(groups) == 2, f"应分 2 个分区，实际 {len(groups)}"
    assert [g["pkg"] for g in groups] == [PKG_WX, "com.x"], (
        "分区应保持传入顺序，实际 %r" % ([g["pkg"] for g in groups],)
    )
    assert groups[0]["label"] == "微信", f"首个分区 label 应为微信，实际 {groups[0]['label']!r}"
    assert groups[0]["count"] == 2, f"微信分区 count 应为 2，实际 {groups[0]['count']}"
    assert [it["id"] for it in groups[0]["items"]] == [1, 3], "微信分区 items 应保序"
    assert groups[1]["label"] == "示例", f"次个分区 label 应为示例，实际 {groups[1]['label']!r}"
    assert groups[1]["count"] == 1, f"示例分区 count 应为 1，实际 {groups[1]['count']}"


def test_group_by_pkg_empty_list(call):
    """groupByPkg：空数组 -> []。"""
    assert call("groupByPkg", [], {}) == [], "空数组应返回 []"


# --------------------------------------------------------------------------
# 重要度档位 / 待办排序与分区
# --------------------------------------------------------------------------


def test_importance_rank(call):
    """importanceRank：high=0 / mid=1 / low=2，其它（含未知值）= 3 同档排最后。

    契约：未知重要度（如 "urgent"）与「未标重要度」同属最后一档（rank=3），
    不能因为值没被识别就排到更后面 —— 否则待办分区里会凭空多出一档。
    """
    assert call("importanceRank", "high") == 0, "high 应为 0"
    assert call("importanceRank", "mid") == 1, "mid 应为 1"
    assert call("importanceRank", "low") == 2, "low 应为 2"
    assert call("importanceRank", "") == 3, f"空重要度应为 3，实际 {call('importanceRank', '')}"
    assert call("importanceRank", None) == 3, "null 应与空串同档"
    assert call("importanceRank", "urgent") == 3, (
        "未知值应与未标重要度同档为 3，实际 %r" % (call("importanceRank", "urgent"),)
    )
    assert call("importanceRank", "urgent") == call("importanceRank", ""), (
        "未知值与空串必须同档"
    )


def test_sort_todos_orders_by_importance_then_received_at(call):
    """sortTodos：高重要度在前，同级按 received_at 倒序（新的在前）。"""
    todos = [
        {"id": "a", "importance": "high", "received_at": 100},
        {"id": "b", "importance": "high", "received_at": 300},
        {"id": "c", "importance": "mid", "received_at": 200},
        {"id": "d", "importance": "low", "received_at": 50},
    ]
    out = call("sortTodos", todos)
    ids = [t["id"] for t in out]
    assert ids == ["b", "a", "c", "d"], f"排序结果不对：{ids}"


def test_sort_todos_returns_new_array(call):
    """sortTodos：返回新数组，不改动入参顺序。"""
    todos = [
        {"id": "a", "importance": "low", "received_at": 100},
        {"id": "b", "importance": "high", "received_at": 300},
    ]
    out = call("sortTodos", todos)
    assert [t["id"] for t in todos] == ["a", "b"], f"入参顺序被改动了：{todos!r}"
    assert [t["id"] for t in out] == ["b", "a"], f"返回数组顺序不对：{out!r}"
    assert out is not todos, "sortTodos 应返回新数组"


def test_group_todos_by_importance_partitions(call):
    """groupTodosByImportance：分区 key 顺序 high -> mid -> low -> ""，label/count 正确。"""
    groups = call(
        "groupTodosByImportance",
        [
            {"id": 1, "importance": "low"},
            {"id": 2, "importance": "high"},
            {"id": 3, "importance": ""},
            {"id": 4, "importance": "mid"},
            {"id": 5, "importance": "high"},
        ],
    )
    assert [g["key"] for g in groups] == ["high", "mid", "low", ""], (
        "分区顺序应为 high->mid->low->空，实际 %r" % ([g["key"] for g in groups],)
    )
    labels = {g["key"]: g["label"] for g in groups}
    assert labels["high"] == "高重要度", f"high 标签不对：{labels['high']!r}"
    assert labels["mid"] == "中重要度", f"mid 标签不对：{labels['mid']!r}"
    assert labels["low"] == "低重要度", f"low 标签不对：{labels['low']!r}"
    assert labels[""] == "未标重要度", f"空档标签不对：{labels['']!r}"
    counts = {g["key"]: g["count"] for g in groups}
    assert counts["high"] == 2, f"high count 应为 2，实际 {counts['high']}"
    assert counts["mid"] == 1, f"mid count 应为 1，实际 {counts['mid']}"
    assert counts["low"] == 1, f"low count 应为 1，实际 {counts['low']}"
    assert counts[""] == 1, f"空档 count 应为 1，实际 {counts['']}"


def test_group_todos_by_importance_unknown_value_goes_to_empty_key(call):
    """groupTodosByImportance：未知重要度（如 "urgent"）归到 key=""（未标重要度）。"""
    groups = call("groupTodosByImportance", [{"id": 1, "importance": "urgent"}])
    assert len(groups) == 1, f"应只分 1 个分区，实际 {len(groups)}"
    assert groups[0]["key"] == "", f"未知重要度应归到空 key，实际 {groups[0]['key']!r}"
    assert groups[0]["label"] == "未标重要度", (
        f"未知重要度分区标签应为未标重要度，实际 {groups[0]['label']!r}"
    )
    assert groups[0]["count"] == 1, f"count 应为 1，实际 {groups[0]['count']}"


# --------------------------------------------------------------------------
# 顾问建议分组
# --------------------------------------------------------------------------


def test_group_suggestions_uses_fixed_type_order(call):
    """groupSuggestions：输入 prompt,watchlist -> 输出 watchlist,prompt（固定优先序）。"""
    groups = call(
        "groupSuggestions",
        [{"type": "prompt", "title": "p"}, {"type": "watchlist", "title": "w"}],
    )
    types = [g["type"] for g in groups]
    assert types == ["watchlist", "prompt"], f"建议类型顺序不对：{types}"


def test_group_suggestions_unknown_type_goes_last(call):
    """groupSuggestions：未知类型排在所有已知类型之后。"""
    groups = call(
        "groupSuggestions",
        [
            {"type": "custom", "title": "c"},
            {"type": "watchlist", "title": "w"},
            {"type": "prompt", "title": "p"},
        ],
    )
    types = [g["type"] for g in groups]
    assert types == ["watchlist", "prompt", "custom"], f"未知类型应排最后，实际 {types}"


def test_group_suggestions_items_sorted_by_title(call):
    """groupSuggestions：同一类型内按 title 升序。"""
    groups = call(
        "groupSuggestions",
        [
            {"type": "prompt", "title": "c"},
            {"type": "prompt", "title": "a"},
            {"type": "prompt", "title": "b"},
        ],
    )
    titles = [it["title"] for g in groups for it in g["items"]]
    assert titles == ["a", "b", "c"], f"组内应按标题升序，实际 {titles}"


def test_group_suggestions_empty_list(call):
    """groupSuggestions：空数组 -> []。"""
    assert call("groupSuggestions", []) == [], "空数组应返回 []"

# --------------------------------------------------------------------------
# 原型键回归：外部数据（群成员名 / 来源包名 / 建议类型）可能正好叫
# "__proto__" 或 "constructor"，分组时绝不能把整页控制台带崩
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString", "hasOwnProperty"],
)
def test_group_by_sender_survives_prototype_keys(call, name):
    """groupBySender：键名撞原型属性时正常分组，不抛异常。"""
    out = call("groupBySender", [{"sender": name, "timestamp": 1}])
    assert len(out) == 1, f"应只分 1 组，实际 {len(out)}"
    assert out[0]["key"] == name, f"分组键应为 {name!r}，实际 {out[0]['key']!r}"
    assert out[0]["count"] == 1, f"count 应为 1，实际 {out[0]['count']}"
    assert len(out[0]["messages"]) == 1, "消息应原样挂在分组里"


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString"],
)
def test_group_by_pkg_survives_prototype_keys(call, name):
    """groupByPkg：来源包名撞原型属性时正常分区，不抛异常。"""
    out = call("groupByPkg", [{"pkg": name}], {}, None)
    assert len(out) == 1, f"应只分 1 区，实际 {len(out)}"
    assert out[0]["pkg"] == name, f"分区 pkg 应为 {name!r}，实际 {out[0]['pkg']!r}"
    assert out[0]["count"] == 1, f"count 应为 1，实际 {out[0]['count']}"


@pytest.mark.parametrize(
    "name",
    ["__proto__", "constructor", "toString"],
)
def test_group_suggestions_survives_prototype_keys(call, name):
    """groupSuggestions：建议类型撞原型属性时正常分组，不抛异常。"""
    out = call("groupSuggestions", [{"type": name, "title": "t"}])
    assert len(out) == 1, f"应只分 1 组，实际 {len(out)}"
    assert out[0]["type"] == name, f"分组类型应为 {name!r}，实际 {out[0]['type']!r}"
    assert len(out[0]["items"]) == 1, "建议应原样挂在分组里"


def test_group_todos_by_importance_survives_prototype_keys(call):
    """groupTodosByImportance：分区键来自固定常量，但计数字典也不能被原型键污染。"""
    out = call(
        "groupTodosByImportance",
        [{"id": 1, "importance": "high"}, {"id": 2, "importance": "urgent"}],
    )
    keys = [g["key"] for g in out]
    assert keys == ["high", ""], f"分区顺序应为 high->空，实际 {keys}"
    ranks = {g["key"]: g["rank"] for g in out}
    assert ranks["high"] == 0, "high rank 应为 0"
    assert ranks[""] == 3, f"未标重要度 rank 应为 3，实际 {ranks['']}"

