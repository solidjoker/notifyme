// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import org.json.JSONObject

/**
 * 一条捕获到的聊天消息。
 *
 * @param sender       发送者（群聊为发言人昵称，私聊为对方昵称）
 * @param text         消息文本内容
 * @param timestamp    消息时间戳（毫秒），优先取通知内消息时间，否则取通知发布时间
 * @param conversation 会话名（群聊为群名，私聊通常等于 sender）
 * @param isGroup      是否群聊（依据通知是否带 MessagingStyle 判断）
 * @param source       来源标记：空 = 通知监听（时间戳真实）；
 *                     a11y-extract = 无障碍直读（时间戳为估算值，见 A11yExtractService）
 * @param pkg          应用包名（schema v2，M2 跨应用通知管理引入）。
 *                     缺省＝微信，因此老数据（schema v1 无此字段）读进来自动归微信——
 *                     这就是 ROADMAP M2 说的「读时补齐」：不做一次性重写，
 *                     缺字段的行在下次被写入时才带上 pkg。
 * @param appLabel     应用显示名快照，纯展示用；**不参与身份**（系统语言/应用改名会变），
 *                     空表示当时没拿到，UI 回落到包名
 */
data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
    val conversation: String,
    val isGroup: Boolean,
    val source: String = "",
    val pkg: String = AppSourceRegistry.PKG_WECHAT,
    val appLabel: String = ""
) {
    /** 会话复合键：M2 起会话身份是 (pkg, conversation)，不再是裸会话名。 */
    val convKey: ConvKey get() = ConvKey(pkg, conversation)

    /** 是否为「我自己发出」的消息：通知监听只捕获他人消息；a11y 右侧气泡标记为我。 */
    val isSelf: Boolean get() = sender == SENDER_SELF

    fun toJson(): JSONObject = JSONObject().apply {
        put("sender", sender)
        put("text", text)
        put("timestamp", timestamp)
        put("conversation", conversation)
        put("isGroup", isGroup)
        // 仅非空时输出：空来源走服务端默认（通知监听），避免覆盖默认口径
        if (source.isNotEmpty()) put("source", source)
        // pkg 始终写出：老客户端把它当未知字段忽略即可；写出来才能让
        // 「规范化」重写幂等，也便于人工翻 jsonl 时看清归属
        put("pkg", pkg)
        // appLabel 只在拿到时写：它是展示快照，写空串只会污染数据
        if (appLabel.isNotEmpty()) put("appLabel", appLabel)
    }

    companion object {
        /** 自己发送消息的规范发送者名（a11y 采集右侧气泡时使用）。 */
        const val SENDER_SELF = "我"

        fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
            sender = obj.optString("sender"),
            text = obj.optString("text"),
            timestamp = obj.optLong("timestamp"),
            conversation = obj.optString("conversation"),
            isGroup = obj.optBoolean("isGroup"),
            source = obj.optString("source"),
            pkg = obj.optString("pkg").ifEmpty { AppSourceRegistry.PKG_WECHAT },
            appLabel = obj.optString("appLabel")
        )
    }
}

/**
 * 轻量本地消息存储：JSON Lines 追加写入 app 私有目录 files/messages.jsonl。
 *
 * 设计取舍：
 *  - 不引入 Room：本项目只是append-only 日志式存储，JSONL 足够且零额外依赖；
 *  - org.json 是 Android 平台内置类，不占第三方依赖；
 *  - 文件在应用私有目录，卸载即删，符合「本地存储、不出库」的安全边界。
 *
 * 线程安全：NotificationListenerService 回调与 UI 读取可能并发，
 * 所有文件操作都在 @Synchronized 方法内完成。
 *
 * 分层（M1）：本 object 只负责「Context → 文件目录」与 UI 变更通知，
 * 真正的读写/去重/删除逻辑在 [MessageStoreCore]（无 Android 依赖，可直接单测）。
 */
object MessageStore {

    private const val FILE_NAME = "messages.jsonl"

    /** 日期分组兜底 key：timestamp ≤0（如 a11y 直读估算失败）的消息归「未标注日期」组 */
    const val DAY_KEY_UNKNOWN = DateKeyCore.UNKNOWN

    // 日期分组用 java.time（API 26+ 可用）：原来的静态 SimpleDateFormat 在
    // UI / Worker / prefs 剔除等多线程路径共用，同一实例被并发 format 会产出错日期；
    // 更严重的是它的历法随 Locale 变（佛历 / 和历），产出的 key 与别的调用点对不上。
    // 口径统一收敛在 DateKeyCore。

    /**
     * 进程内变更观察者：新消息入库后通知 UI 立即刷新，解决「App 停在前台时
     * 不重走 onResume、列表永远不更新」的问题。
     *
     * - 通知监听服务与 Activity 同进程，静态注册即可，无需广播；
     * - append/merge 可能发生在系统回调线程（binder/handler thread），
     *   统一 post 到主线程分发，观察者里可以直接操作视图；
     * - 用 CopyOnWriteArrayList：注册/注销低频，遍历分发高频，读多写少。
     */
    fun interface OnMessagesChangedListener {
        fun onMessagesChanged()
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<OnMessagesChangedListener>()

    /**
     * 惰性创建：JVM 单测里没有 Looper，一旦在类初始化时就 new Handler，
     * 连纯逻辑测试都会被 "Method getMainLooper not mocked" 打断。
     * 只有真的注册了观察者才会走到这里（见 [notifyChanged] 的空列表短路）。
     */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun addOnMessagesChangedListener(l: OnMessagesChangedListener) {
        listeners.addIfAbsent(l)
    }

    fun removeOnMessagesChangedListener(l: OnMessagesChangedListener) {
        listeners.remove(l)
    }

    /** 入库变更后统一走这里：主线程分发，避免观察者里碰视图崩溃。 */
    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        mainHandler.post { listeners.forEach { it.onMessagesChanged() } }
    }

    /** 消息所属日期 key：yyyy-MM-dd（本机时区）；timestamp≤0 返回 [DAY_KEY_UNKNOWN]。 */
    fun dayKeyOf(m: ChatMessage): String = DateKeyCore.dayKeyOf(m.timestamp)

    /**
     * 按目录构造核心逻辑（不依赖 Context）：单测与内部复用入口。
     * @param onChange 入库变更回调；生产路径传 [notifyChanged]，测试传空实现
     */
    internal fun coreIn(dir: File, onChange: () -> Unit = {}): MessageStoreCore =
        MessageStoreCore(JsonlStore(File(dir, FILE_NAME)), onChange)

    private fun core(context: Context): MessageStoreCore =
        coreIn(context.filesDir, ::notifyChanged)

    /** 追加一条消息（JSON Lines，一行一条）；入库后通知已注册的 UI 观察者。 */
    @Synchronized
    fun append(context: Context, message: ChatMessage) {
        // 自身/系统源永不入库（统一收口）：监听器入口已挡一次，
        // 这里兜住 a11y 直读等其他写入路径，杜绝「采集自己的状态通知」噪声
        if (AppSourceRegistry.isBlocked(message.pkg)) return
        core(context).append(message)
    }

    /**
     * 读取最近 [limit] 条消息，按时间倒序（最新在前）。
     * timestamp<=0 的「未标注日期」消息不受截断影响、始终保留（先占名额），
     * 否则消息总数超 [limit] 时它们会因排序最末而永远丢失。
     */
    @Synchronized
    fun readRecent(context: Context, limit: Int = 200): List<ChatMessage> =
        core(context).readRecent(limit)

    /**
     * 按会话取消息窗口（详情页专用）：只含 [key] 会话、按时间升序。
     * 窗口口径与「条数是否可信」的判定见 [ConvWindowCore] —— 别在 Activity 里再 filter 一遍
     * readRecent 的结果，那会把别的会话的活跃度带进本会话的可视条数。
     */
    @Synchronized
    internal fun readWindow(
        context: Context,
        key: ConvKey,
        limit: Int = ConvWindowCore.ALL_LIMIT
    ): ConvWindowCore.Window = core(context).readWindow(key, limit)

    /**
     * 首页信息流的取样：每会话各留一窗（口径全在 [FeedWindowCore]）。
     *
     * 返回的 [FeedWindowCore.Bucket] 已按「最近活跃」倒序、组内按时间升序，
     * 可直接喂给首页的会话头 / 天组 / 消息渲染。别在这里再 filter 一遍
     * [readRecent] 的结果 —— 那会把别的会话的活跃度带进本会话的可见条数。
     */
    @Synchronized
    internal fun feedGroups(
        context: Context,
        maxPerConv: Int = FeedWindowCore.PER_CONV,
        maxConversations: Int = FeedWindowCore.MAX_CONVERSATIONS,
        totalBudget: Int = FeedWindowCore.TOTAL_BUDGET
    ): FeedWindowCore.Result = core(context).feedGroups(
        maxPerConv = maxPerConv,
        maxConversations = maxConversations,
        totalBudget = totalBudget
    )

    /**
     * 按会话分组取「分析候选」：每会话只留最近 [CandidateWindowCore.PER_CONV] 条。
     *
     * 为什么不能沿用 `readRecent(500).groupBy { it.convKey }`：那个窗口是**跨会话**的
     * 最近 500 条，几个群刷满窗口，安静却有待办的会话在候选里一条都没有，永远排不进分析。
     * 这里改成「尾部行窗口（[MessageStoreCore.TAIL_LINES]）+ 每会话自带上限」，
     * 单个会话再吵也只会占掉自己的 [CandidateWindowCore.PER_CONV] 条。
     *
     * 口径（分组顺序 / 升序 / ts<=0 归零 / 超过上限保留最近）全在 [CandidateWindowCore]。
     */
    @Synchronized
    internal fun candidateGroups(
        context: Context,
        perConv: Int = CandidateWindowCore.PER_CONV
    ): Map<ConvKey, List<ChatMessage>> = core(context).candidateGroups(perConv)

    /**
     * 删除 [cutoffMs]（毫秒时间戳）之前的消息（数据保留策略：本地只留 7 天）。
     * timestamp ≤0 与损坏行保守保留。返回实际删除条数。
     */
    @Synchronized
    fun pruneOlderThan(context: Context, cutoffMs: Long): Int {
        val store = JsonlStore(java.io.File(context.filesDir, FILE_NAME))
        if (!store.exists()) return 0
        val result = RetentionCore.prune(store.readRawLines(), cutoffMs) { line ->
            try {
                org.json.JSONObject(line).optLong("timestamp", 0L).takeIf { it > 0 }
            } catch (e: Exception) {
                null // 损坏行保留
            }
        }
        if (result.removed > 0) {
            store.overwrite(result.kept)
            notifyChanged()
        }
        return result.removed
    }

    /** 清空全部记录。 */
    @Synchronized
    fun clear(context: Context) = core(context).clear()

    /** 删除指定会话（复合键）的全部消息，返回实际删除条数。 */
    @Synchronized
    fun deleteConversation(context: Context, key: ConvKey): Int =
        core(context).deleteConversation(key)

    /**
     * 删除指定会话在某一日期 key 下的消息，返回实际删除条数。
     * @param dayKey yyyy-MM-dd（本机时区）或 [DAY_KEY_UNKNOWN]（未标注日期）
     */
    @Synchronized
    fun deleteDate(context: Context, key: ConvKey, dayKey: String): Int =
        core(context).deleteDate(key, dayKey)

    /**
     * 「设置 → 数据 → 规范化」：把还是 schema v1（没有 pkg 字段）的行补齐后整体重写。
     * @return 被补齐的行数；0 表示无需重写（此时不动文件）
     */
    @Synchronized
    fun normalizeSchema(context: Context): Int = core(context).normalizeSchema()

    /**
     * 只读扫描历史重复（未读计数变化导致的重复入库），不碰文件。
     *
     * 归并口径见 [MessageDedupeCore]：同 App + 同会话 + 同发送者 + 剥掉未读前缀后同正文，
     * 且组内至少一行带未读前缀，才判为重复；保留组内最早的一条。
     */
    @Synchronized
    internal fun repeatedPlan(context: Context): MessageDedupeCore.Plan =
        core(context).repeatedPlan()

    /**
     * 执行历史重复归并（二次确认后由用户手动触发），返回实际执行结果。
     * 没有重复时不碰文件、不发变更通知。
     */
    @Synchronized
    internal fun dedupeRepeated(context: Context): MessageDedupeCore.Plan =
        core(context).dedupeRepeated()

    /**
     * 存储层诊断（W4）：原始行数 / 可解析行数 / 损坏行数 / 文件字节数。
     * 加锁：追加写与全量读并发时可能读到写了一半的行，被误计为「损坏行」。
     */
    @Synchronized
    fun storageStats(context: Context): StorageStats = core(context).storageStats()

    /**
     * 删除指定的消息集合（会话详情页多选删除用），返回实际删除条数。
     * 按完整字段匹配；文件里若有完全相同的重复消息，按选中条数依次删除。
     */
    @Synchronized
    fun deleteMessages(context: Context, messages: Collection<ChatMessage>): Int =
        core(context).deleteMessages(messages)

    /**
     * 合并外部来源消息（如服务端历史提取回填），返回实际新增条数。
     *
     * 去重键：会话|发送者|内容|秒级时间戳——通知捕获取通知发布时间、
     * 数据库提取取消息 createTime，同一消息两种来源的毫秒值可能不同，
     * 秒级归一与服务端跨来源去重口径一致。
     */
    @Synchronized
    fun merge(context: Context, incoming: List<ChatMessage>): Int =
        core(context).merge(incoming)

    /**
     * 与 [merge] 同逻辑，但返回实际新增的条目（无障碍直读等来源需要
     * 把新增消息逐条挂到 PendingQueue 走上报，而不是只拿条数）。
     */
    @Synchronized
    fun mergeAndCollect(context: Context, incoming: List<ChatMessage>): List<ChatMessage> =
        core(context).mergeAndCollect(incoming)
}

/**
 * 消息全字段匹配键（会话详情页多选删除用）：[MessageStoreCore] 与 [PendingQueueCore]
 * 共用同一口径。M2 已加上 `pkg`——两个不同 App 里出现同名同文同时刻的消息
 * 现在不再会被误判为同一条。
 */
internal fun chatMessageFullKey(m: ChatMessage): String =
    "${m.pkg}|${m.conversation}|${m.sender}|${m.text}|${m.timestamp}|${m.isGroup}|${m.source}"

/**
 * 消息存储文件诊断快照（W4 采集诊断页）。
 *
 * @param totalLines   原始行数（含损坏行）
 * @param validLines   可解析为 [ChatMessage] 的行数
 * @param corruptLines 无法解析的行数（重写文件时仍原样保留，不自动删除）
 * @param sizeBytes    messages.jsonl 当前字节数
 */
data class StorageStats(
    val totalLines: Int,
    val validLines: Int,
    val corruptLines: Int,
    val sizeBytes: Long
)

/**
 * [MessageStore] 的无 Android 依赖内核：只认一个 [JsonlStore]（即一个文件）
 * 与一个变更回调，因此可以在 JVM 单测里用临时目录直接跑全部分支。
 *
 * 行为与原 object 内联实现逐条对齐（M1 抽出时不改语义）：
 *  - 单行损坏不影响整体读取，且**重写时原样保留**；
 *  - 删除类操作只在真的删掉了行时才重写文件；
 *  - 未标注日期（timestamp≤0）的消息在 [readRecent] 里先占保留名额。
 *
 * 线程安全：不加锁，由调用方（[MessageStore] 的 @Synchronized）保证串行。
 */
internal class MessageStoreCore(
    private val store: JsonlStore,
    private val onChange: () -> Unit = {}
) {

    companion object {
        /**
         * 「最近消息」的尾部读取窗口（原始行数）。
         *
         * 窗口越大，[readRecent] 的结果越接近全量读取（也越能让 ts<=0 的
         * 「未标注日期」消息落进窗口），内存占用只与窗口成正比、与文件总大小无关。
         */
        const val TAIL_LINES = 5_000
    }

    /** 追加一条消息（JSON Lines，一行一条）。 */
    fun append(message: ChatMessage) {
        store.appendLine(message.toJson().toString())
        onChange()
    }

    /** 存储层统计：原始行数、可解析行数、损坏行数、文件字节数。 */
    fun storageStats(): StorageStats {
        if (!store.exists()) return StorageStats(0, 0, 0, 0)
        val lines = store.readRawLines()
        var valid = 0
        for (line in lines) {
            try {
                ChatMessage.fromJson(JSONObject(line))
                valid++
            } catch (e: Exception) {
                // 损坏行计入 corrupt
            }
        }
        return StorageStats(
            totalLines = lines.size,
            validLines = valid,
            corruptLines = lines.size - valid,
            sizeBytes = store.file.length()
        )
    }

    /**
     * 读取最近 [limit] 条消息，按时间倒序（最新在前）。
     *
     * 只从文件尾部读 [TAIL_LINES] 行以内的窗口（见 [JsonlStore.readTailLines]）：
     * append 语义下文件顺序≈时间顺序，尾部即最近，因此对正常规模的历史结果与
     * 全量读取一致；超出窗口的极老消息不参与排序（换取 O(窗口) 而非 O(文件) 的内存）。
     * 调用方都在后台线程（UI 侧另加代次校验），故不做缓存。
     */
    fun readRecent(limit: Int = 200): List<ChatMessage> {
        if (!store.exists()) return emptyList()

        val all = store.readTailLines(TAIL_LINES).mapNotNull { line ->
            try {
                ChatMessage.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null // 单行损坏不影响整体读取
            }
        }

        // ts<=0（a11y 估算失败等「未标注日期」消息）若直接参与按时间倒序截断，
        // 会永远排在末尾：消息总数超过 limit 时必然丢失，UI 的未标注日期兜底
        // 永远不会触发。因此无时间戳消息先占保留名额，剩余名额再取最新消息。
        val undated = all.filter { it.timestamp <= 0 }
        val undatedSlots = minOf(undated.size, limit)
        val dated = all.filter { it.timestamp > 0 }
            .sortedByDescending { it.timestamp }
            .take(limit - undatedSlots)
        return dated + undated.take(undatedSlots)
    }

    /**
     * 按会话分组取「分析候选」：每会话只留最近 [perConv] 条，按时间升序。
     *
     * 与 [readRecent] 的差别是保留名额的**粒度**：readRecent 的 limit 是跨会话总名额，
     * 活跃会话会挤掉安静会话；这里每个会话各自保留，谁的活跃度也不影响别人。
     * 排序口径与 [ConvWindowCore.slice] 一致（ts<=0 归 0 排最前），分组顺序保持首次出现序。
     */
    fun candidateGroups(perConv: Int = CandidateWindowCore.PER_CONV): Map<ConvKey, List<ChatMessage>> {
        if (!store.exists()) return emptyMap()
        val parsed = store.readTailLines(TAIL_LINES).mapNotNull { line ->
            try {
                ChatMessage.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null // 单行损坏不影响整体读取
            }
        }
        return CandidateWindowCore.group(parsed, perConv)
    }

    /**
     * 按会话取消息窗口：只返回 [key] 这个会话的消息，按时间**升序**（气泡从上到下）。
     *
     * 为什么不能沿用 [readRecent] + filter：readRecent 的窗口是**跨会话**的最近 N 条，
     * 别的会话刷满窗口就会把本会话的历史挤出窗口 —— 详情页会空白，可数据仍在文件里。
     * 这里按会话单独请求更大的行窗口（[ConvWindowCore.requestLines]），并同时把
     * 「请求了多少行 / 实际读到多少行」带回给调用方，让 UI 能诚实判断条数是不是总数。
     *
     * @param limit 本会话最多返回多少条（保留最近的），默认 [ConvWindowCore.ALL_LIMIT]
     */
    fun readWindow(key: ConvKey, limit: Int = ConvWindowCore.ALL_LIMIT): ConvWindowCore.Window {
        val requested = ConvWindowCore.requestLines(limit)
        if (!store.exists()) {
            return ConvWindowCore.Window(key, emptyList(), 0, requested, limit)
        }
        val lines = store.readTailLines(requested)
        val parsed = lines.mapNotNull { line ->
            try {
                ChatMessage.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null // 单行损坏不影响整体读取
            }
        }
        return ConvWindowCore.Window(
            key = key,
            messages = ConvWindowCore.slice(parsed, key, limit),
            linesRead = lines.size,
            requested = requested,
            limit = limit
        )
    }

    /**
     * 首页信息流的取样：按会话各留一窗（口径全在 [FeedWindowCore]）。
     *
     * 为什么不能沿用 [readRecent]：那个窗口是**跨会话**的最近 N 条，几个群刷满窗口后
     * 安静会话一条都进不了首页 —— 首页待办区的可见集（todoVisibleKeys）随之看不到它，
     * 而数据其实还在 messages.jsonl 里。这是同源隐患的第五处，前四处已由
     * [ConvWindowCore] / [CandidateWindowCore] / [AnalysisWindowCore] 收口。
     *
     * @param requested 尾部读多少原始行（[FeedWindowCore.REQUEST_LINES]）；读不满即已到文件头
     */
    fun feedGroups(
        maxPerConv: Int = FeedWindowCore.PER_CONV,
        maxConversations: Int = FeedWindowCore.MAX_CONVERSATIONS,
        totalBudget: Int = FeedWindowCore.TOTAL_BUDGET,
        requested: Int = FeedWindowCore.REQUEST_LINES
    ): FeedWindowCore.Result {
        if (!store.exists()) return FeedWindowCore.Result(emptyList(), true)
        val lines = store.readTailLines(requested)
        val parsed = lines.mapNotNull { line ->
            try {
                ChatMessage.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null // 单行损坏不影响整体读取
            }
        }
        // requested<=0 时 readTailLines 返空表，此时「读到文件头」这一说法无意义，按未读全处理
        val complete = requested > 0 && lines.size < requested
        return FeedWindowCore.group(
            messages = parsed,
            maxPerConv = maxPerConv,
            maxConversations = maxConversations,
            totalBudget = totalBudget,
            complete = complete
        )
    }

    /** 清空全部记录。 */
    fun clear() {
        store.delete()
        onChange()
    }

    /**
     * 删除指定会话（复合键）的全部消息，返回实际删除条数。
     * 老数据行没有 pkg 字段，读进来落到默认微信，因此 [ConvKey.legacy] 也能删到它们。
     */
    fun deleteConversation(key: ConvKey): Int =
        rewrite { it.convKey != key }

    /** 删除指定会话在某一日期 key 下的消息，返回实际删除条数。 */
    fun deleteDate(key: ConvKey, dayKey: String): Int =
        rewrite { !(it.convKey == key && MessageStore.dayKeyOf(it) == dayKey) }

    /**
     * schema v1 → v2 规范化：逐行补齐缺省的 `pkg`（老数据按微信）后整体重写。
     *
     * 取舍：
     *  - 返回 0 时**不碰文件**，也不发变更通知（「已规范化」不该假装改动过）；
     *  - 损坏行原样保留，与 [rewrite] 同口径——规范化只补字段，绝不清理数据；
     *  - 由用户手动触发（设置 → 数据 → 规范化），不做开机自动迁移：
     *    大文件迁移中途被杀会留下半截文件，而读时补齐已经保证老数据可用。
     *
     * @return 被补齐（原本没有 pkg）的行数
     */
    fun normalizeSchema(): Int {
        if (!store.exists()) return 0
        val raw = store.readRawLines()
        val out = ArrayList<String>(raw.size)
        var fixed = 0
        for (line in raw) {
            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                null
            }
            if (obj == null) {
                out += line // 损坏行原样保留
                continue
            }
            if (obj.optString("pkg").isEmpty()) {
                // 只补缺失的 pkg，**不重建**整个对象：fromJson().toJson() 会把本版本
                // 不认识的字段直接丢掉（更新版客户端写进来的新字段就是静默数据损失）。
                obj.put("pkg", AppSourceRegistry.PKG_WECHAT)
                fixed += 1
            }
            out += obj.toString()
        }
        if (fixed == 0) return 0
        store.overwrite(out)
        onChange()
        return fixed
    }

    /**
     * 删除指定的消息集合，返回实际删除条数。
     * 按完整字段匹配；文件里若有完全相同的重复消息，按选中条数依次删除。
     */
    fun deleteMessages(messages: Collection<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        val remaining = HashMap<String, Int>()
        messages.forEach { k ->
            val key = chatMessageFullKey(k)
            remaining[key] = (remaining[key] ?: 0) + 1
        }
        return rewrite { m ->
            val key = chatMessageFullKey(m)
            val left = remaining[key] ?: 0
            if (left > 0) {
                remaining[key] = left - 1
                false // 命中待删集合，本行丢弃
            } else {
                true
            }
        }
    }

    /**
     * 只读扫描历史重复，不碰文件（UI 弹确认框前先拿预计删除条数与样例）。
     */
    fun repeatedPlan(): MessageDedupeCore.Plan {
        if (!store.exists()) return MessageDedupeCore.Plan(emptyList(), 0, emptyList())
        return MessageDedupeCore.plan(store.readRawLines())
    }

    /**
     * 执行历史重复归并：只删「剥掉未读前缀后正文完全相同」的后来者，保留最早一条。
     * 没有重复时不重写文件、不发变更通知。
     */
    fun dedupeRepeated(): MessageDedupeCore.Plan {
        val plan = repeatedPlan()
        if (plan.removed <= 0) return plan
        store.overwrite(plan.kept)
        onChange()
        return plan
    }

    /** 合并外部来源消息，返回实际新增条数。 */
    fun merge(incoming: List<ChatMessage>): Int = mergeAndCollect(incoming).size

    /** 与 [merge] 同逻辑，但返回实际新增的条目。 */
    fun mergeAndCollect(incoming: List<ChatMessage>): List<ChatMessage> {
        if (incoming.isEmpty()) return emptyList()
        val existing = HashSet<String>()
        store.forEachRawLine { line ->
            try {
                existing += dedupKey(ChatMessage.fromJson(JSONObject(line)))
            } catch (e: Exception) {
                // 单行损坏跳过，不影响合并
            }
        }
        val added = mutableListOf<ChatMessage>()
        val lines = mutableListOf<String>()
        for (m in incoming) {
            if (m.conversation.isBlank() || m.text.isBlank()) continue
            if (existing.add(dedupKey(m))) {
                lines += m.toJson().toString()
                added += m
            }
        }
        if (added.isNotEmpty()) {
            store.appendLines(lines)
            onChange()
        }
        return added
    }

    /**
     * 整体读入、按 [keep] 过滤、重写文件；返回丢弃条数。
     * 损坏行无法解析，原样保留（不因重写丢数据）。
     */
    private fun rewrite(keep: (ChatMessage) -> Boolean): Int {
        val removed = store.rewriteRaw { line ->
            val msg = try {
                ChatMessage.fromJson(JSONObject(line))
            } catch (e: Exception) {
                null
            }
            // 解析不出来 → 当损坏行原样保留（不因一次重写丢掉用户数据）
            msg == null || keep(msg)
        }
        if (removed > 0) onChange()
        return removed
    }

    /**
     * 合并去重键。M2 起加上包名前缀：不同 App 的同名会话不再互相吞消息。
     * 老数据行缺 pkg → 读进来是微信默认值，与同一条消息的新写法算出同一个键，
     * 因此「读时补齐」不会造成重复入库。
     */
    private fun dedupKey(m: ChatMessage): String =
        if (m.source == "a11y-extract") {
            // 无障碍直读的时间戳是估算值（每次运行都变），不参与去重，
            // 否则同一会话重复提取会反复插入重复消息
            "${keyPart(m.pkg)}|${keyPart(m.conversation)}|${keyPart(m.sender)}|${keyPart(m.text)}|a11y"
        } else {
            "${keyPart(m.pkg)}|${keyPart(m.conversation)}|${keyPart(m.sender)}|${keyPart(m.text)}|${m.timestamp / 1000}"
        }

    /**
     * 去重键字段：长度前缀 + 冒号，避免「会话A + 发送者B」与「会话AB + 空发送者」
     * 拼出同一个键（原实现直接相邻拼接，长会话名会吞掉相邻字段）导致消息被误判重复丢弃。
     */
    private fun keyPart(value: String): String = value.length.toString() + ":" + value
}
