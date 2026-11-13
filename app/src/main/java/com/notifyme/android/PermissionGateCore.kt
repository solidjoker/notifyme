// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.Manifest

/**
 * 运行时权限的「要不要申请 / 结果算不算全授予」口径（纯函数，可 JVM 单测）。
 *
 * 背景（第十四轮重复代码审计）：`POST_NOTIFICATIONS` 的判断在 [MainActivity] 与
 * [ConsoleActivity] 里逐字相同（连注释都一样），[ReminderReceiver] 又抄了第三份（只查不申请）；
 * 日历的「两个权限都授予了吗」在 [CalendarHelper] 是一段 && 表达式，在
 * [ReminderSettingsActivity] 的 `onRequestPermissionsResult` 里又写成 `all {}` 一份；
 * `arrayOf(READ_CALENDAR, WRITE_CALENDAR)` 这个请求数组则散在三个 Activity 里。
 *
 * 三条契约：
 * ① **Android 13（API 33）是分水岭**：从这一版起前台服务通知也要 `POST_NOTIFICATIONS`，
 *    12 及以下该权限恒为已授予 —— 判断必须同时看 `sdkInt` 与 `checkSelfPermission`，
 *    只看结果会在低版本上把「已授予」误判成「要申请」，弹出系统永远不理的请求。
 * ② **`GRANTED = 0` 是 `PackageManager.PERMISSION_GRANTED` 的字面值**，**`DENIED = -1`** 同理；
 *    Core 不 import `PackageManager`，是为了让断言能用裸数字（-1 就是拒绝，`0` 就是授予），
 *    也避免单测去碰 Android 框架类。
 * ③ **`allGranted` 对空结果返 false**：空 `IntArray` 意味着「回调里没有结果」，
 *    当成授予会让「权限弹窗被系统直接关掉」被误判为成功 —— 旧实现
 *    `grantResults.isNotEmpty() && all {}` 的 `isNotEmpty()` 正是这个意思，别删。
 */
internal object PermissionGateCore {

    /** `POST_NOTIFICATIONS` 成为运行时权限的起始 API level（Android 13 / Tiramisu）。 */
    const val SDK_POST_NOTIFICATIONS = 33

    /** `PackageManager.PERMISSION_GRANTED` 的字面值。 */
    const val GRANTED = 0

    /** `PackageManager.PERMISSION_DENIED` 的字面值。 */
    const val DENIED = -1

    /**
     * 是否需要申请 `POST_NOTIFICATIONS`：仅「≥13 且当前未授予」才申请。
     *
     * @param sdkInt `Build.VERSION.SDK_INT`
     * @param result `checkSelfPermission(POST_NOTIFICATIONS)` 的返回值
     */
    fun needsPostNotifications(sdkInt: Int, result: Int): Boolean =
        sdkInt >= SDK_POST_NOTIFICATIONS && result != GRANTED

    /** 一组权限结果是否**全部**授予；空结果一律 false（见契约③）。 */
    fun allGranted(results: IntArray): Boolean =
        results.isNotEmpty() && results.all { it == GRANTED }

    /** 单条权限结果是否授予。 */
    fun isGranted(result: Int): Boolean = result == GRANTED

    /**
     * 提醒链要用的一对日历权限（顺序即申请顺序，READ 在前 —— 界面上先读后写的暗示）。
     *
     * 必须与 `AndroidManifest.xml` 的两条 `uses-permission` 一致；改名先跑
     * [PermissionGateCoreTest]（它拿 [Manifest] 常量逐字对照）。
     */
    fun calendarPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    /**
     * 常驻通知权限（Android 13+ 前台服务通知用）。
     *
     * 用字面量而不是 [Manifest.permission.POST_NOTIFICATIONS]：该常量是编译期内联的 API 33 字段，
     * 这里没有 `SDK_INT >= 33` 的现场守卫（守卫在调用方），lint 会报 InlinedApi，
     * 让基线从 3 条漂到 4 条。权限名是 Android 的稳定契约（与 AndroidManifest.xml 逐字一致），
     * 单测也按同一个字面量断言。
     */
    fun postNotificationPermissions(): Array<String> = arrayOf(PERM_POST_NOTIFICATIONS)

    /** AndroidManifest.xml 里 android.permission.POST_NOTIFICATIONS 的逐字副本。 */
    const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
}
