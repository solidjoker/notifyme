// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

/**
 * 聊天记录无障碍直读服务（M2 起由微信专用的 WeChatA11yExtractService 改名/扩展而来）。
 *
 * 支持哪些 App 完全由 [AppSourceRegistry] 的 [A11yConfig] 决定：
 * 注册表里 a11yConfig == null 的 App（如尚未取证的飞书/钉钉）即使窗口事件到来也不接管，
 * 宁可不直读也不猜控件 id。
 *
 * 工作流程：
 *  1. 用户在系统设置为本 App 开启无障碍服务；
 *  2. 在目标 App 里打开目标会话，回到控制台选择「提取目标」并点「开始提取」
 *     （置位 startRequested/targetPkg 并跳回目标 App）；
 *  3. 服务收到目标窗口事件后开 worker 线程接管：
 *     识别会话标题 → 循环「解析当前屏 → 向上翻页 → 去重合并」；
 *  4. 连续 [NO_NEW_LIMIT] 屏无新增判定到顶（或用户停止/翻页失败）→ 结束；
 *  5. 时间戳按采集顺序（新→旧）从当前时间每秒递减估算，记录带 source=a11y-extract；
 *  6. 结果经 MessageStore.mergeAndCollect 入库，新增条目挂 PendingQueue 走既有同步链路。
 *
 * 防碎裂（各 App UI 结构随版本漂移）：
 *  - 会话标题：配置的资源 id 候选表 → 标题栏区域文本启发式；
 *  - 消息正文：行内面积最大文本节点（气泡正文）→ 昵称取正文上方小字；
 *  - 整屏零命中时降级「整屏文本行」兜底收集，每级失败都记 logcat（tag=A11yExtract）。
 *
 * 可中断：stopRequested 每屏检查；杀进程/服务断连只是本次提取终止，不影响 App 其它功能。
 */
class A11yExtractService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yExtract"

        /** 入库/上报的来源标记：服务端 messages.source 直接收该值 */
        const val SOURCE_TAG = "a11y-extract"

        /** 连续无新增屏数阈值：达到即判定翻到顶 */
        private const val NO_NEW_LIMIT = 3

        /** 保险丝：最多翻 300 屏，防异常会话死循环 */
        private const val MAX_SCREENS = 300

        /** 翻页后等待内容稳定的时长 */
        private const val SCROLL_WAIT_MS = 700L

        /** 控制台「开始提取」置位；服务在目标窗口事件里消费（一次性） */
        @Volatile
        var startRequested = false
            private set

        /** 控制台「停止」置位；采集循环每屏检查 */
        @Volatile
        var stopRequested = false
            private set

        /** 本次提取的目标包名；窗口事件只接受这个包（DEBUG 自检除外） */
        @Volatile
        var targetPkg: String = AppSourceRegistry.PKG_WECHAT
            private set

        fun requestStart(pkg: String) {
            targetPkg = pkg
            stopRequested = false
            startRequested = true
        }

        fun requestStop() {
            stopRequested = true
            startRequested = false
        }

        /** 本服务是否已在系统无障碍设置中启用（按包名子串判断，覆盖双 flavor 包名） */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.contains(context.packageName)
        }
    }

    /** 提取会话进行中（防重入） */
    @Volatile
    private var running = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !startRequested || running) return
        val pkg = event.packageName?.toString() ?: return
        // DEBUG 包自检：允许事件来自本 App（无真实 IM 会话时验证采集引擎端到端链路）
        val selfTarget = BuildConfig.DEBUG && pkg == packageName
        if (pkg != targetPkg && !selfTarget) return

        // 注册表没配 a11y 的 App 不接管；自检时借微信的标题 id 配置
        val cfg = AppSourceRegistry.a11yConfigFor(pkg)
            ?: if (selfTarget) {
                AppSourceRegistry.a11yConfigFor(AppSourceRegistry.PKG_WECHAT)
            } else null
        if (cfg == null) return

        startRequested = false
        running = true
        thread(name = "a11y-extract") {
            try {
                runExtraction(pkg, cfg)
            } catch (t: Throwable) {
                Log.e(TAG, "提取异常终止", t)
                A11yExtractStore.finish(
                    this,
                    "提取中断：${t.javaClass.simpleName}: ${t.message.orEmpty().take(60)}",
                    0, ""
                )
            } finally {
                running = false
            }
        }
    }

    override fun onInterrupt() {
        stopRequested = true
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }

    // ---------------- 提取主流程 ----------------

    private fun runExtraction(pkg: String, cfg: A11yConfig) {
        Thread.sleep(800) // 等目标窗口稳定
        val excludeTitles = buildExcludeTitles(pkg, cfg)
        val appLabel = excludeTitles.firstOrNull { it.isNotBlank() } ?: pkg
        val root0 = rootInActiveWindow
        val conversation = root0
            ?.let { detectConversationTitle(it, cfg, excludeTitles) }
            .orEmpty()
        if (conversation.isBlank()) {
            A11yExtractStore.finish(
                this,
                "未识别到会话标题：请先在「$appLabel」里打开要提取的会话，再点「开始提取」",
                0, ""
            )
            Log.w(TAG, "标题识别失败，提取取消")
            return
        }
        val convKey = ConvKey(pkg, conversation)
        // 群/私聊标记参考本地已有记录；界面本身拿不到可靠群标记
        val isGroup = MessageStore.readRecent(this, 500)
            .firstOrNull { it.convKey == convKey }?.isGroup ?: false

        // 起始屏必须能找到消息列表，否则说明不在聊天页（如登录页/通讯录），直接中止。
        // 防碎裂的「整屏文本行兜底」只在提取中途结构漂移时启用，不在起始屏启用。
        val probe = rootInActiveWindow
        if (probe == null || findMessageList(probe) == null) {
            A11yExtractStore.finish(
                this,
                "未检测到聊天消息列表：请确认当前停留在「$conversation」的聊天页",
                0, conversation
            )
            Log.w(TAG, "起始屏无消息列表节点，提取取消（当前不在聊天页）")
            return
        }

        Log.i(TAG, "开始提取 $pkg 会话「$conversation」（isGroup=$isGroup）")
        A11yExtractStore.update(this, true, "正在提取「$conversation」…", 0, conversation)

        // ordered：全局消息列表，新消息在头部；seen 做「包+会话+发送者+文本」内存去重
        val ordered = ArrayDeque<ChatMessage>()
        val seen = HashSet<String>()
        var noNewScreens = 0
        var screens = 0
        var stoppedByUser = false

        while (screens < MAX_SCREENS) {
            if (stopRequested) {
                stoppedByUser = true
                break
            }
            val root = rootInActiveWindow
            if (root == null) {
                Thread.sleep(500)
                continue
            }
            val before = seen.size
            collectScreen(root, pkg, conversation, isGroup, ordered, seen)
            screens++
            val addedNow = seen.size - before
            Log.i(TAG, "第 $screens 屏：新增 $addedNow 条，累计 ${seen.size} 条")
            A11yExtractStore.update(
                this, true,
                "正在提取「$conversation」… 已采集 ${seen.size} 条（第 $screens 屏）",
                seen.size, conversation
            )
            noNewScreens = if (addedNow == 0) noNewScreens + 1 else 0
            if (noNewScreens >= NO_NEW_LIMIT) break
            if (!scrollBackward(root)) {
                Log.w(TAG, "翻页失败，按到顶处理")
                break
            }
            Thread.sleep(SCROLL_WAIT_MS)
        }

        // 界面拿不到精确时间戳：以当前时间为锚，按采集顺序（新→旧）每秒递减估算，
        // 保证列表排序自然正确；source 标记避免与真实时间戳来源混淆
        val now = System.currentTimeMillis()
        val messages = ordered.mapIndexed { idx, m ->
            m.copy(timestamp = now - idx * 1000L, source = SOURCE_TAG)
        }
        val added = MessageStore.mergeAndCollect(this, messages)
        added.forEach { PendingQueue.append(this, it) }
        Log.i(TAG, "提取结束：采集 ${messages.size} 条，新增入库 ${added.size} 条")

        val status = if (stoppedByUser) {
            "已停止：本次新增 ${added.size} 条（采集 ${messages.size} 条，会话「$conversation」）"
        } else {
            "提取完成：本次新增 ${added.size} 条（采集 ${messages.size} 条，会话「$conversation」）"
        }
        A11yExtractStore.finish(this, status, added.size, conversation)
    }

    /**
     * 标题识别要排除的名字集合：注册表配置的 selfTitles（内置名）+ PackageManager 实时名。
     * 实时名查不到不影响内置名生效。
     */
    private fun buildExcludeTitles(pkg: String, cfg: A11yConfig): Set<String> {
        val out = cfg.selfTitles.toMutableSet()
        try {
            val pmName = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
            if (pmName.isNotBlank()) out += pmName
        } catch (_: PackageManager.NameNotFoundException) {
            // 包没装：实时名拿不到，沿用配置
        }
        return out
    }

    // ---------------- 会话标题识别 ----------------

    private val timeTextPattern = Regex(".*\\d{1,2}:\\d{2}.*")

    private fun detectConversationTitle(
        root: AccessibilityNodeInfo,
        cfg: A11yConfig,
        excludeTitles: Set<String>
    ): String {
        for (id in cfg.titleViewIds) {
            val t = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { !it.text.isNullOrBlank() }
                ?.text?.toString()?.trim()
            if (!t.isNullOrBlank() && t !in excludeTitles) {
                Log.i(TAG, "标题命中资源 id：$id -> $t")
                return t
            }
        }
        // 启发式：标题栏区域（屏幕顶部 15% 以内）最靠上的有效文本。
        // 取最靠上而非最靠下：标题栏常在标题下方还有提示小字，靠下会误中提示语；
        // 同时要求含字母/数字/汉字，排除「←」这类纯符号按钮
        val screenH = resources.displayMetrics.heightPixels
        val texts = mutableListOf<Pair<String, Rect>>()
        collectTexts(root, texts)
        val title = texts
            .filter { (t, b) ->
                t.isNotBlank() && b.top in 1 until (screenH * 0.15).toInt() &&
                    t.length <= 30 && !timeTextPattern.matches(t) &&
                    t.any { it.isLetterOrDigit() } &&
                    !t.endsWith("%") && t !in excludeTitles
            }
            .minByOrNull { it.second.top }
            ?.first
        if (title != null) Log.i(TAG, "标题启发式命中：$title")
        return title.orEmpty().trim()
    }

    // ---------------- 单屏采集 ----------------

    private val timeSepPattern = Regex(
        ".*(\\d{1,2}:\\d{2}|昨天|星期[一二三四五六日天]|上周|凌晨|上午|中午|下午|晚上|\\d{4}年).*"
    )
    private val voiceDurationPattern = Regex("^\\d+\"")

    /**
     * 解析当前屏消息。列表节点找不到、或首屏零解析时，降级为整屏文本行兜底。
     * 列表内自下而上遍历（底部是最新消息），保证 [ordered] 头部始终是更新的消息。
     */
    private fun collectScreen(
        root: AccessibilityNodeInfo,
        pkg: String,
        conversation: String,
        isGroup: Boolean,
        ordered: ArrayDeque<ChatMessage>,
        seen: HashSet<String>
    ) {
        val target = findMessageList(root)
        if (target == null) {
            // 中途列表消失 = 用户可能切走了页面：本屏按零新增处理，
            // 连续无新增会自然终止循环；不做兜底，避免把别的页面文本当消息
            Log.w(TAG, "本屏未找到消息列表节点，按零新增处理")
            return
        }
        val rowParent = target.rowParent
        val screenW = resources.displayMetrics.widthPixels
        var parsed = 0
        for (i in rowParent.childCount - 1 downTo 0) {
            val row = rowParent.getChild(i) ?: continue
            val msg = parseRow(row, pkg, conversation, isGroup, screenW)
            if (msg != null) {
                val key = "$pkg|${msg.conversation}${msg.sender}|${msg.text}"
                if (seen.add(key)) {
                    ordered.addFirst(msg)
                    parsed++
                }
            }
        }
        if (parsed == 0 && seen.isEmpty()) {
            Log.w(TAG, "列表节点存在但整屏零解析，降级整屏文本行兜底")
            fallbackCollectAllText(root, pkg, conversation, isGroup, ordered, seen)
        }
    }

    /** 消息列表命中结果：scrollNode 用于翻页，rowParent 的直接子项即消息行 */
    private class ListTarget(
        val scrollNode: AccessibilityNodeInfo,
        val rowParent: AccessibilityNodeInfo
    )

    /**
     * 找消息列表。三级匹配（防碎裂）：
     *  1. 首选可滚动的 ListView/RecyclerView（聊天页的标准结构）；
     *  2. 不可滚动的 ListView/RecyclerView：消息不足一屏时列表不滚动，仍是聊天页；
     *  3. 兜底任意可滚动容器：直接子项 ≥2 时自身即行容器；
     *     只有唯一内容子容器时取其内容子容器（ScrollView+LinearLayout 类结构）。
     * 共同门槛：高度超过 1/3 屏、行容器至少 2 个子项，面积最大者胜出。
     */
    private fun findMessageList(root: AccessibilityNodeInfo): ListTarget? {
        val screenH = resources.displayMetrics.heightPixels

        fun bigEnough(n: AccessibilityNodeInfo): Boolean {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r.height() > screenH / 3
        }

        fun area(n: AccessibilityNodeInfo): Int {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r.width() * r.height()
        }

        var bestTyped: AccessibilityNodeInfo? = null
        var bestTypedArea = 0
        var bestTypedNoScroll: AccessibilityNodeInfo? = null
        var bestTypedNoScrollArea = 0
        var bestAny: AccessibilityNodeInfo? = null
        var bestAnyArea = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            val isTypedList = cls.contains("ListView") || cls.contains("RecyclerView")
            if (bigEnough(node)) {
                val a = area(node)
                if (isTypedList && node.childCount >= 2) {
                    if (node.isScrollable && a > bestTypedArea) {
                        bestTyped = node
                        bestTypedArea = a
                    } else if (!node.isScrollable && a > bestTypedNoScrollArea) {
                        // 消息不足一屏时列表不可滚动，仍应识别为聊天页
                        bestTypedNoScroll = node
                        bestTypedNoScrollArea = a
                    }
                }
                if (node.isScrollable && a > bestAnyArea) {
                    bestAny = node
                    bestAnyArea = a
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (bestTyped != null) return ListTarget(bestTyped, bestTyped)
        if (bestTypedNoScroll != null) return ListTarget(bestTypedNoScroll, bestTypedNoScroll)
        val any = bestAny ?: return null
        if (any.childCount >= 2) return ListTarget(any, any)
        if (any.childCount == 1) {
            val content = any.getChild(0)
            if (content != null && content.childCount >= 2) return ListTarget(any, content)
        }
        return null
    }

    /**
     * 解析一行消息：
     *  - 无文本但有图片 → [图片] 占位；
     *  - 整行只有一个时间样式文本 → 时间分隔行，跳过；
     *  - 正文 = 行内面积最大文本；形如 3" 的时长文本 → [语音] 占位；
     *  - 发送者 = 正文上方的小字昵称（群聊）；拿不到时按气泡左右位置推断（右=我，左=对方）。
     */
    private fun parseRow(
        row: AccessibilityNodeInfo,
        pkg: String,
        conversation: String,
        isGroup: Boolean,
        screenW: Int
    ): ChatMessage? {
        val texts = mutableListOf<Pair<String, Rect>>()
        collectTexts(row, texts)
        if (texts.isEmpty()) {
            return if (hasImageNode(row)) {
                ChatMessage(
                    sender = guessSenderByAvatar(row, conversation, screenW),
                    text = "[图片]",
                    timestamp = 0L,
                    conversation = conversation,
                    isGroup = isGroup,
                    pkg = pkg
                )
            } else null
        }
        if (texts.size == 1 && timeSepPattern.matches(texts[0].first) && !hasImageNode(row)) {
            return null // 时间分隔行
        }
        val contentIdx = texts.indices.maxByOrNull {
            texts[it].second.width() * texts[it].second.height()
        } ?: return null
        val content = texts[contentIdx]
        var text = content.first.trim()
        if (voiceDurationPattern.matches(text)) text = "[语音]"
        if (text.isBlank()) return null

        // 昵称：正文上方、短文本、非时间样式
        val nickname = texts.firstOrNull { (t, b) ->
            b != content.second && b.bottom <= content.second.top + 4 &&
                t.isNotBlank() && t.length <= 20 && !timeSepPattern.matches(t)
        }?.first?.trim()
        val sender = when {
            !nickname.isNullOrBlank() -> nickname
            content.second.centerX() > screenW / 2 -> ChatMessage.SENDER_SELF
            else -> conversation
        }
        return ChatMessage(
            sender = sender,
            text = text,
            timestamp = 0L,
            conversation = conversation,
            isGroup = isGroup,
            pkg = pkg
        )
    }

    /** 兜底：整屏文本行逐行收集（标题栏区域除外），发送者留空。 */
    private fun fallbackCollectAllText(
        root: AccessibilityNodeInfo,
        pkg: String,
        conversation: String,
        isGroup: Boolean,
        ordered: ArrayDeque<ChatMessage>,
        seen: HashSet<String>
    ) {
        val screenH = resources.displayMetrics.heightPixels
        val texts = mutableListOf<Pair<String, Rect>>()
        collectTexts(root, texts)
        // 自下而上（屏幕下方更新），与主路径同序
        texts.sortedByDescending { it.second.top }.forEach { (t, b) ->
            val text = t.trim()
            if (text.isBlank() || text.length > 500) return@forEach
            if (b.top < (screenH * 0.15).toInt()) return@forEach
            if (timeSepPattern.matches(text) && text.length <= 20) return@forEach
            val key = "$pkg|$conversation|$text"
            if (seen.add(key)) {
                ordered.addFirst(
                    ChatMessage(
                        sender = "",
                        text = text,
                        timestamp = 0L,
                        conversation = conversation,
                        isGroup = isGroup,
                        pkg = pkg
                    )
                )
            }
        }
    }

    // ---------------- 翻页 ----------------

    /** 向上翻页（看更早的消息）：优先列表 ACTION_SCROLL_BACKWARD，失败用手势下滑兜底 */
    private fun scrollBackward(root: AccessibilityNodeInfo): Boolean {
        val target = findMessageList(root)
        if (target != null &&
            target.scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        ) {
            return true
        }
        Log.w(TAG, "SCROLL_BACKWARD 失败，改用手势下滑翻页")
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val path = Path().apply {
            moveTo(w / 2f, h * 0.35f)
            lineTo(w / 2f, h * 0.75f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------------- 节点工具 ----------------

    /** DFS 收集节点树内全部文本（text 优先，空则 contentDescription）及屏幕坐标 */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>) {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("TextView") || cls.contains("Button")) {
            val t = node.text?.toString().orEmpty()
                .ifBlank { node.contentDescription?.toString().orEmpty() }
            if (t.isNotBlank()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) out += t to r
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out) }
        }
    }

    /** 行内是否有图片类节点（纯图/表情消息无文本时用于占位判定） */
    private fun hasImageNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("ImageView") || cls.contains("ImageButton")) return true
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (hasImageNode(it)) return true }
        }
        return false
    }

    /** 纯图消息的发送者按头像位置推断：头像在右=我，在左=对方 */
    private fun guessSenderByAvatar(
        row: AccessibilityNodeInfo,
        conversation: String,
        screenW: Int
    ): String {
        val cls = row.className?.toString().orEmpty()
        if (cls.contains("ImageView")) {
            val r = Rect()
            row.getBoundsInScreen(r)
            if (!r.isEmpty) {
                return if (r.centerX() > screenW / 2) ChatMessage.SENDER_SELF else conversation
            }
        }
        for (i in 0 until row.childCount) {
            row.getChild(i)?.let { child ->
                val childCls = child.className?.toString().orEmpty()
                if (childCls.contains("ImageView")) {
                    val r = Rect()
                    child.getBoundsInScreen(r)
                    if (!r.isEmpty) {
                        return if (r.centerX() > screenW / 2) ChatMessage.SENDER_SELF else conversation
                    }
                }
            }
        }
        return conversation
    }
}
