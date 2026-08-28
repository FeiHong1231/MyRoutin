package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.WeeklyResetStats
import kotlin.math.abs

/**
 * 说明：根据刷新前后的周额度差值估算提前 Reset 额度，排除自然周结算和套餐变更。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal object WeeklyResetQuotaCalculator {
    /**
     * 首次快照只建立基线；只有同一窗口内的有效额度恢复才累加。
     * @param previousUsage 刷新前的最后有效额度快照
     * @param previousStats 当前 Key 已保存的 Reset 统计
     * @param currentUsage 刷新后得到的新额度快照
     * @param observedAt 本次快照的观测时间
     */
    fun update(
        previousUsage: PlanUsageSnapshot?,
        previousStats: WeeklyResetStats?,
        currentUsage: PlanUsageSnapshot,
        observedAt: Long
    ): WeeklyResetStats? {
        val currentWindow = Window(currentUsage.weekWindowStartAt, currentUsage.weekWindowEndAt)
        if (!currentWindow.isValid()) return previousStats
        val stats = previousStats
            ?.takeIf { it.windowStartAt == currentWindow.startAt && it.windowEndAt == currentWindow.endAt }
            ?: WeeklyResetStats(
                windowStartAt = currentWindow.startAt,
                windowEndAt = currentWindow.endAt,
                totalRestoredUsd = previousStats?.totalRestoredUsd ?: previousStats?.restoredUsd ?: 0.0,
                resetCount = 0,
                totalResetCount = previousStats?.totalResetCount ?: 0
            )
        val before = previousUsage?.let(::values) ?: return stats
        val after = values(currentUsage) ?: return stats
        val oldEnd = PlanUsageFormatter.parseServerTimeMillis(previousUsage.weekWindowEndAt) ?: return stats
        if (observedAt >= oldEnd - NATURAL_RESET_GUARD_MILLIS) return stats
        val usedDrop = before.used - after.used
        val remainingRise = after.remaining - before.remaining
        val minimum = maxOf(MIN_GAIN_USD, before.limit * MIN_GAIN_RATE)
        if (!close(before.limit, after.limit, LIMIT_TOLERANCE) ||
            !close(usedDrop, remainingRise, BALANCE_TOLERANCE) ||
            usedDrop < minimum || remainingRise < minimum
        ) return stats
        return stats.copy(
            restoredUsd = stats.restoredUsd + minOf(usedDrop, remainingRise),
            totalRestoredUsd = stats.totalRestoredUsd + minOf(usedDrop, remainingRise),
            resetCount = stats.resetCount + 1,
            totalResetCount = stats.totalResetCount + 1,
            lastObservedAt = observedAt
        )
    }

    private fun values(usage: PlanUsageSnapshot): Values? {
        val limit = usage.weeklyLimitUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val used = usage.weeklyUsedUsd?.takeIf { it.isFinite() && it >= 0.0 }
            ?: usage.weeklyRemainingUsd?.let { limit - it } ?: return null
        val remaining = usage.weeklyRemainingUsd?.takeIf { it.isFinite() && it >= 0.0 }
            ?: (limit - used)
        if (!remaining.isFinite() || remaining < 0.0) return null
        return Values(limit, used.coerceIn(0.0, limit), remaining.coerceIn(0.0, limit))
    }

    private fun close(a: Double, b: Double, tolerance: Double): Boolean {
        return abs(a - b) <= maxOf(1.0, abs(a), abs(b)) * tolerance
    }

    private data class Window(val startAt: String?, val endAt: String?) {
        fun isValid(): Boolean {
            val start = startAt?.let(PlanUsageFormatter::parseServerTimeMillis)
            val end = endAt?.let(PlanUsageFormatter::parseServerTimeMillis)
            return start != null && end != null && end > start
        }
    }

    private data class Values(val limit: Double, val used: Double, val remaining: Double)
    private const val MIN_GAIN_USD = 0.01
    private const val MIN_GAIN_RATE = 0.01
    private const val LIMIT_TOLERANCE = 0.01
    private const val BALANCE_TOLERANCE = 0.02
    private const val NATURAL_RESET_GUARD_MILLIS = 5 * 60 * 1000L
}
