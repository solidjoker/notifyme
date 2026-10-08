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
       （source='android-db'），按 (conversation, sender, 秒级时间戳, text) 归一去重
    7. 清理设备与本地的临时文件

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

# WCDB2/SQLCipher 页加密参数（APK 逆向实证，见 .tmp_android_db/cipherspec.txt）
PAGE_SIZE = 1024
KDF_ITER = 4000
RESERVE = 16          # 仅 IV，HMAC 关闭
SQLITE_HDR = b"SQLite format 3\x00"

# 消息类型占位符（type=1 为文本，其余按类型给占位符）
TYPE_PLACEHOLDER = {
    3: "[图片]", 34: "[语音]", 43: "[视频]", 47: "[表情]",
    42: "[名片]", 48: "[位置]", 49: "[链接/文件]",
    10000: "[系统消息]",
}


class ExtractError(RuntimeError):
    """提取流程的可读错误，CLI/HTTP 层直接展示 message。"""


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
        buf = open(local, "rb").read()
    finally:
        root_sh(f"rm -f {DEVICE_TMP_PREF}")
        if os.path.exists(local):
            os.remove(local)

    key = b"default_uin"
    p = 0
    while True:
        p = buf.find(key, p)
        if p < 0:
            break
        # keyLen 前缀必须恰好等于 len(key)，以此排除 "default_uin64" 等前缀命中
        if p >= 1 and buf[p - 1] == len(key):
            q = p + len(key)
            vlen, q = _read_varint(buf, q)      # 值长度
            raw = buf[q:q + vlen]
            val, _ = _read_varint(raw, 0)       # 值本体也是 varint（int64 编码）
            if val >= 1 << 63:                  # 转有符号
                val -= 1 << 64
            return int(val)
        p += 1
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
    return int(size)


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


def merge_messages(full=False):
    """读解密库，增量/全量合并进 messages.db，返回统计 dict。"""
    # 读增量状态
    state = {"last_msgId": 0}
    if os.path.exists(STATE_PATH) and not full:
        try:
            state = json.load(open(STATE_PATH, encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    last_id = 0 if full else int(state.get("last_msgId", 0))

    src = sqlite3.connect("file:" + DECRYPTED_DB + "?mode=ro", uri=True)
    try:
        contact_name, chatroom_name = _load_name_maps(src)
        rows = src.execute(
            """
            SELECT msgId, msgSvrId, type, isSend, createTime, talker, content
            FROM message WHERE msgId > ? ORDER BY msgId
            """, (last_id,)).fetchall()
    finally:
        src.close()

    now_ms = int(time.time() * 1000)
    inserted = duplicated = skipped = 0
    max_id = last_id

    # 与服务端 app.py 同口径：主库可能正被上报/控制台写入，
    # 不给 busy_timeout 时会直接抛 "database is locked"
    dst = sqlite3.connect(MAIN_DB, timeout=10)
    dst.execute("PRAGMA busy_timeout = 5000")
    try:
        _ensure_main_schema(dst)  # 独立运行时主库可能还没迁移 source / pkg 列
        # 建归一去重键集合：现有全部消息 + 本次批次
        # 键 = (conversation, sender, 秒级时间戳, text)
        # 秒级归一是因为通知来源与数据库来源对同一条消息的毫秒时间戳可能不同
        existing = set()
        for conv, sender, ts, text in dst.execute(
                "SELECT conversation, sender, timestamp, text FROM messages"):
            existing.add((conv, sender, ts // 1000, text))

        batch_keys = set()
        for row in rows:
            max_id = max(max_id, row[0])
            try:
                m = _parse_row(row, contact_name, chatroom_name)
            except Exception:
                skipped += 1
                continue
            key = (m["conversation"], m["sender"], m["timestamp"] // 1000, m["text"])
            if key in existing or key in batch_keys:
                duplicated += 1
                continue
            batch_keys.add(key)
            try:
                dst.execute(
                    """
                    INSERT INTO messages
                        (sender, text, timestamp, conversation, is_group,
                         received_at, source, pkg)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (m["sender"], m["text"], m["timestamp"], m["conversation"],
                     m["is_group"], now_ms, SOURCE_TAG, PKG))
                inserted += 1
            except sqlite3.IntegrityError:
                # 兜底：命中毫秒级唯一索引（两来源毫秒恰好相同的极端情况）
                duplicated += 1
        dst.commit()
    finally:
        dst.close()

    # 更新增量状态
    state.update({
        "last_msgId": max_id,
        "last_run": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    })
    with open(STATE_PATH, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)

    return {
        "scanned": len(rows),      # 本次扫描到的 message 行数
        "inserted": inserted,      # 新合并进监测库的条数
        "duplicated": duplicated,  # 归一去重跳过的条数
        "skipped": skipped,        # 解析失败的条数
        "last_msgId": max_id,
    }


# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------

def run_extract(full=False, keep_files=False):
    """完整提取流程，返回统计 dict（供 CLI 打印 / app.py 状态页使用）。"""
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

    print("[成功] 提取合并完成")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
