package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明：验证周额度速度融合、低用量降级和周重置边界，避免预测文案建立在错误时间窗口上。
 *
 * @作者 huangssh
 * @版本 5.1
 */
class PlanUsageQuotaCalculatorTest {

    @Test
    fun estimateWeeklyExhaustion_usesWeeklySpeedAndStopsAtReset() {
        val result = estimate(
            weeklyUsedUsd = 300.0,
            weeklyRemainingUsd = 200.0,
            dailyUsedUsd = 12.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z"
        )

        assertTrue(result is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillExhaust)
        val willExhaust = result as PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillExhaust
        assertTrue(willExhaust.hoursUntilExhaustion < 156.0)
        assertEquals(
            PlanUsageQuotaCalculator.EstimateConfidence.HIGH,
            willExhaust.confidence
        )
    }

    @Test
    fun estimateWeeklyExhaustion_survivesResetWhenRemainingNeedsLonger() {
        val result = estimate(
            weeklyUsedUsd = 20.0,
            weeklyRemainingUsd = 480.0,
            dailyUsedUsd = 3.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z"
        )

        assertTrue(result is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset)
    }

    @Test
    fun estimateWeeklyExhaustion_usesShortWindowAtWeekStartWithLowConfidence() {
        val result = estimate(
            weeklyUsedUsd = 30.0,
            weeklyRemainingUsd = 470.0,
            dailyUsedUsd = 10.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-01T04:00:00Z",
            now = "2026-01-01T05:00:00Z",
            dayWindowStart = "2026-01-01T00:00:00Z",
            dayWindowEnd = "2026-01-01T05:00:00Z"
        )

        assertTrue(result is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset)
        val surviveReset = result as PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset
        assertEquals(
            PlanUsageQuotaCalculator.EstimateConfidence.LOW,
            surviveReset.confidence
        )
        assertEquals(2.5, surviveReset.effectiveSpeedUsdPerHour, 0.0001)
    }

    @Test
    fun estimateWeeklyExhaustion_clampsBurstShortWindowSpeed() {
        val result = estimate(
            weeklyUsedUsd = 10.0,
            weeklyRemainingUsd = 490.0,
            dailyUsedUsd = 100.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z"
        )

        assertTrue(result is PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset)
        val surviveReset = result as PlanUsageQuotaCalculator.WeeklyExhaustionResult.WillSurviveReset
        assertTrue(surviveReset.effectiveSpeedUsdPerHour < 0.4)
        assertTrue(surviveReset.effectiveSpeedUsdPerHour > 0.2)
    }

    @Test
    fun estimateWeeklyExhaustion_rejectsLowUsageAtWeekStart() {
        val result = estimate(
            weeklyUsedUsd = 1.0,
            weeklyRemainingUsd = 499.0,
            dailyUsedUsd = 1.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-01T04:00:00Z",
            now = "2026-01-01T05:00:00Z",
            dayWindowStart = "2026-01-01T00:00:00Z",
            dayWindowEnd = "2026-01-01T05:00:00Z"
        )

        assertEquals(
            PlanUsageQuotaCalculator.WeeklyExhaustionResult.Insufficient(
                PlanUsageQuotaCalculator.InsufficientReason.LOW_USAGE
            ),
            result
        )
    }

    @Test
    fun estimateWeeklyExhaustion_distinguishesNoUsageAndExhausted() {
        val noUsage = estimate(
            weeklyUsedUsd = 0.0,
            weeklyRemainingUsd = 500.0,
            dailyUsedUsd = 0.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z"
        )
        val exhausted = estimate(
            weeklyUsedUsd = 500.0,
            weeklyRemainingUsd = 0.0,
            dailyUsedUsd = 100.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z"
        )

        assertEquals(
            PlanUsageQuotaCalculator.WeeklyExhaustionResult.Insufficient(
                PlanUsageQuotaCalculator.InsufficientReason.NO_USAGE
            ),
            noUsage
        )
        assertEquals(PlanUsageQuotaCalculator.WeeklyExhaustionResult.Exhausted, exhausted)
    }

    @Test
    fun estimateWeeklyExhaustion_returnsNearResetBeforePrediction() {
        val result = estimate(
            weeklyUsedUsd = 100.0,
            weeklyRemainingUsd = 400.0,
            dailyUsedUsd = 10.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-07T12:00:00Z",
            now = "2026-01-07T20:00:00Z"
        )

        assertTrue(result is PlanUsageQuotaCalculator.WeeklyExhaustionResult.NearReset)
    }

    @Test
    fun estimateWeeklyExhaustion_rejectsMissingWindowOrSampleTime() {
        val missingWindow = estimate(
            weeklyUsedUsd = 20.0,
            weeklyRemainingUsd = 480.0,
            dailyUsedUsd = 3.0,
            dailyLimitUsd = 100.0,
            sampleAt = "2026-01-04T00:00:00Z",
            now = "2026-01-04T12:00:00Z",
            weekWindowStart = null
        )
        val missingSample = PlanUsageQuotaCalculator.estimateWeeklyExhaustion(
            usage = createUsage(weeklyUsedUsd = 20.0, weeklyRemainingUsd = 480.0),
            sampleAtMillis = null,
            nowMillis = parse("2026-01-04T12:00:00Z")
        )

        assertEquals(
            PlanUsageQuotaCalculator.WeeklyExhaustionResult.Insufficient(
                PlanUsageQuotaCalculator.InsufficientReason.INVALID_DATA
            ),
            missingWindow
        )
        assertEquals(
            PlanUsageQuotaCalculator.WeeklyExhaustionResult.Insufficient(
                PlanUsageQuotaCalculator.InsufficientReason.INVALID_DATA
            ),
            missingSample
        )
    }

    /** 构造一个固定周窗口的快照，测试只改变额度和短窗口用量。 */
    private fun estimate(
        weeklyUsedUsd: Double,
        weeklyRemainingUsd: Double,
        dailyUsedUsd: Double,
        dailyLimitUsd: Double,
        sampleAt: String,
        now: String,
        dayWindowStart: String? = "2026-01-03T19:00:00Z",
        dayWindowEnd: String? = "2026-01-04T00:00:00Z",
        weekWindowStart: String? = "2026-01-01T00:00:00Z"
    ): PlanUsageQuotaCalculator.WeeklyExhaustionResult {
        return PlanUsageQuotaCalculator.estimateWeeklyExhaustion(
            usage = createUsage(
                weeklyUsedUsd = weeklyUsedUsd,
                weeklyRemainingUsd = weeklyRemainingUsd,
                dailyUsedUsd = dailyUsedUsd,
                dailyLimitUsd = dailyLimitUsd,
                dayWindowStartAt = dayWindowStart,
                dayWindowEndAt = dayWindowEnd,
                weekWindowStartAt = weekWindowStart
            ),
            sampleAtMillis = parse(sampleAt),
            nowMillis = parse(now)
        )
    }

    /** 创建最小完整额度快照，避免测试依赖页面绑定器或接口 JSON。 */
    private fun createUsage(
        weeklyUsedUsd: Double,
        weeklyRemainingUsd: Double,
        dailyUsedUsd: Double = 0.0,
        dailyLimitUsd: Double = 100.0,
        dayWindowStartAt: String? = "2026-01-03T19:00:00Z",
        dayWindowEndAt: String? = "2026-01-04T00:00:00Z",
        weekWindowStartAt: String? = "2026-01-01T00:00:00Z"
    ): PlanUsageSnapshot {
        return PlanUsageSnapshot(
            planName = "Test Plan",
            type = 1,
            status = 1,
            startAt = "2026-01-01T00:00:00Z",
            endAt = "2026-02-01T00:00:00Z",
            dailyLimitUsd = dailyLimitUsd,
            weeklyLimitUsd = 500.0,
            dailyUsedUsd = dailyUsedUsd,
            weeklyUsedUsd = weeklyUsedUsd,
            dailyRemainingUsd = dailyLimitUsd - dailyUsedUsd,
            weeklyRemainingUsd = weeklyRemainingUsd,
            dayWindowStartAt = dayWindowStartAt,
            dayWindowEndAt = dayWindowEndAt,
            weekWindowStartAt = weekWindowStartAt,
            weekWindowEndAt = "2026-01-08T00:00:00Z",
            totalTokens = null,
            consumedTokens = null,
            remainingTokens = null,
            allowedModels = emptyList(),
            allowedGroups = emptyList(),
            groupNames = emptyMap(),
            groupMultipliers = emptyMap()
        )
    }

    private fun parse(value: String): Long {
        return PlanUsageFormatter.parseServerTimeMillis(value)
            ?: error("invalid test time: $value")
    }
}
