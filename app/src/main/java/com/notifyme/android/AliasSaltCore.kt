// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import android.content.Context
import java.security.SecureRandom

/**
 * 姓名别名加盐内核（M3 强匿名）：把「无盐 sha256 别名」升级成「每台设备随机盐 + sha256」。
 *
 * 为什么需要盐：旧别名是 sha256(名字) 前 6 位，拿到一份候选名单（同事名单、班级名单、
 * 通讯录）的人可以逐个哈希比对，直接把 `[人名3f2a1c]` 反解成真人姓名——别名形同虚设。
 * 加盐后同名的人在不同设备上得到不同别名，攻击者必须知道本机盐才可能比对；
 * 盐只存在 app 私有 SharedPreferences，不入库、不上报、不进日志、不支持导出。
 *
 * 边界（同步写进 PRIVACY.md，别当零风险承诺）：
 *  - 同一台设备上别名依旧稳定：跨会话、跨时间都能追踪「同一个人」。这是分析需要的能力，
 *    也意味着别名是可关联的伪匿名，不是不可链接匿名；
 *  - 盐是设备级单值，拿到设备存储（root / 备份 / 无障碍读屏）的人仍可重算别名；
 *  - 别名截断到 6 位十六进制（24 bit）：一个会话窗口里人数极少，碰撞概率可忽略，
 *    但不要把它当唯一 ID 做跨库统计。
 */
object AliasSaltCore {
    /** 盐长度（字节）：32 字节 = 256 bit，足以让候选名单爆破失去意义。 */
    const val SALT_BYTES = 32

    /** 别名前缀：与其它占位符同一视觉语言，模型能看出「这是一个人」而不是正文。 */
    const val PERSON_PREFIX = "[人名"
    const val GROUP_PREFIX = "[群"

    /** 别名十六进制位数（6 位 = 24 bit）；改它会让历史别名全部变样，必须同步 PRIVACY.md。 */
    const val ALIAS_HEX_LEN = 6

    /** 无盐兼容盐（空字节数组）：只给单测和「历史别名一致性」断言用，生产调用点不许用。 */
    val LEGACY_SALT: ByteArray = ByteArray(0)

    /** 生成一把新盐：SecureRandom，不掺时间戳 / 设备标识，避免可预测。 */
    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray {
        val out = ByteArray(SALT_BYTES)
        random.nextBytes(out)
        return out
    }

    /** 十六进制编码（落盘形态，64 字符）。 */
    fun encode(salt: ByteArray): String = salt.joinToString("") { "%02x".format(it) }

    /**
     * 解码落盘形态：长度不是 [SALT_BYTES] 的两倍、或含非十六进制字符一律返回 null
     * （调用方重新生成，不抛异常——坏 prefs 不该把脱敏链路打挂）。
     */
    fun decode(raw: String?): ByteArray? {
        val s = raw?.trim().orEmpty()
        if (s.length != SALT_BYTES * 2) return null
        val out = ByteArray(SALT_BYTES)
        for (i in out.indices) {
            val hi = hexVal(s[i * 2])
            val lo = hexVal(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * 落盘值 → 可用盐。能解出 32 字节就原样复用（保证别名跨重启、跨升级稳定），
     * 否则生成新盐。第二项非 null 表示「需要把这份十六进制写回」。
     */
    fun ensure(
        stored: String?,
        random: SecureRandom = SecureRandom()
    ): Pair<ByteArray, String?> {
        decode(stored)?.let { return Pair(it, null) }
        val salt = newSalt(random)
        return Pair(salt, encode(salt))
    }

    /** 人名别名：`[人名` + 6 位 hex + `]`。 */
    fun personAlias(name: String, salt: ByteArray): String = PERSON_PREFIX + digest(name, salt) + "]"

    /** 群名别名：`[群` + 6 位 hex + `]`。 */
    fun groupAlias(name: String, salt: ByteArray): String = GROUP_PREFIX + digest(name, salt) + "]"

    /**
     * 摘要口径：sha256(salt ‖ 0x00 ‖ name) 前 [ALIAS_HEX_LEN] 位十六进制。
     * 0x00 分隔符消除拼接歧义（否则 salt="ab", name="c" 与 salt="a", name="bc" 同输入）。
     * 盐为空时退化为 sha256(name)，与 M3 初版别名逐字节一致——历史别名可复现。
     */
    fun digest(name: String, salt: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        if (salt.isNotEmpty()) {
            md.update(salt)
            md.update(0.toByte())
        }
        val d = md.digest(name.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(ALIAS_HEX_LEN)
    }
}

/**
 * 别名盐持久化：与隐私模式共用 prefs 文件 `privacy_config`，键 `alias_salt`（十六进制）。
 *
 * 生命周期刻意做成「首次真正需要脱敏时才生成」——隐私模式没开过的设备根本不会有盐，
 * 也就没有可被关联的别名。重置入口在隐私设置页（设备被他人拿到过时的补救手段）。
 */
internal object AliasSaltStore {
    private const val KEY_SALT = "alias_salt"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrivacyConfig.PREFS_NAME, Context.MODE_PRIVATE)

    /** 读盐：缺失 / 损坏就地生成并落盘，保证后续每次调用拿到同一把。 */
    @Synchronized
    fun salt(context: Context): ByteArray {
        val sp = prefs(context)
        val (salt, writeBack) = AliasSaltCore.ensure(sp.getString(KEY_SALT, null))
        if (writeBack != null) sp.edit().putString(KEY_SALT, writeBack).apply()
        return salt
    }

    /** 现成的别名工厂：调用点一行拿到与落盘盐一致的 [NameAliases]。 */
    fun aliases(context: Context): NameAliases = NameAliases(salt(context))

    /** 重置盐：旧别名全部作废（历史记录里已存的别名不会变，变的是此后出设备的文本）。 */
    @Synchronized
    fun reset(context: Context): ByteArray {
        val salt = AliasSaltCore.newSalt()
        prefs(context).edit().putString(KEY_SALT, AliasSaltCore.encode(salt)).apply()
        return salt
    }

    /** 是否已经有落盘盐（UI 展示用，不产生写副作用）。 */
    fun hasSalt(context: Context): Boolean =
        AliasSaltCore.decode(prefs(context).getString(KEY_SALT, null)) != null
}
