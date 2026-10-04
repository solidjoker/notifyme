// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备能力判定（M4 任务 3）：决定能否开放 S2（MiniCPM3-4B，2.47 GB）。
 *
 * 4B Q4 模型本体约 2.5 GB，加上 KV/上下文与系统占用，需要足够大的内存与
 * 64 位 ARM 真机；不达条件就不开放，UI 给明确理由而不是加载到一半 OOM。
 */
object LocalDeviceCapabilities {

    /** S2 准入内存门槛：8 GB（与 ROADMAP M4 口径一致）。 */
    private const val S2_MIN_TOTAL_MEM_BYTES = 8L * 1024 * 1024 * 1024

    /**
     * 返回 null 表示可运行；否则为不可运行的原因（已本地化为可读文案由调用方拼）。
     *
     * 架构口径：要求 arm64-v8a。x86_64 模拟器技术上能跑（冒烟已证），
     * 但 S2 准入面向真机——模拟器调试走冒烟页，不受此限。
     */
    fun s2BlockReason(context: Context): String? {
        val isArm64 = Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } == true
        if (!isArm64) {
            // 真机非 arm64（理论上现代机型几乎不存在）与模拟器都先归到这一条
            return "S2（4B）需要 arm64-v8a 真机；当前设备架构不受支持"
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalMem = info?.totalMem ?: 0L
        if (totalMem < S2_MIN_TOTAL_MEM_BYTES) {
            val gb = totalMem / (1024L * 1024 * 1024)
            return "S2（4B）需要 8GB 以上内存；当前设备约 ${gb}GB"
        }
        return null
    }

    fun canRunS2(context: Context): Boolean = s2BlockReason(context) == null
}
