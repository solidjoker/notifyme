// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 通知正文 → (发言人, 正文, 是否群聊) 的切分规则，纯函数、无 Android 依赖。
 *
 * 为什么要单独抽出来：真实通知形状与代码里的假设差得远（2026-11-12 真机取证，见
 * docs/通知形状取证记录.md §5）。真机上微信与钉钉的通知正文普遍长这样：
 *
 * ```
 * text = "[11条]发言人: 正文"      // 未读计数 + 发言人 + 半角冒号（冒号后可能没有空格）
 * title = "会话名"                  // 群聊是群名，私聊是对方昵称
 * styleMessages = []                // 没有 MessagingStyle
 * ```
 *
 * 旧实现的两个后果：
 *
 *  1. 未读计数前缀「[N条]」被当成正文的一部分入库。它随未读数变化（[3条] → [4条] → …），
 *     而 [MessageStore] 的去重键含正文原文，于是**同一句话每涨一次未读数就多入库一条**；
 *  2. 发言人没被切出来，`sender` 只能是会话名，群聊被判成私聊 —— 分析 prompt 里出现
 *     「群名说了一句话」这种错误归属。
 *
 * 本对象只做「切」，不碰 MessageStore / 解析器注册表，因此可以直接单测。
 */
internal object NotificationTextCore {

    /** 发言人前缀的字符上限（含）：再长就不是昵称，是正文的一部分。 */
    const val MAX_SENDER_PREFIX_LEN = 20

    /**
     * 分隔符，按优先级排列：全角冒号 → 半角冒号+空格 → 半角冒号。
     *
     * 最后一个是真机逼出来的：钉钉走小米推送代发时正文是「沈晓怡(老师):「…」」，
     * 冒号后**没有空格**，旧实现只认前两种，于是整串被当成正文。
     */
    val SEPARATORS = listOf("：", ": ", ":")

    /** 微信未读计数前缀：「[11条]」「[3条] 」（容忍内部空白）。 */
    private val UNREAD_PREFIX = Regex("^\\[\\s*[0-9]+\\s*条\\s*\\]\\s*")

    /** 纯数字前缀（「10:30 开会」里的 10）不是昵称。 */
    private val DIGITS_ONLY = Regex("^[0-9]+$")

    /** 一次切分的结果。 */
    data class Split(
        /** 切出来的发言人；null = 没识别出前缀，调用方应回落到会话名。 */
        val sender: String?,
        /** 去掉未读计数前缀与发言人前缀之后的正文。 */
        val text: String,
        /** 是否群聊（发言人存在且与会话名不是同一个身份）。 */
        val isGroup: Boolean,
        /** 是否剥掉了未读计数前缀（用于日志/统计，不参与入库字段）。 */
        val unreadStripped: Boolean
    )

    /** 是否带未读计数前缀（[11条] 这种）。 */
    fun hasUnreadPrefix(raw: String): Boolean = UNREAD_PREFIX.containsMatchIn(raw)

    /**
     * 剥掉开头的未读计数前缀。
     *
     * 剥完为空时**保留原文**：宁可正文里带一个「[3条]」，也不要把整条消息变成空串
     * （空正文在调用方会被判定为「无内容」直接丢弃，那是丢消息，比脏数据更糟）。
     */
    fun stripUnreadPrefix(raw: String): String {
        val match = UNREAD_PREFIX.find(raw) ?: return raw
        val rest = raw.substring(match.range.last + 1)
        return if (rest.isBlank()) raw else rest
    }

    /**
     * 把一条通知正文切成 (发言人, 正文) 并判定是否群聊。
     *
     * @param body         原始正文（可带未读计数前缀）
     * @param conversation 会话名（通知标题；群聊是群名，私聊是对方昵称）
     */
    fun splitSender(body: String, conversation: String): Split {
        val unreadStripped = hasUnreadPrefix(body)
        val stripped = stripUnreadPrefix(body).trim()
        if (stripped.isEmpty()) {
            return Split(sender = null, text = body.trim(), isGroup = false, unreadStripped = false)
        }

        val hit = findPrefix(stripped)
            ?: return Split(sender = null, text = stripped, isGroup = false, unreadStripped)

        val prefix = hit.first
        val rest = hit.second
        val sameAsConversation = prefix == conversation
        // 会话名常是「昵称_备注_…」拼出来的（真机上就见过「程婷_乐读_叶衿心」），
        // 此时 prefix 是会话名的子串 —— 同一个人的两种叫法，按私聊处理，但保留更短的昵称作发言人
        val conversationContainsPrefix = !sameAsConversation && conversation.contains(prefix)
        val sender = if (sameAsConversation) conversation else prefix
        return Split(
            sender = sender,
            text = rest,
            isGroup = !sameAsConversation && !conversationContainsPrefix,
            unreadStripped = unreadStripped
        )
    }

    /** 找到合法的 (发言人, 正文)；找不到返回 null。 */
    private fun findPrefix(body: String): Pair<String, String>? {
        for (sep in SEPARATORS) {
            val idx = body.indexOf(sep)
            if (idx <= 0 || idx > MAX_SENDER_PREFIX_LEN) continue
            val prefix = body.substring(0, idx).trim()
            val rest = body.substring(idx + sep.length).trim()
            if (prefix.isEmpty() || rest.isEmpty()) continue
            if (prefix.contains('\n') || prefix.contains('\r')) continue
            if (!isPlausibleSender(prefix, rest, sep)) continue
            return prefix to rest
        }
        return null
    }

    /**
     * 收紧半角冒号（含「: 」与「:」两种写法）的判定；全角冒号保持既有宽松口径。
     *
     * 半角冒号在中文正文里天然歧义（时间、比值、协议头、英文标点），不收紧就会出现
     * 「明天 10:30 在三楼开会」被切成「明天 10 说：30 在三楼开会」这种归属错误。
     */
    private fun isPlausibleSender(prefix: String, rest: String, sep: String): Boolean {
        // 昵称不含空白：「明天 10:30 在三楼开会」的前缀带空格，说明冒号在正文里，不是发言人。
        // 这条对所有分隔符生效——真机上没见过带空格的昵称，误判成本却很高。
        if (prefix.any { it.isWhitespace() }) return false
        if (sep == "：") return true
        // 单字前缀 + 半角冒号，几乎都是正文标点用法（「答: 明天开会」），不是昵称
        if (prefix.length < 2) return false
        // 「10:30 开会」的时间、「1:2」这种比值不是发言人
        if (DIGITS_ONLY.matches(prefix)) return false
        // 「https://…」「ftp://…」这类协议头不切
        if (rest.startsWith("//")) return false
        return true
    }
}
