// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context

/**
 * 资源字符串带数字占位时的**唯一**取文案方式：[templateText] 而不是 `getString(id, 数字)`。
 *
 * 为什么：`Resources.getString(id, args)` 内部是 `String.format(Configuration.locale)`，
 * 数字形态与小数点跟着系统语言走。真机实测（ar-EG，界面语言切 ar）：
 *  - `getString(R.string.msg_count, 200)` → `٢٠٠ 条`
 *  - `getString(R.string.chip_importance, 2.0)` → `重要度 ٢٫٠`
 *  - `getString(R.string.chip_need_action_no, 10)` → `✓ 无需行动 ١٠%`
 * 同一批文案在中文设备上是 ASCII，到了阿语设备就变成另一套数字 —— 而这些数字在界面里是
 * 权重、概率、条数、分钟阈值，用户要拿它们跟设置项对照，本地数字会造成误读。
 *
 * [templateText] 先取裸模板（`getString(id)` 不带参不会格式化），再交给
 * [LocaleTextCore.format] 用 `Locale.ROOT` 语义代入，数字与小数点恒 ASCII。
 *
 * 契约：
 *  - 模板本身**不改**（`%1$d` / `%1$.1f` 的精度意图留在资源里），只换调用方式；
 *  - 参数顺序仍按模板里的 `%N$`，`format` 按 N 取值，写错顺序不会静默串味；
 *  - 只覆盖数字槽位；`%1$s` 里的文案（如会话名）本来就不受 Locale 影响，但一起走这里也不会变；
 *  - 值为 `null` 时 [LocaleTextCore.format] 代入空串，不抛异常。
 */
internal fun Context.templateText(id: Int, vararg args: Any?): String =
    LocaleTextCore.format(getString(id), args.toList())
