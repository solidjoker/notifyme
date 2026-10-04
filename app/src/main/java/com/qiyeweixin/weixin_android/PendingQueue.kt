// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

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
 */
object PendingQueue {

    private const val FILE_NAME = "pending.jsonl"

    /** 自增 id 计数器；0 表示尚未从磁盘恢复（进程重启后惰性初始化） */
    private var nextId: Long = 0L

    private fun queueFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 追加一条待上报消息。 */
    @Synchronized
    fun append(context: Context, message: ChatMessage) {
        val entry = PendingMessage(allocId(context), message)
        queueFile(context).appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /** 读取全部待上报消息，按 id 升序（先入队的先上报）。 */
    @Synchronized
    fun readAll(context: Context): List<PendingMessage> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()

        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    PendingMessage.fromJson(JSONObject(line))
                } catch (e: Exception) {
                    null // 单行损坏不影响整体读取
                }
            }
            .sortedBy { it.id }
            .toList()
    }

    /**
     * 上报成功后剔除已发送条目：整体重写文件。
     * 只删本次实际发送的 id 集合，上报期间新入队的消息天然保留。
     */
    @Synchronized
    fun removeSent(context: Context, sentIds: Set<Long>) {
        val remaining = readAll(context).filterNot { it.id in sentIds }
        val file = queueFile(context)
        if (remaining.isEmpty()) {
            file.delete()
        } else {
            file.writeText(
                remaining.joinToString("") { it.toJson().toString() + "\n" },
                Charsets.UTF_8
            )
        }
    }

    /** 清空队列（随主界面「清除记录」一起清理）。 */
    @Synchronized
    fun clear(context: Context) {
        queueFile(context).delete()
        nextId = 0L
    }

    /** 删除属于指定会话的待上报条目（本地删除会话时联动，避免已删消息仍被上报）。 */
    @Synchronized
    fun removeByConversations(context: Context, conversations: Collection<String>): Int =
        rewrite(context) { it.message.conversation !in conversations }

    /** 删除指定会话某一日期 key 下的待上报条目。 */
    @Synchronized
    fun removeByConversationDay(context: Context, conversation: String, dayKey: String): Int =
        rewrite(context) {
            !(it.message.conversation == conversation &&
                MessageStore.dayKeyOf(it.message) == dayKey)
        }

    /** 删除与给定消息完全匹配的待上报条目（会话详情页多选删除时联动）。 */
    @Synchronized
    fun removeByMessages(context: Context, messages: Collection<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        val remaining = HashMap<String, Int>()
        messages.forEach { m ->
            val key = "${m.conversation}|${m.sender}|${m.text}|${m.timestamp}|${m.isGroup}|${m.source}"
            remaining[key] = (remaining[key] ?: 0) + 1
        }
        return rewrite(context) { entry ->
            val m = entry.message
            val key = "${m.conversation}|${m.sender}|${m.text}|${m.timestamp}|${m.isGroup}|${m.source}"
            val left = remaining[key] ?: 0
            if (left > 0) {
                remaining[key] = left - 1
                false
            } else {
                true
            }
        }
    }

    /** 整体读入、按 [keep] 过滤、重写文件；返回丢弃条数。 */
    private fun rewrite(context: Context, keep: (PendingMessage) -> Boolean): Int {
        val file = queueFile(context)
        if (!file.exists()) return 0
        val kept = mutableListOf<PendingMessage>()
        var removed = 0
        for (entry in readAll(context)) {
            if (keep(entry)) {
                kept += entry
            } else {
                removed++
            }
        }
        if (removed > 0) {
            if (kept.isEmpty()) {
                file.delete()
            } else {
                file.writeText(
                    kept.joinToString("") { it.toJson().toString() + "\n" },
                    Charsets.UTF_8
                )
            }
        }
        return removed
    }

    /** 分配自增 id；进程重启后先从现有文件恢复最大值再继续递增。 */
    private fun allocId(context: Context): Long {
        if (nextId == 0L) {
            nextId = (readAll(context).maxOfOrNull { it.id } ?: 0L) + 1
        }
        return nextId++
    }
}
