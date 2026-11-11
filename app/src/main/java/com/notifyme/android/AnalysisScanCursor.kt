// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * 分析通道的**增量扫描游标**（纯字节计算 + 文件级扫描，无 Android 依赖，可 JVM 单测）。
 *
 * 背景（第四轮，报告外发现第 2 条）：原来的 [AnalysisStore.readUnsynced] 每轮都从文件头
 * 顺序扫到文件尾 —— 记录一多，每轮就是一次 O(文件大小) 的 IO。
 *
 * 现在维护一个**持久化字节游标**：
 *  - 游标指向「第一条还没 settle 的记录」（未同步、且未被隔离）；
 *    下一轮从那里接着扫，已经 settle 的前缀不再重复读；
 *  - 扫到文件末尾后，按 [FULL_PASS_INTERVAL_MS] 的间隔周期性**从头重扫**（环形），
 *    保证「隔离到期该复活的记录」「被裁剪/改写的记录」不会永远漏掉；
 *  - 文件被重写后旧偏移必然失效（字节布局变了），此时自动从头开始，
 *    不抛异常、不打断上报。
 */
internal object AnalysisScanCursor {

    /** 换行符的字节值（JSONL 一行一条，行尾 \\n）。 */
    const val NEWLINE: Int = 10

    /** 两次「从头重扫」之间的最小间隔：30 分钟。 */
    const val FULL_PASS_INTERVAL_MS = 30L * 60 * 1000

    /** 单行字节上限：超过即当损坏行跳过（不让一行坏数据卡住整条扫描）。 */
    const val MAX_LINE_BYTES = 1024 * 1024

    /**
     * 从 [offset] 起找下一个换行符的下标；没有则返回 -1（末尾残行 / 已到尾部）。
     */
    fun indexOfNewline(buffer: ByteArray, offset: Int): Int {
        if (offset < 0) return -1
        var i = offset
        while (i < buffer.size) {
            if (buffer[i].toInt() == NEWLINE) return i
            i++
        }
        return -1
    }

    /**
     * 读 [offset] 起的一整行（不含行尾换行）：返回「行文本 -> 该行结束后的偏移」。
     * 没有完整行时返回 null。
     */
    fun readLine(buffer: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset < 0 || offset >= buffer.size) return null
        val nl = indexOfNewline(buffer, offset)
        if (nl < 0) return null
        return String(buffer, offset, nl - offset, Charsets.UTF_8) to (nl + 1)
    }

    /**
     * 从 [offset] 起最多消费 [limit] 行，返回消费后的偏移；
     * buffer 内凑不满 [limit] 行（= 已到末尾）时返回 null。
     */
    fun nextOffset(buffer: ByteArray, offset: Int, limit: Int): Int? {
        if (limit <= 0) return offset
        var pos = offset
        var consumed = 0
        while (consumed < limit) {
            val line = readLine(buffer, pos) ?: return null
            pos = line.second
            consumed++
        }
        return pos
    }

    /** 偏移越界（文件被截断/重写）时回落 0：损坏游标不抛异常、不打断上报。 */
    fun normalizeOffset(offset: Long, fileLength: Long): Long =
        if (offset < 0L || offset > fileLength) 0L else offset

    /** 偏移是否落在行首（offset == 0，或前一个字节是换行）。 */
    fun isLineBoundary(buffer: ByteArray, offset: Int): Boolean =
        offset <= 0 || (offset <= buffer.size && buffer[offset - 1].toInt() == NEWLINE)

    /**
     * 下一轮的扫描起点（环形）。
     *
     *  - 本轮没扫到末尾 -> 接着上次的位置往下扫；
     *  - 扫到末尾且距上次「从头重扫」已超过 [fullPassIntervalMs] -> 从头重扫；
     *  - 否则停在末尾（等新记录追加到文件尾，下轮自然被扫到）。
     */
    fun nextStartOffset(
        reachedEnd: Boolean,
        offset: Long,
        lastFullPassAt: Long,
        nowMs: Long,
        fullPassIntervalMs: Long = FULL_PASS_INTERVAL_MS
    ): Long {
        if (!reachedEnd) return offset
        if (lastFullPassAt <= 0L || nowMs - lastFullPassAt >= fullPassIntervalMs) return 0L
        return offset
    }
}


/**
 * 文件级的**增量扫描**（无 Android 依赖，可 JVM 单测）。
 *
 * 语义（第四轮任务 B）：
 *  - 从 [startOffset] 开始按行读，**已同步**（analyzedAt <= lastSyncedAt）与
 *    **已隔离**（quarantined）的记录直接跳过（不返回给调用方，也不阻塞游标）；
 *  - 其余记录（待上报）按文件顺序返回，最多 [maxRecords] 条；
 *  - 下一轮起点 = **首个待上报记录的起始偏移**：这样「上报失败导致水位不前进」
 *    时，下一轮一定还会读到这批记录（不会漏报）；水位一旦前进，已同步前缀
 *    被跳过，游标自然前移——这就是省掉每轮 O(文件) 重扫的关键；
 *  - 没有待上报记录时，下一轮起点 = 文件末尾（新追加的记录在文件尾，下轮自然扫到）。
 *
 * 损坏游标（越界 / 落在半行上）一律按「从头开始」处理，不抛异常、不打断上报。
 */
internal class AnalysisScanCore(private val file: File) {

    /** 一次扫描的结果。 */
    data class ScanResult(
        /** 本轮取到的待上报记录（未同步、未被隔离）。 */
        val records: List<AnalysisCaseRecord>,
        /** 下轮的扫描起点：首个待上报记录的起始偏移；没有待上报记录时是文件末尾。 */
        val nextOffset: Long,
        /** 本轮是否走到了文件末尾（false = 因取满 maxRecords 提前收工）。 */
        val reachedEnd: Boolean,
        /** 本轮跳过的隔离记录条数（仅统计，不返回）。 */
        val skippedQuarantined: Int,
        /**
         * 本轮跳过的**未脱敏**记录条数（requireRedacted=true 时；仅统计，不返回）。
         *
         * 关键口径：未脱敏记录被跳过时**不钉住游标**（不设置 firstPendingOffset），
         * 否则 CLOUD_REDACT 模式下队首那条未脱敏旧记录会把游标永久钉住，
         * 它后面新追加的记录永远读不到、永远不上报。
         */
        val skippedUnredacted: Int = 0
    )

    companion object {
        /** 单轮最多取多少条待上报记录（内存上限保护）。 */
        const val DEFAULT_MAX_RECORDS = 1000

        /** 读文件时的分块大小（不把整个文件读进内存）。 */
        private const val CHUNK_SIZE = 256 * 1024
    }

    /**
     * 从 [startOffset] 起扫描。
     *
     * @param startOffset 起始字节偏移（损坏/越界时自动回落到 0）
     * @param lastSyncedAt 已同步水位：analyzedAt <= 它的记录视为已上报
     * @param maxRecords 本轮最多返回多少条待上报记录
     * @param requireRedacted true = 只放行**已脱敏**记录；未脱敏记录被跳过且不返回，
     *   但**不会钉住游标**（否则队尾新记录永远读不到）
     */
    fun scan(
        startOffset: Long,
        lastSyncedAt: Long,
        maxRecords: Int = DEFAULT_MAX_RECORDS,
        requireRedacted: Boolean = false
    ): ScanResult {
        if (!file.exists()) return ScanResult(emptyList(), 0L, true, 0)
        var offset = AnalysisScanCursor.normalizeOffset(startOffset, file.length())
        if (offset > 0L && !isLineStart(offset)) {
            // 游标落在半行上（文件被改写/损坏）：按「从头开始」处理，不抛异常
            offset = 0L
        }
        val records = ArrayList<AnalysisCaseRecord>()
        var firstPendingOffset = -1L
        var skippedQuarantined = 0
        var skippedUnredacted = 0
        var reachedEnd = true
        var filePos = offset
        var lineStart = offset
        val lineBuf = ByteArrayOutputStream()
        var skippingLongLine = false
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val chunk = ByteArray(CHUNK_SIZE)
            loop@ while (true) {
                val n = try { raf.read(chunk) } catch (e: Exception) { -1 }
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = chunk[i]
                    val absPos = filePos
                    filePos++
                    if (b.toInt() == AnalysisScanCursor.NEWLINE) {
                        val text = if (skippingLongLine) ""
                        else String(lineBuf.toByteArray(), Charsets.UTF_8)
                        lineBuf.reset()
                        skippingLongLine = false
                        val record = parseRecord(text)
                        if (record != null) {
                            when {
                                record.quarantined -> skippedQuarantined++
                                requireRedacted && !record.redacted -> skippedUnredacted++
                                record.analyzedAt <= lastSyncedAt -> Unit
                                else -> {
                                    if (firstPendingOffset < 0L) firstPendingOffset = lineStart
                                    if (records.size < maxRecords) records += record
                                }
                            }
                        }
                        lineStart = absPos + 1
                        if (records.size >= maxRecords) {
                            reachedEnd = false
                            break@loop
                        }
                        continue
                    }
                    if (skippingLongLine) continue
                    if (lineBuf.size() >= AnalysisScanCursor.MAX_LINE_BYTES) {
                        // 超长行（损坏）：整行丢弃，不让它卡住整条扫描
                        lineBuf.reset()
                        skippingLongLine = true
                        continue
                    }
                    lineBuf.write(b.toInt())
                }
            }
            // 文件末尾没有换行时，最后一段也算一条记录
            if (reachedEnd && !skippingLongLine && lineBuf.size() > 0) {
                val record = parseRecord(String(lineBuf.toByteArray(), Charsets.UTF_8))
                if (record != null) {
                    when {
                        record.quarantined -> skippedQuarantined++
                        requireRedacted && !record.redacted -> skippedUnredacted++
                        record.analyzedAt <= lastSyncedAt -> Unit
                        else -> {
                            if (firstPendingOffset < 0L) firstPendingOffset = lineStart
                            if (records.size < maxRecords) records += record
                        }
                    }
                }
            }
        }
        val nextOffset = if (firstPendingOffset >= 0L) firstPendingOffset else filePos
        return ScanResult(records, nextOffset, reachedEnd, skippedQuarantined, skippedUnredacted)
    }

    /** 偏移是否落在行首（offset == 0，或前一个字节是换行）。 */
    private fun isLineStart(offset: Long): Boolean {
        if (offset <= 0L) return true
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset - 1)
                raf.read() == AnalysisScanCursor.NEWLINE
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 单行解析：空行/旧格式/损坏行一律跳过（不抛异常）。 */
    private fun parseRecord(text: String): AnalysisCaseRecord? {
        if (text.isBlank()) return null
        return try {
            AnalysisCaseRecord.fromJson(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }
}