// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器：设备重启后拉起 KeepAliveService 前台保活服务。
 *
 * Android 12+ 限制后台启动前台服务（ForegroundServiceStartNotAllowedException），
 * 此处失败属预期场景，捕获后打日志即可——兜底是微信通知到达时
 * 系统会自动绑定通知监听器，看门狗随后也会把前台服务状态恢复。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
            Log.i(TAG, "开机完成，已请求拉起前台保活服务")
        } catch (e: Exception) {
            // 覆盖 ForegroundServiceStartNotAllowedException（API 31+）与 IllegalStateException
            Log.w(TAG, "开机拉起前台服务被系统限制，等待通知到达触发兜底绑定", e)
        }
    }
}
