// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 非有限数（NaN / ±Inf）会让 org.json 的 put 直接抛
 * "JSON does not allow non-finite numbers"——落库与上报双双炸掉。
 *
 * 根因（上一轮报告外发现第 1 条）：org.json 的**单参** optDouble(name) 在字段缺失时
 * 返回 **NaN**（默认缺省值就是 NaN），所以「缺 s1 块」的旧记录读进来就带着 NaN，
 * 再 toJson 就抛异常。现在双管齐下：读入用 0.0 缺省，写出前再过一道有限性闸。
 */
private fun finiteOrZero(value: Double): Double =
    if (value.isNaN() || value.isInfinite()) 0.0 else value

/**
 * 一条会话级分析结果（case 记录）。
 *
 * 分析单位从「单条消息」升级为「会话窗口」后的落库结构：
 * {caseId, conversation, windowEnd, messageCount,
 *  s1:{need_action:{value,prob}, importance, due_window, topic, confidence},
 *  escalated, s2:{summary, due_time, suggested_action, tasks[]},
 *  analyzedAt, protocol, raw_json}
 *
 * @param caseId        窗口指纹（sha1(pkg|conversation|windowEnd) 前 16 位），去重键
 * @param conversation  会话名
 * @param pkg           应用包名（schema v2，M2 引入）；缺省＝微信，老记录读进来自动归微信
 * @param windowEnd     窗口末条消息时间戳（毫秒）
 * @param messageCount  窗口内消息条数（≤10）
 * @param analyzedAt    分析完成时间（毫秒）
 * @param protocol      本轮 S1 所用协议（laya / openai）
 * @param rawJson       S1（及 S2）后端返回的原始 JSON 文本（防御式保留）
 */
data class AnalysisCaseRecord(
    val caseId: String,
    val conversation: String,
    val windowEnd: Long,
    val messageCount: Int,
    val analyzedAt: Long,
    val protocol: String,
    val rawJson: String,
    val pkg: String = AppSourceRegistry.PKG_WECHAT,
    // ---- S1 判定 ----
    val s1NeedAction: Boolean,
    val s1NeedActionProb: Double,
    val s1Importance: Double,
    val s1DueWindow: String,
    val s1Topic: String,
    val s1Confidence: Double,
    // ---- S2 深分析（escalated=false 时字段为空） ----
    val escalated: Boolean,
    val s2Summary: String,
    val s2DueTime: String,
    val s2SuggestedAction: String,
    val s2Tasks: List<Task>,
    // ---- fork 层多级微决策轨迹（agent-tree） ----
    /** 每个分叉的判定记录（时间序：prefilter -> s1 -> escalate） */
    val forks: List<ForkRecord> = emptyList(),
    /** fork 1 预筛就地结案标记：true 时未调 laya/S2，s1/s2 字段均为兜底值 */
    val filtered: Boolean = false,
    // ---- M3 脱敏证据：只存规则 id / 命中数，不存任何原文片段 ----
    /** true = 该 case 出设备前经过脱敏（云端收到的是占位符文本） */
    val redacted: Boolean = false,
    /** 实际命中的规则 id（规则清单顺序去重） */
    val redactionRules: List<String> = emptyList(),
    /** 窗口内总命中替换数（所有消息、所有规则求和） */
    val redactionHits: Int = 0,
    // ---- M12 待办手动覆盖：只影响显示与日历文本，不改动原始分析结论 ----
    /** 用户编辑的待办内容；非空时显示/日历优先用它（取代 S2 概括与 S1 话题） */
    val todoText: String = "",
    /** 用户编辑的截止时间文本；非空时日历解析优先用它 */
    val todoDue: String = "",
    // ---- 第四轮：被拒计数与隔离（分析通道防饥饿） ----
    /** 累计被服务端拒绝次数（207 部分被拒）；0 = 从未被拒 */
    val rejectedCount: Int = 0,
    /** 首次被拒时间（毫秒）；0 = 从未被拒；后续被拒不续期 */
    val firstRejectedAt: Long = 0L,
    /** true = 已隔离：记录仍留在文件里可人工查看，但不再上报、不再阻塞水位 */
    val quarantined: Boolean = false
) {
    /** 会话复合键（M2）：分析结果归属哪个 App 的哪个会话。 */
    val convKey: ConvKey get() = ConvKey(pkg, conversation)

    /** 展示与日历用待办文本：手动覆盖 > S2 概括 > S1 话题。 */
    fun displayTodo(): String = todoText.ifEmpty { s2Summary.ifEmpty { s1Topic } }

    /** 展示与日历解析用截止时间文本：手动覆盖 > S2 截止时间 > S1 截止窗口。 */
    fun displayDue(): String = todoDue.ifEmpty { s2DueTime.ifEmpty { s1DueWindow } }

    /**
     * 一个分叉的判定记录。
     * prob 统一语义：该分叉判为 split/正向的概率——
     * prefilter：值得进入 S1 的概率；s1：need_action 概率；
     * escalate：升级依据综合值（max(needActionProb, importance/9)）。
     * verdict：sharp=就地结案（不再进入下一阶段）；split=继续向下走。
     */
    data class ForkRecord(
        val fork: String,    // prefilter / s1 / escalate
        val prob: Double,
        val verdict: String, // sharp / split
        val note: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fork", fork)
            put("prob", finiteOrZero(prob))
            put("verdict", verdict)
            put("note", note)
        }

        companion object {
            fun fromJson(obj: JSONObject): ForkRecord = ForkRecord(
                fork = obj.optString("fork"),
                // 缺省 0.0 而不是 NaN：NaN 写回 JSON 会抛 JSONException
                prob = finiteOrZero(obj.optDouble("prob", 0.0)),
                verdict = obj.optString("verdict"),
                note = obj.optString("note")
            )
        }
    }

    /** S2 任务条目：谁 / 做什么 / 何时 */
    data class Task(val who: String, val what: String, val `when`: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("who", who)
            put("what", what)
            put("when", `when`)
        }

        companion object {
            fun fromJson(obj: JSONObject): Task = Task(
                who = obj.optString("who"),
                what = obj.optString("what"),
                `when` = obj.optString("when")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("case_id", caseId)
        put("conversation", conversation)
        put("window_end", windowEnd)
        put("message_count", messageCount)
        put("analyzed_at", analyzedAt)
        put("protocol", protocol)
        put("raw_json", rawJson)
        // pkg 始终写出，口径与 ChatMessage.toJson 一致（老客户端忽略未知字段即可）
        put("pkg", pkg)
        put("s1", JSONObject().apply {
            put("need_action", JSONObject().apply {
                put("value", s1NeedAction)
                put("prob", finiteOrZero(s1NeedActionProb))
            })
            put("importance", finiteOrZero(s1Importance))
            put("due_window", s1DueWindow)
            put("topic", s1Topic)
            put("confidence", finiteOrZero(s1Confidence))
        })
        put("escalated", escalated)
        if (escalated) {
            put("s2", JSONObject().apply {
                put("summary", s2Summary)
                put("due_time", s2DueTime)
                put("suggested_action", s2SuggestedAction)
                put("tasks", JSONArray().apply {
                    s2Tasks.forEach { put(it.toJson()) }
                })
            })
        }
        // fork 层轨迹：snake_case 键名，同步到服务端 /analysis 时随记录上报
        put("forks", JSONArray().apply { forks.forEach { put(it.toJson()) } })
        put("filtered", filtered)
        // 脱敏证据仅在发生脱敏时写出，保持其余记录紧凑；随 /analysis 上报后
        // 服务端可统计脱敏覆盖率，却拿不到被替换的内容
        if (redacted) {
            put("redacted", true)
            put("redaction_rules", JSONArray(redactionRules))
            put("redaction_hits", redactionHits)
        }
        // M12 手动覆盖字段仅在非空时写出，老记录格式不变（缺键即未编辑）
        if (todoText.isNotEmpty()) put("todo_text", todoText)
        if (todoDue.isNotEmpty()) put("todo_due", todoDue)
        // 第四轮：被拒计数与隔离（仅非默认值时写出，老记录格式完全不变）
        if (rejectedCount > 0) put("rejected_count", rejectedCount)
        if (firstRejectedAt > 0L) put("first_rejected_at", firstRejectedAt)
        if (quarantined) put("quarantined", true)
    }

    companion object {
        /**
         * 防御式解析：旧版消息级记录（msg_ref 格式）没有 case_id 字段，
         * 解析返回 null 由调用方跳过——旧数据不崩、不参与新逻辑。
         */
        fun fromJson(obj: JSONObject): AnalysisCaseRecord? {
            val caseId = obj.optString("case_id")
            if (caseId.isEmpty()) return null // 旧格式记录，防御式跳过
            val s1 = obj.optJSONObject("s1") ?: JSONObject()
            val needAction = s1.optJSONObject("need_action") ?: JSONObject()
            val s2 = obj.optJSONObject("s2")
            val tasks = mutableListOf<Task>()
            s2?.optJSONArray("tasks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { tasks.add(Task.fromJson(it)) }
                }
            }
            val forks = mutableListOf<ForkRecord>()
            obj.optJSONArray("forks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { forks.add(ForkRecord.fromJson(it)) }
                }
            }
            return AnalysisCaseRecord(
                caseId = caseId,
                conversation = obj.optString("conversation"),
                windowEnd = obj.optLong("window_end"),
                messageCount = obj.optInt("message_count"),
                analyzedAt = obj.optLong("analyzed_at"),
                protocol = obj.optString("protocol"),
                rawJson = obj.optString("raw_json"),
                pkg = obj.optString("pkg").ifEmpty { AppSourceRegistry.PKG_WECHAT },
                s1NeedAction = needAction.optBoolean("value"),
                // 缺省一律 0.0：org.json 单参 optDouble 缺省是 NaN，会在 toJson 时炸掉
                s1NeedActionProb = finiteOrZero(needAction.optDouble("prob", 0.0)),
                s1Importance = finiteOrZero(s1.optDouble("importance", 0.0)),
                s1DueWindow = s1.optString("due_window"),
                s1Topic = s1.optString("topic"),
                s1Confidence = finiteOrZero(s1.optDouble("confidence", 0.0)),
                escalated = obj.optBoolean("escalated"),
                s2Summary = s2?.optString("summary").orEmpty(),
                s2DueTime = s2?.optString("due_time").orEmpty(),
                s2SuggestedAction = s2?.optString("suggested_action").orEmpty(),
                s2Tasks = tasks,
                forks = forks,
                filtered = obj.optBoolean("filtered"),
                redacted = obj.optBoolean("redacted"),
                redactionRules = obj.optJSONArray("redaction_rules")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            arr.optString(it).ifEmpty { null }
                        }
                    }.orEmpty(),
                redactionHits = obj.optInt("redaction_hits"),
                todoText = obj.optString("todo_text"),
                todoDue = obj.optString("todo_due"),
                // 老记录没有这三个键：缺省 0 / 0 / false（读写兼容）
                rejectedCount = obj.optInt("rejected_count", 0),
                firstRejectedAt = obj.optLong("first_rejected_at", 0L),
                quarantined = obj.optBoolean("quarantined", false)
            )
        }
    }
}

/**
 * 分析结果存储：JSON Lines 追加写入 app 私有目录 files/analysis.jsonl。
 *
 * case 级去重：已分析 caseId 集合直接从本文件恢复（全量读入内存），
 * 窗口内容没变（无新消息 -> windowEnd 不变 -> caseId 相同）就不重分析——
 * 即使进程重启、Worker 重试也一样。
 *
 * 旧版消息级记录（msg_ref 格式）读取时防御式跳过，不崩溃也不参与统计。
 */
object AnalysisStore {

    private const val FILE_NAME = "analysis.jsonl"

    /** 分析结果上报游标（独立 prefs，避免与 sync_config 耦合） */
    private const val SYNC_PREFS_NAME = "analysis_sync"
    private const val KEY_LAST_SYNCED_AT = "last_synced_analyzed_at"

    // ---- 第四轮：增量扫描游标 + 隔离索引 ----
    /** 文件被整体重写的次数（generation）：字节游标失效的判据 */
    private const val KEY_FILE_GENERATION = "file_generation"
    /** 扫描游标：下一轮的起始字节偏移 */
    private const val KEY_SCAN_OFFSET = "scan_offset"
    /** 扫描游标所属的文件 generation（与 KEY_FILE_GENERATION 不一致即游标失效） */
    private const val KEY_SCAN_GENERATION = "scan_generation"
    /** 上次「从头全量扫」的时间（毫秒）：到期后环形重扫一次 */
    private const val KEY_SCAN_LAST_FULL_PASS_AT = "scan_last_full_pass_at"
    /** 隔离索引：case_id -> 首次被拒时间（毫秒）；只记已隔离的记录 */
    private const val KEY_QUARANTINE_INDEX = "quarantine_index"
    /** 上次上报时看到的隐私模式；模式一变就重置水位与扫描偏移 */
    private const val KEY_PRIVACY_MODE = "last_privacy_mode"

    private fun syncPrefs(context: Context) = context.applicationContext
        .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)

    private fun storeFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * 文件被整体重写后调用：字节游标失效（generation 变了下一轮就从头扫）。
     */
    private fun bumpGeneration(context: Context) {
        val prefs = syncPrefs(context)
        val next = prefs.getInt(KEY_FILE_GENERATION, 0) + 1
        prefs.edit().putInt(KEY_FILE_GENERATION, next).apply()
    }

    /** 读隔离索引（case_id -> 首次被拒时间）；损坏时当作空，不打断上报。 */
    private fun loadQuarantineIndex(context: Context): MutableMap<String, Long> {
        val raw = syncPrefs(context).getString(KEY_QUARANTINE_INDEX, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, Long>()
            obj.keys().forEach { key -> out[key] = obj.optLong(key) }
            out
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** 写隔离索引。 */
    private fun saveQuarantineIndex(context: Context, index: Map<String, Long>) {
        val obj = JSONObject()
        index.forEach { (caseId, firstAt) -> obj.put(caseId, firstAt) }
        syncPrefs(context).edit().putString(KEY_QUARANTINE_INDEX, obj.toString()).apply()
    }

    /** 追加一条 case 分析结果。 */
    @Synchronized
    fun append(context: Context, record: AnalysisCaseRecord) {
        storeFile(context).appendText(record.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /** 读取全部已分析 caseId（从文件恢复去重集合）。 */
    @Synchronized
    fun loadCaseIds(context: Context): Set<String> {
        val file = storeFile(context)
        if (!file.exists()) return emptySet()

        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    JSONObject(line).optString("case_id").ifEmpty { null }
                } catch (e: Exception) {
                    null // 单行损坏不影响整体读取
                }
            }
            .toSet()
    }

    /**
     * 读取最近 [limit] 条 case 记录，按分析时间倒序（最新在前）。
     * 旧格式记录（无 case_id）与损坏行防御式跳过。
     *
     * 只从文件尾部读 [limit] 行（append 语义下尾部≈最近），不再整文件载入——
     * 与 MessageStore 同口径，避免分析历史增长后每次主页刷新/搜索都 O(文件)。
     */
    @Synchronized
    fun readRecent(context: Context, limit: Int = 100): List<AnalysisCaseRecord> =
        JsonlStore(storeFile(context)).readTailLines(limit.coerceAtLeast(1))
            .asSequence()
            .mapNotNull { line ->
                try {
                    AnalysisCaseRecord.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null
                }
            }
            .toList()
            .sortedByDescending { it.analyzedAt }
            .take(limit)

    /**
     * 删除分析时间早于 [cutoffMs] 的记录（保留策略，与 messages 同 7 天口径）。
     * 损坏行与 analyzed_at≤0 的行保守保留；消息侧同窗口早已清理，旧 case 不会被
     * 重新分析，删除不影响 caseId 去重成本。返回删除条数。
     */
    @Synchronized
    fun pruneOlderThan(context: Context, cutoffMs: Long): Int {
        val removed = JsonlStore(storeFile(context)).rewriteRaw { line ->
            AnalysisRetentionCore.shouldKeep(line, cutoffMs)
        }
        // 文件被重写 -> 字节游标失效
        if (removed > 0) bumpGeneration(context)
        return removed
    }

    /** [ConvKey] 口径的最新记录：M2 起同名会话在不同 App 里分别给角标。 */
    fun latestByConvKey(context: Context): Map<ConvKey, AnalysisCaseRecord> =
        readRecent(context, 500)
            .groupBy { it.convKey }
            .mapValues { (_, list) -> list.maxByOrNull { it.windowEnd }!! }

    /** 删除一条 case 记录（闪电标记阅读后清除），返回是否实际删除。 */
    @Synchronized
    fun deleteCase(context: Context, caseId: String): Boolean =
        rewrite(context) { it.caseId != caseId } > 0

    /**
     * 按 caseId 原地更新一条记录（M12 待办编辑）。
     *
     * 只重写第一条命中的行，其余行原样保留（含无法解析的损坏行）；
     * 未命中不写盘，避免无谓 IO 和重写窗口。与 [append] 同为对象锁，
     * 与 Worker 并发追加互斥安全。返回是否命中并更新。
     */
    @Synchronized
    fun updateRecord(
        context: Context,
        caseId: String,
        patch: (AnalysisCaseRecord) -> AnalysisCaseRecord
    ): Boolean {
        val file = storeFile(context)
        if (!file.exists()) return false
        var patched = false
        val out = mutableListOf<String>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            if (!patched) {
                val record = try {
                    AnalysisCaseRecord.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null
                }
                if (record != null && record.caseId == caseId) {
                    out += patch(record).toJson().toString()
                    patched = true
                    continue
                }
            }
            out += line
        }
        if (patched) {
            JsonlStore.atomicWrite(file, out)
            // 文件被重写 -> 字节游标失效
            bumpGeneration(context)
        }
        return patched
    }

    /** 删除某会话（复合键）的全部 case 记录（删除整个会话时联动），返回实际删除条数。 */
    @Synchronized
    fun deleteByConversation(context: Context, key: ConvKey): Int =
        rewrite(context) { it.convKey != key }

    /**
     * 整体读入、按 [keep] 过滤、重写文件；返回丢弃条数。
     * 旧格式/损坏行无法解析，原样保留。
     */
    private fun rewrite(context: Context, keep: (AnalysisCaseRecord) -> Boolean): Int {
        val file = storeFile(context)
        if (!file.exists()) return 0
        val keptLines = mutableListOf<String>()
        var removed = 0
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val record = try {
                AnalysisCaseRecord.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            }
            if (record != null && !keep(record)) {
                removed++
            } else {
                keptLines += line
            }
        }
        if (removed > 0) {
            // 原子重写：writeText 会先截断原文件，重写中途被杀会丢掉整份分析历史
            JsonlStore.atomicWrite(file, keptLines)
            // 文件被重写 -> 字节游标失效
            bumpGeneration(context)
        }
        return removed
    }

    /**
     * 读取尚未同步到服务器的 case 记录（analyzedAt 大于已同步水位），按分析时间正序。
     * 服务端按 case_id 去重，水位线只是减少重复传输的优化，重复上报无害。
     *
     * **增量扫描（第四轮任务 B）**：上一轮为修「只读最近 500 条」改成了每轮
     * 从文件头顺序扫到文件尾，记录一多就是每轮 O(文件大小) 的 IO。现在维护一个
     * **持久化字节游标**（[KEY_SCAN_OFFSET]）：
     *  - 游标指向「首个待上报记录」：已同步前缀被跳过；水位没前进（上报失败）
     *    时下一轮一定还会读到这批记录，不会漏报；
     *  - 已隔离（被拒超限）的记录被跳过，不再上报、不再阻塞水位；
     *  - 扫到文件末尾后按 [AnalysisScanCursor.FULL_PASS_INTERVAL_MS] 环形从头
     *    重扫一次，长期不漏任何记录；
     *  - 文件被重写（generation 变了）-> 旧偏移失效，自动从头开始，异常不外抛。
     *
     * **requireRedacted（第五轮审计）**：CLOUD_REDACT 模式下只放行脱敏态产出的记录。
     * 未脱敏的旧记录被跳过（不返回、不上报），但**不钉住游标** —— 否则队首那条
     * 未脱敏记录会把游标永久钉住，它后面新追加的记录永远读不到、永远不上报。
     * 它们切回 OFF 模式后的补报靠 [syncPrivacyMode] 重置水位 + 扫描偏移来实现。
     *
     * 内存上限不变：单轮最多 PAGE_SIZE * MAX_PAGES = 1000 条进内存。
     */
    @Synchronized
    fun readPending(context: Context, requireRedacted: Boolean): PendingAnalysis {
        val file = storeFile(context)
        if (!file.exists()) return PendingAnalysis(emptyList(), 0, 0)
        val prefs = syncPrefs(context)
        val now = System.currentTimeMillis()

        // 隔离到期复活：解除隔离 + 清零计数 + **下调水位**（否则复活记录
        // analyzedAt <= 水位会被扫描直接跳过，等于「复活」只是个空标记）
        reviveExpired(context, now)

        val lastSynced = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        val generation = prefs.getInt(KEY_FILE_GENERATION, 0)
        val scanGeneration = prefs.getInt(KEY_SCAN_GENERATION, -1)
        val storedOffset = prefs.getLong(KEY_SCAN_OFFSET, 0L)
        val lastFullPassAt = prefs.getLong(KEY_SCAN_LAST_FULL_PASS_AT, 0L)
        // 文件被整体重写过 -> 旧偏移失效，从头开始
        val base = if (generation == scanGeneration) storedOffset else 0L
        // 游标已到文件末尾（上一轮没有待上报记录）-> 到期后环形从头重扫
        val reachedEnd = base >= file.length()
        val start = AnalysisScanCursor.nextStartOffset(
            reachedEnd = reachedEnd,
            offset = base,
            lastFullPassAt = lastFullPassAt,
            nowMs = now
        )
        val result = AnalysisScanCore(file).scan(
            startOffset = start,
            lastSyncedAt = lastSynced,
            maxRecords = AnalysisSyncCore.PAGE_SIZE * AnalysisSyncCore.MAX_PAGES,
            requireRedacted = requireRedacted
        )
        prefs.edit()
            .putLong(KEY_SCAN_OFFSET, result.nextOffset)
            .putInt(KEY_SCAN_GENERATION, generation)
            .putLong(
                KEY_SCAN_LAST_FULL_PASS_AT,
                if (start == 0L) now else lastFullPassAt
            )
            .apply()
        return PendingAnalysis(
            records = result.records.sortedBy { it.analyzedAt },
            skippedUnredacted = result.skippedUnredacted,
            skippedQuarantined = result.skippedQuarantined
        )
    }

    /**
     * 本轮待上报内容（记录 + 跳过统计）。
     *
     * skippedUnredacted：CLOUD_REDACT 模式下被跳过的未脱敏记录条数（留本机不上云）；
     * skippedQuarantined：被拒超限而隔离的记录条数（留本机不再上报）。
     */
    data class PendingAnalysis(
        val records: List<AnalysisCaseRecord>,
        val skippedUnredacted: Int,
        val skippedQuarantined: Int
    )

    /**
     * 隐私模式切换检测：模式一变就**重置水位与扫描偏移**（下一轮全量重扫）。
     *
     * 为什么必须重置：CLOUD_REDACT 期间未脱敏的旧记录被跳过（不上云），但它们
     * 仍留在文件里；用户把模式切回 OFF 后，这些记录必须**重新变成待上报**，
     * 否则它们永远躺在本机（analyzedAt <= 水位，扫描直接跳过）。
     *
     * @return true = 模式发生了变化（本次已重置）
     */
    @Synchronized
    fun syncPrivacyMode(context: Context, mode: String): Boolean {
        val prefs = syncPrefs(context)
        val last = prefs.getString(KEY_PRIVACY_MODE, null)
        if (last == mode) return false
        prefs.edit()
            .putString(KEY_PRIVACY_MODE, mode)
            .putLong(KEY_LAST_SYNCED_AT, 0L)
            .putLong(KEY_SCAN_OFFSET, 0L)
            .putInt(KEY_SCAN_GENERATION, -1)
            .apply()
        return true
    }

    /**
     * 把「已同步水位」推进到 [timestamp]（只前进不后退）。
     * 调用方必须传本次实际上报记录里的最大 analyzedAt，
     * 不能用当前时间——上报期间新落库的记录不能误标为已同步。
     */
    @Synchronized
    fun markSynced(context: Context, timestamp: Long) {
        val prefs = syncPrefs(context)
        val current = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        if (timestamp > current) {
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, timestamp).apply()
        }
    }

    /**
     * 隔离到期复活：解除隔离 + 清零被拒计数 + 清除文件里的 quarantined 标记，
     * 记录重新获得上报机会。返回实际复活的 case_id 集合。
     *
     * 索引里的条目 7 天（[AnalysisRejectCore.REJECT_TTL_MS]）后到期，
     * 过期即「重新给一次机会」：服务端若不再拒绝，case 会重新被上报。
     */
    @Synchronized
    fun reviveExpired(context: Context, nowMs: Long): Set<String> {
        val index = loadQuarantineIndex(context)
        if (index.isEmpty()) return emptySet()
        val expired = index.filterValues { AnalysisRejectCore.isExpired(it, nowMs) }.keys.toSet()
        if (expired.isEmpty()) return emptySet()
        val file = storeFile(context)
        val revived = if (file.exists()) AnalysisRejectFile(file).revive(expired) else emptySet()
        // 文件里已经没有的 case（被用户删了）也一并从索引摘掉
        index.keys.removeAll(expired)
        saveQuarantineIndex(context, index)
        if (revived.isNotEmpty()) {
            // 文件被重写 -> 字节游标失效
            bumpGeneration(context)
            // 水位必须下调到复活记录之前：水位只前进不后退，不下调的话复活记录
            // analyzedAt <= 水位，扫描会直接跳过 —— 「复活」等于没复活。
            val earliest = minAnalyzedAtOf(context, revived)
            if (earliest > 0L) {
                lowerSyncedWatermark(context, earliest - 1)
                // 扫描偏移也回到文件头：复活记录可能落在游标之前
                syncPrefs(context).edit().putLong(KEY_SCAN_OFFSET, 0L).apply()
            }
        }
        return revived
    }

    /**
     * 下调已同步水位（只用于隔离复活：让复活记录重新变成待上报）。
     * 常规路径的水位仍然只前进不后退。
     */
    @Synchronized
    private fun lowerSyncedWatermark(context: Context, timestamp: Long) {
        val prefs = syncPrefs(context)
        val current = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        if (timestamp < current) {
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, timestamp).apply()
        }
    }

    /** 取指定 case_id 集合里最小的 analyzedAt（没有则 0）。 */
    private fun minAnalyzedAtOf(context: Context, caseIds: Set<String>): Long {
        val file = storeFile(context)
        if (!file.exists() || caseIds.isEmpty()) return 0L
        var min = Long.MAX_VALUE
        try {
            file.forEachLine { line ->
                if (line.isBlank() || min == 0L) return@forEachLine
                try {
                    val record = AnalysisCaseRecord.fromJson(JSONObject(line)) ?: return@forEachLine
                    if (record.caseId in caseIds && record.analyzedAt > 0L) {
                        if (record.analyzedAt < min) min = record.analyzedAt
                    }
                } catch (e: Exception) {
                    // 损坏行跳过，不影响其它记录
                }
            }
        } catch (e: Exception) {
            return 0L
        }
        return if (min == Long.MAX_VALUE) 0L else min
    }

    /**
     * 记一批 case 被服务端拒绝（207 部分被拒）：累计被拒次数，达到上限即**隔离**。
     *
     * 分析记录是已经生成的复盘，**不能删**（消息队列是「待发消息」，超限可以丢；
     * 分析记录是用户资产），所以这里用「隔离」代替「丢弃」：记录仍留在
     * analysis.jsonl 里可人工查看，只是不再上报、不再阻塞水位。
     *
     * @return 本次**新**被隔离的 case_id 集合（调用方据此判断「还有没有 case 阻塞水位」）
     */
    @Synchronized
    fun markRejected(context: Context, caseIds: Set<String>, nowMs: Long): Set<String> {
        if (caseIds.isEmpty()) return emptySet()
        val file = storeFile(context)
        if (!file.exists()) return emptySet()
        val outcome = AnalysisRejectFile(file).markRejected(caseIds, nowMs)
        if (!outcome.rewritten) return emptySet()
        // 文件被重写 -> 字节游标失效（下一轮自动从头扫）
        bumpGeneration(context)
        val index = loadQuarantineIndex(context)
        outcome.newlyQuarantined.forEach { (caseId, firstAt) -> index[caseId] = firstAt }
        saveQuarantineIndex(context, index)
        return outcome.newlyQuarantined.keys.toSet()
    }

    /**
     * 强制隔离：立即把这些 case 标记为不再上报（**413 毒丸条目**专用）。
     *
     * 413 的语义是「这条记录单独就超过服务端上限」，重试多少次都不会成功 ——
     * 立刻隔离，别再每轮把几 MiB 往上传。记录仍留在 analysis.jsonl 里可人工查看。
     * 隔离同样计入索引，7 天 TTL 到期自动复活（重新获得一次上报机会）。
     *
     * @return 是否确实改动了文件
     */
    @Synchronized
    fun quarantineNow(context: Context, caseIds: Set<String>, nowMs: Long): Boolean {
        if (caseIds.isEmpty()) return false
        val file = storeFile(context)
        if (!file.exists()) return false
        val changed = AnalysisRejectFile(file).quarantine(caseIds, nowMs)
        if (!changed) return false
        // 文件被重写 -> 字节游标失效（下一轮自动从头扫）
        bumpGeneration(context)
        val index = loadQuarantineIndex(context)
        caseIds.forEach { caseId ->
            if (!index.containsKey(caseId)) index[caseId] = nowMs
        }
        saveQuarantineIndex(context, index)
        return true
    }

    /** 当前被隔离的 case_id 集合（记录仍在文件里，只是不再上报）。 */
    @Synchronized
    fun quarantinedCaseIds(context: Context): Set<String> {
        val file = storeFile(context)
        if (!file.exists()) return emptySet()
        return AnalysisRejectFile(file).quarantinedCaseIds()
    }

    /** 被隔离的 case 条数。 */
    @Synchronized
    fun quarantinedCount(context: Context): Int {
        val file = storeFile(context)
        if (!file.exists()) return 0
        return AnalysisRejectFile(file).quarantinedCount()
    }
}
