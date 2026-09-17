package com.dsh.planusage

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 金额统一 $x.xx。 */
fun money(value: Double): String = "$" + String.format(Locale.US, "%.2f", value)

fun percentText(percent: Double?): String =
    if (percent == null) "--" else String.format(Locale.US, "%.0f%%", percent)

/** 相对倒计时：手机端只保留最大的两个时间单位，够看且不占行宽。 */
fun countdown(resetsAtMs: Long, now: Long = System.currentTimeMillis()): String {
    if (resetsAtMs <= now) return "即将重置"
    val leftMinutes = (resetsAtMs - now) / 60_000
    val days = leftMinutes / 1440
    val hours = (leftMinutes % 1440) / 60
    val minutes = leftMinutes % 60
    return when {
        days > 0 -> "${days}天${hours}小时后重置"
        hours > 0 -> "${hours}小时${minutes}分后重置"
        minutes > 0 -> "${minutes}分钟后重置"
        else -> "不到 1 分钟后重置"
    }
}

private val STAMP = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

fun absoluteTime(epochMs: Long): String =
    STAMP.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun clockTime(epochMs: Long): String =
    CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
