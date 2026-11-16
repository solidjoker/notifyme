# -*- coding: utf-8 -*-
"""topic / due_window「码值 <-> 中文」映射的端云契约守卫。

同一条分析记录，App 侧显示什么、控制台侧显示什么，取决于两处互不相干的映射表：

  - Android：app/src/main/java/com/notifyme/android/TopicLabelCore.kt
             （码值常量 + normalizeTopic / normalizeDue 里的中文别名分支）
             app/src/main/res/values/strings.xml（topic_* / due_* 中文文案）
  - 服务端：server/console_classify.js（TOPIC_LABELS / TOPIC_ALIASES /
             DUE_LABELS / DUE_ALIASES）

任一端改了码值或文案而另一端没跟上，同一条记录在 App 里显示「工作任务」、
在控制台显示原始码 work_task（或反过来），用户会以为说的是两件事。
这种漂移靠端上单测发现不了（端上只看得见自己那半张表），
所以这里读源码做静态比对，再跑真 node 函数做运行时比对，CI 一红就说明两边该同步改了。

与 server/test_report_contract.py（上报字段契约）、server/test_queue_contract.py（排队字段契约）同族。
"""
import json
import os
import re
import shutil
import subprocess

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CORE_PATH = os.path.join(ROOT, "app", "src", "main", "java", "com",
                         "notifyme", "android", "TopicLabelCore.kt")
STRINGS_PATH = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")
CLASSIFY_PATH = os.path.join(HERE, "console_classify.js")
RUNNER_PATH = os.path.join(HERE, "test_console_classify_runner.js")


def _read(path):
    with open(path, "r", encoding="utf-8") as fh:
        # Kotlin / XML 是 CRLF，JS 是 CRLF，统一成 LF 再切行，免得定位函数体切空。
        return fh.read().replace("\r\n", "\n")


def _kt_consts(text):
    """抠出 Kotlin 的 const val 常量：名字 -> 字符串值。"""
    out = {}
    for name, value in re.findall(r'const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"', text):
        out[name] = value
    return out


def _kt_when(text, func_name):
    """抠出 normalizeTopic / normalizeDue 里 when 分支的映射。

    返回 (别名 -> 码值, 分支里出现过的码值集合)。分支形如：
        TOPIC_SCHEDULE, "日程安排", "日程" -> TOPIC_SCHEDULE
    左半边的引号串是中文别名，裸标识符是码值常量；右半边是归一后的码值常量。
    """
    start = text.find("fun %s(" % func_name)
    assert start >= 0, "TopicLabelCore.kt 里找不到 fun %s" % func_name
    end = text.find("\n    /**", start)
    body = text[start:end if end > 0 else len(text)]
    consts = _kt_consts(text)
    aliases = {}
    codes = set()
    for line in body.split("\n"):
        if "->" not in line:
            continue
        left, right = line.split("->", 1)
        target = right.strip()
        if target not in consts:
            continue
        for token in left.split(","):
            token = token.strip()
            if token.startswith('"') and token.endswith('"') and len(token) >= 2:
                aliases[token[1:-1]] = consts[target]
            elif token in consts:
                codes.add(consts[token])
    return aliases, codes


def _kt_list(text, name):
    """抠出 val NAME: List<String> = listOf(...) 的元素值。"""
    m = re.search(r'%s:\s*List<String>\s*=\s*\n?\s*listOf\(([^)]*)\)' % name, text)
    if not m:
        return []
    consts = _kt_consts(text)
    out = []
    for token in m.group(1).split(","):
        token = token.strip()
        if token in consts:
            out.append(consts[token])
    return out


def _js_block(text, name):
    """取 JS 里 var NAME = { ... } 的花括号内容（按深度配对，不靠正则找右括号）。"""
    m = re.search(r'var\s+%s\s*=\s*\{' % name, text)
    assert m, "console_classify.js 里找不到 var %s" % name
    i = text.find("{", m.start())
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    raise AssertionError("var %s 的花括号没闭合" % name)


def _js_labels(block):
    """JS 对象里的裸键 -> 单引号字符串值。"""
    out = {}
    for key, value in re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'([^']*)'", block):
        out[key] = value
    return out


def _js_aliases(block):
    """JS 对象里的单引号键 -> 单引号字符串值（别名表）。"""
    out = {}
    for key, value in re.findall(r"'([^']*)'\s*:\s*'([^']*)'", block):
        out[key] = value
    return out


def _xml_strings(text):
    """strings.xml 里的 topic_* / due_* 文案：资源名 -> 中文。"""
    out = {}
    for name, value in re.findall(r'<string name="(topic_[a-z_]+|due_[a-z_]+)">([^<]*)</string>', text):
        out[name] = value
    return out


CORE_TEXT = _read(CORE_PATH)
STRINGS_TEXT = _read(STRINGS_PATH)
CLASSIFY_TEXT = _read(CLASSIFY_PATH)

CONSTS = _kt_consts(CORE_TEXT)
TOPIC_CODES = set(v for k, v in CONSTS.items() if k.startswith("TOPIC_"))
DUE_CODES = set(v for k, v in CONSTS.items() if k.startswith("DUE_"))

APP_TOPIC_ALIASES, APP_TOPIC_BRANCH_CODES = _kt_when(CORE_TEXT, "normalizeTopic")
APP_DUE_ALIASES, APP_DUE_BRANCH_CODES = _kt_when(CORE_TEXT, "normalizeDue")

CON_TOPIC_LABELS = _js_labels(_js_block(CLASSIFY_TEXT, "TOPIC_LABELS"))
CON_TOPIC_ALIASES = _js_aliases(_js_block(CLASSIFY_TEXT, "TOPIC_ALIASES"))
CON_DUE_LABELS = _js_labels(_js_block(CLASSIFY_TEXT, "DUE_LABELS"))
CON_DUE_ALIASES = _js_aliases(_js_block(CLASSIFY_TEXT, "DUE_ALIASES"))

STRINGS = _xml_strings(STRINGS_TEXT)


def _node_exe():
    exe = shutil.which("node") or shutil.which("nodejs")
    if not exe:
        if os.environ.get("REQUIRE_NODE") == "1":
            pytest.fail("REQUIRE_NODE=1 但 PATH 里找不到 node：控制台 topic/due 契约必须能跑真逻辑。")
        pytest.skip("node 不可用，跳过控制台 topic/due 契约的运行时比对")
    return exe


@pytest.fixture(scope="session")
def node_exe():
    """本文件的运行时比对需要一个 node（CI 里 REQUIRE_NODE=1 时缺 node 直接 fail）。"""
    return _node_exe()


def _call(node_exe, fn, *args):
    """调 server/test_console_classify_runner.js 里的真函数。"""
    payload = json.dumps({"fn": fn, "args": list(args)}, ensure_ascii=False)
    proc = subprocess.run(
        [node_exe, RUNNER_PATH],
        input=payload,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=30,
    )
    assert proc.returncode == 0, "runner 调用失败：fn=%s 退出码=%s stderr=%r" % (fn, proc.returncode, proc.stderr)
    data = json.loads(proc.stdout)
    assert data.get("ok") is True, "runner 未返回 ok：fn=%s stdout=%r" % (fn, proc.stdout)
    return data.get("out")


def test_topic_code_sets_are_identical():
    """topic 码值集合两端一致（filtered 也算正式码值）。"""
    assert TOPIC_CODES == set(CON_TOPIC_LABELS), (
        "topic 码值不一致：App=%s 控制台=%s" % (sorted(TOPIC_CODES), sorted(CON_TOPIC_LABELS))
    )


def test_due_code_sets_are_identical():
    """due_window 码值集合两端一致（none 也算正式码值）。"""
    assert DUE_CODES == set(CON_DUE_LABELS), (
        "due 码值不一致：App=%s 控制台=%s" % (sorted(DUE_CODES), sorted(CON_DUE_LABELS))
    )


def test_every_code_appears_in_the_normalizer_branches():
    """每个码值都要在归一分支里出现（不然 App 认不出自己写的码值）。"""
    assert APP_TOPIC_BRANCH_CODES == TOPIC_CODES, (
        "App 的 normalizeTopic 分支没覆盖全部 topic 码值：%s" % sorted(TOPIC_CODES - APP_TOPIC_BRANCH_CODES)
    )
    assert APP_DUE_BRANCH_CODES == DUE_CODES, (
        "App 的 normalizeDue 分支没覆盖全部 due 码值：%s" % sorted(DUE_CODES - APP_DUE_BRANCH_CODES)
    )


def test_topic_labels_are_identical():
    """topic 中文文案：strings.xml 与控制台 TOPIC_LABELS 逐条相等。"""
    app = {}
    for code in TOPIC_CODES:
        name = "topic_" + code
        assert name in STRINGS, "strings.xml 缺 %s" % name
        app[code] = STRINGS[name]
    assert app == CON_TOPIC_LABELS, (
        "topic 文案不一致：App=%s 控制台=%s" % (app, CON_TOPIC_LABELS)
    )


def test_due_labels_are_identical():
    """due_window 中文文案：strings.xml 与控制台 DUE_LABELS 逐条相等。"""
    app = {}
    for code in DUE_CODES:
        name = "due_" + code
        assert name in STRINGS, "strings.xml 缺 %s" % name
        app[code] = STRINGS[name]
    assert app == CON_DUE_LABELS, (
        "due 文案不一致：App=%s 控制台=%s" % (app, CON_DUE_LABELS)
    )


def test_topic_alias_maps_are_identical():
    """topic 中文别名表两端一致（模型直接输出中文桶名时要能归一）。"""
    assert APP_TOPIC_ALIASES == CON_TOPIC_ALIASES, (
        "topic 别名表不一致：App=%s 控制台=%s" % (APP_TOPIC_ALIASES, CON_TOPIC_ALIASES)
    )


def test_due_alias_maps_are_identical():
    """due_window 中文别名表两端一致。"""
    assert APP_DUE_ALIASES == CON_DUE_ALIASES, (
        "due 别名表不一致：App=%s 控制台=%s" % (APP_DUE_ALIASES, CON_DUE_ALIASES)
    )


def test_every_topic_label_is_also_an_alias():
    """每个桶名本身必须能在别名表里找到（模型抄题面中文时要能认）。"""
    for code, label in CON_TOPIC_LABELS.items():
        assert label in APP_TOPIC_ALIASES, "App 别名表缺桶名 %s（%s）" % (label, code)
        assert label in CON_TOPIC_ALIASES, "控制台别名表缺桶名 %s（%s）" % (label, code)


def test_every_due_label_is_also_an_alias():
    """每个截止桶名本身必须能在别名表里找到。"""
    for code, label in CON_DUE_LABELS.items():
        assert label in APP_DUE_ALIASES, "App 别名表缺桶名 %s（%s）" % (label, code)
        assert label in CON_DUE_ALIASES, "控制台别名表缺桶名 %s（%s）" % (label, code)


def test_no_orphan_string_resources():
    """strings.xml 里不许留孤立的 topic_* / due_* 资源（多一个就是只改了一端）。"""
    topic_names = set(n for n in STRINGS if n.startswith("topic_"))
    due_names = set(n for n in STRINGS if n.startswith("due_"))
    assert topic_names == set("topic_" + c for c in TOPIC_CODES), (
        "strings.xml 的 topic_* 资源与码值集合对不上：%s" % sorted(topic_names)
    )
    assert due_names == set("due_" + c for c in DUE_CODES), (
        "strings.xml 的 due_* 资源与码值集合对不上：%s" % sorted(due_names)
    )


def test_code_lists_exclude_the_special_codes():
    """TOPIC_CODES 不含 filtered、DUE_CODES 不含 none（这两个是特殊值不是桶）。"""
    assert _kt_list(CORE_TEXT, "TOPIC_CODES") == [
        "work_task", "schedule", "notice", "smalltalk", "risk"
    ]
    assert _kt_list(CORE_TEXT, "DUE_CODES") == ["today", "tomorrow", "this_week", "later"]


def test_alias_targets_are_known_codes():
    """别名表的目标码值必须都在码值集合里（否则归一到一个不存在的桶）。"""
    for alias, code in APP_TOPIC_ALIASES.items():
        assert code in TOPIC_CODES, "App topic 别名 %s 指向未知码值 %s" % (alias, code)
    for alias, code in CON_TOPIC_ALIASES.items():
        assert code in TOPIC_CODES, "控制台 topic 别名 %s 指向未知码值 %s" % (alias, code)
    for alias, code in APP_DUE_ALIASES.items():
        assert code in DUE_CODES, "App due 别名 %s 指向未知码值 %s" % (alias, code)
    for alias, code in CON_DUE_ALIASES.items():
        assert code in DUE_CODES, "控制台 due 别名 %s 指向未知码值 %s" % (alias, code)


def test_runtime_normalizer_matches_the_static_tables(node_exe):
    """运行时：控制台归一函数对每个码值 / 别名的结果与静态表一致。"""
    for code in sorted(TOPIC_CODES):
        assert _call(node_exe, "normalizeTopic", code) == code
    for alias, code in sorted(CON_TOPIC_ALIASES.items()):
        assert _call(node_exe, "normalizeTopic", alias) == code
    for code in sorted(DUE_CODES):
        assert _call(node_exe, "normalizeDue", code) == code
    for alias, code in sorted(CON_DUE_ALIASES.items()):
        assert _call(node_exe, "normalizeDue", alias) == code


def test_runtime_labels_match_the_app_strings(node_exe):
    """运行时：控制台展示文案与 App 的 strings.xml 文案逐条相等。"""
    for code in sorted(TOPIC_CODES):
        assert _call(node_exe, "topicLabel", code) == STRINGS["topic_" + code]
    for code in sorted(DUE_CODES):
        assert _call(node_exe, "dueLabel", code) == STRINGS["due_" + code]


def test_unknown_value_is_echoed_verbatim(node_exe):
    """未知值原样回显（两端同一口径：吞掉就是丢信息）。"""
    assert "else -> text" in CORE_TEXT, "App 的归一分支少了 else -> text（未知值会被吞成默认桶）"
    assert _call(node_exe, "normalizeTopic", "未知主题") == "未知主题"
    assert _call(node_exe, "normalizeDue", "某月某日") == "某月某日"
    assert _call(node_exe, "normalizeTopic", "  ") == ""
