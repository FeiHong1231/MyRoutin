package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageSnapshot

/**
 * 说明：集中计算订阅周期额度、资源包 token 比例和分组倍率趋势，不依赖 Android View 或资源。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object PlanUsageQuotaCalculator {

    /**
     * 根据周窗口累计用量和短窗口用量估算周额度的耗尽时间，结果不跨越服务端重置边界。
     * @param usage 当前 Key 的完整额度快照
     * @param sampleAtMillis 快照实际成功获取的时间，不能使用卡片绑定时间代替
     * @param nowMillis 当前时间，测试时传入固定值
     */
    fun estimateWeeklyExhaustion(
        usage: PlanUsageSnapshot,
        sampleAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): WeeklyExhaustionResult {
        val weekQuota = resolveQuotaValues(
            usedUsd = usage.weeklyUsedUsd,
            limitUsd = usage.weeklyLimitUsd,
            remainingUsd = usage.weeklyRemainingUsd
        ) ?: return WeeklyExhaustionResult.Insufficient(InsufficientReason.INVALID_DATA)
        if (weekQuota.remainingUsd <= 0.0) {
            return WeeklyExhaustionResult.Exhausted
        }
        if (sampleAtMillis == null || sampleAtMillis > nowMillis) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.INVALID_DATA)
        }

        val weekStartMillis = PlanUsageFormatter.parseServerTimeMillis(usage.weekWindowStartAt)
        val weekEndMillis = PlanUsageFormatter.parseServerTimeMillis(usage.weekWindowEndAt)
        if (weekStartMillis == null || weekEndMillis == null || weekEndMillis <= weekStartMillis) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.INVALID_DATA)
        }
        if (nowMillis >= weekEndMillis || nowMillis < weekStartMillis) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.INVALID_DATA)
        }

        val weekElapsedHours = resolveElapsedHours(
            windowStartMillis = weekStartMillis,
            windowEndMillis = weekEndMillis,
            sampleAtMillis = sampleAtMillis
        ) ?: return WeeklyExhaustionResult.Insufficient(InsufficientReason.INVALID_DATA)
        val weeklySpeed = if (weekElapsedHours > 0.0) {
            weekQuota.usedUsd / weekElapsedHours
        } else {
            0.0
        }
        val weeklyUsedRate = weekQuota.usedUsd / weekQuota.limitUsd

        val shortQuota = resolveQuotaValues(
            usedUsd = usage.dailyUsedUsd,
            limitUsd = usage.dailyLimitUsd,
            remainingUsd = usage.dailyRemainingUsd
        )
        val shortWindow = resolveShortWindow(usage, sampleAtMillis, shortQuota)
        val shortUsedRate = shortQuota?.let { it.usedUsd / it.limitUsd } ?: 0.0
        val shortWindowIsUsable = shortWindow != null &&
            shortWindow.elapsedHours >= MIN_SHORT_WINDOW_HOURS &&
            shortUsedRate >= MIN_SHORT_USED_RATE

        if (weekQuota.usedUsd <= 0.0 && (shortQuota?.usedUsd ?: 0.0) <= 0.0) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.NO_USAGE)
        }
        if (
            weekElapsedHours < MIN_WEEK_OBSERVATION_HOURS &&
            weeklyUsedRate < MIN_WEEK_USED_RATE &&
            shortUsedRate < MIN_SHORT_USED_RATE
        ) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.LOW_USAGE)
        }

        val shortSpeed = shortWindow?.let { window ->
            if (window.elapsedHours > 0.0) {
                (shortQuota?.usedUsd ?: 0.0) / window.elapsedHours
            } else {
                0.0
            }
        } ?: 0.0
        val shortWeight = if (shortWindowIsUsable && shortSpeed > 0.0) {
            resolveShortWindowWeight(shortUsedRate)
        } else {
            0.0
        }
        val effectiveSpeed = when {
            weekElapsedHours < MIN_WEEK_OBSERVATION_HOURS && shortWindowIsUsable -> shortSpeed
            weeklySpeed > 0.0 && shortWeight > 0.0 -> {
                val boundedShortSpeed = shortSpeed.coerceIn(
                    weeklySpeed * SHORT_SPEED_MIN_FACTOR,
                    weeklySpeed * SHORT_SPEED_MAX_FACTOR
                )
                weeklySpeed * (1.0 - shortWeight) + boundedShortSpeed * shortWeight
            }
            weeklySpeed > 0.0 -> weeklySpeed
            shortWindowIsUsable -> shortSpeed
            else -> 0.0
        }
        if (effectiveSpeed <= 0.0 || !effectiveSpeed.isFinite()) {
            return WeeklyExhaustionResult.Insufficient(InsufficientReason.LOW_USAGE)
        }

        val hoursUntilReset = (weekEndMillis - nowMillis).toDouble() / MILLIS_PER_HOUR
        val confidence = resolveConfidence(
            weekElapsedHours = weekElapsedHours,
            shortWindowIsUsable = shortWindowIsUsable,
            shortWeight = shortWeight
        )
        if (hoursUntilReset <= NEAR_RESET_HOURS) {
            return WeeklyExhaustionResult.NearReset(
                effectiveSpeedUsdPerHour = effectiveSpeed,
                hoursUntilReset = hoursUntilReset,
                confidence = confidence
            )
        }

        val hoursUntilExhaustion = weekQuota.remainingUsd / effectiveSpeed
        return if (hoursUntilExhaustion <= hoursUntilReset) {
            WeeklyExhaustionResult.WillExhaust(
                effectiveSpeedUsdPerHour = effectiveSpeed,
                hoursUntilExhaustion = hoursUntilExhaustion,
                exhaustionAtMillis = nowMillis + (hoursUntilExhaustion * MILLIS_PER_HOUR).toLong(),
                confidence = confidence
            )
        } else {
            WeeklyExhaustionResult.WillSurviveReset(
                effectiveSpeedUsdPerHour = effectiveSpeed,
                hoursUntilReset = hoursUntilReset,
                confidence = confidence
            )
        }
    }

    /**
     * 汇总周期额度的展示已用值、已用比例和预警状态。
     * @param usedUsd 服务端已用金额
     * @param limitUsd 服务端总额度
     * @param remainingUsd 服务端剩余额度
     */
    fun calculateCycleQuota(
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ): CycleQuotaResult {
        val displayUsedUsd = resolveDisplayUsedUsd(usedUsd, limitUsd, remainingUsd)
        val usedRate = calculateUsedRate(displayUsedUsd, limitUsd)
        val isExhausted = limitUsd != null && limitUsd > 0.0 &&
            remainingUsd != null && remainingUsd <= 0.0
        return CycleQuotaResult(
            displayUsedUsd = displayUsedUsd,
            usedRate = usedRate,
            isWarning = isExhausted || isOverWarningThreshold(usedRate)
        )
    }

    /**
     * 从资源包快照中汇总 token 总量、已用比例和预警状态，避免调用方拆传同一 Bean 的字段。
     * @param usage 当前 Key 返回的完整用量快照
     */
    fun calculateTokenQuota(usage: PlanUsageSnapshot): TokenQuotaResult {
        val declaredTotalTokens = usage.totalTokens
        val consumedTokens = usage.consumedTokens
        val remainingTokens = usage.remainingTokens
        val totalTokens = when {
            declaredTotalTokens != null && declaredTotalTokens > 0L -> declaredTotalTokens
            consumedTokens != null && remainingTokens != null -> consumedTokens + remainingTokens
            else -> declaredTotalTokens
        }
        val usedRate = if (consumedTokens == null || totalTokens == null || totalTokens <= 0L) {
            null
        } else {
            (consumedTokens.toDouble() / totalTokens.toDouble()).coerceIn(0.0, 1.0)
        }
        return TokenQuotaResult(
            totalTokens = totalTokens,
            usedRate = usedRate,
            isWarning = isOverWarningThreshold(usedRate)
        )
    }

    /**
     * 比较接口倍率与套餐默认倍率，仅返回展示层需要的高低趋势。
     * @param groupId 服务端返回的分组 ID
     * @param groupName 服务端返回的分组名称
     * @param multiplier 当前 Key 对应的分组倍率
     */
    fun resolveGroupMultiplierTrend(
        groupId: String,
        groupName: String,
        multiplier: Double?
    ): GroupMultiplierTrend? {
        val defaultMultiplier = when {
            groupId == GROUP_ID_CODEX_PRO || groupName == GROUP_NAME_CODEX_PRO ->
                DEFAULT_CODEX_PRO_GROUP_MULTIPLIER
            groupId == GROUP_ID_CODEX || groupName == GROUP_NAME_CODEX ->
                DEFAULT_CODEX_GROUP_MULTIPLIER
            else -> return null
        }
        return when {
            multiplier == null || multiplier == defaultMultiplier -> null
            multiplier < defaultMultiplier -> GroupMultiplierTrend.LOWER
            else -> GroupMultiplierTrend.HIGHER
        }
    }

    /** 将接口的总量、已用和剩余字段补齐为可计算的非负额度。 */
    private fun resolveQuotaValues(
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ): QuotaValues? {
        val validUsed = usedUsd?.takeIf { it.isFinite() && it >= 0.0 }
        val validLimit = limitUsd?.takeIf { it.isFinite() && it > 0.0 }
        val validRemaining = remainingUsd?.takeIf { it.isFinite() && it >= 0.0 }
        val resolvedLimit = validLimit ?: if (validUsed != null && validRemaining != null) {
            validUsed + validRemaining
        } else {
            null
        }
        if (resolvedLimit == null || !resolvedLimit.isFinite() || resolvedLimit <= 0.0) {
            return null
        }
        val resolvedUsed = (validUsed ?: (resolvedLimit - (validRemaining ?: 0.0)))
            .coerceIn(0.0, resolvedLimit)
        val resolvedRemaining = (validRemaining ?: (resolvedLimit - resolvedUsed))
            .coerceIn(0.0, resolvedLimit)
        return QuotaValues(
            usedUsd = resolvedUsed,
            limitUsd = resolvedLimit,
            remainingUsd = resolvedRemaining
        )
    }

    /** 以快照时间为观测点，避免用当前时间把缓存快照的观察周期拉长。 */
    private fun resolveElapsedHours(
        windowStartMillis: Long,
        windowEndMillis: Long,
        sampleAtMillis: Long
    ): Double? {
        val windowDurationMillis = windowEndMillis - windowStartMillis
        if (windowDurationMillis <= 0L) {
            return null
        }
        val elapsedMillis = (sampleAtMillis - windowStartMillis)
            .coerceIn(0L, windowDurationMillis)
        return elapsedMillis.toDouble() / MILLIS_PER_HOUR
    }

    /** 解析短窗口实际跨度；窗口名称不能替代服务端返回的起止时间。 */
    private fun resolveShortWindow(
        usage: PlanUsageSnapshot,
        sampleAtMillis: Long,
        shortQuota: QuotaValues?
    ): ShortWindow? {
        if (shortQuota == null) {
            return null
        }
        val startMillis = PlanUsageFormatter.parseServerTimeMillis(usage.dayWindowStartAt)
            ?: return null
        val endMillis = PlanUsageFormatter.parseServerTimeMillis(usage.dayWindowEndAt)
            ?: return null
        val elapsedHours = resolveElapsedHours(startMillis, endMillis, sampleAtMillis)
            ?: return null
        return ShortWindow(elapsedHours)
    }

    /** 将短窗口使用比例映射为 0 到 40% 的修正权重。 */
    private fun resolveShortWindowWeight(shortUsedRate: Double): Double {
        if (shortUsedRate < MIN_SHORT_USED_RATE) {
            return 0.0
        }
        if (shortUsedRate >= SHORT_WEIGHT_FULL_RATE) {
            return MAX_SHORT_WEIGHT
        }
        return ((shortUsedRate - MIN_SHORT_USED_RATE) /
            (SHORT_WEIGHT_FULL_RATE - MIN_SHORT_USED_RATE) * MAX_SHORT_WEIGHT)
            .coerceIn(0.0, MAX_SHORT_WEIGHT)
    }

    /** 周窗口样本越充分，且短窗口有有效观测，预测可信度越高。 */
    private fun resolveConfidence(
        weekElapsedHours: Double,
        shortWindowIsUsable: Boolean,
        shortWeight: Double
    ): EstimateConfidence {
        return when {
            weekElapsedHours < MIN_WEEK_OBSERVATION_HOURS -> EstimateConfidence.LOW
            weekElapsedHours >= HIGH_CONFIDENCE_WEEK_HOURS && shortWindowIsUsable && shortWeight > 0.0 ->
                EstimateConfidence.HIGH
            else -> EstimateConfidence.MEDIUM
        }
    }

    /** 周期接口只给剩余额度时，用总额度减剩余额度补出展示已用值。 */
    private fun resolveDisplayUsedUsd(
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ): Double? {
        if (usedUsd != null) {
            return usedUsd
        }
        if (limitUsd != null && remainingUsd != null && limitUsd > 0.0) {
            return (limitUsd - remainingUsd).coerceIn(0.0, limitUsd)
        }
        return usedUsd
    }

    /** 非正总额度没有有效比例，有效比例统一限制在 0 到 1。 */
    private fun calculateUsedRate(usedUsd: Double?, limitUsd: Double?): Double? {
        if (usedUsd == null || limitUsd == null || limitUsd <= 0.0) {
            return null
        }
        return (usedUsd / limitUsd).coerceIn(0.0, 1.0)
    }

    /** 已用比例严格超过 80% 时进入预警，保持原有边界语义。 */
    private fun isOverWarningThreshold(usedRate: Double?): Boolean {
        return usedRate != null && usedRate > PROGRESS_WARNING_THRESHOLD
    }

    /** 周期额度绑定所需的纯计算结果，剩余额度仍使用服务端原始值展示。 */
    data class CycleQuotaResult(
        val displayUsedUsd: Double?,
        val usedRate: Double?,
        val isWarning: Boolean
    )

    /** 资源包绑定所需的纯计算结果，总量可由已用和剩余 token 补齐。 */
    data class TokenQuotaResult(
        val totalTokens: Long?,
        val usedRate: Double?,
        val isWarning: Boolean
    )

    /** 分组倍率相对默认套餐倍率的方向，由 Adapter 映射为对应语义色。 */
    enum class GroupMultiplierTrend {
        LOWER,
        HIGHER
    }

    /** 周额度耗尽预测的稳定状态，展示层只负责将状态映射为文案。 */
    sealed interface WeeklyExhaustionResult {
        /** 剩余额度已为零，速度不再参与判断。 */
        object Exhausted : WeeklyExhaustionResult

        /** 观测数据不足或时间窗口无效，reason 用于选择保守文案。 */
        data class Insufficient(val reason: InsufficientReason) : WeeklyExhaustionResult

        /** 预计在周窗口重置前耗尽。 */
        data class WillExhaust(
            val effectiveSpeedUsdPerHour: Double,
            val hoursUntilExhaustion: Double,
            val exhaustionAtMillis: Long,
            val confidence: EstimateConfidence
        ) : WeeklyExhaustionResult

        /** 当前速度下，周额度会先重置。 */
        data class WillSurviveReset(
            val effectiveSpeedUsdPerHour: Double,
            val hoursUntilReset: Double,
            val confidence: EstimateConfidence
        ) : WeeklyExhaustionResult

        /** 距离重置过近，避免展示没有实际价值的耗尽预测。 */
        data class NearReset(
            val effectiveSpeedUsdPerHour: Double,
            val hoursUntilReset: Double,
            val confidence: EstimateConfidence
        ) : WeeklyExhaustionResult
    }

    /** 用于区分没有使用量、样本过少和时间/额度字段损坏。 */
    enum class InsufficientReason {
        NO_USAGE,
        LOW_USAGE,
        INVALID_DATA
    }

    /** 速度估算可信度只服务于文案降级，不改变额度计算结果。 */
    enum class EstimateConfidence {
        HIGH,
        MEDIUM,
        LOW
    }

    /** 已归一化的周期额度，确保剩余和已用都落在总量范围内。 */
    private data class QuotaValues(
        val usedUsd: Double,
        val limitUsd: Double,
        val remainingUsd: Double
    )

    /** 服务端短窗口的实际运行时长。 */
    private data class ShortWindow(
        val elapsedHours: Double
    )

    private const val PROGRESS_WARNING_THRESHOLD = 0.8
    private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
    private const val MIN_SHORT_WINDOW_HOURS = 0.5
    private const val MIN_WEEK_OBSERVATION_HOURS = 12.0
    private const val HIGH_CONFIDENCE_WEEK_HOURS = 48.0
    private const val MIN_WEEK_USED_RATE = 0.02
    private const val MIN_SHORT_USED_RATE = 0.03
    private const val SHORT_WEIGHT_FULL_RATE = 0.10
    private const val MAX_SHORT_WEIGHT = 0.40
    private const val SHORT_SPEED_MIN_FACTOR = 0.25
    private const val SHORT_SPEED_MAX_FACTOR = 4.0
    private const val NEAR_RESET_HOURS = 6.0
    private const val GROUP_ID_CODEX_PRO = "ffa027fc-8402-4b99-8db2-66eefc87325f"
    private const val GROUP_ID_CODEX = "ffa2f93c-6a1f-4bbd-a968-632ae3654465"
    private const val GROUP_NAME_CODEX_PRO = "Codex Pro"
    private const val GROUP_NAME_CODEX = "Codex"
    private const val DEFAULT_CODEX_PRO_GROUP_MULTIPLIER = 2.0
    private const val DEFAULT_CODEX_GROUP_MULTIPLIER = 1.0
}
