#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通知形状取证工具：把 dumpsys notification --noredact 变成「某 App 的通知长什么样」。

背景：docs/通知形状取证记录.md 里的结论（微信没有 MessagingStyle、正文带 [N条] 前缀、
发言人用半角冒号且冒号后无空格、钉钉标题是「钉钉｜群名」……）都是一次性手抄的。
本工具让取证可复现、可比对：

  1) 取 dump：adb shell dumpsys notification --noredact（也可直接喂已有 dump 文件）
  2) 抽字段：按 NotificationRecord 块切分，只抽解析器真正读的 extras
  3) 出结论：每个包一行「有没有 MessagingStyle / 几次未读前缀 / 冒号是半角还是全角 /
     冒号后有没有空格 / 标题带没带｜」

用法：
  adb -s <serial> shell dumpsys notification --noredact > dump.txt
  python docs/scripts/notify_shape_capture.py --input dump.txt --out shape.json

输出：人读摘要（每包一行）+ --out 指定的 JSON 明细。
"""

import argparse
import json
import re
import subprocess
import sys

# 只抽解析器实际读的字段；tickerText/category/smallIcon/color 等与入库口径无关。
FIELD_KEYS = ("android.title", "android.text", "android.bigText", "android.subText")

RECORD_RE = re.compile(r"^\s*NotificationRecord\(", re.M)
EXTRAS_RE = re.compile(r"^\s+extras:\s*$", re.M)
KV_RE = re.compile(r"^\s+([\w.]+)\s*=\s*(.*)$")
STRING_RE = re.compile(r"^String \((.*)\)$", re.S)

# 结论口径（与 docs/通知形状取证记录.md 的 §3 对齐）
UNREAD_PREFIX_RE = re.compile(r"^\[\d+条\]")
COLON_RE = re.compile(r"^([^:：]{1,40})([:：])(.*)$", re.S)

def decode_text(raw):
    """dump 文本的编码不一定是 UTF-8：中文 Windows 的 PowerShell 重定向（> dump.txt）
    会写成 UTF-16LE（带 BOM），按 UTF-8 读会全是乱码、一条记录都抽不到。
    这里自动识别，别让「工具说没抽到记录」变成误判。
    """
    if raw[:2] == b"\xff\xfe":
        return raw[2:].decode("utf-16-le", "replace")
    if raw[:2] == b"\xfe\xff":
        return raw[2:].decode("utf-16-be", "replace")
    if b"\x00" in raw[:256]:
        # 无 BOM 的 UTF-16LE：ASCII 字符后面跟 NUL
        return raw.decode("utf-16-le", "replace")
    return raw.decode("utf-8", "replace").lstrip("\ufeff")


def split_sender(text):
    """切「发言人: 正文」；时间里的冒号（10:30）不算分隔符。"""
    for i, ch in enumerate(text):
        if ch not in ":：":
            continue
        if 0 < i < len(text) - 1 and text[i - 1].isdigit() and text[i + 1].isdigit():
            continue
        return text[:i], ch, text[i + 1:]
    return None, None, None


def _blocks(text):
    """把 dump 切成 NotificationRecord 块（含块头那一行）。"""
    starts = [m.start() for m in RECORD_RE.finditer(text)]
    if not starts:
        return []
    bounds = starts + [len(text)]
    return [text[bounds[i]:bounds[i + 1]] for i in range(len(starts))]


def _header_value(block, key):
    """从块头取 pkg= / opPkg= / tag= / channel= / postTime= 等；取不到就是 None。"""
    for line in block.splitlines()[:12]:
        m = re.search(r"\b" + re.escape(key) + r"=([^\s\)]*)", line)
        if m:
            return m.group(1)
    return None


def _extras(block):
    """抽 extras 段里的键值；值统一成字符串（String(...) 拆壳，其余原样）。"""
    out = {}
    in_extras = False
    for line in block.splitlines():
        if EXTRAS_RE.match(line):
            in_extras = True
            continue
        if in_extras:
            if line.strip() == "" or not line.startswith(" "):
                break
            m = KV_RE.match(line)
            if not m:
                continue
            key, raw = m.group(1), m.group(2).strip()
            sm = STRING_RE.match(raw)
            out[key] = sm.group(1) if sm else raw
    return out


def parse_dump(text):
    """dump 文本 -> 通知明细列表。字段缺失就是 None，不猜。"""
    records = []
    for block in _blocks(text):
        extras = _extras(block)
        messages = extras.get("android.messages")
        records.append({
            "pkg": _header_value(block, "pkg"),
            "op_pkg": _header_value(block, "opPkg"),
            "tag": _header_value(block, "tag"),
            "channel": _header_value(block, "channel"),
            "post_time": _header_value(block, "postTime"),
            "title": extras.get("android.title"),
            "text": extras.get("android.text"),
            "big_text": extras.get("android.bigText"),
            "sub_text": extras.get("android.subText"),
            "has_messages": messages is not None,
            "message_count": messages.count("MessageRecord(") if messages else 0,
        })
    return records


def body_shape(text):
    """正文的形状标记：未读前缀 / 冒号是半角还是全角 / 冒号后有没有空格 / 发言人。"""
    if text is None:
        return {"unread_prefix": False, "sender": None, "colon": None,
                "space_after_colon": None, "no_colon": True}
    prefix = UNREAD_PREFIX_RE.match(text)
    stripped = text[prefix.end():] if prefix else text
    sender, colon, rest = split_sender(stripped)
    return {
        "unread_prefix": bool(prefix),
        "sender": sender,
        "colon": colon,
        "space_after_colon": rest.startswith(" ") if rest is not None else None,
        "no_colon": colon is None,
    }


def summarize(records):
    """通知明细 -> 每个包一份结论（字段出现次数 + 形状计数 + 样例）。"""
    by_pkg = {}
    for rec in records:
        pkg = rec.get("pkg") or "unknown"
        by_pkg.setdefault(pkg, []).append(rec)
    out = {}
    for pkg, items in by_pkg.items():
        shape = [body_shape(r.get("text")) for r in items]
        titles = [r.get("title") for r in items if r.get("title")]
        out[pkg] = {
            "count": len(items),
            "field_presence": {
                "title": sum(1 for r in items if r.get("title")),
                "text": sum(1 for r in items if r.get("text")),
                "big_text": sum(1 for r in items if r.get("big_text")),
                "sub_text": sum(1 for r in items if r.get("sub_text")),
                "messages": sum(1 for r in items if r.get("has_messages")),
            },
            "has_messaging_style": any(r.get("has_messages") for r in items),
            "unread_prefix_count": sum(1 for s in shape if s["unread_prefix"]),
            "half_colon_count": sum(1 for s in shape if s["colon"] == ":"),
            "full_colon_count": sum(1 for s in shape if s["colon"] == "："),
            "colon_space_count": sum(1 for s in shape if s["space_after_colon"]),
            "no_colon_count": sum(1 for s in shape if s["no_colon"]),
            "group_title_prefix_count": sum(1 for t in titles if "｜" in t),
            "via_op_pkg": sorted(set(r.get("op_pkg") for r in items if r.get("op_pkg"))),
            "samples": [
                {"title": r.get("title"), "text": (r.get("text") or "")[:60]}
                for r in items[:3]
            ],
        }
    return out


def read_dump(args):
    """从文件读，或用 adb 现取一份。"""
    if args.input:
        with open(args.input, "rb") as fh:
            return decode_text(fh.read())
    if not args.serial:
        raise SystemExit("需要 --serial <adb 设备> 或 --input <dump 文件>")
    cmd = ["adb"]
    if args.serial:
        cmd += ["-s", args.serial]
    cmd += ["shell", "dumpsys", "notification", "--noredact"]
    proc = subprocess.run(cmd, capture_output=True)
    return decode_text(proc.stdout)


def main(argv=None):
    ap = argparse.ArgumentParser(description="通知形状取证工具（dumpsys notification -> 结论）")
    ap.add_argument("--serial", help="adb 设备 serial")
    ap.add_argument("--input", help="已有的 dumpsys 输出文件")
    ap.add_argument("--pkg", help="只看某个包")
    ap.add_argument("--out", help="把明细与结论写成 JSON")
    args = ap.parse_args(argv)

    text = read_dump(args)
    records = parse_dump(text)
    if args.pkg:
        records = [r for r in records if r.get("pkg") == args.pkg]
    summary = summarize(records)

    for pkg, s in sorted(summary.items()):
        print("%s  共 %d 条  MessagingStyle=%s  未读前缀=%d  半角冒号=%d  全角冒号=%d  冒号后有空格=%d  标题带｜=%d" % (
            pkg, s["count"], "有" if s["has_messaging_style"] else "无",
            s["unread_prefix_count"], s["half_colon_count"], s["full_colon_count"],
            s["colon_space_count"], s["group_title_prefix_count"],
        ))
    if not summary:
        print("没有抽到任何 NotificationRecord（没有 --noredact 就没有 extras）")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump({"records": records, "summary": summary}, fh, ensure_ascii=False, indent=2)
        print("已写出 " + args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
