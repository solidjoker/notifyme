// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 会话/发送者规范化（纯函数，无 Android 依赖）。
 *
 * 为什么需要：云端与端侧两条 S1 路径必须读同一份窗口才能比一致率，
 * 但发送者名来源不同（a11y 侧写「我」，通知捕获侧写原始发言人名），
 * 会话身份也不同（a11y 用估算时间戳，通知用通知内真实时间）。
 * 这里统一成 [MessageStore.SENDER_SELF] 视角 + (pkg, conversation) 分组。
 */
internal object ConvNormalize {

    /** 归一化后的窗口消息（发送者已折叠成 我/对方）。 */
    data class NormMessage(
        val sender: String,
        val text: String,
        val timestamp: Long,
        val isSelf: Boolean,
        val isGroup: Boolean
    )

    /** 一条可读会话：规范化窗口 + 归属包名 + 群聊标记。 */
    data class Conv(
        val conversation: String,
        val pkg: String,
        val isGroup: Boolean,
        val messages: List<NormMessage>
    ) {
        val size: Int get() = messages.size
        val windowEnd: Long get() = messages.last().timestamp
    }

    /**
     * 规范化一条会话。
     * @param conversation 会话名（群聊为群名，单聊为对方昵称）
     * @param pkg 所属 App 包名
     * @param raw 原始消息（任意顺序，内部按时间正序）
     * @param group 群聊标记；null 时按「对方里是否出现 ≥2 个不同发言人」推断
     */
    fun normalize(
        conversation: String,
        pkg: String,
        raw: List<ChatMessage>,
        group: Boolean? = null
    ): Conv? {
        if (raw.isEmpty()) return null
        val sorted = raw.sortedBy { it.timestamp }
        val isGroup = group ?: run {
            val others = sorted.filterNot { it.isSelf }
            others.map { it.sender.trim() }
                .filter { it.isNotEmpty() }
                .distinct().size > 1
        }
        val norm = sorted.map { m ->
            NormMessage(
                sender = if (m.isSelf) ChatMessage.SENDER_SELF else "对方",
                text = m.text.trim(),
                timestamp = m.timestamp,
                isSelf = m.isSelf,
                isGroup = isGroup
            )
        }
        return Conv(conversation.trim(), pkg, isGroup, norm)
    }

    /**
     * 分组：同一 (pkg, conversation) 视为同一会话，时间正序，空会话丢弃。
     * 返回按 (pkg, conversation) 字典序，保证工具输出可复现。
     */
    fun group(raw: List<ChatMessage>): List<Conv> {
        val byKey = LinkedHashMap<String, MutableList<ChatMessage>>()
        for (m in raw) {
            val key = m.pkg + "\u0000" + m.conversation.trim()
            byKey.getOrPut(key) { ArrayList() }.add(m)
        }
        val out = ArrayList<Conv>(byKey.size)
        for (entry in byKey) {
            val list = entry.value
            val first = list.first()
            val conv = normalize(first.conversation, first.pkg, list) ?: continue
            out.add(conv)
        }
        return out.sortedWith(compareBy({ it.pkg }, { it.conversation }))
    }
}
