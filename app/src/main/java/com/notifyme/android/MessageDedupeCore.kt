// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * 历史重复消息的归并规则，纯函数、无 Android 依赖。
 *
 * 为什么需要：2026-11-12 真机取证（docs/通知形状取证记录.md §4.2）量化出
 * messages.jsonl 里 78 组 / 271 行是「同一条消息被重复捕获」—— 微信通知正文形如
 * `[11条]发言人: 正文`，未读计数一变正文就变，而 [MessageStore] 的入库去重键含正文原文，
 * 于是每涨一次未读数就多入库一条。
 *
 * [NotificationTextCore] 修的是「以后不再重复」，本对象处理「已经重复的历史怎么办」：
 * 既有数据不会被自动重写（[MessageStoreCore.normalizeSchema] 只补 pkg 字段、从不删行），
 * 只能由用户手动触发一次归并。
 *
 * 归并口径（保守优先，宁可少删不可误删）：
 *  - 同一 App（pkg）、同一会话（conversation）、同一发送者、剥掉未读计数前缀后正文完全
 *    相同，才进同一组；
 *  - **组内至少有一行带未读计数前缀**才合并。全是裸正文的相同文本可能是用户真的连发
 *    两条（「好的」+「好的」），那种绝不删；
 *  - 老行（sender 与 conversation 相同，即旧解析器没能切出发言人）先用
 *    [NotificationTextCore.splitSender] 还原出真实发送者，再参与归并 ——
 *    否则「[3条]张三: 好的」与「[2条]张三: 好的」之间多一层「sender 是否已切分」的差异；
 *  - 保留组内**第一条**（文件是 append-only，顺序即捕获顺序，最早那条最接近原文）；
 *  - 解析不了的行、空正文行一律原样保留（与 [MessageStoreCore] 的损坏行口径一致）。
 *
 * 纯函数：只吃原始行、只吐计划，不碰文件，因此可以直接单测。
 */
internal object MessageDedupeCore {

    /** 一组被判定为重复的消息。 */
    data class Group(
        val pkg: String,
        val conversation: String,
        val sender: String,
        /** 剥掉未读计数前缀后的正文（组内所有行这个值相同）。 */
        val text: String,
        /** 组内总行数（含被保留的那一条）。 */
        val count: Int,
        /** 组内最早 / 最晚的捕获时间戳（未知为 0）。 */
        val firstTs: Long,
        val lastTs: Long
    ) {
        /** 会被删掉的行数。 */
        val dropCount: Int get() = count - 1

        /** 一行预览：「张三：明天上午十点开会」，超长截断到 [PREVIEW_MAX]。 */
        val preview: String get() {
            val body = if (sender.isBlank() || sender == conversation) text else sender + "：" + text
            return TextClipCore.clipWithEllipsis(body, PREVIEW_MAX)
        }

        companion object {
            const val PREVIEW_MAX = 40
        }
    }

    /**
     * 归并计划。
     *
     * @param kept    重写后的完整行列表（保持原始顺序；损坏行与未归并行原样在内）
     * @param removed 实际删除的行数
     * @param groups  被删了至少一行的组（按首次出现顺序，供 UI 预览）
     */
    data class Plan(
        val kept: List<String>,
        val removed: Int,
        val groups: List<Group>
    ) {
        val isEmpty: Boolean get() = removed == 0
    }

    /** 单行解析结果。 */
    private class Row(
        val key: String,
        val pkg: String,
        val conversation: String,
        val sender: String,
        val text: String,
        val ts: Long,
        val unread: Boolean
    )

    /**
     * 计算归并计划。
     *
     * @param lines 原始行（[JsonlStore.readRawLines] 的结果，已去空行）
     */
    fun plan(lines: List<String>): Plan {
        if (lines.isEmpty()) return Plan(lines, 0, emptyList())

        // 第一遍：解析 + 标出「组内存在未读计数前缀」的归并键（只对这些键动手）
        val rows = arrayOfNulls<Row>(lines.size)
        val mergeable = HashMap<String, Boolean>()
        for (i in lines.indices) {
            val row = parse(lines[i]) ?: continue
            rows[i] = row
            if (row.unread) mergeable[row.key] = true
        }
        if (mergeable.isEmpty()) return Plan(lines, 0, emptyList())

        // 第二遍：按原始顺序输出，每个可归并键只留第一条
        val out = ArrayList<String>(lines.size)
        val counters = HashMap<String, LongArray>() // [count, firstTs, lastTs]
        val order = ArrayList<String>()
        val firstRow = HashMap<String, Row>()
        var removed = 0
        for (i in lines.indices) {
            val row = rows[i]
            if (row == null || mergeable[row.key] != true) {
                out += lines[i]
                continue
            }
            val counter = counters[row.key]
            if (counter == null) {
                counters[row.key] = longArrayOf(1L, row.ts, row.ts)
                order += row.key
                firstRow[row.key] = row
                out += lines[i]
            } else {
                counter[0] += 1L
                if (row.ts > 0L) {
                    if (counter[1] <= 0L) counter[1] = row.ts
                    if (row.ts > counter[2]) counter[2] = row.ts
                }
                removed++
            }
        }

        val groups = order.mapNotNull { key ->
            val counter = counters.getValue(key)
            if (counter[0] <= 1L) return@mapNotNull null
            val row = firstRow.getValue(key)
            Group(
                pkg = row.pkg,
                conversation = row.conversation,
                sender = row.sender,
                text = row.text,
                count = counter[0].toInt(),
                firstTs = counter[1],
                lastTs = counter[2]
            )
        }
        return Plan(out, removed, groups)
    }

    /** 解析一行；损坏行与空正文返回 null（它们永不参与归并）。 */
    private fun parse(line: String): Row? {
        val obj = try {
            JSONObject(line)
        } catch (e: Exception) {
            return null
        }
        val msg = ChatMessage.fromJson(obj)
        if (msg.text.isBlank()) return null
        val unread = NotificationTextCore.hasUnreadPrefix(msg.text)
        // 正文一律先按统一口径切成 (发言人, 正文)：
        //  - 老行（sender 与 conversation 相同）旧解析器没切发言人，只能填会话名 → 用切出来的
        //    真实发言人，否则「[3条]李四: 正文」（sender=群名）与「[4条]李四: 正文」
        //    （sender=李四）会因 sender 不同被当成两条，历史重复永远删不掉；
        //  - 新行 sender 已经切对了，但正文里的「李四: 」前缀仍在 → 也剥掉，两侧才可比。
        val split = NotificationTextCore.splitSender(msg.text, msg.conversation)
        val text = split.text
        val sender = if (msg.sender == msg.conversation && split.sender != null) {
            split.sender
        } else {
            msg.sender
        }
        val key = keyPart(msg.pkg) + "|" + keyPart(msg.conversation) + "|" +
            keyPart(sender) + "|" + keyPart(text)
        return Row(
            key = key,
            pkg = msg.pkg,
            conversation = msg.conversation,
            sender = sender,
            text = text,
            ts = msg.timestamp,
            unread = unread
        )
    }

    /** 与 [MessageStoreCore] 同一套长度前缀口径，避免相邻字段拼出同一个键。 */
    private fun keyPart(value: String): String = value.length.toString() + ":" + value
}
