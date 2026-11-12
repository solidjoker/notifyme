// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionInfoCore] 的口径回归：版本行必须带 versionCode、渠道/构建文案固定、
 * 空值条目不渲染、复制文本首行是标题。改文案前先看这里的断言。
 */
class VersionInfoCoreTest {

    @Test
    fun `版本行带版本号括号`() {
        assertEquals("0.3.19（25）", VersionInfoCore.versionLine("0.3.19", 25))
    }

    @Test
    fun `版本行去掉名字两端空白`() {
        assertEquals("0.3.19（25）", VersionInfoCore.versionLine("  0.3.19  ", 25))
    }

    @Test
    fun `版本名称为空时只留版本号不留半截括号`() {
        assertEquals("25", VersionInfoCore.versionLine("", 25))
        assertEquals("25", VersionInfoCore.versionLine("   ", 25))
    }

    @Test
    fun `渠道文案固定`() {
        assertEquals("正式版", VersionInfoCore.flavorLabel("open"))
        assertEquals("内测版", VersionInfoCore.flavorLabel("beta"))
        assertEquals("未知渠道", VersionInfoCore.flavorLabel(""))
    }

    @Test
    fun `渠道大小写不敏感且未知值原样保留`() {
        assertEquals("正式版", VersionInfoCore.flavorLabel("OPEN"))
        assertEquals("内测版", VersionInfoCore.flavorLabel(" Beta "))
        assertEquals("canary", VersionInfoCore.flavorLabel("canary"))
    }

    @Test
    fun `构建类型文案固定`() {
        assertEquals("调试构建", VersionInfoCore.buildLabel("debug"))
        assertEquals("发布构建", VersionInfoCore.buildLabel("release"))
        assertEquals("未知构建", VersionInfoCore.buildLabel(""))
    }

    @Test
    fun `标题不重复拼渠道后缀`() {
        // beta 的 versionName 自带 -test（app/build.gradle.kts 的 versionNameSuffix）
        assertEquals("notifyme v0.3.19-test", VersionInfoCore.title("0.3.19-test"))
        assertTrue(!VersionInfoCore.title("0.3.19-test").endsWith("-test-test"))
    }

    @Test
    fun `标题在版本名为空时退回应用名`() {
        assertEquals("notifyme", VersionInfoCore.title(""))
        assertEquals("notifyme", VersionInfoCore.title("  "))
    }

    @Test
    fun `条目表前三条永远是版本渠道构建`() {
        val entries = VersionInfoCore.entries(
            versionName = "0.3.19",
            versionCode = 25,
            flavor = "open",
            buildType = "debug",
            applicationId = "com.notifyme.android",
            abis = listOf("arm64-v8a", "x86_64"),
            androidRelease = "17",
            deviceModel = "25098PN5AC"
        )
        assertEquals(7, entries.size)
        assertEquals(VersionInfoCore.Entry("版本", "0.3.19（25）"), entries[0])
        assertEquals(VersionInfoCore.Entry("渠道", "正式版"), entries[1])
        assertEquals(VersionInfoCore.Entry("构建", "调试构建"), entries[2])
        assertEquals(VersionInfoCore.Entry("应用 ID", "com.notifyme.android"), entries[3])
        assertEquals(VersionInfoCore.Entry("架构", "arm64-v8a/x86_64"), entries[4])
        assertEquals(VersionInfoCore.Entry("系统", "17"), entries[5])
        assertEquals(VersionInfoCore.Entry("设备", "25098PN5AC"), entries[6])
    }

    @Test
    fun `空值条目直接丢掉但版本与渠道构建永不丢`() {
        val entries = VersionInfoCore.entries(
            versionName = "0.3.19",
            versionCode = 25,
            flavor = "open",
            buildType = "debug",
            applicationId = "  ",
            abis = emptyList(),
            androidRelease = "",
            deviceModel = "   "
        )
        assertEquals(3, entries.size)
        assertEquals("版本", entries[0].label)
        assertEquals("渠道", entries[1].label)
        assertEquals("构建", entries[2].label)
    }

    @Test
    fun `架构列表里的空串不渲染成斜杠`() {
        val entries = VersionInfoCore.entries(
            versionName = "0.3.19",
            versionCode = 25,
            flavor = "open",
            buildType = "release",
            applicationId = "com.notifyme.android",
            abis = listOf("arm64-v8a", "", "  "),
            androidRelease = "17",
            deviceModel = "25098PN5AC"
        )
        val arch = entries.first { it.label == "架构" }
        assertEquals("arm64-v8a", arch.value)
    }

    @Test
    fun `明细文本逐行且用中文冒号`() {
        val entries = listOf(
            VersionInfoCore.Entry("版本", "0.3.19（25）"),
            VersionInfoCore.Entry("渠道", "正式版")
        )
        assertEquals("版本：0.3.19（25）\n渠道：正式版", VersionInfoCore.detailText(entries))
    }

    @Test
    fun `复制文本首行是标题其后逐行明细`() {
        val entries = listOf(VersionInfoCore.Entry("版本", "0.3.19（25）"))
        assertEquals("notifyme v0.3.19\n版本：0.3.19（25）", VersionInfoCore.copyText("0.3.19", entries))
    }

    @Test
    fun `复制文本在条目为空时只有标题`() {
        assertEquals("notifyme v0.3.19", VersionInfoCore.copyText("0.3.19", emptyList()))
    }
}
