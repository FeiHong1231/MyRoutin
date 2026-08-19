package com.hss.myroutin.logic

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
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
     * @param includeZoneLabel 是否在时间后附加“北京时间”文字
     */
    fun formatBeijingTime(serverTime: String?, includeZoneLabel: Boolean = true): String {
        val timeMillis = parseServerTimeMillis(serverTime) ?: return EMPTY_VALUE_PLACEHOLDER
        return formatBeijingDate(Date(timeMillis), includeZoneLabel)
    }

    /**
     * 将本地缓存时间按北京时间展示，与服务端周期时间保持同一种阅读习惯。
     * @param timeMillis 本地时间戳，单位为毫秒
     * @param includeZoneLabel 是否在时间后附加“北京时间”文字
     */
    fun formatLocalTime(timeMillis: Long, includeZoneLabel: Boolean = true): String {
        return formatBeijingDate(Date(timeMillis), includeZoneLabel)
    }

    /**
     * 将套餐到期时间转换为适合卡片告警的剩余时长；不足一天显示小时，不足一小时显示分钟。
     * @param serverTime 服务端返回的套餐到期时间
     * @param nowMillis 当前时间，默认使用设备当前时间，测试时可传入固定值
     * @return 可展示的剩余时长；时间缺失或无法解析时返回 null
     */
    fun resolveExpiryCountdown(
        serverTime: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): ExpiryCountdown? {
        val endTimeMillis = parseServerTimeMillis(serverTime) ?: return null
        val remainingMillis = endTimeMillis - nowMillis
        if (remainingMillis <= 0L) {
            return ExpiryCountdown.Expired
        }
        return when {
            remainingMillis >= MILLIS_PER_DAY -> ExpiryCountdown.Remaining(
                amount = remainingMillis / MILLIS_PER_DAY,
                unit = ExpiryUnit.DAY
            )
            remainingMillis >= MILLIS_PER_HOUR -> ExpiryCountdown.Remaining(
                amount = remainingMillis / MILLIS_PER_HOUR,
                unit = ExpiryUnit.HOUR
            )
            else -> ExpiryCountdown.Remaining(
                amount = (remainingMillis / MILLIS_PER_MINUTE).coerceAtLeast(1L),
                unit = ExpiryUnit.MINUTE
            )
        }
    }

    /**
     * 判断套餐是否进入八天内的到期提醒窗口；已到期或时间缺失均不属于未来提醒。
     * @param serverTime 服务端返回的套餐到期时间
     * @param nowMillis 当前时间，默认使用设备当前时间，测试时可传入固定值
     */
    fun isExpiryWithinWarningWindow(
        serverTime: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val endTimeMillis = parseServerTimeMillis(serverTime) ?: return false
        val remainingMillis = endTimeMillis - nowMillis
        return remainingMillis > 0L && remainingMillis <= EXPIRY_WARNING_WINDOW_MILLIS
    }

    /**
     * 根据周窗口结束时间解析北京时间对应的星期，用于说明周期额度的固定重置日。
     * @param serverTime 服务端返回的周窗口结束时间
     * @return 中文星期简称；时间缺失或解析失败时返回 null
     */
    fun resolveBeijingWeekday(serverTime: String?): String? {
        val timeMillis = parseServerTimeMillis(serverTime) ?: return null
        val calendar = Calendar.getInstance(BEIJING_TIME_ZONE, Locale.CHINA).apply {
            timeInMillis = timeMillis
        }
        return BEIJING_WEEKDAY_LABELS.getOrNull(calendar.get(Calendar.DAY_OF_WEEK))
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

    /** 将模型雷达的 Token 数量转换为百万单位，避免大数挤占效率卡片空间。 */
    fun formatTokenInMillions(value: Long?): String {
        return value?.let {
            "${DecimalFormat(TOKEN_MILLION_PATTERN).format(it / TOKENS_PER_MILLION)}M"
        } ?: EMPTY_VALUE_PLACEHOLDER
    }

    /**
     * 将 CodexRadar 的英文推理档位转换为中英文并列标签，统一模型卡和效率卡的阅读口径。
     * @param effort 服务端返回的推理档位标识
     * @return 可直接展示的中英文档位标签，未知档位保留原始值
     */
    fun formatEffortLabel(effort: String?): String {
        val normalizedEffort = effort?.trim()?.lowercase(Locale.US) ?: return ""
        return when (normalizedEffort) {
            "low" -> "low 轻度"
            "medium" -> "medium 中"
            "high" -> "high 高"
            "xhigh" -> "xhigh 极高"
            "max" -> "max 最高"
            "ultra" -> "ultra 极高（更快消耗）"
            else -> effort.orEmpty()
        }
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
    private fun formatBeijingDate(date: Date, includeZoneLabel: Boolean): String {
        val dateFormat = SimpleDateFormat(BEIJING_TIME_PATTERN, Locale.CHINA).apply {
            timeZone = BEIJING_TIME_ZONE
        }
        val formattedDate = dateFormat.format(date)
        return if (includeZoneLabel) "$formattedDate 北京时间" else formattedDate
    }

    /** 到期倒计时的单位，供展示层选择对应的本地化文案。 */
    enum class ExpiryUnit {
        DAY,
        HOUR,
        MINUTE
    }

    /** 套餐到期倒计时结果，区分已到期和仍有剩余时间。 */
    sealed interface ExpiryCountdown {
        /** 服务端到期时间已早于当前时间。 */
        object Expired : ExpiryCountdown

        /** 套餐仍有效，amount 使用 unit 对应的整数单位。 */
        data class Remaining(
            val amount: Long,
            val unit: ExpiryUnit
        ) : ExpiryCountdown
    }

    private const val MASK_KEY_SHORT_LENGTH = 15
    private const val EMPTY_VALUE_PLACEHOLDER = "--"
    private const val DECIMAL_PATTERN = "0.##"
    private const val PERCENT_PATTERN = "0.#"
    private const val TOKEN_PATTERN = "#,###"
    private const val TOKEN_MILLION_PATTERN = "0.##"
    private const val TOKENS_PER_MILLION = 1_000_000.0
    private const val BEIJING_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
    private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR
    private const val EXPIRY_WARNING_WINDOW_MILLIS = 8L * MILLIS_PER_DAY
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
    private const val MINUTES_PER_WEEK = 7L * MINUTES_PER_DAY

    /** Calendar.DAY_OF_WEEK 以周日为 1，按该顺序映射中文星期简称。 */
    private val BEIJING_WEEKDAY_LABELS = listOf("", "日", "一", "二", "三", "四", "五", "六")

    /** 服务端当前存在带毫秒和不带毫秒两种 ISO 时间格式。 */
    private val ISO_DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX"
    )
    private val UTC_TIME_ZONE = TimeZone.getTimeZone("UTC")
    private val BEIJING_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai")
}
