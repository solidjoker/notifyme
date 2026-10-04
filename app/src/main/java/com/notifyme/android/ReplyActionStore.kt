// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * 快速回复动作缓存（W3）：进程内单例，通知监听器写入、会话详情页读取。
 *
 * 只保留每会话最近一条原始通知里带 RemoteInput 的「直接回复」action；
 * 通知被滑掉 / 过期后 actionIntent 发送会抛 CanceledException，调用方按失败提示。
 * 不落盘、重启进程即清空——回复能力本就依赖存活的通知。
 */
object ReplyActionStore {

    private const val CAPACITY = 50

    /**
     * access-ordered LRU；所有读写经同一把锁（put 发生在监听回调线程，
     * actionFor 发生在 UI 线程）。
     */
    private val cache = object : LinkedHashMap<ConvKey, Notification>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConvKey, Notification>?): Boolean =
            size > CAPACITY
    }

    fun put(key: ConvKey, sbn: StatusBarNotification) {
        // 没有可回复 action 的通知不占缓存
        if (MessageReplier.findReplyAction(sbn) == null) return
        synchronized(cache) { cache[key] = sbn.notification ?: return }
    }

    /**
     * 返回该会话当前可直接回复的 action；无缓存 / 通知已无 action 时返回 null。
     */
    fun actionFor(key: ConvKey): Notification.Action? {
        val notification = synchronized(cache) { cache[key] } ?: return null
        return notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }

    fun remove(key: ConvKey) {
        synchronized(cache) { cache.remove(key) }
    }
}
