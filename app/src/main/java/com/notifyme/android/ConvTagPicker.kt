// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 会话标签选择弹窗（M19）：主页会话卡与会话详情页共用一套文案与配色，
 * 避免两处各自拼字符串导致同一个标签两种说法。
 */
object ConvTagPicker {

    /** 标签名（不含 emoji）。 */
    fun label(context: Context, tag: ConvTag): String = context.getString(
        when (tag) {
            ConvTag.TODO -> R.string.conv_tag_todo
            ConvTag.TRACK -> R.string.conv_tag_track
            ConvTag.DONE -> R.string.conv_tag_done
            ConvTag.NOISE -> R.string.conv_tag_noise
            ConvTag.NONE -> R.string.conv_tag_none
        }
    )

    /** 徽标文案「🟠 待办」；未标记为空串。 */
    fun badgeText(context: Context, tag: ConvTag): String =
        ConvTagCore.badgeText(tag, label(context, tag))

    /** 标签配色：待办橙、跟踪蓝、已办结绿、无关灰。 */
    fun color(context: Context, tag: ConvTag): Int =
        context.getColor(
            when (tag) {
                ConvTag.TODO -> R.color.accent_orange
                ConvTag.TRACK -> R.color.status_info
                ConvTag.DONE -> R.color.status_ok
                ConvTag.NOISE, ConvTag.NONE -> R.color.status_gray
            }
        )

    /**
     * 弹出五档单选；选中即落库并回调（调用方负责刷新视图）。
     * [onChanged] 在 UI 线程回调，参数是刚落库的标签。
     */
    fun show(activity: Activity, key: ConvKey, onChanged: (ConvTag) -> Unit) {
        val order = ConvTagCore.PICKER_ORDER
        val current = ConvTagStore.tagOf(activity, key)
        val density = activity.resources.displayMetrics.density
        // 行内点击要关掉弹窗，故先声明再赋值（Kotlin 不允许前向引用 val）
        var dialog: AlertDialog? = null
        fun pad(v: View, l: Int, t2: Int, r: Int, b: Int) {
            v.setPadding((l * density).toInt(), (t2 * density).toInt(), (r * density).toInt(), (b * density).toInt())
        }
        // 说明文字与选项一起放进自定义视图：AlertDialog 同时 setMessage + setSingleChoiceItems 时，
        // 部分主题下选项面板会被消息面板挤掉（真机实测只剩说明文字和取消），故不用框架的列表槽位。
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.conv_tag_dialog_desc)
                textSize = 13f
                setTextColor(activity.getColor(R.color.text_secondary))
                pad(this, 20, 4, 20, 10)
            })
            order.forEachIndexed { index, tag ->
                val suffix = when {
                    ConvTagCore.keepsAnalyzing(tag) -> activity.getString(R.string.conv_tag_keep)
                    ConvTagCore.stopsAnalyzing(tag) -> activity.getString(R.string.conv_tag_stop)
                    else -> activity.getString(R.string.conv_tag_follow)
                }
                val name = label(activity, tag)
                addView(TextView(activity).apply {
                    text = (if (tag == ConvTag.NONE) name else badgeText(activity, tag)) + " · " + suffix
                    textSize = 15f
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(R.drawable.btn_secondary)
                    setTextColor(if (tag == current) activity.getColor(R.color.brand_green) else activity.getColor(R.color.text_primary))
                    paint.isFakeBoldText = tag == current
                    pad(this, 20, 12, 20, 12)
                    if (index > 0) {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (6 * density).toInt() }
                    }
                    setOnClickListener {
                        ConvTagStore.set(activity, key, tag)
                        dialog?.dismiss()
                        onChanged(tag)
                    }
                })
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.conv_tag_dialog_title)
            .setView(list)
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
