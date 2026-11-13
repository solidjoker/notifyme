# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""
提取脚本的共享内核（android_db_extract.py / pc_db_extract.py 共用）
==================================================================
两个数据库直读脚本（安卓 EnMicroMsg.db 解密库 / PC 微信 message_0.db）在
「写主库」这一段是同一套实现：结构化日志、原文存档、跨进程写锁、SQLite
侧去重、分批提交、主库结构补列、游标值解析。以前是两份逐字复制的代码
（合计约 500 行），改一处忘另一处就会让两个来源的上报口径分叉。

分工：
    * 本模块只管「拿到一个连接之后怎么安全地写」；
    * 各脚本保留自己的业务段（解密 / 建表 / 行解析 / 游标状态读写 / CLI）。

配置注入（bind）：本模块不在 import 时快照任何配置。脚本在每个入口函数开头调用
bind(本模块)，此后所有默认值都在调用时到宿主脚本模块里现取
（MAIN_DB / PKG / SOURCE_TAG / 六个环境变量常量）。原因：测试用
monkeypatch.setattr(ade, "MAIN_DB", tmp) 改的是脚本模块的属性；若这里
在 import 时拷贝一份，存档与锁文件会写进真实 server/data（污染仓库），
COMMIT_BATCH 之类的调参也会静默失效。

依赖方向是单向的（脚本 -> 本模块），本模块不 import 任何业务脚本。
"""

import json
import os
import sqlite3
import sys
import time
from pathlib import Path
from urllib.parse import quote as _url_quote

# 跨进程锁：Windows 用 msvcrt.locking，POSIX 用 fcntl.flock，
# 都不可用时退化成「原子创建锁文件」（见 _FileLock）
try:
    import msvcrt
except ImportError:   # pragma: no cover - 非 Windows
    msvcrt = None
try:
    import fcntl
except ImportError:   # pragma: no cover - 非 Windows
    fcntl = None

# Windows 控制台默认 GBK，中文输出前强制 utf-8，避免 UnicodeEncodeError
# （脚本导入本模块即生效，两个入口的 stdout/stderr 编码因此同一口径）
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# 消息类型占位符（文本消息不走这张表，其余按类型给占位符）
TYPE_PLACEHOLDER = {
    3: "[图片]", 34: "[语音]", 43: "[视频]", 47: "[表情]",
    42: "[名片]", 48: "[位置]", 49: "[链接/文件]",
    10000: "[系统消息]",
}

# ------------------------------------------------------------------
# 配置注入：默认值在调用时从宿主（脚本）模块现取
# ------------------------------------------------------------------

_HOST = None

# 宿主模块没定义某项时的回退值，与两个脚本里的默认值一致（脚本自己也定义
# 同样的常量，那是为了让 monkeypatch 有目标）。0 是合法值
# （COMMIT_INTERVAL=0 表示只按条数提交），所以判 None 而不是判真值。
_DEFAULTS = {
    "MAIN_DB": "",
    "PKG": "com.tencent.mm",
    "SOURCE_TAG": "",
    "COMMIT_BATCH": 500,
    "COMMIT_INTERVAL": 2.0,
    "CROSS_SOURCE_SKEW_MS": 1000,
    "JSONL_MAX_BYTES": 64 * 1024 * 1024,
    "LOCK_TIMEOUT": 120.0,
    "LOCK_STALE_SECONDS": 3600.0,
}


def activate(module):
    """把宿主脚本模块登记为「当前配置来源」。

    两个脚本会先后导入本模块（同一进程里 ade / pde 共存），所以不能只在
    导入时登记一次 —— 最后导入的那个会顶掉前面那个的配置，导致先导入的脚本
    读到的 MAIN_DB / COMMIT_BATCH 全变成另一个脚本的值（存档写进别处、
    调参静默失效）。因此每个入口函数（merge_messages / run_extract /
    extract_account）开头都要再 activate 自己一次，把配置来源切回来。
    """
    global _HOST
    _HOST = module
    return module


def _setting(name):
    """取一项配置：宿主模块优先，缺失时用 _DEFAULTS（绝不在 import 时快照）。"""
    if _HOST is not None:
        value = getattr(_HOST, name, None)
        if value is not None:
            return value
    return _DEFAULTS[name]


# ------------------------------------------------------------------
# 结构化日志
# ------------------------------------------------------------------

class ExtractError(RuntimeError):
    """提取流程的可读错误，CLI/HTTP 层直接展示 message。"""


def _log_warn(msg):
    """告警统一走 stderr：stdout 只留最终 JSON，脚本化调用不受影响。"""
    print(f"[warn] {msg}", file=sys.stderr)


def _log_event(event, **fields):
    """结构化日志：event=<name> key=value ...（一行一条，走 stderr）。

    stdout 只留最终 JSON 统计，脚本化调用不受影响；
    字段顺序固定、值里的换行折成空格，方便 grep / 日志采集器按 key=value 解析。
    """
    parts = ["event=" + str(event)]
    for key, value in fields.items():
        if value is None:
            continue
        text = str(value).replace("\r", " ").replace("\n", " ")
        parts.append("%s=%s" % (key, text))
    print("[extract] " + " ".join(parts), file=sys.stderr)


def _ro_uri(path):
    """只读打开用的 file: URI（Windows 路径里的反斜杠/冒号要转义）。"""
    return "file:" + _url_quote(str(Path(path).absolute())) + "?mode=ro"


def _archive_path():
    """原文存档路径：默认与主库同目录（生产环境即 server/data/messages.jsonl）。

    运行时按 MAIN_DB 解析，而不是 import 时定死：主库被指到别处（临时目录、
    测试夹具）时，存档跟着主库走，不会把别处的消息写进正式存档。
    """
    return os.path.join(os.path.dirname(os.path.abspath(_setting("MAIN_DB"))),
                        "messages.jsonl")


def _lock_path():
    """跨进程写锁文件路径：与主库同目录（生产环境即 server/data/extract.lock）。"""
    return os.path.join(os.path.dirname(os.path.abspath(_setting("MAIN_DB"))),
                        "extract.lock")


# ------------------------------------------------------------------
# 原文存档（与 server/app.py 的 _append_jsonl 同口径）
# ------------------------------------------------------------------

def _archive_item(m, source=None, pkg=None):
    """构造一条存档记录：字段与客户端上报（/weixin）完全一致。

    app.py 的 _append_jsonl 写的是客户端上报的原始条目（蛇形 is_group），
    这里用同一套字段名、同一个 json.dumps(..., ensure_ascii=False) 序列化，
    保证两个写入方写出的存档行格式一致、可被同一套工具解析。
    """
    return {
        "sender": str(m.get("sender", "")),
        "text": str(m.get("text", "")),
        "timestamp": int(m.get("timestamp", 0)),
        "conversation": str(m.get("conversation", "")),
        "is_group": bool(m.get("is_group")),
        "pkg": str(pkg or m.get("pkg") or _setting("PKG")),
        "source": str(source or m.get("source") or _setting("SOURCE_TAG")),
    }


def _archive_line(item):
    """存档行：与 app.py _append_jsonl 一致（一行一条 JSON）。"""
    return json.dumps(item, ensure_ascii=False) + "\n"


class _JsonlArchiver:
    """把真正入库的新消息追加进 data/messages.jsonl（原文存档）。

    - 只追加写（O(1)），不重写整个文件；
    - 目录不存在先建；
    - 超过 JSONL_MAX_BYTES 轮转成 messages.jsonl.1（与 app.py 同口径：
      同名 .1 直接覆盖旧档）；
    - 只写真正 INSERT 成功的新消息，被去重跳过的重复行不写
      （否则存档条数会多于库内条数）；
    - 写失败只计数 + stderr 告警：库才是真相，存档写失败不该让整轮提取崩。
    """

    def __init__(self, path=None, max_bytes=None):
        self.path = path or _archive_path()
        self.max_bytes = int(_setting("JSONL_MAX_BYTES")
                             if max_bytes is None else max_bytes)
        self.archived = 0     # 成功写入的条数
        self.errors = 0       # 写入失败的条数
        self.skipped = 0      # 因去重跳过、没写存档的条数
        self._pending = []

    def add(self, item):
        """登记一条新消息（只有真正入库成功的才调）。"""
        self._pending.append(_archive_line(item))

    def skip(self, n=1):
        """登记「被去重跳过、没写存档」的条数。"""
        self.skipped += int(n)

    def flush(self):
        """把登记的条目追加落盘；写失败只计数 + 告警，返回写入条数。"""
        if not self._pending:
            return 0
        pending, self._pending = self._pending, []
        blob = "".join(pending)
        try:
            folder = os.path.dirname(os.path.abspath(self.path))
            if folder:
                os.makedirs(folder, exist_ok=True)
            self._maybe_rotate()
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(blob)
        except OSError as e:
            self.errors += len(pending)
            _log_warn(f"写入 jsonl 存档失败（{len(pending)} 条未存档，"
                      f"库内数据不受影响）: {e}")
            return 0
        self.archived += len(pending)
        return len(pending)

    def _maybe_rotate(self):
        """超限轮转：messages.jsonl -> messages.jsonl.1（与 app.py 同口径）。"""
        try:
            if not os.path.exists(self.path):
                return
            if os.path.getsize(self.path) <= self.max_bytes:
                return
            os.replace(self.path, self.path + ".1")
            _log_event("archive_rotated", path=self.path,
                       max_bytes=self.max_bytes)
        except OSError as e:
            # 轮转失败不致命：app.py 那边同样是 OSError 直接忽略
            _log_warn(f"jsonl 存档轮转失败（继续追加写）: {e}")


# ------------------------------------------------------------------
# 跨进程写锁
# ------------------------------------------------------------------

class _FileLock:
    """跨进程互斥锁：两个提取进程（或 CLI 与服务端后台提取）不得同时写主库。

    Windows 用 msvcrt.locking（字节区间锁，进程退出自动释放），
    POSIX 用 fcntl.flock；两者都不可用时退化为「原子创建锁文件 + 超时」。
    拿不到锁就抛 ExtractError：宁可明确失败，也不静默并发写主库。
    """

    def __init__(self, path=None, timeout=None):
        self.path = path or _lock_path()
        self.timeout = float(_setting("LOCK_TIMEOUT")
                             if timeout is None else timeout)
        self._fh = None
        self._excl = None      # 兜底路径创建的独占锁文件

    def acquire(self):
        """拿到锁返回 self；超时抛 ExtractError。"""
        deadline = time.monotonic() + self.timeout
        folder = os.path.dirname(os.path.abspath(self.path))
        if folder:
            os.makedirs(folder, exist_ok=True)
        last_err = "锁被其它进程占用"
        while True:
            try:
                if self._try_lock():
                    _log_event("lock_acquired", path=self.path)
                    return self
            except OSError as e:
                last_err = str(e)
            if time.monotonic() >= deadline:
                raise ExtractError(
                    f"拿不到提取写锁（{self.path}，已等待 {self.timeout:.0f}s）："
                    f"另一个提取进程可能正在写主库（{last_err}）")
            time.sleep(0.2)

    def release(self):
        """释放锁（finally 里必须调用）。"""
        if self._fh is not None:
            try:
                if msvcrt is not None:
                    self._fh.seek(0)
                    msvcrt.locking(self._fh.fileno(), msvcrt.LK_UNLCK, 1)
                elif fcntl is not None:
                    fcntl.flock(self._fh.fileno(), fcntl.LOCK_UN)
            except OSError:
                pass
            finally:
                try:
                    self._fh.close()
                except OSError:
                    pass
                self._fh = None
        if self._excl is not None:
            try:
                os.unlink(self._excl)
            except OSError:
                pass
            self._excl = None

    def _try_lock(self):
        """尝试拿锁，成功返回 True；被占用返回 False。"""
        if msvcrt is not None:
            fh = open(self.path, "a+b")
            try:
                if fh.seek(0, os.SEEK_END) == 0:
                    fh.write(b"0")     # 保证至少有 1 字节可锁
                    fh.flush()
                fh.seek(0)
                msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                fh.close()
                return False
            self._fh = fh
            return True
        if fcntl is not None:
            fh = open(self.path, "a+b")
            try:
                fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                fh.close()
                return False
            self._fh = fh
            return True
        # 兜底：原子创建独占锁文件（O_CREAT|O_EXCL 是原子的）
        excl = self.path + ".excl"
        try:
            fd = os.open(excl, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            try:
                age = time.time() - os.path.getmtime(excl)
            except OSError:
                age = 0
            if age > _setting("LOCK_STALE_SECONDS"):
                try:
                    os.unlink(excl)   # 上一个进程崩了留下的陈旧锁
                except OSError:
                    pass
            return False
        except OSError:
            return False
        try:
            os.write(fd, str(os.getpid()).encode("ascii"))
        finally:
            os.close(fd)
        self._excl = excl
        return True


class _Dedup:
    """基于主库索引的有界去重（不再把整张表读进 Python set）。

    去重键 = (pkg, conversation, sender, 毫秒时间戳, text)，与主库唯一索引
    idx_messages_dedup (pkg, conversation, sender, timestamp, text) 完全一致：
      * 同一来源同一条消息重复提取 -> 命中，重复插入被挡住；
      * 同 App 同秒但毫秒不同的两条消息 -> 键不同，两条都保留
        （旧的秒级归一键会把它们误判成重复，真的丢消息）；
      * 不同 App 的同名会话 -> 键含 pkg，不会互相吞掉。

    跨来源近重复（同一消息经通知渠道与数据库渠道各来一次，两边时间戳
    略有偏差）走单独一条宽松判定：只有来源不同、且时间差落在
    CROSS_SOURCE_SKEW_MS 窗口内，才算同一条消息。

    内存占用：只保留"当前批次"的键（批大小有界），历史键全部留在 SQLite
    索引里，内存占用与表大小解耦。
    """

    def __init__(self, conn, source, pkg, skew_ms=None):
        self.conn = conn
        self.source = source
        self.pkg = pkg
        self.skew_ms = (_setting("CROSS_SOURCE_SKEW_MS")
                        if skew_ms is None else skew_ms)
        self._batch_keys = set()

    def reset_batch(self):
        """批次提交后清空批内键集合（这些行已经在库里，交给索引判重）。"""
        self._batch_keys.clear()

    @staticmethod
    def _key(m):
        return (m["pkg"], m["conversation"], m["sender"],
                int(m["timestamp"]), m["text"])

    def is_duplicate(self, m):
        """True = 主库里已经有同一条消息，不应该再插入。"""
        key = self._key(m)
        if key in self._batch_keys:
            return True
        row = self.conn.execute(
            "SELECT 1 FROM messages WHERE pkg=? AND conversation=? AND sender=? "
            "AND timestamp=? AND text=? LIMIT 1", key).fetchone()
        if row:
            self._batch_keys.add(key)
            return True
        # 跨来源近重复：同一条消息经通知渠道与数据库渠道各来一次时，
        # 两边时间戳可能差几毫秒。只在来源不同时才放宽到时间差窗口。
        ts = int(m["timestamp"])
        row = self.conn.execute(
            "SELECT 1 FROM messages WHERE pkg=? AND conversation=? AND sender=? "
            "AND text=? AND source<>? AND timestamp BETWEEN ? AND ? LIMIT 1",
            (m["pkg"], m["conversation"], m["sender"], m["text"],
             self.source, ts - self.skew_ms, ts + self.skew_ms)).fetchone()
        if row:
            return True
        self._batch_keys.add(key)
        return False


class _BatchWriter:
    """分批提交写入器：每 N 条 / 每 T 秒 commit 一次，批间释放写锁。

    异常路径不留半截事务：异常时把已写入的批次提交掉（每条都是完整
    消息、去重幂等），既不丢已写数据，也不会把写事务悬着不放。
    """

    def __init__(self, conn, batch=None, interval=None, on_commit=None):
        self.conn = conn
        self.batch = max(1, int(_setting("COMMIT_BATCH")
                                if batch is None else batch))
        self.interval = max(0.0, float(_setting("COMMIT_INTERVAL")
                                        if interval is None else interval))
        self.rows = 0
        self.commits = 0
        self._last = time.monotonic()
        # on_commit：批次提交成功后的回调（存档写盘用）
        self.on_commit = on_commit

    def execute(self, sql, params):
        self.conn.execute(sql, params)
        self.rows += 1

    def commit(self):
        """提交当前批次；主库被占用时退避重试，连续失败才报错。"""
        last_err = None
        for attempt in range(5):
            try:
                self.conn.commit()
            except sqlite3.OperationalError as e:
                last_err = e
                if "locked" not in str(e) and "busy" not in str(e):
                    raise
                time.sleep(0.2 * (attempt + 1))
                continue
            committed_rows = self.rows
            self.commits += 1
            self.rows = 0
            self._last = time.monotonic()
            _log_event("batch_committed", rows=committed_rows,
                       commits=self.commits)
            if self.on_commit:
                # 提交成功之后才写存档：与 app.py「先提交库、后写存档」同口径，
                # 库没提交的行不能先落存档（否则存档里有库里没有的消息）
                self.on_commit()
            return
        raise ExtractError(f"提交批次失败（主库持续被占用）: {last_err}")

    def maybe_commit(self):
        """按条数或时间触发提交；时间间隔为 0 时只按条数。"""
        if self.rows >= self.batch or (
                self.interval and time.monotonic() - self._last >= self.interval):
            self.commit()

    def flush(self):
        """收尾提交（有未提交内容才提交）。"""
        if self.rows or self.conn.in_transaction:
            self.commit()



# ------------------------------------------------------------------
# 主库结构兜底（独立运行时主库可能还没迁移 source / pkg 列）
# ------------------------------------------------------------------
def _ensure_dedup_index(dst):
    """保证去重查询有索引可用。

    去重改成在 SQLite 侧查之后，查询形如
    "WHERE pkg=? AND conversation=? AND sender=? AND timestamp=? AND text=?"，
    没有索引就是每行一次全表扫描（时间 O(n²)；内存虽不受影响但很慢）。
    app.py 的 init_db() 会建唯一索引 idx_messages_dedup，但独立跑提取脚本时
    主库可能还没建过它，这里补一个同列索引兜底。

    用非唯一索引：老库里可能已经存在重复行，唯一索引会直接建失败。
    """
    try:
        index_rows = dst.execute("PRAGMA index_list(messages)").fetchall()
    except sqlite3.Error:
        return
    for row in index_rows:
        name = row[1]
        try:
            cols = [r[2] for r in dst.execute(
                f'PRAGMA index_info("{name}")').fetchall()]
        except sqlite3.Error:
            continue
        if cols[:5] == ["pkg", "conversation", "sender", "timestamp", "text"]:
            return   # 已有等价索引（多半是 app.py 建的 idx_messages_dedup）
    try:
        dst.execute(
            "CREATE INDEX IF NOT EXISTS idx_extract_dedup_lookup "
            "ON messages (pkg, conversation, sender, timestamp, text)")
    except sqlite3.Error as e:
        # 建不了索引不影响正确性（只是查询变慢），不因此中断提取
        _log_warn(f"去重索引创建失败（查询会变慢，不影响正确性）: {e}")


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
            f"NOT NULL DEFAULT '{_setting('PKG')}'")
    _ensure_dedup_index(dst)
    dst.commit()


def _cursor_int(value):
    """把游标字段安全转成非负 int；非法值返回 None（由调用方退回全量）。

    bool 是 int 的子类，必须先排除（True 会被当成游标 1）；
    负数、浮点小数、空串、None、容器类型一律判非法。
    """
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, float):
        return int(value) if value >= 0 and float(value).is_integer() else None
    if isinstance(value, str):
        text = value.strip()
        if text.startswith("+"):
            text = text[1:]
        if not text.isdigit():
            return None
        try:
            return int(text)
        except ValueError:
            return None
    return None
