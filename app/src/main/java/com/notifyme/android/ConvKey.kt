// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 复合会话键：M2 起「一个会话」的身份是 (应用包名, 会话名)。
 *
 * 为什么不能只用会话名：接入多个 App 后，微信里的「张三」和短信里的「张三」是两个会话，
 * 只用会话名会让删除、关注名单、提醒互相串台。
 *
 * 持久化形态是 [id]（`pkg|conversation`）。解析只切**第一个**分隔符，
 * 因此会话名里含 `|`（微信群名很常见）也不会被切坏——这正是老实现
 * `substringBefore('|')`/`substringAfter('|')` 拆日期头 key 的隐患。
 */
data class ConvKey(val pkg: String, val conversation: String) {

    /** 规范字符串形态，用于落盘、SharedPreferences 名单、跨端接口。 */
    val id: String get() = "$pkg$SEPARATOR$conversation"

    override fun toString(): String = id

    /** UI 展示用的应用名：注册表内置名 → 消息里带的 label → 包名兜底。 */
    fun displayLabel(appLabel: String = ""): String =
        AppSourceRegistry.labelFor(pkg) ?: appLabel.ifBlank { null } ?: pkg

    companion object {
        const val SEPARATOR = "|"

        fun of(pkg: String, conversation: String) = ConvKey(pkg, conversation)

        fun of(m: ChatMessage) = ConvKey(m.pkg, m.conversation)

        /**
         * 老数据兼容入口：schema v1 的行里没有 pkg 字段，本项目起点是微信专用工具，
         * 因此「没有包名」一律按微信处理。
         */
        fun legacy(conversation: String) = ConvKey(AppSourceRegistry.PKG_WECHAT, conversation)

        /**
         * 解析持久化形态。容错口径：
         *  - 不含分隔符（v1 名单里的裸会话名）→ [legacy]；
         *  - 分隔符在首位（包名为空）→ 同样按 [legacy] 处理会话名部分；
         *  - 空字符串 → 会话名为空的微信键（不抛异常，交给调用方的过滤逻辑判空）。
         */
        fun parse(raw: String): ConvKey {
            val idx = raw.indexOf(SEPARATOR)
            if (idx < 0) return legacy(raw)
            val pkg = raw.substring(0, idx)
            val conversation = raw.substring(idx + SEPARATOR.length)
            return if (pkg.isEmpty()) legacy(conversation) else ConvKey(pkg, conversation)
        }

        /** 批量解析并跳过空白项（名单里可能混入空串）。 */
        fun parseAll(raw: Collection<String>): Set<ConvKey> =
            raw.filter { it.isNotBlank() }.map { parse(it) }.toSet()
    }
}
