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

