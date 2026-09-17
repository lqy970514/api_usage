package com.dsh.planusage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 非 2xx 响应，携带状态码便于给出中文提示。 */
private class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

/** 用于把 HTTP 失败翻译成各自贴切的中文提示。 */
private enum class Kind { OPENCODE, COMMAND_CODE, ZHIPU }

/**
 * 网络层：只负责发请求 + 把 HTTP 失败翻译成中文提示，解析全部交给 [UsageParser]。
 *
 * 三家的鉴权头不一样：OpenCode Go 与 Command Code 用 `Authorization: Bearer`，
 * 智谱按 cc-switch 的做法直接放原始 key（不加 Bearer 前缀；实测带 Bearer 也能通）。
 */
internal object UsageApi {

    private const val TIMEOUT_MS = 20_000
    private const val UA = "PlanUsage/1.1 (Android)"

    /** OpenCode Go 用量端点。 */
    private const val OPENCODE_URL = "https://opencode.ai/zen/go/v1/usage"

    /** Command Code 用量 Host 是 api.commandcode.ai；commandcode.ai 会返回 404 HTML。 */
    private const val CMD_BASE = "https://api.commandcode.ai"

    suspend fun fetchOpenCodeGo(key: String): ProviderSnapshot = withContext(Dispatchers.IO) {
        try {
            UsageParser.openCode(JSONObject(get(OPENCODE_URL, key)))
        } catch (t: Throwable) {
            ProviderSnapshot(ok = false, error = describe(t, Kind.OPENCODE))
        }
    }

    suspend fun fetchCommandCode(key: String): ProviderSnapshot = withContext(Dispatchers.IO) {
        try {
            val credits = JSONObject(get("$CMD_BASE/alpha/billing/credits", key))
            // 订阅接口只用来拿套餐 ID 与账单周期，失败不影响主用量展示。
            val subscription = runCatching {
                JSONObject(get("$CMD_BASE/alpha/billing/subscriptions", key)).optJSONObject("data")
            }.getOrNull()
            UsageParser.commandCode(credits, subscription)
        } catch (t: Throwable) {
            ProviderSnapshot(ok = false, error = describe(t, Kind.COMMAND_CODE))
        }
    }

    /** 智谱 GLM Coding Plan 用量（国内站 / 国际站同一 shape）。 */
    suspend fun fetchZhipu(key: String, host: ZhipuHost): ProviderSnapshot = withContext(Dispatchers.IO) {
        try {
            val body = get(
                url = "${host.base}/api/monitor/usage/quota/limit",
                key = key,
                bearer = false,
                extraHeaders = mapOf(
                    "Accept-Language" to "en-US,en",
                    "Content-Type" to "application/json",
                ),
            )
            UsageParser.zhipu(JSONObject(body), host.label)
        } catch (t: Throwable) {
            ProviderSnapshot(ok = false, error = describe(t, Kind.ZHIPU))
        }
    }

    private fun get(
        url: String,
        key: String,
        bearer: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", if (bearer) "Bearer $key" else key)
            setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("User-Agent", UA)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, text.trim())
            text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun describe(t: Throwable, kind: Kind): String = when {
        t is HttpError && t.code == 401 && kind == Kind.ZHIPU ->
            "API Key 无效，或该 key 未开通 Coding Plan（HTTP 401）"
        t is HttpError && t.code == 401 -> "API Key 无效（HTTP 401）"
        t is HttpError && t.code == 403 && kind == Kind.ZHIPU ->
            "无权访问用量接口（HTTP 403）：确认 key 属于该站点的 Coding Plan"
        t is HttpError && t.code == 403 && kind == Kind.COMMAND_CODE -> "无权访问用量接口（HTTP 403）"
        t is HttpError && t.code == 403 -> "Key 有效，但该 workspace 没有 Go 订阅（HTTP 403）"
        t is HttpError && t.code == 404 && kind == Kind.COMMAND_CODE ->
            "接口不存在（HTTP 404）：用量在 api.commandcode.ai，不是 commandcode.ai"
        t is HttpError && t.code == 404 -> "接口不存在（HTTP 404），上游路由可能已变更"
        t is HttpError -> "HTTP ${t.code}：${t.body.take(160).ifBlank { "响应无正文" }}"
        t.message.isNullOrBlank() -> "请求失败：${t.javaClass.simpleName}"
        else -> "请求失败：${t.message!!.take(160)}"
    }
}
