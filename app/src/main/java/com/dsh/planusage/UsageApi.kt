package com.dsh.planusage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 非 2xx 响应，携带状态码便于给出中文提示。 */
private class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

/**
 * 网络层：只负责发请求 + 把 HTTP 失败翻译成中文提示，解析全部交给 [UsageParser]。
 *
 * 两个端点都只认 `Authorization: Bearer`（OpenCode Go 用 `x-api-key` 会 403）。
 */
internal object UsageApi {

    private const val TIMEOUT_MS = 20_000
    private const val UA = "PlanUsage/1.0 (Android)"

    /** OpenCode Go 用量端点。 */
    private const val OPENCODE_URL = "https://opencode.ai/zen/go/v1/usage"

    /** Command Code 用量 Host 是 api.commandcode.ai；commandcode.ai 会返回 404 HTML。 */
    private const val CMD_BASE = "https://api.commandcode.ai"

    suspend fun fetchOpenCodeGo(key: String): ProviderSnapshot = withContext(Dispatchers.IO) {
        try {
            UsageParser.openCode(JSONObject(get(OPENCODE_URL, key)))
        } catch (t: Throwable) {
            ProviderSnapshot(ok = false, error = describe(t, isCommandCode = false))
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
            ProviderSnapshot(ok = false, error = describe(t, isCommandCode = true))
        }
    }

    private fun get(url: String, key: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
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

    private fun describe(t: Throwable, isCommandCode: Boolean): String = when {
        t is HttpError && t.code == 401 -> "API Key 无效（HTTP 401）"
        t is HttpError && t.code == 403 && isCommandCode -> "无权访问用量接口（HTTP 403）"
        t is HttpError && t.code == 403 -> "Key 有效，但该 workspace 没有 Go 订阅（HTTP 403）"
        t is HttpError && t.code == 404 && isCommandCode ->
            "接口不存在（HTTP 404）：用量在 api.commandcode.ai，不是 commandcode.ai"
        t is HttpError && t.code == 404 -> "接口不存在（HTTP 404），上游路由可能已变更"
        t is HttpError -> "HTTP ${t.code}：${t.body.take(160).ifBlank { "响应无正文" }}"
        t.message.isNullOrBlank() -> "请求失败：${t.javaClass.simpleName}"
        else -> "请求失败：${t.message!!.take(160)}"
    }
}
