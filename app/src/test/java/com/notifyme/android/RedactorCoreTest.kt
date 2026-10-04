// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.notifyme.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RedactorCore] 规则单测：每类规则命中 + 边界不命中（误匹配是隐私工具最贵的 bug，
 * 它要么漏放真实敏感信息，要么把正常消息打成占位符让分析报废）。
 */
class RedactorCoreTest {

    private fun redact(
        text: String,
        rules: Set<String> = RedactorRules.ALL,
        names: Map<String, String> = emptyMap()
    ) = RedactorCore(rules, names).redact(text)

    // ---------------- 无命中 ----------------

    @Test
    fun `普通文本不替换且 hitCount 为 0`() {
        val r = redact("下午三点在公司开会，记得带评审材料")
        assertFalse(r.redacted)
        assertEquals(0, r.hitCount)
        assertEquals(emptyList<String>(), r.rules)
        assertEquals("下午三点在公司开会，记得带评审材料", r.text)
    }

    // ---------------- 手机号 ----------------

    @Test
    fun `11 位手机号替换为占位符`() {
        val r = redact("打我电话 13812345678 联系")
        assertEquals("打我电话 [手机号] 联系", r.text)
        assertEquals(1, r.hitCount)
        assertEquals(listOf(RedactorRules.PHONE), r.rules)
    }

    @Test
    fun `400 服务号也按手机号规则替换`() {
        val r = redact("客服 400-123-4567 转 9")
        assertEquals("客服 [手机号] 转 9", r.text)
    }

    @Test
    fun `手机号嵌在更长数字串内部不替换`() {
        // 两侧有数字 = 不是独立手机号，交给银行卡/其它规则或保留
        val r = redact("编号 51381234567890 结束")
        assertFalse(r.rules.contains(RedactorRules.PHONE))
    }

    @Test
    fun `两个手机号分别计数`() {
        val r = redact("13812345678 和 15998765432")
        assertEquals("[手机号] 和 [手机号]", r.text)
        assertEquals(2, r.hitCount)
    }

    // ---------------- 身份证 ----------------

    @Test
    fun `18 位身份证含 X 替换`() {
        val r = redact("身份证号 11010119900307888X 别外传")
        assertEquals("身份证号 [身份证] 别外传", r.text)
        assertTrue(r.rules.contains(RedactorRules.ID_CARD))
    }

    @Test
    fun `17 位数字不算身份证`() {
        val r = redact("11010119900307888")
        // 17 位不触发身份证；也不足银行卡 13... 17 位会触发银行卡规则
        assertFalse(r.rules.contains(RedactorRules.ID_CARD))
    }

    @Test
    fun `身份证优先于手机号识别`() {
        // 末 11 位形似手机号，但整体是 18 位身份证：不能出现手机号占位符
        val r = redact("310101199001234567")
        assertEquals("[身份证]", r.text)
        assertFalse(r.rules.contains(RedactorRules.PHONE))
    }

    // ---------------- 银行卡 / 长数字 ----------------

    @Test
    fun `19 位银行卡号替换`() {
        val r = redact("转账账号 6222021234567890123")
        assertEquals("转账账号 [银行卡]", r.text)
        assertTrue(r.rules.contains(RedactorRules.BANK_CARD))
    }

    @Test
    fun `手机号替换后不被银行卡规则二次处理`() {
        val r = redact("13812345678")
        assertEquals("[手机号]", r.text)
        assertFalse(r.rules.contains(RedactorRules.BANK_CARD))
    }

    @Test
    fun `12 位数字不触发银行卡`() {
        val r = redact("订单 123456789012")
        assertFalse(r.redacted)
    }

    // ---------------- 邮箱 ----------------

    @Test
    fun `邮箱替换`() {
        val r = redact("资料发 zhang.san+1@mail.example.com 即可")
        assertEquals("资料发 [邮箱] 即可", r.text)
        assertTrue(r.rules.contains(RedactorRules.EMAIL))
    }

    // ---------------- URL ----------------

    @Test
    fun `http 与 https 链接替换`() {
        val r = redact("点 http://t.cn/a1b2 或 https://example.com/x?y=1 看详情")
        assertEquals("点 [链接] 或 [链接] 看详情", r.text)
        assertEquals(2, r.hitCount)
    }

    @Test
    fun `www 开头链接替换且中文句号截断`() {
        val r = redact("访问 www.example.com/a。多的")
        assertEquals("访问 [链接]。多的", r.text)
    }

    // ---------------- 金额 ----------------

    @Test
    fun `带币种符号的金额替换`() {
        val r = redact("合计 ¥1,200.50 元含税")
        assertEquals("合计 [金额] 元含税", r.text)
    }

    @Test
    fun `数字加元或万元后缀替换`() {
        assertEquals("报销 [金额]", redact("报销 300元").text)
        assertEquals("合同额 [金额]", redact("合同额 50万元").text)
        assertEquals("花了 [金额]", redact("花了 12块钱").text)
        assertEquals("花了 [金额]", redact("花了 8块").text)
    }

    @Test
    fun `时间点和数量不被当成金额`() {
        assertFalse(redact("下午 3 点开会").redacted)
        assertFalse(redact("买了 10 个苹果").redacted)
    }

    @Test
    fun `美元金额替换`() {
        assertEquals("[金额] 起", redact("$9.99 起").text)
    }

    // ---------------- 地址 ----------------

    @Test
    fun `完整地址两级锚点替换`() {
        val r = redact("寄到 北京市朝阳区建国路88号 谢谢")
        assertEquals("寄到 [地址] 谢谢", r.text)
        assertTrue(r.rules.contains(RedactorRules.ADDRESS))
    }

    @Test
    fun `地址含单元门牌替换`() {
        val r = redact("杭州市西湖区文三路100号3单元501室")
        assertEquals("[地址]", r.text)
    }

    @Test
    fun `只有行政词没有细节锚不替换`() {
        assertFalse(redact("这个区域归朝阳区管").redacted)
        assertFalse(redact("我们公司在市中心").redacted)
    }

    // ---------------- 姓名映射 ----------------

    @Test
    fun `姓名映射替换并计数`() {
        val names = mapOf("张三" to "[人名1]", "李四" to "[人名2]")
        val r = redact("张三说李四负责", names = names)
        assertEquals("[人名1]说[人名2]负责", r.text)
        assertEquals(2, r.hitCount)
        assertEquals(listOf(RedactorRules.NAME), r.rules)
    }

    @Test
    fun `长名字优先于短名字`() {
        // 「张三丰」包含「张三」：必须先按长名整体替换
        val names = mapOf("张三" to "[人名1]", "张三丰" to "[人名3]")
        val r = redact("张三丰来了", names = names)
        assertEquals("[人名3]来了", r.text)
    }

    @Test
    fun `姓名含正则特殊字符也按字面量匹配`() {
        val names = mapOf("A·B（产品）" to "[人名1]")
        val r = redact("找A·B（产品）签字", names = names)
        assertEquals("找[人名1]签字", r.text)
    }

    // ---------------- 规则子集 ----------------

    @Test
    fun `只启用部分规则时其它规则不生效`() {
        val r = redact(
            "电话 13812345678 邮箱 a@b.com",
            rules = setOf(RedactorRules.EMAIL)
        )
        assertEquals("电话 13812345678 邮箱 [邮箱]", r.text)
        assertEquals(listOf(RedactorRules.EMAIL), r.rules)
    }

    @Test
    fun `多规则混排 hitCount 为总命中数`() {
        val r = redact(
            "张三的手机号 13812345678，邮箱 a@b.com，欠 500元",
            names = mapOf("张三" to "[人名1]")
        )
        assertEquals("[人名1]的手机号 [手机号]，邮箱 [邮箱]，欠 [金额]", r.text)
        assertEquals(4, r.hitCount)
    }

    @Test
    fun `多行文本逐行生效`() {
        val r = redact("第一行 13812345678\n第二行 300元")
        assertEquals("第一行 [手机号]\n第二行 [金额]", r.text)
        assertEquals(2, r.hitCount)
    }
}
