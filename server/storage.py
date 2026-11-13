# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""notifyme 服务端 —— 存储层。

SQLite 连接与建库、JSONL 归档与轮转、顾问存档读写、抽库后台任务。
路径、限额等运行期配置一律在调用时从 app 模块动态读取。
"""

import json
import os
import sqlite3
import sys
import time
import importlib.util

from util import logger

def _connect():
    """统一的 SQLite 连接：WAL + busy_timeout。

    默认的 rollback journal 下，读写会互相阻塞；Flask 是多线程的
    （threaded=True），控制台查询与 App 上报一并发就会出现
    "database is locked"。WAL 让读不阻塞写，busy_timeout 让偶发争用重试而不是直接报错。
    """
    import app as app_module
    # timeout 与 busy_timeout 保持一致：sqlite3.connect 的 timeout 只是
    # 首次取锁的默认等待，真正的等待由 busy_timeout 决定，两者不一致会让
    # 等待行为难以推断（评审 P3）。15s 覆盖提取脚本分批提交时的持锁窗口。
    conn = sqlite3.connect(app_module.DB_PATH, timeout=15)
    conn.execute("PRAGMA busy_timeout = 15000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
    return conn

def _ensure_index(conn, name, ddl):
    """按需建索引：已存在且定义一致就不动它。

    原来每次启动都 DROP + CREATE 唯一索引（含全文 text），
    几万行以上的库重建一次要几十秒，而且建索引全程持写锁
    → 上报请求被 database is locked 挡住 5 秒后 500。
    """
    row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
        (name,)).fetchone()
    if row and row[0]:
        want = " ".join(ddl.replace(
            "CREATE UNIQUE INDEX IF NOT EXISTS",
            "CREATE UNIQUE INDEX").split()).lower()
        have = " ".join(row[0].split()).lower()
        if want == have:
            return
        # 定义变了才重建（索引名是本文件里的常量，不来自请求）
        conn.execute("DROP INDEX IF EXISTS " + name)
    conn.execute(ddl)

def init_db():
    """初始化数据目录和 SQLite 表结构（含去重用的唯一索引）。"""
    import app as app_module
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    conn = app_module._connect()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                sender        TEXT,
                text          TEXT,
                timestamp     INTEGER,   -- 消息时间戳（毫秒）
                conversation  TEXT,
                is_group      INTEGER,   -- SQLite 无布尔型，0/1 存储
                received_at   INTEGER,   -- 服务端接收时间（毫秒）
                pkg           TEXT       -- 来源 App 包名（M2）
            )
            """
        )
        # 迁移：老库补 source 列，区分消息来源
        #   android-notification = 通知监听上报（默认，历史数据）
        #   android-db           = 安卓数据库直读合并（android_db_extract.py）
        # 说明：跨来源去重不依赖此索引（毫秒时间戳不一致），
        # 由 android_db_extract 在 SQLite 侧按 (pkg, conversation, sender,
        # 毫秒 timestamp, text) 归一键查询完成（不再在 Python 侧全表建集合）；
        # 此索引继续负责通知来源的重试去重和毫秒级完全相同时的兜底。
        cols = [r[1] for r in conn.execute("PRAGMA table_info(messages)")]
        if "source" not in cols:
            conn.execute(
                "ALTER TABLE messages ADD COLUMN source TEXT "
                "NOT NULL DEFAULT 'android-notification'")
        # M2 迁移：老库补 pkg 列并回填微信（历史数据全部来自微信）。
        if "pkg" not in cols:
            conn.execute(
                "ALTER TABLE messages ADD COLUMN pkg TEXT "
                f"NOT NULL DEFAULT '{app_module.DEFAULT_PKG}'")
        # 去重唯一索引（M2 版）：列补齐后再（重）建，去重维度含 pkg——
        # 不同 App 的同名会话里同发送者同毫秒同内容不再被误判为重复。
        # 老库的旧索引不含 pkg，先丢弃再建新索引（索引定义无法直接改）。
        app_module._ensure_index(
            conn, "idx_messages_dedup",
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedup
            ON messages (pkg, conversation, sender, timestamp, text)""")
        # 分析结果表：App 侧会话级分析（S1 轻量模型 + S2 深度分析）的上报存档
        # case_id 唯一：同一次分析重复上报直接跳过
        # s1 / s2 存 JSON 原文；need_action / importance 单独抽列方便过滤
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS analyses (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                case_id       TEXT UNIQUE,   -- 分析用例 ID（App 侧生成，去重键）
                conversation  TEXT,          -- 会话名
                window_end    INTEGER,       -- 分析窗口右端（毫秒）
                message_count INTEGER,       -- 窗口内消息数
                need_action   INTEGER,       -- S1 判定是否需要行动 0/1
                importance    TEXT,          -- S1 重要度（如 high/mid/low）
                escalated     INTEGER,       -- 是否升级到 S2 深度分析 0/1
                s1            TEXT,          -- S1 结果 JSON 原文
                s2            TEXT,          -- S2 结果 JSON 原文（未升级为 NULL）
                analyzed_at   TEXT,          -- App 侧分析完成时间（原样存）
                protocol      TEXT,          -- 协议版本
                received_at   INTEGER,       -- 服务端接收时间（毫秒）
                pkg           TEXT           -- 来源 App 包名（M2）
            )
            """
        )
        # 迁移：老库补 forks / filtered 列（fork 层改造，App 侧 v0.1.2+ 上报）
        #   forks    = 分叉决策轨迹 JSON 数组 [{fork, prob, verdict, note}]
        #   filtered = 被 fork1 预筛就地结案 0/1（filtered 的 case 不调 laya）
        acols = [r[1] for r in conn.execute("PRAGMA table_info(analyses)")]
        if "forks" not in acols:
            conn.execute("ALTER TABLE analyses ADD COLUMN forks TEXT")
        if "filtered" not in acols:
            conn.execute(
                "ALTER TABLE analyses ADD COLUMN filtered INTEGER "
                "NOT NULL DEFAULT 0")
        # M2 迁移：老库补 pkg 列并回填微信（历史分析全部来自微信）。
        if "pkg" not in acols:
            conn.execute(
                "ALTER TABLE analyses ADD COLUMN pkg TEXT "
                f"NOT NULL DEFAULT '{app_module.DEFAULT_PKG}'")
        # 迁移：补齐客户端已经在上报、但服务端表里没有的列。
        # 客户端 AnalysisStore 会带脱敏证据（redacted / redaction_rules /
        # redaction_hits）与手动改写（todo_text / todo_due）；
        # 老表没有这些列 → INSERT 直接报 no such column，
        # 整批分析上报被拒（客户端当成失败重试，永远上不来）。
        for _col, _ddl in (("redacted", "INTEGER"),
                           ("redaction_rules", "TEXT"),
                           ("redaction_hits", "TEXT"),
                           ("todo_text", "TEXT"),
                           ("todo_due", "TEXT")):
            if _col not in acols:
                conn.execute(
                    "ALTER TABLE analyses ADD COLUMN " + _col + " " + _ddl)
        # 查询索引：/messages 与控制台都按时间倒序翻页并按会话/来源过滤，
        # 没有索引时每次查询都是全表扫描 + 排序（历史越大越慢）。
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (timestamp)")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_messages_conv_ts "
            "ON messages (conversation, timestamp)")
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_window_end "
            "ON analyses (window_end)")
        # 控制台按接收时间倒序取分析结果，没有索引就是全表扫 + 临时排序
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_received_at "
            "ON analyses (received_at)")
        # 按会话过滤分析结果：conversation 前缀走范围比较才能用上这个索引
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_analyses_conv_ts "
            "ON analyses (conversation, received_at)")
        # 排队任务表：端上报「排队中 / 运行中 / 失败」的任务状态，控制台按设备查。
        # 主键用 (device_id, task_id) 而不是自增 id —— 同一条任务会被反复上报
        # （排队 -> 运行 -> 结束），靠主键做幂等 upsert，避免同一任务在表里出现多行。
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_tasks (
                device_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                queued_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (device_id, task_id)
            )
            """)
        # 控制台与 /queue 都按 device_id 过滤再按 updated_at 倒序，
        # 设备维度的索引让过滤走索引而不是全表扫 + 排序。
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_queue_device "
            "ON queue_tasks (device_id)")
        conn.commit()
    finally:
        conn.close()

def _append_jsonl(items):
    """把入库成功的原始条目追加到 jsonl 存档。

    - 先建目录（数据目录可能被清过）
    - 超过阈值就轮转一份（messages.jsonl.1），避免单文件无限膨胀
    """
    import app as app_module
    os.makedirs(app_module.DATA_DIR, exist_ok=True)
    try:
        if (os.path.exists(app_module.JSONL_PATH)
                and os.path.getsize(app_module.JSONL_PATH) > app_module.JSONL_MAX_BYTES):
            os.replace(app_module.JSONL_PATH, app_module.JSONL_PATH + ".1")
    except OSError:
        pass
    with open(app_module.JSONL_PATH, "a", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()

def _advisor_jsonl_path():
    """主存储路径（跟着 DATA_DIR 走，测试里改 DATA_DIR 就能一起隔离）。"""
    import app as app_module
    return os.path.join(app_module.DATA_DIR, "advisor_reports.jsonl")

class AdvisorArchiveError(RuntimeError):
    """顾问存档损坏。

    存档损坏时**拒绝写入**（不是静默当空列表后整份覆盖），
    历史报告原样留在盘上，等人工修复。
    """

def _write_advisor_jsonl(reports):
    """整份重写 JSONL 主存储（迁移 / 裁剪时用）。"""
    import app as app_module
    path = app_module._advisor_jsonl_path()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for item in reports:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()
    os.replace(tmp, path)

def _append_advisor_reports(items):
    """追加写入 JSONL 主存储：O(1) 追加，不再整份重写。"""
    import app as app_module
    if not items:
        return
    path = app_module._advisor_jsonl_path()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    # 容量上限：文件超阈值就轮转一份（只保留最近一份历史），避免无界增长
    try:
        if (os.path.exists(path)
                and os.path.getsize(path) > app_module.ADVISOR_MAX_BYTES):
            os.replace(path, path + ".1")
    except OSError:
        pass
    with open(path, "a", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
        f.flush()

def _trim_advisor_jsonl(keep=None):
    """有界裁剪：JSONL 条数超上限时只保留最近 keep 条。"""
    import app as app_module
    if keep is None:
        keep = app_module.ADVISOR_MAX_REPORTS
    path = app_module._advisor_jsonl_path()
    try:
        with open(path, encoding="utf-8") as f:
            lines = [ln for ln in f if ln.strip()]
    except OSError:
        return
    if len(lines) <= keep:
        return
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.writelines(lines[-keep:])
    os.replace(tmp, path)

def _load_advisor_reports():
    """读取顾问报告存档（主存储 JSONL；兼容旧的 JSON 数组并自动迁移）。

    存档损坏时抛 AdvisorArchiveError，**拒绝写入**：
    原来的实现在这里直接返回空列表，下一条上报就会拿「空列表 + 新条目」
    整份覆盖写回去 -> 历史报告被无声抹掉。现在损坏的文件原样留在盘上，
    写入路径直接回 5xx，等人工修复（修好或删掉损坏文件后自动恢复）。
    """
    import app as app_module
    jsonl_path = app_module._advisor_jsonl_path()
    if os.path.exists(jsonl_path):
        items = []
        try:
            with open(jsonl_path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    obj = json.loads(line)
                    if isinstance(obj, dict):
                        items.append(obj)
        except (json.JSONDecodeError, OSError, ValueError) as e:
            logger.error("顾问存档损坏，拒绝写入: %s（原因: %s）", jsonl_path, e)
            raise app_module.AdvisorArchiveError(
                f"advisor archive is corrupted: {jsonl_path}") from e
        return items
    # 没有 JSONL：读旧格式（JSON 数组）；读通就迁移成 JSONL
    if not os.path.exists(app_module.ADVISOR_PATH):
        return []
    try:
        with open(app_module.ADVISOR_PATH, encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, list):
            raise ValueError("advisor archive is not a JSON list")
    except (json.JSONDecodeError, OSError, ValueError) as e:
        logger.error("顾问存档损坏，拒绝写入: %s（原因: %s）", app_module.ADVISOR_PATH, e)
        raise app_module.AdvisorArchiveError(
            f"advisor archive is corrupted: {app_module.ADVISOR_PATH}") from e
    records = [r for r in data if isinstance(r, dict)]
    try:
        app_module._write_advisor_jsonl(records)
    except OSError as e:
        logger.error("顾问存档迁移到 JSONL 失败（原因: %s）", e)
    return records

def _write_advisor_snapshot(reports):
    """写向后兼容的 JSON 数组快照（ADVISOR_PATH）。

    只在内容有变化时写；内容没变直接跳过（原来每次上报都无条件整份重写）。
    """
    import app as app_module
    payload = json.dumps(reports, ensure_ascii=False, indent=2)
    try:
        with open(app_module.ADVISOR_PATH, encoding="utf-8") as f:
            if f.read() == payload:
                return
    except OSError:
        pass
    tmp = app_module.ADVISOR_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(payload)
    os.replace(tmp, app_module.ADVISOR_PATH)

def _save_advisor_reports(reports):
    """整份覆盖写顾问存档（JSONL 主存储 + JSON 数组快照）。

    正常上报走 _append_advisor_reports（O(1) 追加）；这个函数留给
    迁移 / 裁剪 / 人工修复的整份重写场景。
    """
    import app as app_module
    records = [r for r in reports if isinstance(r, dict)]
    app_module._write_advisor_jsonl(records)
    app_module._write_advisor_snapshot(records)

def _normalize_advisor_item(item):
    """把一条顾问报告收敛成安全形状。

    返回 (归一化后的 dict, None) 表示接受；(None, 原因) 表示拒绝。

    原来整条 item 原样落盘：case_count / period_days 可能是字符串、null 或
    浮点，控制台 parseInt 后显示 undefined；summary / suggestions 里的文本
    长度不设上限，一份几 MB 的报告就能把存档撑爆。这里统一成
    「数值字段收敛成 int、字符串字段截断、suggestions 只留 dict 且条数有上限」。
    """
    import app as app_module
    if not isinstance(item, dict):
        return None, "item is not an object"
    rid = item.get("report_id")
    if not isinstance(rid, str) or not rid.strip():
        return None, "missing report_id"
    rid = rid.strip()
    if len(rid) > app_module.MAX_NAME_LEN:
        return None, "report_id too long"
    out = {"report_id": rid}

    def _clip(value, limit):
        if value is None:
            return None
        if not isinstance(value, str):
            value = str(value)
        return value[:limit]

    created_at = _clip(item.get("created_at"), app_module.ADVISOR_MAX_CREATED_LEN)
    if created_at is not None:
        out["created_at"] = created_at
    summary = _clip(item.get("summary"), app_module.ADVISOR_MAX_SUMMARY_LEN)
    if summary is not None:
        out["summary"] = summary
    model_used = _clip(item.get("model_used"), app_module.ADVISOR_MAX_MODEL_LEN)
    if model_used is not None:
        out["model_used"] = model_used
    # 数值字段统一成 int（脏值/越界回落到 0），控制台不用再 parseInt 兜底
    out["period_days"] = app_module._clamp_int(
        item.get("period_days"), lo=0, hi=app_module.ADVISOR_MAX_PERIOD_DAYS)
    out["case_count"] = app_module._clamp_int(
        item.get("case_count"), lo=0, hi=app_module.ADVISOR_MAX_CASE_COUNT)
    sug = item.get("suggestions")
    norm_sug = []
    if isinstance(sug, list):
        for s in sug[:app_module.ADVISOR_MAX_SUGGESTIONS]:
            if not isinstance(s, dict):
                continue
            ns = {}
            for key, limit in (("type", app_module.ADVISOR_MAX_TITLE_LEN),
                               ("title", app_module.ADVISOR_MAX_TITLE_LEN),
                               ("detail", app_module.ADVISOR_MAX_DETAIL_LEN)):
                v = _clip(s.get(key), limit)
                if v is not None:
                    ns[key] = v
            norm_sug.append(ns)
    out["suggestions"] = norm_sug
    # pkg 可选透传：客户端带上就存下来，控制台才能对顾问报告按来源过滤
    # （与 /analysis、/messages 同口径）。缺省或非法则不带该键。
    apkg = item.get("pkg")
    if isinstance(apkg, str) and apkg.strip():
        out["pkg"] = apkg.strip()[:app_module.MAX_NAME_LEN]
    return out, None

def _import_extract_module(leg):
    """按文件路径加载提取模块（android_db_extract / pc_db_extract）。

    原来用 __import__("android_db_extract") 裸名导入：这只有在 app.py 所在
    目录恰好是 sys.path[0] 时才成立。用 WSGI 启动器
    （waitress-serve app:app / gunicorn app:app）时 sys.path 里没有 server/
    → ModuleNotFoundError，提取功能整个报废（实测 POST /admin/extract
    → "No module named android_db_extract"）。
    """
    import app as app_module
    path = os.path.join(app_module.BASE_DIR, f"{leg}_db_extract.py")
    if not os.path.exists(path):
        raise ImportError(f"extract module not found: {path}")
    spec = importlib.util.spec_from_file_location(
        f"notifyme_{leg}_db_extract", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load extract module: {path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod

def _run_extract_bg(full, source):
    """后台线程跑提取，结果写进 _extract_status。

    source：android（安卓数据库直读）/ pc（PC 微信 4.x 数据库直读）/
    all（两者都跑）。各路独立执行：一路失败只记该路的 error，
    不拖垮另一路；整体 ok = 至少一路成功。
    """
    import app as app_module
    result = {"source": source}
    legs = []
    if source in ("android", "all"):
        legs.append("android")
    if source in ("pc", "all"):
        legs.append("pc")
    try:
        for leg in legs:
            try:
                mod = app_module._import_extract_module(leg)
                result[leg] = mod.run_extract(full=full)
            except Exception as e:  # 单路失败不拖垮服务与其它路
                result[leg] = {"ok": False, "error": str(e)}
                # 主库被占用是可重试的瞬时故障，单独标出来，
                # 控制台/运维能一眼看出「等一会儿再试」而不是「数据坏了」
                _etext = str(e).lower()
                if (isinstance(e, sqlite3.OperationalError)
                        or "locked" in _etext or "busy" in _etext
                        or "占用" in str(e)):
                    result[leg]["error_kind"] = "busy"
        # 成功判定：必须明确是 True 才算成功（B11）。
        # 原来写成 is not False：返回体里没有 ok 键时会被当成成功
        # （两个提取脚本早期版本都没这个键）-> 状态页显示 ok 但其实没跑成。
        ok = any(isinstance(result[leg], dict) and result[leg].get("ok") is True
                 for leg in legs)
        with app_module._extract_lock:
            app_module._extract_status["last"] = {"ok": ok, **result}
    finally:
        # 无论成功失败都要复位 running：
        # 原来只有走到最后一行才复位，中途抛异常 → running 永久为 True
        # → 之后的 /admin/extract 一直返回 409，提取功能彻底卡死。
        with app_module._extract_lock:
            app_module._extract_status["running"] = False

def _probe_main_db_writable(timeout=0.5):
    """探测主库现在能不能立刻拿到写锁。

    返回 (state, detail)，state 取值：ok / busy / error。
    提取任务与 /weixin、/analysis 都写同一个主库；被占用时（另一个提取任务
    正在批量提交、或有长事务没提交）直接回 503 + Retry-After，
    比让请求挂着等 30 秒 busy_timeout 再失败强。
    """
    import app as app_module
    try:
        conn = sqlite3.connect(app_module.DB_PATH, timeout=timeout, isolation_level=None)
    except sqlite3.Error as e:
        return "error", str(e)
    try:
        conn.execute("PRAGMA busy_timeout=%d" % int(timeout * 1000))
        conn.execute("BEGIN IMMEDIATE")
        conn.execute("ROLLBACK")
        return "ok", ""
    except sqlite3.OperationalError as e:
        text = str(e).lower()
        if "locked" in text or "busy" in text:
            return "busy", str(e)
        return "error", str(e)
    finally:
        try:
            conn.close()
        except sqlite3.Error:
            pass

# ------------------------------------------------------------------
# 排队任务（端上排队 / 运行中 / 失败的任务状态）
# ------------------------------------------------------------------
# 端把「排队中 / 运行中 / 失败」的任务状态上报到服务端，PWA 控制台据此
# 显示端上还挂着什么任务。任务状态是易变数据：同一条任务会被反复上报
# （排队 -> 运行 -> 结束），所以按 (device_id, task_id) 做幂等 upsert，
# 而不是像消息那样追加入库。

# 超过这个时长没有更新就认为这条任务「过期」：端上可能已经把它从队列里
# 摘掉、或者进程被杀，留着只会让控制台显示一个永远在跑的任务。
QUEUE_TTL_MS = 30 * 60 * 1000
# 行保留时长：任务状态只是「当下」的快照，7 天前的行没有参考价值，
# 写入时顺带清掉，避免表无限膨胀。
QUEUE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000
# 单次查询返回条数上限（服务端也夹紧，防止一个 limit=999999 打爆内存）
QUEUE_MAX_LIMIT = 500
# 控制台默认一屏要看多少条
QUEUE_DEFAULT_LIMIT = 200
# 字段长度上限：端上报的是「一句话标题 + 一句详情」，超长视为脏数据
QUEUE_DEVICE_ID_MAX_LEN = 64
QUEUE_TASK_ID_MAX_LEN = 128
QUEUE_TITLE_MAX_LEN = 256
QUEUE_DETAIL_MAX_LEN = 512
# 枚举值：kind 决定控制台怎么分组，state 决定徽标颜色。
# 只认这几个值：端升级后新增的枚举一律拒收，不让未知值进库污染 summary 计数。
QUEUE_KINDS = ("analysis", "sync", "model", "other")
# 单个状态先起名、再组元组：「哪些状态算在跑」直接引用常量，
# 别处不再写一遍状态字符串，枚举改动只改这一处。
QUEUE_STATE_QUEUED = "queued"
QUEUE_STATE_RUNNING = "running"
QUEUE_STATE_FAILED = "failed"
QUEUE_STATE_DONE = "done"
QUEUE_STATES = (QUEUE_STATE_QUEUED, QUEUE_STATE_RUNNING,
                QUEUE_STATE_FAILED, QUEUE_STATE_DONE)
# 「在跑」= 排队中 + 运行中（设备维度汇总用）。
QUEUE_ACTIVE_STATES = (QUEUE_STATE_QUEUED, QUEUE_STATE_RUNNING)


def _normalize_queue_task(item, max_ts_ms):
    """校验并归一一条排队任务。

    返回 (归一化后的 dict, None) 表示接受；返回 (None, 原因) 表示拒绝。

    逐条校验、逐条拒收：端上报的是一整批排队任务，其中一条脏数据
    （字段缺失 / 超长 / 枚举越界）不能连累整批 —— 端会把被拒的条目一直重试，
    一批卡住就等于整条队列卡住。
    """
    if not isinstance(item, dict):
        return None, "task is not an object"
    device_id = item.get("device_id")
    if not isinstance(device_id, str) or not device_id.strip():
        return None, "missing device_id"
    device_id = device_id.strip()
    if len(device_id) > QUEUE_DEVICE_ID_MAX_LEN:
        return None, "device_id too long"
    task_id = item.get("task_id")
    if not isinstance(task_id, str) or not task_id.strip():
        return None, "missing task_id"
    task_id = task_id.strip()
    if len(task_id) > QUEUE_TASK_ID_MAX_LEN:
        return None, "task_id too long"
    kind = item.get("kind")
    if not isinstance(kind, str) or kind not in QUEUE_KINDS:
        return None, "kind must be one of: " + ", ".join(QUEUE_KINDS)
    state = item.get("state")
    if not isinstance(state, str) or state not in QUEUE_STATES:
        return None, "state must be one of: " + ", ".join(QUEUE_STATES)
    title = item.get("title", "")
    if title is None:
        title = ""
    if not isinstance(title, str):
        return None, "title must be a string"
    if len(title) > QUEUE_TITLE_MAX_LEN:
        return None, "title too long"
    detail = item.get("detail", "")
    if detail is None:
        detail = ""
    if not isinstance(detail, str):
        return None, "detail must be a string"
    if len(detail) > QUEUE_DETAIL_MAX_LEN:
        return None, "detail too long"
    queued_at = item.get("queued_at", 0)
    if queued_at is None:
        queued_at = 0
    if isinstance(queued_at, bool):
        return None, "queued_at must be an integer"
    if isinstance(queued_at, float):
        if not queued_at.is_integer():
            return None, "queued_at must be an integer"
        queued_at = int(queued_at)
    if not isinstance(queued_at, int):
        return None, "queued_at must be an integer"
    if queued_at < 0 or queued_at > int(max_ts_ms):
        return None, "queued_at out of range"
    return {
        "device_id": device_id,
        "task_id": task_id,
        "kind": kind,
        "title": title,
        "state": state,
        "detail": detail,
        "queued_at": queued_at,
    }, None


def upsert_queue_tasks(items, now_ms):
    """写入一批排队任务（按 (device_id, task_id) 幂等 upsert）。

    返回 (写入条数, 拒收清单)。拒收清单元素形如 {"index": i, "reason": "..."}，
    index 是 items 里的原始下标 —— 端据此只重试被拒的那几条，而不是整批重发。
    """
    import app as app_module
    if not isinstance(items, list):
        return 0, []
    now_ms = int(now_ms)
    rows = []
    rejected = []
    for idx, item in enumerate(items):
        normalized, why = _normalize_queue_task(item, app_module.MAX_TS_MS)
        if normalized is None:
            rejected.append({"index": idx, "reason": why})
            continue
        rows.append(normalized)
    conn = app_module._connect()
    try:
        with conn:
            # 顺带清理过期行：任务状态只是当下快照，7 天前的行留着只是垃圾
            cutoff = now_ms - QUEUE_RETENTION_MS
            conn.execute("DELETE FROM queue_tasks WHERE updated_at < ?", (cutoff,))
            for row in rows:
                conn.execute(
                    """
                    INSERT INTO queue_tasks
                        (device_id, task_id, kind, title, state, detail,
                         queued_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id, task_id) DO UPDATE SET
                        kind = excluded.kind,
                        title = excluded.title,
                        state = excluded.state,
                        detail = excluded.detail,
                        queued_at = excluded.queued_at,
                        updated_at = excluded.updated_at
                    """,
                    (row["device_id"], row["task_id"], row["kind"], row["title"],
                     row["state"], row["detail"], row["queued_at"], now_ms))
    finally:
        conn.close()
    return len(rows), rejected


def query_queue_tasks(device_id=None, state=None, limit=QUEUE_DEFAULT_LIMIT):
    """查询排队任务（按 updated_at 倒序，最新的在前）。

    device_id / state 为空则不过滤；state 非法直接返回空列表 ——
    既不能 500，也不能「忽略过滤条件把全表都吐出来」。
    limit 上限 QUEUE_MAX_LIMIT（服务端也夹紧，防毒丸参数）。

    每条额外带一个 stale 布尔：updated_at 距今超过 QUEUE_TTL_MS 即为 True，
    表示端上大概率已经不再跑这条任务了。
    """
    import app as app_module
    device_id = (device_id or "").strip()
    state = (state or "").strip()
    if state and state not in QUEUE_STATES:
        return []
    try:
        limit = int(limit)
    except (TypeError, ValueError):
        limit = QUEUE_DEFAULT_LIMIT
    if limit <= 0:
        limit = QUEUE_DEFAULT_LIMIT
    limit = min(limit, QUEUE_MAX_LIMIT)
    sql = ("SELECT device_id, task_id, kind, title, state, detail, "
           "queued_at, updated_at FROM queue_tasks")
    params = []
    conditions = []
    if device_id:
        conditions.append("device_id = ?")
        params.append(device_id)
    if state:
        conditions.append("state = ?")
        params.append(state)
    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    sql += " ORDER BY updated_at DESC LIMIT ?"
    params.append(limit)
    conn = app_module._connect()
    try:
        rows = conn.execute(sql, params).fetchall()
    finally:
        conn.close()
    now_ms = int(time.time() * 1000)
    tasks = []
    for row in rows:
        # 列是 INTEGER 亲和，但写不进整数时 SQLite 会原样存 TEXT：
        # 裸 int("zzz") 会把整页 /queue 打成 500（与 /stats 同族，见 stats_api）。
        updated_at = app_module._safe_int(row[7], default=0, lo=0)
        tasks.append({
            "device_id": row[0],
            "task_id": row[1],
            "kind": row[2],
            "title": row[3] or "",
            "state": row[4],
            "detail": row[5] or "",
            "queued_at": app_module._safe_int(row[6], default=0, lo=0),
            "updated_at": updated_at,
            "stale": (now_ms - updated_at) > QUEUE_TTL_MS,
        })
    return tasks


def queue_summary(device_id=None, state=None):
    """排队任务的计数（全库口径，不受分页 limit 截断）。

    GET /queue 的 tasks 只回一页（默认 200 / 上限 500），但控制台要回答
    「一共有多少条任务、多少还在跑」—— 按页统计会让任务超一页时把总数
    显示成 200，而 /stats 与 devices 给的是全库真值，同一时刻两个 Tab
    的数字对不上（真机 260 条任务时列表页统计 200、统计页 260）。

    过滤口径与 query_queue_tasks 一致（device_id / state），但**不看 limit**：
    - total：过滤后命中的全部行数（含已过期）
    - stale：其中 updated_at 距今超过 QUEUE_TTL_MS 的行数（与状态计数互斥，
      与 /stats 的 queue 段同口径）
    - queued / running / failed / done：未过期行按 state 计数
    state 非法时返回全 0，与 query_queue_tasks 的「空列表」口径对齐。

    devices 不在这里算：设备维度恒为全库（不受 device_id / state 过滤），
    由调用方填 summary["devices"] = len(queue_devices())。
    """
    import app as app_module
    device_id = (device_id or "").strip()
    state = (state or "").strip()
    summary = {"queued": 0, "running": 0, "failed": 0, "done": 0,
               "stale": 0, "total": 0}
    if state and state not in QUEUE_STATES:
        return summary
    sql = "SELECT state, updated_at FROM queue_tasks"
    params = []
    conditions = []
    if device_id:
        conditions.append("device_id = ?")
        params.append(device_id)
    if state:
        conditions.append("state = ?")
        params.append(state)
    if conditions:
        sql += " WHERE " + " AND ".join(conditions)
    conn = app_module._connect()
    try:
        rows = conn.execute(sql, params).fetchall()
    except sqlite3.OperationalError as e:
        # 表还没建（老库没跑过 init_db）：当「没有任务」，不抛异常
        if "no such table" in str(e).lower():
            return summary
        raise
    finally:
        conn.close()
    now_ms = int(time.time() * 1000)
    summary["total"] = len(rows)
    for row in rows:
        # 脏行（updated_at 被写成文本）不能拖垮 summary：认不出按 0
        updated_at = app_module._safe_int(row[1], default=0, lo=0)
        if (now_ms - updated_at) > QUEUE_TTL_MS:
            summary["stale"] += 1
            continue
        row_state = row[0]
        if row_state in QUEUE_STATES:
            summary[row_state] += 1
    return summary

def queue_devices():
    """统计库里出现过哪几台设备（回答「有哪几台设备」）。

    返回：[{"device_id": str, "total": int, "active": int}, ...]
    - total ：该设备在队列表里的总条数（含各种状态）
    - active：其中 state 为排队中 / 运行中的条数（还在跑的）
    排序：active 倒序 -> total 倒序 -> device_id 升序（结果稳定可复现）。

    一条 SQL 搞定分组聚合：按 device_id 分组，COUNT(*) 给总数，
    SUM(CASE WHEN state IN (...) THEN 1 ELSE 0 END) 给在跑数。
    「在跑」判定引用模块常量 QUEUE_ACTIVE_STATES（queued / running），
    不另写一份状态字面量。
    """
    import app as app_module
    placeholders = ",".join("?" * len(QUEUE_ACTIVE_STATES))
    sql = (
        "SELECT device_id, COUNT(*) AS total, "
        "SUM(CASE WHEN state IN (%s) THEN 1 ELSE 0 END) AS active "
        "FROM queue_tasks GROUP BY device_id "
        "ORDER BY active DESC, total DESC, device_id ASC" % placeholders)
    params = list(QUEUE_ACTIVE_STATES)
    conn = app_module._connect()
    try:
        rows = conn.execute(sql, params).fetchall()
    except sqlite3.OperationalError as e:
        # 表还没建（老库没跑过 init_db）：当「没有设备」，不抛异常
        if "no such table" in str(e).lower():
            return []
        raise
    finally:
        conn.close()
    devices = []
    for row in rows:
        devices.append({
            "device_id": row[0],
            "total": app_module._safe_int(row[1], default=0, lo=0),
            "active": app_module._safe_int(row[2], default=0, lo=0),
        })
    return devices


