# -*- coding: utf-8 -*-
"""排队任务「端上 <-> 服务端」字段契约守卫。

端上（app/src/main/java/com/notifyme/android/QueueStateCore.kt）手拼上报报文，
服务端（server/storage.py 的 _normalize_queue_task + server/routes.py 的 receive_queue）
逐条校验入库。两边的字段名、枚举值、长度上限必须一致：

  - 端上多写一个字段 -> 服务端静默丢弃 -> 数据对不上，控制台看不到；
  - 服务端多要一个字段 -> 端侧没写 -> 整批被拒收（逐条拒收）-> 队列卡住。

这类漂移在真机上表现为「控制台排队数对不上」，很难一眼定位，
所以这里直接读源码做静态比对，CI 一红就说明两边该同步改了。
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KOTLIN_PATH = os.path.join(ROOT, "app", "src", "main", "java", "com",
                           "notifyme", "android", "QueueStateCore.kt")
STORAGE_PATH = os.path.join(HERE, "storage.py")
ROUTES_PATH = os.path.join(HERE, "routes.py")

BS = chr(92)
NL = chr(10)


def _read(path):
    with open(path, "r", encoding="utf-8") as fh:
        # 统一换行符：Kotlin 源码是 CRLF，Python 源码是 LF；
        # 不归一化的话按换行切函数体的定位会切不到。
        return fh.read().replace(chr(13) + chr(10), chr(10))


def _slice(text, start, end):
    a = text.find(start)
    if a < 0:
        return ""
    b = text.find(end, a + len(start))
    if b < 0:
        return text[a:]
    return text[a:b]


def _kt_payload_keys(body):
    """抠出 buildPayload 里真正写进 JSON 的键名（含顶层与 task 级）。"""
    out = []
    needle = BS + '"'
    i = 0
    while True:
        j = body.find(needle, i)
        if j < 0:
            break
        k = body.find('":', j + 2)
        if k < 0:
            break
        name = body[j + 2:k].rstrip(BS)
        if name and name.replace("_", "").isalnum():
            out.append(name)
        i = k + 2
    return out


def _py_get_keys(body):
    """抠出 Python 源码里 .get("...") 读到的键名。"""
    out = []
    needle = '.get("'
    i = 0
    while True:
        j = body.find(needle, i)
        if j < 0:
            break
        k = body.find('"', j + len(needle))
        if k < 0:
            break
        out.append(body[j + len(needle):k])
        i = k + 1
    return out


def _kt_const_strings(text, prefix):
    out = set()
    needle = "const val " + prefix
    i = 0
    while True:
        j = text.find(needle, i)
        if j < 0:
            break
        k = text.find('"', j)
        m = text.find('"', k + 1)
        if k < 0 or m < 0:
            break
        out.add(text[k + 1:m])
        i = m + 1
    return out


def _kt_const_int(text, name):
    needle = "const val " + name + " = "
    i = text.find(needle)
    if i < 0:
        return None
    j = i + len(needle)
    k = j
    while k < len(text) and text[k] in "0123456789":
        k += 1
    if k == j:
        return None
    return int(text[j:k])


def _py_str_const(text, name):
    """取 NAME = 字符串字面量 形式的常量值。"""
    needle = name + " = "
    i = text.find(needle)
    if i < 0:
        return None
    k = text.find(chr(34), i)
    if k < 0:
        return None
    m = text.find(chr(34), k + 1)
    if m < 0:
        return None
    return text[k + 1:m]


def _py_tuple(text, name):
    """取元组常量的实际取值。

    两种写法都要认：
      QUEUE_STATES = (queued, running)  —— 直接写字面量
      QUEUE_STATES = (QUEUE_STATE_QUEUED, ...) —— 先起名再组元组，
    后者会跟着常量名解析回字面量。
    """
    needle = name + " = ("
    i = text.find(needle)
    if i < 0:
        return set()
    j = text.find(")", i)
    inner = text[i + len(needle):j]
    out = set()
    for item in inner.split(","):
        item = item.strip()
        if not item:
            continue
        if item.startswith(chr(34)) and item.endswith(chr(34)):
            out.add(item.strip(chr(34)))
            continue
        lit = _py_str_const(text, item)
        if lit is not None:
            out.add(lit)
    return out


def _py_int(text, name):
    needle = name + " = "
    i = text.find(needle)
    if i < 0:
        return None
    j = i + len(needle)
    k = j
    while k < len(text) and text[k] in "0123456789":
        k += 1
    if k == j:
        return None
    return int(text[j:k])


KT = _read(KOTLIN_PATH)
STORAGE = _read(STORAGE_PATH)
ROUTES = _read(ROUTES_PATH)

KT_PAYLOAD = _kt_payload_keys(_slice(KT, "fun buildPayload", NL + "    fun "))
KT_TASK_FIELDS = set(k for k in KT_PAYLOAD if k not in ("device_id", "tasks"))
PY_ACCEPTED = set(_py_get_keys(_slice(STORAGE, "def _normalize_queue_task", NL + "def ")))
ROUTE_BODY_KEYS = set(_py_get_keys(_slice(ROUTES, "def receive_queue", NL + "def ")))


def test_client_task_fields_are_all_accepted_by_server():
    missing = sorted(KT_TASK_FIELDS - PY_ACCEPTED)
    assert not missing, "端上写了服务端不认的字段（会被静默丢弃）: " + ", ".join(missing)


def test_server_expected_fields_are_all_written_by_client():
    missing = sorted(PY_ACCEPTED - {"device_id"} - KT_TASK_FIELDS)
    assert not missing, "服务端要这些字段但端侧没写（会被逐条拒收）: " + ", ".join(missing)


def test_device_id_is_top_level_on_both_sides():
    assert "device_id" in KT_PAYLOAD, "端上顶层没有 device_id"
    assert "device_id" in ROUTE_BODY_KEYS, "服务端 receive_queue 没读 device_id"


def test_tasks_key_is_top_level_on_both_sides():
    assert "tasks" in KT_PAYLOAD, "端上顶层没有 tasks"
    assert "tasks" in ROUTE_BODY_KEYS, "服务端 receive_queue 没读 tasks"


def test_no_unknown_payload_keys():
    expected = set(["device_id", "tasks", "task_id", "kind", "title",
                    "state", "detail", "queued_at"])
    unknown = sorted(set(KT_PAYLOAD) - expected)
    assert not unknown, "端上报文出现未知字段: " + ", ".join(unknown)


def test_state_enum_matches():
    kt = _kt_const_strings(KT, "STATE_")
    py = _py_tuple(STORAGE, "QUEUE_STATES")
    assert kt == py, "state 枚举两端不一致: 端上=%s 服务端=%s" % (sorted(kt), sorted(py))


def test_kind_enum_matches():
    kt = _kt_const_strings(KT, "KIND_")
    py = _py_tuple(STORAGE, "QUEUE_KINDS")
    assert kt == py, "kind 枚举两端不一致: 端上=%s 服务端=%s" % (sorted(kt), sorted(py))


def test_length_limits_match():
    pairs = [("MAX_TASK_ID_LEN", "QUEUE_TASK_ID_MAX_LEN"),
             ("MAX_TITLE_LEN", "QUEUE_TITLE_MAX_LEN"),
             ("MAX_DETAIL_LEN", "QUEUE_DETAIL_MAX_LEN")]
    for kt_name, py_name in pairs:
        kt_val = _kt_const_int(KT, kt_name)
        py_val = _py_int(STORAGE, py_name)
        assert kt_val is not None and py_val is not None, "找不到长度常量: %s / %s" % (kt_name, py_name)
        assert kt_val == py_val, "%s=%s 与 %s=%s 不一致" % (kt_name, kt_val, py_name, py_val)


def test_device_id_length_fits_server_limit():
    limit = _py_int(STORAGE, "QUEUE_DEVICE_ID_MAX_LEN")
    assert limit is not None and limit >= 16, "服务端 device_id 上限容不下端上生成的 id"
    assert "android-" in KT, "端上 device_id 前缀约定丢了"
