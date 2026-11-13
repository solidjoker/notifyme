// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PermissionGateCore] 的权限判定口径测试。
 *
 * 三处同源重复（第十四轮审计）：POST_NOTIFICATIONS 的「≥13 且未授予才申请」在两处逐字相同、
 * ReminderReceiver 又抄了第三份只查不申请的；日历的「两个都授予了吗」两种写法；
 * `arrayOf(READ_CALENDAR, WRITE_CALENDAR)` 请求数组散在三个 Activity 里。
 *
 * 这里刻意用裸数字断言（0 = 授予 / -1 = 拒绝），不引 [android.content.pm.PackageManager]：
 * 纯 JVM 单测只跑得到 android.jar 的桩，走框架常量会让断言变成「桩返回什么就是什么」。
 */
class PermissionGateCoreTest {

    private val granted = PermissionGateCore.GRANTED
    private val denied = PermissionGateCore.DENIED

    @Test
    fun `GRANTED 与 DENIED 是 PackageManager 的字面值`() {
        assertEquals(0, granted)
        assertEquals(-1, denied)
        assertEquals(33, PermissionGateCore.SDK_POST_NOTIFICATIONS)
    }

    // ---------------- needsPostNotifications ----------------

    @Test
    fun `13 且未授予需要申请`() {
        assertTrue(PermissionGateCore.needsPostNotifications(33, denied))
        assertTrue(PermissionGateCore.needsPostNotifications(34, denied))
    }

    @Test
    fun `13 且已授予不再申请`() {
        assertFalse(PermissionGateCore.needsPostNotifications(33, granted))
        assertFalse(PermissionGateCore.needsPostNotifications(35, granted))
    }

    @Test
    fun `12 及以下恒不申请即使是未授予`() {
        // Android 12 及以下 POST_NOTIFICATIONS 不是运行时权限，结果恒为授予；
        // 只看结果会在低版本上把「已授予」误判成「要申请」，弹出系统永远不理的请求。
        assertFalse(PermissionGateCore.needsPostNotifications(32, denied))
        assertFalse(PermissionGateCore.needsPostNotifications(26, denied))
        assertFalse(PermissionGateCore.needsPostNotifications(0, denied))
    }

    @Test
    fun `12 及以下已授予也不申请`() {
        assertFalse(PermissionGateCore.needsPostNotifications(32, granted))
        assertFalse(PermissionGateCore.needsPostNotifications(26, granted))
    }

    @Test
    fun `结果不是 0 与非 0 两种以外的值也算未授予`() {
        // 某些 ROM 会返回 -2 / 1 之类的自定义码；判定是「不等于 0」而不是「等于 -1」。
        assertTrue(PermissionGateCore.needsPostNotifications(33, -2))
        assertTrue(PermissionGateCore.needsPostNotifications(33, 1))
    }

    @Test
    fun `与旧的内联判断逐条一致`() {
        // 旧写法：Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU &&
        //         checkSelfPermission(...) != PackageManager.PERMISSION_GRANTED
        fun legacy(sdk: Int, result: Int) = sdk >= 33 && result != 0
        for (sdk in listOf(0, 26, 30, 32, 33, 34, 35)) {
            for (result in listOf(-2, -1, 0, 1)) {
                assertEquals(
                    "sdk=" + sdk + " result=" + result,
                    legacy(sdk, result),
                    PermissionGateCore.needsPostNotifications(sdk, result)
                )
            }
        }
    }

    // ---------------- isGranted / allGranted ----------------

    @Test
    fun `isGranted 只认 0`() {
        assertTrue(PermissionGateCore.isGranted(granted))
        assertFalse(PermissionGateCore.isGranted(denied))
        assertFalse(PermissionGateCore.isGranted(-2))
        assertFalse(PermissionGateCore.isGranted(1))
    }

    @Test
    fun `allGranted 全授予才算授予`() {
        assertTrue(PermissionGateCore.allGranted(intArrayOf(granted, granted)))
        assertFalse(PermissionGateCore.allGranted(intArrayOf(granted, denied)))
        assertFalse(PermissionGateCore.allGranted(intArrayOf(denied, granted)))
    }

    @Test
    fun `allGranted 空结果返 false`() {
        // 空 IntArray = 回调里没有结果（权限弹窗被系统直接关掉），
        // 当成授予会让「没批」被记成「批了」—— 旧实现的 isNotEmpty() 就是这个意思。
        assertFalse(PermissionGateCore.allGranted(intArrayOf()))
    }

    @Test
    fun `allGranted 单个元素等价于 isGranted`() {
        for (r in listOf(-2, -1, 0, 1)) {
            assertEquals(
                "result=" + r,
                PermissionGateCore.isGranted(r),
                PermissionGateCore.allGranted(intArrayOf(r))
            )
        }
    }

    @Test
    fun `allGranted 与旧的 isNotEmpty 加 all 写法逐条一致`() {
        fun legacy(results: IntArray) = results.isNotEmpty() && results.all { it == 0 }
        val cases = listOf(
            intArrayOf(),
            intArrayOf(0),
            intArrayOf(-1),
            intArrayOf(0, 0),
            intArrayOf(0, -1),
            intArrayOf(-1, -1),
            intArrayOf(1, 0)
        )
        for (c in cases) {
            assertEquals(
                "case=" + c.joinToString(","),
                legacy(c),
                PermissionGateCore.allGranted(c)
            )
        }
    }

    // ---------------- 权限数组 ----------------

    @Test
    fun `calendarPermissions 是读写一对且 READ 在前`() {
        assertArrayEquals(
            arrayOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            PermissionGateCore.calendarPermissions()
        )
    }

    @Test
    fun `postNotificationPermissions 只有一条 POST_NOTIFICATIONS`() {
        assertArrayEquals(
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            PermissionGateCore.postNotificationPermissions()
        )
    }

    @Test
    fun `权限数组每次返回新实例`() {
        // 否则调用方 requestPermissions 后再改数组会污染下一次请求
        val a = PermissionGateCore.calendarPermissions()
        val b = PermissionGateCore.calendarPermissions()
        assertFalse("每次调用必须给新数组", a === b)
        a[0] = "tampered"
        assertEquals("android.permission.READ_CALENDAR", b[0])
    }
}
