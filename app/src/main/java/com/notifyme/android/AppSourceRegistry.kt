// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 通知里的一条消息（MessagingStyle 展开后的单项）。
 *
 * @param sender    发言人显示名；私聊/无 Person 信息时可能为空
 * @param text      消息正文
 * @param timestamp 消息自带时间戳（毫秒），≤0 表示系统没给，需要回落到通知发布时间
 */
data class RawStyleMessage(
    val sender: String,
    val text: String,
    val timestamp: Long
)

/**
 * 一条通知的原始素材：从 [android.service.notification.StatusBarNotification] 抽出后就是纯数据，
 * 因此「怎么解析成入库消息」这段逻辑可以在 JVM 单测里直接跑，不需要 Robolectric。
 *
 * @param pkg           发出通知的包名
 * @param appLabel      应用显示名（拿不到时为空，解析器会回落到包名）
 * @param title         `Notification.EXTRA_TITLE`，通常就是会话名
 * @param text          `Notification.EXTRA_TEXT`，折叠态正文（可能被系统截断）
 * @param postTime      通知发布时间（毫秒）
 * @param bigText       `Notification.EXTRA_BIG_TEXT`，展开态全文
 * @param subText       `Notification.EXTRA_SUB_TEXT`，副标题（账号名等）
 * @param styleMessages MessagingStyle 展开的消息列表；空表示这条通知不是聊天式通知
 */
data class RawNotification(
    val pkg: String,
    val appLabel: String = "",
    val title: String,
    val text: String,
    val postTime: Long,
    val bigText: String = "",
    val subText: String = "",
    val styleMessages: List<RawStyleMessage> = emptyList()
)

/** 把一条原始通知解析成入库消息；返回 null 表示这条通知不该入库（内容为空等）。 */
fun interface NotificationParser {
    fun parse(raw: RawNotification): ChatMessage?
}

/**
 * 无障碍直读配置：只有控件资源 id 足够稳定的 App 才配得起，其余 App 没有稳定 id，
 * 注册表里 `a11yConfig == null` 即表示「不做 a11y 直读」。
 *
 * @param titleViewIds 聊天页标题控件 id 候选，按优先级逐个尝试（防版本碎裂）
 * @param selfTitles   标题启发式里要排除的 App 自身名字（否则会把「微信」当成会话名）
 */
data class A11yConfig(
    val titleViewIds: List<String>,
    val selfTitles: Set<String> = emptySet()
)

/**
 * 一个「应用源」：本项目从微信专用工具升级为通用通知管理后，微信只是注册表里的第一个源。
 *
 * @param pkg        包名（会话复合键 [ConvKey] 的前半段）
 * @param label      展示名（UI 里的应用列/筛选 chips 用；未收录的 App 运行时向系统查）
 * @param parser     通知 → [ChatMessage] 的解析规则
 * @param a11yConfig 无障碍直读配置，null = 不支持直读
 */
data class AppSource(
    val pkg: String,
    val label: String,
    val parser: NotificationParser,
    val a11yConfig: A11yConfig? = null
)

/**
 * 微信解析器：与改造前 `WeChatNotificationListener.parseMessage` 的语义逐条对齐
 * （MessagingStyle 有消息 → 群聊取最后一条；否则 → 私聊用 title/text），
 * 只是把「取 Android 类型」与「决定字段含义」拆开了，前者留在监听器里。
 */
internal object WeChatNotificationParser : NotificationParser {

    override fun parse(raw: RawNotification): ChatMessage? {
        val style = raw.styleMessages.lastOrNull()
        return if (style != null) {
            val text = style.text.trim()
            if (text.isEmpty()) return null
            ChatMessage(
                sender = style.sender.ifEmpty { raw.title },
                text = text,
                timestamp = if (style.timestamp > 0) style.timestamp else raw.postTime,
                conversation = raw.title,
                isGroup = true,
                pkg = raw.pkg,
                appLabel = raw.appLabel
            )
        } else {
            val text = raw.text.trim()
            if (text.isEmpty()) return null
            ChatMessage(
                sender = raw.title,
                text = text,
                timestamp = raw.postTime,
                conversation = raw.title,
                isGroup = false,
                pkg = raw.pkg,
                appLabel = raw.appLabel
            )
        }
    }
}

/**
 * 兜底解析器：任何 App 的通知都能入库，不需要为该 App 写专门规则。
 *
 * 取舍：
 *  - 正文优先级 bigText > text > subText——折叠态 `EXTRA_TEXT` 常被系统截断，
 *    展开态全文更适合送去分析；两者都空才用副标题（账号名之类）兜底；
 *  - 会话名用 title，为空时回落 appLabel，再回落 pkg（宁可显示包名也不丢消息）；
 *  - 群聊判定是启发式：发言人名与会话名不同 → 认为标题是群名（短信/Telegram 群、
 *    邮件列表都符合这个形态），相同或无发言人 → 按私聊处理。
 */
internal object GenericNotificationParser : NotificationParser {

    override fun parse(raw: RawNotification): ChatMessage? {
        val conversation = raw.title.ifBlank { raw.appLabel }.ifBlank { raw.pkg }
        val style = raw.styleMessages.lastOrNull()
        if (style != null) {
            val text = style.text.trim()
            if (text.isEmpty()) return null
            val sender = style.sender.ifEmpty { conversation }
            return ChatMessage(
                sender = sender,
                text = text,
                timestamp = if (style.timestamp > 0) style.timestamp else raw.postTime,
                conversation = conversation,
                isGroup = sender != conversation,
                pkg = raw.pkg,
                appLabel = raw.appLabel
            )
        }

        val text = raw.bigText.ifBlank { raw.text }.ifBlank { raw.subText }.trim()
        if (text.isEmpty()) return null
        return ChatMessage(
            sender = conversation,
            text = text,
            timestamp = raw.postTime,
            conversation = conversation,
            isGroup = false,
            pkg = raw.pkg,
            appLabel = raw.appLabel
        )
    }
}

/**
 * 企业 IM 解析器（飞书 / 钉钉）：这两家的群通知普遍是「标题=群名，正文=`发言人：内容`」，
 * 而系统并不总给出 MessagingStyle，所以要把发言人从正文前缀里切出来，
 * 否则分析 prompt 里会出现「群名说了一句话」这种错误归属。
 *
 * 切前缀的保守条件（任何一条不满足就当普通正文，不切）：
 *  - 分隔符只认全角「：」或半角「: 」（冒号+空格）；
 *  - 前缀长度 ≤ [MAX_SENDER_PREFIX_LEN]，且不含换行；
 *  - 前缀与会话名不同（相同说明这就是私聊，标题已经是发言人）；
 *  - 切完的正文非空。
 *
 * 已知误判面：正文本身以「提醒：」「注意:」开头时会被当成发言人。
 * 这是归属展示层面的偏差，不影响入库与去重；确认某个源误判率高，
 * 就把它的 parser 换回 [GenericNotificationParser]。
 */
internal object PrefixImParser : NotificationParser {

    private const val MAX_SENDER_PREFIX_LEN = 20

    /** 全角冒号优先：中文 IM 的群消息前缀基本是全角。 */
    private val SEPARATORS = listOf("：", ": ")

    override fun parse(raw: RawNotification): ChatMessage? {
        val conversation = raw.title.ifBlank { raw.appLabel }.ifBlank { raw.pkg }

        // 系统给了 MessagingStyle 就直接用，比前缀猜测可靠
        val style = raw.styleMessages.lastOrNull()
        if (style != null) {
            val text = style.text.trim()
            if (text.isEmpty()) return null
            val sender = style.sender.ifEmpty { conversation }
            return ChatMessage(
                sender = sender,
                text = text,
                timestamp = if (style.timestamp > 0) style.timestamp else raw.postTime,
                conversation = conversation,
                isGroup = sender != conversation,
                pkg = raw.pkg,
                appLabel = raw.appLabel
            )
        }

        val body = raw.bigText.ifBlank { raw.text }.ifBlank { raw.subText }.trim()
        if (body.isEmpty()) return null

        val split = splitSenderPrefix(body)
        val sender = split?.first?.takeIf { it.isNotEmpty() && it != conversation }
        return ChatMessage(
            sender = sender ?: conversation,
            text = split?.second ?: body,
            timestamp = raw.postTime,
            conversation = conversation,
            // 切出发言人 → 群聊；否则按私聊（标题即对方昵称）
            isGroup = sender != null,
            pkg = raw.pkg,
            appLabel = raw.appLabel
        )
    }

    /** 返回 (发言人, 正文)；不符合保守条件时返回 null。 */
    private fun splitSenderPrefix(body: String): Pair<String, String>? {
        for (sep in SEPARATORS) {
            val idx = body.indexOf(sep)
            if (idx <= 0 || idx > MAX_SENDER_PREFIX_LEN) continue
            val prefix = body.substring(0, idx).trim()
            val rest = body.substring(idx + sep.length).trim()
            if (prefix.isEmpty() || rest.isEmpty()) continue
            if (prefix.contains('\n') || prefix.contains('\r')) continue
            return prefix to rest
        }
        return null
    }
}

/**
 * 应用源注册表：包名 → 解析规则。
 *
 * 已收录源＝有针对性的解析规则（微信＝MessagingStyle 语义，飞书/钉钉＝发言人前缀），
 * 其余 App 一律走 [GenericNotificationParser]，因此新增一个应用源不需要改监听器代码。
 * 三个包名都在真机上用 `adb shell pm list packages` 核对过（不是凭记忆猜的）。
 */
object AppSourceRegistry {

    /** 微信包名：也是老数据（schema v1，没有 pkg 字段）的默认归属。 */
    const val PKG_WECHAT = "com.tencent.mm"

    const val LABEL_WECHAT = "微信"

    /** 飞书（Lark）国内版包名。 */
    const val PKG_FEISHU = "com.ss.android.lark"

    const val LABEL_FEISHU = "飞书"

    /** 钉钉包名。 */
    const val PKG_DINGTALK = "com.alibaba.android.rimet"

    const val LABEL_DINGTALK = "钉钉"

    /**
     * 永不入库的包：自身（本 App 自己的提醒通知会造成回环）、系统、系统 UI。
     * 用 BuildConfig 而非硬编码，保证 beta flavor 若加了 applicationIdSuffix 也照样生效。
     */
    private val BLOCKED_PKGS = setOf(BuildConfig.APPLICATION_ID, "android", "com.android.systemui")

    /** 微信各版本聊天页标题控件 id（原先散在 WeChatA11yExtractService 里，M2 收进配置）。 */
    private val WECHAT_TITLE_IDS = listOf(
        "com.tencent.mm:id/obn",
        "com.tencent.mm:id/kk",
        "com.tencent.mm:id/j3t",
        "com.tencent.mm:id/gas",
        "com.tencent.mm:id/action_bar_title"
    )

    val weChatSource = AppSource(
        pkg = PKG_WECHAT,
        label = LABEL_WECHAT,
        parser = WeChatNotificationParser,
        a11yConfig = A11yConfig(
            titleViewIds = WECHAT_TITLE_IDS,
            selfTitles = setOf(LABEL_WECHAT)
        )
    )

    /**
     * 飞书 / 钉钉：通知解析走 [PrefixImParser]，a11y 直读暂时 null——
     * 这两家的聊天页控件 id 还没在真机上取证，宁可不直读也不猜 id（猜错只会静默失败）。
     * 取证方法见 docs/ROADMAP.md M2「a11y 直读扩展」：打开聊天页后
     * `adb shell uiautomator dump` 找标题控件的 resource-id，确认后填进来即可。
     */
    val feishuSource = AppSource(PKG_FEISHU, LABEL_FEISHU, PrefixImParser)

    val dingTalkSource = AppSource(PKG_DINGTALK, LABEL_DINGTALK, PrefixImParser)

    /** 已收录源；未收录的 App 由 [parserFor] 回落到通用解析器。 */
    private val sources: List<AppSource> = listOf(weChatSource, feishuSource, dingTalkSource)

    /** 内置源快照（UI 的「通知来源」列表用它打底，再加上实际收到过通知的 App）。 */
    fun knownSources(): List<AppSource> = sources

    /** 该包名的通知是否永不入库（自身/系统通知）。 */
    fun isBlocked(pkg: String): Boolean = pkg in BLOCKED_PKGS

    /** 已收录源，未收录返回 null。 */
    fun sourceFor(pkg: String): AppSource? = sources.firstOrNull { it.pkg == pkg }

    /** 该包名的解析器：已收录用专属规则，其余用通用兜底。 */
    fun parserFor(pkg: String): NotificationParser =
        sourceFor(pkg)?.parser ?: GenericNotificationParser

    /** 该包名是否支持无障碍直读（目前只有微信）。 */
    fun a11yConfigFor(pkg: String): A11yConfig? = sourceFor(pkg)?.a11yConfig

    /** 注册表内置的展示名；未收录返回 null，调用方再向 PackageManager 查或回落包名。 */
    fun labelFor(pkg: String): String? = sourceFor(pkg)?.label
}
