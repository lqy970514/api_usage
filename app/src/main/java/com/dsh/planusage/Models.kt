package com.dsh.planusage

/** 一个额度窗口（5 小时 / 周 / 月度）。字段可空，上游结构变更时只丢单个窗口，不整体失败。 */
data class UsageWindow(
    val title: String,
    /** 明细文字，如 "$0.56 / $14.00"；纯百分比制服务商为 null。 */
    val usedText: String? = null,
    /** 已用百分比 0..100；无法计算时为 null。 */
    val percent: Double? = null,
    /** 重置时间（毫秒）。 */
    val resetsAtMs: Long? = null,
    /** percent==0 时上游给的是占位重置时间，此时不展示倒计时。 */
    val showCountdown: Boolean = true,
    /** 异常状态徽标，如「已限流」。 */
    val badge: String? = null,
)

/** 单个服务商一次拉取的完整快照。 */
data class ProviderSnapshot(
    val ok: Boolean,
    val windows: List<UsageWindow> = emptyList(),
    val facts: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val queriedAt: Long = System.currentTimeMillis(),
)

/** 智谱用量端点分国内站与国际站；同一把 key 两边都能查通，按接入习惯选。 */
enum class ZhipuHost(val short: String, val label: String, val base: String) {
    CN("国内站", "国内站 open.bigmodel.cn", "https://open.bigmodel.cn"),
    INTL("国际站", "国际站 api.z.ai", "https://api.z.ai"),
}
