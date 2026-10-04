// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.Notification
import android.app.Notification.MessagingStyle
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 微信通知监听器 —— qiyeweixin Android 端「消息获取」的零注入等价方案。
 *
 * 原理：系统官方 NotificationListenerService API。
 * 微信收到消息时会弹出系统通知，本服务在通知栏层面读取通知内容，
 * 不注入微信进程、不 hook、不读取应用数据目录，与母项目的安全红线一致。
 *
 * 能力边界（详见 README「已知限制」）：
 *  - 只能看到「触发了系统通知」的消息：免打扰群、正在前台聊天的会话不产生通知；
 *  - 拿不到历史消息，只能从服务启动后开始捕获；
 *  - 通知内容可能被系统折叠（EXTRA_TEXT 为摘要，EXTRA_MESSAGES 为多条历史）。
 */
class WeChatNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WeChatListener"

        /** 微信包名 */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** 去重窗口：最多记住最近 200 个通知 key */
        private const val DEDUP_CAPACITY = 200

        /**
         * 监听服务连接标志：onListenerConnected/Disconnected 回调里维护，
         * 供 KeepAliveService 看门狗巡检；@Volatile 保证跨线程可见。
         */
        @Volatile
        var connected: Boolean = false
            private set
    }

    /**
     * 去重：同一通知的 update 会重复回调 onNotificationPosted（key 相同）。
     * 用 LinkedHashMap 做一个有界 LRU：key -> 最近一次的内容指纹。
     * 指纹包含文本内容，避免「内容没变的刷新」被重复入库，
     * 同时保证同一会话来了新消息（内容变了）不会被误丢弃。
     */
    private val recentKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > DEDUP_CAPACITY
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1) 只关心微信
        if (sbn.packageName != PKG_WECHAT) return

        val notification = sbn.notification ?: return

        // 2) 丢弃系统/常驻类通知：
        //    - ongoing（如「微信正在运行」的前台服务通知）；
        //    - CATEGORY_SERVICE / CATEGORY_STATUS 等状态类；
        //    - group summary（聚合摘要通知，避免与单条重复入库）。
        if (sbn.isOngoing) return
        if (notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_STATUS
        ) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        // 3) 标题与正文都为空的通知（如纯状态更新）没有意义，丢弃
        if (title.isEmpty() && text.isEmpty()) return

        // 4) 去重：同 key 且内容指纹一致 -> 视为同一条的刷新，跳过
        val fingerprint = "$title|$text"
        if (recentKeys[sbn.key] == fingerprint) return
        recentKeys[sbn.key] = fingerprint

        // 5) 解析消息（优先 MessagingStyle，群聊每条带独立 sender）
        val message = parseMessage(title, text, notification, sbn.postTime) ?: return

        // 6) 入库 + 日志
        MessageStore.append(applicationContext, message)
        // 同步进入待上报队列（核心过滤逻辑不变，仅追加一行）
        PendingQueue.append(applicationContext, message)
        Log.i(TAG, "捕获微信消息: $message")
    }

    /**
     * 从通知 extras 中解析出结构化消息。
     *
     * 群聊典型结构：EXTRA_TITLE = 群名，MessagingStyle（EXTRA_MESSAGES）里
     * 每条 message 带 sender（发言人）与 text；取最后一条作为「当前消息」。
     * 私聊典型结构：EXTRA_TITLE = 对方昵称，EXTRA_TEXT = 消息内容。
     */
    private fun parseMessage(
        title: String,
        text: String,
        notification: Notification,
        postTime: Long
    ): ChatMessage? {
        // 注意：extractMessagingStyleFromNotification 是隐藏 API，不在公开 SDK 里，
        // 这里改用公开途径——从 extras 的 EXTRA_MESSAGES（Bundle 数组）还原消息列表
        @Suppress("DEPRECATION") // API 33 起推荐带类型的 getParcelableArray，minSdk 26 仍需旧签名
        val messagesArray = notification.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        val styleMessages = messagesArray
            ?.let { MessagingStyle.Message.getMessagesFromBundleArray(it) }
            .orEmpty()

        return if (styleMessages.isNotEmpty()) {
            // 群聊（或开了 MessagingStyle 的会话）：最后一条是当前新消息
            val last = styleMessages.last()
            val sender = extractSenderName(last)
            val msgText = last.text?.toString()?.trim().orEmpty()
            if (msgText.isEmpty()) return null

            val timestamp = if (last.timestamp > 0) last.timestamp else postTime
            ChatMessage(
                sender = sender.ifEmpty { title },
                text = msgText,
                timestamp = timestamp,
                conversation = title,
                isGroup = true
            )
        } else {
            // 私聊/无 MessagingStyle 的通知：发送者就是标题
            if (text.isEmpty()) return null
            ChatMessage(
                sender = title,
                text = text,
                timestamp = postTime,
                conversation = title,
                isGroup = false
            )
        }
    }

    /**
     * 提取 MessagingStyle.Message 的发言人名称。
     * API 28+ 推荐 senderPerson.name；更低版本用已废弃的 sender（CharSequence）。
     */
    private fun extractSenderName(message: MessagingStyle.Message): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            message.senderPerson?.name?.toString()?.trim().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            message.sender?.toString()?.trim().orEmpty()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 通知被用户滑掉/微信撤回时回调。目前仅清理去重缓存，不删历史记录。
        if (sbn.packageName == PKG_WECHAT) {
            recentKeys.remove(sbn.key)
        }
    }

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "通知监听服务已断开，主动请求系统重绑")
        // 不等 KeepAliveService 看门狗巡检，断绑当场就请求重绑（API 24+ 官方 API）
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, WeChatNotificationListener::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind 调用失败", e)
        }
    }
}
