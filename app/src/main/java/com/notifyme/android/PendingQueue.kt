// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 待上报队列里的一条消息：在 ChatMessage 基础上加一个自增 id。
 * id 用于上报成功后精确剔除已发送条目（避免 timestamp+text 撞键误删）。
 */
data class PendingMessage(
    val id: Long,
    val message: ChatMessage
) {
    fun toJson(): JSONObject = message.toJson().apply {
        put("id", id)
    }

    companion object {
        fun fromJson(obj: JSONObject): PendingMessage = PendingMessage(
            id = obj.optLong("id"),
            message = ChatMessage.fromJson(obj)
        )
    }
}

/**
 * 待上报队列：JSON Lines 追加写入 app 私有目录 files/pending.jsonl。
 *
 * 与 MessageStore 的关系（为什么单独一个文件而不改 MessageStore）：
 *  - MessageStore 是「历史全量、append-only」的本地存档，语义是不可变日志；
 *  - PendingQueue 是「待发送、上报成功后剔除」的瞬态队列，生命周期完全不同；
 *  - 拆开后 MessageStore 一行不动，不影响既有列表展示逻辑，回归风险最小。
 *
 * 写放大的取舍：上报成功后整体重写 pending.jsonl 剔除已发条目。
 * 对个人学习规模（周期内数百条以内）足够；量级变大再考虑分段/墓碑标记。
 *
 * 线程安全：与 MessageStore 一致，所有文件操作都在 @Synchronized 方法内完成
 * （@Synchronized 基于可重入监视器，append 内部调用 readAll 不会死锁）。
 *
 * 分层（M1）：本 object 只负责「Context → 文件目录」与 core 实例缓存，
 * 队列逻辑在 [PendingQueueCore]（无 Android 依赖，可直接单测）。
 */
object PendingQueue {

    private const val FILE_NAME = "pending.jsonl"

    /**
     * 每个数据目录缓存一个 core：[PendingQueueCore] 内部有自增 id 计数器，
     * 必须跨调用存活，否则每次 append 都要整文件扫一遍求 max(id)。
     * 读写都发生在本 object 的 @Synchronized 方法里，监视器已保证可见性。
     */
    private var cachedDir: File? = null
    private var cachedCore: PendingQueueCore? = null

    /** 按目录构造队列内核（不依赖 Context）：单测与内部复用入口。 */
    internal fun coreIn(dir: File): PendingQueueCore =
        PendingQueueCore(JsonlStore(File(dir, FILE_NAME)))

    @Synchronized
    private fun core(context: Context): PendingQueueCore {
        val dir = context.filesDir
        val cached = cachedCore
        if (cached != null && cachedDir == dir) return cached
        return coreIn(dir).also {
            cachedCore = it
            cachedDir = dir
        }
    }

    /** 追加一条待上报消息。 */
    @Synchronized
    fun append(context: Context, message: ChatMessage) = core(context).append(message)

    /** 读取全部待上报消息，按 id 升序（先入队的先上报）。 */
    @Synchronized
    fun readAll(context: Context): List<PendingMessage> = core(context).readAll()

    /**
     * 上报成功后剔除已发送条目：整体重写文件。
     * 只删本次实际发送的 id 集合，上报期间新入队的消息天然保留。
     */
    @Synchronized
    fun removeSent(context: Context, sentIds: Set<Long>) = core(context).removeSent(sentIds)

    /** 清空队列（随主界面「清除记录」一起清理）。 */
    @Synchronized
    fun clear(context: Context) = core(context).clear()

    /**
     * 删除属于指定会话（复合键）的待上报条目（本地删除会话时联动，避免已删消息仍被上报）。
     * M2 起按 [ConvKey] 匹配：只给会话名会误删同名会话在别的 App 里的条目。
     */
    @Synchronized
    fun removeByConversations(context: Context, keys: Collection<ConvKey>): Int =
        core(context).removeByConversations(keys)

    /** 删除指定会话某一日期 key 下的待上报条目。 */
    @Synchronized
    fun removeByConversationDay(context: Context, key: ConvKey, dayKey: String): Int =
        core(context).removeByConversationDay(key, dayKey)

    /** 删除与给定消息完全匹配的待上报条目（会话详情页多选删除时联动）。 */
    @Synchronized
    fun removeByMessages(context: Context, messages: Collection<ChatMessage>): Int =
        core(context).removeByMessages(messages)
}

/**
 * [PendingQueue] 的无 Android 依赖内核：只认一个 [JsonlStore]（即一个文件），
 * 因此可以在 JVM 单测里用临时目录直接跑全部分支（含 id 恢复、各条删除路径）。
 *
 * 行为与原 object 内联实现逐条对齐（M1 抽出时不改语义），其中一处**与 MessageStore 的
 * 已知差异**是刻意的（M2 已就「要不要统一」拍板：不统一）：
 * 重写按「解析后的对象」重新序列化，所以损坏行会在重写时被丢弃，而 MessageStore
 * 的重写原样保留损坏行。理由是两者语义不同——messages.jsonl 是**用户历史存档**，
 * 损坏行也可能是能人工抢救的数据；pending.jsonl 是**待发送队列**，损坏条目永远
 * 发不出去，留着只会在每次重写时被反复搬运、越积越多。差异由 PendingQueueCoreTest 记档。
 *
 * 线程安全：不加锁，由调用方（[PendingQueue] 的 @Synchronized）保证串行。
 */
internal class PendingQueueCore(private val store: JsonlStore) {

    /** 自增 id 计数器；0 表示尚未从磁盘恢复（进程重启后惰性初始化） */
    private var nextId: Long = 0L

    /** 追加一条待上报消息。 */
    fun append(message: ChatMessage) {
        val entry = PendingMessage(allocId(), message)
        store.appendLine(entry.toJson().toString())
    }

    /** 读取全部待上报消息，按 id 升序（先入队的先上报）。 */
    fun readAll(): List<PendingMessage> {
        if (!store.exists()) return emptyList()
        return store.readRawLines()
            .mapNotNull { line ->
                try {
                    PendingMessage.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null // 单行损坏不影响整体读取
                }
            }
            .sortedBy { it.id }
    }

    /** 上报成功后剔除已发送条目：整体重写文件（全删完则删文件，不留 0 字节）。 */
    fun removeSent(sentIds: Set<Long>) {
        val remaining = readAll().filterNot { it.id in sentIds }
        store.overwrite(remaining.map { it.toJson().toString() })
    }

    /** 清空队列，并把 id 计数器归零（下次 append 会重新从文件恢复）。 */
    fun clear() {
        store.delete()
        nextId = 0L
    }

    /** 删除属于指定会话（复合键）的待上报条目，返回实际删除条数。 */
    fun removeByConversations(keys: Collection<ConvKey>): Int =
        rewrite { it.message.convKey !in keys }

    /** 删除指定会话某一日期 key 下的待上报条目，返回实际删除条数。 */
    fun removeByConversationDay(key: ConvKey, dayKey: String): Int =
        rewrite {
            !(it.message.convKey == key &&
                MessageStore.dayKeyOf(it.message) == dayKey)
        }

    /** 删除与给定消息完全匹配的待上报条目，返回实际删除条数。 */
    fun removeByMessages(messages: Collection<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        val remaining = HashMap<String, Int>()
        messages.forEach { m ->
            val key = chatMessageFullKey(m)
            remaining[key] = (remaining[key] ?: 0) + 1
        }
        return rewrite { entry ->
            val key = chatMessageFullKey(entry.message)
            val left = remaining[key] ?: 0
            if (left > 0) {
                remaining[key] = left - 1
                false
            } else {
                true
            }
        }
    }

    /** 整体读入、按 [keep] 过滤、重写文件；返回丢弃条数（没删掉任何行就不碰文件）。 */
    private fun rewrite(keep: (PendingMessage) -> Boolean): Int {
        if (!store.exists()) return 0
        val kept = mutableListOf<PendingMessage>()
        var removed = 0
        for (entry in readAll()) {
            if (keep(entry)) {
                kept += entry
            } else {
                removed++
            }
        }
        if (removed > 0) {
            store.overwrite(kept.map { it.toJson().toString() })
        }
        return removed
    }

    /** 分配自增 id；进程重启后先从现有文件恢复最大值再继续递增。 */
    private fun allocId(): Long {
        if (nextId == 0L) {
            nextId = (readAll().maxOfOrNull { it.id } ?: 0L) + 1
        }
        return nextId++
    }
}
