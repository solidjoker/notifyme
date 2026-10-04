// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File

/**
 * JSON Lines 存储的共用文件原语（M1 抽出）。
 *
 * 为什么抽：messages / pending / analysis / reminders / advisor_reports 五个 store
 * 此前各自复制了同一套「追加一行 → 整体读入 → 过滤后整体重写 → 空则删文件」的代码，
 * 差异只在解析与序列化。抽出与格式无关的文件动作后：
 *  - store 的纯逻辑可以用普通 JUnit + 临时目录测（不需要 Context，也不需要 Robolectric）；
 *  - M2 要给 [ChatMessage] 加 `pkg` 字段、做 schema v2 读时补齐迁移时，只有唯一一处入口要改。
 *
 * 格式约定（与既有数据文件完全一致，不改口径）：
 *  - 一行一条 JSON，行尾 `\n`，UTF-8；
 *  - 空行读取时忽略；
 *  - 单行解析失败视为「损坏行」：读取时跳过，[rewriteRaw] 时**原样保留**
 *    （绝不因为一次重写丢掉用户数据）。
 *
 * 线程安全：本类不加锁。锁仍由各 store 的 `@Synchronized` 方法持有（进程内单点写入），
 * 这样既保留原有语义，又让测试能在单线程里直接调用。
 */
internal class JsonlStore(val file: File) {

    fun exists(): Boolean = file.exists()

    /** 删除数据文件（清空语义）；文件不存在时是 no-op。 */
    fun delete() {
        file.delete()
    }

    /** 追加已序列化好的若干行（自动补 `\n`）；空列表不碰文件。 */
    fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
    }

    fun appendLine(line: String) = appendLines(listOf(line))

    /** 全部非空原始行（不解析）。文件不存在返回空列表。 */
    fun readRawLines(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }

    /**
     * 逐行回调（不解析、不整体载入列表）：merge 建去重集这类「只扫一遍」的场景用，
     * 避免大文件在内存里多留一份 List。
     */
    fun forEachRawLine(action: (String) -> Unit) {
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) action(line)
        }
    }

    /**
     * 整体重写落盘 [lines]；列表为空则删除文件
     * （与既有 store 行为一致：队列清空后不留 0 字节文件）。
     */
    fun overwrite(lines: List<String>) {
        if (lines.isEmpty()) {
            delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
    }

    /**
     * 行级过滤重写：[keep] 收到原始行，返回 true 表示保留。
     *
     * 解析与「损坏行怎么办」由调用方决定——本方法只看行，因此调用方可以把
     * 「解析不出来」直接判成保留，损坏行就不会被重写吞掉。
     *
     * @return 实际丢弃的行数；为 0 时不碰文件（避免无谓的整文件重写与 mtime 变化）
     */
    fun rewriteRaw(keep: (String) -> Boolean): Int {
        if (!file.exists()) return 0
        val kept = mutableListOf<String>()
        var removed = 0
        for (line in readRawLines()) {
            if (keep(line)) kept += line else removed++
        }
        if (removed > 0) overwrite(kept)
        return removed
    }
}
