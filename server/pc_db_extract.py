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
    5. 与 android_db_extract 同口径去重（键含 pkg 与毫秒时间戳，与主库唯一
       索引 idx_messages_dedup 一致），增量合并进 messages.db
    7. 把真正入库的新消息按 /weixin 同格式追加进 data/messages.jsonl 原文存档

增量状态：data/pc_db/state.json 记录每账号每会话已合并的最大 create_time
（秒），重复跑只拉新消息；--full 忽略状态全量重扫（去重兜底不重复入库）。
状态文件带版本号（version=2：version / source / cursor_kind / accounts）与自愈口径：
文件缺失、JSON 损坏、字段非法、版本不认识时一律退回全量重扫 + stderr 结构化告警，
不会抛异常终止整轮提取；某个账号提取失败时该账号游标保持不变（不前进），
全部账号失败时本轮不写状态文件（保留旧游标）。

写入策略：分批提交（每 EXTRACT_COMMIT_BATCH 条 / 每 EXTRACT_COMMIT_INTERVAL 秒
提交一次），批与批之间释放 SQLite 写锁——整批一次提交会让提取期间主库的写锁
一直不释放，服务端 /weixin 上报被 busy_timeout 挡住后返回 500。去重在 SQLite
侧用索引查，不再把整张表读进 Python set（内存与表大小解耦）。

原文存档：真正入库的新消息按 server/app.py 的 _append_jsonl 同格式追加进
data/messages.jsonl（被去重跳过的重复行不写，否则存档条数会多于库内条数）；
O(1) 追加、超限轮转成 messages.jsonl.1；写入失败只计数 + stderr 告警，
不让整轮提取崩掉。

跨进程锁：写主库期间持一把跨进程文件锁（Windows 用 msvcrt.locking，
POSIX 用 fcntl.flock，都不可用时退化成原子创建锁文件 + 超时），
拿不到锁就明确报错退出，不静默并发写。

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

# 共享内核（结构化日志 / 原文存档 / 跨进程锁 / 去重 / 分批提交 / 主库结构兜底）：
# 与 android_db_extract.py 共用同一份实现，改动只需改 extract_common.py 一处。
# 本脚本可能被 server/storage.py 按文件路径加载（那时 sys.path 里没有 server/），
# 所以先把自身目录补进 sys.path，再按裸名导入。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_common import (
    ExtractError, _log_warn, _log_event, _ro_uri, _archive_path, _lock_path,
    _archive_item, _archive_line, _JsonlArchiver, _FileLock, _Dedup,
    _BatchWriter, _ensure_dedup_index, _ensure_main_schema, _cursor_int,
    TYPE_PLACEHOLDER, activate as _activate_common)
_activate_common(sys.modules[__name__])


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

# ------------------------------------------------------------------
# 增量游标状态（state.json）
# ------------------------------------------------------------------
# PC 侧的游标粒度比安卓细：PC 微信按会话分表（Msg_<md5(username)>），
# 同一账号下不同会话的消息时间互不单调，所以游标必须按
# 「账号 -> 会话(username) -> 该会话最大 create_time(秒)」记录。
# 文件路径：server/data/pc_db/state.json
# version=2 格式：
# {
#   "version": 2,
#   "source": "pc-db",
#   "cursor_kind": "conversation_create_time",
#   "last_run": "YYYY-MM-DD HH:MM:SS",
#   "accounts": {
#     "<账号>": {"conversations": {"<username>": 1700000000}}
#   }
# }
# 兼容老格式（没有 version，只有 accounts）照常读取；版本不认识（更高版本 /
# 非法类型）不猜语义，直接退回全量重扫。任意一层字段非法都只丢弃该节点
# 并告警，不让整轮提取崩掉。
CURSOR_STATE_VERSION = 2
CURSOR_KIND = "conversation_create_time"

# ------------------------------------------------------------------
# 写入与去重参数（环境变量可覆盖）
# ------------------------------------------------------------------

# 分批提交：每 COMMIT_BATCH 条、或每 COMMIT_INTERVAL 秒提交一次，
# 批与批之间释放 SQLite 写锁。整批一次提交时，提取全程占着写锁，
# 服务端 /weixin 上报最多被 busy_timeout 挡 5 秒后 500（database is locked）。
COMMIT_BATCH = int(os.environ.get("EXTRACT_COMMIT_BATCH", "500"))
COMMIT_INTERVAL = float(os.environ.get("EXTRACT_COMMIT_INTERVAL", "2"))

# 跨来源近重复的时间差窗口（毫秒）：只有来源不同、且时间差落在窗口内，
# 才判为同一条消息（通知渠道与数据库渠道对同一条消息的时间戳略有偏差）；
# 窗口设为 0 表示关掉这条宽松判定（只保留毫秒级精确去重）。
CROSS_SOURCE_SKEW_MS = int(os.environ.get("EXTRACT_CROSS_SOURCE_SKEW_MS", "1000"))

# 原文存档：与 server/app.py 的 _append_jsonl 写同一个文件
# （/weixin 上报与数据库直读提取的消息都要进这个存档，否则「原文存档」
#   只覆盖通知渠道，数据库直读来源的消息只剩库里的副本）
# 轮转阈值必须与 server/app.py 的 JSONL_MAX_BYTES 一致：两个写入方轮转的是
# 同一个 .1 文件，阈值不一致会互相覆盖（先轮转的一方冲掉对方还没轮转的内容）。
# app.py 当前取值 64 MiB。
JSONL_MAX_BYTES = 64 * 1024 * 1024

# 跨进程写锁：两个提取脚本（或手工 CLI 与服务端后台提取）同时写主库时，
# 会互相抢 SQLite 写锁，把对方的写入推迟到 busy_timeout 之外。
# 写主库期间持锁，拿不到锁就明确报错退出，不静默并发写。
# 存档与锁文件的具体路径见 _archive_path() / _lock_path()（运行时按主库位置解析）。
LOCK_TIMEOUT = float(os.environ.get("EXTRACT_LOCK_TIMEOUT", "120"))      # 秒
LOCK_STALE_SECONDS = float(os.environ.get("EXTRACT_LOCK_STALE", "3600"))  # 秒

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

# 系统会话（不是真人聊天，不入库也无妨，但保留 filehelper 便于自测）
SKIP_USERS = {"qqmail", "fmessage", "medianote", "floatbottle",
              "notification_messages", "notifymessage", "wechat"}


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
    con = sqlite3.connect(_ro_uri(out), uri=True)
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

def _load_state(full=False, diag=None):
    """读增量状态，返回 {accounts: {账号: {conversations: {username: 最大秒级时间戳}}}}。

    diag 是可选的诊断输出字典，调用方可以拿到"这轮为什么退回全量"：
      {"reset": bool, "reason": str, "origin": "state"|"full"|"missing"|"broken"}
    任何异常路径都只打 stderr 告警并退回全量重扫，绝不抛异常——
    状态文件坏了不该让整轮提取失败（去重兜底，重扫不会产生重复行）。
    """
    d = {"reset": False, "reason": "", "origin": "state"}

    def _done(state):
        if diag is not None:
            diag.update(d)
        return state

    if full:
        d.update(reset=True, reason="full_scan", origin="full")
        _log_event("cursor_reset", reason="full_scan", path=STATE_PATH)
        return _done({"accounts": {}})
    if not os.path.exists(STATE_PATH):
        d.update(reset=True, reason="state_missing", origin="missing")
        _log_event("cursor_reset", reason="state_missing", path=STATE_PATH)
        return _done({"accounts": {}})
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except (json.JSONDecodeError, OSError, UnicodeDecodeError) as e:
        d.update(reset=True, reason=f"state_unreadable: {e}", origin="broken")
        _log_warn(f"增量状态读取失败（退回全量重扫）: {e}")
        _log_event("cursor_reset", reason="unreadable", detail=str(e))
        return _done({"accounts": {}})
    if not isinstance(raw, dict):
        d.update(reset=True, reason="state_not_object", origin="broken")
        _log_warn("增量状态格式异常（不是 JSON 对象，退回全量重扫）")
        _log_event("cursor_reset", reason="not_object")
        return _done({"accounts": {}})
    # 版本：没有 version = 老格式（v1，只有 accounts），照常读；
    # 版本不认识（更高版本 / 非法类型）不猜语义，直接退回全量重扫。
    if "version" in raw:
        version = _cursor_int(raw.get("version"))
        if version is None or version > CURSOR_STATE_VERSION:
            d.update(reset=True, reason="state_version_unknown", origin="broken")
            _log_warn("增量状态版本不认识（退回全量重扫）: "
                      f"{raw.get('version')!r}")
            _log_event("cursor_reset", reason="version_unknown",
                       version=str(raw.get("version")))
            return _done({"accounts": {}})
    accounts = {}
    accounts_raw = raw.get("accounts")
    if accounts_raw is None:
        accounts_raw = {}
    if not isinstance(accounts_raw, dict):
        d.update(reset=True, reason="accounts_invalid", origin="broken")
        _log_warn("增量状态 accounts 字段异常（丢弃后退回全量重扫）")
        _log_event("cursor_reset", reason="accounts_invalid")
        accounts_raw = {}
    for acct, node in accounts_raw.items():
        if not isinstance(node, dict):
            _log_warn(f"增量状态账号节点异常（丢弃该账号游标）: {acct!r}")
            _log_event("cursor_reset", reason="account_node_invalid")
            continue
        node = dict(node)
        conv_raw = node.get("conversations")
        if conv_raw is None:
            conv_raw = {}
        if not isinstance(conv_raw, dict):
            _log_warn(f"增量状态会话游标异常（丢弃该账号游标）: {acct!r}")
            _log_event("cursor_reset", reason="conversations_invalid")
            conv_raw = {}
        conv = {}
        for user, ts in conv_raw.items():
            value = _cursor_int(ts)
            if value is None:
                _log_warn(f"增量状态会话游标非法（丢弃该会话）: {user!r}")
                _log_event("cursor_reset", reason="conv_cursor_invalid")
                continue
            conv[user] = value
        node["conversations"] = conv
        accounts[acct] = node
    return _done({"accounts": accounts})


def _account_conversations(state, acct):
    """取某账号已保存的会话游标副本（非 dict 一律当空处理）。"""
    node = (state.get("accounts") or {}).get(acct)
    if not isinstance(node, dict):
        return {}
    conv = node.get("conversations")
    if not isinstance(conv, dict):
        return {}
    return dict(conv)


def _save_state(state):
    """原子写增量状态：先写临时文件，再 os.replace 覆盖目标。

    写一半崩溃会留下损坏的 JSON（服务端读 state.json 时只能退化成"未知"）；
    os.replace 在同一文件系统内是原子替换，读者要么看到旧文件、要么看到
    完整的新文件；fsync 保证内容先落盘再改名。
    """
    os.makedirs(PC_DIR, exist_ok=True)
    if not isinstance(state, dict):
        state = {}
    if not isinstance(state.get("accounts"), dict):
        state["accounts"] = {}
    state["version"] = CURSOR_STATE_VERSION
    state["source"] = SOURCE_TAG
    state["cursor_kind"] = CURSOR_KIND
    state["last_run"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2, default=str)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, STATE_PATH)


# ------------------------------------------------------------------
# 单账号提取
# ------------------------------------------------------------------

def _mask_self_name(name):
    """对外脱敏：本人 wxid 只留首字符。

    stats 会写进 state.json / 日志 / 服务端的状态页，属于可能被同步走的
    文件，不该带真实 wxid。内部去重逻辑仍然用完整的 self_name。
    """
    if not name:
        return ""
    return name[0] + "***"


def extract_account(account, state_acct, dedup, dst, toolchain, writer=None,
                    archiver=None):
    """提取单个账号：解密 -> 遍历会话 -> 增量合并。返回统计 dict。

    dedup / dst / writer / archiver 由上层（run_extract）提供：dedup 是基于
    主库索引的去重器（不再把整张表读进 Python set），dst 是已打开的主库连接，
    writer 是分批提交写入器（批间释放写锁），archiver 是原文存档写入器
    （只写真正入库的新消息，与 app.py 的 _append_jsonl 同口径）。
    """
    _activate_common(sys.modules[__name__])
    wx_accounts, snapshot_and_decrypt, maybe_decompress = toolchain
    stats = {"conversations": 0, "scanned": 0, "inserted": 0,
             "duplicated": 0, "skipped": 0,
             "archived": 0, "archive_skipped": 0}
    parse_errors = []
    writer = writer if writer is not None else _BatchWriter(dst)
    archiver = archiver if archiver is not None else _JsonlArchiver()

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
        con = sqlite3.connect(_ro_uri(merged), uri=True)

        # 2. Name2Id：rowid -> username（real_sender_id 反查 + 表名反查）
        id2name = {}
        for rid, un in con.execute("SELECT rowid, user_name FROM Name2Id"):
            if un:
                id2name[rid] = un
        self_name = _resolve_self_username(account, id2name)
        # 对外只报首字符：stats 会落进 state.json / 日志 / 服务端状态页，
        # 等于把本人 wxid 写进了可能被同步走的文件（内部去重仍用完整 self_name）
        stats["self"] = _mask_self_name(self_name)
        hash2user = {hashlib.md5(u.encode()).hexdigest(): u
                     for u in id2name.values()}

        # 会话游标节点可能是脏数据（非 dict），一律当空处理：
        # 游标坏了最多多扫一遍，不该让整轮提取崩掉
        conv_state = state_acct.get("conversations")
        if not isinstance(conv_state, dict):
            conv_state = {}
            state_acct["conversations"] = conv_state
        now_ms = int(time.time() * 1000)

        # 3. 遍历会话表，按每会话最大 create_time 增量拉取
        tables = [r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name LIKE 'Msg_%'")]
        for t in tables:
            user = hash2user.get(t[4:])
            if not user or user in SKIP_USERS or user.endswith("@openim"):
                continue  # 反查不到 username 的表 / 系统会话 / openim 会话跳过
            last_ts = _cursor_int(conv_state.get(user, 0))
            if last_ts is None:
                last_ts = 0   # 游标非法：按从头扫处理（去重兜底，不会重复入库）
            try:
                # 流式取，不把整个会话 fetchall 进内存（内存与会话大小解耦）
                cur = con.execute(
                    f'SELECT real_sender_id, create_time, local_type, '
                    f'message_content FROM "{t}" WHERE create_time > ? '
                    f'ORDER BY create_time', (last_ts,))
            except sqlite3.Error as e:
                print(f"[warn] 账号 {account} 表 {t} 不可读，已跳过: {e}")
                continue
            stats["conversations"] += 1

            max_ts = last_ts
            for sid, ctime, ltype, content in cur:
                stats["scanned"] += 1
                max_ts = max(max_ts, ctime)
                try:
                    m = _parse_row(user, sid, ctime, ltype, content,
                                   name_of, id2name, self_name, maybe_decompress)
                except Exception as e:
                    # 解析失败不再静默吞：计数 + 记日志，数据缺失可追溯
                    stats["skipped"] += 1
                    if len(parse_errors) < 5:
                        parse_errors.append(
                            f"账号 {account} 表 {t}: {type(e).__name__}: {e}")
                    continue
                m["pkg"] = PKG
                # 与 android_db_extract 同口径：键含 pkg 与毫秒时间戳，
                # 与主库唯一索引 idx_messages_dedup 一致
                if dedup.is_duplicate(m):
                    stats["duplicated"] += 1
                    # 库里已有 = 首次入库方（/weixin 上报或更早的提取轮次）
                    # 已经写进存档，这里跳过，不再写第二份
                    archiver.skip()
                    stats["archive_skipped"] += 1
                    continue
                try:
                    writer.execute(
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
                    archiver.add(_archive_item(m))   # 真正入库的新消息进存档
                    stats["archived"] += 1
                except sqlite3.IntegrityError:
                    # 兜底：命中毫秒级唯一索引
                    stats["duplicated"] += 1
                    archiver.skip()
                    stats["archive_skipped"] += 1
                writer.maybe_commit()
                if writer.rows == 0:
                    dedup.reset_batch()   # 已提交，批内键集合可以清空
            conv_state[user] = max_ts
        if parse_errors:
            _log_warn(f"账号 {account} 解析失败 {stats['skipped']} 条，示例: "
                      + "；".join(parse_errors))
            _log_event("parse_errors", account=account,
                       count=stats["skipped"], samples="；".join(parse_errors))
        stats["parse_error_samples"] = parse_errors
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

def run_extract(full=False, account=None):
    """完整提取流程（全部账号或指定账号），返回统计 dict。"""
    _activate_common(sys.modules[__name__])
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

    cursor_diag = {"reset": False, "reason": "", "origin": "state"}
    state = _load_state(full=full, diag=cursor_diag)
    stats = {"full": bool(full), "accounts": {}, "errors": {}}

    # 原文存档：只写真正入库的新消息（与 app.py 的 _append_jsonl 同格式）
    archiver = _JsonlArchiver()

    # 跨进程写锁：两个提取进程（或 CLI 与服务端后台提取）同时写主库会互相
    # 抢 SQLite 写锁，把对方的写入推迟到 busy_timeout 之外。拿不到锁就明确
    # 报错退出，不静默并发写。
    lock = _FileLock()
    lock.acquire()
    dst = None
    try:
        # 与服务端 app.py 同口径：主库可能正被上报/控制台写入，
        # WAL 让读不阻塞写；busy_timeout 让偶发争用重试而不是直接抛
        # "database is locked"。真正管用的是分批提交把持锁时间压到秒级。
        dst = sqlite3.connect(MAIN_DB, timeout=30)
        dst.execute("PRAGMA busy_timeout = 30000")
        try:
            dst.execute("PRAGMA journal_mode = WAL")
        except sqlite3.Error:
            pass  # 别的连接正在切换日志模式时忽略
        _ensure_main_schema(dst)  # 独立运行时主库可能还没迁移 source / pkg 列
        # 去重在 SQLite 侧用索引查（键含 pkg 与毫秒时间戳），不再把整张表
        # 读进 Python set，内存占用与表大小解耦
        dedup = _Dedup(dst, SOURCE_TAG, PKG)
        # 批次提交成功之后才把本批新消息写进原文存档（先提交库、后写存档）
        writer = _BatchWriter(dst, on_commit=archiver.flush)

        # 2. 逐账号提取；单账号失败记录错误，不拖垮其它账号
        for a in accounts:
            acct = a["account"]
            keys_file = wx_accounts.keys_file_for(acct, a["db_dir"])
            if not os.path.exists(keys_file):
                stats["errors"][acct] = (
                    f"密钥文件不存在: {keys_file}（请先在该账号登录状态下"
                    "重新提取密钥）")
                continue
            # 会话游标用工作副本：账号提取失败时旧游标保持不变（不前进），
            # 成功才把本轮的会话游标写回状态文件
            acct_state = {"conversations": _account_conversations(state, acct)}
            try:
                stats["accounts"][acct] = extract_account(
                    acct, acct_state, dedup, dst, toolchain, writer, archiver)
            except ExtractError as e:
                stats["errors"][acct] = str(e)
                continue
            node = dict(state["accounts"].get(acct) or {})
            node["conversations"] = acct_state.get("conversations") or {}
            state["accounts"][acct] = node
        writer.flush()          # 收尾提交剩余批次
        stats["commits"] = writer.commits   # 写锁释放次数
    finally:
        if dst is not None:
            dst.close()
        lock.release()

    # 3. 落增量状态：只有至少一个账号成功才回写游标
    #    （全部账号失败 = 这一轮什么都没读到，写状态会把旧游标抹成空，
    #      下一轮反而从头重扫；这里直接保留旧游标）
    if stats["accounts"]:
        _save_state(state)
    else:
        _log_warn("本轮没有账号提取成功，保留原增量游标（不写状态文件）")
        _log_event("cursor_keep", reason="all_accounts_failed")

    # 4. 全部账号都失败时整体报错（密钥失效等场景不静默吞）
    if stats["errors"] and not stats["accounts"]:
        raise ExtractError("所有账号提取均失败: " + json.dumps(
            stats["errors"], ensure_ascii=False))

    stats["scanned"] = sum(s["scanned"] for s in stats["accounts"].values())
    stats["inserted"] = sum(s["inserted"] for s in stats["accounts"].values())
    stats["duplicated"] = sum(s["duplicated"]
                              for s in stats["accounts"].values())
    stats["skipped"] = sum(s["skipped"] for s in stats["accounts"].values())
    # 原文存档统计：真正写进 messages.jsonl 的条数 / 因去重跳过没重复存档的条数
    stats["archived"] = archiver.archived
    stats["archive_skipped"] = archiver.skipped
    stats["archive_errors"] = archiver.errors
    # 明确告诉调用方（app.py 后台提取 / CLI）这次跑成功了：
    # 服务端侧的成功判定要求 ok 明确为 True，缺键按失败处理（B11）。
    stats["ok"] = True
    # 游标诊断：本轮是否退回全量重扫、原因、游标口径与状态文件版本
    stats["cursor_reset"] = bool(cursor_diag.get("reset"))
    stats["cursor_reason"] = cursor_diag.get("reason", "")
    stats["cursor_kind"] = CURSOR_KIND
    stats["state_version"] = CURSOR_STATE_VERSION
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
    except (sqlite3.Error, OSError) as e:
        # sqlite3 / 文件系统异常同样要以非零退出码收场，别让失败看起来像成功
        print(f"[失败] {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)

    print("[成功] 提取合并完成")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
