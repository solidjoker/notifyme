// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

/**
 * 版本信息的展示口径（纯函数，不碰 Android API，可纯 JVM 单测）。
 *
 * 为什么单独抽出来：版本号以前只出现在「控制台 → 关于」这一类界面里靠手写字符串拼接，
 * 渠道（open / beta）与构建类型（debug / release）的文案各页各写一份，真机排查时
 * 用户报的「我是 0.3.19」经常对不上包里到底是正式版还是内测版、调试包还是发布包。
 * 现在统一由这里出：同一份 [Entry] 既渲染成界面行，也拼成「复制版本信息」的文本。
 *
 * 两条约定（改之前先读）：
 *  1. [versionLine] 必须把 versionCode 一起带上 —— debug 包原地覆盖安装时 versionName
 *     可能不变，只有 versionCode 能证明「真的装上了新包」；
 *  2. beta flavor 的 versionName 自带 `-test` 后缀（app/build.gradle.kts 的
 *     versionNameSuffix），[title] 不再自己拼渠道后缀，否则会出现 `-test-test`。
 */
internal object VersionInfoCore {

    /** 复制/展示用的一行：标签 + 值。 */
    data class Entry(val label: String, val value: String)

    /**
     * 显示用版本号：`0.3.19` + `25` → `0.3.19（25）`。
     * versionName 为空（理论上不会发生）时只留版本号，不要出现 `（25）` 这种半截文案。
     */
    fun versionLine(versionName: String, versionCode: Int): String {
        val name = versionName.trim()
        return if (name.isEmpty()) versionCode.toString() else name + "（" + versionCode + "）"
    }

    /** 渠道文案：open → 正式版，beta → 内测版（beta 产物名仍叫 test，见 docs/BUILD.md）。 */
    fun flavorLabel(flavor: String): String {
        val f = flavor.trim().lowercase()
        return when (f) {
            "open" -> "正式版"
            "beta" -> "内测版"
            "" -> "未知渠道"
            else -> flavor.trim()
        }
    }

    /** 构建类型文案：debug → 调试构建，release → 发布构建。 */
    fun buildLabel(buildType: String): String {
        val b = buildType.trim().lowercase()
        return when (b) {
            "debug" -> "调试构建"
            "release" -> "发布构建"
            "" -> "未知构建"
            else -> buildType.trim()
        }
    }

    /**
     * 标题行：`notifyme v0.3.19`。
     * beta 的 versionName 已含 `-test`，这里不再拼渠道后缀。
     */
    fun title(versionName: String): String {
        val name = versionName.trim()
        return if (name.isEmpty()) "notifyme" else "notifyme v" + name
    }

    /**
     * 界面上逐行渲染、也用于「复制版本信息」的条目表。
     *
     * 空值条目直接丢掉（真机偶尔取不到设备型号 / 系统版本），
     * 但「版本」「版本号」两条永不丢 —— 这是用户报障时唯一能对上的两个值。
     */
    fun entries(
        versionName: String,
        versionCode: Int,
        flavor: String,
        buildType: String,
        applicationId: String,
        abis: List<String>,
        androidRelease: String,
        deviceModel: String
    ): List<Entry> {
        val out = ArrayList<Entry>(8)
        out.add(Entry("版本", versionLine(versionName, versionCode)))
        out.add(Entry("渠道", flavorLabel(flavor)))
        out.add(Entry("构建", buildLabel(buildType)))
        pushIfNotBlank(out, "应用 ID", applicationId)
        pushIfNotBlank(out, "架构", abis.filter { it.isNotBlank() }.joinToString("/"))
        pushIfNotBlank(out, "系统", androidRelease)
        pushIfNotBlank(out, "设备", deviceModel)
        return out
    }

    private fun pushIfNotBlank(out: MutableList<Entry>, label: String, value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) out.add(Entry(label, v))
    }

    /** 界面正文（多行）：`版本：0.3.19（25）` 逐行。 */
    fun detailText(entries: List<Entry>): String =
        entries.joinToString("\n") { it.label + "：" + it.value }

    /** 「复制版本信息」的剪贴板文本：首行标题 + 明细，便于贴进 issue。 */
    fun copyText(versionName: String, entries: List<Entry>): String {
        if (entries.isEmpty()) return title(versionName)
        return title(versionName) + "\n" + detailText(entries)
    }
}
