// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject

/**
 * 端上「排队任务」的纯逻辑：状态归一化 + 上报报文构造。
 *
 * 为什么单独抽成无 Android 依赖的 core：排队任务是**给服务端看**的对外口径
 * （服务端 GET /queue 按它分组、按它统计），一旦口径漂移，端上和控制台就会
 * 对不上（控制台显示 3 个排队、服务端其实是 2 个这类）。
 * 所以把「非法枚举退回默认值 / 超长截断 / 同 id 去重 / 排序」全部固化在这里，
 * JVM 单测直接覆盖，不依赖设备与 WorkManager。
 *
 * 口径与 server/storage.py 的 QUEUE_* 常量一一对应：
 *  - state ∈ {queued, running, failed, done}
 *  - kind  ∈ {analysis, sync, model, other}
 */
internal object QueueStateCore {

    /** 排队中（已入队，尚未开始执行）。 */
    const val STATE_QUEUED = "queued"

    /** 运行中。 */
    const val STATE_RUNNING = "running"

    /** 失败。 */
    const val STATE_FAILED = "failed"

    /** 已完成。 */
    const val STATE_DONE = "done"

    /** 分析类任务。 */
    const val KIND_ANALYSIS = "analysis"

    /** 同步类任务。 */
    const val KIND_SYNC = "sync"

    /** 模型类任务（模型下载/加载）。 */
    const val KIND_MODEL = "model"

    /** 其它。 */
    const val KIND_OTHER = "other"

    /** task_id 长度上限（与服务端 QUEUE_TASK_ID_MAX_LEN 一致）。 */
    const val MAX_TASK_ID_LEN = 128

    /** 标题长度上限（与服务端 QUEUE_TITLE_MAX_LEN 一致）。 */
    const val MAX_TITLE_LEN = 256

    /** 详情长度上限（与服务端 QUEUE_DETAIL_MAX_LEN 一致）。 */
    const val MAX_DETAIL_LEN = 512

    /** 空标题的占位名。 */
    const val DEFAULT_TITLE = "未命名任务"

    /** 展示顺序：先看正在动的，再看排队的，最后才是失败与已完成。 */
    private val STATE_ORDER = listOf(STATE_RUNNING, STATE_QUEUED, STATE_FAILED, STATE_DONE)

    /** 合法的任务类型。 */
    private val KINDS = setOf(KIND_ANALYSIS, KIND_SYNC, KIND_MODEL, KIND_OTHER)

    /**
     * 一条排队任务。
     *
     * @param taskId 业务稳定标识（同一任务重复上报幂等覆盖）
     * @param kind 任务类型（analysis/sync/model/other）
     * @param state 状态（queued/running/failed/done）
     * @param queuedAt 入队时间（毫秒）
     */
    data class QueueTask(
        val taskId: String,
        val kind: String,
        val title: String,
        val state: String,
        val detail: String,
        val queuedAt: Long
    )

    /** 状态归一化：非法值退回「排队中」。 */
    fun normalizeState(raw: String?): String {
        val v = raw?.trim() ?: ""
        return if (v in STATE_ORDER) v else STATE_QUEUED
    }

    /** 类型归一化：非法值退回「其它」。 */
    fun normalizeKind(raw: String?): String {
        val v = raw?.trim() ?: ""
        return if (v in KINDS) v else KIND_OTHER
    }

    /**
     * 归一化一批任务：
     *  - 空 / 超长 task_id 直接丢弃（没有稳定标识的任务无法幂等上报）；
     *  - 非法 kind/state 退回默认值；
     *  - 空标题给占位名，超长标题/详情截断；
     *  - 负的 queued_at 归零；
     *  - 同 task_id 只留一条（后到的覆盖先到的）；
     *  - 排序：状态展示序 -> queued_at 升序 -> task_id。
     */
    fun normalize(raw: List<QueueTask>): List<QueueTask> {
        val byId = LinkedHashMap<String, QueueTask>()
        for (t in raw) {
            val id = t.taskId.trim()
            if (id.isEmpty() || id.length > MAX_TASK_ID_LEN) continue
            val title = t.title.trim().let {
                val base = if (it.isEmpty()) DEFAULT_TITLE else it
                if (base.length > MAX_TITLE_LEN) base.substring(0, MAX_TITLE_LEN) else base
            }
            val detail = if (t.detail.length > MAX_DETAIL_LEN) {
                t.detail.substring(0, MAX_DETAIL_LEN)
            } else {
                t.detail
            }
            val queuedAt = if (t.queuedAt < 0) 0L else t.queuedAt
            byId[id] = QueueTask(
                taskId = id,
                kind = normalizeKind(t.kind),
                title = title,
                state = normalizeState(t.state),
                detail = detail,
                queuedAt = queuedAt
            )
        }
        return byId.values.sortedWith(
            compareBy(
                { STATE_ORDER.indexOf(it.state) },
                { it.queuedAt },
                { it.taskId }
            )
        )
    }

    /**
     * 构造上报报文（与服务端 POST /queue 的契约一致）：
     * {"device_id": "...", "tasks": [{"task_id","kind","title","state","detail","queued_at"}]}
     *
     * 为什么手拼字符串而不用 JSONObject.put 链：org.json 的字段顺序取决于底层 Map
     * 实现——Android 平台版是 LinkedHashMap（保序），而纯 JVM 单测里的 org.json:json
     * 是 HashMap（不保序）。两端同一份代码吐出的字段顺序会不一致，服务端做快照
     * diff 时全是噪声。字段顺序是契约的一部分，所以这里手拼：键的顺序写死，
     * 字符串转义仍然交给 org.json 的 JSONObject.quote（两套实现行为一致）。
     */
    fun buildPayload(deviceId: String, tasks: List<QueueTask>): String {
        val sb = StringBuilder()
        sb.append("{\"device_id\":").append(JSONObject.quote(deviceId))
        sb.append(",\"tasks\":[")
        for ((i, t) in tasks.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"task_id\":").append(JSONObject.quote(t.taskId))
            sb.append(",\"kind\":").append(JSONObject.quote(t.kind))
            sb.append(",\"title\":").append(JSONObject.quote(t.title))
            sb.append(",\"state\":").append(JSONObject.quote(t.state))
            sb.append(",\"detail\":").append(JSONObject.quote(t.detail))
            sb.append(",\"queued_at\":").append(t.queuedAt)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 由随机种子派生设备标识：android-xxxxxxxx（8 位十六进制）。
     *
     * 不用硬件标识（IMEI/ANDROID_ID）——那既拿不到也涉及隐私；
     * 种子随机生成后持久化，卸载重装即失效。
     */
    fun deriveDeviceId(seed: String): String {
        var h = -0x7ee3623bL // FNV-1a offset basis
        for (ch in seed) {
            h = h xor ch.code.toLong()
            h *= 0x1000193L
        }
        val hex = java.lang.Long.toHexString(h and 0xffffffffL)
        return "android-" + hex.padStart(8, '0')
    }
}
