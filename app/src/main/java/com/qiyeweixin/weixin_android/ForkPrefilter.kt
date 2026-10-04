// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

/**
 * fork 1 · 廉价本地启发式预筛（零网络、零模型成本）。
 *
 * 在 S1（laya 判定）之前跑一层纯本地规则，把明显不值得分析的窗口就地结案：
 * 系统消息、纯表情/表情标签、广告链接刷屏、窗口太小。
 *
 * prob 语义（forks 数组统一口径，勿改）：「该分叉判为 split/正向的概率」，
 * 对本分叉即「窗口值得进入 S1 分析的概率」。
 * prob < [SHARP_THRESHOLD] -> sharp（就地结案：记录 case 标记 filtered，不调 laya）；
 * 否则 split（进入 fork 2 的 S1 判定）。
 *
 * 约束：只减不增——pre filter 只在「本来要调 laya」的 case 上做拦截，
 * laya/S2 调用次数只会减少，不会增加。
 */
object ForkPrefilter {

    /** sharp 阈值：值得分析的概率低于该值就地结案 */
    const val SHARP_THRESHOLD = 0.15

    /** 分叉判定结果 */
    data class Result(
        /** 值得进入 S1 分析的概率（0-1） */
        val prob: Double,
        /** 判定依据（命中规则说明，未命中为空串） */
        val note: String
    ) {
        val sharp: Boolean get() = prob < SHARP_THRESHOLD
    }

    /** 微信表情标签：[微笑] [Emm] [旺柴] [捂脸] 等 */
    private val EMOJI_TAG = Regex("""\[[^\[\]]{1,8}\]""")

    /** 系统提示语特征（通知栏能捕获到的微信系统消息文案） */
    private val SYSTEM_PATTERNS = listOf(
        "撤回了一条消息",
        "加入了群聊",
        "邀请你加入",
        "以上是打招呼的内容",
        "通过了你的朋友验证请求",
        "你已添加了",
        "对方不是你的好友"
    )

    /** 广告/刷屏关键词（与链接同时出现才算广告，避免误伤正常分享） */
    private val AD_KEYWORDS = listOf(
        "优惠券", "领取", "红包", "点击链接", "抢购", "下单",
        "拼团", "砍价", "助力", "返现", "秒杀", "限时"
    )

    /**
     * 评估一个 case 窗口是否值得进入 S1。
     * 规则为乘性惩罚：初始 1.0，每条命中规则按比例下调。
     */
    fun evaluate(kase: AnalysisCase): Result {
        var prob = 1.0
        val reasons = mutableListOf<String>()
        val msgs = kase.messages

        // 规则 1：窗口消息太少（单条消息几乎没有任务语义）
        if (msgs.size < 2) {
            prob *= 0.3
            reasons += "窗口仅${msgs.size}条"
        }

        // 规则 2-4：逐条判定垃圾消息（纯表情 / 系统消息 / 广告链接）
        var junk = 0
        var emoji = 0
        var system = 0
        var ad = 0
        for (m in msgs) {
            val t = m.text.trim()
            when {
                t.isEmpty() -> { junk++; system++ }
                isSystemLike(t) -> { junk++; system++ }
                isPureEmoji(t) -> { junk++; emoji++ }
                isAdLink(t) -> { junk++; ad++ }
            }
        }
        val junkRatio = junk.toDouble() / msgs.size.coerceAtLeast(1)
        when {
            junkRatio >= 0.8 -> {
                prob *= 0.05
                reasons += "垃圾消息占比${(junkRatio * 100).toInt()}%" +
                    junkBreakdown(emoji, system, ad)
            }
            junkRatio >= 0.5 -> {
                prob *= 0.3
                reasons += "垃圾消息占比${(junkRatio * 100).toInt()}%" +
                    junkBreakdown(emoji, system, ad)
            }
        }

        // 规则 5：整个窗口没有任何字母/数字/汉字（纯表情图片刷屏的兜底）
        val hasSubstance = msgs.any { m -> m.text.any { it.isLetterOrDigit() } }
        if (!hasSubstance) {
            prob = minOf(prob, 0.02)
            reasons += "全窗口无文字内容"
        }

        return Result(prob.coerceIn(0.0, 1.0), reasons.joinToString("；"))
    }

    private fun junkBreakdown(emoji: Int, system: Int, ad: Int): String {
        val parts = mutableListOf<String>()
        if (emoji > 0) parts += "表情$emoji"
        if (system > 0) parts += "系统$system"
        if (ad > 0) parts += "广告$ad"
        return if (parts.isEmpty()) "" else "（${parts.joinToString("/")}）"
    }

    /** 纯表情：剔除 [表情] 标签后没有任何字母/数字/汉字，且长度很短 */
    private fun isPureEmoji(text: String): Boolean {
        if (text.length > 20) return false
        val stripped = EMOJI_TAG.replace(text, "")
        return stripped.none { it.isLetterOrDigit() }
    }

    private fun isSystemLike(text: String): Boolean =
        SYSTEM_PATTERNS.any { text.contains(it) }

    /** 广告链接：带 URL 且命中广告关键词，或整条消息就是裸链接 */
    private fun isAdLink(text: String): Boolean {
        val hasUrl = text.contains("http://") || text.contains("https://")
        if (!hasUrl) return false
        if (AD_KEYWORDS.any { text.contains(it) }) return true
        // 裸链接（去掉 URL 后几乎没剩内容）
        val stripped = text.replace(Regex("""https?://\S+"""), "").trim()
        return stripped.length <= 4
    }
}
