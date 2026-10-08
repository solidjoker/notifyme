# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""
PC 微信聊天记录数据库提取工具
================================
安卓真机无 root 读不了微信库，改走 PC 路线：手机微信「迁移到电脑微信」
把记录灌进 PC 客户端后，本工具把 PC 侧（微信 4.x）加密数据库解密、
增量合并进监测库 messages.db（source='pc-db'）。

全流程（可独立 CLI 运行，也可被 app.py import 调用）：
    1. wx_accounts.list_accounts() 发现 xwechat_files 下所有账号
    2. 每账号用 keys/<账号>.json 的密钥，snapshot_and_decrypt 快照解密
       contact.db + message_0.db（只读快照到临时目录，绝不写微信目录）
    3. 会话发现：Name2Id 表（rowid -> username）反查 Msg_<md5(username)> 表
    4. 消息行映射成统一字段（conversation 会话名/群名、sender 发送者、
       timestamp 毫秒、text 正文、is_group），群聊发言人取「wxid:\\n正文」
       前缀，real_sender_id 经 Name2Id 反查 wxid，识别「我」
    5. 与 android_db_extract 同口径的秒级归一去重，增量合并进 messages.db
    6. 临时明文库用完即删（shutil.rmtree 临时目录）

增量状态：data/pc_db/state.json 记录每账号每会话已合并的最大 create_time
（秒），重复跑只拉新消息；--full 忽略状态全量重扫（去重兜底不重复入库）。

CLI 用法：
    python pc_db_extract.py              # 全部账号增量
    python pc_db_extract.py --full       # 全量重扫
    python pc_db_extract.py --account account_001   # 只跑指定账号

解密失败 / 密钥失效（微信大版本更新后可能发生）会以 ExtractError 明确抛出，
不静默吞。解密与会话名映射复用外部已验证工具链（通过环境变量
WX_TOOLCHAIN_DIR 指定工具链目录，不随本仓库分发）。
"""

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import time
from datetime import datetime

# Windows 控制台默认 GBK，中文输出前强制 utf-8，避免 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ------------------------------------------------------------------
# 可配置常量
# ------------------------------------------------------------------

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
PC_DIR = os.path.join(DATA_DIR, "pc_db")              # 提取产物目录
STATE_PATH = os.path.join(PC_DIR, "state.json")       # 增量状态
MAIN_DB = os.path.join(DATA_DIR, "messages.db")       # 监测主库

SOURCE_TAG = "pc-db"  # 本来源标记
PKG = "com.tencent.mm"  # PC 微信包名（与安卓侧同口径，用于跨来源去重维度）

# PC 微信 4.x 解密工具链：目录由环境变量 WX_TOOLCHAIN_DIR 指定
# （工具链不随本仓库分发；未设置时在导入阶段给出可读报错）
WX_TOOLCHAIN = os.environ.get("WX_TOOLCHAIN_DIR", "")
if WX_TOOLCHAIN:
    WX_TOOLCHAIN = os.path.normpath(WX_TOOLCHAIN)
    if not os.path.isdir(WX_TOOLCHAIN):
        # 早失败：目录写错时给出可读报错，而不是后面 import 时才莫名 ImportError
        raise RuntimeError(f"WX_TOOLCHAIN_DIR 不是有效目录: {WX_TOOLCHAIN}")
    # 追加到 sys.path 末尾，而不是插到 sys.path[0]：
    # sys.path[0] 的模块会优先于标准库与本仓库被 import，环境变量指向的第三方
    # 目录不应该有这种覆盖能力。
    if WX_TOOLCHAIN not in sys.path:
        sys.path.append(WX_TOOLCHAIN)

MSG_DB_REL = os.path.join("message", "message_0.db")
CONTACT_DB_REL = os.path.join("contact", "contact.db")

# 消息类型占位符（local_type=1 为文本，其余按类型给占位符）
TYPE_PLACEHOLDER = {
    3: "[图片]", 34: "[语音]", 43: "[视频]", 47: "[表情]",
    42: "[名片]", 48: "[位置]", 49: "[链接/文件]",
    10000: "[系统消息]",
}

# 系统会话（不是真人聊天，不入库也无妨，但保留 filehelper 便于自测）
SKIP_USERS = {"qqmail", "fmessage", "medianote", "floatbottle",
              "notification_messages", "notifymessage", "wechat"}


class ExtractError(RuntimeError):
    """提取流程的可读错误，CLI/HTTP 层直接展示 message。"""


# ------------------------------------------------------------------
# 解密工具链导入（失败时给出可读指引）
# ------------------------------------------------------------------

def _import_toolchain():
    try:
        import wx_accounts          # noqa: E402
        from wx_read_test import snapshot_and_decrypt, maybe_decompress  # noqa: E402
        return wx_accounts, snapshot_and_decrypt, maybe_decompress
    except ImportError as e:
        raise ExtractError(
            f"无法导入 PC 解密工具链 {WX_TOOLCHAIN}: {e}；"
            "可用环境变量 WX_TOOLCHAIN_DIR 覆盖路径")


# ------------------------------------------------------------------
# 名称映射与行解析
# ------------------------------------------------------------------

def _contact_name_map(snapshot_and_decrypt, workdir, account):
    """wxid -> 显示名 映射（备注 > 昵称 > 别名 > wxid），含群名。"""
    out, _, _ = snapshot_and_decrypt(CONTACT_DB_REL, workdir, account=account)
    con = sqlite3.connect("file:" + out + "?mode=ro", uri=True)
    try:
        name_of = {}
        for u, alias, remark, nick in con.execute(
                "SELECT username, alias, remark, nick_name FROM contact"):
            name_of[u] = remark or nick or alias or u
        return name_of
    finally:
        con.close()


def _resolve_self_username(account, id2name):
    """推断本账号自己的 username。

    账号目录名形如 <username> 或 <username>_<多开编号后缀>（编号可能是
    纯数字或字母数字混合，如 account_001 / wxid_xxx_df68），逐段剥掉
    尾部 ``_<段>`` 后在 Name2Id 里命中即可信；都不命中时取 Name2Id 最小
    rowid（实测两个账号 rowid=1 均为本账号）。注意 Name2Id 的 SELECT
    不保证按 rowid 有序，不能依赖遍历顺序。
    """
    names = set(id2name.values())
    cand = account
    while cand:
        if cand in names:
            return cand
        if "_" not in cand:
            break
        cand = cand.rsplit("_", 1)[0]
    if id2name:
        return id2name[min(id2name)]
    return account


def _parse_row(user, sid, ctime, ltype, content, name_of, id2name,
               self_name, maybe_decompress):
    """把 Msg_xxx 表一行解析成监测库的统一字段。

    返回 dict(conversation, sender, timestamp, text, is_group)。
    """
    is_group = user.endswith("@chatroom")
    c = maybe_decompress(content)
    c = c if isinstance(c, str) else ("" if c is None else str(c))
    conversation = name_of.get(user, user)

    if is_group:
        # 群消息：他人发言格式为「发送者wxid:\n内容」；无前缀的是我自己发的
        if ":\n" in c:
            swx, body = c.split(":\n", 1)
            sender = name_of.get(swx, swx)
        else:
            sun = id2name.get(sid)
            sender = "我" if sun == self_name else name_of.get(sun, sun or f"id:{sid}")
            body = c
    else:
        # 私聊：conversation 与 sender 都是对方显示名；我发的 sender 记「我」
        sun = id2name.get(sid)
        sender = "我" if sun == self_name else conversation
        body = c

    # 非文本类型记占位符（群聊也要先按上面拆出发言人，再覆盖正文）
    text = body if ltype == 1 else TYPE_PLACEHOLDER.get(ltype, f"[类型{ltype}消息]")

    return {
        "conversation": conversation,
        "sender": sender,
        "timestamp": int(ctime) * 1000,   # PC 库 create_time 是秒，统一成毫秒
        "text": text,
        "is_group": 1 if is_group else 0,
    }


# ------------------------------------------------------------------
# 增量状态
# ------------------------------------------------------------------

def _load_state(full=False):
    """读增量状态：{accounts: {账号: {conversations: {username: 最大秒级时间戳}}}}。"""
    if full or not os.path.exists(STATE_PATH):
        return {"accounts": {}}
    try:
        state = json.load(open(STATE_PATH, encoding="utf-8"))
        if isinstance(state, dict) and isinstance(state.get("accounts"), dict):
            return state
    except (json.JSONDecodeError, OSError):
        pass
    return {"accounts": {}}


def _save_state(state):
    os.makedirs(PC_DIR, exist_ok=True)
    state["last_run"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    os.replace(tmp, STATE_PATH)


# ------------------------------------------------------------------
# 单账号提取
# ------------------------------------------------------------------

def extract_account(account, state_acct, existing, dst, toolchain):
    """提取单个账号：解密 -> 遍历会话 -> 增量合并。返回统计 dict。

    existing / dst 由上层（merge_all）提供：existing 是归一去重键集合
    （主库已有 + 本批次已收），dst 是已打开的主库连接。
    """
    wx_accounts, snapshot_and_decrypt, maybe_decompress = toolchain
    stats = {"conversations": 0, "scanned": 0, "inserted": 0,
             "duplicated": 0, "skipped": 0}

    workdir = tempfile.mkdtemp(prefix="wxpc_")
    con = None
    try:
        # 1. 快照解密：contact.db（会话名映射）+ message_0.db（消息正文）
        #    全程只读微信目录，明文落在 %TEMP% 临时目录，finally 里清除
        name_of = _contact_name_map(snapshot_and_decrypt, workdir, account)
        merged, npages, nframes = snapshot_and_decrypt(
            MSG_DB_REL, workdir, account=account)
        stats["db_pages"] = npages
        stats["wal_frames"] = nframes
        con = sqlite3.connect("file:" + merged + "?mode=ro", uri=True)

        # 2. Name2Id：rowid -> username（real_sender_id 反查 + 表名反查）
        id2name = {}
        for rid, un in con.execute("SELECT rowid, user_name FROM Name2Id"):
            if un:
                id2name[rid] = un
        self_name = _resolve_self_username(account, id2name)
        stats["self"] = self_name
        hash2user = {hashlib.md5(u.encode()).hexdigest(): u
                     for u in id2name.values()}

        conv_state = state_acct.setdefault("conversations", {})
        now_ms = int(time.time() * 1000)

        # 3. 遍历会话表，按每会话最大 create_time 增量拉取
        tables = [r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name LIKE 'Msg_%'")]
        for t in tables:
            user = hash2user.get(t[4:])
            if not user or user in SKIP_USERS or user.endswith("@openim"):
                continue  # 反查不到 username 的表 / 系统会话 / openim 会话跳过
            last_ts = int(conv_state.get(user, 0))
            try:
                rows = con.execute(
                    f'SELECT real_sender_id, create_time, local_type, '
                    f'message_content FROM "{t}" WHERE create_time > ? '
                    f'ORDER BY create_time', (last_ts,)).fetchall()
            except sqlite3.Error as e:
                print(f"[warn] 账号 {account} 表 {t} 不可读，已跳过: {e}")
                continue
            stats["conversations"] += 1
            stats["scanned"] += len(rows)

            max_ts = last_ts
            for sid, ctime, ltype, content in rows:
                max_ts = max(max_ts, ctime)
                try:
                    m = _parse_row(user, sid, ctime, ltype, content,
                                   name_of, id2name, self_name, maybe_decompress)
                except Exception:
                    stats["skipped"] += 1
                    continue
                # 与 android_db_extract 同口径：秒级归一去重键
                key = (m["conversation"], m["sender"],
                       m["timestamp"] // 1000, m["text"])
                if key in existing:
                    stats["duplicated"] += 1
                    continue
                existing.add(key)
                try:
                    dst.execute(
                        """
                        INSERT INTO messages
                            (sender, text, timestamp, conversation, is_group,
                             received_at, source, pkg)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (m["sender"], m["text"], m["timestamp"],
                         m["conversation"], m["is_group"], now_ms, SOURCE_TAG,
                         PKG))
                    stats["inserted"] += 1
                except sqlite3.IntegrityError:
                    # 兜底：命中毫秒级唯一索引
                    stats["duplicated"] += 1
            conv_state[user] = max_ts
        return stats
    except ExtractError:
        raise
    except Exception as e:
        # 解密失败 / 密钥失效（微信大版本更新后可能发生）：明确报错
        raise ExtractError(f"账号 {account} 提取失败: {e}") from e
    finally:
        if con is not None:
            con.close()
        shutil.rmtree(workdir, ignore_errors=True)  # 明文临时库即删


# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------

def _ensure_main_schema(dst):
    """确保监测主库有 source / pkg 列（与 app.py 的迁移逻辑一致，幂等）。"""
    cols = [r[1] for r in dst.execute("PRAGMA table_info(messages)")]
    if "source" not in cols:
        dst.execute(
            "ALTER TABLE messages ADD COLUMN source TEXT "
            "NOT NULL DEFAULT 'android-notification'")
    if "pkg" not in cols:
        # 老库（M2 之前）没有 pkg；历史数据都是微信，回填微信包名
        dst.execute(
            "ALTER TABLE messages ADD COLUMN pkg TEXT "
            f"NOT NULL DEFAULT '{PKG}'")
    dst.commit()


def run_extract(full=False, account=None):
    """完整提取流程（全部账号或指定账号），返回统计 dict。"""
    t0 = time.time()
    toolchain = _import_toolchain()
    wx_accounts = toolchain[0]

    # 1. 发现账号；无账号 / 缺密钥都明确报错
    accounts = wx_accounts.list_accounts()
    if account:
        accounts = [a for a in accounts if a["account"] == account]
        if not accounts:
            raise ExtractError(
                f"找不到账号 {account!r}，本机账号: "
                f"{[a['account'] for a in wx_accounts.list_accounts()] or '无'}")
    if not accounts:
        raise ExtractError("xwechat_files 下没有找到任何账号，请先登录 PC 微信")

    state = _load_state(full=full)
    stats = {"full": bool(full), "accounts": {}, "errors": {}}

    # 与服务端 app.py 同口径：主库可能正被上报/控制台写入，
    # 不给 busy_timeout 时会直接抛 "database is locked"
    dst = sqlite3.connect(MAIN_DB, timeout=10)
    dst.execute("PRAGMA busy_timeout = 5000")
    try:
        _ensure_main_schema(dst)  # 独立运行时主库可能还没迁移 source / pkg 列
        # 建归一去重键集合：主库现有全部消息（键 = 会话+发送者+秒级时间戳+正文）
        existing = set()
        for conv, sender, ts, text in dst.execute(
                "SELECT conversation, sender, timestamp, text FROM messages"):
            existing.add((conv, sender, ts // 1000, text))

        # 2. 逐账号提取；单账号失败记录错误，不拖垮其它账号
        for a in accounts:
            acct = a["account"]
            keys_file = wx_accounts.keys_file_for(acct, a["db_dir"])
            if not os.path.exists(keys_file):
                stats["errors"][acct] = (
                    f"密钥文件不存在: {keys_file}（请先在该账号登录状态下"
                    "重新提取密钥）")
                continue
            try:
                stats["accounts"][acct] = extract_account(
                    acct, state["accounts"].setdefault(acct, {}),
                    existing, dst, toolchain)
            except ExtractError as e:
                stats["errors"][acct] = str(e)
        dst.commit()
    finally:
        dst.close()

    # 3. 落增量状态
    _save_state(state)

    # 4. 全部账号都失败时整体报错（密钥失效等场景不静默吞）
    if stats["errors"] and not stats["accounts"]:
        raise ExtractError("所有账号提取均失败: " + json.dumps(
            stats["errors"], ensure_ascii=False))

    stats["scanned"] = sum(s["scanned"] for s in stats["accounts"].values())
    stats["inserted"] = sum(s["inserted"] for s in stats["accounts"].values())
    stats["duplicated"] = sum(s["duplicated"]
                              for s in stats["accounts"].values())
    stats["skipped"] = sum(s["skipped"] for s in stats["accounts"].values())
    stats["elapsed_sec"] = round(time.time() - t0, 2)
    stats["finished_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return stats


def main():
    parser = argparse.ArgumentParser(
        description="PC 微信聊天记录数据库提取合并工具（微信 4.x）")
    parser.add_argument("--full", action="store_true",
                        help="全量重扫（默认增量，基于 state.json 每会话最大时间戳）")
    parser.add_argument("--account",
                        help="只提取指定账号（默认 xwechat_files 下全部账号）")
    args = parser.parse_args()

    try:
        stats = run_extract(full=args.full, account=args.account)
    except ExtractError as e:
        print(f"[失败] {e}", file=sys.stderr)
        sys.exit(1)

    print("[成功] 提取合并完成")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
