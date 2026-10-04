// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 采集计数器（W4 诊断页用）：进程内、本次启动以来的通知处理漏斗统计。
 *
 * 不落盘——它的用途是回答「为什么微信消息没被抓到」：
 * posted → blocked / disabled / ongoing / empty / duplicate / parseFailed / stored，
 * 每一级丢弃都有数，诊断页可直接定位卡在哪一级。
 *
 * 回调发生在 NotificationListenerService 的 binder 线程，全部用原子类型。
 */
object CaptureStats {

    private val _posted = AtomicInteger()
    private val _blocked = AtomicInteger()
    private val _disabled = AtomicInteger()
    private val _ongoing = AtomicInteger()
    private val _empty = AtomicInteger()
    private val _duplicate = AtomicInteger()
    private val _parseFailed = AtomicInteger()
    private val _stored = AtomicInteger()
    private val _lastStoredAt = AtomicLong()

    /** 每包成功入库条数（LinkedHashMap 访问经 synchronized 块）。 */
    private val perPkg = LinkedHashMap<String, AtomicInteger>()

    val posted: Int get() = _posted.get()
    val blocked: Int get() = _blocked.get()
    val disabled: Int get() = _disabled.get()
    val ongoing: Int get() = _ongoing.get()
    val empty: Int get() = _empty.get()
    val duplicate: Int get() = _duplicate.get()
    val parseFailed: Int get() = _parseFailed.get()
    val stored: Int get() = _stored.get()
    val lastStoredAt: Long get() = _lastStoredAt.get()

    fun onPosted() = _posted.incrementAndGet()
    fun onBlocked() = _blocked.incrementAndGet()
    fun onDisabled() = _disabled.incrementAndGet()
    fun onOngoing() = _ongoing.incrementAndGet()
    fun onEmpty() = _empty.incrementAndGet()
    fun onDuplicate() = _duplicate.incrementAndGet()
    fun onParseFailed() = _parseFailed.incrementAndGet()

    fun onStored(pkg: String) {
        _stored.incrementAndGet()
        _lastStoredAt.set(System.currentTimeMillis())
        synchronized(perPkg) {
            perPkg.getOrPut(pkg) { AtomicInteger() }.incrementAndGet()
        }
    }

    /** 包名 → 本次启动以来入库条数（副本）。 */
    fun storedByPkg(): Map<String, Int> = synchronized(perPkg) {
        perPkg.mapValues { it.value.get() }
    }
}
