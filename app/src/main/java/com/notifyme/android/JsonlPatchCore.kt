// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File

/**
 * JSONL 文件的「逐行打补丁后原子重写」内核（纯文件操作 + 解析/序列化回调，无 Android 依赖，
 * 可 JVM 单测）。
 *
 * 为什么抽：AnalysisRejectFile（analysis.jsonl 的隔离标记）与 AdvisorRejectFile
 * （advisor_reports.jsonl 的隔离标记）此前是两份几乎逐行复制的实现，差异只在
 * 「解析哪个 schema、按哪个字段匹配」；而这段代码的四条口径又都容易写错：
 *  1. 整体读入 -> 逐行判定 -> 原子重写（临时文件 + fsync + rename，见 JsonlStore.overwrite）；
 *  2. 只有真正变化的行才被改写，其余行原样保留；
 *  3. 解析不出来的损坏行原样保留 —— 绝不因为一次打补丁丢掉用户数据；
 *  4. 序列化失败（例如旧记录字段缺失让浮点变成 NaN，org.json 拒收）也保留原行，
 *     既不丢数据，也不让整轮上报崩掉。
 * 现在这四条只有一份实现，两边的文件级类只留 schema 适配。
 *
 * 匹配口径：target 为 null 表示「全文件扫一遍」（reviveExpired 这类按时间条件而不是按
 * id 复活的场景）；非 null 时只有 keyOf 命中集合的行才会进入 patch。同一个 key 出现多行
 * （重复记录）时每一行都会被打补丁，与旧实现一致。
 *
 * 「没有变化就不碰文件」是硬口径：不改 mtime、不触发下游的字节游标失效
 * （AnalysisStore.bumpGeneration / AdvisorStore 的扫描偏移都靠重写结果判断）。
 */
internal object JsonlPatchCore {

    /**
     * 逐行打补丁后原子重写。
     *
     * @param file 目标 JSONL 文件；不存在时直接返回 false（不创建空文件）
     * @param target 命中的业务键集合；null = 全文件扫
     * @param keyOf 从记录里取业务键
     * @param parse 单行 -> 记录；抛异常或返回 null 一律当损坏行处理
     * @param encode 记录 -> 单行 JSON；抛异常时保留原行
     * @param patch 命中记录的改写函数
     * @return 文件是否被重写（false = 没有任何行发生变化，文件未被触碰）
     */
    fun <T> patch(
        file: File,
        target: Set<String>?,
        keyOf: (T) -> String,
        parse: (String) -> T?,
        encode: (T) -> String,
        patch: (T) -> T
    ): Boolean {
        if (!file.exists()) return false
        val out = ArrayList<String>()
        var changed = false
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) {
                out += line
                continue
            }
            val record = try {
                parse(line)
            } catch (e: Exception) {
                null
            }
            if (record == null || (target != null && keyOf(record) !in target)) {
                out += line
                continue
            }
            val updated = patch(record)
            if (updated == record) {
                out += line
                continue
            }
            val encoded = try {
                encode(updated)
            } catch (e: Exception) {
                null
            }
            if (encoded == null) {
                // 序列化失败：保留原行。丢掉它等于销毁用户数据，
                // 而重写成一个缺字段的记录又会让下一次 fromJson 再炸一遍
                out += line
                continue
            }
            out += encoded
            changed = true
        }
        if (changed) JsonlStore.atomicWrite(file, out)
        return changed
    }

    /**
     * 扫全文件，收集满足 [include] 的记录的业务键（去重、按文件顺序）。
     *
     * 用于「当前被隔离的 id 集合」这类只读聚合：损坏行与空行跳过，不重写文件。
     */
    fun <T> collect(
        file: File,
        keyOf: (T) -> String,
        parse: (String) -> T?,
        include: (T) -> Boolean
    ): Set<String> {
        if (!file.exists()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val record = try {
                parse(line)
            } catch (e: Exception) {
                null
            } ?: continue
            if (include(record)) out += keyOf(record)
        }
        return out
    }
}
