# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""
安卓微信聊天记录数据库提取工具
================================
像 PC 端直读微信数据库一样，从 MuMu 模拟器里把安卓微信的
EnMicroMsg.db 拉出来、解密、合并进监测库 messages.db。

全流程（可独立 CLI 运行，也可被 app.py import 调用）：
    1. MuMuManager root shell 发现账号目录（MicroMsg/<md5("mm"+uin)>/）
    2. 从 MMKV 配置 files/mmkv/system_config_prefs 解析 default_uin
       （新版微信 uin 已不在 system_config.xml，而是存 MMKV，protobuf varint 编码）
    3. 推导解密密钥：MD5(IMEI + str(uin))[:7].lower()
       优先尝试从设备读真 IMEI，拿不到用历史兜底 "1234567890ABCDEF"
    4. root 下 cp EnMicroMsg.db 到 /data/local/tmp/ 并 chmod 644，adb pull 到本地
    5. 纯 Python 页级解密（WCDB2/SQLCipher：pageSize=1024、PBKDF2-HMAC-SHA1、
       4000 迭代、HMAC 关闭、reserve=16 仅 IV），采用 SQLCipher4 纯 Python
    6. 读 message / rcontact / chatroom 三表，增量合并进 messages.db
       （source='android-db'），按 (pkg, conversation, sender, 毫秒时间戳, text)
       去重——与主库唯一索引 idx_messages_dedup 同口径；跨来源近重复
       （通知渠道 vs 数据库渠道时间戳略有偏差）另按时间差窗口单独判定
    7. 清理设备与本地的临时文件
    8. 把真正入库的新消息按 /weixin 同格式追加进 data/messages.jsonl 原文存档

写入策略：分批提交（每 EXTRACT_COMMIT_BATCH 条 / 每 EXTRACT_COMMIT_INTERVAL 秒
提交一次），批与批之间释放 SQLite 写锁——整批一次提交会让提取期间主库的
写锁一直不释放，服务端 /weixin 上报被 busy_timeout 挡住后返回 500。
去重在 SQLite 侧用索引查，不再把整张表读进 Python set（内存与表大小解耦）。

增量游标：源库 message 表的 msgId（自增主键）就是增量水位，state.json 记
「已处理到的最大 msgId」，每轮只扫 msgId > 游标的新行；--full 忽略游标、
从 0 全量重扫并重建游标。游标状态文件的格式与自愈口径见下方
「增量游标状态」小节。

原文存档：真正入库的新消息按 server/app.py 的 _append_jsonl 同格式追加进
data/messages.jsonl（被去重跳过的重复行不写，否则存档条数会多于库内条数）；
O(1) 追加、超限轮转成 messages.jsonl.1；写入失败只计数 + stderr 告警，
不让整轮提取崩掉。

跨进程锁：写主库期间持一把跨进程文件锁（Windows 用 msvcrt.locking，
POSIX 用 fcntl.flock，都不可用时退化成原子创建锁文件 + 超时），
拿不到锁就明确报错退出，不静默并发写。

CLI 用法：
    python android_db_extract.py          # 增量（state.json 记录上次最大 msgId）
    python android_db_extract.py --full   # 全量重扫

所有外部工具路径均可通过环境变量覆盖：
    MUMU_MANAGER_PATH / MUMU_ADB_PATH / MUMU_ADB_SERIAL / MUMU_INDEX
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import struct
import subprocess
import sys
import time
from datetime import datetime

from Crypto.Cipher import AES  # pycryptodome，.venv 已装

# 共享内核（结构化日志 / 原文存档 / 跨进程锁 / 去重 / 分批提交 / 主库结构兜底）：
# 与 pc_db_extract.py 共用同一份实现，改动只需改 extract_common.py 一处。
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
# 可配置常量（环境变量优先）
# ------------------------------------------------------------------

# MuMu 12 安装目录下的管理器与 adb（注意：实际装在 Netease\MuMu 下，
# 不是文档里常见的 "MuMu Player 12\shell"，以环境变量覆盖最稳）
MUMU_MANAGER = os.environ.get(
    "MUMU_MANAGER_PATH",
    r"C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe")
ADB = os.environ.get(
    "MUMU_ADB_PATH",
    r"C:\Program Files\Netease\MuMu\nx_device\15.0\shell\adb.exe")
ADB_SERIAL = os.environ.get("MUMU_ADB_SERIAL", "127.0.0.1:16384")
MUMU_INDEX = os.environ.get("MUMU_INDEX", "0")

# 微信包名与关键设备路径
MM_PKG = "com.tencent.mm"
MM_MICROMSG = f"/data/data/{MM_PKG}/MicroMsg"
MM_MMKV_PREF = f"/data/data/{MM_PKG}/files/mmkv/system_config_prefs"  # uin 所在 MMKV
DEVICE_TMP_DB = "/data/local/tmp/EnMicroMsg.db"     # 设备临时落点（root cp 后 adb 可读）
DEVICE_TMP_PREF = "/data/local/tmp/scp_prefs"       # MMKV 临时落点

# 微信历史默认 IMEI 兜底（模拟器一般没有真 IMEI）
FALLBACK_IMEI = "1234567890ABCDEF"

# 本地数据目录
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
ANDROID_DIR = os.path.join(DATA_DIR, "android_db")     # 提取产物目录
LOCAL_DB = os.path.join(ANDROID_DIR, "EnMicroMsg.db")  # 拉取的加密库
DECRYPTED_DB = os.path.join(ANDROID_DIR, "EnMicroMsg.decrypted.db")  # 解密临时库
STATE_PATH = os.path.join(ANDROID_DIR, "state.json")   # 增量状态
MAIN_DB = os.path.join(DATA_DIR, "messages.db")        # 监测主库

SOURCE_TAG = "android-db"  # 本来源标记
# messages.pkg 口径：本脚本只导微信 Android 库，固定包名
# （与 server/app.py 的 DEFAULT_PKG 保持一致，控制台按来源 App 过滤要用）
PKG = "com.tencent.mm"

# ------------------------------------------------------------------
# 写入与去重参数（环境变量可覆盖）
# ------------------------------------------------------------------

# 分批提交：每 COMMIT_BATCH 条、或每 COMMIT_INTERVAL 秒提交一次，
# 批与批之间释放 SQLite 写锁。整批一次提交时，提取全程占着写锁，
# 服务端 /weixin 上报最多被 busy_timeout 挡 5 秒后 500（database is locked）。
COMMIT_BATCH = int(os.environ.get("EXTRACT_COMMIT_BATCH", "500"))
COMMIT_INTERVAL = float(os.environ.get("EXTRACT_COMMIT_INTERVAL", "2"))

# 跨来源近重复的时间差窗口（毫秒）：通知渠道上报的是"通知发布时间"，
# 数据库渠道是"消息创建时间"，同一条消息两者可能差几毫秒到几秒。
# 只有两条记录来源不同、且时间差落在窗口内，才判为同一条消息；
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

# WCDB2/SQLCipher 页加密参数（APK 逆向实证，见 .tmp_android_db/cipherspec.txt）
PAGE_SIZE = 1024
KDF_ITER = 4000
RESERVE = 16          # 仅 IV，HMAC 关闭
SQLITE_HDR = b"SQLite format 3\x00"


# ------------------------------------------------------------------
# 增量游标状态（state.json）
# ------------------------------------------------------------------
#
# 游标状态文件：server/data/android_db/state.json
# （DATA_DIR 下的来源子目录；PC 侧各存各的 server/data/pc_db/state.json，
#   两路游标互不影响，也不会互相覆盖。）
#
# 文件格式（version=2）：
# {
#   "version": 2,            # 格式版本；版本不认识 -> 退回全量重扫（自愈）
#   "source": "android-db",  # 来源标记（与 messages.source 同口径）
#   "cursor_kind": "msgId",  # 游标口径：源库 message 表的自增主键 msgId
#   "last_msgId": 12345,     # 游标值：已处理到的最大 msgId（下一轮扫 > 它）
#   "last_run": "2026-11-12 00:00:00",    # 最近一次提取时间
#   "last_full_run": "2026-11-12 00:00:00"  # 最近一次 --full 全量重扫时间
# }
#
# 兼容性：顶层保留 last_msgId / last_run 两个老键——服务端 app.py 的落地页
# 直接读 state.json 的 last_run 显示「最近提取」时间，键名不能动；老版本
# 写出来的 state.json（没有 version）也照常读取，读取时迁移到 version=2。
#
# 自愈口径（任何一种都只打 stderr 告警，绝不抛异常终止整轮提取）：
#   * 文件不存在        -> 首次运行，从 0 开始（= 全量）
#   * JSON 损坏 / 读不了 -> 退回全量重扫
#   * 不是 JSON 对象     -> 退回全量重扫
#   * version 不认识     -> 退回全量重扫（不猜测新格式的语义）
#   * last_msgId 非法    -> 退回全量重扫
# 退回全量是安全的：去重在 SQLite 侧按唯一索引判重，重扫不会产生重复行。
CURSOR_STATE_VERSION = 2
CURSOR_KIND = "msgId"


# ------------------------------------------------------------------
# 外部命令封装
# ------------------------------------------------------------------

def _run(cmd, timeout=120):
    """执行外部命令，失败时抛出带完整命令与输出的 ExtractError。"""
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        raise ExtractError(f"找不到可执行文件: {cmd[0]}（可用环境变量覆盖路径）")
    except subprocess.TimeoutExpired:
        raise ExtractError(f"命令超时({timeout}s): {' '.join(cmd)}")
    out = (p.stdout + p.stderr).decode("utf-8", errors="replace").strip()
    if p.returncode != 0:
        raise ExtractError(f"命令失败(rc={p.returncode}): {' '.join(cmd)}\n{out}")
    return out


def root_sh(shell_cmd, timeout=120):
    """通过 MuMuManager 在模拟器里执行 uid=0 shell 命令（adb shell 无 su）。"""
    return _run([MUMU_MANAGER, "sh", "-v", MUMU_INDEX, "-c", shell_cmd], timeout)


def adb(*args, timeout=120):
    """执行 adb 命令（固定 -s 指定模拟器序列号）。"""
    env = dict(os.environ, MSYS_NO_PATHCONV="1")  # 防 Git Bash 改写 /data 路径
    try:
        p = subprocess.run([ADB, "-s", ADB_SERIAL, *args],
                           capture_output=True, timeout=timeout, env=env)
    except FileNotFoundError:
        raise ExtractError(f"找不到 adb: {ADB}（可设 MUMU_ADB_PATH 覆盖）")
    out = (p.stdout + p.stderr).decode("utf-8", errors="replace").strip()
    if p.returncode != 0:
        raise ExtractError(f"adb {' '.join(args)} 失败(rc={p.returncode})\n{out}")
    return out


# ------------------------------------------------------------------
# 设备侧信息发现
# ------------------------------------------------------------------

def find_account_dir():
    """在 MicroMsg/ 下动态发现当前账号目录：32 位十六进制名且含 EnMicroMsg.db。"""
    out = root_sh(f"ls {MM_MICROMSG}/")
    candidates = [d for d in out.split() if re.fullmatch(r"[0-9a-f]{32}", d)]
    for d in candidates:
        chk = root_sh(f"test -f {MM_MICROMSG}/{d}/EnMicroMsg.db && echo Y || echo N")
        if chk.strip().endswith("Y"):
            return d
    raise ExtractError(
        f"未找到含 EnMicroMsg.db 的账号目录（候选: {candidates or '无'}），"
        "请确认模拟器里微信已登录")


def get_uin():
    """从 MMKV 配置解析 default_uin（有符 int32，protobuf varint 编码）。

    新版微信已不再写 shared_prefs/system_config.xml，uin 存于
    files/mmkv/system_config_prefs。MMKV 单条记录格式：
        [keyLen varint][key][valueLen varint][value(varint 编码的整数)]
    """
    # root 拷贝 -> adb pull -> 读完后清理
    root_sh(f"cp {MM_MMKV_PREF} {DEVICE_TMP_PREF} && chmod 644 {DEVICE_TMP_PREF}")
    local = os.path.join(ANDROID_DIR, "scp_prefs.tmp")
    os.makedirs(ANDROID_DIR, exist_ok=True)
    try:
        adb("pull", DEVICE_TMP_PREF, local)
        # adb 退出码为 0 不代表真的拉到了文件，按实际输出判断
        if not os.path.exists(local) or os.path.getsize(local) == 0:
            raise ExtractError(f"adb pull 之后本地文件为空或缺失: {local}")
        with open(local, "rb") as f:
            buf = f.read()
    finally:
        root_sh(f"rm -f {DEVICE_TMP_PREF}")
        if os.path.exists(local):
            os.remove(local)

    key = b"default_uin"
    p = 0
    bad = 0                  # 坏记录数：这一条解析失败，但不影响其它记录
    bad_samples = []
    while True:
        p = buf.find(key, p)
        if p < 0:
            break
        # keyLen 前缀必须恰好等于 len(key)，以此排除 "default_uin64" 等前缀命中
        if p >= 1 and buf[p - 1] == len(key):
            try:
                q = p + len(key)
                vlen, q = _read_varint(buf, q)      # 值长度
                raw = buf[q:q + vlen]
                val, _ = _read_varint(raw, 0)       # 值本体也是 varint（int64 编码）
            except ExtractError as e:
                # 坏记录只跳过这一条，继续找后面的同键记录：原来遇首条坏记录
                # 就中断整轮搜索，后面真正的 uin 也读不到了，整轮提取直接失败
                bad += 1
                if len(bad_samples) < 5:
                    bad_samples.append(f"偏移 {p}: {e}")
                p += 1
                continue
            if val >= 1 << 63:                  # 转有符号
                val -= 1 << 64
            if bad:
                _log_warn(f"MMKV 解析跳过 {bad} 条坏记录，示例: "
                          + "；".join(bad_samples))
            return int(val)
        p += 1
    if bad:
        _log_warn(f"MMKV 解析跳过 {bad} 条坏记录（仍未找到 default_uin），示例: "
                  + "；".join(bad_samples))
    raise ExtractError("MMKV 中未找到 default_uin，微信可能未登录")


def _read_varint(buf, pos):
    """读 protobuf varint，返回 (值, 新位置)。

    数据被截断/损坏时原来的 buf[pos] 会直接抛 IndexError 崩掉整个提取流程；
    这里统一抛 ExtractError，让调用方按"这条消息解析失败"处理。
    """
    result, shift = 0, 0
    while True:
        if pos >= len(buf):
            raise ExtractError("varint 越界：数据被截断或损坏")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            return result, pos
        if shift > 63:
            raise ExtractError("varint 超过 64 位：数据损坏")


def get_imei_candidates():
    """IMEI 候选序列：先试真 IMEI（多数模拟器拿不到），最后兜底历史默认值。"""
    candidates = []
    # 尝试 1：iphonesubinfo（需要 root；输出是 Parcel 十六进制转储，淘出可见字符）
    try:
        out = root_sh("service call iphonesubinfo 1", timeout=30)
        hexbytes = re.findall(r"[0-9a-fA-F]{8}", out)
        chars = "".join(
            chr(int(h[:4], 16)) for h in hexbytes
            if 0x20 <= int(h[:4], 16) < 0x7F)
        m = re.search(r"[0-9A-Za-z]{14,15}", chars)
        if m:
            candidates.append(m.group(0))
    except ExtractError:
        pass
    # 尝试 2：WLOGIN_DEVICE_INFO.xml 里的 imei 字段（hex 编码的字符串）
    try:
        out = root_sh(f"cat /data/data/{MM_PKG}/shared_prefs/WLOGIN_DEVICE_INFO.xml")
        m = re.search(r'name="imei"[^>]*value="([0-9a-fA-F]+)"', out)
        if m:
            try:
                candidates.append(bytes.fromhex(m.group(1)).decode())
            except ValueError:
                candidates.append(m.group(1))
    except ExtractError:
        pass
    candidates.append(FALLBACK_IMEI)
    # 去重保序
    seen, uniq = set(), []
    for c in candidates:
        if c and c not in seen:
            seen.add(c)
            uniq.append(c)
    return uniq


# ------------------------------------------------------------------
# 拉取与解密
# ------------------------------------------------------------------

def pull_db(account_dir):
    """root 拷贝 EnMicroMsg.db 到 /data/local/tmp 后 adb pull 到本地。"""
    src = f"{MM_MICROMSG}/{account_dir}/EnMicroMsg.db"
    os.makedirs(ANDROID_DIR, exist_ok=True)
    # root 下复制并放开读权限（直接 adb pull 无权读应用私有目录）
    root_sh(f"cp {src} {DEVICE_TMP_DB} && chmod 644 {DEVICE_TMP_DB}")
    size = root_sh(f"stat -c %s {DEVICE_TMP_DB}").strip().split()[-1]
    try:
        adb("pull", DEVICE_TMP_DB, LOCAL_DB, timeout=300)
    finally:
        root_sh(f"rm -f {DEVICE_TMP_DB}")
    # adb 退出码为 0 不代表真的拉到了文件，按实际输出判断
    if not os.path.exists(LOCAL_DB) or os.path.getsize(LOCAL_DB) == 0:
        raise ExtractError(
            f"adb pull 之后本地库为空或缺失: {LOCAL_DB}"
            "（adb 退出码为 0 不代表拉到了文件）")
    try:
        return int(size)
    except (TypeError, ValueError):
        raise ExtractError(f"stat 取不到文件大小: {size!r}")


def _derive_key(passphrase, salt):
    """PBKDF2-HMAC-SHA1，4000 迭代，32 字节密钥。"""
    return hashlib.pbkdf2_hmac("sha1", passphrase.encode(), salt, KDF_ITER, 32)


def decrypt_db(uin, imei_candidates):
    """纯 Python 页级解密 EnMicroMsg.db -> DECRYPTED_DB。

    密钥 = MD5(IMEI + str(uin))[:7].lower()，uin 尝试有符/无符两种写法；
    用第 1 页明文前两字节（SQLite 头偏移 16 的页大小字段）做命中校验。
    返回实际命中的 (imei描述, uin写法)。
    """
    data = open(LOCAL_DB, "rb").read()
    if len(data) % PAGE_SIZE != 0:
        raise ExtractError(f"EnMicroMsg.db 大小 {len(data)} 不是 {PAGE_SIZE} 的整数倍，"
                           "文件可能拉取不完整")
    salt = data[:16]
    uin_strs = [str(uin)]
    if uin < 0:
        uin_strs.append(str(uin & 0xFFFFFFFF))  # 无符 uint32 写法

    hit = None
    for imei in imei_candidates:
        for us in uin_strs:
            pw = hashlib.md5((imei + us).encode()).hexdigest()[:7].lower()
            key = _derive_key(pw, salt)
            page = data[:PAGE_SIZE]
            iv = page[PAGE_SIZE - RESERVE: PAGE_SIZE - RESERVE + 16]
            ct = page[16: PAGE_SIZE - RESERVE]
            pt = AES.new(key, AES.MODE_CBC, iv).decrypt(ct)
            if struct.unpack(">H", pt[0:2])[0] == PAGE_SIZE:
                hit = (pw, imei, us)
                break
        if hit:
            break
    if not hit:
        raise ExtractError(
            "所有 IMEI×uin 组合均未通过第 1 页校验，密钥推导失败"
            f"（uin={uin}，IMEI 候选数={len(imei_candidates)}）")

    pw, imei, us = hit
    key = _derive_key(pw, salt)
    npages = len(data) // PAGE_SIZE
    with open(DECRYPTED_DB, "wb") as f:
        for pgno in range(1, npages + 1):
            page = data[(pgno - 1) * PAGE_SIZE: pgno * PAGE_SIZE]
            iv = page[PAGE_SIZE - RESERVE: PAGE_SIZE - RESERVE + 16]
            if pgno == 1:  # 第 1 页前 16 字节是明文 salt，输出时换回 SQLite 头
                ct = page[16: PAGE_SIZE - RESERVE]
                pt = SQLITE_HDR + AES.new(key, AES.MODE_CBC, iv).decrypt(ct)
            else:
                ct = page[: PAGE_SIZE - RESERVE]
                pt = AES.new(key, AES.MODE_CBC, iv).decrypt(ct)
            f.write(pt + b"\x00" * RESERVE)  # reserve 区补零占位
    # 修头：journal mode 改 rollback（偏移 18/19），页数写回（偏移 28）
    with open(DECRYPTED_DB, "r+b") as f:
        f.seek(18)
        f.write(b"\x01\x01")
        f.seek(28)
        f.write(struct.pack(">I", npages))
    return imei, us


# ------------------------------------------------------------------
# 解析与合并
# ------------------------------------------------------------------

def _load_name_maps(con):
    """构建 wxid -> 显示名 映射：rcontact（备注优先）+ chatroom（群名）。"""
    contact_name = {}
    for username, remark, nickname in con.execute(
            "SELECT username, conRemark, nickname FROM rcontact"):
        # 备注(conRemark)优先，其次昵称，最后 username 本身
        contact_name[username] = remark or nickname or username
    chatroom_name = {}
    for name, display in con.execute(
            "SELECT chatroomname, displayname FROM chatroom"):
        chatroom_name[name] = display or name
    return contact_name, chatroom_name


def _parse_row(row, contact_name, chatroom_name):
    """把 message 表一行解析成监测库的统一字段。

    返回 dict(conversation, sender, timestamp, text, is_group)。
    """
    msg_id, _svr, mtype, is_send, create_ms, talker, content = row
    is_group = talker.endswith("@chatroom")
    content = content or ""

    if is_group:
        # 群聊：conversation 取群名（rcontact 备注/昵称优先，其次 chatroom.displayname）
        conversation = contact_name.get(talker) or chatroom_name.get(talker) or talker
        if is_send:
            sender, text = "我", content
        else:
            # 群消息格式：「发送者wxid:\n内容」
            if ":\n" in content:
                wxid, text = content.split(":\n", 1)
                sender = contact_name.get(wxid, wxid)
            else:
                sender, text = talker, content
    else:
        # 私聊：conversation 与 sender 都是对方显示名；我发的 sender 记「我」
        peer = contact_name.get(talker, talker)
        conversation = peer
        sender = "我" if is_send else peer
        text = content

    # 非文本类型记占位符（任务要求：type=1 原文，其它占位）
    if mtype != 1:
        text = TYPE_PLACEHOLDER.get(mtype, f"[类型{mtype}消息]")

    return {
        "conversation": conversation,
        "sender": sender,
        "timestamp": int(create_ms),
        "text": text,
        "is_group": 1 if is_group else 0,
    }



def _load_cursor_state(full=False):
    """读游标状态，返回 (游标值, 诊断 dict, 原始 state dict)。

    诊断 dict：{"reset": bool 是否退回全量, "reason": str 原因,
                "origin": "state"|"full"|"missing"|"broken"}
    原始 state dict：读到的原始内容（用于保留未识别字段，方便以后扩展）；
    读不出来时是空 dict。

    任何异常路径都只打 stderr 告警并退回全量，绝不把异常抛给调用方——
    状态文件坏了不该让整轮提取失败（去重兜底，重扫不会产生重复行）。
    """
    diag = {"reset": False, "reason": "", "origin": "state"}
    if full:
        diag.update(reset=True, reason="full_scan", origin="full")
        _log_event("cursor_reset", reason="full_scan", path=STATE_PATH)
        return 0, diag, {}

    if not os.path.exists(STATE_PATH):
        diag.update(reset=True, reason="state_missing", origin="missing")
        _log_event("cursor_reset", reason="state_missing", path=STATE_PATH)
        return 0, diag, {}

    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except (json.JSONDecodeError, OSError, UnicodeDecodeError) as e:
        diag.update(reset=True, reason=f"state_unreadable: {e}", origin="broken")
        _log_warn(f"增量状态读取失败（退回全量重扫）: {e}")
        _log_event("cursor_reset", reason="unreadable", detail=str(e))
        return 0, diag, {}

    if not isinstance(raw, dict):
        diag.update(reset=True, reason="state_not_object", origin="broken")
        _log_warn("增量状态格式异常（不是 JSON 对象，退回全量重扫）")
        _log_event("cursor_reset", reason="not_object")
        return 0, diag, {}

    # 版本：缺版本 = 老格式（v1，只有 last_msgId/last_run），照常读；
    # 版本不认识（更高版本 / 非法类型）不猜语义，直接退回全量重扫。
    if "version" in raw:
        version = _cursor_int(raw.get("version"))
        if version is None or version > CURSOR_STATE_VERSION:
            diag.update(reset=True, reason="state_version_unknown", origin="broken")
            _log_warn("增量状态版本不认识（退回全量重扫）: "
                      f"{raw.get('version')!r}")
            _log_event("cursor_reset", reason="version_unknown",
                       version=str(raw.get("version")))
            return 0, diag, raw

    last_id = _cursor_int(raw.get("last_msgId", 0))
    if last_id is None:
        diag.update(reset=True, reason="state_cursor_invalid", origin="broken")
        _log_warn("增量状态游标字段非法（退回全量重扫）: "
                  f"last_msgId={raw.get('last_msgId')!r}")
        _log_event("cursor_reset", reason="cursor_invalid",
                   value=str(raw.get("last_msgId")))
        return 0, diag, raw

    return last_id, diag, raw


def _save_state(state):
    """原子写增量状态：先写临时文件，再 os.replace 覆盖目标。

    直接 open(STATE_PATH, "w") 写一半崩溃会留下损坏的 JSON，
    服务端读 state.json 时只能退化成"未知"。os.replace 在同一文件系统内
    是原子替换，读者要么看到旧文件、要么看到完整的新文件。

    写出的文件带 version / source / cursor_kind；老键 last_msgId / last_run
    原样保留（app.py 的落地页读 last_run 显示「最近提取」时间）。
    default=str：状态里混进不可序列化对象时不至于写崩整轮提取。
    """
    os.makedirs(ANDROID_DIR, exist_ok=True)
    payload = dict(state or {})
    payload["version"] = CURSOR_STATE_VERSION
    payload["source"] = SOURCE_TAG
    payload["cursor_kind"] = CURSOR_KIND
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2, default=str)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, STATE_PATH)


def merge_messages(full=False):
    """读解密库，增量/全量合并进 messages.db，返回统计 dict。

    - 写入分批提交（每 COMMIT_BATCH 条 / 每 COMMIT_INTERVAL 秒提交一次），
      批与批之间释放 SQLite 写锁，服务端 /weixin 上报不会被锁死；
    - 去重在 SQLite 侧按唯一索引查（键含 pkg 与毫秒时间戳），
      不再把整张表读进 Python set，内存占用与表大小解耦；
    - 解析失败计数 + 记日志，不再静默吞掉。
    """
    _activate_common(sys.modules[__name__])
    # 读增量状态（损坏 / 字段非法 / 版本不认识 -> 退回全量重扫，只告警不抛异常）
    last_id, cursor_diag, prev_state = _load_cursor_state(full=full)

    now_ms = int(time.time() * 1000)
    inserted = duplicated = skipped = scanned = 0
    max_id = last_id
    parse_errors = []
    commits = 0

    # 原文存档：只写真正入库的新消息（与 app.py 的 _append_jsonl 同格式）
    archiver = _JsonlArchiver()

    # 跨进程写锁：两个提取进程（或 CLI 与服务端后台提取）同时写主库会互相
    # 抢 SQLite 写锁，把对方的写入推迟到 busy_timeout 之外。拿不到锁就明确
    # 报错退出，不静默并发写。
    lock = _FileLock()
    lock.acquire()
    src = None
    dst = None
    try:
        # 源库只读打开；行是流式取的，不把整表 fetchall 进内存
        src = sqlite3.connect(_ro_uri(DECRYPTED_DB), uri=True)
        # 与服务端 app.py 同口径：WAL 让读不阻塞写；busy_timeout 让偶发争用
        # 重试而不是直接抛 "database is locked"。真正管用的是分批提交把
        # 持锁时间压到秒级。
        dst = sqlite3.connect(MAIN_DB, timeout=30)
        dst.execute("PRAGMA busy_timeout = 30000")
        try:
            dst.execute("PRAGMA journal_mode = WAL")
        except sqlite3.Error:
            pass  # 别的连接正在切换日志模式时忽略
        try:
            contact_name, chatroom_name = _load_name_maps(src)
            cur = src.execute(
                """
                SELECT msgId, msgSvrId, type, isSend, createTime, talker, content
                FROM message WHERE msgId > ? ORDER BY msgId
                """, (last_id,))
            # 批次提交成功之后才把本批新消息写进原文存档（先提交库、后写存档）
            writer = _BatchWriter(dst, on_commit=archiver.flush)
            dedup = _Dedup(dst, SOURCE_TAG, PKG)
            try:
                _ensure_main_schema(dst)  # 独立运行时主库可能还没迁移 source / pkg 列
                for row in cur:
                    scanned += 1
                    max_id = max(max_id, row[0])
                    try:
                        m = _parse_row(row, contact_name, chatroom_name)
                    except Exception as e:
                        # 解析失败不再静默吞：计数 + 记日志，数据缺失可追溯
                        skipped += 1
                        if len(parse_errors) < 5:
                            parse_errors.append(
                                f"msgId={row[0]}: {type(e).__name__}: {e}")
                        continue
                    m["pkg"] = PKG
                    if dedup.is_duplicate(m):
                        duplicated += 1
                        # 库里已有 = 首次入库方（/weixin 或更早的提取轮次）
                        # 已经写进存档，这里跳过，不再写第二份
                        archiver.skip()
                        continue
                    try:
                        writer.execute(
                            """
                            INSERT INTO messages
                                (sender, text, timestamp, conversation, is_group,
                                 received_at, source, pkg)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            (m["sender"], m["text"], m["timestamp"], m["conversation"],
                             m["is_group"], now_ms, SOURCE_TAG, PKG))
                        inserted += 1
                        archiver.add(_archive_item(m))   # 真正入库的新消息进存档
                    except sqlite3.IntegrityError:
                        # 兜底：命中毫秒级唯一索引（两来源毫秒恰好相同的极端情况）
                        duplicated += 1
                        archiver.skip()
                    writer.maybe_commit()
                    if writer.rows == 0:
                        dedup.reset_batch()   # 已提交，批内键集合可以清空
                writer.flush()
                commits = writer.commits
            except BaseException:
                # 中途异常：把已写入但未提交的批次提交掉再把异常抛出去。每条都是
                # 完整消息、去重幂等，所以既不留半截事务也不丢数据（state.json 不
                # 更新，下次重跑会重扫，去重会挡住重复行）。
                try:
                    writer.flush()
                except Exception:
                    try:
                        dst.rollback()
                    except Exception:
                        pass
                raise
        finally:
            cur.close()
    finally:
        if src is not None:
            src.close()
        if dst is not None:
            dst.close()
        lock.release()

    if parse_errors:
        _log_warn(f"解析失败 {skipped} 条，示例: " + "；".join(parse_errors))
        _log_event("parse_errors", count=skipped,
                   samples="；".join(parse_errors))

    # 更新增量状态（原子写）；保留读到的未识别字段，方便以后扩展
    now_text = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    state = dict(prev_state)
    state.update({
        "last_msgId": max_id,
        "last_run": now_text,
    })
    if full:
        state["last_full_run"] = now_text   # 标记最近一次全量重扫
    _save_state(state)

    return {
        "ok": True,                # 明确成功标记（app.py 要求 ok 明确为 True）
        "scanned": scanned,        # 本次扫描到的 message 行数
        "inserted": inserted,      # 新合并进监测库的条数
        "duplicated": duplicated,  # 归一去重跳过的条数
        "skipped": skipped,        # 解析失败的条数
        "parse_error_samples": parse_errors,  # 解析失败样例（最多 5 条）
        "commits": commits,        # 实际提交次数 = 写锁释放次数
        "archived": archiver.archived,        # 写入原文存档（messages.jsonl）的条数
        "archive_skipped": archiver.skipped,  # 去重跳过、未重复写存档的条数
        "archive_errors": archiver.errors,    # 存档写失败的条数（库内数据不受影响）
        "last_msgId": max_id,
        # 游标诊断：本轮是否退回全量重扫、原因、游标口径与状态文件版本
        "cursor_reset": bool(cursor_diag.get("reset")),
        "cursor_reason": cursor_diag.get("reason", ""),
        "cursor_kind": CURSOR_KIND,
        "state_version": CURSOR_STATE_VERSION,
    }


# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------

def run_extract(full=False, keep_files=False):
    """完整提取流程，返回统计 dict（供 CLI 打印 / app.py 状态页使用）。"""
    _activate_common(sys.modules[__name__])
    t0 = time.time()
    stats = {"full": bool(full), "steps": {}}

    # 1. 发现账号目录
    stats["steps"]["account_dir"] = find_account_dir()

    # 2. 读 uin
    uin = get_uin()
    stats["steps"]["uin_found"] = True  # 不记录具体 uin 值（敏感信息）

    # 3. IMEI 候选
    imeis = get_imei_candidates()

    # 4. 拉取加密库
    stats["steps"]["db_bytes"] = pull_db(stats["steps"]["account_dir"])

    try:
        # 5. 解密
        imei_used, uin_form = decrypt_db(uin, imeis)
        stats["steps"]["decrypt"] = "ok"
        stats["steps"]["imei_source"] = (
            "fallback" if imei_used == FALLBACK_IMEI else "device")

        # 6. 合并
        stats.update(merge_messages(full=full))
    finally:
        # 7. 清理本地临时文件（解密库含明文聊天，默认删除；加密库保留便于排查）。
        # 放在 finally：解密写了一半或合并中途报错也不能把明文库留在磁盘上。
        if not keep_files and os.path.exists(DECRYPTED_DB):
            os.remove(DECRYPTED_DB)

    # 明确告诉调用方（app.py 后台提取 / CLI）这次跑成功了：
    # 服务端侧的成功判定要求 ok 明确为 True，缺键按失败处理（B11）。
    stats["ok"] = True
    stats["elapsed_sec"] = round(time.time() - t0, 2)
    stats["finished_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return stats


def main():
    parser = argparse.ArgumentParser(description="安卓微信聊天记录数据库提取合并工具")
    parser.add_argument("--full", action="store_true",
                        help="全量重扫（默认增量，基于 state.json 的 last_msgId）")
    parser.add_argument("--keep-files", action="store_true",
                        help="保留解密临时库（调试用；含明文聊天，慎用）")
    args = parser.parse_args()

    try:
        stats = run_extract(full=args.full, keep_files=args.keep_files)
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
