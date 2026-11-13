// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 「Intent extras → [ConvKey]」的容错口径（纯函数，可 JVM 单测）。
 *
 * 背景（第十四轮重复代码审计）：四个 Activity（分析列表 / 提醒列表 / 会话详情 / 提示词编辑）
 * 各抄了同一段「取 EXTRA_PKG，空白就回落成微信」，再加上「会话名为空就 finish()」与
 * 「会话名为空就当没有筛选」两套分支；[ReminderReceiver] 与 [OverlayService] 又从 Intent 里
 * 取同一对字段。规则本身只有一条，却散在六处：改「缺包名算什么」要同时改六个文件，
 * 漏一个就出现「同一个会话在不同页面被当成两个」。
 *
 * 三条契约：
 * ① 包名缺失 / 纯空白一律回落微信（与 [ConvKey.legacy] 同口径）—— 本项目起点是微信工具，
 *    schema v1 的 Intent 也没带包名。**不 trim**：非空白原样透传，包名里带空白是调用方 bug，
 *    不该在这里被悄悄修掉，否则日志里永远看不到真实入参。
 * ② 会话名为 null / 空串 / 纯空白 = 没有会话，拼键返回 null 而不是构造空会话键。
 *    空会话键在下游是「一个永远匹配不到的会话」：标题显示空、列表永远空，
 *    而调用方本意多半是「没有筛选」（分析列表 / 提醒列表）或「参数缺失，直接关页」（会话详情）。
 * ③ 会话名不 trim、不切分隔符：真实通知标题可能带前后空白，含竖线的群名也很常见，
 *    这里只做「空 / 非空」判定，切分与归一留给 [ConvKey.parse]。
 */
internal object ConvExtrasCore {

    /** 包名口径：纯空白 → 微信，否则原样返回（不 trim）。 */
    fun pkg(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: AppSourceRegistry.PKG_WECHAT

    /** 会话名口径：纯空白 → 空串，否则原样返回（不 trim）。 */
    fun conversation(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: ""

    /**
     * 由两个原始字符串拼出会话键；会话名缺失时返回 null（见契约②）。
     *
     * 入参顺序刻意为 (conversation, pkg)：会话名是「有没有得看」的那一个，包名只是修饰。
     */
    fun key(conversation: String?, pkg: String?): ConvKey? {
        val conv = conversation(conversation)
        if (conv.isEmpty()) return null
        return ConvKey(pkg(pkg), conv)
    }
}
