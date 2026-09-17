package com.dsh.planusage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯解析层单测：全部样本直接取自 `opencode-go-command-code-goat-usage.md` 的实测响应。
 * 这两个端点都是未文档化私有路由，所以这里同时锁住「字段缺失时只丢单个窗口」的行为。
 */
class UsageParserTest {

    // ── OpenCode Go ──────────────────────────────────────────────────────

    private val openCodeSample = JSONObject(
        """
        {"usage":{
          "rolling":{"status":"ok","percent":4,"resetsAt":"2026-09-17T06:03:21.751Z"},
          "weekly":{"status":"ok","percent":3,"resetsAt":"2026-09-21T00:00:00.751Z"},
          "monthly":{"status":"ok","percent":17,"resetsAt":"2026-09-26T13:43:20.751Z"}}}
        """.trimIndent(),
    )

    @Test
    fun `OpenCode 三个窗口按 rolling weekly monthly 顺序解析`() {
        val snap = UsageParser.openCode(openCodeSample)
        assertTrue(snap.error ?: "", snap.ok)
        assertEquals(listOf("5 小时", "周", "月度"), snap.windows.map { it.title })
        assertEquals(4.0, snap.windows[0].percent!!, 0.001)
        assertEquals(3.0, snap.windows[1].percent!!, 0.001)
        assertEquals(17.0, snap.windows[2].percent!!, 0.001)
    }

    @Test
    fun `OpenCode 百分比按文档口径换算成金额`() {
        val snap = UsageParser.openCode(openCodeSample)
        // 4% × $12 = $0.48；17% × $60 = $10.20
        assertEquals("≈ \$0.48 / \$12.00", snap.windows[0].usedText)
        assertEquals("≈ \$10.20 / \$60.00", snap.windows[2].usedText)
    }

    @Test
    fun `OpenCode ISO 重置时间解析为毫秒且展示倒计时`() {
        val snap = UsageParser.openCode(openCodeSample)
        val rolling = snap.windows[0]
        assertTrue("resetsAt 应解析成有效毫秒时间戳", rolling.resetsAtMs!! > 1_700_000_000_000L)
        assertEquals("2026-09-17T06:03:21.751Z", java.time.Instant.ofEpochMilli(rolling.resetsAtMs!!).toString())
        assertTrue(rolling.showCountdown)
    }

    @Test
    fun `OpenCode percent 为 0 时不展示倒计时（占位重置时间）`() {
        val snap = UsageParser.openCode(
            JSONObject("""{"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":"2026-09-17T11:03:21.751Z"}}}"""),
        )
        assertTrue(snap.ok)
        assertEquals(0.0, snap.windows[0].percent!!, 0.001)
        assertNotNull("占位时间仍解析出来，但由 showCountdown 决定不展示", snap.windows[0].resetsAtMs)
        assertFalse(snap.windows[0].showCountdown)
    }

    @Test
    fun `OpenCode rate-limited 打限流徽标`() {
        val snap = UsageParser.openCode(
            JSONObject("""{"usage":{"rolling":{"status":"rate-limited","percent":100,"resetsAt":"2026-09-17T06:03:21.751Z"}}}"""),
        )
        assertEquals("已限流", snap.windows[0].badge)
    }

    @Test
    fun `OpenCode 单窗口结构异常只丢该窗口`() {
        val snap = UsageParser.openCode(
            JSONObject(
                """{"usage":{"rolling":{"status":"ok"},"weekly":{"status":"ok","percent":3,"resetsAt":"2026-09-21T00:00:00.751Z"}}}""",
            ),
        )
        assertTrue(snap.ok)
        assertEquals(listOf("周"), snap.windows.map { it.title })
    }

    @Test
    fun `OpenCode 旧版扁平结构给出明确错误而不是崩溃`() {
        val snap = UsageParser.openCode(JSONObject("""{"rollingUsage":{"usagePercent":10}}"""))
        assertFalse(snap.ok)
        assertTrue(snap.error!!.contains("usage"))
    }

    // ── Command Code GOAT ────────────────────────────────────────────────

    private val creditsSample = JSONObject(
        """
        {"credits":{"belowThreshold":false,"creditThreshold":0,
          "monthlyCredits":34.4268524747,"purchasedCredits":0,"freeCredits":0},
         "windowLimits":{"limited":true,"exceeded":null,
          "fiveHour":{"used":0.5643128855,"cap":14,"exceeded":false,"resetAt":1789626552497},
          "weekly":{"used":0.5729497515,"cap":35,"exceeded":false,"resetAt":1790143788608}}}
        """.trimIndent(),
    )

    private val subscriptionSample = JSONObject(
        """
        {"success":true,"data":{"id":"sub_1","status":"active",
          "currentPeriodStart":"2026-09-08T10:45:31.000Z",
          "currentPeriodEnd":"2026-10-08T10:45:31.000Z",
          "planId":"individual-goat"}}
        """.trimIndent(),
    ).getJSONObject("data")

    @Test
    fun `GOAT 5h 与周窗口按 used cap 求百分比`() {
        val snap = UsageParser.commandCode(creditsSample, subscriptionSample)
        assertTrue(snap.error ?: "", snap.ok)
        val fiveHour = snap.windows.first { it.title == "5 小时" }
        val weekly = snap.windows.first { it.title == "周" }
        assertEquals(0.5643128855 / 14 * 100, fiveHour.percent!!, 0.001)
        assertEquals(0.5729497515 / 35 * 100, weekly.percent!!, 0.001)
        assertEquals("\$0.56 / \$14.00", fiveHour.usedText)
        assertEquals(1789626552497L, fiveHour.resetsAtMs)
    }

    @Test
    fun `GOAT 月度用 max(planCap, remaining) 反推已用`() {
        val snap = UsageParser.commandCode(creditsSample, subscriptionSample)
        val monthly = snap.windows.first { it.title == "月度" }
        // pool = 70，remaining = 34.4268524747，used = 35.5731475253 → 50.82%
        assertEquals(50.8188, monthly.percent!!, 0.01)
        assertEquals("\$35.57 / \$70.00 · 余 \$34.43", monthly.usedText)
        assertNotNull("账单周期结束时间应为有效毫秒", monthly.resetsAtMs)
    }

    @Test
    fun `GOAT 套餐名与账单周期进入 facts`() {
        val snap = UsageParser.commandCode(creditsSample, subscriptionSample)
        assertTrue(snap.facts.contains("套餐" to "GOAT"))
    }

    @Test
    fun `GOAT 兼容 windowLimits 嵌在 credits 内的历史变体`() {
        val nested = JSONObject(
            """
            {"credits":{"monthlyCredits":34.4,"purchasedCredits":0,"freeCredits":0,
              "windowLimits":{"fiveHour":{"used":1,"cap":14,"exceeded":false,"resetAt":1789626552497}}}}
            """.trimIndent(),
        )
        val snap = UsageParser.commandCode(nested, subscriptionSample)
        val fiveHour = snap.windows.first { it.title == "5 小时" }
        assertEquals(1.0 / 14 * 100, fiveHour.percent!!, 0.001)
    }

    @Test
    fun `GOAT 按量购买额度计入 pool 且不占 5h 周窗口`() {
        val withPurchased = JSONObject(
            """
            {"credits":{"monthlyCredits":0,"purchasedCredits":10,"freeCredits":0},
             "windowLimits":{"fiveHour":{"used":0,"cap":14,"exceeded":false,"resetAt":1789626552497}}}
            """.trimIndent(),
        )
        val snap = UsageParser.commandCode(withPurchased, subscriptionSample)
        val monthly = snap.windows.first { it.title == "月度" }
        // pool = 70 + 10 = 80，remaining = 10，used = 70
        assertEquals("\$70.00 / \$80.00 · 余 \$10.00", monthly.usedText)
        assertTrue(snap.facts.contains("按量额度" to "\$10.00"))
    }

    @Test
    fun `GOAT 未知套餐不硬猜 cap，退回显示月度剩余`() {
        val unknown = JSONObject("""{"credits":{"monthlyCredits":12.5}}""")
        val snap = UsageParser.commandCode(
            unknown,
            JSONObject("""{"planId":"individual-future-plan"}"""),
        )
        assertTrue(snap.ok)
        assertTrue(snap.windows.none { it.title == "月度" })
        assertTrue(snap.facts.contains("套餐" to "individual-future-plan"))
        assertTrue(snap.facts.contains("月度剩余" to "\$12.50"))
    }

    @Test
    fun `GOAT exceeded 打超额徽标`() {
        val exceeded = JSONObject(
            """
            {"credits":{"monthlyCredits":5},
             "windowLimits":{"fiveHour":{"used":15,"cap":14,"exceeded":true,"resetAt":1789626552497}}}
            """.trimIndent(),
        )
        val snap = UsageParser.commandCode(exceeded, subscriptionSample)
        val fiveHour = snap.windows.first { it.title == "5 小时" }
        assertEquals("已超额", fiveHour.badge)
        assertEquals(100.0, fiveHour.percent!!, 0.001) // 超额时百分比封顶
    }

    @Test
    fun `GOAT 订阅接口挂了仍能展示窗口用量`() {
        val snap = UsageParser.commandCode(creditsSample, null)
        assertTrue(snap.ok)
        assertEquals(listOf("5 小时", "周"), snap.windows.map { it.title })
        assertNull(snap.windows.firstOrNull { it.title == "月度" })
    }

    @Test
    fun `GOAT 完全无法识别的响应给出错误`() {
        val snap = UsageParser.commandCode(JSONObject("""{"ok":true}"""), null)
        assertFalse(snap.ok)
        assertTrue(snap.error!!.contains("用量"))
    }

    // ── 展示辅助 ─────────────────────────────────────────────────────────

    @Test
    fun `倒计时按最大两个单位展示`() {
        val now = 1_700_000_000_000L
        assertEquals("3小时12分后重置", countdown(now + (3 * 60 + 12) * 60_000, now))
        assertEquals("2天5小时后重置", countdown(now + ((2 * 24 + 5) * 60) * 60_000, now))
        assertEquals("25分钟后重置", countdown(now + 25 * 60_000, now))
        assertEquals("即将重置", countdown(now - 1, now))
    }

    @Test
    fun `金额与百分比格式化`() {
        assertEquals("\$35.57", money(35.5731))
        assertEquals("--", percentText(null))
        assertEquals("51%", percentText(50.8188))
    }
}
