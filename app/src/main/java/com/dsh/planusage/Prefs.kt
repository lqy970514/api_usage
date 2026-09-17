package com.dsh.planusage

import android.content.Context

/** 极简持久化：Key 只存本应用私有 SharedPreferences，不落日志、不外发、不联网同步。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("plan_usage", Context.MODE_PRIVATE)

    var openCodeKey: String
        get() = sp.getString(KEY_OPENCODE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_OPENCODE, value).apply()

    var commandCodeKey: String
        get() = sp.getString(KEY_COMMANDCODE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_COMMANDCODE, value).apply()

    var zhipuKey: String
        get() = sp.getString(KEY_ZHIPU, "").orEmpty()
        set(value) = sp.edit().putString(KEY_ZHIPU, value).apply()

    /** 存 [ZhipuHost] 的枚举名，读取端做 valueOf 兜底。 */
    var zhipuHost: String
        get() = sp.getString(KEY_ZHIPU_HOST, ZhipuHost.CN.name).orEmpty()
        set(value) = sp.edit().putString(KEY_ZHIPU_HOST, value).apply()

    private companion object {
        const val KEY_OPENCODE = "opencode_go_key"
        const val KEY_COMMANDCODE = "commandcode_goat_key"
        const val KEY_ZHIPU = "zhipu_glm_key"
        const val KEY_ZHIPU_HOST = "zhipu_host"
    }
}
