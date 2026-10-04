// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val redactionHits: Int = 0
) {
    /** 会话复合键（M2）：分析结果归属哪个 App 的哪个会话。 */
    val convKey: ConvKey get() = ConvKey(pkg, conversation)

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
            put("prob", prob)
            put("verdict", verdict)
            put("note", note)
        }

        companion object {
            fun fromJson(obj: JSONObject): ForkRecord = ForkRecord(
                fork = obj.optString("fork"),
                prob = obj.optDouble("prob"),
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
                put("prob", s1NeedActionProb)
            })
            put("importance", s1Importance)
            put("due_window", s1DueWindow)
            put("topic", s1Topic)
            put("confidence", s1Confidence)
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
                s1NeedActionProb = needAction.optDouble("prob"),
                s1Importance = s1.optDouble("importance"),
                s1DueWindow = s1.optString("due_window"),
                s1Topic = s1.optString("topic"),
                s1Confidence = s1.optDouble("confidence"),
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
                redactionHits = obj.optInt("redaction_hits")
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

    private fun syncPrefs(context: Context) = context.applicationContext
        .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)

    private fun storeFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

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
     */
    @Synchronized
    fun readRecent(context: Context, limit: Int = 100): List<AnalysisCaseRecord> {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()

        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
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
            file.writeText(keptLines.joinToString("") { "$it\n" }, Charsets.UTF_8)
        }
        return removed
    }

    /**
     * 读取尚未同步到服务器的 case 记录（analyzedAt 大于已同步水位），按分析时间正序。
     * 服务端按 case_id 去重，水位线只是减少重复传输的优化，重复上报无害。
     */
    fun readUnsynced(context: Context): List<AnalysisCaseRecord> {
        val lastSynced = syncPrefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)
        return readRecent(context, 500)
            .filter { it.analyzedAt > lastSynced }
            .sortedBy { it.analyzedAt }
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
}
