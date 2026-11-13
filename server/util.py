# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""notifyme 服务端 —— 纯函数工具层。

这里只放不持有状态的小工具（数值归一、时间戳格式化、文本转义等）。
凡是需要 app 模块里的运行期常量的地方，都在调用时通过 app_module 动态读取，
保证测试对 app 模块的 monkeypatch 立即生效。
"""

import json
import re
from datetime import datetime, timezone
import logging

logger = logging.getLogger("notifyme.server")

def pkg_label(pkg):
    """包名转中文名；未知包名回落到包名本身，不瞎猜。"""
    import app as app_module
    return app_module.PKG_LABELS.get(pkg, pkg or app_module.DEFAULT_PKG)

def _importance_label(value):
    """把 S1 重要度统一成 high/mid/low 标签。

    端上实际发的是 0-9 数值（>=6 high / >=3 mid / 其余 low），而旧文档与
    控制台 JS 只认 high/mid/low 字符串；两种都要归一，否则数值重要度会在
    控制台里被当成未知值退化为最低优先级。
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        num = float(value)
    else:
        text = str(value).strip().lower()
        if not text:
            return None
        try:
            num = float(text)
        except ValueError:
            return text
    if num >= 6:
        return "high"
    if num >= 3:
        return "mid"
    return "low"

def _safe_int(value, default=0, lo=None, hi=None):
    """把任意上报值安全转成 int：非法或越界一律回落到 default。

    原来 int(item["timestamp"]) / int(item.get("window_end")) 直接抛
    ValueError / OverflowError → 整个批次 500，而且异常发生在写了一半的
    时候（jsonl 已经写进去、库里没有）。
    """
    if value is None or isinstance(value, bool):
        return default
    try:
        n = int(value)
    except (TypeError, ValueError, OverflowError):
        return default
    if lo is not None and n < lo:
        return default
    if hi is not None and n > hi:
        return default
    return n

def _clamp_int(value, lo=0, hi=None, default=0):
    """把任意上报值收敛到 [lo, hi] 区间内的 int。

    与 _safe_int 的区别：_safe_int 越界时回落到 default（值直接变 0），
    这里改成夹紧到边界 —— 例如 case_count 报了 99999999，存 1000000
    比存 0 更贴近事实（控制台显示的数字仍然有意义）。
    """
    if value is None or isinstance(value, bool):
        return default
    try:
        n = int(value)
    except (TypeError, ValueError, OverflowError):
        return default
    if n < lo:
        return lo
    if hi is not None and n > hi:
        return hi
    return n

def _advisor_ts(value):
    """顾问报告的时间字段（created_at / received_at）→ 毫秒整数，认不出返 0。

    为什么需要它：顾问存档是 JSONL，写入路径之外（人工修复、旧快照迁移、
    第三方脚本）可以塞进任意的值。created_at 上报时是毫秒数字，但
    storage._normalize_advisor_item 会 _clip 成字符串；更老的部署写的是
    "2026-11-10 08:00:00" 这种本地时间文本。routes.query_advisor 直接拿
    原值当排序键 → int 与 str 比较抛 TypeError → 整页 500；
    suggests._advisor_state 只认 int → 文本形态的老数据算不出「最近一次」。

    口径（与 _safe_int 一致：认不出就回落，绝不抛）：
      1. None / bool / 空串 / 认不出的文本 → 0；
      2. 纯数字（int / float / 数字字符串）按毫秒；10**9..10**11 之间视为秒并乘 1000；
      3. 越界（<=0 或 > MAX_TS_MS）→ 0；
      4. 文本按 "%Y-%m-%d %H:%M:%S" / "%Y-%m-%dT%H:%M:%S" / "%Y-%m-%d" 按**本地时区**解析。
    """
    import app as app_module
    if value is None or isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        num = value
    else:
        text = str(value).strip()
        if not text:
            return 0
        try:
            num = float(text)
        except (TypeError, ValueError):
            num = None
        if num is None:
            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S",
                        "%Y-%m-%d %H:%M", "%Y-%m-%d"):
                try:
                    return app_module._safe_int(
                        datetime.strptime(text, fmt).timestamp() * 1000,
                        default=0, lo=1, hi=app_module.MAX_TS_MS)
                except (TypeError, ValueError):
                    continue
            return 0
    # 秒级时间戳（10 位）按秒解释：老版本与手工脚本会写成秒
    if 1_000_000_000 <= num < 100_000_000_000:
        num = num * 1000
    return app_module._safe_int(num, default=0, lo=1, hi=app_module.MAX_TS_MS)

def _safe_bool(value, default=False):
    """把任意来源的布尔值归一成 True/False。

    客户端把 false 上报成字符串 "false" / "0" / "" 时，Python 的真值判断
    会把它们全当成 True（非空字符串都是真值）-> 分析结果里 escalated /
    filtered / redacted 被批量写成 1。
    这里统一口径：布尔直接用；字符串按 true/1/yes/on 判真；数字按 != 0 判真；
    认不出来的值一律按 default（默认 False）处理。
    """
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        text = value.strip().lower()
        if text in ("true", "1", "yes", "on", "y", "t"):
            return True
        if text in ("false", "0", "no", "off", "n", "f", ""):
            return False
        return default
    return default

def _parse_tz_offset(raw_tz, raw_offset):
    """解析时区参数，返回分钟偏移（东八区 = +480）或 None（未指定）。

    支持 tz=+08:00 / tz=+0800 / tz=+8，以及 tz_offset=480（分钟偏移）。
    非法值返回 ("error", 说明)，调用方据此回 400。

    兼容 URL 里未编码的 '+'：查询串 "?tz=+08:00" 里的 '+' 会被解码成空格
    （服务端收到的是 " 08:00"）。这是手写 curl / 浏览器地址栏最常见的写法，
    按 '+' 处理，免得用户白挨一个 400。
    """
    raw = raw_tz or ""
    tz = raw.strip()
    if tz and raw[:1] == " " and tz[0] not in "+-":
        tz = "+" + tz
    off = (raw_offset or "").strip()
    if tz and off:
        return ("error", "use either tz or tz_offset, not both")
    if off:
        try:
            minutes = int(off)
        except ValueError:
            return ("error", "tz_offset must be an integer number of minutes")
        if not (-14 * 60 <= minutes <= 14 * 60):
            return ("error", "tz_offset out of range")
        return minutes
    if not tz:
        return None
    matched = re.match(r"^([+-])(\d{1,2})(?::?(\d{2}))?$", tz)
    if not matched:
        return ("error", "tz must look like +08:00")
    sign = -1 if matched.group(1) == "-" else 1
    hours = int(matched.group(2))
    minutes = int(matched.group(3) or 0)
    if hours > 14 or minutes > 59:
        return ("error", "tz out of range")
    return sign * (hours * 60 + minutes)

def _parse_cursor(before_str):
    """解析游标分页参数 before，返回 (毫秒, rowid) 或 (None, None)。

    before 有两种形态：
        <毫秒>            老客户端只发毫秒 —— rowid 取 int64 上界（该毫秒之前的全部）
        <毫秒>_<rowid>    新客户端把上一页的 next_before 原样回传
    空值表示「没有游标」，返回 (None, None)。

    非法 / 越界返回 ("error", 说明)，调用方据此回 400。**两段都必须校验**：
    只校验毫秒段时，超过 int64 的 rowid 传给 sqlite 会抛 OverflowError
    （/messages 曾因此整页 500），负 rowid 则静默返回空页 —— 看起来像「翻到底了」。
    多余的下划线段（<ms>_<rowid>_x）按格式错误拒绝，不静默丢弃。
    """
    import app as app_module
    text = (before_str or "").strip()
    if not text:
        return None, None
    parts = text.split("_")
    if len(parts) > 2:
        return ("error", "before must be <ms> or <ms>_<rowid>")
    try:
        before_ms = int(parts[0])
        before_rid = int(parts[1]) if len(parts) > 1 else None
    except (ValueError, IndexError):
        return ("error", "before must be <ms> or <ms>_<rowid>")
    if not (app_module.MIN_TS_MS <= before_ms <= app_module.MAX_TS_MS):
        return ("error", "before out of range")
    rid = app_module.MAX_ROWID if before_rid is None else before_rid
    if not (0 <= rid <= app_module.MAX_ROWID):
        return ("error", "before out of range")
    return before_ms, rid


def _fmt_tz_offset(minutes):
    """分钟偏移 -> "+08:00" 形式的字符串；None 原样返回。"""
    if minutes is None:
        return None
    sign = "-" if minutes < 0 else "+"
    minutes = abs(int(minutes))
    return "%s%02d:%02d" % (sign, minutes // 60, minutes % 60)

def _day_bounds_ms(date_str, tz_offset_min):
    """把 YYYY-MM-DD 换算成当天 [00:00, 次日00:00) 的毫秒区间。

    tz_offset_min 为 None 时用服务器本地时区（向后兼容的老行为）；
    否则按该固定偏移时区算（固定偏移没有夏令时，加 24h 精确）。
    """
    day = datetime.strptime(date_str, "%Y-%m-%d")
    if tz_offset_min is None:
        day_start = int(day.timestamp() * 1000)
    else:
        day_start = int(day.replace(tzinfo=timezone.utc).timestamp() * 1000) \
            - tz_offset_min * 60000
    return day_start, day_start + 24 * 3600 * 1000

def _fmt_ts(ms):
    """毫秒时间戳 → 本地时间字符串；越界/非法返回「时间未知」。

    datetime.fromtimestamp 对越界值抛 OSError / OverflowError，
    落地页里一条坏数据就能把整页打挂（实测 10**18 → 500）。
    """
    import app as app_module
    n = app_module._safe_int(ms, default=None, lo=app_module.MIN_TS_MS, hi=app_module.MAX_TS_MS)
    if n is None:
        return "时间未知"
    try:
        return datetime.fromtimestamp(n / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except (OSError, OverflowError, ValueError):
        return "时间未知"

def _safe_json(raw):
    """解析库里存的 JSON 列；坏数据返回 None，而不是让整个查询 500。"""
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError):
        logger.warning("analyses 表存在损坏的 JSON 列，已跳过解析")
        return None

def _like_escape(s):
    """转义 LIKE 查询里的通配符（% 与 _）。

    不转义时，会话名里带 _（单字符通配）会匹配到别人的会话，
    带 % 则等于全表匹配。
    """
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

def _prefix_upper(prefix):
    """前缀匹配的字典序上界：conversation >= p AND conversation < u。

    SQLite 对 TEXT 用 BINARY（UTF-8 字节序）比较，而 UTF-8 字节序与
    Unicode 码点序一致，所以 p + U+10FFFF 就是「以 p 开头」的宽上界：
        "项目组" <= c < "项目组\U0010FFFF"
    用范围比较而不是 LIKE '%x%'，是因为前导通配符会让
    idx_messages_conv_ts / idx_analyses_conv_ts 失效 → 每次查询全表扫。

    这里刻意用宽上界，而不是「最后一个码点 +1」的紧上界：紧上界会把
    「前缀 + 下一个码点」开头的会话一起排除（查 "项目组" 时漏掉 "项目组细"），
    属于误杀。
    """
    if not prefix:
        return None
    return prefix + "\U0010FFFF"

def _esc_html(s):
    """HTML 转义（落地页用；控制台内联 JS 自带 esc）。

    None / 空值一律渲染成空串：messages.conversation / sender 这类列
    允许为 NULL，原来 str(None) 会把字面量 None 显示给用户，
    None.replace() 还会直接抛 AttributeError 让整页 500。
    """
    if s is None:
        return ""
    text = str(s)
    if not text:
        return ""
    return (text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;")
            .replace("'", "&#39;"))

def _analyzed_at_text(value):
    """analyzed_at 列是 TEXT，但 App 上报的是毫秒整数。

    原实现只接受 str，数值上报会永远写成 NULL（控制台"分析于"始终为空）。
    数值统一转成本地时间字符串，字符串原样保留。
    越界的数值（如 10**18）原来会让 fromtimestamp 抛 OSError → 整批 500，
    现在越界值按「无法解释的时间」处理（返回 None），不拖垮整批。
    """
    import app as app_module
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        ms = app_module._safe_int(value, default=None, lo=app_module.MIN_TS_MS, hi=app_module.MAX_TS_MS)
        if ms is None:
            return None
        if ms > 100_000_000_000:  # >10^11 视为毫秒（10^11 ≈ 1973 年）
            return app_module._fmt_ts(ms)
        return str(ms)
    text = str(value).strip()
    return text or None

def _validate_item(item):
    """校验单条消息的必备字段、类型、长度与数值边界。

    越界数据必须在这里被挡下来：正文 1 MB 会把内存撑爆，
    timestamp 越界会让查询与落地页整页 500。
    """
    import app as app_module
    for field, expected_type in app_module.REQUIRED_FIELDS.items():
        if field not in item:
            return False
        value = item[field]
        # bool 是 int 的子类，is_group 必须严格是 bool，timestamp 不能是 bool
        if field == "is_group":
            if not isinstance(value, bool):
                return False
        elif field == "timestamp":
            if isinstance(value, bool) or not isinstance(value, expected_type):
                return False
            # 时间戳必须落在 [1970, 2100) 毫秒区间内
            if app_module._safe_int(value, default=None,
                         lo=app_module.MIN_TS_MS, hi=app_module.MAX_TS_MS) is None:
                return False
        elif not isinstance(value, expected_type):
            return False
    # 长度上限：正文 64 KiB，发送者 / 会话名 512
    if len(item["text"]) > app_module.MAX_TEXT_LEN:
        return False
    if len(item["sender"]) > app_module.MAX_NAME_LEN:
        return False
    if len(item["conversation"]) > app_module.MAX_NAME_LEN:
        return False
    return True

