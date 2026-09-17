package com.dsh.planusage

import org.json.JSONObject
import java.time.Instant

/**
 * 纯解析层：输入上游 JSON，输出 [ProviderSnapshot]，**不碰网络、不碰 Android API**，
 * 因此可以在 JVM 单测里直接跑（见 `app/src/test/.../UsageParserTest.kt`）。
 *
 * 两个服务商的用量端点都是未文档化的私有路由，所以整体策略是「逐窗口防御性解析」：
 * 任何单个窗口解析失败只丢那个窗口，绝不整体崩。
 */
internal object UsageParser {

    // 文档口径：OpenCode Go 只有百分比，按 $12/5h、$30/周、$60/月 换算金额。
    private val OPENCODE_WINDOWS = listOf(
        Triple("5 小时", "rolling", 12.0),
        Triple("周", "weekly", 30.0),
        Triple("月度", "monthly", 60.0),
    )

    /** 月度 cap 不在响应里，官方 CLI 按套餐硬编码（源码 Xn 表）。 */
    private val PLAN_CAP = mapOf(
        "individual-go" to 10.0,
        "individual-goat" to 70.0,
        "individual-pro" to 30.0,
        "individual-pro-v1" to 80.0,
        "individual-provider" to 15.0,
        "individual-max" to 150.0,
        "individual-ultra" to 300.0,
        "teams-pro" to 40.0,
    )

    private val PLAN_NAME = mapOf(
        "individual-go" to "Go",
        "individual-goat" to "GOAT",
        "individual-pro" to "Pro",
        "individual-pro-v1" to "Pro v1",
        "individual-provider" to "Provider",
        "individual-max" to "Max",
        "individual-ultra" to "Ultra",
        "teams-pro" to "Team Pro",
    )

    /** `GET https://opencode.ai/zen/go/v1/usage` 的响应体。 */
    fun openCode(root: JSONObject): ProviderSnapshot {
        val usage = root.optJSONObject("usage")
            ?: return ProviderSnapshot(
                ok = false,
                error = "响应里没有 usage 字段（旧版扁平结构已作废），上游可能已变更",
            )
        val windows = OPENCODE_WINDOWS.mapNotNull { (title, field, cap) ->
            val window = usage.optJSONObject(field) ?: return@mapNotNull null
            val percent = window.optDoubleOrNull("percent")?.coerceIn(0.0, 100.0) ?: return@mapNotNull null
            val resetsAt = parseIso(window.optStringOrNull("resetsAt"))
            UsageWindow(
                title = title,
                usedText = "≈ ${money(percent / 100 * cap)} / ${money(cap)}",
                percent = percent,
                resetsAtMs = resetsAt,
                // percent==0 时上游给的是「now + 窗口时长」占位值，不展示倒计时。
                showCountdown = percent > 0.0 && resetsAt != null,
                badge = if (window.optString("status") == "rate-limited") "已限流" else null,
            )
        }
        if (windows.isEmpty()) {
            return ProviderSnapshot(ok = false, error = "未解析到任何用量窗口，上游可能已变更结构")
        }
        return ProviderSnapshot(ok = true, windows = windows)
    }

    /**
     * `GET /alpha/billing/credits` 的响应体（`subscription` 可为 null，
     * 来自 `/alpha/billing/subscriptions` 的 `data`，只用来拿套餐 ID 与账单周期）。
     */
    fun commandCode(root: JSONObject, subscription: JSONObject?): ProviderSnapshot {
        val credits = root.optJSONObject("credits") ?: root
        // 正常在 credits 同级；历史响应出现过嵌在 credits 内的变体，两者都兼容。
        val limits = root.optJSONObject("windowLimits") ?: credits.optJSONObject("windowLimits")

        val monthlyRemaining = credits.optDoubleOrNull("monthlyCredits") ?: 0.0
        val purchased = credits.optDoubleOrNull("purchasedCredits") ?: 0.0
        val free = credits.optDoubleOrNull("freeCredits") ?: 0.0

        val planId = subscription?.optStringOrNull("planId")
        val planCap = planId?.let { PLAN_CAP[it] }

        val windows = mutableListOf<UsageWindow>()
        limits?.optJSONObject("fiveHour")?.let { windows += windowOf("5 小时", it) }
        limits?.optJSONObject("weekly")?.let { windows += windowOf("周", it) }

        if (planCap != null) {
            val totalRemaining = monthlyRemaining + purchased + free
            val totalPool = maxOf(planCap, monthlyRemaining) + purchased + free
            val used = (totalPool - totalRemaining).coerceAtLeast(0.0)
            windows += UsageWindow(
                title = "月度",
                usedText = "${money(used)} / ${money(totalPool)} · 余 ${money(totalRemaining)}",
                percent = if (totalPool > 0) used / totalPool * 100 else null,
                resetsAtMs = parseIso(subscription?.optStringOrNull("currentPeriodEnd")),
            )
        }

        val facts = buildList {
            if (planId != null) add("套餐" to (PLAN_NAME[planId] ?: planId))
            if (planCap == null && monthlyRemaining > 0) add("月度剩余" to money(monthlyRemaining))
            if (purchased > 0) add("按量额度" to money(purchased))
            if (free > 0) add("免费额度" to money(free))
        }

        if (windows.isEmpty() && facts.isEmpty()) {
            return ProviderSnapshot(ok = false, error = "未解析到用量字段，上游可能已变更结构")
        }
        return ProviderSnapshot(ok = true, windows = windows, facts = facts)
    }

    // ── 智谱 GLM Coding Plan ─────────────────────────────────────────────

    /** 积分额度（5h / 周），官方文档口径；绝对值要用 data.level 换算，响应里没有分母。 */
    private val ZHIPU_ALLOWANCE = mapOf(
        "lite" to (2_000 to 10_000),
        "pro" to (12_000 to 60_000),
        "max" to (28_000 to 140_000),
    )

    private val ZHIPU_LEVEL_NAME = mapOf("lite" to "Lite", "pro" to "Pro", "max" to "Max")

    /**
     * `GET {host}/api/monitor/usage/quota/limit` 的响应体。
     *
     * GLM Coding Plan 只有 **5 小时 + 周** 两个窗口（没有月度），`unit` 才是窗口标识：
     * `3` = 5 小时、`6` = 每周。**不能按 nextResetTime 排序分桶**——周期末尾每周窗口
     * 可能比 5 小时窗口更早重置，排序会把两个桶标反。
     */
    fun zhipu(root: JSONObject, hostLabel: String): ProviderSnapshot {
        val code = root.optDoubleOrNull("code")?.toInt() ?: 200
        if (code != 200) {
            val msg = root.optStringOrNull("msg") ?: "未知错误"
            return ProviderSnapshot(ok = false, error = "智谱返回 code=$code：$msg")
        }
        val data = root.optJSONObject("data")
            ?: return ProviderSnapshot(ok = false, error = "响应里没有 data 字段，上游可能已变更")
        val limits = data.optJSONArray("limits")
            ?: return ProviderSnapshot(ok = false, error = "响应里没有 limits 字段，上游可能已变更")

        // 逐条防御：只收编码积分窗口；TIME_LIMIT 是联网搜索/网页读取这类 MCP 工具额度，
        // 不是编码 token 额度，按文档直接忽略。
        val entries = mutableListOf<ZhipuEntry>()
        for (i in 0 until limits.length()) {
            val item = limits.optJSONObject(i) ?: continue
            val type = item.optStringOrNull("type")?.uppercase() ?: continue
            if (type != "TOKENS_LIMIT" && type != "CREDIT_LIMIT") continue
            val percent = item.optDoubleOrNull("percentage") ?: continue
            entries += ZhipuEntry(
                unit = item.optDoubleOrNull("unit")?.toInt(),
                percent = percent.coerceIn(0.0, 100.0),
                // nextResetTime 是毫秒时间戳，不是 ISO 字符串。
                resetsAt = item.optLongOrNull("nextResetTime"),
            )
        }
        if (entries.isEmpty()) {
            return ProviderSnapshot(ok = false, error = "未找到 5 小时 / 周用量窗口，上游可能已变更结构")
        }

        var fiveHour: ZhipuEntry? = null
        var weekly: ZhipuEntry? = null
        val unclassified = mutableListOf<ZhipuEntry>()
        for (entry in entries) {
            when (entry.unit) {
                3 -> if (fiveHour == null) fiveHour = entry else unclassified += entry
                6 -> if (weekly == null) weekly = entry else unclassified += entry
                else -> unclassified += entry
            }
        }
        // unit 缺失或不认识时的兜底（对齐 cc-switch）：无 nextResetTime 的优先归 5h，
        // 其余按 reset 升序填空位。老套餐只返回 1 条，自然降级为只显示 5h。
        unclassified.sortWith(compareBy({ if (it.resetsAt == null) 0 else 1 }, { it.resetsAt ?: 0L }))
        for (entry in unclassified) {
            if (fiveHour == null) fiveHour = entry else if (weekly == null) weekly = entry
        }

        val level = data.optStringOrNull("level")
        val allowance = level?.lowercase()?.let { ZHIPU_ALLOWANCE[it] }

        val windows = mutableListOf<UsageWindow>()
        fiveHour?.let { windows += zhipuWindow("5 小时", it, allowance?.first) }
        weekly?.let { windows += zhipuWindow("周", it, allowance?.second) }

        val facts = buildList {
            if (level != null) add("套餐" to (ZHIPU_LEVEL_NAME[level.lowercase()] ?: level))
            add("站点" to hostLabel)
        }
        return ProviderSnapshot(ok = true, windows = windows, facts = facts)
    }

    /** TOKENS_LIMIT 只给百分比，绝对值 = percentage% × 套餐额度。 */
    private fun zhipuWindow(title: String, entry: ZhipuEntry, cap: Int?): UsageWindow = UsageWindow(
        title = title,
        usedText = if (cap != null) {
            "≈ ${credits(entry.percent / 100 * cap)} / ${credits(cap.toDouble())} 积分"
        } else {
            "已用 ${percentText(entry.percent)}"
        },
        percent = entry.percent,
        resetsAtMs = entry.resetsAt,
    )

    private fun windowOf(title: String, window: JSONObject): UsageWindow {
        val used = window.optDoubleOrNull("used") ?: 0.0
        val cap = window.optDoubleOrNull("cap") ?: 0.0
        return UsageWindow(
            title = title,
            usedText = "${money(used)} / ${money(cap)}",
            percent = if (cap > 0) (used / cap * 100).coerceIn(0.0, 100.0) else null,
            // resetAt 是毫秒数字时间戳，不是 ISO 字符串。
            resetsAtMs = window.optLongOrNull("resetAt"),
            badge = if (window.optBoolean("exceeded", false)) "已超额" else null,
        )
    }
}

// ── JSON 取值小工具：区分「字段缺失」与「字段为 0」 ────────────────────────

/** 智谱 limits[] 里一条编码积分窗口（分桶前的中间结构）。 */
private data class ZhipuEntry(
    val unit: Int?,
    val percent: Double,
    val resetsAt: Long?,
)

internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

internal fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() && it != "null" } else null

internal fun parseIso(text: String?): Long? =
    text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
