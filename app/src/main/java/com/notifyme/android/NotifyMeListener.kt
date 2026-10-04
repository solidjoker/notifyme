// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Notification
import android.app.Notification.MessagingStyle
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知监听器（M2 起由微信专用的 WeChatNotificationListener 改名而来）——
 * notifyme Android 端「消息获取」的零注入方案：系统官方 NotificationListenerService API，
 * 不注入任何 App 进程、不 hook、不读取应用数据目录。
 *
 * M2 之后的口径：
 *  - 不再做单包判定，按「已启用应用源集合」（[AppSourceStore]）过滤；
 *  - 每个 App 怎么解析通知由 [AppSourceRegistry] 决定（微信/飞书/钉钉有专属规则，
 *    其余走通用兜底），本类只负责把系统通知抽成纯数据 [RawNotification]；
 *  - 自身/系统通知永不入库（[AppSourceRegistry.isBlocked]）。
 *
 * 能力边界（详见 README「已知限制」）：
 *  - 只能看到「触发了系统通知」的消息：免打扰群、正在前台聊天的会话不产生通知；
 *  - 拿不到历史消息，只能从服务启动后开始捕获；
 *  - 通知内容可能被系统折叠（EXTRA_TEXT 为摘要，EXTRA_MESSAGES 为多条历史）。
 */
class NotifyMeListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifyMeListener"

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
        CaptureStats.onPosted()
        val pkg = sbn.packageName

        // 1) 自身/系统/系统 UI 永不入库（本 App 自己的提醒通知会造成回环）
        if (AppSourceRegistry.isBlocked(pkg)) {
            CaptureStats.onBlocked()
            return
        }

        val notification = sbn.notification ?: run { CaptureStats.onEmpty(); return }

        // 2) 「已启用应用源集合」过滤：用户在设置里关掉的来源直接跳过
        if (!AppSourceStore.isEnabled(applicationContext, pkg)) {
            CaptureStats.onDisabled()
            return
        }

        // 3) 丢弃系统/常驻类通知：
        //    - ongoing（如「微信正在运行」的前台服务通知）；
        //    - CATEGORY_SERVICE / CATEGORY_STATUS 等状态类；
        //    - group summary（聚合摘要通知，避免与单条重复入库）。
        if (sbn.isOngoing) {
            CaptureStats.onOngoing()
            return
        }
        if (notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_STATUS
        ) {
            CaptureStats.onOngoing()
            return
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            CaptureStats.onOngoing()
            return
        }

        // 4) 系统通知 → 纯数据（此步之后解析逻辑就能在 JVM 单测里复用）
        val raw = toRawNotification(pkg, notification, sbn.postTime)

        // 5) 标题与正文（含 bigText）都为空的通知（如纯状态更新）没有意义，丢弃
        if (raw.title.isEmpty() && raw.text.isEmpty() && raw.bigText.isEmpty()) {
            CaptureStats.onEmpty()
            return
        }

        // 6) 去重：同 key 且内容指纹一致 -> 视为同一条的刷新，跳过
        val fingerprint = "${raw.title}|${raw.text}|${raw.bigText}"
        if (recentKeys[sbn.key] == fingerprint) {
            CaptureStats.onDuplicate()
            return
        }
        recentKeys[sbn.key] = fingerprint

        // 7) 按来源规则解析；入库 + 记录来源 + 日志
        val message = AppSourceRegistry.parserFor(pkg).parse(raw) ?: run {
            CaptureStats.onParseFailed()
            return
        }
        MessageStore.append(applicationContext, message)
        CaptureStats.onStored(pkg)
        // 同步进入待上报队列（核心过滤逻辑不变，仅追加一行）
        PendingQueue.append(applicationContext, message)
        // 缓存原始通知供「快速回复」使用（无回复 action 时内部不占缓存）
        ReplyActionStore.put(message.convKey, sbn)
        // 成功入库后才算「观察到该来源」，避免空内容通知污染来源列表
        AppSourceStore.recordSeen(applicationContext, pkg, raw.appLabel)
        Log.i(TAG, "捕获消息[$pkg]: $message")

        // 8) M9 悬浮卡（即时路径）：只对「用户显式加入重点名单」的会话弹卡——
        //    空名单语义是「全部关注」，此时每条都弹会打扰，改由分析完成路径出卡
        if (OverlayConfig.get(applicationContext).cardsEnabled) {
            val explicit = WatchlistStore.watchedKeys(applicationContext)
            if (explicit.contains(message.convKey)) {
                OverlayManager.show(
                    applicationContext,
                    OverlayManager.Card(
                        pkg = message.pkg,
                        conversation = message.conversation,
                        sender = message.sender,
                        text = message.text,
                        appLabel = message.appLabel
                    )
                )
            }
        }
    }

    /**
     * 把系统 [Notification] 抽成纯数据 [RawNotification]。
     * 「取 Android 类型」集中在这里，字段含义交给注册表里的 parser 决定。
     */
    private fun toRawNotification(
        pkg: String,
        notification: Notification,
        postTime: Long
    ): RawNotification {
        val extras = notification.extras
        fun charString(key: String): String =
            extras?.getCharSequence(key)?.toString()?.trim().orEmpty()

        return RawNotification(
            pkg = pkg,
            appLabel = resolveAppLabel(pkg),
            title = charString(Notification.EXTRA_TITLE),
            text = charString(Notification.EXTRA_TEXT),
            postTime = postTime,
            bigText = charString(Notification.EXTRA_BIG_TEXT),
            subText = charString(Notification.EXTRA_SUB_TEXT),
            styleMessages = extractStyleMessages(extras, postTime)
        )
    }

    /**
     * 从 extras 的 EXTRA_MESSAGES（Bundle 数组）还原 MessagingStyle 消息列表；
     * 不用隐藏 API extractMessagingStyleFromNotification，公开 SDK 内只有这条路。
     */
    private fun extractStyleMessages(extras: Bundle?, postTime: Long): List<RawStyleMessage> {
        // API 33 起推荐带类型的 getParcelableArray，minSdk 26 仍需旧签名
        @Suppress("DEPRECATION")
        val messagesArray = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: return emptyList()
        return MessagingStyle.Message.getMessagesFromBundleArray(messagesArray)
            .map { m ->
                RawStyleMessage(
                    sender = extractSenderName(m),
                    text = m.text?.toString()?.trim().orEmpty(),
                    timestamp = if (m.timestamp > 0) m.timestamp else postTime
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

    /**
     * 应用显示名：注册表内置名直接用；其余向 PackageManager 查，
     * 查不到（Android 11+ 包可见性限制）返回空，由 UI/parser 回落包名。
     */
    private fun resolveAppLabel(pkg: String): String =
        AppSourceRegistry.labelFor(pkg) ?: try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) {
            ""
        }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 通知被用户滑掉/对方撤回时回调。多源后不再按包名判定，统一清理去重缓存，不删历史记录。
        recentKeys.remove(sbn.key)
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
                ComponentName(this, NotifyMeListener::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind 调用失败", e)
        }
    }
}
