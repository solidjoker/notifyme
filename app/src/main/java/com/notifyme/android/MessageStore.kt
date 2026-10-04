// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条捕获到的聊天消息。
 *
 * @param sender       发送者（群聊为发言人昵称，私聊为对方昵称）
 * @param text         消息文本内容
 * @param timestamp    消息时间戳（毫秒），优先取通知内消息时间，否则取通知发布时间
 * @param conversation 会话名（群聊为群名，私聊通常等于 sender）
 * @param isGroup      是否群聊（依据通知是否带 MessagingStyle 判断）
 * @param source       来源标记：空 = 通知监听（时间戳真实）；
 *                     a11y-extract = 无障碍直读（时间戳为估算值，见 WeChatA11yExtractService）
 */
data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
    val conversation: String,
    val isGroup: Boolean,
    val source: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sender", sender)
        put("text", text)
        put("timestamp", timestamp)
        put("conversation", conversation)
        put("isGroup", isGroup)
        // 仅非空时输出：空来源走服务端默认（通知监听），避免覆盖默认口径
        if (source.isNotEmpty()) put("source", source)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
            sender = obj.optString("sender"),
            text = obj.optString("text"),
            timestamp = obj.optLong("timestamp"),
            conversation = obj.optString("conversation"),
            isGroup = obj.optBoolean("isGroup"),
            source = obj.optString("source")
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
    const val DAY_KEY_UNKNOWN = "__unknown__"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

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
    fun dayKeyOf(m: ChatMessage): String =
        if (m.timestamp > 0) dayFormat.format(Date(m.timestamp)) else DAY_KEY_UNKNOWN

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
    fun append(context: Context, message: ChatMessage) = core(context).append(message)

    /**
     * 读取最近 [limit] 条消息，按时间倒序（最新在前）。
     * timestamp<=0 的「未标注日期」消息不受截断影响、始终保留（先占名额），
     * 否则消息总数超 [limit] 时它们会因排序最末而永远丢失。
     */
    @Synchronized
    fun readRecent(context: Context, limit: Int = 200): List<ChatMessage> =
        core(context).readRecent(limit)

    /** 清空全部记录。 */
    @Synchronized
    fun clear(context: Context) = core(context).clear()

    /** 删除指定会话的全部消息，返回实际删除条数。 */
    @Synchronized
    fun deleteConversation(context: Context, conversation: String): Int =
        core(context).deleteConversation(conversation)

    /**
     * 删除指定会话在某一日期 key 下的消息，返回实际删除条数。
     * @param dayKey yyyy-MM-dd（本机时区）或 [DAY_KEY_UNKNOWN]（未标注日期）
     */
    @Synchronized
    fun deleteDate(context: Context, conversation: String, dayKey: String): Int =
        core(context).deleteDate(conversation, dayKey)

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
 * 共用同一口径，M2 给 [ChatMessage] 加 `pkg` 时只需改这一处。
 */
internal fun chatMessageFullKey(m: ChatMessage): String =
    "${m.conversation}|${m.sender}|${m.text}|${m.timestamp}|${m.isGroup}|${m.source}"

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

    /** 追加一条消息（JSON Lines，一行一条）。 */
    fun append(message: ChatMessage) {
        store.appendLine(message.toJson().toString())
        onChange()
    }

    /**
     * 读取最近 [limit] 条消息，按时间倒序（最新在前）。
     *
     * 文件较大时只从尾部读必要行数之外的简化实现：整体读入再截取，
     * 对个人学习规模（数千条）足够；如需更大规模再换成分页/索引。
     */
    fun readRecent(limit: Int = 200): List<ChatMessage> {
        if (!store.exists()) return emptyList()

        val all = store.readRawLines().mapNotNull { line ->
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

    /** 清空全部记录。 */
    fun clear() {
        store.delete()
        onChange()
    }

    /** 删除指定会话的全部消息，返回实际删除条数。 */
    fun deleteConversation(conversation: String): Int =
        rewrite { it.conversation != conversation }

    /** 删除指定会话在某一日期 key 下的消息，返回实际删除条数。 */
    fun deleteDate(conversation: String, dayKey: String): Int =
        rewrite { !(it.conversation == conversation && MessageStore.dayKeyOf(it) == dayKey) }

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

    private fun dedupKey(m: ChatMessage): String =
        if (m.source == "a11y-extract") {
            // 无障碍直读的时间戳是估算值（每次运行都变），不参与去重，
            // 否则同一会话重复提取会反复插入重复消息
            "${m.conversation}${m.sender}|${m.text}|a11y"
        } else {
            "${m.conversation}${m.sender}|${m.text}|${m.timestamp / 1000}"
        }
}
