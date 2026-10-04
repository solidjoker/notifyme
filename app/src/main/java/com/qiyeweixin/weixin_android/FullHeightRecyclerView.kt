// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 全高展开的 RecyclerView：强制按 UNSPECIFIED 测量高度。
 *
 * 背景：首页把 RecyclerView(wrap_content, nestedScrollingEnabled=false) 放进 ScrollView，
 * 期望它测量出全部 item 的总高、滚动统一交给外层。但部分固件/场景下传入的高度 spec
 * 会被压成「视口剩余空间」（实测 14 个会话只量出 2 行高，且 nestedScrollingEnabled=false
 * 导致内外都滚不动）。这里忽略父级给的高度 spec，固定以 UNSPECIFIED 重新测量，
 * 保证 auto-measure 铺出全部子项；宽度 spec 保持原样。
 */
class FullHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val expandedHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        super.onMeasure(widthSpec, expandedHeightSpec)
    }
}
