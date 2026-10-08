// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import java.io.File
import java.io.RandomAccessFile

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
     * 从文件尾部倒读最多 [maxLines] 条非空行，按文件顺序（旧 → 新）返回。
     *
     * 为什么需要：只为取「最近 N 条」而调用 [readRawLines] 会把整个文件读进内存
     * 再解析，messages.jsonl 长到几万行时这是 OOM / 卡顿的主要来源。
     *
     * 规则：
     *  - 最多读取 [maxBytes] 字节；这些字节里凑不满 [maxLines] 行且文件更长时，
     *    窗口按 4 倍扩大重读，直到凑满或读到文件头（此时等价于全量读取）；
     *  - 从文件中间开始时，第一条可能是被截断的半行，直接丢弃（该行的内容不完整，
     *    无法解析；多字节字符被切断留下的替换字符也在这一行内）；
     *  - 空行忽略（与 [readRawLines] 同口径）；
     *  - 返回超过 [maxLines] 时只保留最后 [maxLines] 条。
     *
     * 注意：这是**按文件尾部**取行，不是按时间排序取最新；调用方需自行按时间排序。
     * append 语义下文件顺序基本等于时间顺序，故尾部即最近。
     */
    fun readTailLines(maxLines: Int, maxBytes: Long = DEFAULT_TAIL_BYTES): List<String> {
        if (maxLines <= 0 || !file.exists()) return emptyList()
        var window = maxBytes.coerceAtLeast(MIN_TAIL_BYTES)
        while (true) {
            val length = file.length()
            val start = (length - window).coerceAtLeast(0L)
            var lines = readRange(start).split('\n')
            if (start > 0L) lines = lines.drop(1) // 丢掉被截断的半行
            val nonEmpty = lines.filter { it.isNotBlank() }
            if (nonEmpty.size >= maxLines || start == 0L) {
                return if (nonEmpty.size > maxLines) nonEmpty.takeLast(maxLines) else nonEmpty
            }
            window *= 4
        }
    }

    /** 读 [start] 到文件末尾的字节并解码为 UTF-8（文件可能在读的过程中增长，故不假设长度）。 */
    private fun readRange(start: Long): String {
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val size = (raf.length() - start).coerceAtLeast(0L)
            if (size == 0L) return ""
            raf.seek(start)
            val buf = ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            var read = 0
            while (read < buf.size) {
                val n = raf.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
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
        // 原子重写：先写同目录临时文件并 fsync，再 rename 覆盖原文件。
        // 直接 writeText 会先把原文件截断，写到一半被杀/OOM/断电就会丢掉整个文件的
        // 全部历史（messages / analysis / advisor 等都走这里重写）。
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // rename 失败（个别文件系统/权限场景）退化为整体覆盖写，至少不丢原文件
                file.writeText(lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** 尾部读取的默认字节窗口：1 MiB（约数千条消息）。 */
        const val DEFAULT_TAIL_BYTES = 1L * 1024 * 1024

        /** 尾部读取的最小字节窗口，避免调用方传入过小值时反复扩容。 */
        private const val MIN_TAIL_BYTES = 64L * 1024

        /**
         * 原子重写任意 JSONL 文件（临时文件 + fsync + rename + 空列表即删）。
         * 供尚未持有 [JsonlStore] 实例的 store（AnalysisStore / AdvisorStore /
         * ReminderStore）复用同一套落盘口径，避免各自 writeText 截断丢历史。
         */
        fun atomicWrite(file: File, lines: List<String>) {
            JsonlStore(file).overwrite(lines)
        }
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
