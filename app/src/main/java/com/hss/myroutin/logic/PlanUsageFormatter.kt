package com.hss.myroutin.logic

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 说明：统一处理订阅 Key 脱敏、额度数字和服务端周期时间格式，避免展示层与日志层各自维护规则。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object PlanUsageFormatter {

    /**
     * 隐藏长期凭证中段，页面展示和 Debug 日志必须复用同一规则，禁止输出完整 Key。
     * @param apiKey 用户保存的完整订阅 Key
     */
    fun maskKey(apiKey: String): String {
        return if (apiKey.length <= MASK_KEY_SHORT_LENGTH) {
            "${apiKey.take(4)}****"
        } else {
            "${apiKey.take(9)}****${apiKey.takeLast(6)}"
        }
    }

    /**
     * 将服务端 ISO 时间固定展示为北京时间，缺失或解析失败时返回占位符。
     * @param serverTime 服务端返回的 ISO 时间
     */
    fun formatBeijingTime(serverTime: String?): String {
        val timeMillis = parseServerTimeMillis(serverTime) ?: return EMPTY_VALUE_PLACEHOLDER
        return formatBeijingDate(Date(timeMillis))
    }

    /**
     * 将本地缓存时间按北京时间展示，与服务端周期时间保持同一种阅读习惯。
     * @param timeMillis 本地时间戳，单位为毫秒
     */
    fun formatLocalTime(timeMillis: Long): String {
        return formatBeijingDate(Date(timeMillis))
    }

    /**
     * 根据服务端窗口跨度生成周期名称，旧缓存缺少有效时间时保留调用方提供的名称。
     * @param windowStartAt 服务端窗口开始时间
     * @param windowEndAt 服务端窗口结束时间
     * @param fallbackLabel 无法确定窗口跨度时的保守展示名称
     */
    fun resolveWindowLabel(
        windowStartAt: String?,
        windowEndAt: String?,
        fallbackLabel: String
    ): String {
        val startTime = parseServerTimeMillis(windowStartAt) ?: return fallbackLabel
        val endTime = parseServerTimeMillis(windowEndAt) ?: return fallbackLabel
        val durationMinutes = (endTime - startTime) / MILLIS_PER_MINUTE
        if (durationMinutes <= 0L) {
            return fallbackLabel
        }
        return when (durationMinutes) {
            MINUTES_PER_DAY -> "日"
            MINUTES_PER_WEEK -> "周"
            else -> when {
                durationMinutes % MINUTES_PER_DAY == 0L -> "${durationMinutes / MINUTES_PER_DAY}天"
                durationMinutes % MINUTES_PER_HOUR == 0L -> "${durationMinutes / MINUTES_PER_HOUR}小时"
                else -> "${durationMinutes}分钟"
            }
        }
    }

    /** 将 0 到 1 的已用比例转换为页面百分比。 */
    fun formatPercent(usedRate: Double?): String {
        return usedRate?.let { "${DecimalFormat(PERCENT_PATTERN).format(it * 100)}%" }
            ?: EMPTY_VALUE_PLACEHOLDER
    }

    /** 将美元额度转换为页面金额，空值不误显示为零。 */
    fun formatUsd(value: Double?): String {
        return value?.let { "${'$'}${formatDecimal(it)}" } ?: EMPTY_VALUE_PLACEHOLDER
    }

    /** 将 token 数量增加千分位分隔，空值保留占位符。 */
    fun formatToken(value: Long?): String {
        return value?.let { DecimalFormat(TOKEN_PATTERN).format(it) } ?: EMPTY_VALUE_PLACEHOLDER
    }

    /** 分组倍率与金额共用最多两位小数的展示精度。 */
    fun formatDecimal(value: Double): String {
        return DecimalFormat(DECIMAL_PATTERN).format(value)
    }

    /**
     * 将服务端 ISO 时间转换为毫秒时间戳，供到期判断和页面格式化复用同一解析规则。
     * @param serverTime 服务端返回的 ISO 时间
     */
    fun parseServerTimeMillis(serverTime: String?): Long? {
        if (serverTime.isNullOrBlank()) {
            return null
        }
        ISO_DATE_PATTERNS.forEach { pattern ->
            val dateFormat = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = UTC_TIME_ZONE
            }
            runCatching { dateFormat.parse(serverTime) }.getOrNull()?.let { date ->
                return date.time
            }
        }
        return null
    }

    /** 北京时间格式器不跨调用共享，保证纯逻辑可安全用于不同线程。 */
    private fun formatBeijingDate(date: Date): String {
        val dateFormat = SimpleDateFormat(BEIJING_TIME_PATTERN, Locale.CHINA).apply {
            timeZone = BEIJING_TIME_ZONE
        }
        return "${dateFormat.format(date)} 北京时间"
    }

    private const val MASK_KEY_SHORT_LENGTH = 15
    private const val EMPTY_VALUE_PLACEHOLDER = "--"
    private const val DECIMAL_PATTERN = "0.##"
    private const val PERCENT_PATTERN = "0.#"
    private const val TOKEN_PATTERN = "#,###"
    private const val BEIJING_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
    private const val MINUTES_PER_WEEK = 7L * MINUTES_PER_DAY

    /** 服务端当前存在带毫秒和不带毫秒两种 ISO 时间格式。 */
    private val ISO_DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX"
    )
    private val UTC_TIME_ZONE = TimeZone.getTimeZone("UTC")
    private val BEIJING_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai")
}
